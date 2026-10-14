package com.virlin.app.ui.pdf

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pages
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.PanToolAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.ui.hierarchy.LocalProjectPalette
import com.virlin.app.ui.hierarchy.rememberProjectPalette
import com.virlin.app.ui.hierarchy.readableOn
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset

const val PdfWorkspaceScreenTag = "pdf_workspace_screen"
const val PdfChooseFileTag = "pdf_choose_file"
const val PdfSaveToPageTag = "pdf_save_to_page"
const val PdfReaderTag = "pdf_reader"

private val PdfPaper: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.surface) } ?: Color(0xFFFAF9F6)
private val PdfInk: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.text) } ?: Color(0xFF172019)
private val PdfMuted: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.muted) } ?: Color(0xFF68736C)
private val PdfGreen: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF078A5A)
private val PdfMint: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.tertiary) } ?: Color(0xFFE8F7EF)
private val PdfLine: Color @Composable get() = PdfGreen.copy(alpha = .18f)

@Composable
fun PdfWorkspaceScreen(
    navController: NavController,
    captureId: String?,
    taskId: String?,
    projectId: String? = null,
    vm: PdfWorkspaceViewModel = viewModel(
        factory = PdfWorkspaceViewModel.factory(captureId, taskId, LocalContext.current, projectId)
    )
) {
    val state by vm.state.collectAsState()
    var readerPagesOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val palette = rememberProjectPalette(state.context.projectId ?: projectId)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.importPdf(uri)
        }
    }
    fun back() {
        if (state.mode == PdfWorkspaceMode.HOME) navController.popBackStack()
        else vm.setMode(PdfWorkspaceMode.HOME)
    }
    BackHandler { back() }

    CompositionLocalProvider(LocalProjectPalette provides palette) {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
        primary = PdfGreen, onPrimary = readableOn(PdfGreen),
        surface = PdfPaper, onSurface = PdfInk,
        surfaceVariant = PdfMint, outline = PdfLine)) {
    Column(
        Modifier.fillMaxSize().background(PdfPaper).statusBarsPadding().testTag(PdfWorkspaceScreenTag)
    ) {
        if (state.mode == PdfWorkspaceMode.PREVIEW || state.mode == PdfWorkspaceMode.ANNOTATE) PdfReaderTopBar(
            state = state,
            annotate = state.mode == PdfWorkspaceMode.ANNOTATE,
            onBack = ::back,
            onPages = { readerPagesOpen = true },
            onOrganize = { vm.setMode(PdfWorkspaceMode.ORGANIZE) },
            onExport = { vm.setMode(PdfWorkspaceMode.EXPORT) },
        ) else PdfTopBar(
            title = when (state.mode) {
                PdfWorkspaceMode.HOME -> "PDF workspace"
                PdfWorkspaceMode.PREVIEW -> state.displayName.ifBlank { "Preview" }
                PdfWorkspaceMode.ANNOTATE -> "Annotate"
                PdfWorkspaceMode.ORGANIZE -> "Organize pages"
                PdfWorkspaceMode.CROP -> "Crop selected pages"
                PdfWorkspaceMode.OCR -> "Extract text"
                PdfWorkspaceMode.EXPORT -> "Export"
            },
            subtitle = when (state.mode) {
                PdfWorkspaceMode.HOME -> state.taskTitle?.let { "Attached to · $it" } ?: "PDF"
                PdfWorkspaceMode.PREVIEW, PdfWorkspaceMode.ANNOTATE -> "${state.currentPage + 1} / ${state.pageCount}"
                PdfWorkspaceMode.ORGANIZE -> "${state.pageCount} pages"
                PdfWorkspaceMode.CROP -> "Page ${state.currentPage + 1}"
                PdfWorkspaceMode.OCR -> "Local OCR capability"
                PdfWorkspaceMode.EXPORT -> "${state.selectedOrAll.size} selected pages"
            },
            onBack = ::back
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.mode) {
                PdfWorkspaceMode.HOME -> PdfHome(state, vm) { picker.launch(arrayOf("application/pdf")) }
                PdfWorkspaceMode.PREVIEW -> PdfReader(state, readerPagesOpen,
                    onPageChanged = vm::setCurrentPage, setPagesOpen = { readerPagesOpen = it })
                PdfWorkspaceMode.ANNOTATE -> PdfReader(state, readerPagesOpen,
                    onPageChanged = vm::setCurrentPage, setPagesOpen = { readerPagesOpen = it },
                    annotationEnabled = true, onAddAnnotation = vm::addAnnotation,
                    onEraseAnnotation = vm::eraseAnnotation, onUndoAnnotation = vm::undoAnnotation)
                PdfWorkspaceMode.ORGANIZE -> PdfOrganize(state, vm)
                PdfWorkspaceMode.CROP -> PdfCropScreen(state, vm)
                PdfWorkspaceMode.OCR -> PdfOcrScreen(state)
                PdfWorkspaceMode.EXPORT -> PdfExportScreen(state, vm)
            }
            if (state.busy) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .68f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = PdfGreen)
            }
        }
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            confirmButton = { TextButton(vm::clearMessage) { Text("OK") } },
            text = { Text(message) }
        )
    }
    }
    }
}

@Composable
private fun PdfTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = PdfInk) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PdfInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = PdfMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton({}) { Icon(Icons.Rounded.MoreVert, "More", tint = PdfInk) }
    }
}

@Composable
private fun PdfReaderTopBar(
    state: PdfWorkspaceState,
    annotate: Boolean,
    onBack: () -> Unit,
    onPages: () -> Unit,
    onOrganize: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(PdfPaper).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = PdfInk) }
        Column(Modifier.weight(1f)) {
            Text(state.displayName, color = PdfInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${if (annotate) "Annotate · " else ""}Page ${state.currentPage + 1} of ${state.pageCount}",
                color = PdfMuted, fontSize = 12.sp)
        }
        IconButton(onPages) { Icon(Icons.Rounded.GridView, "Page thumbnails", tint = PdfInk) }
        Box {
            IconButton({ menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "More PDF actions", tint = PdfInk) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Organize pages") }, onClick = { menuOpen = false; onOrganize() })
                DropdownMenuItem(text = { Text("Export") }, onClick = { menuOpen = false; onExport() })
            }
        }
    }
}

private data class PdfHomeAction(
    val mode: PdfWorkspaceMode,
    val title: String,
    val detail: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
private fun PdfHome(state: PdfWorkspaceState, vm: PdfWorkspaceViewModel, chooseFile: () -> Unit) {
    val actions = listOf(
        PdfHomeAction(PdfWorkspaceMode.PREVIEW, "Preview", "Read the document", Icons.Rounded.RemoveRedEye),
        PdfHomeAction(PdfWorkspaceMode.ANNOTATE, "Annotate", "Highlight, draw & add notes", Icons.Rounded.Draw),
        PdfHomeAction(PdfWorkspaceMode.ORGANIZE, "Organize", "Select & extract pages", Icons.Rounded.Pages),
        PdfHomeAction(PdfWorkspaceMode.CROP, "Crop", "Trim pages or select an area", Icons.Rounded.Crop),
        PdfHomeAction(PdfWorkspaceMode.OCR, "OCR", "Extract and copy text", Icons.Rounded.TextFields),
        PdfHomeAction(PdfWorkspaceMode.EXPORT, "Export", "Save PDF or images", Icons.Rounded.SaveAlt)
    )
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, PdfLine)) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = chooseFile).testTag(PdfChooseFileTag).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PictureAsPdf, null, tint = PdfGreen, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (state.hasPdf) state.displayName else "Add a PDF", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = PdfInk)
                        Text(if (state.hasPdf) "${state.pageCount} pages · ${formatPdfBytes(state.sizeBytes)}" else "Choose a file from your device", fontSize = 12.sp, color = PdfMuted)
                    }
                    Text(if (state.hasPdf) "Replace" else "Choose file", color = PdfGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp)).border(1.dp, PdfLine, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Image, null, tint = PdfGreen)
                Spacer(Modifier.width(10.dp))
                Text("Show previews", Modifier.weight(1f), color = PdfInk, fontWeight = FontWeight.SemiBold)
                Switch(state.showPreviews, vm::setShowPreviews)
            }
        }
        item { Text("Work with this PDF", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = PdfInk, modifier = Modifier.padding(top = 6.dp)) }
        items(actions.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { action ->
                    Card(
                        modifier = Modifier.weight(1f).clickable(enabled = state.hasPdf) { vm.setMode(action.mode) },
                        colors = CardDefaults.cardColors(containerColor = if (state.hasPdf) PdfMint else PdfMint.copy(alpha = .42f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PdfLine)
                    ) {
                        Column(Modifier.padding(14.dp).height(86.dp)) {
                            Icon(action.icon, null, tint = if (state.hasPdf) PdfGreen else PdfMuted.copy(alpha = .7f))
                            Spacer(Modifier.height(7.dp))
                            Text(action.title, fontWeight = FontWeight.Bold, color = if (state.hasPdf) PdfInk else PdfMuted)
                            Text(if (state.hasPdf) action.detail else "Add a PDF first", fontSize = 10.sp, color = PdfMuted, maxLines = 2)
                        }
                    }
                }
            }
        }
        item {
            Button(
                onClick = vm::saveOriginalToPage,
                enabled = state.hasPdf && !state.committed,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag(PdfSaveToPageTag),
                colors = ButtonDefaults.buttonColors(containerColor = PdfGreen)
            ) { Icon(Icons.Rounded.Description, null); Spacer(Modifier.width(8.dp)); Text(if (state.committed) "Saved to Page" else "Save to Page") }
        }
        item { Text("Original file remains unchanged", Modifier.fillMaxWidth().padding(bottom = 18.dp), color = PdfMuted, fontSize = 11.sp) }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PdfReader(
    state: PdfWorkspaceState,
    pagesOpen: Boolean,
    onPageChanged: (Int) -> Unit,
    setPagesOpen: (Boolean) -> Unit,
    annotationEnabled: Boolean = false,
    onAddAnnotation: (PdfAnnotation) -> Unit = {},
    onEraseAnnotation: (Int, PdfAnnotationPoint) -> Unit = { _, _ -> },
    onUndoAnnotation: () -> Unit = {},
) {
    val path = state.absolutePath ?: return
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage)
    val horizontalState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var zoom by remember(path) { mutableFloatStateOf(1f) }
    var previousZoom by remember(path) { mutableFloatStateOf(1f) }
    var viewportWidth by remember(path) { mutableIntStateOf(0) }
    var annotationTool by remember { mutableStateOf(PdfAnnotationTool.VIEW) }
    val density = LocalDensity.current
    val viewportDp = with(density) { viewportWidth.toDp() }
    val pageWidth = ((viewportDp - 24.dp).coerceAtLeast(1.dp) * zoom)
    val documentWidth = maxOf(viewportDp, pageWidth + 24.dp)
    val pinch = rememberTransformableState { zoomChange, _, _ ->
        zoom = (zoom * zoomChange).coerceIn(.5f, 5f)
    }
    // Keep the visible part of the document approximately anchored while its layout grows.
    LaunchedEffect(zoom, viewportWidth) {
        if (viewportWidth == 0 || zoom == previousZoom) return@LaunchedEffect
        val ratio = zoom / previousZoom
        val horizontalCenter = horizontalState.value + viewportWidth / 2f
        val page = listState.firstVisibleItemIndex
        val verticalOffset = listState.firstVisibleItemScrollOffset
        withFrameNanos { }
        horizontalState.scrollTo((horizontalCenter * ratio - viewportWidth / 2f).roundToInt()
            .coerceIn(0, horizontalState.maxValue))
        listState.scrollToItem(page, (verticalOffset * ratio).roundToInt())
        previousZoom = zoom
    }
    LaunchedEffect(listState, state.pageCount) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { page ->
            if (page in 0 until state.pageCount) onPageChanged(page)
        }
    }
    fun jumpTo(page: Int) {
        if (page !in 0 until state.pageCount) return
        onPageChanged(page)
        scope.launch { listState.animateScrollToItem(page) }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFFF1F2F4)).testTag(PdfReaderTag)) {
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewportWidth = it.width }
            .transformable(pinch, canPan = { false })) {
            Box(Modifier.fillMaxSize().horizontalScroll(horizontalState).testTag("pdf_document_pan")) {
                Box(Modifier.width(documentWidth).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        modifier = Modifier.width(pageWidth).fillMaxHeight().testTag("pdf_document_scroll"),
                        state = listState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.pageCount, key = { it }) { page ->
                            PdfReaderPage(path, page, state.crops[page], zoom,
                                onDoubleTap = { zoom = if (zoom > 1f) 1f else 2f },
                                annotationTool = if (annotationEnabled) annotationTool else PdfAnnotationTool.VIEW,
                                annotations = state.annotations.filter { it.page == page },
                                onAddAnnotation = onAddAnnotation,
                                onEraseAnnotation = onEraseAnnotation)
                        }
                    }
                }
            }
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
                    .background(PdfPaper.copy(alpha = .94f), RoundedCornerShape(24.dp))
                    .padding(vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("${state.currentPage + 1}/${state.pageCount}",
                    Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    color = PdfInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                IconButton({ jumpTo(state.currentPage - 1) }, enabled = state.currentPage > 0) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Previous page")
                }
                IconButton({ jumpTo(state.currentPage + 1) }, enabled = state.currentPage + 1 < state.pageCount) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Next page")
                }
            }
        }
        if (annotationEnabled) PdfAnnotationToolbar(annotationTool, onToolSelected = { annotationTool = it },
            onUndo = onUndoAnnotation)
        Row(
            Modifier.fillMaxWidth().background(PdfPaper).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ setPagesOpen(true) }) { Icon(Icons.Rounded.GridView, "Page thumbnails", tint = PdfGreen) }
            Text("Pages", fontSize = 12.sp, color = PdfInk, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton({ zoom = (zoom - .25f).coerceAtLeast(.5f) }, enabled = zoom > .5f) {
                Icon(Icons.Rounded.ZoomOut, "Zoom out")
            }
            Text("${(zoom * 100).roundToInt()}%", fontSize = 11.sp, color = PdfMuted)
            IconButton({ zoom = (zoom + .25f).coerceAtMost(5f) }, enabled = zoom < 5f) {
                Icon(Icons.Rounded.ZoomIn, "Zoom in")
            }
            TextButton({ zoom = 1f }, enabled = zoom != 1f) { Text("Fit", fontSize = 11.sp) }
        }
    }
    if (pagesOpen) ModalBottomSheet(onDismissRequest = { setPagesOpen(false) }, containerColor = PdfPaper) {
        Text("Pages", Modifier.padding(horizontal = 20.dp), color = PdfInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Jump to a page", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = PdfMuted, fontSize = 12.sp)
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxWidth().height(390.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.pageCount) { page ->
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .border(if (page == state.currentPage) 2.dp else 1.dp,
                            if (page == state.currentPage) PdfGreen else PdfLine, RoundedCornerShape(8.dp))
                        .clickable { setPagesOpen(false); jumpTo(page) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.showPreviews) PdfPageImage(path, page, 240, state.crops[page], Modifier.fillMaxWidth().aspectRatio(.72f))
                    else Box(Modifier.fillMaxWidth().aspectRatio(.72f).background(Color.White), Alignment.Center) {
                        Icon(Icons.Rounded.Description, null, tint = PdfMuted)
                    }
                    Text("${page + 1}", fontSize = 12.sp, color = if (page == state.currentPage) PdfGreen else PdfInk)
                }
            }
        }
    }
}

@Composable
private fun PdfAnnotationToolbar(
    selected: PdfAnnotationTool,
    onToolSelected: (PdfAnnotationTool) -> Unit,
    onUndo: () -> Unit,
) {
    val tools = listOf(
        Triple(PdfAnnotationTool.VIEW, Icons.Rounded.PanToolAlt, Color(0xFF137B67)),
        Triple(PdfAnnotationTool.HIGHLIGHT, Icons.Rounded.Highlight, Color(0xFFD18B00)),
        Triple(PdfAnnotationTool.PEN, Icons.Rounded.Brush, Color(0xFF6537C9)),
        Triple(PdfAnnotationTool.NOTE, Icons.Rounded.StickyNote2, Color(0xFF2670BE)),
        Triple(PdfAnnotationTool.ERASER, Icons.Rounded.Backspace, Color(0xFFD15B55)),
    )
    Row(Modifier.fillMaxWidth().background(PdfPaper).padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        tools.forEach { (tool, icon, color) ->
            PdfAnnotationAction(icon, tool.name.lowercase().replaceFirstChar(Char::uppercase), color,
                selected == tool, Modifier.weight(1f)) { onToolSelected(tool) }
        }
        PdfAnnotationAction(Icons.Rounded.Undo, "Undo annotation", Color(0xFF68758A), false,
            Modifier.weight(1f), onUndo)
    }
}

@Composable
private fun PdfAnnotationAction(icon: ImageVector, description: String, color: Color,
    selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        IconButton(onClick, Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = if (selected) .2f else .08f))
            .border(if (selected) 1.5.dp else 0.dp, color.copy(alpha = .55f), RoundedCornerShape(14.dp))) {
            Icon(icon, description, tint = color)
        }
    }
}

@Composable
private fun PdfReaderPage(path: String, page: Int, crop: PdfCrop?, zoom: Float,
    onDoubleTap: () -> Unit, annotationTool: PdfAnnotationTool,
    annotations: List<PdfAnnotation>, onAddAnnotation: (PdfAnnotation) -> Unit,
    onEraseAnnotation: (Int, PdfAnnotationPoint) -> Unit) {
    val renderWidth = if (zoom > 2f) 2400 else 1600
    var bitmap by remember(path, page, crop, renderWidth) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path, page, crop, renderWidth) { mutableStateOf(false) }
    LaunchedEffect(path, page, crop, renderWidth) {
        failed = false
        val rendered = withContext(Dispatchers.IO) {
            runCatching { PdfDocumentEngine.renderPage(File(path), page, renderWidth, crop ?: PdfCrop()) }
        }
        bitmap = rendered.getOrNull()
        failed = rendered.isFailure
    }
    val ratio = bitmap?.let { it.width.toFloat() / it.height } ?: .72f
    var draft by remember(page) { mutableStateOf<List<PdfAnnotationPoint>>(emptyList()) }
    var notePoint by remember(page) { mutableStateOf<PdfAnnotationPoint?>(null) }
    var noteText by remember(page) { mutableStateOf("") }
    var visibleNote by remember(page) { mutableStateOf<String?>(null) }
    val gestures = when (annotationTool) {
        PdfAnnotationTool.VIEW -> Modifier.pointerInput(path, page, zoom) {
            detectTapGestures(onDoubleTap = { onDoubleTap() })
        }
        PdfAnnotationTool.HIGHLIGHT, PdfAnnotationTool.PEN -> Modifier.pointerInput(page, annotationTool) {
            detectDragGestures(onDragStart = { start ->
                draft = listOf(PdfAnnotationPoint((start.x / size.width).coerceIn(0f, 1f),
                    (start.y / size.height).coerceIn(0f, 1f)))
            }, onDrag = { change, _ ->
                change.consume()
                draft = draft + PdfAnnotationPoint((change.position.x / size.width).coerceIn(0f, 1f),
                    (change.position.y / size.height).coerceIn(0f, 1f))
            }, onDragEnd = {
                if (draft.isNotEmpty()) onAddAnnotation(PdfAnnotation(page, annotationTool, draft))
                draft = emptyList()
            }, onDragCancel = { draft = emptyList() })
        }
        PdfAnnotationTool.NOTE, PdfAnnotationTool.ERASER -> Modifier.pointerInput(page, annotationTool) {
            detectTapGestures { position ->
                val point = PdfAnnotationPoint((position.x / size.width).coerceIn(0f, 1f),
                    (position.y / size.height).coerceIn(0f, 1f))
                if (annotationTool == PdfAnnotationTool.NOTE) { notePoint = point; noteText = "" }
                else onEraseAnnotation(page, point)
            }
        }
    }
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(ratio).shadow(3.dp, RoundedCornerShape(3.dp))
            .background(Color.White).then(gestures).testTag("pdf_reader_page_$page"),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(bitmap!!.asImageBitmap(), "PDF page ${page + 1}",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            failed -> Text("Page ${page + 1} could not be displayed", color = PdfMuted, fontSize = 13.sp)
            else -> CircularProgressIndicator(color = PdfGreen, modifier = Modifier.size(24.dp))
        }
        Canvas(Modifier.fillMaxSize()) {
            (annotations.filter { it.tool != PdfAnnotationTool.NOTE } +
                if (draft.isNotEmpty()) listOf(PdfAnnotation(page, annotationTool, draft)) else emptyList()).forEach { mark ->
                val color = if (mark.tool == PdfAnnotationTool.HIGHLIGHT)
                    Color(0xFFFFCC32).copy(alpha = .55f) else Color(0xFF6840CB)
                val width = if (mark.tool == PdfAnnotationTool.HIGHLIGHT) 18.dp.toPx() else 3.dp.toPx()
                val points = mark.points.map { Offset(it.x * size.width, it.y * size.height) }
                if (points.size == 1) drawCircle(color, width / 2f, points.first())
                else points.zipWithNext().forEach { (start, end) ->
                    drawLine(color, start, end, width, cap = StrokeCap.Round)
                }
            }
        }
        annotations.filter { it.tool == PdfAnnotationTool.NOTE }.forEach { mark ->
            val point = mark.points.firstOrNull() ?: return@forEach
            IconButton({ visibleNote = mark.text }, Modifier.align(Alignment.TopStart)
                .offset(x = maxWidth * point.x - 18.dp, y = maxHeight * point.y - 18.dp)
                .size(36.dp).background(Color(0xFFDBEBFF), RoundedCornerShape(10.dp))) {
                Icon(Icons.Rounded.StickyNote2, "Read note", tint = Color(0xFF2670BE),
                    modifier = Modifier.size(20.dp))
            }
        }
    }
    if (notePoint != null) AlertDialog(onDismissRequest = { notePoint = null },
        title = { Text("Add note") }, text = { OutlinedTextField(noteText, { noteText = it },
            label = { Text("Note") }, minLines = 3) },
        confirmButton = { TextButton({
            val point = notePoint
            if (point != null && noteText.isNotBlank())
                onAddAnnotation(PdfAnnotation(page, PdfAnnotationTool.NOTE, listOf(point), noteText.trim()))
            notePoint = null
        }) { Text("Save") } },
        dismissButton = { TextButton({ notePoint = null }) { Text("Cancel") } })
    visibleNote?.let { text -> AlertDialog(onDismissRequest = { visibleNote = null },
        text = { Text(text) }, confirmButton = { TextButton({ visibleNote = null }) { Text("Close") } }) }
}

@Composable
private fun PdfOrganize(state: PdfWorkspaceState, vm: PdfWorkspaceViewModel) {
    var range by remember(state.selectedPages) { mutableStateOf(formatPdfPageRange(state.selectedPages)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${state.selectedPages.size} selected", Modifier.weight(1f), fontWeight = FontWeight.Bold)
            TextButton(vm::clearSelection) { Text("Clear") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(range, { range = it }, label = { Text("Page range") }, placeholder = { Text("12-16, 19-21") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(Modifier.width(8.dp)); Button({ vm.selectRange(range) }, colors = ButtonDefaults.buttonColors(containerColor = PdfGreen)) { Text("Apply") }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items((0 until state.pageCount).toList()) { page ->
                val selected = page in state.selectedPages
                Column(
                    Modifier.clickable { vm.togglePage(page) }.background(if (selected) PdfMint else Color.White, RoundedCornerShape(8.dp))
                        .border(if (selected) 2.dp else 1.dp, if (selected) PdfGreen else PdfLine, RoundedCornerShape(8.dp)).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (state.showPreviews) PdfPageImage(state.absolutePath, page, 240, null, Modifier.fillMaxWidth().height(130.dp))
                    else Box(Modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Description, null, tint = PdfMuted) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("${page + 1}", fontSize = 11.sp); if (selected) Icon(Icons.Rounded.Check, null, tint = PdfGreen, modifier = Modifier.size(16.dp)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton({ vm.setMode(PdfWorkspaceMode.EXPORT) }, Modifier.weight(1f)) { Text("Extract PDF", fontSize = 11.sp) }
            OutlinedButton({ vm.setMode(PdfWorkspaceMode.CROP) }, Modifier.weight(1f)) { Text("Crop selected", fontSize = 11.sp) }
            OutlinedButton({ vm.exportSelectionToPage(PdfExportFormat.PNG) }, Modifier.weight(1f)) { Text("To images", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun PdfCropScreen(state: PdfWorkspaceState, vm: PdfWorkspaceViewModel) {
    var left by remember(state.currentPage) { mutableStateOf(state.crops[state.currentPage]?.left ?: 0f) }
    var top by remember(state.currentPage) { mutableStateOf(state.crops[state.currentPage]?.top ?: 0f) }
    var right by remember(state.currentPage) { mutableStateOf(state.crops[state.currentPage]?.right ?: 1f) }
    var bottom by remember(state.currentPage) { mutableStateOf(state.crops[state.currentPage]?.bottom ?: 1f) }
    var all by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        PdfPageImage(state.absolutePath, state.currentPage, 900, PdfCrop(left, top, right, bottom), Modifier.weight(1f).fillMaxWidth())
        Text("Crop bounds", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        CropSlider("Left", left, { left = it.coerceAtMost(right - .05f) })
        CropSlider("Top", top, { top = it.coerceAtMost(bottom - .05f) })
        CropSlider("Right", right, { right = it.coerceAtLeast(left + .05f) })
        CropSlider("Bottom", bottom, { bottom = it.coerceAtLeast(top + .05f) })
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(all, { all = it }); Text("Use same crop for all selected pages") }
        Text("The crop is applied only to exported copies.", fontSize = 11.sp, color = PdfMuted)
        Button(
            { vm.setCrop(state.currentPage, PdfCrop(left, top, right, bottom), all); vm.setMode(PdfWorkspaceMode.EXPORT) },
            Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = PdfGreen)
        ) { Text("Apply crop") }
    }
}

@Composable
private fun CropSlider(label: String, value: Float, change: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.width(54.dp), fontSize = 11.sp); Slider(value, change, valueRange = 0f..1f, modifier = Modifier.weight(1f)); Text("${(value * 100).toInt()}%", fontSize = 10.sp) }
}

@Composable
private fun PdfOcrScreen(state: PdfWorkspaceState) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, PdfLine)) {
            Column(Modifier.padding(16.dp)) {
                Icon(Icons.Rounded.AutoFixHigh, null, tint = PdfGreen, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp)); Text("OCR foundation", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Choose whole pages or a selected area, then review extracted text before copying or saving it.", color = PdfMuted, fontSize = 13.sp)
            }
        }
        PdfPageImage(state.absolutePath, state.currentPage, 720, state.crops[state.currentPage], Modifier.fillMaxWidth().height(280.dp))
        OutlinedTextField("", {}, label = { Text("Extracted text") }, placeholder = { Text("Text will appear here after the local OCR engine is installed.") }, modifier = Modifier.fillMaxWidth().weight(1f), enabled = false)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({}, Modifier.weight(1f), enabled = false) { Icon(Icons.Rounded.ContentCopy, null); Text("Copy all") }
            OutlinedButton({}, Modifier.weight(1f), enabled = false) { Text("Save as note") }
        }
        Text("OCR remains disabled until a bundled, offline recognition dependency is approved in the integration branch.", color = PdfMuted, fontSize = 11.sp)
    }
}

@Composable
private fun PdfExportScreen(state: PdfWorkspaceState, vm: PdfWorkspaceViewModel) {
    var format by remember { mutableStateOf(PdfExportFormat.PDF) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Format", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ExportChoice("PDF", Icons.Rounded.PictureAsPdf, format == PdfExportFormat.PDF, Modifier.weight(1f)) { format = PdfExportFormat.PDF }
            ExportChoice("Images", Icons.Rounded.Image, format == PdfExportFormat.PNG, Modifier.weight(1f)) { format = PdfExportFormat.PNG }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, PdfLine)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Output", fontWeight = FontWeight.Bold)
                Text("${state.selectedOrAll.size} pages · ${if (format == PdfExportFormat.PDF) "Rasterized PDF" else "PNG images"}", color = PdfMuted)
                if (state.crops.isNotEmpty()) Text("${state.crops.size} page crop${if (state.crops.size == 1) "" else "s"} applied", color = PdfGreen)
                Text("Destination · Task Page", color = PdfMuted)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Exports preserve the visual page. Native Android export rasterizes PDF pages; the original remains unchanged.", color = PdfMuted, fontSize = 11.sp)
        Button({ vm.exportSelectionToPage(format) }, Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = PdfGreen)) {
            Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.width(8.dp)); Text(if (format == PdfExportFormat.PDF) "Export PDF to Page" else "Export images to Page")
        }
    }
}

@Composable
private fun ExportChoice(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = if (selected) PdfMint else Color.White), border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp, if (selected) PdfGreen else PdfLine)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, tint = if (selected) PdfGreen else PdfMuted); Text(label, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun PdfPageImage(path: String?, page: Int, width: Int, crop: PdfCrop?, modifier: Modifier) {
    var bitmap by remember(path, page, width, crop) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, page, width, crop) {
        // Compose can replay a display list after state changes; recycling here can crash it.
        bitmap = null
        bitmap = withContext(Dispatchers.IO) {
            path?.let(::File)?.takeIf(File::exists)?.let { runCatching { PdfDocumentEngine.renderPage(it, page, width, crop ?: PdfCrop()) }.getOrNull() }
        }
    }
    Box(modifier.background(Color(0xFFE8E9E7)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), "PDF page ${page + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: CircularProgressIndicator(color = PdfGreen, modifier = Modifier.size(24.dp))
    }
}

private fun formatPdfBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
