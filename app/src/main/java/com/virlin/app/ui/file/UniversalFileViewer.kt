package com.virlin.app.ui.file

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.virlin.app.domain.attachment.CsvTableReader
import com.virlin.app.domain.attachment.OoxmlReaders
import com.virlin.app.domain.attachment.TextFileReader
import com.virlin.app.domain.model.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

const val UniversalFileViewerTag = "universal_file_viewer"
const val UnsupportedViewerTag = "unsupported_file_viewer"

/** One resolver → kind-specific embedded viewer. Capture UI never branches on formats. */
@Composable
fun UniversalFileViewer(
    absolutePath: String,
    kind: AttachmentKind,
    displayName: String,
    mimeType: String,
    modifier: Modifier = Modifier
) {
    val file = remember(absolutePath) { File(absolutePath) }
    Box(modifier = modifier.fillMaxSize().testTag(UniversalFileViewerTag)) {
        when (kind) {
            AttachmentKind.PDF -> PdfViewer(file)
            AttachmentKind.IMAGE -> ImageViewer(file)
            AttachmentKind.VIDEO -> VideoViewer(file)
            AttachmentKind.AUDIO -> AudioViewer(file)
            AttachmentKind.TEXT -> TextViewer(file, displayName, mimeType)
            AttachmentKind.CSV -> CsvViewer(file)
            AttachmentKind.DOCX -> DocxViewer(file)
            AttachmentKind.XLSX -> XlsxViewer(file)
            AttachmentKind.PPTX -> PptxViewer(file)
            AttachmentKind.UNSUPPORTED -> UnsupportedViewer(displayName, mimeType)
        }
    }
}

@Composable
private fun UnsupportedViewer(displayName: String, mimeType: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(UnsupportedViewerTag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Unsupported Viewer", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF162016))
        Spacer(Modifier.height(8.dp))
        Text(
            "Virlin cannot render this format in-app yet.\n$displayName\n$mimeType",
            fontSize = 13.sp,
            color = Color(0xFF5C665C),
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun PdfViewer(file: File) {
    var pageCount by remember(file.absolutePath) { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(file.absolutePath) {
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { pageCount = it.pageCount }
                }
            }.onFailure { error = it.message ?: "Could not open PDF" }
        }
    }
    when {
        error != null -> Text(error!!, color = Color(0xFFB45309), modifier = Modifier.padding(16.dp))
        pageCount == 0 -> Text("Loading PDF…", modifier = Modifier.padding(16.dp), color = Color(0xFF8A918A))
        else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val targetWidth = constraints.maxWidth.coerceAtLeast(1)
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(pageCount) { index ->
                    PdfPage(file, index, targetWidth)
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun PdfPage(file: File, pageIndex: Int, targetWidth: Int) {
    var bitmap by remember(file.absolutePath, pageIndex, targetWidth) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.absolutePath, pageIndex, targetWidth) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        renderer.openPage(pageIndex).use { page ->
                            val scale = targetWidth.toFloat() / page.width.toFloat()
                            val w = targetWidth
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "PDF page ${pageIndex + 1}",
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().height(220.dp).background(Color(0xFFF0EEE9)),
            contentAlignment = Alignment.Center
        ) {
            Text("Page ${pageIndex + 1}…", color = Color(0xFF8A918A))
        }
    }
}

@Composable
private fun ImageViewer(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    val bitmap = remember(file.absolutePath) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }
    if (bitmap == null) {
        Text("Could not decode image", modifier = Modifier.padding(16.dp), color = Color(0xFFB45309))
        return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                }
        )
    }
}

@Composable
private fun VideoViewer(file: File) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            VideoView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setVideoURI(Uri.fromFile(file))
                setOnPreparedListener { mp ->
                    mp.isLooping = false
                    start()
                }
                setMediaController(android.widget.MediaController(context).also { mc ->
                    mc.setAnchorView(this)
                })
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp)
            .background(Color.Black)
    )
}

@Composable
private fun AudioViewer(file: File) {
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    val player = remember(file.absolutePath) {
        MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
        }
    }
    DisposableEffect(player) {
        durationMs = player.duration.toLong().coerceAtLeast(0L)
        onDispose {
            runCatching { player.stop() }
            player.release()
        }
    }
    LaunchedEffect(playing) {
        while (isActive && playing) {
            position = player.currentPosition.toLong()
            delay(200)
        }
    }
    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Text(file.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Slider(
            value = if (durationMs > 0) position.toFloat() / durationMs else 0f,
            onValueChange = { frac ->
                val seek = (frac * durationMs).toLong()
                player.seekTo(seek.toInt())
                position = seek
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatMs(position), fontSize = 11.sp, color = Color(0xFF5C665C))
            Spacer(modifier = Modifier.weight(1f))
            Text(formatMs(durationMs), fontSize = 11.sp, color = Color(0xFF5C665C))
        }
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = {
                val next = (position - 15_000).coerceAtLeast(0)
                player.seekTo(next.toInt())
                position = next
            }) { Icon(Icons.Rounded.Replay, contentDescription = "Back 15 seconds") }
            IconButton(onClick = {
                if (playing) {
                    player.pause()
                    playing = false
                } else {
                    player.start()
                    playing = true
                }
            }) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play"
                )
            }
            IconButton(onClick = {
                val next = (position + 15_000).coerceAtMost(durationMs)
                player.seekTo(next.toInt())
                position = next
            }) {
                Icon(
                    Icons.Rounded.Replay,
                    contentDescription = "Forward 15 seconds",
                    modifier = Modifier.graphicsLayer { scaleX = -1f }
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun TextViewer(file: File, displayName: String, mimeType: String) {
    var result by remember(file.absolutePath) { mutableStateOf<TextFileReader.Result?>(null) }
    LaunchedEffect(file.absolutePath) {
        result = withContext(Dispatchers.IO) {
            FileInputStream(file).use { TextFileReader.read(it, displayName, mimeType) }
        }
    }
    val r = result
    if (r == null) {
        Text("Loading…", modifier = Modifier.padding(16.dp), color = Color(0xFF8A918A))
        return
    }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll)
                .verticalScroll(vScroll)
                .padding(16.dp)
        ) {
            Text(
                r.text.ifBlank { "(Empty file)" },
                fontFamily = if (r.monospaced) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (r.monospaced) 12.sp else 14.sp,
                lineHeight = 20.sp,
                color = Color(0xFF162016)
            )
            if (r.truncated) {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Showing first portion only (large file).", fontSize = 11.sp, color = Color(0xFFB45309))
            }
        }
    }
}

@Composable
private fun CsvViewer(file: File) {
    var table by remember(file.absolutePath) { mutableStateOf<CsvTableReader.Table?>(null) }
    LaunchedEffect(file.absolutePath) {
        table = withContext(Dispatchers.IO) {
            FileInputStream(file).use { CsvTableReader.read(it) }
        }
    }
    val t = table
    if (t == null) {
        Text("Loading table…", modifier = Modifier.padding(16.dp))
        return
    }
    val hScroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll)
                .padding(8.dp)
        ) {
            itemsIndexed(t.rows) { index, row ->
                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEach { cell ->
                        Text(
                            cell,
                            fontSize = if (index == 0) 12.sp else 11.sp,
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(120.dp).padding(horizontal = 4.dp),
                            maxLines = 3,
                            color = Color(0xFF162016)
                        )
                    }
                }
            }
        }
        if (t.truncated) {
            Text(
                "Table truncated for memory safety.",
                fontSize = 11.sp,
                color = Color(0xFFB45309),
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun DocxViewer(file: File) {
    var view by remember(file.absolutePath) { mutableStateOf<OoxmlReaders.DocView?>(null) }
    LaunchedEffect(file.absolutePath) {
        view = withContext(Dispatchers.IO) {
            FileInputStream(file).use { OoxmlReaders.readDocx(it) }
        }
    }
    val v = view
    if (v == null) {
        Text("Reading document…", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(v.paragraphs) { p ->
            Text(p, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFF162016))
        }
        if (v.truncated) {
            item {
                Text("Document truncated for memory safety.", fontSize = 11.sp, color = Color(0xFFB45309))
            }
        }
        item {
            Text(
                "Read-only OOXML text extract — not Word-perfect layout.",
                fontSize = 10.sp,
                color = Color(0xFF8A918A),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun XlsxViewer(file: File) {
    var workbook by remember(file.absolutePath) { mutableStateOf<OoxmlReaders.WorkbookView?>(null) }
    var sheetIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(file.absolutePath) {
        workbook = withContext(Dispatchers.IO) {
            FileInputStream(file).use { OoxmlReaders.readXlsx(it) }
        }
    }
    val wb = workbook
    if (wb == null) {
        Text("Reading spreadsheet…", modifier = Modifier.padding(16.dp))
        return
    }
    val sheet = wb.sheets.getOrNull(sheetIndex) ?: wb.sheets.first()
    val hScroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            wb.sheets.forEachIndexed { i, s ->
                Text(
                    s.name,
                    fontWeight = if (i == sheetIndex) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 12.sp,
                    color = if (i == sheetIndex) Color(0xFF0E7490) else Color(0xFF5C665C),
                    modifier = Modifier
                        .background(if (i == sheetIndex) Color(0xFFE8F6F8) else Color.Transparent)
                        .clickable { sheetIndex = i }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll)
                .padding(8.dp)
        ) {
            itemsIndexed(sheet.rows) { index, row ->
                Row(modifier = Modifier.padding(vertical = 3.dp)) {
                    row.forEach { cell ->
                        Text(
                            cell,
                            fontSize = 11.sp,
                            fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.width(100.dp).padding(horizontal = 3.dp),
                            maxLines = 2
                        )
                    }
                }
            }
        }
        if (wb.truncated) {
            Text(
                "Spreadsheet truncated for memory safety.",
                fontSize = 11.sp,
                color = Color(0xFFB45309),
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
private fun PptxViewer(file: File) {
    var deck by remember(file.absolutePath) { mutableStateOf<OoxmlReaders.DeckView?>(null) }
    LaunchedEffect(file.absolutePath) {
        deck = withContext(Dispatchers.IO) {
            FileInputStream(file).use { OoxmlReaders.readPptx(it) }
        }
    }
    val d = deck
    if (d == null) {
        Text("Reading presentation…", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(d.slides) { slide ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F5F1))
                    .padding(14.dp)
            ) {
                Text(
                    "Slide ${slide.index}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFF0E7490)
                )
                Spacer(modifier = Modifier.height(8.dp))
                slide.lines.forEach { line ->
                    Text(
                        line,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = Color(0xFF162016),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
        if (d.truncated) {
            item {
                Text("Slides truncated for memory safety.", fontSize = 11.sp, color = Color(0xFFB45309))
            }
        }
        item {
            Text(
                "Read-only OOXML text extract — not PowerPoint-perfect layout.",
                fontSize = 10.sp,
                color = Color(0xFF8A918A)
            )
        }
    }
}
