package com.virlin.app.ui.notes
import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.components.SafeDialog as Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteListStyles
import com.virlin.app.domain.notedoc.NotePasteSource
import com.virlin.app.domain.notedoc.NoteSourcePaste
import com.virlin.app.domain.notedoc.VirlinNoteCodec
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.visibleNoteBlocks
import com.virlin.app.data.noteimage.NoteImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.virlin.app.ui.hierarchy.rememberProjectPalette
import com.virlin.app.ui.hierarchy.readableOn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Navigation route for the Notes page. */
const val NOTES_ROUTE: String = "notes/{ownerKey}?title={title}"
const val NOTES_READER_ROUTE: String = "note_reader/{ownerKey}?title={title}"

/**
 * Opens the Notes page for a task.
 *
 * The task id only namespaces the document key and supplies the header title. The Notes page
 * reads and writes nothing of the task itself, so the owner can be changed later without
 * touching stored documents.
 */
fun notesRoute(ownerKey: String, title: String): String =
    "notes/" + android.net.Uri.encode(ownerKey) + "?title=" + android.net.Uri.encode(title)

fun notesReaderRoute(ownerKey: String, title: String): String =
    "note_reader/" + android.net.Uri.encode(ownerKey) + "?title=" + android.net.Uri.encode(title)

fun notesForTask(taskId: String, title: String): String =
    notesRoute(TaskPageTypeKeys.noteOwner(taskId), title)

fun notesForProject(projectId: String, title: String): String =
    notesRoute("project-$projectId", title)

fun globalNotes(): String = notesRoute("global", "Notes")

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/**
 * The Notes page route.
 *
 * Renders the approved [VirlinNotesScreen] shell unchanged. The shell's `footer` slot is left
 * empty on purpose: the shared footer and the Orb come from the app Scaffold for this route, so
 * filling the slot as well would put two footers on the page.
 *
 * [ownerKey] is the opaque key the document is stored under and [title] is display only. Nothing
 * on this page writes a task's legacy note field. Capture-owned keys use the domain adapter
 * to update Knowledge lifecycle/preview atomically alongside the rich document.
 */
@Composable
fun NotesRoute(
    navController: NavController,
    ownerKey: String,
    title: String,
    vm: NotesViewModel = viewModel(
        key = "notes-$ownerKey",
        factory = NotesViewModel.factory(ownerKey, title),
    ),
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var pendingImagePicker by remember { mutableStateOf(false) }
    // The image block a picker result replaces; null inserts a new image instead.
    var replaceImageTarget by remember { mutableStateOf<String?>(null) }
    var imageBusy by remember { mutableStateOf(false) }
    var pendingFlashcard by remember { mutableStateOf<List<NoteDocBlock>?>(null) }
    var pendingFlashcardSource by remember { mutableStateOf<String?>(null) }
    var flashcardSaving by remember { mutableStateOf(false) }
    var flashcardError by remember { mutableStateOf<String?>(null) }
    var documentSelectionMode by remember { mutableStateOf(false) }
    var arrangeMode by remember { mutableStateOf(false) }
    var headingFoldRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var selectedBlockIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(documentSelectionMode, selectedBlockIds) {
        vm.setBlockSelection(if (documentSelectionMode) selectedBlockIds else emptySet())
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val replacing = replaceImageTarget
        replaceImageTarget = null
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { NoteImageStore.import(context, uri) } }
                .onSuccess { name -> if (replacing != null) vm.replaceImage(replacing, name) else vm.insertImage(name) }
                .onFailure { vm.openDialog(NoteDialog.Message(it.message ?: "Could not add image")) }
        }
    }
    val state by vm.state.collectAsState()
    // Launch only after the add-block dialog has left composition. Launching from its click
    // callback can race the dialog window being dismissed on Android devices.
    LaunchedEffect(pendingImagePicker, state.dialog) {
        if (pendingImagePicker && state.dialog == null) {
            pendingImagePicker = false
            runCatching { imagePicker.launch(arrayOf("image/*")) }
                .onFailure { vm.openDialog(NoteDialog.Message("No image picker is available on this device.")) }
        }
    }
    val pickImage = { pendingImagePicker = true }
    var pendingDocumentPicker by remember { mutableStateOf(false) }
    // The name of the document being read; the note is untouched until reading succeeds.
    var importingDocument by remember { mutableStateOf<String?>(null) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && importingDocument == null) scope.launch {
            val name = NoteDocumentImport.displayName(context, uri) ?: "document"
            importingDocument = name
            val result = runCatching { withContext(Dispatchers.IO) { NoteDocumentImport.import(context, uri) } }
            importingDocument = null
            result.onSuccess { imported ->
                when {
                    imported.blocks.isEmpty() ->
                        vm.openDialog(NoteDialog.Message("\u201C$name\u201D has no text or pictures to import."))
                    vm.insertImportedBlocks(imported.blocks) ->
                        vm.openDialog(NoteDialog.Message(NoteDocumentImport.summary(name, imported)))
                    else -> withContext(Dispatchers.IO) {
                        NoteDocumentImport.discardImages(context, NoteDocumentImport.storedImages(imported))
                    }
                }
            }.onFailure {
                vm.openDialog(NoteDialog.Message((it as? NoteDocxReader.Refused)?.message
                    ?: "This document couldn't be imported. Nothing was changed."))
            }
        }
    }
    LaunchedEffect(pendingDocumentPicker, state.dialog) {
        if (pendingDocumentPicker && state.dialog == null) {
            pendingDocumentPicker = false
            runCatching { documentPicker.launch(NoteDocumentImport.PICKER_TYPES) }
                .onFailure { vm.openDialog(NoteDialog.Message("No file picker is available on this device.")) }
        }
    }
    val pasteClipboardImage: () -> Unit = {
        vm.closeDialog()
        val uris = runCatching { NoteClipboardImages.uris(context) }.getOrDefault(emptyList())
        if (uris.isEmpty()) vm.openDialog(NoteDialog.Message("The clipboard no longer holds a picture."))
        else scope.launch {
            uris.forEach { uri ->
                runCatching { withContext(Dispatchers.IO) { NoteImageStore.import(context, uri) } }
                    .onSuccess(vm::insertImage)
                    .onFailure { vm.openDialog(NoteDialog.Message(it.message ?: "Could not paste the picture")) }
            }
        }
    }
    val chooseImage: () -> Unit = {
        if (runCatching { NoteClipboardImages.available(context) }.getOrDefault(false))
            vm.openDialog(NoteDialog.ImageSource)
        else pickImage()
    }
    val projectId = rememberNoteProjectId(ownerKey)
    val palette = rememberProjectPalette(projectId)
    // An extra task note's Page card is dropped while the note is unsaved; keep it once saved.
    if (TaskPageTypeKeys.isExtraNoteOwner(ownerKey)) {
        val saved = state.save == NoteSaveState.SAVED
        LaunchedEffect(saved) {
            if (saved) runCatching {
                com.virlin.app.domain.action.TaskPageActions(VirlinGraph.repository, VirlinGraph.clock, VirlinGraph.ids)
                    .ensure(TaskPageTypeKeys.noteTaskId(ownerKey)!!, TaskPageTypeKeys.NOTE, ownerKey)
            }
        }
    }
    BackHandler { vm.saveAndClose { navController.popBackStack() } }

    // The debounce must never eat the last keystrokes when the page goes away.
    DisposableEffect(Unit) { onDispose { vm.flush() } }

    val textToolbar = rememberCoalescingTextToolbar()
    CompositionLocalProvider(LocalNotesPalette provides palette, LocalTextToolbar provides textToolbar) {
    MaterialTheme(colorScheme = lightColorScheme(primary = NotesAccent, onPrimary = readableOn(NotesAccent),
        surface = NotesPaper, onSurface = NotesInk, secondary = NotesAccent)) {
    // Page formatting is kept per note beside it, never in the document. While it is on the note
    // is edited as pages, and printing and exporting follow those pages.
    val pageStore = remember(context) { NotePageSetupStore(context) }
    var pageSetup by remember(ownerKey) { mutableStateOf(pageStore.load(ownerKey)) }
    var formattingOn by remember(ownerKey) { mutableStateOf(pageStore.isOn(ownerKey) && pageSetup != null) }
    val setFormatting: (Boolean) -> Unit = { on ->
        if (on && pageSetup == null) pageSetup = NotePrintSettings().also { pageStore.save(ownerKey, it) }
        formattingOn = on
        pageStore.setOn(ownerKey, on)
        if (on) { documentSelectionMode = false; arrangeMode = false }
    }
    val handleTool: (NoteTool) -> Unit = { tool ->
        // Arranging is a view of the blocks; any tool but undo/redo returns to writing first.
        if (arrangeMode && tool != NoteTool.ARRANGE_BLOCKS && tool != NoteTool.UNDO && tool != NoteTool.REDO)
            arrangeMode = false
        when (tool) {
            NoteTool.FORMATTING -> setFormatting(!formattingOn)
            NoteTool.ARRANGE_BLOCKS -> {
                arrangeMode = !arrangeMode
                if (arrangeMode) { documentSelectionMode = false; vm.closeDialog() }
                focusManager.clearFocus(); keyboard?.hide()
            }
            NoteTool.SELECT_DOCUMENT -> {
                documentSelectionMode = !documentSelectionMode
                selectedBlockIds = emptySet()
                focusManager.clearFocus(); keyboard?.hide()
            }
            NoteTool.IMAGE -> if (state.selectedImage != null) vm.toggleImageTools() else chooseImage()
            NoteTool.DUPLICATE -> if (documentSelectionMode && state.blocks.any { it.id in selectedBlockIds }) {
                vm.duplicateBlocks(selectedBlockIds)?.let { copies ->
                    selectedBlockIds = copies.toSet()
                    android.widget.Toast.makeText(context,
                        if (copies.size == 1) "Block duplicated" else "${copies.size} blocks duplicated",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            } else vm.duplicateNote { source ->
                com.virlin.app.domain.action.TaskPageActions(VirlinGraph.repository, VirlinGraph.clock, VirlinGraph.ids)
                    .duplicateNote(source)
            }
            NoteTool.FLASHCARD -> {
                val selected = if (documentSelectionMode) {
                    flashcardBlocks(state.blocks.filter { it.id in selectedBlockIds }).takeIf { it.isNotEmpty() }
                } else vm.selectedFlashcardContent()?.let(::listOf)
                if (selected == null) vm.openDialog(NoteDialog.Message(
                    if (documentSelectionMode) "Select blocks first." else "Select note text first."))
                else {
                    pendingFlashcard = selected; flashcardError = null
                    pendingFlashcardSource = if (documentSelectionMode)
                        state.blocks.firstOrNull { it.id in selectedBlockIds }?.id
                    else state.focusedBlockId
                }
            }
            NoteTool.HEADING_FOLD -> headingFoldRequest++
            NoteTool.TOGGLE -> if (documentSelectionMode && state.blocks.any { it.id in selectedBlockIds }) {
                val range = contiguousBlockRange(state.blocks, selectedBlockIds)
                if (range == null) vm.openDialog(NoteDialog.Message(
                    "Select blocks that sit next to each other to put them in a toggle."))
                else if (vm.wrapInToggle(range)) {
                    selectedBlockIds = emptySet()
                    documentSelectionMode = false
                }
            } else vm.onTool(tool)
            // Ids survive a case change, so repeated taps cycle the same blocks.
            NoteTool.CHANGE_CASE -> if (documentSelectionMode) {
                vm.cycleBlocksCase(selectedBlockIds)
            } else vm.onTool(tool)
            NoteTool.DELETE -> if (documentSelectionMode) {
                if (vm.deleteBlocks(selectedBlockIds)) {
                    selectedBlockIds = emptySet()
                    documentSelectionMode = false
                }
            } else vm.onTool(tool)
            else -> vm.onTool(tool)
        }
    }
    val paragraphAnchor = remember { NoteChipAnchor() }
    val spacingAnchor = remember { NoteChipAnchor() }
    val sizeTarget = remember { mutableStateOf<NotesViewModel.StyleTarget?>(null) }
    val sizeControl = remember(vm) {
        NoteSizeControl(
            onStep = vm::stepFontSize,
            onEditStart = { sizeTarget.value = vm.styleTarget() },
            onSet = { sp -> vm.applyFontSize(sp, sizeTarget.value); sizeTarget.value = null },
        )
    }
    val tableEntry = remember(vm) { NoteTableEntry(onInsert = vm::insertTable, onCancel = vm::closeDialog) }
    val colorTarget = remember { mutableStateOf<NotesViewModel.StyleTarget?>(null) }
    val colorStrip = (state.dialog as? NoteDialog)?.let { dialog ->
        val highlight = when (dialog) {
            NoteDialog.TextColor -> false
            NoteDialog.Highlight -> true
            else -> return@let null
        }
        val (color, mark) = vm.currentColors()
        NoteColorStripState(
            highlight = highlight,
            current = if (highlight) mark else color,
            onPick = { vm.pickColor(it, highlight) },
            onHexStart = { colorTarget.value = vm.styleTarget() },
            onHex = { vm.pickColor(it, highlight, colorTarget.value); colorTarget.value = null },
            onClose = vm::closeDialog,
        )
    }
    val listStrip = (state.dialog as? NoteDialog.ListStyle)?.let { dialog ->
        val focused = state.focusedBlock
        NoteListStyleStripState(
            type = dialog.type,
            current = focused?.takeIf { it.type == dialog.type }
                ?.let { NoteListStyles.effectiveKey(it.type, it.listStyle) },
            onPick = { key -> vm.setListStyle(dialog.type, key) },
            onNone = vm::removeListFromFocused,
            onClose = vm::closeDialog,
        )
    }
    val paragraphStrip = if (state.dialog == NoteDialog.Paragraph) NoteParagraphStyleStripState(
        current = state.focusedBlock?.type,
        currentToggleHeading = state.focusedBlock?.toggleHeading == true,
        onPickStyle = { type, toggle -> vm.setParagraphStyle(type, toggle, keepOpen = true) },
        onPickBlock = { type -> vm.setBlockType(type, keepOpen = true) },
        onClose = vm::closeDialog,
    ) else null
    val spacingStrip = if (state.dialog == NoteDialog.LineSpacing) NoteLineSpacingStripState(
        current = state.focusedBlock?.lineSpacing,
        onPick = { value -> vm.setLineSpacing(value, keepOpen = true) },
        onClose = vm::closeDialog,
    ) else null
    val tableToolsStrip = if (state.dialog == NoteDialog.TableTools) vm.tableToolState()?.let { tools ->
        NoteTableToolsStripState(
            tools = tools,
            onInsertRow = vm::tableInsertRow,
            onInsertColumn = vm::tableInsertColumn,
            onDeleteRow = vm::tableDeleteRow,
            onDeleteColumn = vm::tableDeleteColumn,
            onDeleteTable = vm::tableDelete,
            onHeaderRow = vm::tableToggleHeaderRow,
            onHeaderColumn = vm::tableToggleHeaderColumn,
            onGridLines = vm::tableToggleGridLines,
            onWidthStep = vm::tableStepColumnWidth,
            onFitColumns = vm::tableFitColumns,
            onShade = vm::tableShadeCell,
            onClose = vm::closeDialog,
        )
    } else null
    val imageToolsStrip = if (state.dialog == NoteDialog.ImageTools) state.selectedImage?.let { image ->
        NoteImageToolsStripState(
            busy = imageBusy,
            onEdit = vm::openImageEditor,
            onRotate = {
                val name = image.imagePath
                if (!imageBusy && name != null) scope.launch {
                    imageBusy = true
                    runCatching {
                        withContext(Dispatchers.Default) {
                            val file = NoteImageStore.resolve(context, name)?.takeIf { it.isFile }
                                ?: throw IllegalStateException("This image is a link to a file outside the note, so it can't be rotated here.")
                            val full = NoteImageRender.decode(file, NoteImageRender.MAX_SIDE)
                                ?: throw IllegalStateException("The image could not be read")
                            val out = NoteImageRender.render(full, NoteImageEdit(quarterTurns = 1))
                            try { NoteImageStore.saveEdited(context, out, NoteImageStore.keepsTransparency(file)) }
                            finally { out.recycle(); full.recycle() }
                        }
                    }.onSuccess { vm.replaceImage(image.id, it) }
                        .onFailure { vm.openDialog(NoteDialog.Message(it.message ?: "Could not rotate the image")) }
                    imageBusy = false
                }
            },
            onReplace = {
                replaceImageTarget = image.id
                vm.closeDialog()
                pickImage()
            },
            onDelete = vm::deleteSelectedImage,
            onClose = vm::closeDialog,
        )
    } else null
    val videoEntry = (state.dialog as? NoteDialog.Video)?.let { dialog ->
        val editId = dialog.editBlockId
        val editing = editId?.let { id -> state.blocks.firstOrNull { it.id == id && it.type == NoteDocBlockType.VIDEO }?.video }
        if (editId != null && editing == null) null
        else NoteVideoEntryState(
            key = editId,
            editing = editing,
            onEmbed = { video -> if (editId != null) vm.updateVideo(editId, video) else vm.insertVideo(video) },
            onClose = vm::closeDialog,
        )
    }
    val audioRecorder = if (state.dialog == NoteDialog.Audio) NoteAudioRecorderState(
        onInsert = vm::insertAudio,
        onClose = vm::closeDialog,
    ) else null
    val renameStrip = if (state.dialog == NoteDialog.Rename) NoteRenameStripState(
        current = state.title.ifBlank { title },
        onRename = vm::renameNote,
        onClose = vm::closeDialog,
    ) else null
    val printOpen = state.dialog == NoteDialog.Print
    val fontResolver = LocalFontFamilyResolver.current
    // Output keeps a block that fits on one page whole, as the editable page view shows it,
    // unless auto-fit lets content flow on across pages.
    val outputSettings = ((if (formattingOn) pageSetup else null) ?: NotePrintSettings()).let {
        it.copy(keepBlocksWhole = !it.autoFit)
    }
    val pageViewActive = formattingOn && !arrangeMode && !documentSelectionMode && !state.damaged
    // Auto-fit pages are shown as they print, read only.
    val autoFitView = pageViewActive && outputSettings.autoFit
    var flowPages by remember { mutableStateOf<List<NotePrintRenderer.Page>?>(null) }
    LaunchedEffect(autoFitView) { if (autoFitView) { focusManager.clearFocus(); keyboard?.hide() } }
    // An output action waiting on the "format the pages first" question.
    var formatFirst by remember { mutableStateOf<(() -> Unit)?>(null) }
    val whenFormatted: (() -> Unit) -> Unit = { action -> if (formattingOn) action() else formatFirst = action }
    var printFaces by remember { mutableStateOf<Map<String, android.graphics.Typeface>?>(null) }
    var printPages by remember { mutableStateOf<Int?>(null) }
    var printBusy by remember { mutableStateOf(false) }
    var pagination by remember { mutableStateOf<NotePagination?>(null) }
    val noteTitle = state.title.ifBlank { title }
    val printSource = {
        NotePrintSource(noteTitle, state.blocks, NotePrintColors.of(palette), printFaces.orEmpty(),
            imageFile = { NoteImageStore.resolve(context, it) },
            thumbnail = { NoteVideoThumbnails.cached(it) })
    }
    val exportOpen = state.dialog == NoteDialog.Export
    val pagesNeeded = printOpen || exportOpen || formattingOn
    LaunchedEffect(printOpen || exportOpen) {
        if (printOpen || exportOpen) { focusManager.clearFocus(); keyboard?.hide() }
    }
    val fontNames = remember(state.blocks) {
        state.blocks.flatMapTo(HashSet()) { b ->
            b.runs.mapNotNull { it.fontFamily } + b.table?.cellRuns.orEmpty().flatten().mapNotNull { it.fontFamily }
        }
    }
    LaunchedEffect(pagesNeeded, fontNames) {
        if (pagesNeeded) printFaces = NotePrinting.loadTypefaces(fontResolver, state.blocks)
    }
    val videoIds = remember(state.blocks) { state.blocks.mapNotNull { it.video?.videoId }.toSet() }
    LaunchedEffect(pagesNeeded, videoIds) {
        if (pagesNeeded) videoIds.forEach { runCatching { NoteVideoThumbnails.load(it) } }
    }
    LaunchedEffect(printOpen, outputSettings, state.blocks, printFaces) {
        if (!printOpen || printFaces == null) return@LaunchedEffect
        printPages = null
        delay(200)
        val source = printSource()
        printPages = withContext(Dispatchers.Default) {
            runCatching { NotePrintRenderer.layout(source, outputSettings).size }.getOrNull()
        }
    }
    LaunchedEffect(formattingOn, outputSettings, state.blocks, printFaces, noteTitle) {
        if (!formattingOn || printFaces == null) {
            if (!formattingOn) { pagination = null; flowPages = null }
            return@LaunchedEffect
        }
        val settings = outputSettings
        if (settings.autoFit) {
            delay(if (flowPages == null) 0L else 250L)
            val source = printSource().let {
                NotePrintSource(it.title, it.blocks, it.colors, it.typefaces, it.imageFile, FLOW_VIEW_DPI, it.thumbnail)
            }
            withContext(Dispatchers.Default) { runCatching { NotePrintRenderer.layout(source, settings) }.getOrNull() }
                ?.let { flowPages = it; pagination = null }
        } else {
            flowPages = null
            delay(if (pagination == null) 0L else 250L)
            val source = printSource()
            withContext(Dispatchers.Default) { runCatching { NotePrintRenderer.paginate(source, settings) }.getOrNull() }
                ?.let { pagination = it }
        }
    }
    val pdfSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            printBusy = true
            runCatching { NotePrinting.savePdf(context, uri, printSource(), outputSettings) }
                .onSuccess { pages ->
                    android.widget.Toast.makeText(context,
                        "PDF saved · ${if (pages == 1) "1 page" else "$pages pages"}", android.widget.Toast.LENGTH_SHORT).show()
                }
                .onFailure { vm.openDialog(NoteDialog.Message("The PDF could not be saved: ${it.message ?: "unknown error"}")) }
            printBusy = false
        }
    }
    val printStrip = if (printOpen) NotePrintStripState(
        settings = if (formattingOn) pageSetup else null,
        onFormat = { setFormatting(true) },
        pageCount = printPages,
        busy = printBusy || printFaces == null,
        onPrint = {
            whenFormatted {
                runCatching { NotePrinting.print(context, noteTitle, printSource(), outputSettings) }
                    .onFailure { vm.openDialog(NoteDialog.Message("Printing is not available on this device.")) }
            }
        },
        onSavePdf = {
            whenFormatted {
                runCatching { pdfSaver.launch(NotePrinting.fileName(noteTitle)) }
                    .onFailure { vm.openDialog(NoteDialog.Message("No app is available to save the PDF.")) }
            }
        },
        onClose = vm::closeDialog,
    ) else null
    var exportSettings by remember { mutableStateOf(NoteExportSettings()) }
    var exportBusy by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val exportSaver = rememberLauncherForActivityResult(CreateExportDocument()) { uri ->
        if (uri != null) scope.launch {
            exportBusy = true
            exportResult = null
            val settings = exportSettings
            runCatching { NoteExporter.export(context, uri, settings, outputSettings, printSource()) }
                .onSuccess { bytes ->
                    val label = "Saved · ${NoteExporter.sizeLabel(bytes)}"
                    exportResult = label
                    android.widget.Toast.makeText(context, "${settings.format?.label} $label", android.widget.Toast.LENGTH_SHORT).show()
                }
                .onFailure { vm.openDialog(NoteDialog.Message("The export could not be saved: ${it.message ?: "unknown error"}")) }
            exportBusy = false
        }
    }
    val exportStrip = if (exportOpen) NoteExportStripState(
        settings = exportSettings,
        onChange = { exportSettings = it; exportResult = null },
        busy = exportBusy || printFaces == null,
        result = exportResult,
        onExport = {
            val launch = {
                runCatching { exportSaver.launch(NoteExporter.fileName(noteTitle, exportSettings) to exportSettings.mimeType) }
                    .onFailure { vm.openDialog(NoteDialog.Message("No app is available to save the file.")) }
                Unit
            }
            if (exportSettings.usesPages) whenFormatted(launch) else launch()
        },
        onClose = vm::closeDialog,
        pageSetup = if (formattingOn) pageSetup else null,
        onFormat = { setFormatting(true) },
    ) else null
    val formattingStrip = if (formattingOn && !arrangeMode) pageSetup?.let { setup ->
        NoteFormattingStripState(
            settings = setup,
            onChange = { next -> pageSetup = next; pageStore.save(ownerKey, next) },
            pageCount = if (setup.autoFit) flowPages?.size else pagination?.pageCount,
            onClose = { setFormatting(false) },
        )
    } else null
    val pasteFromOpen = state.dialog == NoteDialog.PasteFrom
    var pasteFromBusy by remember { mutableStateOf<NotePasteSource?>(null) }
    var pasteFromLast by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val pasteFromSource: (NotePasteSource) -> Unit = { source ->
        if (pasteFromBusy == null) scope.launch {
            pasteFromBusy = source
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    NoteSourceClipboard.read(context)?.let { copy ->
                        NoteSourcePaste.parse(source, copy.plain, copy.html, NoteSourceClipboard.imageResolver(context))
                    }
                }
            }
            pasteFromBusy = null
            result.onSuccess { blocks ->
                when {
                    blocks == null ->
                        if (runCatching { NoteClipboardImages.available(context) }.getOrDefault(false)) pasteClipboardImage()
                        else vm.openDialog(NoteDialog.Message(
                            "The clipboard is empty. Copy something first, then choose where it came from."))
                    blocks.isEmpty() -> vm.openDialog(NoteDialog.Message(
                        "Nothing on the clipboard could be pasted as ${source.label}. Nothing was changed."))
                    vm.insertImportedBlocks(blocks, fillBlankLine = true) -> {
                        pasteFromLast = source.name
                        android.widget.Toast.makeText(context, "Pasted from ${source.label}",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    else -> withContext(Dispatchers.IO) {
                        NoteDocumentImport.discardImages(context, blocks.mapNotNull { it.imagePath })
                    }
                }
            }.onFailure {
                vm.openDialog(NoteDialog.Message("The clipboard couldn't be pasted. Nothing was changed."))
            }
        }
    }
    val pasteSourceStrip = if (pasteFromOpen) NotePasteSourceStripState(
        busy = pasteFromBusy,
        last = pasteFromLast?.let { name -> NotePasteSource.entries.firstOrNull { it.name == name } },
        onSource = pasteFromSource,
        onClose = vm::closeDialog,
    ) else null
    NoteAudioPlaybackHost()
    val toolState = vm.toolState().let { tools -> tools.copy(
        duplicateLabel = if (documentSelectionMode && state.blocks.any { it.id in selectedBlockIds })
            "Duplicate selected blocks" else tools.duplicateLabel,
        active = tools.active + when (val dialog = state.dialog) {
            NoteDialog.Paragraph -> setOf(NoteTool.PARAGRAPH)
            NoteDialog.Font -> setOf(NoteTool.FONT)
            NoteDialog.Size -> setOf(NoteTool.SIZE)
            is NoteDialog.Link -> setOf(NoteTool.LINK)
            NoteDialog.WordCount -> setOf(NoteTool.WORD_COUNT)
            NoteDialog.ImageSource, is NoteDialog.ImageEditor -> setOf(NoteTool.IMAGE)
            is NoteDialog.ListStyle ->
                setOf(if (dialog.type == NoteDocBlockType.NUMBERED) NoteTool.NUMBERING else NoteTool.BULLETS)
            NoteDialog.TextColor -> setOf(NoteTool.TEXT_COLOR)
            NoteDialog.Highlight -> setOf(NoteTool.HIGHLIGHT)
            NoteDialog.Table, NoteDialog.TableTools -> setOf(NoteTool.TABLE)
            NoteDialog.ImageTools -> setOf(NoteTool.IMAGE)
            is NoteDialog.Video -> setOf(NoteTool.YOUTUBE)
            NoteDialog.Audio -> setOf(NoteTool.AUDIO)
            NoteDialog.TextToAudio -> setOf(NoteTool.TEXT_TO_AUDIO)
            NoteDialog.Rename -> setOf(NoteTool.RENAME)
            NoteDialog.Print -> setOf(NoteTool.PRINT)
            NoteDialog.Export -> setOf(NoteTool.EXPORT)
            NoteDialog.ImportDocument -> setOf(NoteTool.IMPORT)
            NoteDialog.PasteFrom -> setOf(NoteTool.PASTE_FROM)
            NoteDialog.LineSpacing -> setOf(NoteTool.LINE_SPACING)
            else -> emptySet()
        } + (if (arrangeMode) setOf(NoteTool.ARRANGE_BLOCKS) else emptySet()) +
            (if (documentSelectionMode) setOf(NoteTool.SELECT_DOCUMENT) else emptySet()) +
            if (formattingOn) setOf(NoteTool.FORMATTING) else emptySet(),
        // While selecting across blocks, Delete acts on that selection, not the hidden field's.
        enabled = if (state.damaged) emptySet() else
            (if (documentSelectionMode) tools.enabled - NoteTool.DELETE - NoteTool.CHANGE_CASE else tools.enabled) +
            NoteTool.SELECT_DOCUMENT + NoteTool.ARRANGE_BLOCKS + NoteTool.FORMATTING +
            (if (importingDocument == null) setOf(NoteTool.IMPORT) else emptySet()) +
            (if (state.pasting) emptySet() else setOf(NoteTool.PASTE_FROM)) +
            if (documentSelectionMode && state.blocks.any { it.id in selectedBlockIds })
                setOf(NoteTool.FLASHCARD, NoteTool.DELETE, NoteTool.CHANGE_CASE, NoteTool.DUPLICATE) else emptySet(),
    ) }
    VirlinNotesScreen(
        taskTitle = state.title.ifBlank { title },
        saveLabel = vm.saveLabel(),
        tools = toolState,
        onClose = { vm.saveAndClose { navController.popBackStack() } },
        onTool = { tool -> if (!state.damaged) handleTool(tool) },
        onAddBlock = { if (!state.damaged) { documentSelectionMode = false; arrangeMode = false; vm.openAddBlock() } },
        onPreview = {
            vm.saveAndClose {
                focusManager.clearFocus(); keyboard?.hide()
                navController.navigate(notesReaderRoute(ownerKey, title)) { launchSingleTop = true }
            }
        },
        headingLevels = state.blocks.mapNotNull { block ->
            block.type.headingLevel.takeIf { it > 0 }
        }.distinct().sortedDescending(),
        onHeadingFoldLevel = vm::foldHeadingsAtLevel,
        headingFoldRequest = headingFoldRequest,
        documentSelectionMode = documentSelectionMode,
        editor = { headingFoldMode, _ ->
            if (!state.loading && state.damaged) NoteDamagedPanel(
                onRecover = vm::recoverDamaged,
                rawPayload = vm::damagedPayload,
                modifier = Modifier.fillMaxSize().padding(top = NoteDocumentTopSpace),
            )
            else if (!state.loading && arrangeMode) NoteBlockArranger(
                blocks = state.blocks,
                onMove = vm::moveBlock,
                onDone = { arrangeMode = false },
                modifier = Modifier.fillMaxSize().padding(top = NoteDocumentTopSpace),
            ) { block, before -> PreviewBlock(block, before) }
            else if (!state.loading && autoFitView) NoteFlowPages(
                pages = flowPages,
                settings = outputSettings,
                colors = NotePrintColors.of(palette),
                onTap = {
                    android.widget.Toast.makeText(context, "Auto-fit pages are read only. Turn Auto-fit off to edit.",
                        android.widget.Toast.LENGTH_SHORT).show()
                },
            )
            else if (!state.loading) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                // The field keeps focus under a picker so its selection survives; its handle
                // popup would otherwise draw on top of the picker.
                val selectionColors = androidx.compose.foundation.text.selection.LocalTextSelectionColors.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
                        if (state.dialog == null || state.dialog == NoteDialog.TextColor ||
                            state.dialog == NoteDialog.Highlight || state.dialog is NoteDialog.ListStyle ||
                            state.dialog == NoteDialog.Paragraph || state.dialog == NoteDialog.LineSpacing ||
                            state.dialog == NoteDialog.TableTools || state.dialog is NoteDialog.Video ||
                            state.dialog == NoteDialog.Audio) selectionColors
                        else androidx.compose.foundation.text.selection.TextSelectionColors(
                            Color.Transparent, selectionColors.backgroundColor),
                ) {
                    NoteBlockEditor(
                        state = state, vm = vm,
                        headingFoldMode = headingFoldMode,
                        documentSelectionMode = documentSelectionMode,
                        selectedBlockIds = selectedBlockIds,
                        onSelectedBlockIdsChange = { selectedBlockIds = it },
                        modifier = Modifier.fillMaxSize(),
                        positionKey = ownerKey,
                        pageView = if (pageViewActive) pageSetup?.let {
                            NotePageView(it, pagination, noteTitle)
                        } else null,
                    )
                }
                NotePasteIndicator(state.pasting,
                    Modifier.align(androidx.compose.ui.Alignment.TopCenter).padding(top = NoteDocumentTopSpace))
            }
        },
        footer = {},
        onParagraphChipPlaced = { paragraphAnchor.bounds = it },
        sizeControl = sizeControl,
        tableEntry = if (state.dialog is NoteDialog.Table) tableEntry else null,
        colorStrip = colorStrip,
        onSpacingChipPlaced = { spacingAnchor.bounds = it },
        listStrip = listStrip,
        paragraphStrip = paragraphStrip,
        spacingStrip = spacingStrip,
        tableToolsStrip = tableToolsStrip,
        imageToolsStrip = imageToolsStrip,
        videoEntry = videoEntry,
        audioRecorder = audioRecorder,
        renameStrip = renameStrip,
        printStrip = printStrip,
        exportStrip = exportStrip,
        formattingStrip = formattingStrip,
        pasteSourceStrip = pasteSourceStrip,
        pageView = pageViewActive && !state.loading,
    )
    formatFirst?.let { pending ->
        AlertDialog(
            onDismissRequest = { formatFirst = null },
            title = { Text("Format the pages first?") },
            text = {
                Text("Turn on formatting to choose the paper, margins, page numbers and title, and see the " +
                    "pages as they will come out. Exporting now uses A4 with normal margins.")
            },
            confirmButton = {
                TextButton(onClick = { formatFirst = null; setFormatting(true) },
                    modifier = Modifier.testTag("notes_format_first_turn_on")) { Text("Turn on formatting") }
            },
            dismissButton = {
                TextButton(onClick = { formatFirst = null; pending() },
                    modifier = Modifier.testTag("notes_format_first_anyway")) { Text("Continue anyway") }
            },
        )
    }

    state.dialog?.let { dialog ->
        NoteDialogHost(dialog, state, vm, chooseImage, projectId, pickFromDevice = pickImage,
            pasteImage = pasteClipboardImage, pickDocument = { pendingDocumentPicker = true },
            // The selection the flashcard reads is still in the editor, so offer it here too.
            tools = toolState.copy(enabled = if (state.damaged) emptySet() else toolState.enabled + NoteTool.FLASHCARD),
            onTool = { tool -> if (!state.damaged) handleTool(tool) },
            paragraphAnchor = paragraphAnchor, sizeControl = sizeControl,
            spacingAnchor = spacingAnchor) { navController.popBackStack() }
    }
    importingDocument?.let { name ->
        PickerDialog("Importing document", onDismiss = {}) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("notes_import_progress")) {
                androidx.compose.material3.CircularProgressIndicator(
                    Modifier.size(20.dp), color = Green, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Reading \u201C$name\u201D\u2026", color = Ink, fontSize = 14.sp,
                    maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
    pendingFlashcard?.let { content ->
        FlashcardCreateDialog(content, flashcardSaving, flashcardError,
            onDismiss = { pendingFlashcard = null; flashcardError = null },
            onSave = { front, back, difficulty, days, frontCardStyle ->
                if (!flashcardSaving) scope.launch {
                    flashcardSaving = true
                    runCatching {
                        val question = flashcardSummaryBlock(front)
                        val prompt = question.text.trim().take(300)
                        com.virlin.app.data.flashcard.FlashcardStore.add(context, ownerKey,
                            state.title.ifBlank { title }, flashcardSummaryBlock(back), prompt,
                            difficulty, days,
                            frontContent = question.takeIf { it.text.trim() == prompt },
                            frontBlocks = front, backBlocks = back,
                            sourceBlockId = pendingFlashcardSource?.takeIf { it.length <= 256 },
                            frontCardStyle = frontCardStyle)
                    }
                        .onSuccess {
                            pendingFlashcard = null; flashcardError = null
                            android.widget.Toast.makeText(context, "Flashcard saved", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { flashcardError = "Could not save flashcard: ${it.message}" }
                    flashcardSaving = false
                }
            })
    }
    } }
}

@Composable
private fun NoteDialogHost(dialog: NoteDialog, state: NotesUiState, vm: NotesViewModel,
                           pickImage: () -> Unit, projectId: String? = null,
                           pickFromDevice: () -> Unit = pickImage, pasteImage: () -> Unit = {},
                           pickDocument: () -> Unit = {},
                           tools: NoteToolState = NoteToolState(), onTool: (NoteTool) -> Unit = {},
                           paragraphAnchor: NoteChipAnchor? = null,
                           sizeControl: NoteSizeControl? = null,
                           spacingAnchor: NoteChipAnchor? = null,
                           onClose: () -> Unit = {}) {
    when (dialog) {
        // Paragraph styles are an inline strip under the formatting rail (NoteParagraphStyleStrip).
        is NoteDialog.Paragraph -> Unit

        is NoteDialog.Font -> NoteFontPickerSheet(
            current = vm.toolState().fontLabel,
            onPick = vm::setFont,
            onDismiss = vm::closeDialog,
        )

        is NoteDialog.Size -> PickerDialog("Text size", vm::closeDialog) {
            NotesViewModel.SIZES.forEach { sp ->
                PickerRow("$sp", vm.toolState().sizeLabel == "$sp") {
                    vm.setFontSize(sp)
                }
            }
        }

        // Text colour and highlight are inline strips under the formatting rail (NoteColorStrip).
        is NoteDialog.TextColor, is NoteDialog.Highlight -> Unit
        // Bullet and numbering styles are an inline strip too (NoteListStyleStrip).
        is NoteDialog.ListStyle -> Unit

        // Line spacing is an inline strip under the formatting rail (NoteLineSpacingStrip).
        is NoteDialog.LineSpacing -> Unit

        is NoteDialog.AddBlock -> NoteAddSheet(
            tools = tools,
            onDismiss = vm::closeDialog,
            onInsert = { type ->
                if (type == NoteDocBlockType.TABLE) vm.openDialog(NoteDialog.Table)
                else { vm.insertBlock(type); vm.closeDialog() }
            },
            onInsertEquation = { vm.insertEquation(); vm.closeDialog() },
            onPickImage = { vm.closeDialog(); pickImage() },
            onTool = { tool -> vm.closeDialog(); onTool(tool) },
            sizeControl = sizeControl,
        )

        // Rows × columns and the table tools are strips under the formatting rail
        // (NoteTableEntryBar, NoteTableToolsStrip).
        is NoteDialog.Table, NoteDialog.TableTools -> Unit
        // Image tools are a strip too (NoteImageToolsStrip); the editor is full screen.
        is NoteDialog.ImageTools -> Unit
        // The YouTube embed panel sits under the formatting rail (NoteVideoEntryPanel).
        is NoteDialog.Video -> Unit
        // The voice recorder is a strip under the formatting rail too (NoteAudioRecorderPanel).
        NoteDialog.Audio -> Unit
        NoteDialog.Rename -> Unit
        // Print options are a strip under the formatting rail (NotePrintStrip).
        NoteDialog.Print -> Unit
        NoteDialog.Export -> Unit
        NoteDialog.PasteFrom -> Unit
        NoteDialog.TextToAudio -> NoteTextToAudioDialog(remember(vm) {
            NoteTextToAudioState(onInsert = vm::insertAudio, onClose = vm::closeDialog)
        })
        is NoteDialog.ImageEditor -> {
            val name = state.blocks.firstOrNull { it.id == dialog.blockId }?.imagePath
            if (name == null) LaunchedEffect(dialog) { vm.closeDialog() }
            else NoteImageEditor(
                imageName = name,
                startMode = dialog.mode,
                onCancel = { vm.openDialog(NoteDialog.ImageTools) },
                onSaved = { vm.replaceImage(dialog.blockId, it) },
            )
        }

        is NoteDialog.Link -> LinkDialog(dialog, vm)

        is NoteDialog.Message -> NoteMessagePopup(dialog.text, vm::closeDialog)
        is NoteDialog.PasteDetails -> PickerDialog("Paste details", vm::closeDialog) {
            Text(vm.lastPasteReport ?: "Nothing has been pasted into this note since it was opened.",
                color = Ink, fontSize = 14.sp, modifier = Modifier.testTag("notes_paste_details"))
            Spacer(Modifier.height(14.dp))
            PickerRow("OK", false) { vm.closeDialog() }
        }
        is NoteDialog.ImportDocument -> PickerDialog("Import a document", vm::closeDialog) {
            Text("Choose a Word (.docx) or Google Docs file on this device. Its headings, lists, text " +
                "styles, links, tables and pictures become sections of this note, after the line you're " +
                "editing or at the end.",
                color = Ink, fontSize = 14.sp, modifier = Modifier.testTag("notes_import_explainer"))
            Spacer(Modifier.height(14.dp))
            PickerRow("Choose file", false) { vm.closeDialog(); pickDocument() }
            Spacer(Modifier.height(8.dp))
            PickerRow("Cancel", false) { vm.closeDialog() }
        }
        is NoteDialog.WordCount -> NoteWordCountPopup(remember(state.blocks) { vm.wordCount() }, vm::closeDialog)
        is NoteDialog.ImageSource -> PickerDialog("Add image", vm::closeDialog) {
            PickerRow("Paste copied picture", false) { pasteImage() }
            Spacer(Modifier.height(8.dp))
            PickerRow("Choose from device", false) { vm.closeDialog(); pickFromDevice() }
        }
        is NoteDialog.SaveRefused -> PickerDialog(
            if (dialog.stale) "A newer version exists" else "Note not saved", vm::closeDialog,
        ) {
            Text(dialog.reason, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            if (projectId != null) {
                PickerRow(if (dialog.stale) "Keep both: save mine as a new note" else "Save my edits as a new note",
                    false) { vm.saveEditsAsNewNote(projectId, onClose) }
                Spacer(Modifier.height(8.dp))
            }
            PickerRow("Keep editing", false) { vm.closeDialog() }
            Spacer(Modifier.height(8.dp))
            PickerRow(when {
                dialog.closeAfter -> "Discard my edits and close"
                dialog.stale -> "Discard mine, open the newer version"
                else -> "Discard my edits"
            }, false) { vm.discardEdits(onClose) }
        }
    }
}

@Composable
internal fun PickerDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(
                Modifier.width(300.dp).padding(18.dp)
                    .heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                    .testTag("notes_dialog"),
            ) {
                Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
internal fun PickerRow(
    label: String,
    selected: Boolean,
    swatch: Long? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape)
            .background(if (selected) Mint else Color.White)
            .border(1.dp, if (selected) Green.copy(alpha = .35f) else Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag("notes_pick_$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .background(Color(swatch.toULong().toLong()))
                    .border(1.dp, Border, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            color = if (selected) Green else Ink,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LinkDialog(dialog: NoteDialog.Link, vm: NotesViewModel) {
    var url by remember { mutableStateOf(dialog.initialUrl) }
    PickerDialog("Link", vm::closeDialog) {
        Text(
            if (dialog.hasSelection) "The selected text becomes the link."
            else "The whole block becomes the link.",
            color = Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 46.dp)
                .clip(RoundedCornerShape(11.dp))
                .border(1.dp, Border, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                cursorBrush = SolidColor(Green),
                textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (url.isEmpty()) Text("example.com", color = Muted, fontSize = 14.sp)
                    inner()
                },
                modifier = Modifier.fillMaxWidth().testTag("notes_link_field"),
            )
        }
        Spacer(Modifier.height(12.dp))
        PickerRow("Apply link", false) {
            vm.setLink(url); vm.closeDialog()
        }
        if (dialog.initialUrl.isNotEmpty()) PickerRow("Remove link", false) {
            vm.setLink(null); vm.closeDialog()
        }
        PickerRow("Cancel", false) { vm.closeDialog() }
    }
}

/** The project whose colours a note's editor and its reading view both use. */
@Composable
private fun rememberNoteProjectId(ownerKey: String): String? {
    val tasks by VirlinGraph.repository.tasks.collectAsState()
    val captures by VirlinGraph.repository.captures.collectAsState()
    return when {
        ownerKey.startsWith("project-") -> ownerKey.removePrefix("project-")
        ownerKey.startsWith("task-") -> tasks.firstOrNull { it.id == TaskPageTypeKeys.noteTaskId(ownerKey) }?.projectId
        ownerKey.startsWith("capture-") -> captures.firstOrNull { it.id == ownerKey.removePrefix("capture-") }?.projectId
        else -> CaptureNoteBridge.draftProject(ownerKey)
    }
}

/** A real full-screen destination. No dimmed editor, app footer, or dialog inset can show through. */
@Composable
fun NotesReaderRoute(navController: NavController, ownerKey: String, title: String) {
    val editorEntry = navController.previousBackStackEntry ?: return
    val vm: NotesViewModel = viewModel(
        viewModelStoreOwner = editorEntry,
        key = "notes-$ownerKey",
        factory = NotesViewModel.factory(ownerKey, title),
    )
    val state by vm.state.collectAsState()
    NoteAudioPlaybackHost()
    val context = LocalContext.current
    val view = LocalView.current
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
    }
    DisposableEffect(activity, view) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previousBehavior = controller?.systemBarsBehavior
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            previousBehavior?.let { controller?.systemBarsBehavior = it }
        }
    }
    val back: () -> Unit = { navController.popBackStack(); Unit }
    BackHandler(onBack = back)
    // The editor's own renderer, colours and fonts, read only: what the note really looks like.
    CompositionLocalProvider(LocalNotesPalette provides rememberProjectPalette(rememberNoteProjectId(ownerKey))) {
        Surface(modifier = Modifier.fillMaxSize(), color = NotesPaper) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
                .testTag("notes_preview")) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).background(Color.White)
                        .padding(start = 8.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("← Edit", color = Green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { back() }
                            .padding(horizontal = 12.dp, vertical = 10.dp).testTag("notes_preview_close"))
                    Spacer(Modifier.weight(1f))
                    Text("Reading view", color = Muted, fontSize = 13.sp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    when {
                        state.loading -> Unit
                        state.damaged -> Text("This note could not be read. Open it in the editor to recover it.",
                            color = Muted, fontSize = 15.sp, lineHeight = 22.sp,
                            modifier = Modifier.padding(top = NoteDocumentTopSpace))
                        else -> NoteBlockEditor(
                            state = state, vm = vm,
                            modifier = Modifier.fillMaxSize(),
                            readOnly = true,
                            readerHeader = {
                                Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
                                    Text(state.title.ifBlank { "Untitled note" }, color = Ink,
                                        fontSize = 27.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.testTag("notes_preview_title"))
                                    if (state.blocks.all { it.text.isBlank() && it.table == null &&
                                            it.type != NoteDocBlockType.DIVIDER && it.type != NoteDocBlockType.IMAGE &&
                                            it.type != NoteDocBlockType.VIDEO && it.type != NoteDocBlockType.AUDIO }) {
                                        Spacer(Modifier.height(16.dp))
                                        Text("This note is empty.", color = Muted, fontSize = 16.sp)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A read-only preview of a note for cards outside the editor: drawn by the editor itself, so
 * prompts, code, tables, media and spacing look exactly as they do while editing, and Copy works.
 * Uses [palette]'s colours (the note editor's).
 */
@Composable
internal fun NoteDocumentPreview(
    blocks: List<NoteDocBlock>,
    palette: com.virlin.app.ui.hierarchy.ProjectPalette?,
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(LocalNotesPalette provides palette) {
        NoteAudioPlaybackHost()
        NoteReadOnlyBlocks(blocks, modifier)
    }
}

@Composable
internal fun PreviewBlock(block: NoteDocBlock, before: List<NoteDocBlock>) {
    val quoteAccent = Green
    if (com.virlin.app.domain.notedoc.NoteMath.isEquation(block)) {
        NoteEquationView(block.text)
        return
    }
    when (block.type) {
        NoteDocBlockType.IMAGE -> NoteImageBlock(block.imagePath,
            Modifier.padding(start = (block.indent * 14).dp))
        NoteDocBlockType.VIDEO -> {
            var playing by remember(block.id) { mutableStateOf(false) }
            NoteVideoCard(block.video, playing, onPlay = { playing = true }, onStop = { playing = false },
                modifier = Modifier.padding(start = (block.indent * 14).dp))
        }
        NoteDocBlockType.AUDIO -> NoteAudioCard(block.audio, Modifier.padding(start = (block.indent * 14).dp))
        NoteDocBlockType.TOGGLE -> Row(
            Modifier.fillMaxWidth().padding(start = (block.indent * 14).dp)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            NoteToggleArrow(collapsed = false, textSizeSp = 14, lineHeightSp = 20f, color = Ink)
            Spacer(Modifier.width(4.dp))
            Text(block.text.ifBlank { "Toggle" }, color = Ink, fontSize = 14.sp, lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold)
        }
        NoteDocBlockType.DIVIDER ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))

        NoteDocBlockType.TABLE -> block.table?.let { t ->
            val accent = Green
            Column(Modifier.fillMaxWidth()) {
                repeat(t.rows) { r ->
                    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        repeat(t.cols) { c ->
                            val header = t.isHeader(r, c)
                            val fill = t.fillAt(r, c)?.let { Color(it) } ?: if (header) NotesWash else Color.White
                            Box(
                                Modifier.weight(1f).fillMaxHeight()
                                    .background(if (t.gridLines) Border else fill).padding(1.dp)
                                    .background(fill).padding(6.dp),
                            ) {
                                val text = t.cell(r, c)
                                val runs = t.runsAt(r, c)
                                Text(
                                    androidx.compose.ui.text.buildAnnotatedString {
                                        append(text)
                                        runs.forEach { run ->
                                            val s = run.start.coerceIn(0, text.length)
                                            val e = run.end.coerceIn(s, text.length)
                                            if (e > s) addStyle(run.toSpanStyle(accent), s, e)
                                        }
                                    },
                                    color = Ink, fontSize = 13.sp,
                                    fontWeight = if (header) FontWeight.SemiBold else null,
                                )
                            }
                        }
                    }
                }
            }
        }

        else -> {
            val prefix = when (block.type) {
                NoteDocBlockType.BULLET -> "${NoteListStyles.marker(block.type, block.listStyle)}  "
                NoteDocBlockType.NUMBERED -> {
                    val n = before.reversed()
                        .takeWhile { it.type == NoteDocBlockType.NUMBERED && it.indent == block.indent }
                        .size + 1
                    "${NoteListStyles.marker(block.type, block.listStyle, n)}  "
                }
                NoteDocBlockType.CHECKLIST -> if (block.checked) "✓  " else "▢  "
                else -> ""
            }
            val annotated = androidx.compose.ui.text.buildAnnotatedString {
                append(prefix)
                val base = length
                append(block.text)
                block.runs.forEach { r ->
                    val s = base + r.start.coerceIn(0, block.text.length)
                    val e = base + r.end.coerceIn(0, block.text.length)
                    if (e <= s) return@forEach
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontWeight = if (NoteDocMark.BOLD in r.marks) FontWeight.Bold else null,
                            fontStyle = if (NoteDocMark.ITALIC in r.marks) {
                                androidx.compose.ui.text.font.FontStyle.Italic
                            } else null,
                            textDecoration = buildList {
                                if (NoteDocMark.UNDERLINE in r.marks || r.linkUrl != null) {
                                    add(TextDecoration.Underline)
                                }
                                if (NoteDocMark.STRIKE in r.marks) add(TextDecoration.LineThrough)
                            }.let { if (it.isEmpty()) null else TextDecoration.combine(it) },
                            color = when {
                                r.linkUrl != null -> Green
                                r.color != null -> Color(r.color.toULong().toLong())
                                else -> Color.Unspecified
                            },
                            background = r.highlight?.let { Color(it.toULong().toLong()) }
                                ?: Color.Unspecified,
                            fontFamily = when {
                                NoteDocMark.CODE in r.marks -> FontFamily.Monospace
                                else -> NoteFonts.familyFor(r.fontFamily)
                            },
                            fontSize = r.fontSizeSp?.sp
                                ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                        ),
                        s, e,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = (block.indent * 14).dp)
                .then(if (block.type == NoteDocBlockType.CODE)
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(Mint.copy(alpha = .65f))
                        .border(1.dp, Border, RoundedCornerShape(10.dp))
                        .padding(15.dp)
                else if (block.type == NoteDocBlockType.QUOTE)
                    Modifier.clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                        .background(quoteAccent.copy(alpha = .045f))
                        .drawBehind { drawRect(quoteAccent.copy(alpha = .65f), size = Size(3.dp.toPx(), size.height)) }
                        .padding(horizontal = 17.dp, vertical = 15.dp)
                else Modifier)) {
                block.promptTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    annotated,
                    color = if (block.type == NoteDocBlockType.QUOTE) Muted else Ink,
                    fontSize = NotesViewModel.defaultSizeFor(block.type).sp,
                    lineHeight = NoteDocumentLayout.lineHeightSp(block).sp,
                    fontWeight = when (block.type) {
                        NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2,
                        NoteDocBlockType.HEADING_3, NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5,
                        -> FontWeight.SemiBold
                        else -> FontWeight.Normal
                    },
                    fontFamily = if (block.type == NoteDocBlockType.CODE) FontFamily.Monospace else null,
                    textAlign = when (block.align) {
                        NoteDocAlign.CENTER -> TextAlign.Center
                        NoteDocAlign.RIGHT -> TextAlign.End
                        NoteDocAlign.LEFT -> TextAlign.Start
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Image resolution for the on-screen auto-fit pages; printing and export use their own. */
private const val FLOW_VIEW_DPI = 144