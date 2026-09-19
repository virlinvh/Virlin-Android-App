package com.virlin.app.ui.prompt

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
import com.virlin.app.domain.model.PromptDocument
import com.virlin.app.domain.note.NoteClipboardImporter
import com.virlin.app.domain.note.NoteEnterSemantics
import com.virlin.app.domain.prompt.PromptDocumentCodec
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PromptSaveStatus { Idle, Saving, Saved, Error }

data class PromptUiState(
    val captureId: String?,
    val promptId: String,
    val title: String,
    val description: String,
    val tags: List<String>,
    val blocks: List<NoteBlock>,
    val focusedBlockId: String?,
    val saveStatus: PromptSaveStatus = PromptSaveStatus.Idle,
    val toast: String? = null,
    val contextLabel: String? = null,
    val context: CaptureContext = CaptureContext.None,
    val contextPickerOpen: Boolean = false,
    val tagDraft: String = "",
    val committedToInbox: Boolean = false,
    val persisted: Boolean = false,
    val loading: Boolean = true,
    val inboxBanner: String? = null,
    val cursorAtEndBlockId: String? = null
)

/**
 * Full-screen Prompt editor — reuse/copy focused, not a general note surface.
 * Autosave = content protected; Inbox commit via Save to Inbox / Back.
 */
class PromptEditorViewModel(
    private val initialCaptureId: String?,
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val ids: IdProvider = VirlinGraph.ids,
    private val clock: VirlinClock = VirlinGraph.clock
) : ViewModel() {

    private var saveJob: Job? = null
    private val draftCaptureId = initialCaptureId ?: ids.newId("cap")
    private val draftPromptId = ids.newId("prm")
    private val firstBlockId = ids.newId("blk")

    private val _state = MutableStateFlow(
        PromptUiState(
            captureId = initialCaptureId,
            promptId = draftPromptId,
            title = "",
            description = "",
            tags = emptyList(),
            blocks = listOf(NoteBlock(firstBlockId, NoteBlockType.TEXT)),
            focusedBlockId = firstBlockId,
            committedToInbox = initialCaptureId != null,
            loading = initialCaptureId != null
        )
    )
    val state: StateFlow<PromptUiState> = _state.asStateFlow()

    init {
        refreshContextLabel()
        if (initialCaptureId != null) {
            viewModelScope.launch {
                when (val r = actions.getOrHydratePrompt(initialCaptureId)) {
                    is ActionResult.Success -> {
                        val doc = r.value
                        val hasDoc = actions.getPromptByCaptureId(initialCaptureId) != null
                        _state.update {
                            it.copy(
                                captureId = doc.captureItemId,
                                promptId = doc.id,
                                title = doc.title.orEmpty(),
                                description = doc.description.orEmpty(),
                                tags = doc.tags,
                                blocks = doc.blocks.ifEmpty { listOf(NoteBlock(ids.newId("blk"), NoteBlockType.TEXT)) },
                                focusedBlockId = doc.blocks.firstOrNull()?.id ?: it.focusedBlockId,
                                committedToInbox = true,
                                persisted = hasDoc,
                                loading = false,
                                saveStatus = if (hasDoc) PromptSaveStatus.Saved else PromptSaveStatus.Idle
                            )
                        }
                        refreshContextLabel()
                    }
                    else -> _state.update { it.copy(loading = false, toast = "Could not open prompt") }
                }
            }
        } else {
            _state.update { it.copy(loading = false) }
        }
    }

    fun onTitleChange(t: String) { _state.update { it.copy(title = t) }; scheduleAutosave() }
    fun onDescriptionChange(t: String) { _state.update { it.copy(description = t) }; scheduleAutosave() }
    fun onTagDraftChange(t: String) = _state.update { it.copy(tagDraft = t) }

    fun addTagFromDraft() {
        val raw = _state.value.tagDraft.trim()
        if (raw.isEmpty()) return
        _state.update { st ->
            st.copy(tags = (st.tags + raw).distinct(), tagDraft = "")
        }
        scheduleAutosave()
    }

    fun removeTag(tag: String) {
        _state.update { it.copy(tags = it.tags.filterNot { t -> t == tag }) }
        scheduleAutosave()
    }

    fun onBlockTextChange(blockId: String, text: String) {
        updateBlock(blockId) { it.copy(plainText = text) }
        scheduleAutosave()
    }

    fun focusBlock(blockId: String?) = _state.update { it.copy(focusedBlockId = blockId) }
    fun clearCursorAtEnd() = _state.update { it.copy(cursorAtEndBlockId = null) }

    fun handleEnter(blockId: String, before: String, after: String) {
        val st = _state.value
        val idx = st.blocks.indexOfFirst { it.id == blockId }
        if (idx < 0) return
        val result = NoteEnterSemantics.applyEnter(st.blocks[idx], before, after, ids.newId("blk"))
        _state.update { s ->
            val list = s.blocks.toMutableList()
            list[idx] = result.updatedCurrent
            result.newSibling?.let { list.add(idx + 1, it) }
            s.copy(blocks = list, focusedBlockId = result.newSibling?.id ?: result.updatedCurrent.id)
        }
        scheduleAutosave()
    }

    fun insertSoftLineBreak(blockId: String? = _state.value.focusedBlockId, cursor: Int? = null) {
        val id = blockId ?: return
        updateBlock(id) { b ->
            val at = cursor ?: b.plainText.length
            b.copy(plainText = NoteEnterSemantics.insertSoftBreak(b.plainText, at))
        }
        scheduleAutosave()
    }

    fun handleBackspaceOnEmpty(blockId: String): Boolean {
        val st = _state.value
        val idx = st.blocks.indexOfFirst { it.id == blockId }
        if (idx < 0) return false
        val block = st.blocks[idx]
        if (block.plainText.isNotEmpty()) return false
        if (st.blocks.size == 1) {
            if (block.type != NoteBlockType.TEXT) {
                updateBlock(blockId) { it.copy(type = NoteBlockType.TEXT, checked = false, children = emptyList()) }
            }
            return true
        }
        val prevId = st.blocks.getOrNull(idx - 1)?.id
        val nextId = st.blocks.getOrNull(idx + 1)?.id
        _state.update { s ->
            s.copy(
                blocks = s.blocks.filterNot { it.id == blockId },
                focusedBlockId = prevId ?: nextId,
                cursorAtEndBlockId = prevId
            )
        }
        scheduleAutosave()
        return true
    }

    fun pasteStructured(blockId: String, html: String?, plain: String) {
        val imported = NoteClipboardImporter.import(html, plain) { ids.newId("blk") }
        if (imported.isEmpty()) return
        val st = _state.value
        val idx = st.blocks.indexOfFirst { it.id == blockId }
        if (idx < 0) return
        val current = st.blocks[idx]
        val replaceEmpty = current.plainText.isEmpty() && current.type != NoteBlockType.DIVIDER
        _state.update { s ->
            val list = s.blocks.toMutableList()
            if (replaceEmpty) {
                list.removeAt(idx)
                list.addAll(idx, imported)
            } else {
                list.addAll(idx + 1, imported)
            }
            s.copy(blocks = list, focusedBlockId = imported.last().id, cursorAtEndBlockId = imported.last().id)
        }
        scheduleAutosave()
    }

    fun openContextPicker(open: Boolean) = _state.update { it.copy(contextPickerOpen = open) }

    fun setContext(ctx: CaptureContext) {
        _state.update { it.copy(context = ctx, contextPickerOpen = false) }
        refreshContextLabel()
        val capId = _state.value.captureId
        if (capId != null && _state.value.committedToInbox) {
            viewModelScope.launch {
                actions.attachCapture(capId, ctx)
                _state.update { it.copy(toast = "Context updated", saveStatus = PromptSaveStatus.Saved) }
            }
        }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }
    fun clearInboxBanner() = _state.update { it.copy(inboxBanner = null) }

    fun copyPromptPlainText(): String {
        _state.update { it.copy(toast = "Copied") }
        return PromptDocumentCodec.plainText(currentDocument())
    }

    fun currentDocument(): PromptDocument {
        val st = _state.value
        return PromptDocument(
            id = st.promptId,
            captureItemId = st.captureId ?: draftCaptureId,
            title = st.title.ifBlank { null },
            description = st.description.ifBlank { null },
            tags = st.tags,
            blocks = st.blocks,
            createdAt = clock.now(),
            updatedAt = clock.now()
        )
    }

    fun saveToInbox() {
        viewModelScope.launch {
            if (!currentDocument().hasMeaningfulContent()) {
                _state.update { it.copy(toast = "Nothing to save yet") }
                return@launch
            }
            commitToInbox(showBanner = "✓ In Inbox")
        }
    }

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
            actions.createPrompt(
                title = doc.title,
                description = doc.description,
                tags = doc.tags,
                blocks = doc.blocks,
                context = st.context,
                captureId = st.captureId ?: draftCaptureId,
                promptId = st.promptId
            )
        } else {
            actions.savePrompt(st.captureId!!, doc.title, doc.description, doc.tags, doc.blocks)
        }
        return when (result) {
            is ActionResult.Success -> {
                _state.update {
                    it.copy(
                        captureId = result.value.captureItemId,
                        promptId = result.value.id,
                        committedToInbox = true,
                        persisted = true,
                        saveStatus = PromptSaveStatus.Saved,
                        inboxBanner = showBanner,
                        toast = null
                    )
                }
                true
            }
            else -> {
                _state.update { it.copy(saveStatus = PromptSaveStatus.Error, toast = "Could not save") }
                false
            }
        }
    }

    private suspend fun persistCommitted() {
        val st = _state.value
        val capId = st.captureId ?: return
        if (!st.committedToInbox) return
        val doc = currentDocument()
        if (!doc.hasMeaningfulContent()) return
        when (actions.savePrompt(capId, doc.title, doc.description, doc.tags, doc.blocks)) {
            is ActionResult.Success -> _state.update { it.copy(persisted = true, saveStatus = PromptSaveStatus.Saved) }
            else -> _state.update { it.copy(saveStatus = PromptSaveStatus.Error) }
        }
    }

    private fun scheduleAutosave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            _state.update { it.copy(saveStatus = PromptSaveStatus.Saving) }
            delay(450)
            val st = _state.value
            if (!st.committedToInbox) {
                _state.update { it.copy(saveStatus = PromptSaveStatus.Saved) }
                return@launch
            }
            persistCommitted()
        }
    }

    private fun updateBlock(blockId: String, transform: (NoteBlock) -> NoteBlock) {
        _state.update { st ->
            st.copy(blocks = st.blocks.map { if (it.id == blockId) transform(it) else it })
        }
    }

    private fun refreshContextLabel() {
        viewModelScope.launch {
            val ctx = _state.value.context
            val label = when {
                ctx.taskId != null -> repository.getTask(ctx.taskId)?.title?.let { "Attached to · $it" }
                ctx.workStreamId != null -> repository.getStream(ctx.workStreamId)?.title?.let { "Attached to · $it" }
                ctx.projectId != null -> repository.getProject(ctx.projectId)?.title?.let { "Attached to · $it" }
                else -> null
            }
            _state.update { it.copy(contextLabel = label) }
        }
    }

    companion object {
        fun factory(captureId: String?): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PromptEditorViewModel(captureId) as T
        }
    }
}
