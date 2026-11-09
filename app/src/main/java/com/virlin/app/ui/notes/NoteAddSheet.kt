package com.virlin.app.ui.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatAlignCenter
import androidx.compose.material.icons.outlined.FormatAlignLeft
import androidx.compose.material.icons.outlined.FormatAlignRight
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocBlockType

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/** One insertable block in the sheet. [preview] draws the small sample on the right. */
private data class AddBlockSpec(
    val label: String,
    val subtitle: String,
    val icon: ImageVector? = null,
    val glyph: String? = null,
    val type: NoteDocBlockType? = null,
    val preview: @Composable () -> Unit,
)

/** One toolbar action mirrored in the sheet's tool grid. */
private data class AddToolSpec(val tool: NoteTool, val label: String, val icon: ImageVector? = null, val glyph: String? = null)

private val formatTools = listOf(
    AddToolSpec(NoteTool.UNDO, "Undo", Icons.AutoMirrored.Outlined.Undo),
    AddToolSpec(NoteTool.REDO, "Redo", Icons.AutoMirrored.Outlined.Redo),
    AddToolSpec(NoteTool.DELETE, "Delete", Icons.Outlined.Delete),
    AddToolSpec(NoteTool.PASTE_FROM, "Paste from", Icons.Outlined.ContentPaste),
    AddToolSpec(NoteTool.PARAGRAPH, "Text style", Icons.Outlined.TextFields),
    AddToolSpec(NoteTool.FONT, "Font", Icons.Outlined.FontDownload),
    AddToolSpec(NoteTool.SIZE, "Text size", Icons.Outlined.FormatSize),
    AddToolSpec(NoteTool.BOLD, "Bold", Icons.Outlined.FormatBold),
    AddToolSpec(NoteTool.ITALIC, "Italic", Icons.Outlined.FormatItalic),
    AddToolSpec(NoteTool.UNDERLINE, "Underline", Icons.Outlined.FormatUnderlined),
    AddToolSpec(NoteTool.STRIKE, "Strike", Icons.Outlined.FormatStrikethrough),
    AddToolSpec(NoteTool.CHANGE_CASE, "Change case", glyph = "Aa"),
    AddToolSpec(NoteTool.CODE, "Inline code", Icons.Outlined.DataObject),
    AddToolSpec(NoteTool.TEXT_COLOR, "Text colour", Icons.Outlined.FormatColorText),
    AddToolSpec(NoteTool.HIGHLIGHT, "Highlight", Icons.Outlined.BorderColor),
    AddToolSpec(NoteTool.ALIGN_LEFT, "Align left", Icons.Outlined.FormatAlignLeft),
    AddToolSpec(NoteTool.ALIGN_CENTER, "Centre", Icons.Outlined.FormatAlignCenter),
    AddToolSpec(NoteTool.ALIGN_RIGHT, "Align right", Icons.Outlined.FormatAlignRight),
    AddToolSpec(NoteTool.LINE_SPACING, "Spacing", Icons.Outlined.FormatLineSpacing),
    AddToolSpec(NoteTool.INDENT, "Indent", Icons.AutoMirrored.Outlined.FormatIndentIncrease),
    AddToolSpec(NoteTool.OUTDENT, "Outdent", Icons.AutoMirrored.Outlined.FormatIndentDecrease),
    AddToolSpec(NoteTool.LINK, "Link", Icons.Outlined.Link),
    AddToolSpec(NoteTool.FLASHCARD, "Flashcard", Icons.Outlined.Style),
    AddToolSpec(NoteTool.SELECT_DOCUMENT, "Select across", Icons.Outlined.SelectAll),
    AddToolSpec(NoteTool.HEADING_FOLD, "Fold headings", Icons.Outlined.AccountTree),
    AddToolSpec(NoteTool.WORD_COUNT, "Word count", Icons.Outlined.Numbers),
    AddToolSpec(NoteTool.ARRANGE_BLOCKS, "Arrange blocks", Icons.Outlined.DragIndicator),
    AddToolSpec(NoteTool.RENAME, "Rename", Icons.Outlined.DriveFileRenameOutline),
    AddToolSpec(NoteTool.DUPLICATE, "Duplicate", Icons.Outlined.ContentCopy),
    AddToolSpec(NoteTool.IMPORT, "Import doc", Icons.Outlined.UploadFile),
    AddToolSpec(NoteTool.PRINT, "Print / PDF", Icons.Outlined.Print),
    AddToolSpec(NoteTool.FORMATTING, "Formatting", Icons.Outlined.Article),
    AddToolSpec(NoteTool.EXPORT, "Export", Icons.Outlined.IosShare),
)

/**
 * The note's "+" sheet. Rich and text blocks insert after the focused block exactly as the
 * previous picker did; every remaining toolbar action is mirrored in the tool grid and runs
 * through the same [onTool] handler as the toolbar, honouring its enabled and active state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteAddSheet(
    tools: NoteToolState,
    onDismiss: () -> Unit,
    onInsert: (NoteDocBlockType) -> Unit,
    onInsertEquation: () -> Unit,
    onPickImage: () -> Unit,
    onTool: (NoteTool) -> Unit,
    sizeControl: NoteSizeControl? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val richBlocks = listOf(
        AddBlockSpec("Quote", "Highlight a key idea or excerpt", Icons.Outlined.FormatQuote,
            type = NoteDocBlockType.QUOTE) { QuotePreview() },
        AddBlockSpec("Prompt callout", "Draw attention with a prompt or key note", glyph = "✦",
            type = NoteDocBlockType.PROMPT) { PromptPreview() },
        AddBlockSpec("Code", "Add a block of code with monospace formatting", Icons.Outlined.Code,
            type = NoteDocBlockType.CODE) { CodePreview() },
        AddBlockSpec("Equation", "Insert a mathematical expression", glyph = "f(x)") { EquationPreview() },
        AddBlockSpec("Toggle", "Add a collapsible section", Icons.Outlined.KeyboardArrowDown,
            type = NoteDocBlockType.TOGGLE) { TogglePreview() },
        AddBlockSpec("Divider", "Insert a visual separator", Icons.Outlined.HorizontalRule,
            type = NoteDocBlockType.DIVIDER) { DividerPreview() },
        AddBlockSpec("Table", "Add a table to organize information", Icons.Outlined.TableChart,
            type = NoteDocBlockType.TABLE) { TablePreview() },
        AddBlockSpec("Image", "Insert an image from your device or gallery", Icons.Outlined.Image) { ImagePreview() },
    )
    val textBlocks = listOf(
        AddBlockSpec("Paragraph", "Plain body text", glyph = "¶",
            type = NoteDocBlockType.PARAGRAPH) { TextSample("Body text", 12.sp, FontWeight.Normal) },
        AddBlockSpec("Heading 1", "Large section heading", glyph = "H1",
            type = NoteDocBlockType.HEADING_1) { TextSample("Heading", 17.sp, FontWeight.Bold) },
        AddBlockSpec("Heading 2", "Medium section heading", glyph = "H2",
            type = NoteDocBlockType.HEADING_2) { TextSample("Heading", 15.sp, FontWeight.SemiBold) },
        AddBlockSpec("Heading 3", "Small section heading", glyph = "H3",
            type = NoteDocBlockType.HEADING_3) { TextSample("Heading", 13.sp, FontWeight.SemiBold) },
        AddBlockSpec("Heading 4", "Minor heading", glyph = "H4",
            type = NoteDocBlockType.HEADING_4) { TextSample("Heading", 12.sp, FontWeight.SemiBold) },
        AddBlockSpec("Heading 5", "Smallest heading", glyph = "H5",
            type = NoteDocBlockType.HEADING_5) { TextSample("Heading", 11.sp, FontWeight.SemiBold) },
        AddBlockSpec("Bulleted list", "A simple list of points", Icons.AutoMirrored.Outlined.FormatListBulleted,
            type = NoteDocBlockType.BULLET) { ListSample(listOf("•", "•")) },
        AddBlockSpec("Numbered list", "Steps in order", Icons.Outlined.FormatListNumbered,
            type = NoteDocBlockType.NUMBERED) { ListSample(listOf("1.", "2.")) },
        AddBlockSpec("Checklist", "Items you can tick off", Icons.Outlined.CheckBox,
            type = NoteDocBlockType.CHECKLIST) { ListSample(listOf("☐", "☑")) },
    )
    val choose: (AddBlockSpec) -> Unit = { spec ->
        when {
            spec.label == "Image" -> onPickImage()
            spec.label == "Equation" -> onInsertEquation()
            spec.type != null -> onInsert(spec.type)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).padding(bottom = 28.dp)
                .testTag("notes_dialog"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add to note", Modifier.weight(1f), color = Ink,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp).testTag("notes_add_close")) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Ink)
                }
            }
            SectionTitle(Icons.Outlined.Layers, "Rich blocks")
            richBlocks.forEach { spec -> AddBlockRow(spec) { choose(spec) } }
            SectionTitle(Icons.Outlined.TextFields, "Text")
            textBlocks.forEach { spec -> AddBlockRow(spec) { choose(spec) } }
            SectionTitle(Icons.Outlined.Tune, "Format & tools")
            Text("Applies to the block or text you were editing.", color = Muted, fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp))
            formatTools.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { spec ->
                        val size = tools.sizeLabel.toIntOrNull()
                        if (spec.tool == NoteTool.SIZE && sizeControl != null && size != null) SizeTile(
                            size, enabled = spec.tool in tools.enabled, control = sizeControl,
                            modifier = Modifier.weight(1f),
                        ) else ToolTile(spec, enabled = spec.tool in tools.enabled, active = spec.tool in tools.active,
                            modifier = Modifier.weight(1f)) { onTool(spec.tool) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    Row(Modifier.padding(top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Green, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AddBlockRow(spec: AddBlockSpec, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).heightIn(min = 68.dp).clip(shape)
            .background(Color.White).border(1.dp, Border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spec.label }
            .testTag("notes_pick_${spec.label}")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Mint),
            contentAlignment = Alignment.Center) {
            if (spec.icon != null) Icon(spec.icon, contentDescription = null, tint = Green, modifier = Modifier.size(21.dp))
            else Text(spec.glyph.orEmpty(), color = Green, fontSize = if ((spec.glyph?.length ?: 0) > 2) 12.sp else 15.sp,
                fontWeight = FontWeight.SemiBold, fontStyle = if (spec.glyph == "f(x)") FontStyle.Italic else FontStyle.Normal,
                fontFamily = if (spec.glyph == "f(x)") FontFamily.Serif else FontFamily.Default)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(spec.label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(spec.subtitle, color = Muted, fontSize = 12.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(104.dp).height(50.dp).clip(RoundedCornerShape(10.dp))
            .background(Mint.copy(alpha = .45f)).padding(horizontal = 9.dp, vertical = 7.dp),
            contentAlignment = Alignment.CenterStart) { spec.preview() }
    }
}

@Composable
private fun ToolTile(spec: AddToolSpec, enabled: Boolean, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val tint = when {
        !enabled -> Muted.copy(alpha = .55f)
        active -> Green
        else -> Ink
    }
    Column(
        modifier.height(70.dp).clip(shape)
            .background(if (active && enabled) Mint else Color.White)
            .border(1.dp, if (active && enabled) Green.copy(alpha = .38f) else Border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spec.label }
            .testTag("notes_add_tool_${spec.tool.name.lowercase()}")
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (spec.icon != null) Icon(spec.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        else Text(spec.glyph.orEmpty(), color = tint, fontSize = 16.sp)
        Spacer(Modifier.height(5.dp))
        Text(spec.label, color = tint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Text size adjusts in place here too, so the sheet stays open while the size changes behind it. */
@Composable
private fun SizeTile(size: Int, enabled: Boolean, control: NoteSizeControl, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.height(70.dp).clip(shape).background(Color.White).border(1.dp, Border, shape)
            .testTag("notes_add_tool_size"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NoteSizeStepper(size, enabled, control.onStep, control.onEditStart, control.onSet,
            framed = false, tag = "notes_add_size")
        Text("Text size", color = if (enabled) Ink else Muted.copy(alpha = .55f), fontSize = 11.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- previews

@Composable
private fun QuotePreview() {
    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Green))
        Spacer(Modifier.width(7.dp))
        Text("A concise,\nnotable quote.", color = Ink, fontSize = 11.sp, lineHeight = 13.sp,
            fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif)
    }
}

@Composable
private fun PromptPreview() {
    Column {
        Text("✦ Prompt", color = Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        PreviewLine(1f)
        Spacer(Modifier.height(4.dp))
        PreviewLine(.65f)
    }
}

@Composable
private fun CodePreview() {
    Text("def virlin():\n  return \"note\"", color = Ink, fontSize = 9.5.sp, lineHeight = 13.sp,
        fontFamily = FontFamily.Monospace, maxLines = 2)
}

@Composable
private fun EquationPreview() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("x² + y² = r²", color = Ink, fontSize = 14.sp, fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif)
    }
}

@Composable
private fun TogglePreview() {
    Column {
        Text("⌄ Details", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Row { Spacer(Modifier.width(10.dp)); PreviewLine(.85f) }
        Spacer(Modifier.height(4.dp))
        Row { Spacer(Modifier.width(10.dp)); PreviewLine(.55f) }
    }
}

@Composable
private fun DividerPreview() {
    Box(Modifier.fillMaxWidth().height(1.5.dp).background(Muted.copy(alpha = .55f)))
}

@Composable
private fun TablePreview() {
    val line = Muted.copy(alpha = .45f)
    val header = Green.copy(alpha = .22f)
    Canvas(Modifier.fillMaxSize()) {
        val rows = 3
        val cols = 3
        val rowH = size.height / rows
        drawRect(header, Offset.Zero, Size(size.width, rowH))
        for (r in 0..rows) drawLine(line, Offset(0f, r * rowH), Offset(size.width, r * rowH), 1.dp.toPx())
        for (c in 0..cols) {
            val x = c * size.width / cols
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
        }
    }
}

@Composable
private fun ImagePreview() {
    val hill = Green.copy(alpha = .35f)
    val hillBack = Green.copy(alpha = .2f)
    val sun = Green.copy(alpha = .3f)
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(sun, radius = size.height * .13f, center = Offset(size.width * .78f, size.height * .28f))
        drawPath(Path().apply {
            moveTo(size.width * .25f, size.height)
            lineTo(size.width * .62f, size.height * .38f)
            lineTo(size.width, size.height)
            close()
        }, hillBack)
        drawPath(Path().apply {
            moveTo(0f, size.height)
            lineTo(size.width * .32f, size.height * .3f)
            lineTo(size.width * .68f, size.height)
            close()
        }, hill)
    }
}

@Composable
private fun TextSample(text: String, size: androidx.compose.ui.unit.TextUnit, weight: FontWeight) {
    Text(text, color = Ink, fontSize = size, fontWeight = weight, maxLines = 1)
}

@Composable
private fun ListSample(markers: List<String>) {
    Column {
        markers.forEachIndexed { index, marker ->
            if (index > 0) Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(marker, color = Green, fontSize = 10.sp, modifier = Modifier.width(14.dp))
                PreviewLine(if (index == 0) .9f else .6f)
            }
        }
    }
}

@Composable
private fun PreviewLine(fraction: Float) {
    Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp))
        .background(Muted.copy(alpha = .3f)))
}
