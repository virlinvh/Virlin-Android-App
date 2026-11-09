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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/** Callbacks for the line-spacing strip shown under the formatting rail. */
class NoteLineSpacingStripState(
    /** The focused line's spacing multiplier, or null with nothing focused. */
    val current: Float?,
    val onPick: (Float) -> Unit,
    val onClose: () -> Unit,
)

/**
 * Line spacings as a horizontally scrolling strip, like the paragraph-style and colour strips.
 * Each tile previews its spacing as three short lines. Taps never take focus, so spacings can be
 * compared in turn.
 */
@Composable
internal fun NoteLineSpacingStrip(state: NoteLineSpacingStripState, modifier: Modifier = Modifier) {
    BackHandler { state.onClose() }
    val spacings = NotesViewModel.SPACINGS
    Box(
        modifier.fillMaxWidth().height(60.dp).background(Color.White).testTag("notes_spacing_strip"),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val listState = rememberLazyListState()
                LaunchedEffect(Unit) {
                    val at = spacings.indexOfFirst { it.first == state.current }
                    if (at > 0) listState.scrollToItem((at - 1).coerceAtLeast(0))
                }
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("notes_spacing_options"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(spacings.size, key = { spacings[it].second }) { index ->
                        val (value, label) = spacings[index]
                        SpacingTile(value, label, selected = state.current == value) { state.onPick(value) }
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
                    .semantics { contentDescription = "Close line spacing" }
                    .testTag("notes_spacing_close"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp)) }
            Box(Modifier.width(6.dp))
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Border))
    }
}

private val TileShape = RoundedCornerShape(10.dp)

@Composable
private fun SpacingTile(value: Float, label: String, selected: Boolean, onClick: () -> Unit) {
    val lines = if (selected) Green else Ink.copy(alpha = .55f)
    Row(
        Modifier.height(44.dp).clip(TileShape)
            .background(if (selected) Green.copy(alpha = .10f) else Color.White)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Green else Border, TileShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Line spacing $label"; this.selected = selected }
            .testTag("notes_spacing_$label")
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 18.dp, height = 26.dp)) {
            val bar = 2.5.dp.toPx()
            val step = bar + 3.dp.toPx() * value
            val total = bar * 3 + (step - bar) * 2
            var y = (size.height - total) / 2
            listOf(1f, .78f, 1f).forEach { width ->
                drawRoundRect(lines, Offset(0f, y), Size(size.width * width, bar), CornerRadius(bar / 2))
                y += step
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (selected) Green else Ink, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false)
    }
}
