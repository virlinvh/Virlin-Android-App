package com.virlin.app.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteRuns
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Honest save state. Nothing here is set optimistically; SAVED follows a real write. */
enum class NoteSaveState { NO_CHANGES, EDITED, SAVING, SAVED, FAILED }

/** Transient pickers the toolbar opens. They sit above the page and change no frozen layout. */
sealed interface NoteDialog {
    data object Paragraph : NoteDialog
    data object Font : NoteDialog
    data object Size : NoteDialog
    data object TextColor : NoteDialog
    data object Highlight : NoteDialog
    data object LineSpacing : NoteDialog
    data object AddBlock : NoteDialog
    data object Table : NoteDialog
    data class Link(val initialUrl: String, val hasSelection: Boolean) : NoteDialog
    data object Preview : NoteDialog
    data class Message(val text: String) : NoteDialog
}

data class NotesUiState(
    val loading: Boolean = true,
    val title: String = "",
    val blocks: List<NoteDocBlock> = emptyList(),
    val focusedBlockId: String? = null,
    val selection: TextRange = TextRange.Zero,
    /** Style the next typed character takes when the caret is collapsed. */
    val caretMarks: Set<NoteDocMark> = emptySet(),
    val save: NoteSaveState = NoteSaveState.NO_CHANGES,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dialog: NoteDialog? = null,
) {
    val focusedBlock: NoteDocBlock? get() = blocks.firstOrNull { it.id == focusedBlockId }
}

/**
 * The Notes page editor.
 *
 * Owns the document, the caret, the selection and the undo history. Every formatting action is
 * expressed as a change to the document's own blocks and runs, so a toolbar control can never
 * light up without the stored text actually changing — the thing the shell's own comment warns
 * against.
 *
 * Persistence is debounced and goes through [VirlinActions.saveNoteDoc]; the ViewModel never
 * touches a DAO. The document is self-contained: no capture item, no `NoteDocument`, and no
 * legacy `tasks.notes` string is read or written.
 */
class NotesViewModel(
    private val ownerKey: String,
    private val initialTitle: String,
    private val actions: VirlinActions,
    private val ids: IdProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(NotesUiState(title = initialTitle))
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var saveJob: Job? = null
    private var lastTypedBlock: String? = null

    private data class Snapshot(
        val blocks: List<NoteDocBlock>,
        val focusedBlockId: String?,
        val selection: TextRange,
    )

    init {
        viewModelScope.launch {
            val stored = actions.loadNoteDoc(ownerKey)
            val blocks = stored?.blocks?.takeIf { it.isNotEmpty() } ?: listOf(newBlock())
            ui = _state.value.copy(
                loading = false,
                title = stored?.title ?: initialTitle,
                blocks = blocks,
                focusedBlockId = null,
                save = NoteSaveState.NO_CHANGES,
            )
        }
    }

    private fun newBlock(type: NoteDocBlockType = NoteDocBlockType.PARAGRAPH, text: String = "") =
        NoteDocBlock(id = ids.newId("nb"), type = type, text = text)

    // ---------------------------------------------------------------- editing

    fun onFocusChanged(blockId: String, focused: Boolean) {
        val s = _state.value
        if (focused) {
            if (s.focusedBlockId != blockId) {
                val b = s.blocks.firstOrNull { it.id == blockId }
                ui = s.copy(
                    focusedBlockId = blockId,
                    selection = TextRange(b?.text?.length ?: 0),
                    caretMarks = emptySet(),
                )
            }
        } else if (s.focusedBlockId == blockId) {
            ui = s.copy(focusedBlockId = null, caretMarks = emptySet())
        }
    }

    fun onSelectionChanged(blockId: String, selection: TextRange) {
        val s = _state.value
        if (s.focusedBlockId != blockId || s.selection == selection) return
        // Moving the caret abandons a pending caret style: it belonged to the old position.
        ui = s.copy(selection = selection, caretMarks = emptySet())
    }

    /**
     * Accepts new text for a block, keeping runs aligned with it.
     *
     * A typed newline splits the block, which is what makes Enter create the next paragraph or
     * the next list item. CODE blocks keep the newline instead, because a code block is one
     * multi-line unit.
     */
    fun onTextChanged(blockId: String, newText: String, newSelection: TextRange) {
        val s = _state.value
        val block = s.blocks.firstOrNull { it.id == blockId } ?: return
        if (newText == block.text) {
            onSelectionChanged(blockId, newSelection)
            return
        }
        pushUndo(coalesceWith = blockId)

        if ('\n' in newText && block.type != NoteDocBlockType.CODE) {
            splitOnNewline(block, newText, newSelection)
            return
        }

        val (from, removed, inserted) = NoteRuns.diff(block.text, newText)
        var runs = NoteRuns.shiftForEdit(block.runs, from, removed, inserted, newText.length)
        // A pending caret style applies to exactly the characters just typed.
        if (inserted > 0 && s.caretMarks.isNotEmpty()) {
            s.caretMarks.forEach { mark ->
                runs = NoteRuns.applyMark(runs, newText.length, from, from + inserted, mark, add = true)
            }
        }
        val updated = block.copy(text = newText, runs = runs)
        ui = s.copy(
            blocks = s.blocks.map { if (it.id == blockId) updated else it },
            selection = newSelection,
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
    }

    private fun splitOnNewline(block: NoteDocBlock, newText: String, newSelection: TextRange) {
        val s = _state.value
        val at = newText.indexOf('\n')
        val head = newText.take(at)
        val tail = newText.drop(at + 1)

        // Enter on an empty list/quote/checklist item leaves that mode instead of making another.
        if (head.isEmpty() && tail.isEmpty() && block.type.isTextual &&
            block.type != NoteDocBlockType.PARAGRAPH
        ) {
            val plain = block.copy(type = NoteDocBlockType.PARAGRAPH, indent = 0, checked = false)
            ui = s.copy(
                blocks = s.blocks.map { if (it.id == block.id) plain else it },
                selection = TextRange(0),
                save = NoteSaveState.EDITED,
            )
            scheduleSave()
            return
        }

        val headRuns = NoteRuns.normalize(block.runs.filter { it.start < head.length }, head.length)
        // The runs are in the PRE-edit text, where the tail began at `at` - the newline itself
        // was inserted there and is not part of either half. Shifting by `at + 1` would drop the
        // tail's first character from every run.
        val tailRuns = NoteRuns.normalize(
            block.runs.mapNotNull { r ->
                val ne = r.end - at
                if (ne <= 0) null else r.copy(start = maxOf(r.start - at, 0), end = ne)
            },
            tail.length,
        )
        // Headings do not continue; lists and checklists do.
        val nextType = when (block.type) {
            NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED, NoteDocBlockType.CHECKLIST,
            NoteDocBlockType.QUOTE,
            -> block.type
            else -> NoteDocBlockType.PARAGRAPH
        }
        val second = NoteDocBlock(
            id = ids.newId("nb"), type = nextType, text = tail, runs = tailRuns,
            align = block.align, indent = block.indent,
            lineSpacing = block.lineSpacing,
        )
        val index = s.blocks.indexOfFirst { it.id == block.id }
        val blocks = s.blocks.toMutableList()
        blocks[index] = block.copy(text = head, runs = headRuns, checked = false)
        blocks.add(index + 1, second)
        ui = s.copy(
            blocks = blocks,
            focusedBlockId = second.id,
            selection = TextRange(0),
            caretMarks = emptySet(),
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
    }

    /**
     * Backspace with the caret at the very start of a block.
     *
     * Notion-style: a styled block first becomes a plain paragraph; a plain paragraph merges into
     * the block above, carrying its runs across at the join offset.
     */
    fun onBackspaceAtStart(blockId: String): Boolean {
        val s = _state.value
        val index = s.blocks.indexOfFirst { it.id == blockId }
        if (index < 0) return false
        val block = s.blocks[index]
        if (block.type != NoteDocBlockType.PARAGRAPH || block.indent > 0) {
            pushUndo()
            val plain = block.copy(
                type = NoteDocBlockType.PARAGRAPH,
                indent = maxOf(0, block.indent - 1),
                checked = false,
            )
            ui = s.copy(
                blocks = s.blocks.map { if (it.id == blockId) plain else it },
                save = NoteSaveState.EDITED,
            )
            scheduleSave()
            return true
        }
        if (index == 0) return false
        val prev = s.blocks[index - 1]
        if (!prev.type.isTextual) {
            // Remove a divider or table above rather than merging text into it.
            pushUndo()
            ui = s.copy(
                blocks = s.blocks.filterNot { it.id == prev.id },
                save = NoteSaveState.EDITED,
            )
            scheduleSave()
            return true
        }
        pushUndo()
        val join = prev.text.length
        val merged = prev.copy(
            text = prev.text + block.text,
            runs = NoteRuns.normalize(
                prev.runs + block.runs.map { it.copy(start = it.start + join, end = it.end + join) },
                prev.text.length + block.text.length,
            ),
        )
        val blocks = s.blocks.toMutableList()
        blocks[index - 1] = merged
        blocks.removeAt(index)
        ui = s.copy(
            blocks = blocks,
            focusedBlockId = merged.id,
            selection = TextRange(join),
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
        return true
    }

    fun onTitleChanged(title: String) {
        ui = _state.value.copy(title = title, save = NoteSaveState.EDITED)
        scheduleSave()
    }

    fun onChecklistToggled(blockId: String) {
        pushUndo()
        mutateBlock(blockId) { it.copy(checked = !it.checked) }
    }

    fun onTableCellChanged(blockId: String, row: Int, col: Int, value: String) {
        pushUndo(coalesceWith = blockId)
        mutateBlock(blockId) { b -> b.table?.let { b.copy(table = it.withCell(row, col, value)) } ?: b }
    }

    // ---------------------------------------------------------------- tools

    fun onTool(tool: NoteTool) {
        val s = _state.value
        when (tool) {
            NoteTool.UNDO -> undo()
            NoteTool.REDO -> redo()
            NoteTool.PARAGRAPH -> openDialog(NoteDialog.Paragraph)
            NoteTool.FONT -> openDialog(NoteDialog.Font)
            NoteTool.SIZE -> openDialog(NoteDialog.Size)
            NoteTool.BOLD -> toggleMark(NoteDocMark.BOLD)
            NoteTool.ITALIC -> toggleMark(NoteDocMark.ITALIC)
            NoteTool.UNDERLINE -> toggleMark(NoteDocMark.UNDERLINE)
            NoteTool.STRIKE -> toggleMark(NoteDocMark.STRIKE)
            NoteTool.CODE -> toggleMark(NoteDocMark.CODE)
            NoteTool.TEXT_COLOR -> openDialog(NoteDialog.TextColor)
            NoteTool.HIGHLIGHT -> openDialog(NoteDialog.Highlight)
            NoteTool.ALIGN_LEFT -> setAlign(NoteDocAlign.LEFT)
            NoteTool.ALIGN_CENTER -> setAlign(NoteDocAlign.CENTER)
            NoteTool.ALIGN_RIGHT -> setAlign(NoteDocAlign.RIGHT)
            NoteTool.BULLETS -> toggleBlockType(NoteDocBlockType.BULLET)
            NoteTool.NUMBERING -> toggleBlockType(NoteDocBlockType.NUMBERED)
            NoteTool.CHECKLIST -> toggleBlockType(NoteDocBlockType.CHECKLIST)
            NoteTool.QUOTE -> toggleBlockType(NoteDocBlockType.QUOTE)
            NoteTool.INDENT -> changeIndent(+1)
            NoteTool.OUTDENT -> changeIndent(-1)
            NoteTool.LINE_SPACING -> openDialog(NoteDialog.LineSpacing)
            NoteTool.LINK -> {
                val existing = currentRun()?.linkUrl.orEmpty()
                openDialog(NoteDialog.Link(existing, !s.selection.collapsed))
            }
            NoteTool.PROMPT -> toggleBlockType(NoteDocBlockType.PROMPT)
            NoteTool.TABLE -> openDialog(NoteDialog.Table)
            NoteTool.DIVIDER -> insertBlock(NoteDocBlockType.DIVIDER)
        }
    }

    /**
     * Adds or removes an inline mark.
     *
     * With a selection, every character in it changes. With a collapsed caret the mark becomes a
     * pending style, so the next characters typed carry it — the caret case a toggle that only
     * repainted a button would get wrong.
     */
    private fun toggleMark(mark: NoteDocMark) {
        val s = _state.value
        val block = s.focusedBlock ?: return
        if (!block.type.isTextual) return
        if (s.selection.collapsed) {
            val active = mark in effectiveCaretMarks()
            ui = s.copy(
                caretMarks = if (active) s.caretMarks - mark else s.caretMarks + mark,
            )
            return
        }
        pushUndo()
        val start = s.selection.min
        val end = s.selection.max
        val add = !NoteRuns.rangeHasMark(block.runs, start, end, mark)
        mutateBlock(block.id) {
            it.copy(runs = NoteRuns.applyMark(it.runs, it.text.length, start, end, mark, add))
        }
    }

    /** Marks in force where the caret sits: the run under it plus anything pending. */
    private fun effectiveCaretMarks(): Set<NoteDocMark> {
        val s = _state.value
        val block = s.focusedBlock ?: return s.caretMarks
        val at = NoteRuns.styleAt(block.runs, s.selection.start)?.marks.orEmpty()
        return at + s.caretMarks
    }

    private fun currentRun(): NoteDocRun? {
        val s = _state.value
        val block = s.focusedBlock ?: return null
        return NoteRuns.styleAt(block.runs, s.selection.start)
    }

    /** Applies a scalar attribute to the selection, or to the whole block when nothing is selected. */
    private fun applyAttribute(transform: (NoteDocRun) -> NoteDocRun) {
        val s = _state.value
        val block = s.focusedBlock ?: return
        if (!block.type.isTextual || block.text.isEmpty()) return
        pushUndo()
        val start = if (s.selection.collapsed) 0 else s.selection.min
        val end = if (s.selection.collapsed) block.text.length else s.selection.max
        mutateBlock(block.id) {
            it.copy(runs = NoteRuns.applyAttribute(it.runs, it.text.length, start, end, transform))
        }
    }

    fun setFont(family: String?) = applyAttribute { it.copy(fontFamily = family) }.also { closeDialog() }

    fun setFontSize(sp: Int?) = applyAttribute { it.copy(fontSizeSp = sp) }.also { closeDialog() }

    fun setTextColor(argb: Long?) = applyAttribute { it.copy(color = argb) }.also { closeDialog() }

    fun setHighlight(argb: Long?) = applyAttribute { it.copy(highlight = argb) }.also { closeDialog() }

    /**
     * Sets or clears a link.
     *
     * With no selection the whole block becomes the link, so a link is never stored on zero
     * characters where it could not be tapped. An unusable scheme is refused rather than saved.
     */
    fun setLink(rawUrl: String?) {
        if (rawUrl == null) {
            applyAttribute { it.copy(linkUrl = null) }
            closeDialog()
            return
        }
        val url = normalizeUrl(rawUrl)
        if (url == null) {
            ui = _state.value.copy(
                dialog = NoteDialog.Message("That does not look like a web address."),
            )
            return
        }
        applyAttribute { it.copy(linkUrl = url) }
        closeDialog()
    }

    private fun setAlign(align: NoteDocAlign) {
        val id = _state.value.focusedBlockId ?: return
        pushUndo()
        mutateBlock(id) { it.copy(align = align) }
    }

    fun setLineSpacing(multiplier: Float) {
        val id = _state.value.focusedBlockId ?: return
        pushUndo()
        mutateBlock(id) { it.copy(lineSpacing = multiplier) }
        closeDialog()
    }

    /** Applying a block type a second time returns the block to a plain paragraph. */
    fun setBlockType(type: NoteDocBlockType) {
        toggleBlockType(type)
        closeDialog()
    }

    private fun toggleBlockType(type: NoteDocBlockType) {
        val s = _state.value
        val block = s.focusedBlock ?: run {
            insertBlock(type)
            return
        }
        pushUndo()
        val next = if (block.type == type) NoteDocBlockType.PARAGRAPH else type
        mutateBlock(block.id) {
            it.copy(
                type = next,
                checked = if (next == NoteDocBlockType.CHECKLIST) it.checked else false,
                promptTitle = if (next == NoteDocBlockType.PROMPT) it.promptTitle ?: "" else null,
            )
        }
    }

    fun onPromptTitleChanged(blockId: String, title: String) {
        pushUndo(coalesceWith = blockId)
        mutateBlock(blockId) { it.copy(promptTitle = title) }
    }

    private fun changeIndent(delta: Int) {
        val id = _state.value.focusedBlockId ?: return
        pushUndo()
        mutateBlock(id) { it.copy(indent = (it.indent + delta).coerceIn(0, 5)) }
    }

    // ---------------------------------------------------------------- block insertion

    fun openAddBlock() = openDialog(NoteDialog.AddBlock)

    /** Inserts after the focused block, or appends when nothing has focus. */
    fun insertBlock(type: NoteDocBlockType, table: NoteDocTable? = null) {
        pushUndo()
        val s = _state.value
        val created = NoteDocBlock(
            id = ids.newId("nb"),
            type = type,
            table = if (type == NoteDocBlockType.TABLE) table ?: NoteDocTable.blank(2, 2) else null,
            promptTitle = if (type == NoteDocBlockType.PROMPT) "" else null,
        )
        val index = s.blocks.indexOfFirst { it.id == s.focusedBlockId }
        val blocks = s.blocks.toMutableList()
        if (index >= 0) blocks.add(index + 1, created) else blocks.add(created)
        // A divider or table cannot hold the caret, so give the user a paragraph after it.
        val trailing = if (!type.isTextual) newBlock() else null
        trailing?.let { blocks.add(blocks.indexOf(created) + 1, it) }
        ui = s.copy(
            blocks = blocks,
            focusedBlockId = (trailing ?: created).id,
            selection = TextRange.Zero,
            save = NoteSaveState.EDITED,
            dialog = null,
        )
        scheduleSave()
    }

    fun deleteBlock(blockId: String) {
        pushUndo()
        val s = _state.value
        if (s.blocks.size <= 1) {
            ui = s.copy(blocks = listOf(newBlock()), save = NoteSaveState.EDITED)
        } else {
            ui = s.copy(
                blocks = s.blocks.filterNot { it.id == blockId },
                focusedBlockId = null,
                save = NoteSaveState.EDITED,
            )
        }
        scheduleSave()
    }

    // ---------------------------------------------------------------- undo

    /**
     * Records the document for undo. It deliberately does NOT publish state: callers emit their
     * own next state straight after, and [emit] recomputes the undo flags from the stacks, so a
     * caller can never publish a snapshot taken before this ran and silently drop `canUndo`.
     */
    private fun pushUndo(coalesceWith: String? = null) {
        // Consecutive typing in one block is one undo step, not one per keystroke.
        if (coalesceWith != null && coalesceWith == lastTypedBlock && undoStack.isNotEmpty()) return
        lastTypedBlock = coalesceWith
        val s = _state.value
        undoStack.addLast(Snapshot(s.blocks, s.focusedBlockId, s.selection))
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
    }

    /**
     * The single way state is published. The setter recomputes undo availability from the real
     * stacks, so a caller that publishes a snapshot taken before [pushUndo] ran cannot silently
     * drop `canUndo`.
     */
    private var ui: NotesUiState
        get() = _state.value
        set(value) {
            _state.value = value.copy(
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
            )
        }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        val s = _state.value
        redoStack.addLast(Snapshot(s.blocks, s.focusedBlockId, s.selection))
        lastTypedBlock = null
        ui = s.copy(
            blocks = prev.blocks,
            focusedBlockId = prev.focusedBlockId,
            selection = prev.selection,
            caretMarks = emptySet(),
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        val s = _state.value
        undoStack.addLast(Snapshot(s.blocks, s.focusedBlockId, s.selection))
        lastTypedBlock = null
        ui = s.copy(
            blocks = next.blocks,
            focusedBlockId = next.focusedBlockId,
            selection = next.selection,
            caretMarks = emptySet(),
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
    }

    // ---------------------------------------------------------------- dialogs

    fun openDialog(dialog: NoteDialog) {
        ui = _state.value.copy(dialog = dialog)
    }

    fun closeDialog() {
        ui = _state.value.copy(dialog = null)
    }

    fun openPreview() = openDialog(NoteDialog.Preview)

    fun onExport() = openDialog(
        NoteDialog.Message("Export is not built yet. Nothing was written to a file."),
    )

    // ---------------------------------------------------------------- saving

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            persist()
        }
    }

    /** Called when the page closes, so the last keystrokes are never lost to the debounce. */
    fun flush() {
        saveJob?.cancel()
        viewModelScope.launch { persist() }
    }

    private suspend fun persist() {
        val s = _state.value
        if (s.loading) return
        ui = _state.value.copy(save = NoteSaveState.SAVING)
        val result = actions.saveNoteDoc(ownerKey, s.title.takeIf { it.isNotBlank() }, s.blocks)
        ui = _state.value.copy(
            save = when (result) {
                is ActionResult.Success -> NoteSaveState.SAVED
                else -> NoteSaveState.FAILED
            },
        )
    }

    override fun onCleared() {
        saveJob?.cancel()
        super.onCleared()
    }

    private fun mutateBlock(blockId: String, transform: (NoteDocBlock) -> NoteDocBlock) {
        val s = _state.value
        ui = s.copy(
            blocks = s.blocks.map { if (it.id == blockId) transform(it) else it },
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
    }

    // ---------------------------------------------------------------- toolbar projection

    /** What the frozen toolbar shows. Derived from the document, never stored separately. */
    fun toolState(): NoteToolState {
        val s = _state.value
        val block = s.focusedBlock
        val textual = block?.type?.isTextual == true
        val run = currentRun()
        val marks = if (s.selection.collapsed) {
            effectiveCaretMarks()
        } else {
            NoteDocMark.entries.filter {
                NoteRuns.rangeHasMark(block?.runs.orEmpty(), s.selection.min, s.selection.max, it)
            }.toSet()
        }
        val active = buildSet {
            if (NoteDocMark.BOLD in marks) add(NoteTool.BOLD)
            if (NoteDocMark.ITALIC in marks) add(NoteTool.ITALIC)
            if (NoteDocMark.UNDERLINE in marks) add(NoteTool.UNDERLINE)
            if (NoteDocMark.STRIKE in marks) add(NoteTool.STRIKE)
            if (NoteDocMark.CODE in marks) add(NoteTool.CODE)
            if (run?.linkUrl != null) add(NoteTool.LINK)
            when (block?.align) {
                NoteDocAlign.CENTER -> add(NoteTool.ALIGN_CENTER)
                NoteDocAlign.RIGHT -> add(NoteTool.ALIGN_RIGHT)
                else -> if (block != null) add(NoteTool.ALIGN_LEFT)
            }
            when (block?.type) {
                NoteDocBlockType.BULLET -> add(NoteTool.BULLETS)
                NoteDocBlockType.NUMBERED -> add(NoteTool.NUMBERING)
                NoteDocBlockType.CHECKLIST -> add(NoteTool.CHECKLIST)
                NoteDocBlockType.QUOTE -> add(NoteTool.QUOTE)
                NoteDocBlockType.PROMPT -> add(NoteTool.PROMPT)
                else -> Unit
            }
        }
        val enabled = buildSet {
            if (s.canUndo) add(NoteTool.UNDO)
            if (s.canRedo) add(NoteTool.REDO)
            // Block-shape tools work from a focused block; insertion tools always work.
            add(NoteTool.TABLE)
            add(NoteTool.DIVIDER)
            if (block != null) {
                addAll(
                    listOf(
                        NoteTool.PARAGRAPH, NoteTool.BULLETS, NoteTool.NUMBERING,
                        NoteTool.CHECKLIST, NoteTool.QUOTE, NoteTool.PROMPT,
                        NoteTool.INDENT, NoteTool.OUTDENT, NoteTool.LINE_SPACING,
                        NoteTool.ALIGN_LEFT, NoteTool.ALIGN_CENTER, NoteTool.ALIGN_RIGHT,
                    ),
                )
            }
            if (textual) {
                addAll(
                    listOf(
                        NoteTool.BOLD, NoteTool.ITALIC, NoteTool.UNDERLINE, NoteTool.STRIKE,
                        NoteTool.CODE, NoteTool.FONT, NoteTool.SIZE,
                    ),
                )
                // Colour, highlight and link need characters to attach to.
                if (block!!.text.isNotEmpty()) {
                    addAll(listOf(NoteTool.TEXT_COLOR, NoteTool.HIGHLIGHT, NoteTool.LINK))
                }
            }
        }
        return NoteToolState(
            paragraphLabel = when (block?.type) {
                NoteDocBlockType.HEADING_1 -> "Heading 1"
                NoteDocBlockType.HEADING_2 -> "Heading 2"
                NoteDocBlockType.HEADING_3 -> "Heading 3"
                NoteDocBlockType.CODE -> "Code"
                NoteDocBlockType.QUOTE -> "Quote"
                NoteDocBlockType.PROMPT -> "Prompt"
                else -> "Normal text"
            },
            fontLabel = run?.fontFamily ?: "Inter",
            sizeLabel = (run?.fontSizeSp ?: defaultSizeFor(block?.type)).toString(),
            active = active,
            enabled = enabled,
        )
    }

    /** Honest header text. SAVED only ever follows a completed write. */
    fun saveLabel(): String = when (_state.value.save) {
        NoteSaveState.NO_CHANGES -> "No changes"
        NoteSaveState.EDITED -> "Unsaved"
        NoteSaveState.SAVING -> "Saving…"
        NoteSaveState.SAVED -> "Saved"
        NoteSaveState.FAILED -> "Not saved"
    }

    companion object {
        /** Built without Hilt, like the other full-page editors in this app. */
        fun factory(ownerKey: String, title: String): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = NotesViewModel(
                    ownerKey,
                    title,
                    com.virlin.app.domain.VirlinGraph.actions,
                    com.virlin.app.domain.VirlinGraph.ids,
                ) as T
            }

        val FONTS = listOf("Inter", "Serif", "Mono")
        val SIZES = listOf(12, 14, 16, 18, 20, 24, 28, 32)
        val SPACINGS = listOf(1f to "Single", 1.25f to "1.25", 1.5f to "1.5", 2f to "Double")

        /** Text colours, and highlights, offered by the pickers. */
        val TEXT_COLORS = listOf<Pair<Long?, String>>(
            null to "Default",
            0xFF17221DL to "Ink",
            0xFF087848L to "Green",
            0xFFB3261EL to "Red",
            0xFF1A56DBL to "Blue",
            0xFF8A5A00L to "Amber",
            0xFF66758AL to "Grey",
        )
        val HIGHLIGHTS = listOf<Pair<Long?, String>>(
            null to "None",
            0xFFE2F5EAL to "Mint",
            0xFFFFF3BFL to "Yellow",
            0xFFFFE0E0L to "Pink",
            0xFFE0ECFFL to "Blue",
        )

        fun defaultSizeFor(type: NoteDocBlockType?): Int = when (type) {
            NoteDocBlockType.HEADING_1 -> 26
            NoteDocBlockType.HEADING_2 -> 22
            NoteDocBlockType.HEADING_3 -> 18
            else -> 16
        }

        /**
         * Accepts only something that can actually be opened as a web address.
         *
         * A bare domain gets `https://`; anything with another scheme, whitespace or no dot is
         * refused rather than stored as a link that would fail when tapped.
         */
        fun normalizeUrl(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty() || t.any { it.isWhitespace() }) return null
            val withScheme = when {
                t.startsWith("http://", true) || t.startsWith("https://", true) -> t
                "://" in t -> return null
                t.startsWith("mailto:", true) || t.startsWith("tel:", true) -> return null
                else -> "https://$t"
            }
            val host = withScheme.removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore('?').substringBefore('#')
            if ('.' !in host || host.startsWith('.') || host.endsWith('.')) return null
            return withScheme
        }
    }
}
