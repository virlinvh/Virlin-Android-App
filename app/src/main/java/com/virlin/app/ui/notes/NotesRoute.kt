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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
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
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.VirlinNoteCodec
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.visibleNoteBlocks
import com.virlin.app.data.noteimage.NoteImageStore
import kotlinx.coroutines.Dispatchers
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
    var pendingFlashcard by remember { mutableStateOf<NoteDocBlock?>(null) }
    var flashcardSaving by remember { mutableStateOf(false) }
    var flashcardError by remember { mutableStateOf<String?>(null) }
    var documentSelectionMode by remember { mutableStateOf(false) }
    var headingFoldRequest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var documentSelection by remember { mutableStateOf(TextRange.Zero) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { NoteImageStore.import(context, uri) } }
                .onSuccess(vm::insertImage)
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
    val tasks by VirlinGraph.repository.tasks.collectAsState()
    val captures by VirlinGraph.repository.captures.collectAsState()
    val projectId = when {
        ownerKey.startsWith("project-") -> ownerKey.removePrefix("project-")
        ownerKey.startsWith("task-") -> tasks.firstOrNull { it.id == ownerKey.removePrefix("task-") }?.projectId
        ownerKey.startsWith("capture-") -> captures.firstOrNull { it.id == ownerKey.removePrefix("capture-") }?.projectId
        else -> CaptureNoteBridge.draftProject(ownerKey)
    }
    val palette = rememberProjectPalette(projectId)
    BackHandler { vm.saveAndClose { navController.popBackStack() } }

    // The debounce must never eat the last keystrokes when the page goes away.
    DisposableEffect(Unit) { onDispose { vm.flush() } }

    CompositionLocalProvider(LocalNotesPalette provides palette) {
    MaterialTheme(colorScheme = lightColorScheme(primary = NotesAccent, onPrimary = readableOn(NotesAccent),
        surface = NotesPaper, onSurface = NotesInk, secondary = NotesAccent)) {
    val handleTool: (NoteTool) -> Unit = { tool ->
        when (tool) {
            NoteTool.SELECT_DOCUMENT -> {
                documentSelectionMode = !documentSelectionMode
                focusManager.clearFocus(); keyboard?.hide()
            }
            NoteTool.IMAGE -> pickImage()
            NoteTool.FLASHCARD -> {
                val selected = if (documentSelectionMode) {
                    val text = selectableNoteText(state.blocks).text
                    val start = documentSelection.min.coerceIn(0, text.length)
                    val end = documentSelection.max.coerceIn(start, text.length)
                    flashcardSourceFromCopiedText(state.blocks, text.substring(start, end), start)
                } else vm.selectedFlashcardContent()
                if (selected == null) vm.openDialog(NoteDialog.Message("Select note text first."))
                else { pendingFlashcard = selected; flashcardError = null }
            }
            NoteTool.HEADING_FOLD -> headingFoldRequest++
            else -> vm.onTool(tool)
        }
    }
    VirlinNotesScreen(
        taskTitle = state.title.ifBlank { title },
        saveLabel = vm.saveLabel(),
        tools = vm.toolState().let { tools -> tools.copy(
            enabled = tools.enabled + NoteTool.SELECT_DOCUMENT +
                if (documentSelectionMode && !documentSelection.collapsed)
                    setOf(NoteTool.FLASHCARD) else emptySet(),
        ) },
        onClose = { vm.saveAndClose { navController.popBackStack() } },
        onTool = handleTool,
        onAddBlock = { documentSelectionMode = false; vm.openAddBlock() },
        onPreview = {
            vm.saveAndClose {
                focusManager.clearFocus(); keyboard?.hide()
                navController.navigate(notesReaderRoute(ownerKey, title)) { launchSingleTop = true }
            }
        },
        onExport = vm::onExport,
        headingLevels = state.blocks.mapNotNull { block ->
            when (block.type) {
                NoteDocBlockType.HEADING_1 -> 1
                NoteDocBlockType.HEADING_2 -> 2
                NoteDocBlockType.HEADING_3 -> 3
                else -> null
            }
        }.distinct().sortedDescending(),
        onHeadingFoldLevel = vm::foldHeadingsAtLevel,
        headingFoldRequest = headingFoldRequest,
        documentSelectionMode = documentSelectionMode,
        editor = { headingFoldMode, _ ->
            if (!state.loading) NoteBlockEditor(
                state = state, vm = vm,
                headingFoldMode = headingFoldMode,
                documentSelectionMode = documentSelectionMode,
                documentSelection = documentSelection,
                onDocumentSelectionChange = { documentSelection = it },
                modifier = Modifier.fillMaxSize(),
            )
        },
        footer = {},
    )

    state.dialog?.let { dialog ->
        NoteDialogHost(dialog, state, vm, pickImage)
    }
    pendingFlashcard?.let { content ->
        FlashcardCreateDialog(content, flashcardSaving, flashcardError,
            onDismiss = { pendingFlashcard = null; flashcardError = null },
            onSave = { front, back, difficulty, days ->
                if (!flashcardSaving) scope.launch {
                    flashcardSaving = true
                    runCatching { com.virlin.app.data.flashcard.FlashcardStore.add(context,
                        ownerKey, state.title.ifBlank { title }, back, front.text,
                        difficulty, days, frontContent = front) }
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
                           pickImage: () -> Unit) {
    when (dialog) {
        is NoteDialog.Paragraph -> PickerDialog("Paragraph style", vm::closeDialog) {
            listOf(
                NoteDocBlockType.PARAGRAPH to "Normal text",
                NoteDocBlockType.HEADING_1 to "Heading 1",
                NoteDocBlockType.HEADING_2 to "Heading 2",
                NoteDocBlockType.HEADING_3 to "Heading 3",
                NoteDocBlockType.QUOTE to "Quote",
                NoteDocBlockType.CODE to "Code",
                NoteDocBlockType.PROMPT to "Prompt",
            ).forEach { (type, label) ->
                PickerRow(label, state.focusedBlock?.type == type) {
                    vm.setBlockType(type)
                    vm.closeDialog()
                }
            }
        }

        is NoteDialog.Font -> PickerDialog("Font", vm::closeDialog) {
            NotesViewModel.FONTS.forEach { family ->
                PickerRow(family, vm.toolState().fontLabel == family) {
                    vm.setFont(family)
                }
            }
        }

        is NoteDialog.Size -> PickerDialog("Text size", vm::closeDialog) {
            NotesViewModel.SIZES.forEach { sp ->
                PickerRow("$sp", vm.toolState().sizeLabel == "$sp") {
                    vm.setFontSize(sp)
                }
            }
        }

        is NoteDialog.TextColor -> PickerDialog("Text colour", vm::closeDialog) {
            NotesViewModel.TEXT_COLORS.forEach { (argb, label) ->
                PickerRow(label, false, swatch = argb) {
                    vm.setTextColor(argb)
                }
            }
        }

        is NoteDialog.Highlight -> PickerDialog("Highlight", vm::closeDialog) {
            NotesViewModel.HIGHLIGHTS.forEach { (argb, label) ->
                PickerRow(label, false, swatch = argb) {
                    vm.setHighlight(argb)
                }
            }
        }

        is NoteDialog.LineSpacing -> PickerDialog("Line spacing", vm::closeDialog) {
            NotesViewModel.SPACINGS.forEach { (value, label) ->
                PickerRow(label, state.focusedBlock?.lineSpacing == value) {
                    vm.setLineSpacing(value); vm.closeDialog()
                }
            }
        }

        is NoteDialog.AddBlock -> PickerDialog("Add a block", vm::closeDialog) {
            PickerRow("Image", false) { vm.closeDialog(); pickImage() }
            listOf(
                NoteDocBlockType.PARAGRAPH to "Paragraph",
                NoteDocBlockType.HEADING_1 to "Heading 1",
                NoteDocBlockType.HEADING_2 to "Heading 2",
                NoteDocBlockType.HEADING_3 to "Heading 3",
                NoteDocBlockType.BULLET to "Bulleted list",
                NoteDocBlockType.NUMBERED to "Numbered list",
                NoteDocBlockType.CHECKLIST to "Checklist",
                NoteDocBlockType.QUOTE to "Quote",
                NoteDocBlockType.PROMPT to "Prompt callout",
                NoteDocBlockType.CODE to "Code",
                NoteDocBlockType.TOGGLE to "Toggle",
                NoteDocBlockType.DIVIDER to "Divider",
                NoteDocBlockType.TABLE to "Table",
            ).forEach { (type, label) ->
                PickerRow(label, false) {
                    vm.insertBlock(type)
                    vm.closeDialog()
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Attachment blocks are not built yet.",
                color = Muted, fontSize = 11.sp,
            )
        }

        is NoteDialog.Table -> PickerDialog("Insert table", vm::closeDialog) {
            listOf(2 to 2, 3 to 2, 3 to 3, 4 to 3).forEach { (rows, cols) ->
                PickerRow("$rows × $cols", false) {
                    vm.insertBlock(NoteDocBlockType.TABLE,
                        table = NoteDocTable(rows, cols, List(rows * cols) { "" }))
                    vm.closeDialog()
                }
            }
        }

        is NoteDialog.Link -> LinkDialog(dialog, vm)

        is NoteDialog.Message -> PickerDialog("Note", vm::closeDialog) {
            Text(dialog.text, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            PickerRow("OK", false) { vm.closeDialog() }
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
    var controlsVisible by remember { mutableStateOf(true) }
    CompositionLocalProvider(LocalNotesPalette provides rememberProjectPalette(null)) {
        Surface(modifier = Modifier.fillMaxSize(), color = NotesPaper) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
                .testTag("notes_preview")) {
                if (controlsVisible) Row(
                    Modifier.fillMaxWidth().height(54.dp).background(NotesPaper)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("← Edit", color = Green, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { back() }.testTag("notes_preview_close"))
                    Spacer(Modifier.width(16.dp))
                    Text("Reading view", color = Muted, fontSize = 13.sp)
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .clickable { controlsVisible = !controlsVisible }
                        .padding(horizontal = 27.dp, vertical = 26.dp),
                ) {
                    SelectionContainer {
                        Column {
                            Text("VIRLIN · NOTE", color = Green, fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(17.dp))
                            Text(state.title.ifBlank { "Untitled note" }, color = Ink,
                                fontSize = 27.sp, lineHeight = 36.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(25.dp))
                            if (state.blocks.all { it.text.isBlank() && it.table == null &&
                                    it.type != NoteDocBlockType.DIVIDER && it.type != NoteDocBlockType.IMAGE }) {
                                Text("This note is empty.", color = Muted, fontSize = 16.sp)
                            }
                            val fullBlocks = state.blocks.map { block ->
                                if (block.type == NoteDocBlockType.TOGGLE && block.isCollapsed)
                                    block.copy(isCollapsed = false) else block
                            }
                            fullBlocks.forEachIndexed { index, block ->
                                Spacer(Modifier.height(NoteDocumentLayout.beforeDp(block, index).dp))
                                PreviewBlock(block, fullBlocks.take(index))
                                Spacer(Modifier.height(NoteDocumentLayout.afterDp(block, fullBlocks.getOrNull(index + 1)).dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun PreviewBlock(block: NoteDocBlock, before: List<NoteDocBlock>) {
    val quoteAccent = Green
    if (com.virlin.app.domain.notedoc.NoteMath.isEquation(block)) {
        NoteEquationView(block.text)
        return
    }
    when (block.type) {
        NoteDocBlockType.IMAGE -> NoteImageBlock(block.imagePath,
            Modifier.padding(start = (block.indent * 14).dp))
        NoteDocBlockType.TOGGLE -> Row(
            Modifier.fillMaxWidth().padding(start = (block.indent * 14).dp)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▾", color = Green, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(block.text.ifBlank { "Toggle" }, color = Ink, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold)
        }
        NoteDocBlockType.DIVIDER ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))

        NoteDocBlockType.TABLE -> block.table?.let { t ->
            Column(Modifier.fillMaxWidth()) {
                repeat(t.rows) { r ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(t.cols) { c ->
                            Box(
                                Modifier.weight(1f).background(Border).padding(1.dp)
                                    .background(Color.White).padding(6.dp),
                            ) { Text(t.cell(r, c), color = Ink, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }

        else -> {
            val prefix = when (block.type) {
                NoteDocBlockType.BULLET -> "•  "
                NoteDocBlockType.NUMBERED -> {
                    val n = before.reversed()
                        .takeWhile { it.type == NoteDocBlockType.NUMBERED && it.indent == block.indent }
                        .size + 1
                    "$n.  "
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
                                r.fontFamily == "Serif" -> FontFamily.Serif
                                r.fontFamily == "Mono" -> FontFamily.Monospace
                                r.fontFamily == "Inter" -> FontFamily.SansSerif
                                else -> null
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
                        NoteDocBlockType.HEADING_3,
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
