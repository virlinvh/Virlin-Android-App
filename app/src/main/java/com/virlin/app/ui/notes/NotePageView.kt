package com.virlin.app.ui.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The desk the formatted pages lie on. */
internal val NotePageDesk = Color(0xFFE6EAE8)

/**
 * The note shown as formatted pages while editing. [pagination] says where pages break, from
 * the same layout that prints and exports; null while it is being worked out.
 */
class NotePageView(
    val settings: NotePrintSettings,
    val pagination: NotePagination?,
    val title: String?,
)

/** Paper points expressed in the page view's editor units (the editor's dp at paper scale). */
internal fun pagePt(points: Float) = (points / NotePrintRenderer.SP).dp

/**
 * One block on its page. The first block on a page opens the sheet (top margin, page number at
 * the top, the title on page one); the last closes it with the rest of the page and the bottom
 * margin. [content] is measured on its own, so the space filling a page never feeds back into it.
 */
@Composable
internal fun NotePageItem(
    view: NotePageView,
    page: Int,
    lastPage: Int,
    firstOnPage: Boolean,
    lastOnPage: Boolean,
    deskGap: androidx.compose.ui.unit.Dp,
    fill: androidx.compose.ui.unit.Dp,
    onTitleMeasured: (Int) -> Unit,
    onContentMeasured: (Int) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = view.settings
    val margin = pagePt(s.margins.points)
    val total = view.pagination?.pageCount ?: (lastPage + 1)
    val corner = pagePt(3f)
    Column(Modifier.fillMaxWidth()) {
        if (firstOnPage) {
            if (page > 0) Spacer(Modifier.height(deskGap))
            Box(Modifier.fillMaxWidth().height(margin)
                .clip(RoundedCornerShape(topStart = corner, topEnd = corner)).background(Color.White)) {
                if (s.pageNumbers == NotePageNumbers.TOP) PageNumber(s, page, total, margin)
            }
            val title = view.title?.trim().orEmpty()
            if (page == 0 && s.includeTitle && title.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = margin)
                    .onSizeChanged { onTitleMeasured(it.height) }) {
                    Text(title, color = NotesInk, fontSize = 27.sp, lineHeight = (27 * 1.17f * 1.1f).sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("notes_page_title"))
                    Spacer(Modifier.height(22.dp))
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = margin)
            .onSizeChanged { onContentMeasured(it.height) }, content = content)
        if (lastOnPage) {
            Spacer(Modifier.fillMaxWidth().height(fill).background(Color.White))
            Box(Modifier.fillMaxWidth().height(margin)
                .clip(RoundedCornerShape(bottomStart = corner, bottomEnd = corner)).background(Color.White)
                .testTag("notes_page_end_${lastPage + 1}")) {
                if (s.pageNumbers == NotePageNumbers.BOTTOM) PageNumber(s, lastPage, total, margin)
            }
        }
    }
}

/**
 * The auto-fit pages exactly as they print, read only: each page is drawn by the print renderer,
 * so a block split across pages shows here just as it comes out. [pages] is null while laid out.
 */
@Composable
internal fun NoteFlowPages(
    pages: List<NotePrintRenderer.Page>?,
    settings: NotePrintSettings,
    colors: NotePrintColors,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("notes_flow_pages")) {
        if (pages == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(22.dp), color = NotesAccent, strokeWidth = 2.dp)
            return@BoxWithConstraints
        }
        val gutter = 10.dp
        val widthPx = with(LocalDensity.current) { (maxWidth - gutter * 2).roundToPx() }.coerceAtLeast(1)
        // A block split across pages shares one text layout, which must not be drawn on two threads at once.
        val drawing = remember { Mutex() }
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 14.dp, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(pages) { index, page ->
                FlowPage(page, index, pages.size, settings, colors, widthPx, drawing, onTap)
            }
        }
    }
}

@Composable
private fun FlowPage(
    page: NotePrintRenderer.Page, index: Int, total: Int, settings: NotePrintSettings, colors: NotePrintColors,
    widthPx: Int, drawing: Mutex, onTap: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, page, index, total, settings, widthPx) {
        value = drawing.withLock {
            withContext(Dispatchers.Default) {
                runCatching { renderFlowPage(page, index, total, settings, colors, widthPx) }.getOrNull()
            }
        }
    }
    val corner = with(LocalDensity.current) { (3f * widthPx / settings.pageWidthPt).toDp() }
    Box(
        Modifier.fillMaxWidth().aspectRatio(settings.pageWidthPt / settings.pageHeightPt)
            .clip(RoundedCornerShape(corner)).background(Color.White)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            .semantics { contentDescription = "Page ${index + 1} of $total" }
            .testTag("notes_flow_page_${index + 1}"),
    ) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

private fun renderFlowPage(page: NotePrintRenderer.Page, index: Int, total: Int, settings: NotePrintSettings,
                           colors: NotePrintColors, widthPx: Int): ImageBitmap {
    val scale = widthPx / settings.pageWidthPt
    val bitmap = Bitmap.createBitmap(widthPx, (settings.pageHeightPt * scale).roundToInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.scale(scale, scale)
    page.ops.forEach { it(canvas) }
    NotePrintRenderer.drawPageNumber(canvas, index, total, settings, colors, settings.pageWidthPt, settings.pageHeightPt)
    return bitmap.asImageBitmap()
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PageNumber(
    s: NotePrintSettings, index: Int, total: Int, margin: androidx.compose.ui.unit.Dp,
) {
    val align = when (s.numberAlign) {
        NoteNumberAlign.LEFT -> Alignment.CenterStart
        NoteNumberAlign.CENTER -> Alignment.Center
        NoteNumberAlign.RIGHT -> Alignment.CenterEnd
    }
    Text("Page ${index + 1} of $total", color = NotesMuted, fontSize = (8.5f / NotePrintRenderer.SP).sp, maxLines = 1,
        modifier = Modifier.align(align).padding(horizontal = margin))
}
