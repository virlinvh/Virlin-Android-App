package com.virlin.app.ui.notes

import androidx.compose.foundation.background
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
