package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF17221D)
private val Green = Color(0xFF087848)
private val Mint = Color(0xFFE2F5EA)
private val Border = Color(0xFFE3E9E5)
private val Muted = Color(0xFF66758A)

/** Orb size (52dp) + its 12dp bottom inset + a 13dp breathing gap. */
private val OrbClearance = 77.dp

/** Intents only. The host editor must implement each enabled action on the current selection. */
enum class NoteTool {
    UNDO, REDO, PARAGRAPH, FONT, SIZE, BOLD, ITALIC, UNDERLINE,
    STRIKE, TEXT_COLOR, HIGHLIGHT, ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT,
    BULLETS, NUMBERING, INDENT, OUTDENT, LINE_SPACING, LINK,
    QUOTE, PROMPT, CODE, CHECKLIST, TABLE, DIVIDER
}

data class NoteToolState(
    val paragraphLabel: String = "Normal text",
    val fontLabel: String = "Inter",
    val sizeLabel: String = "16",
    val active: Set<NoteTool> = emptySet(),
    val enabled: Set<NoteTool> = NoteTool.entries.toSet(),
)

/**
 * UI shell only. Render the live rich-text/block editor in [editor].
 * Keep this route in the existing app Scaffold so [footer] is the shared footer/Orb.
 * Do not serialize rich text into Task.notes as a JSON string.
 */
@Composable
fun VirlinNotesScreen(
    taskTitle: String,
    saveLabel: String,
    tools: NoteToolState,
    onClose: () -> Unit,
    onTool: (NoteTool) -> Unit,
    onAddBlock: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    editor: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    BackHandler { onClose() }
    Column(modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Close note", tint = Ink)
            }
            Icon(Icons.Outlined.Notes, null, tint = Green, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "$taskTitle · Note", Modifier.weight(1f), color = Ink,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Outlined.CheckCircle, null, tint = Green, modifier = Modifier.size(16.dp))
            Text(" $saveLabel", color = Green, fontSize = 12.sp, maxLines = 1)
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Note options", tint = Ink)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Preview") }, onClick = {
                        menuOpen = false; onPreview()
                    })
                    DropdownMenuItem(text = { Text("Export") }, onClick = {
                        menuOpen = false; onExport()
                    })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        NoteFormattingRail(tools, onTool)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        // The host editor owns cursor, selection, keyboard insets, scrolling and undo history.
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp)) {
            editor()
        }
        // The add-block control alone, lifted clear of the floating Orb.
        //
        // The Orb is an overlay owned by the app shell in the bottom-right of the content area
        // (52dp, 12dp above the bottom), so it sits on top of anything placed in this corner and
        // eats its taps. The bottom padding here clears the Orb's full height plus a gap, which
        // is why it is expressed from the Orb's own measurements rather than a screen position.
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 6.dp, bottom = OrbClearance),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                onClick = onAddBlock,
                shape = CircleShape, color = Mint,
                modifier = Modifier.size(48.dp).testTag("notes_add_block"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add block", tint = Green)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        footer()
    }
}

private data class ToolSpec(val tool: NoteTool, val icon: ImageVector? = null, val text: String? = null)

private val toolSpecs = listOf(
    ToolSpec(NoteTool.UNDO, Icons.AutoMirrored.Outlined.Undo),
    ToolSpec(NoteTool.REDO, Icons.AutoMirrored.Outlined.Redo),
    ToolSpec(NoteTool.PARAGRAPH), ToolSpec(NoteTool.FONT), ToolSpec(NoteTool.SIZE),
    ToolSpec(NoteTool.BOLD, Icons.Outlined.FormatBold),
    ToolSpec(NoteTool.ITALIC, Icons.Outlined.FormatItalic),
    ToolSpec(NoteTool.UNDERLINE, Icons.Outlined.FormatUnderlined),
    ToolSpec(NoteTool.STRIKE, Icons.Outlined.FormatStrikethrough),
    ToolSpec(NoteTool.TEXT_COLOR, Icons.Outlined.FormatColorText),
    ToolSpec(NoteTool.HIGHLIGHT, text = "◒"),
    ToolSpec(NoteTool.ALIGN_LEFT, Icons.Outlined.FormatAlignLeft),
    ToolSpec(NoteTool.ALIGN_CENTER, Icons.Outlined.FormatAlignCenter),
    ToolSpec(NoteTool.ALIGN_RIGHT, Icons.Outlined.FormatAlignRight),
    ToolSpec(NoteTool.BULLETS, Icons.AutoMirrored.Outlined.FormatListBulleted),
    ToolSpec(NoteTool.NUMBERING, Icons.Outlined.FormatListNumbered),
    ToolSpec(NoteTool.INDENT, text = "→|"), ToolSpec(NoteTool.OUTDENT, text = "|←"),
    ToolSpec(NoteTool.LINE_SPACING, text = "↕"),
    ToolSpec(NoteTool.LINK, Icons.Outlined.Link),
    ToolSpec(NoteTool.QUOTE, Icons.Outlined.FormatQuote),
    ToolSpec(NoteTool.PROMPT, text = "✦"),
    ToolSpec(NoteTool.CODE, Icons.Outlined.Code),
    ToolSpec(NoteTool.CHECKLIST, text = "☑"),
    ToolSpec(NoteTool.TABLE, text = "▦"), ToolSpec(NoteTool.DIVIDER, text = "—"),
)

/** Single horizontal rail. Content moves under small feathered edges; page never reflows. */
@Composable
fun NoteFormattingRail(
    state: NoteToolState,
    onTool: (NoteTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth().height(62.dp).background(Color.White)) {
        Row(
            Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            toolSpecs.forEach { spec ->
                val label = when (spec.tool) {
                    NoteTool.PARAGRAPH -> state.paragraphLabel
                    NoteTool.FONT -> state.fontLabel
                    NoteTool.SIZE -> state.sizeLabel
                    else -> spec.tool.name.lowercase().replace('_', ' ')
                }
                val enabled = spec.tool in state.enabled
                val selected = spec.tool in state.active
                val pill = RoundedCornerShape(12.dp)
                Row(
                    Modifier.height(40.dp)
                        .clip(pill)
                        .background(if (selected) Mint else Color.White)
                        .border(1.dp, if (selected) Color(0xFFB7DFC9) else Border, pill)
                        .clickable(enabled = enabled, role = Role.Button) { onTool(spec.tool) }
                        .semantics { contentDescription = label }
                        .testTag("notes_tool_${spec.tool.name.lowercase()}")
                        .padding(horizontal = if (spec.icon == null) 12.dp else 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (spec.icon != null) Icon(
                        spec.icon, contentDescription = null,
                        tint = if (enabled) (if (selected) Green else Ink) else Muted,
                        modifier = Modifier.size(20.dp),
                    ) else Text(
                        spec.text ?: label + if (spec.tool in setOf(NoteTool.PARAGRAPH, NoteTool.FONT, NoteTool.SIZE)) "  ▾" else "",
                        color = if (enabled) (if (selected) Green else Ink) else Muted,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
        // Edge paint is visual only; pointer events pass to the scrollable row.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val feather = 18.dp.toPx()
            val steps = 12
            if (scroll.value > 0) for (i in 0 until steps) {
                val alpha = (1f - i.toFloat() / steps) * .88f
                drawRect(Color.White.copy(alpha = alpha), Offset(i * feather / steps, 0f),
                    androidx.compose.ui.geometry.Size(feather / steps + 1f, size.height))
            }
            if (scroll.value < scroll.maxValue) for (i in 0 until steps) {
                val alpha = (1f - i.toFloat() / steps) * .88f
                drawRect(Color.White.copy(alpha = alpha),
                    Offset(size.width - (i + 1) * feather / steps, 0f),
                    androidx.compose.ui.geometry.Size(feather / steps + 1f, size.height))
            }
        }
    }
}
