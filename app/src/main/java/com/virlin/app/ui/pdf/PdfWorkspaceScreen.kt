package com.virlin.app.ui.pdf

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val PdfWorkspaceScreenTag = "pdf_workspace_screen"
const val PdfChooseFileTag = "pdf_choose_file"
const val PdfSaveToPageTag = "pdf_save_to_page"

private val PdfPaper = Color(0xFFFAF9F6)
private val PdfInk = Color(0xFF172019)
private val PdfMuted = Color(0xFF68736C)
private val PdfGreen = Color(0xFF078A5A)
private val PdfMint = Color(0xFFE8F7EF)
private val PdfLine = Color(0xFFDCE4DF)

@Composable
fun PdfWorkspaceScreen(
    navController: NavController,
    captureId: String?,
    taskId: String?,
    vm: PdfWorkspaceViewModel = viewModel(
        factory = PdfWorkspaceViewModel.factory(captureId, taskId, LocalContext.current)
    )
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
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

    Column(
        Modifier.fillMaxSize().background(PdfPaper).statusBarsPadding().testTag(PdfWorkspaceScreenTag)
    ) {
        PdfTopBar(
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
                PdfWorkspaceMode.PREVIEW -> PdfPreview(state, vm, annotate = false)
                PdfWorkspaceMode.ANNOTATE -> PdfPreview(state, vm, annotate = true)
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
                    Icon(Icons.Rounded.PictureAsPdf, null, tint = Color(0xFFD83232), modifier = Modifier.size(42.dp))
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
                        colors = CardDefaults.cardColors(containerColor = if (state.hasPdf) PdfMint else Color(0xFFF4F5F4)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, PdfLine)
                    ) {
                        Column(Modifier.padding(14.dp).height(86.dp)) {
                            Icon(action.icon, null, tint = if (state.hasPdf) PdfGreen else Color.Gray)
                            Spacer(Modifier.height(7.dp))
                            Text(action.title, fontWeight = FontWeight.Bold, color = if (state.hasPdf) PdfInk else Color.Gray)
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

@Composable
private fun PdfPreview(state: PdfWorkspaceState, vm: PdfWorkspaceViewModel, annotate: Boolean) {
    var pagesOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            PdfPageImage(state.absolutePath, state.currentPage, 1080, state.crops[state.currentPage], Modifier.fillMaxSize())
            if (pagesOpen) {
                Card(
                    Modifier.align(Alignment.BottomStart).padding(10.dp).width(180.dp).height(330.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .95f))
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Pages", Modifier.weight(1f).padding(10.dp), fontWeight = FontWeight.Bold)
                        IconButton({ pagesOpen = false }) { Icon(Icons.Rounded.Close, "Close pages") }
                    }
                    LazyColumn {
                        items((0 until state.pageCount).toList()) { page ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.setCurrentPage(page); pagesOpen = false }
                                    .background(if (page == state.currentPage) PdfMint else Color.Transparent).padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (state.showPreviews) PdfPageImage(state.absolutePath, page, 160, null, Modifier.size(54.dp, 72.dp))
                                Spacer(Modifier.width(8.dp)); Text("${page + 1}", fontWeight = if (page == state.currentPage) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton({ pagesOpen = !pagesOpen }) { Icon(Icons.Rounded.Pages, null); Spacer(Modifier.width(6.dp)); Text("Pages · ${state.currentPage + 1} / ${state.pageCount}") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ vm.setCurrentPage(state.currentPage - 1) }, enabled = state.currentPage > 0) { Text("−") }
                Text("Page ${state.currentPage + 1}", fontSize = 12.sp)
                IconButton({ vm.setCurrentPage(state.currentPage + 1) }, enabled = state.currentPage + 1 < state.pageCount) { Text("+") }
            }
        }
        if (annotate) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Highlight", "Pen", "Note", "Eraser", "Undo").forEach { TextButton({}, enabled = false) { Text(it, fontSize = 11.sp) } }
            }
            Text("Annotation storage is gated until the non-destructive sidecar contract is approved.", color = PdfMuted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
    }
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
        bitmap?.recycle(); bitmap = null
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
