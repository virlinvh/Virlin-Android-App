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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/** Intents only. The host editor must implement each enabled action on the current selection. */
enum class NoteTool {
    UNDO, REDO, PARAGRAPH, FONT, SIZE, BOLD, ITALIC, UNDERLINE,
    STRIKE, TEXT_COLOR, HIGHLIGHT, ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT,
    BULLETS, NUMBERING, INDENT, OUTDENT, LINE_SPACING, LINK,
    QUOTE, PROMPT, CODE, FLASHCARD, SELECT_DOCUMENT, HEADING_FOLD, CHECKLIST, TABLE, DIVIDER, IMAGE, TOGGLE,
    DELETE, CHANGE_CASE, WORD_COUNT, YOUTUBE, AUDIO, TEXT_TO_AUDIO, ARRANGE_BLOCKS, RENAME, DUPLICATE, PRINT, EXPORT, FORMATTING,
    IMPORT, PASTE_FROM
}

/** Callbacks for the inline text-size stepper that replaces the size chip. */
class NoteSizeControl(
    val onStep: (Int) -> Unit,
    val onEditStart: () -> Unit,
    val onSet: (Int) -> Unit,
)

data class NoteToolState(
    val paragraphLabel: String = "Normal text",
    val fontLabel: String = "Inter",
    val sizeLabel: String = "16",
    val spacingLabel: String = "Single",
    val duplicateLabel: String = "Duplicate note",
    val active: Set<NoteTool> = emptySet(),
    val enabled: Set<NoteTool> = NoteTool.entries.toSet(),
)

/**
 * UI shell only. Render the live rich-text/block editor in [editor].
 * Keep this route in the existing app Scaffold so [footer] is the shared footer.
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
    headingLevels: List<Int>,
    onHeadingFoldLevel: (Int?) -> Unit,
    headingFoldRequest: Int = 0,
    documentSelectionMode: Boolean,
    editor: @Composable (Boolean, Boolean) -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onParagraphChipPlaced: (IntRect) -> Unit = {},
    sizeControl: NoteSizeControl? = null,
    tableEntry: NoteTableEntry? = null,
    colorStrip: NoteColorStripState? = null,
    onSpacingChipPlaced: (IntRect) -> Unit = {},
    listStrip: NoteListStyleStripState? = null,
    paragraphStrip: NoteParagraphStyleStripState? = null,
    spacingStrip: NoteLineSpacingStripState? = null,
    tableToolsStrip: NoteTableToolsStripState? = null,
    imageToolsStrip: NoteImageToolsStripState? = null,
    videoEntry: NoteVideoEntryState? = null,
    audioRecorder: NoteAudioRecorderState? = null,
    renameStrip: NoteRenameStripState? = null,
    printStrip: NotePrintStripState? = null,
    exportStrip: NoteExportStripState? = null,
    formattingStrip: NoteFormattingStripState? = null,
    pasteSourceStrip: NotePasteSourceStripState? = null,
    /** The editor shows the note as formatted pages on a desk, edge to edge. */
    pageView: Boolean = false,
) {
    // -1: ordinary editing, 0: controls visible, 1+: levels folded deepest-first.
    var headingFoldStep by rememberSaveable { mutableStateOf(-1) }
    androidx.compose.runtime.LaunchedEffect(headingFoldRequest) {
        if (headingFoldRequest > 0) {
            when {
                headingFoldStep < 0 -> headingFoldStep = 0
                headingFoldStep < headingLevels.size -> {
                    onHeadingFoldLevel(headingLevels[headingFoldStep]); headingFoldStep++
                }
                else -> { onHeadingFoldLevel(null); headingFoldStep = -1 }
            }
        }
    }
    val headingFoldMode = headingFoldStep >= 0
    BackHandler { onClose() }
    Column(modifier.fillMaxSize().background(NotesPaper)) {
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
            IconButton(onClick = onPreview, modifier = Modifier.size(40.dp).testTag("notes_preview_button")) {
                Icon(Icons.Outlined.Visibility, contentDescription = "Read note preview", tint = Green)
            }
            Row(
                Modifier.padding(start = 2.dp, end = 6.dp)
                    .semantics(mergeDescendants = true) {}
                    .testTag("notes_save_status"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CheckCircle, null, tint = Green, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(3.dp))
                Text(saveLabel, color = Green, fontSize = 11.sp, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        val railTools = tools.copy(active = if (headingFoldMode) tools.active + NoteTool.HEADING_FOLD else tools.active)
        val railTool: (NoteTool) -> Unit = { tool ->
            if (tool == NoteTool.HEADING_FOLD) {
                when {
                    headingFoldStep < 0 -> headingFoldStep = 0
                    headingFoldStep < headingLevels.size -> {
                        onHeadingFoldLevel(headingLevels[headingFoldStep])
                        headingFoldStep++
                    }
                    else -> {
                        onHeadingFoldLevel(null)
                        headingFoldStep = -1
                    }
                }
            } else onTool(tool)
        }
        val headingFoldLabel = when {
            headingFoldStep < 0 -> "Show heading controls"
            headingFoldStep < headingLevels.size ->
                "Fold level ${headingLevels[headingFoldStep]} headings"
            else -> "Expand all headings"
        }
        NoteFormattingRail(
            railTools,
            railTool,
            headingFoldLabel = headingFoldLabel,
            onParagraphChipPlaced = onParagraphChipPlaced,
            sizeControl = sizeControl,
            onSpacingChipPlaced = onSpacingChipPlaced,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        formattingStrip?.let { NoteFormattingStrip(it) }
        colorStrip?.let { NoteColorStrip(it) }
        listStrip?.let { NoteListStyleStrip(it) }
        paragraphStrip?.let { NoteParagraphStyleStrip(it) }
        spacingStrip?.let { NoteLineSpacingStrip(it) }
        tableEntry?.let { NoteTableEntryBar(it) }
        tableToolsStrip?.let { NoteTableToolsStrip(it) }
        imageToolsStrip?.let { NoteImageToolsStrip(it) }
        videoEntry?.let { NoteVideoEntryPanel(it) }
        audioRecorder?.let { NoteAudioRecorderPanel(it) }
        renameStrip?.let { NoteRenameStrip(it) }
        printStrip?.let { NotePrintStrip(it) }
        exportStrip?.let { NoteExportStrip(it) }
        pasteSourceStrip?.let { NotePasteSourceStrip(it) }
        // Let the editor use the entire area above the footer. The add control floats over its
        // bottom corner; the editor supplies end-of-document scroll clearance for the last line.
        // No top padding here: the document's opening space scrolls with it, so text reaches the
        // rail's line when scrolled ([NoteDocumentTopSpace]).
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Box(if (pageView) Modifier.fillMaxSize().background(NotePageDesk)
                else Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp)) {
                editor(headingFoldMode, documentSelectionMode)
            }
            NoteAddFan(
                tools = railTools,
                headingFoldLabel = headingFoldLabel,
                onTool = railTool,
                onAddBlock = onAddBlock,
                modifier = Modifier.matchParentSize(),
                // Stays out until the tap that expands every heading again ends the cycle.
                keepOpenAfter = { tool -> tool == NoteTool.HEADING_FOLD && headingFoldStep < headingLevels.size },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        footer()
    }
}

/** Space between the rail and the first line of the note, part of the scrolling document. */
internal val NoteDocumentTopSpace = 28.dp

private data class ToolSpec(
    val tool: NoteTool,
    val icon: ImageVector? = null,
    val text: String? = null,
    /** First tool of a group; a thin divider is drawn before it. */
    val groupStart: Boolean = false,
)

/** Grouped left to right: edit · text style · format · colour · layout · lists · insert · document. */
private val toolSpecs = listOf(
    ToolSpec(NoteTool.UNDO, Icons.AutoMirrored.Outlined.Undo),
    ToolSpec(NoteTool.REDO, Icons.AutoMirrored.Outlined.Redo),
    ToolSpec(NoteTool.DELETE, Icons.Outlined.Delete),
    ToolSpec(NoteTool.PASTE_FROM, Icons.Outlined.ContentPaste),
    ToolSpec(NoteTool.PARAGRAPH, groupStart = true), ToolSpec(NoteTool.FONT), ToolSpec(NoteTool.SIZE),
    ToolSpec(NoteTool.BOLD, Icons.Outlined.FormatBold, groupStart = true),
    ToolSpec(NoteTool.ITALIC, Icons.Outlined.FormatItalic),
    ToolSpec(NoteTool.UNDERLINE, Icons.Outlined.FormatUnderlined),
    ToolSpec(NoteTool.STRIKE, Icons.Outlined.FormatStrikethrough),
    ToolSpec(NoteTool.CHANGE_CASE, text = "Aa"),
    ToolSpec(NoteTool.TEXT_COLOR, Icons.Outlined.FormatColorText, groupStart = true),
    ToolSpec(NoteTool.HIGHLIGHT, text = "◒"),
    ToolSpec(NoteTool.ALIGN_LEFT, Icons.Outlined.FormatAlignLeft, groupStart = true),
    ToolSpec(NoteTool.ALIGN_CENTER, Icons.Outlined.FormatAlignCenter),
    ToolSpec(NoteTool.ALIGN_RIGHT, Icons.Outlined.FormatAlignRight),
    ToolSpec(NoteTool.LINE_SPACING, Icons.Outlined.FormatLineSpacing),
    ToolSpec(NoteTool.BULLETS, Icons.AutoMirrored.Outlined.FormatListBulleted, groupStart = true),
    ToolSpec(NoteTool.NUMBERING, Icons.Outlined.FormatListNumbered),
    ToolSpec(NoteTool.CHECKLIST, text = "☑"),
    ToolSpec(NoteTool.INDENT, text = "→|"), ToolSpec(NoteTool.OUTDENT, text = "|←"),
    ToolSpec(NoteTool.LINK, Icons.Outlined.Link, groupStart = true),
    ToolSpec(NoteTool.QUOTE, Icons.Outlined.FormatQuote),
    ToolSpec(NoteTool.PROMPT, text = "✦"),
    ToolSpec(NoteTool.CODE, Icons.Outlined.Code),
    ToolSpec(NoteTool.TABLE, text = "▦"), ToolSpec(NoteTool.DIVIDER, text = "—"),
    ToolSpec(NoteTool.IMAGE, Icons.Outlined.Image),
    ToolSpec(NoteTool.YOUTUBE, NoteYouTubeGlyph),
    ToolSpec(NoteTool.AUDIO, Icons.Outlined.Mic),
    ToolSpec(NoteTool.TEXT_TO_AUDIO, Icons.Outlined.RecordVoiceOver),
    ToolSpec(NoteTool.TOGGLE, text = "▸"),
    ToolSpec(NoteTool.RENAME, Icons.Outlined.DriveFileRenameOutline, groupStart = true),
    ToolSpec(NoteTool.DUPLICATE, Icons.Outlined.ContentCopy),
    ToolSpec(NoteTool.IMPORT, Icons.Outlined.UploadFile),
    ToolSpec(NoteTool.FORMATTING, Icons.Outlined.Article),
    ToolSpec(NoteTool.PRINT, Icons.Outlined.Print),
    ToolSpec(NoteTool.EXPORT, Icons.Outlined.IosShare),
    ToolSpec(NoteTool.WORD_COUNT, Icons.Outlined.Numbers),
    ToolSpec(NoteTool.ARRANGE_BLOCKS, Icons.Outlined.DragIndicator),
    ToolSpec(NoteTool.SELECT_DOCUMENT, Icons.Outlined.SelectAll),
    ToolSpec(NoteTool.HEADING_FOLD, Icons.Outlined.AccountTree),
    ToolSpec(NoteTool.FLASHCARD, Icons.Outlined.Style),
)

/** Single horizontal rail. Content moves under small feathered edges; page never reflows. */
@Composable
fun NoteFormattingRail(
    state: NoteToolState,
    onTool: (NoteTool) -> Unit,
    modifier: Modifier = Modifier,
    headingFoldLabel: String = "Heading folding",
    onParagraphChipPlaced: (IntRect) -> Unit = {},
    sizeControl: NoteSizeControl? = null,
    onSpacingChipPlaced: (IntRect) -> Unit = {},
) {
    val scroll = rememberScrollState()
    Box(modifier.fillMaxWidth().height(62.dp).background(Color.White)) {
        Row(
            Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            toolSpecs.forEach { spec ->
                if (spec.groupStart) Box(
                    Modifier.padding(horizontal = 3.dp).width(1.dp).height(24.dp).background(Border)
                        .testTag("notes_tool_group_${spec.tool.name.lowercase()}"),
                )
                val label = when (spec.tool) {
                    NoteTool.PARAGRAPH -> state.paragraphLabel
                    NoteTool.FONT -> state.fontLabel
                    NoteTool.SIZE -> state.sizeLabel
                    NoteTool.HEADING_FOLD -> headingFoldLabel
                    NoteTool.SELECT_DOCUMENT -> "Select across blocks"
                    NoteTool.DELETE -> "Delete selection"
                    NoteTool.LINE_SPACING -> "Line spacing ${state.spacingLabel}"
                    NoteTool.CHANGE_CASE -> "Change case: all caps, title case, lowercase"
                    NoteTool.WORD_COUNT -> "Word count"
                    NoteTool.YOUTUBE -> "Embed YouTube video"
                    NoteTool.AUDIO -> "Record audio"
                    NoteTool.TEXT_TO_AUDIO -> "Text to audio"
                    NoteTool.ARRANGE_BLOCKS -> "Arrange blocks"
                    NoteTool.RENAME -> "Rename note"
                    NoteTool.DUPLICATE -> state.duplicateLabel
                    NoteTool.PRINT -> "Print or save as PDF"
                    NoteTool.EXPORT -> "Export as PDF, Word, Markdown or image"
                    NoteTool.FORMATTING -> "Page formatting"
                    NoteTool.IMPORT -> "Import Word or Google Docs"
                    NoteTool.PASTE_FROM -> "Paste from ChatGPT, Gemini, Google Docs, Word, PDF and more"
                    else -> spec.tool.name.lowercase().replace('_', ' ')
                }
                val enabled = spec.tool in state.enabled
                val selected = spec.tool in state.active
                val pill = RoundedCornerShape(12.dp)
                val sizeValue = state.sizeLabel.toIntOrNull()
                if (spec.tool == NoteTool.SIZE && sizeControl != null && sizeValue != null) NoteSizeStepper(
                    value = sizeValue, enabled = enabled,
                    onStep = sizeControl.onStep, onEditStart = sizeControl.onEditStart, onSet = sizeControl.onSet,
                    tag = "notes_tool_size",
                ) else Row(
                    Modifier.height(40.dp)
                        .clip(pill)
                        .background(if (selected) Mint else Color.White)
                        .border(if (selected) 2.dp else 1.dp, if (selected) Green.copy(alpha = .85f) else Border, pill)
                        .clickable(enabled = enabled, role = Role.Button) { onTool(spec.tool) }
                        .semantics { contentDescription = label; this.selected = selected }
                        .testTag("notes_tool_${spec.tool.name.lowercase()}")
                        .then(if (spec.tool == NoteTool.PARAGRAPH || spec.tool == NoteTool.LINE_SPACING)
                            Modifier.onGloballyPositioned { coords ->
                                val r = coords.boundsInWindow()
                                val rect = IntRect(r.left.roundToInt(), r.top.roundToInt(),
                                    r.right.roundToInt(), r.bottom.roundToInt())
                                if (spec.tool == NoteTool.PARAGRAPH) onParagraphChipPlaced(rect)
                                else onSpacingChipPlaced(rect)
                            } else Modifier)
                        .padding(horizontal = when {
                            // The arrow's own box is 28dp wide; 5dp each side matches a 20dp icon chip.
                            spec.tool == NoteTool.TOGGLE -> 5.dp
                            spec.icon == null || spec.tool == NoteTool.LINE_SPACING -> 12.dp
                            else -> 9.dp
                        }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (spec.tool == NoteTool.LINE_SPACING) {
                        val tint = if (enabled) (if (selected) Green else Ink) else Muted
                        Icon(Icons.Outlined.FormatLineSpacing, contentDescription = null, tint = tint,
                            modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("${state.spacingLabel}  ▾", color = tint, fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    } else if (spec.tool == NoteTool.TOGGLE) NoteToggleArrow(
                        collapsed = true, textSizeSp = 16, lineHeightSp = 20f,
                        color = if (enabled) (if (selected) Green else Ink) else Muted,
                    ) else if (spec.icon != null) Icon(
                        spec.icon, contentDescription = null,
                        tint = if (enabled) (if (selected) Green else Ink) else Muted,
                        modifier = Modifier.size(20.dp),
                    ) else Text(
                        spec.text ?: (if (spec.tool == NoteTool.FONT && label.length > 14)
                            label.take(13).trimEnd() + "…" else label) +
                            if (spec.tool in setOf(NoteTool.PARAGRAPH, NoteTool.FONT, NoteTool.SIZE)) "  ▾" else "",
                        color = if (enabled) (if (selected) Green else Ink) else Muted,
                        fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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
