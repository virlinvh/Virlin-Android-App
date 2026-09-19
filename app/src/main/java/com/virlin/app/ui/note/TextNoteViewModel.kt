package com.virlin.app.ui.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.note.NoteClipboardImporter
import com.virlin.app.domain.note.NoteEnterSemantics
import com.virlin.app.domain.note.NotePlainTextSerializer
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NoteSaveStatus { Idle, Saving, Saved, Error }

data class TextNoteUiState(
    val captureId: String?,
    val noteId: String,
    val title: String,
    val blocks: List<NoteBlock>,
    val focusedBlockId: String?,
    val saveStatus: NoteSaveStatus = NoteSaveStatus.Idle,
    val toast: String? = null,
    val contextLabel: String? = null,
    val context: CaptureContext = CaptureContext.None,
    val contextPickerOpen: Boolean = false,
    val blockMenuOpen: Boolean = false,
    val styleMenuOpen: Boolean = false,
    val handleMenuBlockId: String? = null,
    val turnIntoOpen: Boolean = false,
    /** CaptureItem exists in Room (opened from Inbox or after Save to Inbox / Back commit). */
    val committedToInbox: Boolean = false,
    /** True after at least one successful Room write for a committed note (or local draft flush). */
    val persisted: Boolean = false,
    val loading: Boolean = true,
    /** Brief banner after Inbox commit (Back or Save to Inbox). */
    val inboxBanner: String? = null,
    /** After empty-block Backspace: place caret at end of this block once. */
    val cursorAtEndBlockId: String? = null
)

/**
 * Full-screen Text Note editor.
 *
 * Autosave = content protected (in-memory draft until Inbox commit; Room for committed notes).
 * Inbox commit = CaptureItem created/updated via [saveToInbox] or Back on a meaningful draft.
 */
class TextNoteViewModel(
    private val initialCaptureId: String?,
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val ids: IdProvider = VirlinGraph.ids,
    private val clock: VirlinClock = VirlinGraph.clock
) : ViewModel() {

    private var saveJob: Job? = null
    private val draftCaptureId = initialCaptureId ?: ids.newId("cap")
    private val draftNoteId = ids.newId("note")
    private val firstBlockId = ids.newId("blk")

    private val _state = MutableStateFlow(
        TextNoteUiState(
            captureId = initialCaptureId,
            noteId = draftNoteId,
            title = "",
            blocks = listOf(NoteBlock(firstBlockId, NoteBlockType.TEXT)),
            focusedBlockId = firstBlockId,
            committedToInbox = initialCaptureId != null,
            loading = initialCaptureId != null
        )
    )
    val state: StateFlow<TextNoteUiState> = _state.asStateFlow()

    init {
        if (initialCaptureId != null) {
            viewModelScope.launch { loadExisting(initialCaptureId) }
        } else {
            _state.update { it.copy(loading = false) }
            refreshContextLabel()
        }
    }

    private suspend fun loadExisting(captureId: String) {
        when (val r = actions.getOrHydrateTextNote(captureId)) {
            is ActionResult.Success -> {
                val doc = r.value
                val hasNote = actions.getNoteByCaptureId(captureId) != null
                val cap = repository.captures.value.firstOrNull { it.id == captureId }
                _state.update {
                    it.copy(
                        captureId = captureId,
                        noteId = doc.id,
                        title = doc.title.orEmpty(),
                        blocks = doc.blocks.ifEmpty { listOf(NoteBlock(ids.newId("blk"), NoteBlockType.TEXT)) },
                        focusedBlockId = doc.blocks.firstOrNull()?.id,
                        committedToInbox = cap != null,
                        persisted = hasNote || cap != null,
                        loading = false,
                        context = CaptureContext(cap?.projectId, cap?.workStreamId, cap?.taskId),
                        saveStatus = if (hasNote || cap != null) NoteSaveStatus.Saved else NoteSaveStatus.Idle
                    )
                }
                refreshContextLabel()
            }
            else -> _state.update {
                it.copy(loading = false, toast = "Could not open note", saveStatus = NoteSaveStatus.Error)
            }
        }
    }

    fun onTitleChange(title: String) {
        _state.update { it.copy(title = title) }
        scheduleAutosave()
    }

    fun onBlockTextChange(blockId: String, text: String) {
        updateBlockDeep(blockId) { it.copy(plainText = text) }
        scheduleAutosave()
    }

    /**
     * Hardware/IME Enter (not soft break). [before]/[after] are sides of the split newline.
     */
    fun handleEnter(blockId: String, before: String, after: String) {
        val st = _state.value
        val idx = st.blocks.indexOfFirst { it.id == blockId }
        if (idx >= 0) {
            val block = st.blocks[idx]
            val result = NoteEnterSemantics.applyEnter(block, before, after, ids.newId("blk"))
            _state.update { s ->
                val list = s.blocks.toMutableList()
                list[idx] = result.updatedCurrent
                result.newSibling?.let { list.add(idx + 1, it) }
                s.copy(
                    blocks = list,
                    focusedBlockId = result.newSibling?.id ?: result.updatedCurrent.id,
                    blockMenuOpen = false
                )
            }
            scheduleAutosave()
            return
        }
        // Toggle child
        for (parent in st.blocks) {
            if (parent.type != NoteBlockType.TOGGLE) continue
            val cIdx = parent.children.indexOfFirst { it.id == blockId }
            if (cIdx < 0) continue
            val child = parent.children[cIdx]
            val result = NoteEnterSemantics.applyEnter(child, before, after, ids.newId("blk"), asToggleChild = true)
            val children = parent.children.toMutableList()
            children[cIdx] = result.updatedCurrent
            result.newSibling?.let { children.add(cIdx + 1, it) }
            updateBlock(parent.id) { it.copy(children = children) }
            _state.update {
                it.copy(focusedBlockId = result.newSibling?.id ?: result.updatedCurrent.id)
            }
            scheduleAutosave()
            return
        }
    }

    fun insertSoftLineBreak(blockId: String? = _state.value.focusedBlockId, cursor: Int? = null) {
        val id = blockId ?: return
        updateBlockDeep(id) { b ->
            val at = cursor ?: b.plainText.length
            b.copy(plainText = NoteEnterSemantics.insertSoftBreak(b.plainText, at))
        }
        scheduleAutosave()
    }

    /**
     * Empty-block Backspace: remove block and focus previous (Notion-like).
     * Keeps a single empty TEXT surface if it would be the last block.
     * @return true if handled (caller should not delete characters).
     */
    fun handleBackspaceOnEmpty(blockId: String): Boolean {
        val st = _state.value
        val idx = st.blocks.indexOfFirst { it.id == blockId }
        if (idx >= 0) {
            val block = st.blocks[idx]
            if (block.plainText.isNotEmpty()) return false
            if (st.blocks.size == 1) {
                // Keep one empty editable surface.
                if (block.type != NoteBlockType.TEXT) {
                    updateBlock(blockId) {
                        it.copy(type = NoteBlockType.TEXT, checked = false, children = emptyList())
                    }
                }
                return true
            }
            val prevId = st.blocks.getOrNull(idx - 1)?.id
            val nextId = st.blocks.getOrNull(idx + 1)?.id
            val focusId = prevId ?: nextId
            _state.update { s ->
                s.copy(
                    blocks = s.blocks.filterNot { it.id == blockId },
                    focusedBlockId = focusId,
                    handleMenuBlockId = null,
                    cursorAtEndBlockId = if (prevId != null) prevId else null
                )
            }
            scheduleAutosave()
            return true
        }
        // Toggle child empty backspace
        for (parent in st.blocks) {
            if (parent.type != NoteBlockType.TOGGLE) continue
            val cIdx = parent.children.indexOfFirst { it.id == blockId }
            if (cIdx < 0) continue
            val child = parent.children[cIdx]
            if (child.plainText.isNotEmpty()) return false
            if (parent.children.size == 1) {
                updateBlock(parent.id) { it.copy(children = emptyList()) }
                _state.update { it.copy(focusedBlockId = parent.id) }
                scheduleAutosave()
                return true
            }
            val prevChild = parent.children.getOrNull(cIdx - 1)?.id
            val children = parent.children.filterNot { it.id == blockId }
            val focus = prevChild ?: children.firstOrNull()?.id
            updateBlock(parent.id) { it.copy(children = children) }
            _state.update {
                it.copy(
                    focusedBlockId = focus,
                    cursorAtEndBlockId = prevChild
                )
            }
            scheduleAutosave()
            return true
        }
        return false
    }

    fun clearCursorAtEnd() {
        _state.update { it.copy(cursorAtEndBlockId = null) }
    }

    /** Insert imported blocks at [blockId]: replace if empty, otherwise insert after. */
    fun pasteStructured(blockId: String, html: String?, plain: String) {
        val imported = NoteClipboardImporter.import(html, plain) { ids.newId("blk") }
        if (imported.isEmpty()) return
        val st = _state.value
        val idx = st.blocks.indexOfFirst { it.id == blockId }
        if (idx < 0) {
            // Paste into toggle child as text only (keep structure simple)
            val joined = imported.joinToString("\n\n") { it.plainText }
            onBlockTextChange(blockId, joined)
            return
        }
        val current = st.blocks[idx]
        val replaceEmpty = current.plainText.isEmpty() &&
            current.type != NoteBlockType.DIVIDER &&
            current.children.isEmpty()
        _state.update { s ->
            val list = s.blocks.toMutableList()
            if (replaceEmpty) {
                list.removeAt(idx)
                list.addAll(idx, imported)
            } else {
                list.addAll(idx + 1, imported)
            }
            s.copy(
                blocks = list,
                focusedBlockId = imported.last().id,
                blockMenuOpen = false,
                cursorAtEndBlockId = imported.last().id
            )
        }
        scheduleAutosave()
    }

    fun focusBlock(blockId: String?) {
        _state.update { it.copy(focusedBlockId = blockId, handleMenuBlockId = null) }
    }

    fun toggleCheckbox(blockId: String) {
        updateBlock(blockId) { it.copy(checked = !it.checked) }
        scheduleAutosave()
    }

    fun toggleCollapsed(blockId: String) {
        updateBlock(blockId) { it.copy(collapsed = !it.collapsed) }
        scheduleAutosave()
    }

    fun insertBlockAfter(type: NoteBlockType, afterId: String? = _state.value.focusedBlockId) {
        val newId = ids.newId("blk")
        val block = NoteBlock(newId, type)
        _state.update { st ->
            val list = st.blocks.toMutableList()
            val idx = afterId?.let { id -> list.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
                ?: (list.size - 1)
            list.add(idx + 1, block)
            st.copy(blocks = list, focusedBlockId = newId, blockMenuOpen = false, styleMenuOpen = false)
        }
        scheduleAutosave()
    }

    fun transformFocused(type: NoteBlockType) {
        val id = _state.value.focusedBlockId ?: return
        transformBlock(id, type)
    }

    fun transformBlock(blockId: String, type: NoteBlockType) {
        updateBlock(blockId) { b ->
            val cleared = b.plainText.removePrefix("/").trimStart()
            if (b.type == NoteBlockType.TOGGLE && type != NoteBlockType.TOGGLE && b.children.isNotEmpty()) b
            else b.copy(
                type = type,
                plainText = cleared,
                children = if (type.acceptsChildren) b.children else emptyList(),
                checked = if (type == NoteBlockType.CHECKBOX) b.checked else false
            )
        }
        _state.update {
            it.copy(
                styleMenuOpen = false,
                turnIntoOpen = false,
                handleMenuBlockId = null,
                blockMenuOpen = false,
                focusedBlockId = blockId
            )
        }
        scheduleAutosave()
    }

    fun moveBlock(blockId: String, delta: Int) {
        _state.update { st ->
            val list = st.blocks.toMutableList()
            val i = list.indexOfFirst { it.id == blockId }
            if (i < 0) return@update st
            val j = (i + delta).coerceIn(0, list.lastIndex)
            if (i == j) return@update st
            val item = list.removeAt(i)
            list.add(j, item)
            st.copy(blocks = list, handleMenuBlockId = null)
        }
        scheduleAutosave()
    }

    fun duplicateBlock(blockId: String) {
        _state.update { st ->
            val list = st.blocks.toMutableList()
            val i = list.indexOfFirst { it.id == blockId }
            if (i < 0) return@update st
            val copy = list[i].copy(
                id = ids.newId("blk"),
                children = list[i].children.map { it.copy(id = ids.newId("blk")) }
            )
            list.add(i + 1, copy)
            st.copy(blocks = list, focusedBlockId = copy.id, handleMenuBlockId = null)
        }
        scheduleAutosave()
    }

    fun deleteBlock(blockId: String) {
        _state.update { st ->
            if (st.blocks.size <= 1) {
                st.copy(
                    blocks = listOf(NoteBlock(ids.newId("blk"), NoteBlockType.TEXT)),
                    handleMenuBlockId = null
                )
            } else {
                val list = st.blocks.filterNot { it.id == blockId }
                st.copy(blocks = list, focusedBlockId = list.firstOrNull()?.id, handleMenuBlockId = null)
            }
        }
        scheduleAutosave()
    }

    fun addChildToToggle(toggleId: String) {
        val child = NoteBlock(ids.newId("blk"), NoteBlockType.TEXT)
        updateBlock(toggleId) { it.copy(children = it.children + child, collapsed = false) }
        _state.update { it.copy(focusedBlockId = child.id) }
        scheduleAutosave()
    }

    fun onChildTextChange(toggleId: String, childId: String, text: String) {
        updateBlock(toggleId) { t ->
            t.copy(children = t.children.map { if (it.id == childId) it.copy(plainText = text) else it })
        }
        scheduleAutosave()
    }

    fun openBlockMenu(open: Boolean) = _state.update { it.copy(blockMenuOpen = open, styleMenuOpen = false) }
    fun openStyleMenu(open: Boolean) = _state.update { it.copy(styleMenuOpen = open, blockMenuOpen = false) }
    fun openHandle(blockId: String?) = _state.update { it.copy(handleMenuBlockId = blockId, turnIntoOpen = false) }
    fun openTurnInto(open: Boolean) = _state.update { it.copy(turnIntoOpen = open) }
    fun openContextPicker(open: Boolean) = _state.update { it.copy(contextPickerOpen = open) }

    fun setContext(ctx: CaptureContext) {
        _state.update { it.copy(context = ctx, contextPickerOpen = false) }
        refreshContextLabel()
        val capId = _state.value.captureId
        if (capId != null && _state.value.committedToInbox) {
            viewModelScope.launch {
                actions.attachCapture(capId, ctx)
                _state.update { it.copy(toast = "Context updated", saveStatus = NoteSaveStatus.Saved) }
            }
        }
    }

    fun toggleInlineOnFocused(style: com.virlin.app.domain.model.InlineStyle) {
        val id = _state.value.focusedBlockId ?: return
        updateBlock(id) { b ->
            if (b.plainText.isEmpty()) return@updateBlock b
            val existing = b.marks.filter { it.style == style && it.start == 0 && it.end == b.plainText.length }
            if (existing.isNotEmpty()) {
                b.copy(marks = b.marks - existing.toSet())
            } else {
                b.copy(marks = b.marks + com.virlin.app.domain.model.TextMark(0, b.plainText.length, style))
            }
        }
        scheduleAutosave()
    }

    fun clearToast() = _state.update { it.copy(toast = null) }
    fun clearInboxBanner() = _state.update { it.copy(inboxBanner = null) }

    fun copyAllPlainText(): String {
        val doc = currentDocument()
        _state.update { it.copy(toast = "Copied") }
        return NotePlainTextSerializer.serialize(doc)
    }

    fun currentDocument(): NoteDocument {
        val st = _state.value
        return NoteDocument(
            id = st.noteId,
            captureItemId = st.captureId ?: draftCaptureId,
            title = st.title.ifBlank { null },
            blocks = st.blocks,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
    }

    /** Explicit Inbox commit while staying in the editor. */
    fun saveToInbox() {
        viewModelScope.launch {
            val doc = currentDocument()
            if (!doc.hasMeaningfulContent()) {
                _state.update { it.copy(toast = "Nothing to save yet") }
                return@launch
            }
            commitToInbox(showBanner = "✓ In Inbox")
        }
    }

    /**
     * Back / leave: empty → discard; meaningful uncommitted → Inbox commit; committed → flush only.
     * @return true when navigation may proceed (after optional short banner delay handled by caller).
     */
    suspend fun prepareExit(): PrepareExitResult {
        saveJob?.cancel()
        val doc = currentDocument()
        if (!doc.hasMeaningfulContent()) {
            return PrepareExitResult(navigate = true, showInboxFeedback = false)
        }
        val st = _state.value
        if (!st.committedToInbox) {
            val ok = commitToInbox(showBanner = "Saved to Inbox ✓")
            return PrepareExitResult(navigate = ok, showInboxFeedback = ok)
        }
        persistCommitted()
        return PrepareExitResult(navigate = true, showInboxFeedback = false)
    }

    data class PrepareExitResult(val navigate: Boolean, val showInboxFeedback: Boolean)

    fun archiveAndExit(onDone: () -> Unit) {
        viewModelScope.launch {
            val exit = prepareExit()
            if (!exit.navigate) return@launch
            val id = _state.value.captureId
            if (id != null && _state.value.committedToInbox) actions.archiveCapture(id)
            onDone()
        }
    }

    private suspend fun commitToInbox(showBanner: String): Boolean {
        val st = _state.value
        val doc = currentDocument()
        if (!doc.hasMeaningfulContent()) return false
        val result = if (!st.committedToInbox) {
            actions.createTextNote(
                title = doc.title,
                blocks = doc.blocks,
                context = st.context,
                captureId = st.captureId ?: draftCaptureId,
                noteId = st.noteId
            )
        } else {
            actions.saveTextNote(st.captureId!!, doc.title, doc.blocks)
        }
        return when (result) {
            is ActionResult.Success -> {
                _state.update {
                    it.copy(
                        captureId = result.value.captureItemId,
                        noteId = result.value.id,
                        committedToInbox = true,
                        persisted = true,
                        saveStatus = NoteSaveStatus.Saved,
                        inboxBanner = showBanner,
                        toast = null
                    )
                }
                true
            }
            else -> {
                _state.update { it.copy(saveStatus = NoteSaveStatus.Error, toast = "Could not save") }
                false
            }
        }
    }

    private fun scheduleAutosave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            _state.update { it.copy(saveStatus = NoteSaveStatus.Saving) }
            delay(450)
            val st = _state.value
            val doc = currentDocument()
            if (!doc.hasMeaningfulContent()) {
                _state.update { it.copy(saveStatus = NoteSaveStatus.Idle) }
                return@launch
            }
            if (!st.committedToInbox) {
                // Draft session protection only — not yet an Inbox CaptureItem.
                _state.update { it.copy(saveStatus = NoteSaveStatus.Saved) }
                return@launch
            }
            persistCommitted()
        }
    }

    private suspend fun persistCommitted() {
        val st = _state.value
        val doc = currentDocument()
        if (!doc.hasMeaningfulContent() || st.captureId == null) {
            _state.update { it.copy(saveStatus = NoteSaveStatus.Idle) }
            return
        }
        when (val result = actions.saveTextNote(st.captureId, doc.title, doc.blocks)) {
            is ActionResult.Success -> _state.update {
                it.copy(persisted = true, saveStatus = NoteSaveStatus.Saved, noteId = result.value.id)
            }
            else -> _state.update { it.copy(saveStatus = NoteSaveStatus.Error, toast = "Could not save") }
        }
    }

    private fun updateBlock(blockId: String, transform: (NoteBlock) -> NoteBlock) {
        _state.update { st ->
            st.copy(blocks = st.blocks.map { if (it.id == blockId) transform(it) else it })
        }
    }

    private fun updateBlockDeep(blockId: String, transform: (NoteBlock) -> NoteBlock) {
        _state.update { st ->
            st.copy(
                blocks = st.blocks.map { b ->
                    when {
                        b.id == blockId -> transform(b)
                        b.type == NoteBlockType.TOGGLE ->
                            b.copy(children = b.children.map { if (it.id == blockId) transform(it) else it })
                        else -> b
                    }
                }
            )
        }
    }

    private fun refreshContextLabel() {
        val ctx = _state.value.context
        val projects = repository.projects.value
        val streams = repository.streams.value
        val tasks = repository.tasks.value
        val label = when {
            ctx.taskId != null -> {
                val t = tasks.firstOrNull { it.id == ctx.taskId }
                val ws = t?.workStreamId?.let { id -> streams.firstOrNull { it.id == id } }
                val p = (t?.projectId ?: ws?.projectId)?.let { id -> projects.firstOrNull { it.id == id } }
                listOfNotNull(p?.title, ws?.title, t?.title).joinToString(" → ").ifBlank { null }
            }
            ctx.workStreamId != null -> {
                val ws = streams.firstOrNull { it.id == ctx.workStreamId }
                val p = ws?.projectId?.let { id -> projects.firstOrNull { it.id == id } }
                listOfNotNull(p?.title, ws?.title).joinToString(" → ").ifBlank { null }
            }
            ctx.projectId != null -> projects.firstOrNull { it.id == ctx.projectId }?.title
            else -> null
        }
        _state.update { it.copy(contextLabel = label) }
    }

    companion object {
        fun factory(captureId: String?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TextNoteViewModel(captureId) as T
            }
    }
}
