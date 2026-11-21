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
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocBlockType

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/** Callbacks for the paragraph-style strip shown under the formatting rail. */
class NoteParagraphStyleStripState(
    /** The focused line's block type, or null with nothing focused. */
    val current: NoteDocBlockType?,
    val currentToggleHeading: Boolean,
    /** Normal text / headings; `toggle` picks their toggle variant (Toggle list, toggle heading). */
    val onPickStyle: (NoteDocBlockType, Boolean) -> Unit,
    /** Quote, Code, Prompt. */
    val onPickBlock: (NoteDocBlockType) -> Unit,
    val onClose: () -> Unit,
)

private data class StripStyle(
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
    StripStyle(NoteDocBlockType.PARAGRAPH, "Normal text", 14.sp),
    StripStyle(NoteDocBlockType.HEADING_1, "Heading 1", 18.sp, FontWeight.Bold),
    StripStyle(NoteDocBlockType.HEADING_2, "Heading 2", 16.sp, FontWeight.Bold),
    StripStyle(NoteDocBlockType.HEADING_3, "Heading 3", 15.sp, FontWeight.SemiBold),
    StripStyle(NoteDocBlockType.HEADING_4, "Heading 4", 14.sp, FontWeight.SemiBold),
    StripStyle(NoteDocBlockType.HEADING_5, "Heading 5", 13.sp, FontWeight.SemiBold),
)

private val blockStyles = listOf(
    StripStyle(NoteDocBlockType.QUOTE, "Quote", 14.sp, icon = Icons.Outlined.FormatQuote, italic = true),
    StripStyle(NoteDocBlockType.CODE, "Code", 13.sp, icon = Icons.Outlined.Code, mono = true),
    StripStyle(NoteDocBlockType.PROMPT, "Prompt", 14.sp, glyph = "✦"),
)

/** Items in strip order; dividers sit between the three groups. */
private sealed interface StripItem {
    data class Text(val style: StripStyle) : StripItem
    data class Toggle(val style: StripStyle) : StripItem
    data class Block(val style: StripStyle) : StripItem
    data class Divider(val key: String) : StripItem
}

private val stripItems: List<StripItem> =
    textStyles.map { StripItem.Text(it) } + StripItem.Divider("text") +
        textStyles.map { StripItem.Toggle(it) } + StripItem.Divider("toggle") +
        blockStyles.map { StripItem.Block(it) }

/**
 * Paragraph styles as a horizontally scrolling strip, like the colour and list strips: text
 * styles drawn in their own size and weight · their toggle variants · Quote, Code and Prompt,
 * with a thin rule between the groups. Taps never take focus, so styles can be tried in turn;
 * tapping the current style again turns the line back into Normal text, as before.
 */
@Composable
internal fun NoteParagraphStyleStrip(state: NoteParagraphStyleStripState, modifier: Modifier = Modifier) {
    BackHandler { state.onClose() }
    fun selected(item: StripItem): Boolean = when (item) {
        is StripItem.Text -> state.current == item.style.type && !state.currentToggleHeading
        is StripItem.Toggle -> if (item.style.type == NoteDocBlockType.PARAGRAPH)
            state.current == NoteDocBlockType.TOGGLE
            else state.current == item.style.type && state.currentToggleHeading
        is StripItem.Block -> state.current == item.style.type
        is StripItem.Divider -> false
    }
    Box(
        modifier.fillMaxWidth().height(60.dp).background(Color.White).testTag("notes_paragraph_strip"),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val listState = rememberLazyListState()
                LaunchedEffect(Unit) {
                    val at = stripItems.indexOfFirst(::selected)
                    if (at > 0) listState.scrollToItem((at - 1).coerceAtLeast(0))
                }
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("notes_paragraph_styles"),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(stripItems.size, key = { index ->
                        when (val item = stripItems[index]) {
                            is StripItem.Text -> "text_${item.style.type}"
                            is StripItem.Toggle -> "toggle_${item.style.type}"
                            is StripItem.Block -> "block_${item.style.type}"
                            is StripItem.Divider -> "divider_${item.key}"
                        }
                    }) { index ->
                        when (val item = stripItems[index]) {
                            is StripItem.Divider -> Box(
                                Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).background(Border),
                            )
                            is StripItem.Text -> StyleTile(item.style, selected(item),
                                label = item.style.label, spoken = item.style.label,
                                tag = "notes_pick_${item.style.label}") { state.onPickStyle(item.style.type, false) }
                            is StripItem.Toggle -> {
                                val name = if (item.style.type == NoteDocBlockType.PARAGRAPH) "Toggle list"
                                    else "Toggle ${item.style.label.lowercase()}"
                                StyleTile(item.style, selected(item),
                                    label = if (item.style.type == NoteDocBlockType.PARAGRAPH) "Toggle list"
                                        else "H${item.style.type.headingLevel}",
                                    spoken = name, tag = "notes_pick_toggle_${item.style.label}", toggle = true,
                                ) { state.onPickStyle(item.style.type, true) }
                            }
                            is StripItem.Block -> StyleTile(item.style, selected(item),
                                label = item.style.label, spoken = item.style.label,
                                tag = "notes_pick_${item.style.label}") { state.onPickBlock(item.style.type) }
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
                    .semantics { contentDescription = "Close paragraph styles" }
                    .testTag("notes_paragraph_close"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp)) }
            Box(Modifier.width(6.dp))
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Border))
    }
}

private val TileShape = RoundedCornerShape(10.dp)

@Composable
private fun StyleTile(
    style: StripStyle,
    selected: Boolean,
    label: String,
    spoken: String,
    tag: String,
    toggle: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (selected) Green else Ink
    Row(
        Modifier.height(44.dp).clip(TileShape)
            .background(if (selected) Green.copy(alpha = .10f) else Color.White)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Green else Border, TileShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken; this.selected = selected }
            .testTag(tag)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            toggle -> {
                NoteToggleArrow(collapsed = true, textSizeSp = 14, lineHeightSp = 18f, color = Green,
                    modifier = Modifier.padding(end = 2.dp))
            }
            style.icon != null -> {
                Icon(style.icon, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            style.glyph != null -> {
                Text(style.glyph, color = Green, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
            }
        }
        Text(
            label,
            color = tint,
            fontSize = style.size,
            lineHeight = style.size * 1.15f,
            fontWeight = style.weight,
            fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
            fontFamily = if (style.mono) FontFamily.Monospace else null,
            maxLines = 1,
            softWrap = false,
        )
    }
}
