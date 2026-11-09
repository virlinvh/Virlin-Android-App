package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NotePasteSource

class NotePasteSourceStripState(
    /** The source whose paste is being read; every chip is disabled meanwhile. */
    val busy: NotePasteSource?,
    /** The source used last on this page, marked so a repeat paste is one tap. */
    val last: NotePasteSource?,
    val onSource: (NotePasteSource) -> Unit,
    val onClose: () -> Unit,
)

/**
 * The "Paste from" strip under the formatting rail: one row of source names, nothing else. Copy
 * first, then tap where the copy came from; the clipboard is read the way that source formats copies.
 */
@Composable
internal fun NotePasteSourceStrip(state: NotePasteSourceStripState, modifier: Modifier = Modifier) {
    BackHandler { state.onClose() }
    val groups = NotePasteSource.entries.groupBy { it.group }.values.toList()
    Box(modifier.fillMaxWidth().height(52.dp).background(Color.White).testTag("notes_paste_source_strip")) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val listState = rememberLazyListState()
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("notes_paste_source_list"),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    groups.forEachIndexed { index, sources ->
                        if (index > 0) item(key = "divider_$index") {
                            Box(Modifier.padding(horizontal = 3.dp).width(1.dp).height(22.dp).background(NotesBorder))
                        }
                        items(sources, key = { it.name }) { source -> SourceChip(source, state) }
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    val feather = 22.dp.toPx()
                    val steps = 12
                    if (listState.canScrollBackward) for (i in 0 until steps) {
                        drawRect(Color.White.copy(alpha = 1f - i / steps.toFloat()), Offset(i * feather / steps, 0f),
                            Size(feather / steps + 1f, size.height))
                    }
                    if (listState.canScrollForward) for (i in 0 until steps) {
                        drawRect(Color.White.copy(alpha = 1f - i / steps.toFloat()),
                            Offset(size.width - (i + 1) * feather / steps, 0f), Size(feather / steps + 1f, size.height))
                    }
                }
            }
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = state.onClose)
                    .semantics { contentDescription = "Close paste from" }
                    .testTag("notes_paste_source_close"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = NotesMuted, modifier = Modifier.size(20.dp)) }
            Box(Modifier.width(6.dp))
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(NotesBorder))
    }
}

@Composable
private fun SourceChip(source: NotePasteSource, state: NotePasteSourceStripState) {
    val busy = state.busy == source
    val enabled = state.busy == null
    val last = state.last == source
    val pill = RoundedCornerShape(12.dp)
    Row(
        Modifier.height(36.dp).clip(pill)
            .background(if (last) NotesWash else Color.White)
            .border(1.dp, if (last) NotesAccent.copy(alpha = .45f) else NotesBorder, pill)
            .clickable(enabled = enabled, role = Role.Button) { state.onSource(source) }
            .semantics {
                contentDescription = "Paste from ${source.label}"
                selected = last
                if (!enabled) disabled()
            }
            .testTag("notes_paste_source_${source.name.lowercase()}")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = NotesAccent, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(source.label, color = if (enabled || busy) NotesInk else NotesMuted, fontSize = 13.sp,
            fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}
