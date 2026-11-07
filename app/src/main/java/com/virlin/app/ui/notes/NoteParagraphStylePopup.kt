package com.virlin.app.ui.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.virlin.app.domain.notedoc.NoteDocBlockType

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder

/** Window bounds of the toolbar's paragraph-style chip; written on layout, read when the popup opens. */
internal class NoteChipAnchor { var bounds: IntRect? = null }

private data class StyleOption(
    val type: NoteDocBlockType,
    val label: String,
    val size: TextUnit,
    val weight: FontWeight = FontWeight.Normal,
    val icon: ImageVector? = null,
    val glyph: String? = null,
    val italic: Boolean = false,
    val mono: Boolean = false,
)

private val textStyles = listOf(
    StyleOption(NoteDocBlockType.PARAGRAPH, "Normal text", 15.sp),
    StyleOption(NoteDocBlockType.HEADING_1, "Heading 1", 22.sp, FontWeight.Bold),
    StyleOption(NoteDocBlockType.HEADING_2, "Heading 2", 19.sp, FontWeight.Bold),
    StyleOption(NoteDocBlockType.HEADING_3, "Heading 3", 17.sp, FontWeight.SemiBold),
    StyleOption(NoteDocBlockType.HEADING_4, "Heading 4", 15.sp, FontWeight.SemiBold),
    StyleOption(NoteDocBlockType.HEADING_5, "Heading 5", 13.sp, FontWeight.SemiBold),
)

private val blockStyles = listOf(
    StyleOption(NoteDocBlockType.QUOTE, "Quote", 14.sp, icon = Icons.Outlined.FormatQuote, italic = true),
    StyleOption(NoteDocBlockType.CODE, "Code", 13.sp, icon = Icons.Outlined.Code, mono = true),
    StyleOption(NoteDocBlockType.PROMPT, "Prompt", 14.sp, glyph = "✦"),
)

/**
 * Compact paragraph-style menu that drops down from the toolbar chip. Each row is drawn in its
 * own style so the choice is visible before it is applied. Falls back to the top-start of the
 * page when the chip has not been laid out.
 */
@Composable
internal fun NoteParagraphStylePopup(
    anchor: IntRect?,
    current: NoteDocBlockType?,
    onPick: (NoteDocBlockType) -> Unit,
    onDismiss: () -> Unit,
    currentToggleHeading: Boolean = false,
    /** When set, text-style rows pick through here and show a ▸ for their toggle variant. */
    onPickStyle: ((NoteDocBlockType, Boolean) -> Unit)? = null,
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
                Column(Modifier.width(if (onPickStyle != null) 224.dp else 196.dp).padding(vertical = 6.dp)
                    .testTag("notes_dialog")) {
                    textStyles.forEach { option ->
                        if (onPickStyle == null) StyleRow(option, option.type == current) { onPick(option.type) }
                        else {
                            val toggleSelected = if (option.type == NoteDocBlockType.PARAGRAPH)
                                current == NoteDocBlockType.TOGGLE
                            else current == option.type && currentToggleHeading
                            val plainSelected = current == option.type && !currentToggleHeading
                            StyleRow(option, plainSelected, toggle = {
                                ToggleVariantButton(option, toggleSelected) { onPickStyle(option.type, true) }
                            }) { onPickStyle(option.type, false) }
                        }
                    }
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 5.dp).fillMaxWidth().height(1.dp)
                        .background(Border))
                    blockStyles.forEach { StyleRow(it, it.type == current) { onPick(it.type) } }
                }
            }
        }
    }
}

@Composable
private fun ToggleVariantButton(option: StyleOption, selected: Boolean, onClick: () -> Unit) {
    val label = if (option.type == NoteDocBlockType.PARAGRAPH) "Toggle list" else "Toggle ${option.label.lowercase()}"
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(9.dp))
            .background(if (selected) Green else Mint)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected }
            .testTag("notes_pick_toggle_${option.label}"),
        contentAlignment = Alignment.Center,
    ) {
        NoteToggleArrow(collapsed = true, textSizeSp = 20, lineHeightSp = 20f,
            color = if (selected) Color.White else Green)
    }
}

@Composable
private fun StyleRow(
    option: StyleOption,
    selected: Boolean,
    toggle: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) = Row(
    Modifier.padding(horizontal = 6.dp).fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
) {
    StyleRowBody(option, selected, Modifier.weight(1f), onClick)
    if (toggle != null) { Spacer(Modifier.width(4.dp)); toggle(); Spacer(Modifier.width(2.dp)) }
}

@Composable
private fun StyleRowBody(option: StyleOption, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Mint else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = option.label; this.selected = selected }
            .testTag("notes_pick_${option.label}")
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (option.icon != null || option.glyph != null) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (option.icon != null) Icon(option.icon, contentDescription = null, tint = Green,
                    modifier = Modifier.size(18.dp))
                else Text(option.glyph.orEmpty(), color = Green, fontSize = 14.sp)
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(
            option.label,
            Modifier.weight(1f),
            color = if (selected) Green else Ink,
            fontSize = option.size,
            lineHeight = option.size * 1.2f,
            fontWeight = option.weight,
            fontStyle = if (option.italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = if (option.mono) FontFamily.Monospace else null,
            maxLines = 1,
        )
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Green,
            modifier = Modifier.size(16.dp))
    }
}
