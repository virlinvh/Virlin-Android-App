package com.virlin.app.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteRuns
import com.virlin.app.domain.notedoc.NotePasteParser
import com.virlin.app.domain.notedoc.NotePasteSafety
import com.virlin.app.domain.notedoc.VirlinNoteCodec
import com.virlin.app.domain.notedoc.visibleNoteBlocks
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    data class Message(val text: String) : NoteDialog
    /**
     * A save was refused, so the edits on screen are not stored yet. [stale] means a newer
     * version was saved elsewhere; [closeAfter] means the user was trying to leave the page.
     */
    data class SaveRefused(val reason: String, val stale: Boolean, val closeAfter: Boolean) : NoteDialog
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
    /** A large paste is being formatted off the main thread; text fields are read-only meanwhile. */
    val pasting: Boolean = false,
    /** The stored note could not be read. The editor shows recovery instead of a blank page. */
    val damaged: Boolean = false,
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
 * touches a DAO. The editor is owner-agnostic: capture-owned documents use the domain's
 * Knowledge adapter, while ordinary task/project workspace documents remain independent.
 */
class NotesViewModel(
    private val ownerKey: String,
    private val initialTitle: String,
    private val actions: VirlinActions,
    private val ids: IdProvider,
    private val pasteDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(NotesUiState(title = initialTitle))
    val state: StateFlow<NotesUiState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var saveJob: Job? = null
    private var pasteJob: Job? = null
    private val saveMutex = Mutex()
    private var lastTypedBlock: String? = null

    private data class Snapshot(
        val blocks: List<NoteDocBlock>,
        val focusedBlockId: String?,
        val selection: TextRange,
    )

    /** The stored version this editor is based on; saves are refused if storage moved past it. */
    private var baseRevision = 0
    private var baseBlocks: List<NoteDocBlock> = emptyList()
    private var baseTitle: String? = null
    private var damagedRaw: String? = null
    private var refusalShown = false

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val stored = actions.loadNoteDoc(ownerKey)
        val damaged = stored?.loadError != null
        damagedRaw = if (damaged) stored?.damagedRaw else null
        rememberBase(stored)
        val blocks = stored?.blocks?.takeIf { it.isNotEmpty() && !damaged } ?: listOf(newBlock())
        undoStack.clear()
        redoStack.clear()
        lastTypedBlock = null
        ui = _state.value.copy(
            loading = false,
            title = stored?.title ?: initialTitle,
            blocks = blocks,
            focusedBlockId = null,
            selection = TextRange.Zero,
            save = NoteSaveState.NO_CHANGES,
            damaged = damaged,
        )
    }

    private fun rememberBase(doc: com.virlin.app.domain.notedoc.VirlinNoteDoc?) {
        baseRevision = doc?.revision ?: 0
        baseBlocks = doc?.blocks.orEmpty()
        baseTitle = doc?.title
    }

    private fun newBlock(type: NoteDocBlockType = NoteDocBlockType.PARAGRAPH, text: String = "") =
        NoteDocBlock(id = ids.newId("nb"), type = type, text = text)

    /** Receives a complete, schema-validated transaction from the isolated rich editor. */
    fun replaceFromRichEditor(blocks: List<NoteDocBlock>) {
        if (blocks.isEmpty() || blocks == _state.value.blocks) return
        val state = _state.value
        // Tiptap owns fine-grained undo while active. Persist only a document that survives
        // the same codec the repository uses; a malformed bridge payload cannot erase a note.
        val roundTrip = runCatching { VirlinNoteCodec.decode(VirlinNoteCodec.encode(blocks)) }.getOrNull()
            ?.takeIf { it.size == blocks.size } ?: return
        ui = state.copy(blocks = roundTrip, save = NoteSaveState.EDITED)
        scheduleSave()
    }

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
        }
        // A toolbar tap or picker temporarily blurs the field. Keep its selection and block
        // target so Bold/Italic/Code apply to the text the user just selected. A different
        // text field replaces this target when it receives focus.
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

        val (from, removed, inserted) = NoteRuns.diff(block.text, newText)
        // Only a newly typed Enter splits a block. Existing newlines in a legacy
        // unformatted paste must not cause unrelated edits to split it again.
        if (block.type != NoteDocBlockType.CODE && '\n' !in block.text &&
            newText.count { it == '\n' } == 1) {
            splitOnNewline(block, newText, newSelection)
            return
        }

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

    /**
     * One undoable, structure-preserving clipboard insertion. Never stores raw HTML.
     *
     * Returns true when the paste was taken over — applied now, or being formatted off the main
     * thread — and false when the field should apply the edit as ordinary typing.
     */
    fun pasteStructured(blockId: String, newText: String, plainText: String,
        htmlText: String? = null, imageResolver: ((String) -> String?)? = null): Boolean {
        val s = _state.value
        // While a large paste is formatting the fields are read-only; a late callback from the
        // old input connection must not edit the document underneath it.
        if (s.pasting) return true
        val original = s.blocks.firstOrNull { it.id == blockId } ?: return false
        // Code and equations are literal: Markdown inside them (a Python `# comment`) is data.
        if (original.type == NoteDocBlockType.CODE) return false
        val (from, removed, inserted) = NoteRuns.diff(original.text, newText)
        val insertedText = newText.substring(from, from + inserted)
        if (insertedText.isEmpty()) return false
        val clipboardMatches = sameText(insertedText, plainText)
        val largeHtml = (htmlText?.length ?: 0) > NotePasteSafety.BACKGROUND_HTML_CHARS
        if (insertedText.length > NotePasteSafety.BACKGROUND_CHARS || largeHtml && clipboardMatches) {
            pasteInBackground(original, from, removed, insertedText, plainText, htmlText, imageResolver)
            return true
        }
        // Android may supply a different plain-text representation alongside semantic HTML.
        // Only trust that HTML when its visible text is the text the IME actually inserted.
        // HTML far larger than the insertion cannot be its source (a typed word while a long
        // answer sits on the clipboard), so it is not parsed for that edit.
        val htmlMatches = !clipboardMatches && htmlText != null && !largeHtml &&
            htmlText.length <= insertedText.length * 50 + 8_192 && htmlTextMatches(htmlText, insertedText)
        val structuredInsertion = insertedText.length > 3 &&
            (insertedText.length > 20 && '\n' in insertedText ||
            insertedText.lines().any { line ->
                val value = line.trimStart()
                value.matches(Regex("#{1,6}\\s+.*")) || value.startsWith("```") ||
                    value.startsWith("~~~") || value.startsWith("$$") ||
                    value.contains(Regex("\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__")) ||
                    value.matches(Regex("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?"))
            })
        if (!clipboardMatches && !htmlMatches && !structuredInsertion) return false
        val usableHtml = htmlText.takeIf { clipboardMatches || htmlMatches }
        val richAttempt = runCatching { NotePasteParser.parse(insertedText,
            usableHtml, imageResolver) }
        val richImport = richAttempt.getOrNull()?.takeIf { it.isNotEmpty() }
        // Clipboard HTML can be much larger than the visible text (especially from
        // generated responses). A rejected rich payload must not discard its valid
        // Markdown/plain representation and fall through to a single text block.
        val plainAttempt = if (richImport == null) runCatching {
            NotePasteParser.parse(insertedText, null, imageResolver)
        } else null
        val imported = richImport ?: plainAttempt?.getOrNull()?.takeIf { it.isNotEmpty() } ?: run {
            android.util.Log.w("VirlinNotePaste", "Structured import failed: inserted=${insertedText.length} " +
                "clipboardMatches=$clipboardMatches html=${usableHtml?.length ?: 0} " +
                "rich=${richAttempt.exceptionOrNull()?.javaClass?.simpleName}:${richAttempt.exceptionOrNull()?.message} " +
                "plain=${plainAttempt?.exceptionOrNull()?.javaClass?.simpleName}:${plainAttempt?.exceptionOrNull()?.message}")
            if (structuredInsertion) ui = _state.value.copy(dialog = NoteDialog.Message(
                "The note could not import this paste's formatting. Its text was kept unchanged."))
            return false
        }
        if (imported.size == 1 && imported[0].type == NoteDocBlockType.PARAGRAPH &&
            imported[0].runs.isEmpty() && !insertedText.contains('\n')
        ) return false
        applyPastedBlocks(original.id, original.text, from, removed, imported)
        return true
    }

    private fun pasteInBackground(original: NoteDocBlock, from: Int, removed: Int, insertedText: String,
        plainText: String, htmlText: String?, imageResolver: ((String) -> String?)?) {
        ui = _state.value.copy(pasting = true)
        pasteJob = viewModelScope.launch {
            val formatted = withContext(pasteDispatcher) {
                runCatching { formatLargePaste(insertedText, plainText, htmlText, imageResolver) }.getOrNull()
            }
            val blocks = formatted ?: NotePasteSafety.plainParagraphs(insertedText)
            ui = _state.value.copy(pasting = false)
            if (blocks.isNotEmpty()) applyPastedBlocks(original.id, original.text, from, removed, blocks)
            if (formatted == null) ui = _state.value.copy(dialog = NoteDialog.Message(PLAIN_PASTE_FALLBACK))
        }
    }

    /**
     * Replaces the pasted range of [blockId] with [imported] as one undo step. If that block
     * changed or vanished since the paste began, the pasted blocks are placed after it instead
     * of being merged into text they no longer describe.
     */
    private fun applyPastedBlocks(blockId: String, expectedText: String, from: Int, removed: Int,
        imported: List<NoteDocBlock>) {
        val s = _state.value
        val original = s.blocks.firstOrNull { it.id == blockId }
        if (original == null || original.text != expectedText) {
            pushUndo()
            val pasted = imported.map { it.copy(id = ids.newId("nb")) }
            val at = s.blocks.indexOfFirst { it.id == blockId }.let { if (it < 0) s.blocks.size else it + 1 }
            ui = s.copy(
                blocks = s.blocks.take(at) + pasted + s.blocks.drop(at),
                focusedBlockId = pasted.last().id,
                selection = TextRange(pasted.last().text.length),
                caretMarks = emptySet(),
                save = NoteSaveState.EDITED,
            )
            scheduleSave()
            return
        }
        val end = from + removed
        val before = original.text.take(from)
        val after = original.text.drop(end)
        fun sliceRuns(start: Int, finish: Int, offset: Int = 0): List<NoteDocRun> =
            original.runs.mapNotNull { run ->
                val a = maxOf(run.start, start)
                val b = minOf(run.end, finish)
                if (b > a) run.copy(start = a - start + offset, end = b - start + offset)
                else null
            }
        pushUndo()
        val replacement: List<NoteDocBlock>
        val focus: NoteDocBlock
        val caret: Int
        if (imported.size == 1 && imported[0].type == NoteDocBlockType.PARAGRAPH &&
            original.type == NoteDocBlockType.PARAGRAPH) {
            val middle = imported.single()
            val mergedText = before + middle.text + after
            val mergedRuns = sliceRuns(0, from) +
                middle.runs.map { it.copy(start = it.start + from, end = it.end + from) } +
                sliceRuns(end, original.text.length, from + middle.text.length)
            focus = original.copy(id = ids.newId("nb"), text = mergedText,
                runs = NoteRuns.normalize(mergedRuns, mergedText.length))
            replacement = listOf(focus)
            caret = from + middle.text.length
        } else {
            val parts = mutableListOf<NoteDocBlock>()
            if (before.isNotEmpty()) parts += original.copy(id = ids.newId("nb"), text = before,
                runs = NoteRuns.normalize(sliceRuns(0, from), before.length))
            // Replace the edited field's identity too: Android may deliver a second callback
            // from the old IME connection after Compose publishes the structured blocks.
            val pasted = imported.map { it.copy(id = ids.newId("nb")) }
            parts += pasted
            if (after.isNotEmpty()) parts += original.copy(id = ids.newId("nb"), text = after,
                runs = NoteRuns.normalize(sliceRuns(end, original.text.length), after.length))
            replacement = parts
            focus = pasted.last()
            caret = focus.text.length
        }
        val at = s.blocks.indexOfFirst { it.id == blockId }
        ui = s.copy(
            blocks = s.blocks.take(at) + replacement + s.blocks.drop(at + 1),
            focusedBlockId = focus.id,
            selection = TextRange(caret),
            caretMarks = emptySet(),
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
            align = block.align,
            indent = if (block.type == NoteDocBlockType.TOGGLE) (block.indent + 1).coerceAtMost(5)
                else block.indent,
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
            NoteTool.FLASHCARD -> Unit // The route performs the durable save.
            NoteTool.SELECT_DOCUMENT -> Unit // Read-only cross-block selection belongs to the route.
            NoteTool.HEADING_FOLD -> Unit // Local presentation mode belongs to the screen.
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
            // The route launches Android's picker, then calls insertImage with a durable copy.
            NoteTool.IMAGE -> Unit
            NoteTool.TOGGLE -> insertBlock(NoteDocBlockType.TOGGLE)
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
    fun insertBlock(type: NoteDocBlockType, table: NoteDocTable? = null,
        initialText: String = "") {
        pushUndo()
        val s = _state.value
        val focused = s.focusedBlock
        val indent = ((focused?.indent ?: 0) +
            if (focused?.type == NoteDocBlockType.TOGGLE) 1 else 0).coerceAtMost(5)
        val created = NoteDocBlock(
            id = ids.newId("nb"),
            type = type,
            text = initialText,
            mathMode = if (type == NoteDocBlockType.CODE) initialText == "\\( \\)" else null,
            indent = indent,
            table = if (type == NoteDocBlockType.TABLE) table ?: NoteDocTable.blank(2, 2) else null,
            promptTitle = if (type == NoteDocBlockType.PROMPT) "" else null,
        )
        val index = s.blocks.indexOfFirst { it.id == s.focusedBlockId }
        val blocks = s.blocks.toMutableList()
        if (index >= 0) blocks.add(index + 1, created) else blocks.add(created)
        // A divider or table cannot hold the caret, so give the user a paragraph after it.
        val trailing = when (type) {
            NoteDocBlockType.TOGGLE -> newBlock().copy(indent = (indent + 1).coerceAtMost(5))
            else -> if (!type.isTextual) newBlock().copy(indent = indent) else null
        }
        trailing?.let { blocks.add(blocks.indexOf(created) + 1, it) }
        ui = s.copy(
            blocks = blocks,
            focusedBlockId = (if (type == NoteDocBlockType.TOGGLE) created else trailing ?: created).id,
            selection = TextRange.Zero,
            save = NoteSaveState.EDITED,
            dialog = null,
        )
        scheduleSave()
    }

    fun insertEquation() = insertBlock(NoteDocBlockType.CODE, initialText = "\\( \\)")

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
        val pending = pasteJob?.takeIf { it.isActive }
        if (pending == null && (_state.value.save == NoteSaveState.NO_CHANGES || _state.value.save == NoteSaveState.SAVED)) return
        viewModelScope.launch {
            pending?.join()
            saveJob?.cancel()
            if (_state.value.save != NoteSaveState.NO_CHANGES && _state.value.save != NoteSaveState.SAVED) persist()
        }
    }

    fun saveAndClose(onSaved: () -> Unit) {
        saveJob?.cancel()
        val pending = pasteJob?.takeIf { it.isActive }
        if (pending == null && (_state.value.save == NoteSaveState.NO_CHANGES || _state.value.save == NoteSaveState.SAVED)) {
            onSaved()
            return
        }
        viewModelScope.launch {
            pending?.join()
            saveJob?.cancel()
            while (_state.value.save != NoteSaveState.NO_CHANGES && _state.value.save != NoteSaveState.SAVED) {
                if (!persist(closing = true)) return@launch
            }
            onSaved()
        }
    }

    fun toggleCollapsed(blockId: String) {
        val s = _state.value
        val index = s.blocks.indexOfFirst { it.id == blockId }
        if (index < 0 || s.blocks[index].type !in setOf(NoteDocBlockType.TOGGLE,
                NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3)) return
        pushUndo()
        val toggle = s.blocks[index]
        val nextCollapsed = !toggle.isCollapsed
        val headingLevel = { type: NoteDocBlockType -> when (type) {
            NoteDocBlockType.HEADING_1 -> 1
            NoteDocBlockType.HEADING_2 -> 2
            NoteDocBlockType.HEADING_3 -> 3
            else -> 0
        } }
        val level = headingLevel(toggle.type)
        val descendantIds = s.blocks.drop(index + 1).takeWhile {
            if (level == 0) it.indent > toggle.indent
            else headingLevel(it.type) == 0 || headingLevel(it.type) > level
        }
            .mapTo(hashSetOf()) { it.id }
        ui = s.copy(
            blocks = s.blocks.map { if (it.id == blockId) it.copy(isCollapsed = nextCollapsed) else it },
            focusedBlockId = if (nextCollapsed && s.focusedBlockId in descendantIds) blockId else s.focusedBlockId,
            selection = if (nextCollapsed && s.focusedBlockId in descendantIds) TextRange(toggle.text.length)
                else s.selection,
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
    }

    /** One toolbar tap folds every heading at a single depth; null restores all headings. */
    fun foldHeadingsAtLevel(level: Int?) {
        require(level == null || level in 1..3)
        val s = _state.value
        fun depth(type: NoteDocBlockType) = when (type) {
            NoteDocBlockType.HEADING_1 -> 1
            NoteDocBlockType.HEADING_2 -> 2
            NoteDocBlockType.HEADING_3 -> 3
            else -> 0
        }
        val blocks = s.blocks.map { block ->
            val headingDepth = depth(block.type)
            when {
                headingDepth == 0 -> block // independent toggle blocks are untouched
                level == null -> block.copy(isCollapsed = false)
                headingDepth == level -> block.copy(isCollapsed = true)
                else -> block
            }
        }
        if (blocks == s.blocks) return
        pushUndo()
        val visible = visibleNoteBlocks(blocks)
        val focusedVisible = visible.any { it.id == s.focusedBlockId }
        val nextFocus = if (focusedVisible) s.focusedBlockId
            else visible.firstOrNull { depth(it.type) > 0 }?.id ?: visible.firstOrNull()?.id
        val selection = if (focusedVisible) s.selection else TextRange(
            blocks.firstOrNull { it.id == nextFocus }?.text?.length ?: 0)
        ui = s.copy(blocks = blocks, focusedBlockId = nextFocus,
            selection = selection, save = NoteSaveState.EDITED)
        scheduleSave()
    }

    /** Places a selected image after the current block without storing a temporary content URI. */
    fun insertImage(imagePath: String) {
        pushUndo()
        val s = _state.value
        val focused = s.focusedBlock
        val indent = ((focused?.indent ?: 0) +
            if (focused?.type == NoteDocBlockType.TOGGLE) 1 else 0).coerceAtMost(5)
        val image = newBlock(NoteDocBlockType.IMAGE).copy(imagePath = imagePath, indent = indent)
        val following = newBlock().copy(indent = indent)
        val blocks = s.blocks.toMutableList()
        val index = blocks.indexOfFirst { it.id == s.focusedBlockId }
        val insertion = if (index < 0) blocks.size else index + 1
        blocks.add(insertion, image)
        blocks.add(insertion + 1, following)
        ui = s.copy(blocks = blocks, focusedBlockId = following.id,
            selection = TextRange.Zero, save = NoteSaveState.EDITED, dialog = null)
        scheduleSave()
    }

    private suspend fun persist(closing: Boolean = false): Boolean = withContext(NonCancellable) { saveMutex.withLock {
        val s = _state.value
        if (s.loading || s.damaged) return@withLock false
        ui = _state.value.copy(save = NoteSaveState.SAVING)
        val title = s.title.takeIf { it.isNotBlank() }
        var result = actions.saveNoteDoc(ownerKey, title, s.blocks, expectedRevision = baseRevision)
        if (result.isStale()) {
            // Another writer moved the revision. Nothing is lost if it stored exactly what this
            // editor holds, or if it changed nothing this editor was based on.
            val latest = runCatching { actions.loadNoteDoc(ownerKey) }.getOrNull()
            if (latest != null && latest.loadError == null) {
                if (latest.blocks == s.blocks && latest.title == title) {
                    result = ActionResult.Success(latest)
                } else if (latest.blocks == baseBlocks && latest.title == baseTitle) {
                    result = actions.saveNoteDoc(ownerKey, title, s.blocks, expectedRevision = latest.revision)
                }
            }
        }
        if (result is ActionResult.Success) {
            rememberBase(result.value)
            refusalShown = false
        }
        ui = _state.value.copy(
            save = when (result) {
                is ActionResult.Success -> if (_state.value.blocks == s.blocks && _state.value.title == s.title)
                    NoteSaveState.SAVED else NoteSaveState.EDITED
                else -> NoteSaveState.FAILED
            },
        )
        if (result !is ActionResult.Success && (closing || !refusalShown)) {
            refusalShown = true
            ui = _state.value.copy(dialog = NoteDialog.SaveRefused(result.refusalText(), result.isStale(), closing))
        }
        result is ActionResult.Success
    } }

    /**
     * Keeps the on-screen edits as a new note in [projectId]'s Knowledge library, then reopens
     * the stored version of this note. The stored note itself is never overwritten here.
     */
    fun saveEditsAsNewNote(projectId: String?, onClose: () -> Unit) {
        val refused = _state.value.dialog as? NoteDialog.SaveRefused
        if (projectId == null) return
        ui = _state.value.copy(dialog = null)
        viewModelScope.launch {
            val s = _state.value
            val copyTitle = "${s.title.ifBlank { initialTitle }.ifBlank { "Note" }} (my edits)"
            val copy = runCatching {
                actions.saveNoteDoc(CaptureNoteBridge.draftOwner(projectId, ids.newId("cap")), copyTitle, s.blocks)
            }.getOrElse { ActionResult.Failure(it) }
            if (copy !is ActionResult.Success) {
                ui = _state.value.copy(dialog = NoteDialog.Message(
                    "Your edits could not be saved as a new note (${copy.refusalText()}). They are still on screen."))
                return@launch
            }
            saveJob?.cancel()
            if (refused?.closeAfter == true) {
                ui = _state.value.copy(dialog = null, save = NoteSaveState.NO_CHANGES)
                onClose()
                return@launch
            }
            saveMutex.withLock { load() }
            ui = _state.value.copy(dialog = NoteDialog.Message(
                "Your edits were saved as the new note “$copyTitle” in this project. " +
                    "The stored version of this note is open again."))
        }
    }

    /** Drops the unsaved edits after the user chose to, then reopens or leaves the stored note. */
    fun discardEdits(onClose: () -> Unit) {
        val refused = _state.value.dialog as? NoteDialog.SaveRefused
        saveJob?.cancel()
        ui = _state.value.copy(dialog = null, save = NoteSaveState.NO_CHANGES)
        if (refused?.closeAfter == true) { onClose(); return }
        viewModelScope.launch { saveMutex.withLock { load() } }
    }

    /** The unreadable stored payload, exactly as stored, for the recovery panel's copy action. */
    fun damagedPayload(): String? = damagedRaw

    /**
     * Repairs a damaged note through the domain: the raw payload is first kept as a recovery
     * copy, then the note is replaced by the text that could be salvaged.
     */
    fun recoverDamaged() {
        viewModelScope.launch {
            when (val result = runCatching { actions.recoverDamagedNote(ownerKey) }.getOrElse { ActionResult.Failure(it) }) {
                is ActionResult.Success -> {
                    saveMutex.withLock { load() }
                    ui = _state.value.copy(dialog = NoteDialog.Message(
                        "The readable text was recovered. The original data was kept as a recovery copy."))
                }
                else -> ui = _state.value.copy(dialog = NoteDialog.Message(
                    "This note could not be recovered (${result.refusalText()}). Nothing was changed."))
            }
        }
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
                NoteDocBlockType.TOGGLE -> add(NoteTool.TOGGLE)
                else -> Unit
            }
        }
        val enabled = buildSet {
            if (s.canUndo) add(NoteTool.UNDO)
            if (s.canRedo) add(NoteTool.REDO)
            // Block-shape tools work from a focused block; insertion tools always work.
            add(NoteTool.TABLE)
            add(NoteTool.DIVIDER)
            add(NoteTool.TOGGLE)
            add(NoteTool.HEADING_FOLD)
            add(NoteTool.IMAGE)
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
                if (!s.selection.collapsed &&
                    com.virlin.app.domain.notedoc.flashcardSelection(block, s.selection.start,
                        s.selection.end) != null) add(NoteTool.FLASHCARD)
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
                NoteDocBlockType.TOGGLE -> "Toggle"
                else -> "Normal text"
            },
            fontLabel = run?.fontFamily ?: "Inter",
            sizeLabel = (run?.fontSizeSp ?: defaultSizeFor(block?.type)).toString(),
            active = active,
            enabled = enabled,
        )
    }

    fun selectedFlashcardContent(): NoteDocBlock? {
        val s = _state.value
        val block = s.focusedBlock ?: return null
        return com.virlin.app.domain.notedoc.flashcardSelection(block, s.selection.start,
            s.selection.end)
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
            NoteDocBlockType.CODE -> 14
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

internal const val STALE_SAVE_TEXT = "This note was changed somewhere else (for example on the desktop) " +
    "after you opened it. Your edits here are not saved yet."

private fun ActionResult<*>.isStale(): Boolean = this is ActionResult.Rejected &&
    (reason as? com.virlin.app.domain.action.DomainError.PlacementRejected)?.reason ==
    com.virlin.app.domain.action.NoteDocMessages.STALE

/** Why a save was refused, in words the user can act on. */
private fun ActionResult<*>.refusalText(): String = when (this) {
    is ActionResult.Success -> ""
    is ActionResult.Rejected -> when (val r = reason) {
        is com.virlin.app.domain.action.DomainError.PlacementRejected ->
            if (r.reason == com.virlin.app.domain.action.NoteDocMessages.STALE) STALE_SAVE_TEXT else r.reason
        else -> "The note was refused: $r"
    }
    is ActionResult.NotFound -> "The note's owner was not found."
    is ActionResult.Failure -> "Saving failed: ${cause.message ?: cause.javaClass.simpleName}"
}

internal const val PLAIN_PASTE_FALLBACK =
    "This paste's formatting could not be imported. All of its text was kept as plain paragraphs."

private fun normalizedPaste(value: String) = value.replace("\r\n", "\n").replace('\r', '\n').trim()

/** Whether [inserted] is the clipboard's plain text. Runs on every edit, so it rejects cheaply. */
private fun sameText(inserted: String, clip: String): Boolean {
    if (clip.length > inserted.length * 2 + 16 || inserted.length > clip.length * 2 + 16) return false
    return normalizedPaste(inserted) == normalizedPaste(clip)
}

private fun htmlTextMatches(html: String, inserted: String): Boolean =
    runCatching { normalizedPaste(org.jsoup.Jsoup.parse(html).text()) == normalizedPaste(inserted) }
        .getOrDefault(false)

/** Off the main thread. Null means the paste's formatting could not be imported. */
private fun formatLargePaste(insertedText: String, plainText: String, htmlText: String?,
    imageResolver: ((String) -> String?)?): List<NoteDocBlock>? {
    val usableHtml = htmlText?.takeIf { sameText(insertedText, plainText) || htmlTextMatches(it, insertedText) }
    val rich = usableHtml?.let { html ->
        runCatching { NotePasteParser.parse(insertedText, html, imageResolver) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
    val parsed = rich ?: NotePasteParser.parse(insertedText, null, imageResolver).takeIf { it.isNotEmpty() }
        ?: return null
    return NotePasteSafety.splitLongParagraphs(parsed)
}
