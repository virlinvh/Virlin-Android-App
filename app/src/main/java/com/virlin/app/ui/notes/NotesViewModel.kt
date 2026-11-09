package com.virlin.app.ui.notes

import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.NewTaskNote
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteDocVideo
import com.virlin.app.domain.notedoc.NoteDocAudio
import com.virlin.app.domain.notedoc.NoteBlockMove
import com.virlin.app.domain.notedoc.arrangeableNoteBlocks
import com.virlin.app.domain.notedoc.moveNoteBlock
import com.virlin.app.domain.notedoc.NoteListStyles
import com.virlin.app.domain.notedoc.NoteRuns
import com.virlin.app.domain.notedoc.NoteTextCases
import com.virlin.app.domain.notedoc.NoteWordCount
import com.virlin.app.domain.notedoc.NotePasteParser
import com.virlin.app.domain.notedoc.GoogleDocsPaste
import com.virlin.app.domain.notedoc.NotePasteReport
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
    /** The bullet or numbering style strip under the rail; [type] is BULLET or NUMBERED. */
    data class ListStyle(val type: NoteDocBlockType) : NoteDialog
    data object AddBlock : NoteDialog
    /** The rows × columns strip that creates a table. */
    data object Table : NoteDialog
    /** The strip of tools for the table whose cell is focused. */
    data object TableTools : NoteDialog
    /** The strip of tools for the selected image. */
    data object ImageTools : NoteDialog
    /** The full-screen editor for image block [blockId], opened on [mode]. */
    data class ImageEditor(val blockId: String, val mode: NoteImageMode) : NoteDialog
    /** The YouTube embed panel under the rail; [editBlockId] set when changing an embedded clip. */
    data class Video(val editBlockId: String? = null) : NoteDialog
    /** The voice recorder strip under the rail. */
    data object Audio : NoteDialog
    /** The text-to-audio popup. */
    data object TextToAudio : NoteDialog
    /** The strip under the rail that renames this note. */
    data object Rename : NoteDialog
    /** The print strip under the rail: paper and page options, Print and Save PDF. */
    data object Print : NoteDialog
    /** The export strip under the rail: PDF, Word, Markdown or Image and that format's options. */
    data object Export : NoteDialog
    data class Link(val initialUrl: String, val hasSelection: Boolean) : NoteDialog
    data class Message(val text: String) : NoteDialog
    /** What the last paste carried and what the note kept (the Paste details dialog). */
    data object PasteDetails : NoteDialog
    /** The clipboard holds a picture: paste it, or choose one from the device. */
    data object ImageSource : NoteDialog
    /** Explains document import before the system file picker opens. */
    data object ImportDocument : NoteDialog
    /** The "Paste from" strip under the rail: the clipboard pasted the way the chosen source formats copies. */
    data object PasteFrom : NoteDialog
    /** Words and characters in the whole note. */
    data object WordCount : NoteDialog
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
    /** The table cell holding the caret; it counts only while its table is [focusedBlockId]. */
    val focusedCell: NoteCellRef? = null,
    /** A cell the editor should move keyboard focus into (after creating a table or a row). */
    val cellFocusRequest: NoteCellRef? = null,
    /** The image block tapped for editing; any text taking the caret clears it. */
    val selectedImageId: String? = null,
    /** The video block whose player is open; one plays at a time. Never stored. */
    val playingVideoId: String? = null,
    /** Blocks picked in multi-select; while any are picked, formatting tools act on all of them. Never stored. */
    val blockSelection: Set<String> = emptySet(),
) {
    val focusedBlock: NoteDocBlock? get() = blocks.firstOrNull { it.id == focusedBlockId }

    val selectedImage: NoteDocBlock? get() =
        selectedImageId?.let { id -> blocks.firstOrNull { it.id == id && it.type == NoteDocBlockType.IMAGE } }

    /** [focusedCell] when it is live: its table is focused and still has that cell. */
    val activeCell: NoteCellRef? get() {
        val cell = focusedCell ?: return null
        val table = focusedBlock?.takeIf { it.id == cell.blockId }?.table ?: return null
        return cell.takeIf { it.row < table.rows && it.col < table.cols }
    }
}

data class NoteCellRef(val blockId: String, val row: Int, val col: Int)

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
    /** The stored rich (v2) tree the next save merges into, or null for a block-only note. */
    private var baseRich: String? = null
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
        baseRich = doc?.richDocument
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
        if (focused) clearImageSelection()
        val s = _state.value
        if (focused) {
            if (s.focusedBlockId != blockId) {
                val b = s.blocks.firstOrNull { it.id == blockId }
                ui = s.copy(
                    focusedBlockId = blockId,
                    selection = TextRange(b?.text?.length ?: 0),
                    caretMarks = emptySet(),
                    focusedCell = null,
                    dialog = s.dialog.takeUnless { it == NoteDialog.TableTools },
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
    /** Plain-language account of the last clipboard paste; counts only, never its content. */
    var lastPasteReport: String? = null
        private set

    private fun recordPaste(meta: NotePasteReport.ClipMeta?, plain: String, html: String?,
        htmlUsed: Boolean, blocks: List<NoteDocBlock>) {
        lastPasteReport = runCatching { NotePasteReport.describe(meta, plain, html, htmlUsed, blocks) }.getOrNull()
    }

    fun pasteStructured(blockId: String, newText: String, plainText: String,
        htmlText: String? = null, imageResolver: ((String) -> String?)? = null,
        clipSummary: NotePasteReport.ClipMeta? = null): Boolean {
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
        // Google Docs' copy can differ from the inserted text in spacing and chips; its HTML is
        // trusted when the letters match. Its pictures are downloaded, so that runs off the main thread.
        val docsMatches = !clipboardMatches && htmlText != null && insertedText.length >= 2 &&
            GoogleDocsPaste.isGoogleDocsHtml(htmlText) && GoogleDocsPaste.textMatches(htmlText, insertedText)
        val docsPictures = htmlText != null && (clipboardMatches || docsMatches) &&
            htmlText.contains("googleusercontent.com", ignoreCase = true) &&
            GoogleDocsPaste.isGoogleDocsHtml(htmlText) && GoogleDocsPaste.hasRemoteImages(htmlText)
        if (insertedText.length > NotePasteSafety.BACKGROUND_CHARS || largeHtml && (clipboardMatches || docsMatches) ||
            docsPictures) {
            pasteInBackground(original, from, removed, insertedText, plainText, htmlText, imageResolver, clipSummary)
            return true
        }
        // Android may supply a different plain-text representation alongside semantic HTML.
        // Only trust that HTML when its visible text is the text the IME actually inserted.
        // HTML far larger than the insertion cannot be its source (a typed word while a long
        // answer sits on the clipboard), so it is not parsed for that edit.
        val htmlMatches = docsMatches || !clipboardMatches && htmlText != null && !largeHtml &&
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
        if (clipboardMatches || htmlMatches) recordPaste(clipSummary, insertedText, htmlText,
            richImport != null && usableHtml != null,
            richImport ?: plainAttempt?.getOrNull().orEmpty())
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
        plainText: String, htmlText: String?, imageResolver: ((String) -> String?)?,
        clipSummary: NotePasteReport.ClipMeta? = null) {
        ui = _state.value.copy(pasting = true)
        pasteJob = viewModelScope.launch {
            val formatted = withContext(pasteDispatcher) {
                runCatching { formatLargePaste(insertedText, plainText, htmlText, imageResolver) }.getOrNull()
            }
            val blocks = formatted ?: NotePasteSafety.plainParagraphs(insertedText)
            recordPaste(clipSummary, insertedText, htmlText,
                formatted != null && htmlText != null && sameText(insertedText, plainText), blocks)
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
            if (styleChangeRefused(s, plain)) return
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
            listStyle = block.listStyle.takeIf { nextType == block.type },
        )
        val index = s.blocks.indexOfFirst { it.id == block.id }
        val blocks = s.blocks.toMutableList()
        // A new line under a collapsed toggle heading opens it, so the line is never typed hidden.
        blocks[index] = block.copy(text = head, runs = headRuns, checked = false,
            isCollapsed = block.isCollapsed && !block.toggleHeading)
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
     * The block that [onBackspaceAtStart] would merge [blockId] into, or null when it would
     * restyle the block, remove a non-text block above, or do nothing. Reads only.
     */
    fun backspaceMergeTarget(blockId: String): String? {
        val blocks = _state.value.blocks
        val index = blocks.indexOfFirst { it.id == blockId }
        if (index <= 0) return null
        val block = blocks[index]
        if (block.type != NoteDocBlockType.PARAGRAPH || block.indent > 0) return null
        return blocks[index - 1].takeIf { it.type.isTextual }?.id
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
                listStyle = null,
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

    /**
     * Gives this note its own name (one line, at most [MAX_TITLE] characters) and closes the
     * rename strip. Only the note's stored title changes; the task or mind-map item it belongs to
     * keeps its name. False for a blank name, which changes nothing.
     */
    fun renameNote(name: String): Boolean {
        val title = name.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE).trimEnd()
        if (title.isEmpty() || _state.value.damaged || _state.value.loading) return false
        if (title != _state.value.title) onTitleChanged(title)
        if (_state.value.dialog == NoteDialog.Rename) closeDialog()
        return true
    }

    fun onChecklistToggled(blockId: String) {
        pushUndo()
        mutateBlock(blockId) { it.copy(checked = !it.checked) }
    }

    fun onTableCellChanged(blockId: String, row: Int, col: Int, value: String) {
        pushUndo(coalesceWith = blockId)
        mutateBlock(blockId) { b -> b.table?.let { b.copy(table = it.withCell(row, col, value)) } ?: b }
    }

    /**
     * A table cell took the caret. The table becomes the focused block and the cell the target of
     * Bold, colour, size and the other text styles; the table tools strip opens when the caret
     * arrives from outside the table.
     */
    fun onTableCellFocused(blockId: String, row: Int, col: Int) {
        clearImageSelection()
        val s = _state.value
        val cell = NoteCellRef(blockId, row, col)
        if (s.focusedBlockId == blockId && s.focusedCell == cell) return
        val text = s.blocks.firstOrNull { it.id == blockId }?.table?.cell(row, col) ?: return
        val entering = s.focusedBlockId != blockId
        ui = s.copy(
            focusedBlockId = blockId,
            focusedCell = cell,
            selection = TextRange(text.length),
            caretMarks = emptySet(),
            dialog = if (entering && (s.dialog == null || s.dialog == NoteDialog.Table)) NoteDialog.TableTools else s.dialog,
        )
    }

    /** Typing or caret movement in a cell; formatting follows the text like it does in a line. */
    fun onTableCellEdited(blockId: String, row: Int, col: Int, text: String, selection: TextRange) {
        onTableCellFocused(blockId, row, col)
        val s = _state.value
        val table = s.blocks.firstOrNull { it.id == blockId }?.table ?: return
        val old = table.cell(row, col)
        if (old == text) {
            if (s.selection != selection) ui = s.copy(selection = selection, caretMarks = emptySet())
            return
        }
        pushUndo(coalesceWith = "$blockId:$row:$col")
        val (from, removed, inserted) = NoteRuns.diff(old, text)
        var runs = NoteRuns.shiftForEdit(table.runsAt(row, col), from, removed, inserted, text.length)
        if (inserted > 0) s.caretMarks.forEach { mark ->
            runs = NoteRuns.applyMark(runs, text.length, from, from + inserted, mark, add = true)
        }
        ui = s.copy(
            blocks = s.blocks.map { if (it.id == blockId) it.copy(table = table.withCellContent(row, col, text, runs)) else it },
            selection = selection,
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
    }

    fun onCellFocusRequestHandled() {
        if (_state.value.cellFocusRequest != null) ui = _state.value.copy(cellFocusRequest = null)
    }

    /** Adds a blank row above the first row ([atTop]) or below the last one. */
    fun addTableRow(blockId: String, atTop: Boolean) {
        val table = _state.value.blocks.firstOrNull { it.id == blockId }?.table ?: return
        if (table.rows >= NoteDocTable.MAX_ROWS) return
        changeTable(blockId, table.withRowAt(if (atTop) 0 else table.rows))
    }

    /** Adds a blank column after the last one. */
    fun addTableColumn(blockId: String) {
        val table = _state.value.blocks.firstOrNull { it.id == blockId }?.table ?: return
        if (table.cols >= NoteDocTable.MAX_COLS) return
        changeTable(blockId, table.withColumnAt(table.cols))
    }

    /** Inserts a blank [rows] × [cols] table (clamped to the supported size) after the focused block. */
    fun insertTable(rows: Int, cols: Int) {
        insertBlock(NoteDocBlockType.TABLE,
            table = NoteDocTable.blank(rows.coerceIn(1, NoteDocTable.MAX_ROWS), cols.coerceIn(1, NoteDocTable.MAX_COLS)))
        val s = _state.value
        // insertBlock puts the caret on the empty line it adds right after the table.
        val created = s.blocks.getOrNull(s.blocks.indexOfFirst { it.id == s.focusedBlockId } - 1)
            ?.takeIf { it.type == NoteDocBlockType.TABLE }
        if (created == null) { closeDialog(); return }
        val first = NoteCellRef(created.id, 0, 0)
        ui = s.copy(focusedBlockId = created.id, focusedCell = first, cellFocusRequest = first,
            selection = TextRange.Zero, dialog = NoteDialog.TableTools)
    }

    // ---------------------------------------------------------------- table tools (focused cell)

    /** What the table tools strip offers for the focused cell's table. */
    fun tableToolState(): NoteTableToolState? {
        val s = _state.value
        val cell = s.activeCell ?: return null
        val table = s.focusedBlock?.table ?: return null
        return NoteTableToolState(
            rows = table.rows, cols = table.cols, row = cell.row, col = cell.col,
            headerRow = table.headerRow, headerColumn = table.headerColumn, gridLines = table.gridLines,
            columnWidth = table.widthAt(cell.col), fill = table.fillAt(cell.row, cell.col),
        )
    }

    fun tableInsertRow(below: Boolean) = editFocusedTable { table, cell ->
        if (table.rows >= NoteDocTable.MAX_ROWS) return@editFocusedTable null
        val at = if (below) cell.row + 1 else cell.row
        table.withRowAt(at) to cell.copy(row = at)
    }

    fun tableInsertColumn(right: Boolean) = editFocusedTable { table, cell ->
        if (table.cols >= NoteDocTable.MAX_COLS) return@editFocusedTable null
        val at = if (right) cell.col + 1 else cell.col
        table.withColumnAt(at) to cell.copy(col = at)
    }

    fun tableDeleteRow() = editFocusedTable { table, cell ->
        if (table.rows <= 1) return@editFocusedTable null
        table.withoutRow(cell.row) to cell.copy(row = cell.row.coerceAtMost(table.rows - 2))
    }

    fun tableDeleteColumn() = editFocusedTable { table, cell ->
        if (table.cols <= 1) return@editFocusedTable null
        table.withoutColumn(cell.col) to cell.copy(col = cell.col.coerceAtMost(table.cols - 2))
    }

    fun tableToggleHeaderRow() = editFocusedTable { table, cell -> table.copy(headerRow = !table.headerRow) to cell }

    fun tableToggleHeaderColumn() = editFocusedTable { table, cell -> table.copy(headerColumn = !table.headerColumn) to cell }

    fun tableToggleGridLines() = editFocusedTable { table, cell -> table.copy(gridLines = !table.gridLines) to cell }

    /** One step narrower or wider for the focused column; automatic width starts from the default. */
    fun tableStepColumnWidth(delta: Int) = editFocusedTable { table, cell ->
        val current = table.widthAt(cell.col) ?: TABLE_DEFAULT_COL_WIDTH
        val next = (current + delta * TABLE_COL_WIDTH_STEP).coerceIn(NoteDocTable.MIN_COL_WIDTH, NoteDocTable.MAX_COL_WIDTH)
        if (next == table.widthAt(cell.col)) return@editFocusedTable null
        table.withColumnWidth(cell.col, next) to cell
    }

    /** Every column back to automatic width, sharing the page width evenly. */
    fun tableFitColumns() = editFocusedTable { table, cell ->
        if (table.colWidths.isEmpty()) return@editFocusedTable null
        table.copy(colWidths = emptyList()) to cell
    }

    fun tableShadeCell(argb: Long?) = editFocusedTable { table, cell ->
        table.withCellFill(cell.row, cell.col, argb) to cell
    }

    /** Removes the focused table; the caret moves to the line after it (or a new empty one). */
    fun tableDelete() {
        val s = _state.value
        val cell = s.activeCell ?: return
        val index = s.blocks.indexOfFirst { it.id == cell.blockId }
        if (index < 0) return
        pushUndo()
        val remaining = s.blocks.toMutableList().apply { removeAt(index) }
        val next = remaining.getOrNull(index)?.takeIf { it.type.isTextual }
            ?: remaining.getOrNull(index - 1)?.takeIf { it.type.isTextual }
        val blocks = if (next == null) remaining.apply { add(index.coerceAtMost(size), newBlock()) } else remaining
        val focus = next ?: blocks[index.coerceAtMost(blocks.size - 1)]
        ui = s.copy(
            blocks = blocks, focusedBlockId = focus.id, focusedCell = null,
            selection = TextRange(focus.text.length), caretMarks = emptySet(),
            save = NoteSaveState.EDITED, dialog = null,
        )
        scheduleSave()
    }

    /**
     * Applies one table-tool change to the focused cell's table. [edit] returns the new table and
     * the cell to keep the caret in, or null for nothing to do. A rich note whose stored table
     * cannot hold the change (merged cells from another editor) is told so and left unchanged.
     */
    private inline fun editFocusedTable(edit: (NoteDocTable, NoteCellRef) -> Pair<NoteDocTable, NoteCellRef>?) {
        val s = _state.value
        val cell = s.activeCell ?: return
        val table = s.focusedBlock?.table ?: return
        val (next, caret) = edit(table, cell) ?: return
        if (next == table && caret == cell) return
        changeTable(cell.blockId, next, caret)
    }

    private fun changeTable(blockId: String, next: NoteDocTable, caret: NoteCellRef? = null) {
        val s = _state.value
        val updated = s.blocks.map { if (it.id == blockId) it.copy(table = next) else it }
        if (!richDocumentAccepts(updated)) {
            ui = s.copy(dialog = NoteDialog.Message(
                "This table came from another editor with merged or multi-part cells, so it can't be changed " +
                    "this way here. Nothing was changed."))
            return
        }
        pushUndo()
        val before = s.blocks.firstOrNull { it.id == blockId }?.table
        val oldText = s.focusedCell?.let { before?.cell(it.row, it.col) }
        val caretText = caret?.let { next.cell(it.row, it.col) }
        val moved = caret != null && (caret != s.focusedCell || caretText != oldText)
        ui = s.copy(
            blocks = updated,
            save = NoteSaveState.EDITED,
            focusedCell = caret ?: s.focusedCell,
            cellFocusRequest = caret?.takeIf { it != s.focusedCell } ?: s.cellFocusRequest,
            selection = if (moved) TextRange(caretText.orEmpty().length) else s.selection,
        )
        scheduleSave()
    }

    // ---------------------------------------------------------------- tools

    fun onTool(tool: NoteTool) {
        val s = _state.value
        when (tool) {
            NoteTool.UNDO -> undo()
            NoteTool.REDO -> redo()
            NoteTool.PARAGRAPH ->
                if (s.dialog == NoteDialog.Paragraph) closeDialog() else openDialog(NoteDialog.Paragraph)
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
            NoteTool.ARRANGE_BLOCKS -> Unit // Arrange mode belongs to the route; moves come through moveBlock.
            NoteTool.FORMATTING -> Unit // Page formatting is view state kept by the route beside the note.
            NoteTool.IMPORT -> openDialog(NoteDialog.ImportDocument)
            NoteTool.PASTE_FROM -> if (s.dialog == NoteDialog.PasteFrom) closeDialog() else openDialog(NoteDialog.PasteFrom)
            NoteTool.TEXT_COLOR ->
                if (s.dialog == NoteDialog.TextColor) closeDialog() else openDialog(NoteDialog.TextColor)
            NoteTool.HIGHLIGHT ->
                if (s.dialog == NoteDialog.Highlight) closeDialog() else openDialog(NoteDialog.Highlight)
            NoteTool.ALIGN_LEFT -> setAlign(NoteDocAlign.LEFT)
            NoteTool.ALIGN_CENTER -> setAlign(NoteDocAlign.CENTER)
            NoteTool.ALIGN_RIGHT -> setAlign(NoteDocAlign.RIGHT)
            NoteTool.BULLETS -> onListTool(NoteDocBlockType.BULLET)
            NoteTool.NUMBERING -> onListTool(NoteDocBlockType.NUMBERED)
            NoteTool.CHECKLIST -> toggleBlockType(NoteDocBlockType.CHECKLIST)
            NoteTool.QUOTE -> toggleBlockType(NoteDocBlockType.QUOTE)
            NoteTool.INDENT -> changeIndent(+1)
            NoteTool.OUTDENT -> changeIndent(-1)
            NoteTool.LINE_SPACING ->
                if (s.dialog == NoteDialog.LineSpacing) closeDialog() else openDialog(NoteDialog.LineSpacing)
            NoteTool.LINK -> {
                val existing = currentRun()?.linkUrl.orEmpty()
                openDialog(NoteDialog.Link(existing, !s.selection.collapsed))
            }
            NoteTool.PROMPT -> toggleBlockType(NoteDocBlockType.PROMPT)
            NoteTool.TABLE -> when {
                s.dialog == NoteDialog.Table || s.dialog == NoteDialog.TableTools -> closeDialog()
                s.activeCell != null -> openDialog(NoteDialog.TableTools)
                else -> openDialog(NoteDialog.Table)
            }
            NoteTool.DIVIDER -> insertBlock(NoteDocBlockType.DIVIDER)
            // The route launches Android's picker, then calls insertImage with a durable copy.
            NoteTool.IMAGE -> Unit
            NoteTool.YOUTUBE -> if (s.dialog is NoteDialog.Video) closeDialog() else openDialog(NoteDialog.Video())
            NoteTool.AUDIO -> if (s.dialog == NoteDialog.Audio) closeDialog() else openDialog(NoteDialog.Audio)
            NoteTool.TEXT_TO_AUDIO -> openDialog(NoteDialog.TextToAudio)
            NoteTool.RENAME -> if (s.dialog == NoteDialog.Rename) closeDialog() else openDialog(NoteDialog.Rename)
            NoteTool.DUPLICATE -> Unit // The route supplies the domain copy through duplicateNote.
            NoteTool.PRINT -> if (s.dialog == NoteDialog.Print) closeDialog() else openDialog(NoteDialog.Print)
            NoteTool.EXPORT -> if (s.dialog == NoteDialog.Export) closeDialog() else openDialog(NoteDialog.Export)
            NoteTool.TOGGLE -> {
                val index = s.blocks.indexOfFirst { it.id == s.focusedBlockId }
                // Selected text goes inside a new toggle; with only a caret a new toggle is added.
                if (index >= 0 && !s.selection.collapsed) wrapInToggle(index..index)
                else insertBlock(NoteDocBlockType.TOGGLE)
            }
            NoteTool.DELETE -> deleteSelection()
            NoteTool.CHANGE_CASE -> cycleTextCase()
            NoteTool.WORD_COUNT -> openDialog(NoteDialog.WordCount)
        }
    }

    /** Words and characters across the whole note, computed when asked (never on keystrokes). */
    fun wordCount(): NoteWordCount.Stats = NoteWordCount.of(_state.value.blocks)

    /**
     * Change case: each tap moves the selection (or, with only a caret, the word under it) to
     * the next of ALL CAPS → Title Case → lowercase. Only letters change and the length never
     * does, so marks, links and the selection stay where they were. Code blocks and inline
     * maths are left alone.
     */
    fun cycleTextCase(): Boolean {
        val s = _state.value
        if (s.pasting || s.loading || s.damaged) return false
        val index = s.blocks.indexOfFirst { it.id == s.focusedBlockId }
        val block = s.blocks.getOrNull(index) ?: return false
        if (!caseEditable(block)) {
            if (block.type == NoteDocBlockType.CODE) {
                ui = s.copy(dialog = NoteDialog.Message("Case isn't changed inside code or maths."))
            }
            return false
        }
        val range = if (s.selection.collapsed) {
            NoteTextCases.wordAt(block.text, s.selection.start)?.let { it.first until it.last + 1 } ?: return false
        } else {
            val a = s.selection.min.coerceIn(0, block.text.length)
            a until s.selection.max.coerceIn(a, block.text.length)
        }
        return applyTextCase(listOf(index to range))
    }

    /** [cycleTextCase] for a cross-block selection made in [selectableNoteText]. */
    fun cycleDocumentRangeCase(start: Int, end: Int): Boolean {
        val s = _state.value
        if (s.pasting || s.loading || s.damaged || start >= end) return false
        val spans = projectedBlocks(s.blocks)
        val targets = s.blocks.indices.mapNotNull { i ->
            val block = s.blocks[i]
            val span = spans[i]
            if (span.textStart < 0 || !caseEditable(block)) return@mapNotNull null
            val a = (start - span.textStart).coerceIn(0, block.text.length)
            val b = (end - span.textStart).coerceIn(a, block.text.length)
            if (a < b) i to (a until b) else null
        }
        return applyTextCase(targets)
    }

    /** [cycleTextCase] over the whole text of every selected block that has editable text. */
    fun cycleBlocksCase(ids: Set<String>): Boolean {
        val s = _state.value
        if (s.pasting || s.loading || s.damaged) return false
        val targets = s.blocks.indices.mapNotNull { i ->
            val block = s.blocks[i]
            if (block.id !in ids || !caseEditable(block) || block.text.isEmpty()) null
            else i to block.text.indices
        }
        return applyTextCase(targets)
    }

    private fun caseEditable(block: NoteDocBlock) = block.type.isTextual && block.type != NoteDocBlockType.CODE

    // ---------------------------------------------------------------- multi-select formatting

    /** The route's multi-select. While it holds blocks, formatting tools act on all of them. */
    fun setBlockSelection(ids: Set<String>) {
        val s = _state.value
        if (s.blockSelection != ids) ui = s.copy(blockSelection = ids)
    }

    private fun NotesUiState.picked(): List<NoteDocBlock> =
        if (blockSelection.isEmpty()) emptyList() else blocks.filter { it.id in blockSelection }

    /** Font, size, marks and colours go on whole blocks of real text; code keeps its own look. */
    private fun inlineStyleable(block: NoteDocBlock) =
        block.type.isTextual && block.type != NoteDocBlockType.CODE && block.text.isNotEmpty()

    /** Lines that can switch between plain text, headings, lists and quotes without moving anything. */
    private fun shapeChangeable(block: NoteDocBlock) = !block.toggleHeading && block.type in MultiShapeTypes

    /**
     * Rewrites every picked block [transform] changes, as one undo step. With [checkShape] the
     * result is first proved storable in the rich document; otherwise nothing changes and
     * [refusal] is shown.
     */
    private fun mutatePicked(coalesceWith: String? = null, checkShape: Boolean = false,
        refusal: String = "These blocks can't take that style here. Nothing was changed.",
        transform: (NoteDocBlock) -> NoteDocBlock?): Boolean {
        val s = _state.value
        if (s.pasting || s.loading || s.damaged) return false
        val next = s.blocks.map { b -> if (b.id in s.blockSelection) transform(b) ?: b else b }
        if (next == s.blocks) return false
        if (checkShape && !richDocumentAccepts(next)) {
            ui = s.copy(dialog = NoteDialog.Message(refusal))
            return false
        }
        pushUndo(coalesceWith)
        ui = s.copy(blocks = next, save = NoteSaveState.EDITED)
        scheduleSave()
        return true
    }

    private fun togglePickedMark(mark: NoteDocMark) {
        val text = _state.value.picked().filter(::inlineStyleable)
        if (text.isEmpty()) return
        val add = text.any { !NoteRuns.rangeHasMark(it.runs, 0, it.text.length, mark) }
        mutatePicked {
            if (!inlineStyleable(it)) null
            else it.copy(runs = NoteRuns.applyMark(it.runs, it.text.length, 0, it.text.length, mark, add))
        }
    }

    /**
     * Gives every picked line [type], or returns them all to Normal text when they all have it
     * already. Lines that can't change shape (code, prompts, toggles, tables, media) are left alone.
     */
    private fun setPickedType(type: NoteDocBlockType): Boolean {
        val s = _state.value
        val lines = s.picked().filter(::shapeChangeable)
        if (lines.isEmpty() || type !in MultiShapeTypes) {
            ui = s.copy(dialog = NoteDialog.Message(
                if (type in MultiShapeTypes) "None of the selected blocks can take that style."
                else "Pick one block at a time for that style."))
            return false
        }
        return setPickedTo(if (lines.all { it.type == type }) NoteDocBlockType.PARAGRAPH else type) { true }
    }

    /** Makes the picked changeable lines that pass [which] into [next], as one undo step. */
    private fun setPickedTo(next: NoteDocBlockType, which: (NoteDocBlock) -> Boolean): Boolean {
        val s = _state.value
        val first = s.picked().firstOrNull { shapeChangeable(it) && which(it) } ?: return false
        // Lines joining a neighbouring list take that list's style, so the list stays one list.
        val inherited = neighbourListStyle(s.blocks, s.blocks.indexOf(first), next)
        return mutatePicked(checkShape = true,
            refusal = "These blocks can't change to that style here (part of them sits inside a nested part of this note). Nothing was changed.") {
            if (!shapeChangeable(it) || !which(it)) null
            else it.copy(type = next, checked = if (next == NoteDocBlockType.CHECKLIST) it.checked else false,
                listStyle = inherited)
        }
    }

    /** The toolbar for a multi-select: what every picked block shares is shown as active. */
    private fun pickedToolState(s: NotesUiState, picked: List<NoteDocBlock>): NoteToolState {
        val text = picked.filter(::inlineStyleable)
        val lines = picked.filter(::shapeChangeable)
        val textual = picked.filter { it.type.isTextual }
        fun all(mark: NoteDocMark) = text.isNotEmpty() && text.all { NoteRuns.rangeHasMark(it.runs, 0, it.text.length, mark) }
        val first = text.firstOrNull()
        val run = first?.let { NoteRuns.styleAt(it.runs, 0) }
        val sharedType = lines.map { it.type }.distinct().singleOrNull()
        val active = buildSet {
            if (all(NoteDocMark.BOLD)) add(NoteTool.BOLD)
            if (all(NoteDocMark.ITALIC)) add(NoteTool.ITALIC)
            if (all(NoteDocMark.UNDERLINE)) add(NoteTool.UNDERLINE)
            if (all(NoteDocMark.STRIKE)) add(NoteTool.STRIKE)
            when (textual.map { it.align }.distinct().singleOrNull()) {
                NoteDocAlign.CENTER -> add(NoteTool.ALIGN_CENTER)
                NoteDocAlign.RIGHT -> add(NoteTool.ALIGN_RIGHT)
                NoteDocAlign.LEFT -> add(NoteTool.ALIGN_LEFT)
                null -> Unit
            }
            when (sharedType) {
                NoteDocBlockType.BULLET -> add(NoteTool.BULLETS)
                NoteDocBlockType.NUMBERED -> add(NoteTool.NUMBERING)
                NoteDocBlockType.CHECKLIST -> add(NoteTool.CHECKLIST)
                NoteDocBlockType.QUOTE -> add(NoteTool.QUOTE)
                else -> Unit
            }
        }
        val enabled = buildSet {
            if (s.canUndo) add(NoteTool.UNDO)
            if (s.canRedo) add(NoteTool.REDO)
            // The route wraps the picked blocks in a toggle.
            addAll(listOf(NoteTool.TOGGLE, NoteTool.HEADING_FOLD, NoteTool.RENAME, NoteTool.PRINT,
                NoteTool.EXPORT, NoteTool.WORD_COUNT))
            if (canDuplicate) add(NoteTool.DUPLICATE)
            if (lines.isNotEmpty()) addAll(listOf(NoteTool.PARAGRAPH, NoteTool.BULLETS, NoteTool.NUMBERING,
                NoteTool.CHECKLIST, NoteTool.QUOTE))
            if (textual.isNotEmpty()) addAll(listOf(NoteTool.ALIGN_LEFT, NoteTool.ALIGN_CENTER,
                NoteTool.ALIGN_RIGHT, NoteTool.LINE_SPACING))
            if (text.isNotEmpty()) addAll(listOf(NoteTool.BOLD, NoteTool.ITALIC, NoteTool.UNDERLINE,
                NoteTool.STRIKE, NoteTool.FONT, NoteTool.SIZE, NoteTool.TEXT_COLOR, NoteTool.HIGHLIGHT))
        }
        val spacing = textual.map { it.lineSpacing }.distinct().singleOrNull() ?: 1f
        return NoteToolState(
            paragraphLabel = when {
                lines.isEmpty() -> "Normal text"
                sharedType == null -> "Mixed styles"
                else -> paragraphLabelFor(lines.first())
            },
            fontLabel = text.map { NoteRuns.styleAt(it.runs, 0)?.fontFamily }.distinct().singleOrNull() ?: run?.fontFamily ?: "Inter",
            sizeLabel = (run?.fontSizeSp ?: defaultSizeFor(first?.type)).toString(),
            spacingLabel = SPACINGS.firstOrNull { it.first == spacing }?.second
                ?: "%.2f".format(java.util.Locale.US, spacing).trimEnd('0').trimEnd('.'),
            active = active,
            enabled = enabled,
        )
    }

    private fun applyTextCase(targets: List<Pair<Int, IntRange>>): Boolean {
        val s = _state.value
        val segments = targets.map { (i, r) -> NoteTextCases.Segment(s.blocks[i].text, r.first, r.last + 1) }
        val case = NoteTextCases.nextCase(segments) ?: return false
        val blocks = s.blocks.toMutableList()
        targets.forEach { (i, r) ->
            val block = blocks[i]
            val text = NoteTextCases.convert(block.text, r.first, r.last + 1, case)
            // Same length by construction; anything else would move every run, so refuse it.
            if (text.length != block.text.length) return false
            blocks[i] = block.copy(text = text)
        }
        if (blocks == s.blocks) return false
        pushUndo()
        ui = s.copy(blocks = blocks, save = NoteSaveState.EDITED)
        scheduleSave()
        return true
    }

    /**
     * Puts the blocks in [range] inside a new Toggle placed before them, as one undo step, and
     * moves the caret to the toggle's empty title. Refused with a message, changing nothing, when
     * the blocks are already nested as deep as allowed or the stored rich document could not hold
     * the result exactly.
     */
    fun wrapInToggle(range: IntRange): Boolean {
        val s = _state.value
        if (s.pasting || s.loading || s.damaged) return false
        val toggleId = ids.newId("nb")
        val next = wrapBlocksInToggle(s.blocks, range, toggleId)
        if (next == null) {
            ui = s.copy(dialog = NoteDialog.Message("These lines are already nested as deep as a note allows."))
            return false
        }
        if (!richDocumentAccepts(next)) {
            ui = s.copy(dialog = NoteDialog.Message(
                "This part of the note can't be put inside a toggle. Nothing was changed."))
            return false
        }
        pushUndo()
        ui = s.copy(
            blocks = next,
            focusedBlockId = toggleId,
            selection = TextRange.Zero,
            caretMarks = emptySet(),
            save = NoteSaveState.EDITED,
            dialog = null,
        )
        scheduleSave()
        return true
    }

    /** [wrapInToggle] for a cross-block selection made in [selectableNoteText]. */
    fun wrapDocumentRangeInToggle(start: Int, end: Int): Boolean {
        val range = projectedBlockRange(_state.value.blocks, start, end) ?: return false
        return wrapInToggle(range)
    }

    /**
     * Runs the next save's merge ahead of time: a rich note accepts [candidate] only if the merge
     * succeeds and reads back with the same blocks, types and nesting. Block-only notes always do.
     */
    private fun richDocumentAccepts(candidate: List<NoteDocBlock>): Boolean {
        val tree = baseRich ?: return true
        val merged = runCatching {
            com.virlin.app.domain.notedoc.rich.RichNoteAdapter.applyBlockEdits(tree, candidate)
        }.getOrNull() ?: return false
        val projected = runCatching { com.virlin.app.domain.notedoc.rich.RichNoteAdapter.project(merged) }
            .getOrNull() ?: return false
        fun shape(blocks: List<NoteDocBlock>) = blocks.map {
            listOf(it.id, it.type, it.indent, NoteListStyles.storedKey(it.type, it.listStyle))
        }
        return shape(projected) == shape(candidate)
    }

    /**
     * True, after telling the user, when [updated] would leave the note in a shape its stored rich
     * document cannot hold; the change is then not applied, so later saves are not refused.
     */
    private fun styleChangeRefused(s: NotesUiState, updated: NoteDocBlock): Boolean {
        if (richDocumentAccepts(s.blocks.map { if (it.id == updated.id) updated else it })) return false
        ui = s.copy(dialog = NoteDialog.Message(
            "This line can't change to that style here (it sits inside a nested part of this note). Nothing was changed."))
        return true
    }

    /** Removes the selected characters of the focused block as one undo step. */
    fun deleteSelection() {
        val s = _state.value
        if (s.pasting) return
        val block = s.focusedBlock ?: return
        if (!block.type.isTextual || s.selection.collapsed) return
        val a = s.selection.min.coerceIn(0, block.text.length)
        val b = s.selection.max.coerceIn(a, block.text.length)
        if (a == b) return
        pushUndo()
        val text = block.text.removeRange(a, b)
        val updated = block.copy(text = text, runs = NoteRuns.shiftForEdit(block.runs, a, b - a, 0, text.length))
        ui = s.copy(
            blocks = s.blocks.map { if (it.id == block.id) updated else it },
            selection = TextRange(a),
            caretMarks = emptySet(),
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
    }

    /**
     * Removes a "Select across blocks" selection, given as offsets into [selectableNoteText],
     * as one undo step. Returns false when the selection would change nothing.
     */
    fun deleteDocumentRange(start: Int, end: Int): Boolean {
        val s = _state.value
        if (s.pasting) return false
        val remaining = deleteProjectedRange(s.blocks, start, end) ?: return false
        pushUndo()
        ui = s.copy(
            blocks = remaining.ifEmpty { listOf(newBlock()) },
            focusedBlockId = null,
            selection = TextRange.Zero,
            caretMarks = emptySet(),
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
        return true
    }

    /**
     * Copies the selected blocks, each run of neighbouring blocks landing right after itself, as
     * one undo step. Pictures and recordings are shared, never deleted with a block. Returns the
     * copies' ids, or null (with a message, nothing changed) when the note could not store them.
     */
    fun duplicateBlocks(blockIds: Set<String>): List<String>? {
        val s = _state.value
        if (s.pasting || s.loading || s.damaged || s.blocks.none { it.id in blockIds }) return null
        val copies = mutableListOf<String>()
        val next = mutableListOf<NoteDocBlock>()
        val run = mutableListOf<NoteDocBlock>()
        fun flushRun() {
            run.forEach { block -> next += block.copy(id = ids.newId("nb")).also { copies += it.id } }
            run.clear()
        }
        s.blocks.forEach { block ->
            if (block.id in blockIds) run += block else flushRun()
            next += block
        }
        flushRun()
        if (!richDocumentAccepts(next)) {
            ui = s.copy(dialog = NoteDialog.Message(
                "These blocks can't be duplicated here (they sit inside a nested part of this note). Nothing was changed."))
            return null
        }
        pushUndo()
        ui = s.copy(blocks = next, focusedBlockId = null, selection = TextRange.Zero, caretMarks = emptySet(),
            save = NoteSaveState.EDITED)
        scheduleSave()
        return copies
    }

    /**
     * Removes the selected blocks, media included, as one undo step. Refused with a message,
     * changing nothing, when the stored rich document could not hold the result exactly.
     */
    fun deleteBlocks(ids: Set<String>): Boolean {
        val s = _state.value
        if (s.pasting || s.loading || s.damaged) return false
        val remaining = s.blocks.filterNot { it.id in ids }
        if (remaining.size == s.blocks.size) return false
        val next = remaining.ifEmpty { listOf(newBlock()) }
        if (!richDocumentAccepts(next)) {
            ui = s.copy(dialog = NoteDialog.Message(
                "These blocks can't be removed together here (they sit inside a nested part of this note). Nothing was changed."))
            return false
        }
        pushUndo()
        ui = s.copy(
            blocks = next,
            focusedBlockId = null,
            selection = TextRange.Zero,
            caretMarks = emptySet(),
            save = NoteSaveState.EDITED,
        )
        scheduleSave()
        return true
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
        if (s.blockSelection.isNotEmpty()) { togglePickedMark(mark); return }
        val block = s.styleBlock() ?: return
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
        mutateStyleBlock {
            it.copy(runs = NoteRuns.applyMark(it.runs, it.text.length, start, end, mark, add))
        }
    }

    /**
     * The text that inline styles apply to: the focused table cell, seen as a one-line block with
     * the table's id, or else the focused block.
     */
    private fun NotesUiState.styleBlock(): NoteDocBlock? {
        val cell = activeCell ?: return focusedBlock
        val table = focusedBlock?.table ?: return null
        return NoteDocBlock(id = cell.blockId, text = table.cell(cell.row, cell.col), runs = table.runsAt(cell.row, cell.col))
    }

    /** Writes [transform] of [styleBlock] back: into its cell for a table, else into the block. */
    private fun mutateStyleBlock(transform: (NoteDocBlock) -> NoteDocBlock) {
        val s = _state.value
        val target = s.styleBlock() ?: return
        val cell = s.activeCell
        if (cell == null) { mutateBlock(target.id, transform); return }
        val out = transform(target)
        mutateBlock(cell.blockId) { b ->
            b.table?.let { b.copy(table = it.withCellContent(cell.row, cell.col, out.text, out.runs)) } ?: b
        }
    }

    /** Marks in force where the caret sits: the run under it plus anything pending. */
    private fun effectiveCaretMarks(): Set<NoteDocMark> {
        val s = _state.value
        val block = s.styleBlock() ?: return s.caretMarks
        val at = NoteRuns.styleAt(block.runs, s.selection.start)?.marks.orEmpty()
        return at + s.caretMarks
    }

    private fun currentRun(): NoteDocRun? {
        val s = _state.value
        if (s.blockSelection.isNotEmpty())
            return s.picked().firstOrNull(::inlineStyleable)?.let { NoteRuns.styleAt(it.runs, 0) }
        val block = s.styleBlock() ?: return null
        return NoteRuns.styleAt(block.runs, s.selection.start)
    }

    /**
     * Applies a scalar attribute to the selection, or to the whole block when nothing is selected;
     * in multi-select, to the whole text of every picked block.
     */
    private fun applyAttribute(coalesceWith: String? = null, transform: (NoteDocRun) -> NoteDocRun) {
        val s = _state.value
        if (s.blockSelection.isNotEmpty()) {
            mutatePicked(coalesceWith) {
                if (!inlineStyleable(it)) null
                else it.copy(runs = NoteRuns.applyAttribute(it.runs, it.text.length, 0, it.text.length, transform))
            }
            return
        }
        val block = s.styleBlock() ?: return
        if (!block.type.isTextual || block.text.isEmpty()) return
        pushUndo(coalesceWith)
        val start = if (s.selection.collapsed) 0 else s.selection.min
        val end = if (s.selection.collapsed) block.text.length else s.selection.max
        mutateStyleBlock {
            it.copy(runs = NoteRuns.applyAttribute(it.runs, it.text.length, start, end, transform))
        }
    }

    fun setFont(family: String?) = applyAttribute { it.copy(fontFamily = family) }.also { closeDialog() }

    fun setFontSize(sp: Int?) = applyAttribute { it.copy(fontSizeSp = sp) }.also { closeDialog() }

    /** The block (and table cell) and selection a style edit targets, kept while a toolbar field holds focus. */
    data class StyleTarget(val blockId: String, val selection: TextRange, val cell: NoteCellRef? = null)

    fun styleTarget(): StyleTarget? = _state.value.let { s ->
        s.focusedBlockId?.let { StyleTarget(it, s.selection, s.activeCell) }
    }

    /** Puts the caret back on [target] after a typed entry (size, colour code) took focus. */
    private fun restoreStyleTarget(target: StyleTarget) {
        val s = _state.value
        val block = s.blocks.firstOrNull { it.id == target.blockId } ?: return
        val cell = target.cell?.takeIf { c -> block.table?.let { c.row < it.rows && c.col < it.cols } == true }
        val len = cell?.let { block.table!!.cell(it.row, it.col).length } ?: block.text.length
        val selection = TextRange(target.selection.start.coerceIn(0, len), target.selection.end.coerceIn(0, len))
        if (s.focusedBlockId != block.id || s.selection != selection || s.focusedCell != cell) {
            ui = s.copy(focusedBlockId = block.id, selection = selection, focusedCell = cell ?: s.focusedCell)
        }
    }

    /** Toolbar stepper: one step up or down from the current size. A run of steps is one undo step. */
    fun stepFontSize(delta: Int) {
        // In multi-select each piece of text moves from its own size, so headings stay larger.
        pickedKey()?.let { key ->
            mutatePicked(coalesceWith = "size:$key") { b ->
                if (!inlineStyleable(b)) return@mutatePicked null
                val base = defaultSizeFor(b.type)
                b.copy(runs = NoteRuns.applyAttribute(b.runs, b.text.length, 0, b.text.length) { r ->
                    r.copy(fontSizeSp = ((r.fontSizeSp ?: base) + delta).coerceIn(NOTE_MIN_TEXT_SIZE, NOTE_MAX_TEXT_SIZE))
                })
            }
            return
        }
        val current = toolState().sizeLabel.toIntOrNull() ?: return
        applyFontSize(current + delta)
    }

    /** Sets an explicit size (clamped to 8–96), first restoring [target] if a typed entry took focus. */
    fun applyFontSize(sp: Int, target: StyleTarget? = null) {
        if (target != null) restoreStyleTarget(target)
        val size = sp.coerceIn(NOTE_MIN_TEXT_SIZE, NOTE_MAX_TEXT_SIZE)
        val blockId = pickedKey() ?: _state.value.focusedBlockId ?: return
        applyAttribute(coalesceWith = "size:$blockId") { it.copy(fontSizeSp = size) }
    }

    fun setTextColor(argb: Long?) = applyAttribute { it.copy(color = argb) }.also { closeDialog() }

    fun setHighlight(argb: Long?) = applyAttribute { it.copy(highlight = argb) }.also { closeDialog() }

    /**
     * Colour strip pick: applies a text colour or highlight and keeps the strip open so colours can
     * be compared; a run of picks on one block is one undo step. [target] restores the selection a
     * typed colour code took focus from.
     */
    fun pickColor(argb: Long?, highlight: Boolean, target: StyleTarget? = null) {
        if (target != null) restoreStyleTarget(target)
        val blockId = pickedKey() ?: _state.value.focusedBlockId ?: return
        applyAttribute(coalesceWith = (if (highlight) "highlight:" else "color:") + blockId) {
            if (highlight) it.copy(highlight = argb) else it.copy(color = argb)
        }
    }

    /** Text colour and highlight at the caret, for the strips' selected swatch. */
    fun currentColors(): Pair<Long?, Long?> = currentRun().let { it?.color to it?.highlight }

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

    /** Undo-coalescing key for a run of edits on the current multi-select, or null outside one. */
    private fun pickedKey(): String? = _state.value.blockSelection.takeIf { it.isNotEmpty() }
        ?.let { "picked:${it.sorted().hashCode()}" }

    private fun setAlign(align: NoteDocAlign) {
        if (_state.value.blockSelection.isNotEmpty()) {
            mutatePicked { if (it.type.isTextual) it.copy(align = align) else null }
            return
        }
        val id = _state.value.focusedBlockId ?: return
        pushUndo()
        mutateBlock(id) { it.copy(align = align) }
    }

    fun setLineSpacing(multiplier: Float, keepOpen: Boolean = false) {
        if (_state.value.blockSelection.isNotEmpty()) {
            mutatePicked { if (it.type.isTextual) it.copy(lineSpacing = multiplier) else null }
            if (!keepOpen) closeDialog()
            return
        }
        val id = _state.value.focusedBlockId ?: return
        pushUndo()
        mutateBlock(id) { it.copy(lineSpacing = multiplier) }
        if (!keepOpen) closeDialog()
    }

    /** Applying a block type a second time returns the block to a plain paragraph. */
    /** [keepOpen]: picked from the paragraph-style strip, which stays up so styles can be tried in turn. */
    fun setBlockType(type: NoteDocBlockType, keepOpen: Boolean = false) {
        if (toggleBlockType(type) && !keepOpen) closeDialog()
    }

    /** False, with a message shown, when the note could not store the new style. */
    private fun toggleBlockType(type: NoteDocBlockType): Boolean {
        val s = _state.value
        if (s.blockSelection.isNotEmpty()) return setPickedType(type)
        val block = s.focusedBlock ?: run {
            insertBlock(type)
            return true
        }
        val next = if (block.type == type) NoteDocBlockType.PARAGRAPH else type
        // A line joining a neighbouring list takes that list's style, so the list stays one list.
        val inherited = neighbourListStyle(s.blocks, s.blocks.indexOf(block), next)
        val updated = block.copy(
            type = next,
            checked = if (next == NoteDocBlockType.CHECKLIST) block.checked else false,
            promptTitle = if (next == NoteDocBlockType.PROMPT) block.promptTitle ?: "" else null,
            toggleHeading = block.toggleHeading && next.headingLevel > 0,
            isCollapsed = if (block.toggleHeading && next.headingLevel == 0) false else block.isCollapsed,
            listStyle = inherited,
        )
        if (styleChangeRefused(s, updated)) return false
        pushUndo()
        mutateBlock(block.id) { updated }
        return true
    }

    private fun neighbourListStyle(blocks: List<NoteDocBlock>, index: Int, type: NoteDocBlockType): String? {
        if (type != NoteDocBlockType.BULLET && type != NoteDocBlockType.NUMBERED) return null
        val level = blocks.getOrNull(index)?.indent ?: return null
        fun sameLevel(range: IntProgression) = range.asSequence().map { blocks[it] }
            .firstOrNull { it.indent <= level }?.takeIf { it.indent == level && it.type == type }
        return (sameLevel(index - 1 downTo 0) ?: sameLevel(index + 1 until blocks.size))?.listStyle
    }

    /**
     * BULLETS / NUMBERING: makes the focused line that kind of list (if it isn't yet) and opens
     * the style strip under the rail; tapping the same tool again closes the strip.
     */
    private fun onListTool(type: NoteDocBlockType) {
        val s = _state.value
        if ((s.dialog as? NoteDialog.ListStyle)?.type == type) {
            closeDialog()
            return
        }
        if (s.blockSelection.isNotEmpty()) {
            val lines = s.picked().filter(::shapeChangeable)
            if (lines.any { it.type != type } && !setPickedType(type)) return
            openDialog(NoteDialog.ListStyle(type))
            return
        }
        if (s.focusedBlock?.type != type && !toggleBlockType(type)) return
        openDialog(NoteDialog.ListStyle(type))
    }

    /**
     * Gives the whole list the focused line belongs to the [NoteListStyles] style [key] (making the
     * line a [type] list first if needed), as one undo step. Refused with a message, changing
     * nothing, when the stored rich document could not hold the result exactly.
     */
    fun setListStyle(type: NoteDocBlockType, key: String?): Boolean {
        val s = _state.value
        if (s.pasting || s.loading || s.damaged) return false
        if (type != NoteDocBlockType.BULLET && type != NoteDocBlockType.NUMBERED) return false
        if (s.blockSelection.isNotEmpty()) {
            // Every list a picked line of this kind belongs to takes the style.
            val stored = NoteListStyles.storedKey(type, key)
            val group = s.blocks.indices.filter { s.blocks[it].id in s.blockSelection && s.blocks[it].type == type }
                .flatMap { NoteListStyles.listGroup(s.blocks, it) }.toSet()
            if (group.isEmpty()) return false
            val next = s.blocks.mapIndexed { i, b -> if (i in group) b.copy(listStyle = stored) else b }
            if (next == s.blocks) return true
            if (!richDocumentAccepts(next)) {
                ui = s.copy(dialog = NoteDialog.Message("This list can't change style here. Nothing was changed."))
                return false
            }
            pushUndo()
            ui = s.copy(blocks = next, save = NoteSaveState.EDITED)
            scheduleSave()
            return true
        }
        val index = s.blocks.indexOfFirst { it.id == s.focusedBlockId }
        if (index < 0) return false
        val block = s.blocks[index]
        val stored = NoteListStyles.storedKey(type, key)
        val typed = if (block.type == type) s.blocks else s.blocks.toMutableList().also {
            it[index] = block.copy(type = type, checked = false, promptTitle = null, toggleHeading = false,
                isCollapsed = if (block.toggleHeading) false else block.isCollapsed)
        }
        val group = NoteListStyles.listGroup(typed, index).toSet()
        val next = typed.mapIndexed { i, b -> if (i in group) b.copy(listStyle = stored) else b }
        if (next == s.blocks) return true
        if (!richDocumentAccepts(next)) {
            ui = s.copy(dialog = NoteDialog.Message("This list can't change style here. Nothing was changed."))
            return false
        }
        pushUndo()
        ui = s.copy(blocks = next, save = NoteSaveState.EDITED)
        scheduleSave()
        return true
    }

    /** The strip's "None": the focused list line goes back to Normal text. */
    fun removeListFromFocused() {
        val s = _state.value
        if (s.blockSelection.isNotEmpty()) {
            val type = (s.dialog as? NoteDialog.ListStyle)?.type
            val lists = s.picked().filter { shapeChangeable(it) && it.type == type }
            if (lists.isNotEmpty() && !setPickedTo(NoteDocBlockType.PARAGRAPH) { it.type == type }) return
            closeDialog()
            return
        }
        val block = _state.value.focusedBlock ?: return
        if ((block.type == NoteDocBlockType.BULLET || block.type == NoteDocBlockType.NUMBERED) &&
            !toggleBlockType(block.type)) return
        closeDialog()
    }

    /**
     * Paragraph-style popup pick. [toggle] picks the collapsible variant: a toggle heading for
     * Heading 1–5, the Toggle block for Normal text. Picking the exact current style again returns
     * the block to Normal text; switching a toggle heading to its plain heading keeps every block
     * under it and only removes the collapsing.
     */
    fun setParagraphStyle(type: NoteDocBlockType, toggle: Boolean, keepOpen: Boolean = false) {
        if (_state.value.blockSelection.isNotEmpty()) {
            if (toggle) openDialog(NoteDialog.Message("Toggles are made from one block at a time."))
            else if (setPickedType(type) && !keepOpen) closeDialog()
            return
        }
        val targetType = if (toggle && type == NoteDocBlockType.PARAGRAPH) NoteDocBlockType.TOGGLE else type
        val targetToggleHeading = toggle && targetType.headingLevel > 0
        val block = _state.value.focusedBlock
        if (block == null) {
            insertBlock(targetType)
            if (targetToggleHeading) _state.value.focusedBlockId?.let { id ->
                mutateBlock(id) { it.copy(toggleHeading = true) }
            }
            if (!keepOpen) closeDialog()
            return
        }
        val same = block.type == targetType && block.toggleHeading == targetToggleHeading
        val nextType = if (same) NoteDocBlockType.PARAGRAPH else targetType
        val nextToggleHeading = !same && targetToggleHeading
        val updated = block.copy(
            type = nextType,
            checked = false,
            promptTitle = null,
            toggleHeading = nextToggleHeading,
            listStyle = null,
            isCollapsed = when {
                nextType == NoteDocBlockType.TOGGLE || nextToggleHeading -> block.isCollapsed &&
                    (block.type == NoteDocBlockType.TOGGLE || block.toggleHeading)
                block.toggleHeading -> false
                else -> block.isCollapsed
            },
        )
        if (styleChangeRefused(_state.value, updated)) return
        pushUndo()
        mutateBlock(block.id) { updated }
        if (!keepOpen) closeDialog()
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
    /** The block at the top of the editor's screen, reported as the user scrolls. */
    private var viewAnchorId: String? = null

    fun setViewAnchor(blockId: String?) {
        viewAnchorId = blockId
    }

    /**
     * Where a new block goes and its indent: right after the focused block, or — when no field
     * holds focus, e.g. just after the note reopened — right after the block at the top of the
     * screen, so it appears where the user is looking instead of at the end of the note.
     */
    private fun insertionPoint(s: NotesUiState): Pair<Int, Int> {
        s.focusedBlock?.let { f ->
            val i = s.blocks.indexOfFirst { it.id == f.id }
            return (i + 1) to ((f.indent + if (f.type == NoteDocBlockType.TOGGLE) 1 else 0).coerceAtMost(5))
        }
        val anchorIndex = s.blocks.indexOfFirst { it.id == viewAnchorId }
        if (anchorIndex < 0) return s.blocks.size to 0
        val anchor = s.blocks[anchorIndex]
        val visible = visibleNoteBlocks(s.blocks, foldHeadings = false)
        val v = visible.indexOfFirst { it.id == anchor.id }
        // Before the next visible block, so a collapsed toggle's hidden lines stay with it.
        val at = if (v < 0) anchorIndex + 1
            else visible.getOrNull(v + 1)?.let { n -> s.blocks.indexOfFirst { it.id == n.id } } ?: s.blocks.size
        val indent = anchor.indent + if (anchor.type == NoteDocBlockType.TOGGLE && !anchor.isCollapsed) 1 else 0
        return at to indent.coerceAtMost(5)
    }

    fun insertBlock(type: NoteDocBlockType, table: NoteDocTable? = null,
        initialText: String = "") {
        pushUndo()
        val s = _state.value
        val (at, indent) = insertionPoint(s)
        val created = NoteDocBlock(
            id = ids.newId("nb"),
            type = type,
            text = initialText,
            mathMode = if (type == NoteDocBlockType.CODE) initialText == "\\( \\)" else null,
            indent = indent,
            table = if (type == NoteDocBlockType.TABLE) table ?: NoteDocTable.blank(2, 2) else null,
            promptTitle = if (type == NoteDocBlockType.PROMPT) "" else null,
        )
        val blocks = s.blocks.toMutableList()
        blocks.add(at.coerceIn(0, blocks.size), created)
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

    /** Only notes on a mind-map box can be duplicated, and never a note that failed to load. */
    private val canDuplicate: Boolean
        get() = TaskPageTypeKeys.noteTaskId(ownerKey) != null && !_state.value.damaged && !_state.value.loading

    /**
     * Saves any pending edits, then asks [copy] to duplicate the stored note and says where the copy
     * went. This note stays open and unchanged; nothing is copied if the save is refused.
     */
    fun duplicateNote(copy: suspend (String) -> NewTaskNote?) {
        if (!canDuplicate) return
        saveJob?.cancel()
        viewModelScope.launch {
            pasteJob?.takeIf { it.isActive }?.join()
            saveJob?.cancel()
            val dirty = _state.value.save != NoteSaveState.NO_CHANGES && _state.value.save != NoteSaveState.SAVED
            if (dirty && !persist()) {
                if (_state.value.dialog == null) ui = _state.value.copy(dialog = NoteDialog.Message(
                    "This note could not be saved, so it was not duplicated."))
                return@launch
            }
            val note = runCatching { copy(ownerKey) }.getOrNull()
            ui = _state.value.copy(dialog = NoteDialog.Message(
                if (note != null) "Duplicated as “${note.title}”. The copy is on this box's Page, right after this note."
                else "Nothing was duplicated. Write something in this note first, or check that its box still exists."))
        }
    }

    fun toggleCollapsed(blockId: String) {
        val s = _state.value
        val index = s.blocks.indexOfFirst { it.id == blockId }
        if (index < 0 || (s.blocks[index].type != NoteDocBlockType.TOGGLE &&
                s.blocks[index].type.headingLevel == 0)) return
        pushUndo()
        val toggle = s.blocks[index]
        val nextCollapsed = !toggle.isCollapsed
        val headingLevel = { type: NoteDocBlockType -> type.headingLevel }
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
        require(level == null || level in 1..5)
        val s = _state.value
        fun depth(type: NoteDocBlockType) = type.headingLevel
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
        val (at, indent) = insertionPoint(s)
        val image = newBlock(NoteDocBlockType.IMAGE).copy(imagePath = imagePath, indent = indent)
        val following = newBlock().copy(indent = indent)
        val blocks = s.blocks.toMutableList()
        val insertion = at.coerceIn(0, blocks.size)
        blocks.add(insertion, image)
        blocks.add(insertion + 1, following)
        ui = s.copy(blocks = blocks, focusedBlockId = following.id,
            selection = TextRange.Zero, save = NoteSaveState.EDITED, dialog = null)
        scheduleSave()
    }

    /**
     * Places blocks read from an imported document as one undo step, with fresh ids: they replace
     * a blank note, go after the line being edited, or else at the end (also when the stored rich
     * document can't hold them after that line). False, with a message and nothing changed, when
     * they can't be placed.
     */
    fun insertImportedBlocks(imported: List<NoteDocBlock>, fillBlankLine: Boolean = false): Boolean {
        val s = _state.value
        if (s.loading || s.damaged || s.pasting || imported.isEmpty()) return false
        val fresh = imported.map { it.copy(id = ids.newId("nb")) }
        val blank = s.blocks.all { it.type == NoteDocBlockType.PARAGRAPH && it.text.isEmpty() }
        val focused = s.focusedBlock
        val candidates = when {
            blank -> listOf(fresh)
            focused != null -> {
                val at = insertionPoint(s).first.coerceIn(0, s.blocks.size)
                val line = s.blocks.indexOf(focused)
                val fill = if (fillBlankLine && line >= 0 && focused.type == NoteDocBlockType.PARAGRAPH &&
                    focused.text.isEmpty()) s.blocks.take(line) + fresh + s.blocks.drop(line + 1) else null
                listOfNotNull(fill, s.blocks.take(at) + fresh + s.blocks.drop(at), s.blocks + fresh)
            }
            else -> listOf(s.blocks + fresh)
        }
        val next = candidates.firstOrNull { richDocumentAccepts(it) }
        if (next == null) {
            ui = s.copy(dialog = NoteDialog.Message(
                "This note's structure can't take the imported document. Nothing was changed; try a new note."))
            return false
        }
        pushUndo()
        ui = _state.value.copy(blocks = next, focusedBlockId = null, focusedCell = null,
            selection = TextRange.Zero, caretMarks = emptySet(), selectedImageId = null,
            save = NoteSaveState.EDITED, dialog = null)
        scheduleSave()
        return true
    }

    // ---------------------------------------------------------------- image tools (selected image)

    /** A tap on an image selects it and opens its tools strip. */
    fun selectImage(blockId: String) {
        val s = _state.value
        if (s.blocks.none { it.id == blockId && it.type == NoteDocBlockType.IMAGE }) return
        ui = s.copy(selectedImageId = blockId, dialog = NoteDialog.ImageTools)
    }

    /** The image tool with an image selected: show or hide its tools strip. */
    fun toggleImageTools() {
        val s = _state.value
        when {
            s.dialog == NoteDialog.ImageTools -> closeDialog()
            s.selectedImage != null -> ui = s.copy(dialog = NoteDialog.ImageTools)
        }
    }

    private fun clearImageSelection() {
        val s = _state.value
        if (s.selectedImageId == null) return
        ui = s.copy(selectedImageId = null, dialog = s.dialog.takeUnless { it == NoteDialog.ImageTools })
    }

    /** Opens the full-screen editor for the selected image; only images stored in the note can be edited. */
    fun openImageEditor(mode: NoteImageMode) {
        val s = _state.value
        val image = s.selectedImage ?: return
        ui = if (image.imagePath.orEmpty().matches(MANAGED_IMAGE_NAME)) s.copy(dialog = NoteDialog.ImageEditor(image.id, mode))
        else s.copy(dialog = NoteDialog.Message("This image is a link to a file outside the note, so it can't be edited here."))
    }

    /**
     * Points image block [blockId] at [imagePath] (an edited copy or a replacement). The previous
     * file stays on the device, so undo brings the earlier picture back.
     */
    fun replaceImage(blockId: String, imagePath: String) {
        val s = _state.value
        val block = s.blocks.firstOrNull { it.id == blockId && it.type == NoteDocBlockType.IMAGE } ?: return
        if (block.imagePath == imagePath) { ui = s.copy(dialog = NoteDialog.ImageTools, selectedImageId = blockId); return }
        val updated = s.blocks.map { if (it.id == blockId) it.copy(imagePath = imagePath) else it }
        if (!richDocumentAccepts(updated)) {
            ui = s.copy(dialog = NoteDialog.Message("This image came from another editor and can't be changed here. Nothing was changed."))
            return
        }
        pushUndo()
        ui = _state.value.copy(blocks = updated, save = NoteSaveState.EDITED, selectedImageId = blockId,
            dialog = NoteDialog.ImageTools)
        scheduleSave()
    }

    fun deleteSelectedImage() {
        val s = _state.value
        val id = s.selectedImage?.id ?: return
        val index = s.blocks.indexOfFirst { it.id == id }
        val remaining = s.blocks.toMutableList().apply { removeAt(index) }
        if (remaining.isEmpty()) remaining.add(newBlock())
        if (!richDocumentAccepts(remaining)) {
            ui = s.copy(dialog = NoteDialog.Message("This image came from another editor and can't be removed here. Nothing was changed."))
            return
        }
        pushUndo()
        val focus = s.focusedBlockId?.takeIf { f -> remaining.any { it.id == f } }
            ?: remaining[index.coerceAtMost(remaining.size - 1)].id
        ui = _state.value.copy(blocks = remaining, selectedImageId = null, dialog = null, focusedBlockId = focus,
            selection = TextRange(remaining.first { it.id == focus }.text.length), save = NoteSaveState.EDITED)
        scheduleSave()
    }

    // ---------------------------------------------------------------- YouTube videos

    /** Embeds [video] after the block holding the caret, with a paragraph after it to keep writing. */
    fun insertVideo(video: NoteDocVideo): Boolean {
        val s = _state.value
        if (!video.isValid || s.loading || s.damaged) return false
        pushUndo()
        val (at, indent) = insertionPoint(s)
        val block = newBlock(NoteDocBlockType.VIDEO).copy(video = video, indent = indent)
        val following = newBlock().copy(indent = indent)
        val blocks = s.blocks.toMutableList()
        val insertion = at.coerceIn(0, blocks.size)
        blocks.add(insertion, block)
        blocks.add(insertion + 1, following)
        ui = _state.value.copy(blocks = blocks, focusedBlockId = following.id,
            selection = TextRange.Zero, save = NoteSaveState.EDITED, dialog = null)
        scheduleSave()
        return true
    }

    /** New clip times (or details) for embedded video [blockId]. */
    fun updateVideo(blockId: String, video: NoteDocVideo): Boolean {
        val s = _state.value
        val block = s.blocks.firstOrNull { it.id == blockId && it.type == NoteDocBlockType.VIDEO } ?: return false
        if (!video.isValid) return false
        if (block.video == video) { closeDialog(); return true }
        val updated = s.blocks.map { if (it.id == blockId) it.copy(video = video) else it }
        if (!richDocumentAccepts(updated)) {
            ui = s.copy(dialog = NoteDialog.Message("This video came from another editor and can't be changed here. Nothing was changed."))
            return false
        }
        pushUndo()
        ui = _state.value.copy(blocks = updated, save = NoteSaveState.EDITED, dialog = null,
            playingVideoId = s.playingVideoId.takeUnless { it == blockId })
        scheduleSave()
        return true
    }

    fun deleteVideo(blockId: String): Boolean {
        val s = _state.value
        val index = s.blocks.indexOfFirst { it.id == blockId && it.type == NoteDocBlockType.VIDEO }
        if (index < 0) return false
        val remaining = s.blocks.toMutableList().apply { removeAt(index) }
        if (remaining.isEmpty()) remaining.add(newBlock())
        if (!richDocumentAccepts(remaining)) {
            ui = s.copy(dialog = NoteDialog.Message("This video came from another editor and can't be removed here. Nothing was changed."))
            return false
        }
        pushUndo()
        val focus = s.focusedBlockId?.takeIf { f -> remaining.any { it.id == f } }
            ?: remaining[index.coerceAtMost(remaining.size - 1)].id
        ui = _state.value.copy(blocks = remaining, focusedBlockId = focus,
            selection = TextRange(remaining.first { it.id == focus }.text.length), save = NoteSaveState.EDITED,
            dialog = s.dialog.takeUnless { it is NoteDialog.Video && it.editBlockId == blockId },
            playingVideoId = s.playingVideoId.takeUnless { it == blockId })
        scheduleSave()
        return true
    }

    /** Opens the player of video block [blockId] (closing any other), or closes it with null. */
    fun playVideo(blockId: String?) {
        val s = _state.value
        if (blockId != null && s.blocks.none { it.id == blockId && it.type == NoteDocBlockType.VIDEO && it.video != null }) return
        if (s.playingVideoId != blockId) ui = s.copy(playingVideoId = blockId)
    }

    // ---------------------------------------------------------------- Voice recordings

    /** Puts [audio] after the block holding the caret, with a paragraph after it to keep writing. */
    fun insertAudio(audio: NoteDocAudio): Boolean {
        val s = _state.value
        if (!audio.isValid || s.loading || s.damaged) return false
        pushUndo()
        val (at, indent) = insertionPoint(s)
        val block = newBlock(NoteDocBlockType.AUDIO).copy(audio = audio, indent = indent)
        val following = newBlock().copy(indent = indent)
        val blocks = s.blocks.toMutableList()
        val insertion = at.coerceIn(0, blocks.size)
        blocks.add(insertion, block)
        blocks.add(insertion + 1, following)
        ui = _state.value.copy(blocks = blocks, focusedBlockId = following.id,
            selection = TextRange.Zero, save = NoteSaveState.EDITED, dialog = null)
        scheduleSave()
        return true
    }

    /** Removes recording block [blockId]. Its file stays on disk so undo can bring it back. */
    fun deleteAudio(blockId: String): Boolean {
        val s = _state.value
        val index = s.blocks.indexOfFirst { it.id == blockId && it.type == NoteDocBlockType.AUDIO }
        if (index < 0) return false
        val remaining = s.blocks.toMutableList().apply { removeAt(index) }
        if (remaining.isEmpty()) remaining.add(newBlock())
        if (!richDocumentAccepts(remaining)) {
            ui = s.copy(dialog = NoteDialog.Message("This recording came from another editor and can't be removed here. Nothing was changed."))
            return false
        }
        pushUndo()
        val focus = s.focusedBlockId?.takeIf { f -> remaining.any { it.id == f } }
            ?: remaining[index.coerceAtMost(remaining.size - 1)].id
        ui = _state.value.copy(blocks = remaining, focusedBlockId = focus,
            selection = TextRange(remaining.first { it.id == focus }.text.length), save = NoteSaveState.EDITED)
        scheduleSave()
        return true
    }

    // ---------------------------------------------------------------- Arranging blocks

    /**
     * Moves visible block [blockId] (with anything folded under it) to position [toIndex] among
     * [arrangeableNoteBlocks], as one undo step. False, after telling the user, when the move
     * would hide blocks or the note's stored rich document can't hold that order.
     */
    fun moveBlock(blockId: String, toIndex: Int): Boolean {
        val s = _state.value
        if (s.loading || s.damaged || s.pasting) return false
        val blocks = when (val move = moveNoteBlock(s.blocks, blockId, toIndex)) {
            is NoteBlockMove.Moved -> move.blocks
            NoteBlockMove.Unchanged -> return false
            NoteBlockMove.WouldHide -> {
                ui = s.copy(dialog = NoteDialog.Message("That spot would fold blocks out of sight under a " +
                    "collapsed heading or toggle. Expand it first, or drop the block somewhere else. Nothing was changed."))
                return false
            }
        }
        if (!richDocumentAccepts(blocks)) {
            ui = s.copy(dialog = NoteDialog.Message("This block sits inside a list, quote or toggle of a note " +
                "made in another editor, so it can't be moved there. Nothing was changed."))
            return false
        }
        pushUndo()
        ui = _state.value.copy(blocks = blocks, save = NoteSaveState.EDITED)
        scheduleSave()
        return true
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
        s.picked().takeIf { it.isNotEmpty() }?.let { return pickedToolState(s, it) }
        if (s.activeCell != null) return cellToolState(s)
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
            add(NoteTool.YOUTUBE)
            add(NoteTool.AUDIO)
            add(NoteTool.TEXT_TO_AUDIO)
            add(NoteTool.RENAME)
            if (canDuplicate) add(NoteTool.DUPLICATE)
            add(NoteTool.PRINT)
            add(NoteTool.EXPORT)
            add(NoteTool.WORD_COUNT)
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
                if (block!!.type != NoteDocBlockType.CODE && block.text.any(Char::isLetter)) add(NoteTool.CHANGE_CASE)
                if (!s.selection.collapsed) add(NoteTool.DELETE)
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
            paragraphLabel = paragraphLabelFor(block),
            fontLabel = run?.fontFamily ?: "Inter",
            sizeLabel = (run?.fontSizeSp ?: defaultSizeFor(block?.type)).toString(),
            spacingLabel = (block?.lineSpacing ?: 1f).let { spacing ->
                SPACINGS.firstOrNull { it.first == spacing }?.second ?: "%.2f".format(java.util.Locale.US, spacing).trimEnd('0').trimEnd('.')
            },
            active = active,
            enabled = enabled,
        )
    }

    private fun paragraphLabelFor(block: NoteDocBlock?): String =
        if (block?.toggleHeading == true && block.type.headingLevel > 0)
            "Toggle heading ${block.type.headingLevel}" else when (block?.type) {
            NoteDocBlockType.HEADING_1 -> "Heading 1"
            NoteDocBlockType.HEADING_2 -> "Heading 2"
            NoteDocBlockType.HEADING_3 -> "Heading 3"
            NoteDocBlockType.HEADING_4 -> "Heading 4"
            NoteDocBlockType.HEADING_5 -> "Heading 5"
            NoteDocBlockType.CODE -> "Code"
            NoteDocBlockType.QUOTE -> "Quote"
            NoteDocBlockType.PROMPT -> "Prompt"
            NoteDocBlockType.TOGGLE -> "Toggle"
            else -> "Normal text"
        }

    /** The toolbar while a table cell holds the caret: text styles for the cell, line tools off. */
    private fun cellToolState(s: NotesUiState): NoteToolState {
        val cell = s.styleBlock() ?: return NoteToolState()
        val run = currentRun()
        val marks = if (s.selection.collapsed) effectiveCaretMarks()
            else NoteDocMark.entries.filter { NoteRuns.rangeHasMark(cell.runs, s.selection.min, s.selection.max, it) }.toSet()
        val active = buildSet {
            if (NoteDocMark.BOLD in marks) add(NoteTool.BOLD)
            if (NoteDocMark.ITALIC in marks) add(NoteTool.ITALIC)
            if (NoteDocMark.UNDERLINE in marks) add(NoteTool.UNDERLINE)
            if (NoteDocMark.STRIKE in marks) add(NoteTool.STRIKE)
            if (NoteDocMark.CODE in marks) add(NoteTool.CODE)
        }
        val enabled = buildSet {
            if (s.canUndo) add(NoteTool.UNDO)
            if (s.canRedo) add(NoteTool.REDO)
            addAll(listOf(NoteTool.TABLE, NoteTool.DIVIDER, NoteTool.TOGGLE, NoteTool.HEADING_FOLD,
                NoteTool.IMAGE, NoteTool.YOUTUBE, NoteTool.AUDIO, NoteTool.TEXT_TO_AUDIO, NoteTool.RENAME, NoteTool.WORD_COUNT))
            if (canDuplicate) add(NoteTool.DUPLICATE)
            add(NoteTool.PRINT)
            add(NoteTool.EXPORT)
            addAll(listOf(NoteTool.BOLD, NoteTool.ITALIC, NoteTool.UNDERLINE, NoteTool.STRIKE,
                NoteTool.CODE, NoteTool.FONT, NoteTool.SIZE))
            if (cell.text.isNotEmpty()) addAll(listOf(NoteTool.TEXT_COLOR, NoteTool.HIGHLIGHT))
        }
        return NoteToolState(
            paragraphLabel = "Table cell",
            fontLabel = run?.fontFamily ?: "Inter",
            sizeLabel = (run?.fontSizeSp ?: TABLE_CELL_TEXT_SP).toString(),
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
        /** Longest note name the rename strip keeps. */
        const val MAX_TITLE = 120

        /** Names of pictures kept in the note's own image store (see NoteImageStore). */
        private val MANAGED_IMAGE_NAME = Regex("[0-9a-f-]{36}")

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

        /** Line kinds a multi-select can switch between. */
        private val MultiShapeTypes = setOf(
            NoteDocBlockType.PARAGRAPH, NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2,
            NoteDocBlockType.HEADING_3, NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5,
            NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED, NoteDocBlockType.CHECKLIST, NoteDocBlockType.QUOTE,
        )

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
            NoteDocBlockType.HEADING_4 -> 16
            NoteDocBlockType.HEADING_5 -> 14
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
    val usableHtml = htmlText?.takeIf { sameText(insertedText, plainText) || htmlTextMatches(it, insertedText) ||
        GoogleDocsPaste.isGoogleDocsHtml(it) && GoogleDocsPaste.textMatches(it, insertedText) }
    val rich = usableHtml?.let { html ->
        runCatching { NotePasteParser.parse(insertedText, html, imageResolver) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
    val parsed = rich ?: NotePasteParser.parse(insertedText, null, imageResolver).takeIf { it.isNotEmpty() }
        ?: return null
    return NotePasteSafety.splitLongParagraphs(parsed)
}
