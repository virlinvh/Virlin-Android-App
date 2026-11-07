package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteListStyles

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/** Callbacks for the bullet / numbering style strip shown under the formatting rail. */
class NoteListStyleStripState(
    /** [NoteDocBlockType.BULLET] or [NoteDocBlockType.NUMBERED]. */
    val type: NoteDocBlockType,
    /** The focused line's effective style key, or null when it is not this kind of list. */
    val current: String?,
    val onPick: (String) -> Unit,
    val onNone: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * A horizontally scrolling strip of list-style previews: each tile shows three short lines with
 * the markers they would get. Tile taps never take focus, so the caret stays in the list and
 * several styles can be tried in a row. "None" turns the line back into Normal text.
 */
@Composable
internal fun NoteListStyleStrip(state: NoteListStyleStripState, modifier: Modifier = Modifier) {
    BackHandler { state.onClose() }
    val kind = if (state.type == NoteDocBlockType.NUMBERED) "numbers" else "bullets"
    val styles = NoteListStyles.stylesFor(state.type)
    Box(
        modifier.fillMaxWidth().height(60.dp).background(Color.White)
            .testTag("notes_list_strip_$kind"),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val listState = rememberLazyListState()
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("notes_list_styles_$kind"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item(key = "none") { NoneTile(onClick = state.onNone) }
                    items(styles, key = { it.key }) { style ->
                        StyleTile(state.type, style, selected = style.key == state.current) {
                            state.onPick(style.key)
                        }
                    }
                }
                val paper = Color.White
                Canvas(Modifier.fillMaxSize()) {
                    val feather = 22.dp.toPx()
                    val steps = 12
                    if (listState.canScrollBackward) for (i in 0 until steps) {
                        drawRect(paper.copy(alpha = 1f - i / steps.toFloat()), Offset(i * feather / steps, 0f),
                            Size(feather / steps + 1f, size.height))
                    }
                    if (listState.canScrollForward) for (i in 0 until steps) {
                        drawRect(paper.copy(alpha = 1f - i / steps.toFloat()),
                            Offset(size.width - (i + 1) * feather / steps, 0f), Size(feather / steps + 1f, size.height))
                    }
                }
            }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = state.onClose)
                    .semantics { contentDescription = if (kind == "numbers") "Close numbering styles" else "Close bullet styles" }
                    .testTag("notes_list_close_$kind"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp)) }
            Box(Modifier.width(6.dp))
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Border))
    }
}

private val TileShape = RoundedCornerShape(10.dp)

@Composable
private fun StyleTile(type: NoteDocBlockType, style: NoteListStyles.Style, selected: Boolean, onClick: () -> Unit) {
    val numbered = type == NoteDocBlockType.NUMBERED
    Column(
        Modifier.width(if (numbered) 64.dp else 52.dp).height(46.dp).clip(TileShape)
            .background(if (selected) Green.copy(alpha = .10f) else Color.White)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Green else Border, TileShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (numbered) "Numbering ${style.label}" else "${style.label} bullets"
                this.selected = selected
            }
            .testTag("notes_list_style_${style.key}")
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (n in 1..3) PreviewLine(NoteListStyles.marker(type, style.key, n), numbered, selected)
    }
}

@Composable
private fun PreviewLine(marker: String, numbered: Boolean, selected: Boolean) {
    val lineColor = if (selected) Green.copy(alpha = .45f) else Ink.copy(alpha = .16f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            marker,
            color = if (selected) Green else Ink,
            fontSize = if (numbered) 9.sp else 10.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(if (numbered) 20.dp else 10.dp),
        )
        Canvas(Modifier.padding(start = 4.dp).weight(1f).height(3.dp)) {
            drawLine(lineColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                strokeWidth = size.height, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun NoneTile(onClick: () -> Unit) {
    Box(
        Modifier.width(52.dp).height(46.dp).clip(TileShape)
            .border(1.dp, Border, TileShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "No list" }
            .testTag("notes_list_style_none"),
        contentAlignment = Alignment.Center,
    ) {
        Text("None", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
