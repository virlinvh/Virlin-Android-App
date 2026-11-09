package com.virlin.app.ui.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/**
 * Line-spacing menu that drops down from the toolbar's spacing chip, matching the paragraph-style
 * menu. Each row previews its spacing as three short lines. Falls back to the top-start of the
 * page when the chip has not been laid out.
 */
@Composable
internal fun NoteLineSpacingPopup(
    anchor: IntRect?,
    current: Float?,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    val margin = with(density) { 8.dp.roundToPx() }
    val fallbackTop = with(density) { 128.dp.roundToPx() }
    val position = remember(anchor) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                                           layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val left = anchor?.left ?: margin
                val below = anchor?.bottom?.plus(gap) ?: fallbackTop
                val x = left.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                val y = if (below + popupContentSize.height <= windowSize.height - margin) below
                    else (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
                return IntOffset(x, y)
            }
        }
    }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(shown,
            enter = fadeIn(tween(120)) + scaleIn(tween(140), initialScale = .92f,
                transformOrigin = TransformOrigin(0f, 0f))) {
            Surface(shape = RoundedCornerShape(14.dp), color = Color.White, shadowElevation = 10.dp,
                border = BorderStroke(1.dp, Border)) {
                Column(Modifier.width(196.dp).padding(vertical = 6.dp).testTag("notes_dialog")) {
                    Text("LINE SPACING", Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                        color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    NotesViewModel.SPACINGS.forEach { (value, label) ->
                        SpacingRow(value, label, selected = current == value) { onPick(value) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpacingRow(value: Float, label: String, selected: Boolean, onClick: () -> Unit) {
    val lines = if (selected) Green else Ink.copy(alpha = .55f)
    Row(
        Modifier.padding(horizontal = 6.dp).fillMaxWidth().heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Mint else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Line spacing $label"; this.selected = selected }
            .testTag("notes_spacing_$label")
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(width = 22.dp, height = 26.dp)) {
            val bar = 2.5.dp.toPx()
            val step = bar + 3.dp.toPx() * value
            val total = bar * 3 + (step - bar) * 2
            var y = (size.height - total) / 2
            listOf(1f, .78f, 1f).forEach { width ->
                drawRoundRect(lines, Offset(0f, y), Size(size.width * width, bar), CornerRadius(bar / 2))
                y += step
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), color = if (selected) Green else Ink, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Green,
            modifier = Modifier.size(16.dp))
    }
}
