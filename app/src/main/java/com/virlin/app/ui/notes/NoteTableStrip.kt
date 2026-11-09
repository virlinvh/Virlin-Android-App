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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.FormatColorReset
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocTable

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/** Text size of table cells in the editor; the size stepper starts from it. */
internal const val TABLE_CELL_TEXT_SP = 14
/** Width an automatic column is treated as when the width stepper first moves it. */
internal const val TABLE_DEFAULT_COL_WIDTH = 120
internal const val TABLE_COL_WIDTH_STEP = 24

/** Calm cell shadings that keep dark text readable. */
internal val TABLE_SHADES = listOf(
    0xFFFFF4C2L to "Yellow", 0xFFE3F2E1L to "Green", 0xFFE3EEFBL to "Blue",
    0xFFFCE4ECL to "Pink", 0xFFEDE7F6L to "Lavender", 0xFFF1F1EFL to "Grey",
)

/** The focused cell's table, as the table tools strip needs it. */
data class NoteTableToolState(
    val rows: Int,
    val cols: Int,
    val row: Int,
    val col: Int,
    val headerRow: Boolean,
    val headerColumn: Boolean,
    val gridLines: Boolean,
    /** The focused column's width in dp, null when automatic. */
    val columnWidth: Int?,
    val fill: Long?,
)

class NoteTableToolsStripState(
    val tools: NoteTableToolState,
    val onInsertRow: (below: Boolean) -> Unit,
    val onInsertColumn: (right: Boolean) -> Unit,
    val onDeleteRow: () -> Unit,
    val onDeleteColumn: () -> Unit,
    val onDeleteTable: () -> Unit,
    val onHeaderRow: () -> Unit,
    val onHeaderColumn: () -> Unit,
    val onGridLines: () -> Unit,
    val onWidthStep: (Int) -> Unit,
    val onFitColumns: () -> Unit,
    val onShade: (Long?) -> Unit,
    val onClose: () -> Unit,
)

/**
 * Table tools under the formatting rail while a cell holds the caret, grouped like the table
 * menus of Docs, Word and Notion: insert · delete · header and grid lines · column width ·
 * cell shading. Taps never take focus, so the caret stays in the cell and tools can be used in a
 * row; everything acts on the focused cell's row, column or table.
 */
@Composable
internal fun NoteTableToolsStrip(state: NoteTableToolsStripState, modifier: Modifier = Modifier) {
    BackHandler { state.onClose() }
    val t = state.tools
    NoteToolStripShell("notes_table_tools", "Close table tools", "notes_table_tools_close", state.onClose, modifier) {
                    val canAddRow = t.rows < NoteDocTable.MAX_ROWS
                    val canAddCol = t.cols < NoteDocTable.MAX_COLS
                    item { ToolTile(Icons.Outlined.TableRows, "Row above", "Insert row above", "row_above", canAddRow) { state.onInsertRow(false) } }
                    item { ToolTile(Icons.Outlined.TableRows, "Row below", "Insert row below", "row_below", canAddRow) { state.onInsertRow(true) } }
                    item { ToolTile(Icons.Outlined.ViewColumn, "Col left", "Insert column left", "col_left", canAddCol) { state.onInsertColumn(false) } }
                    item { ToolTile(Icons.Outlined.ViewColumn, "Col right", "Insert column right", "col_right", canAddCol) { state.onInsertColumn(true) } }
                    item { GroupDivider() }
                    item { ToolTile(Icons.Outlined.DeleteOutline, "Del row", "Delete row", "delete_row", t.rows > 1, onClick = state.onDeleteRow) }
                    item { ToolTile(Icons.Outlined.DeleteOutline, "Del col", "Delete column", "delete_col", t.cols > 1, onClick = state.onDeleteColumn) }
                    item { ToolTile(Icons.Outlined.DeleteOutline, "Del table", "Delete table", "delete_table", onClick = state.onDeleteTable) }
                    item { GroupDivider() }
                    item { ToolTile(Icons.Outlined.TableRows, "Header row", "Header row", "header_row", selected = t.headerRow, onClick = state.onHeaderRow) }
                    item { ToolTile(Icons.Outlined.ViewColumn, "Header col", "Header column", "header_col", selected = t.headerColumn, onClick = state.onHeaderColumn) }
                    item {
                        ToolTile(if (t.gridLines) Icons.Outlined.GridOn else Icons.Outlined.GridOff,
                            if (t.gridLines) "Lines on" else "Lines off", "Grid lines", "grid", selected = t.gridLines,
                            onClick = state.onGridLines)
                    }
                    item { GroupDivider() }
                    item { WidthStepper(t.columnWidth, state.onWidthStep) }
                    item { ToolTile(Icons.Outlined.FitScreen, "Even cols", "Reset every column to automatic width", "fit_cols", onClick = state.onFitColumns) }
                    item { GroupDivider() }
                    item { ShadeSwatch(null, "No shading", t.fill == null) { state.onShade(null) } }
                    TABLE_SHADES.forEach { (argb, name) ->
                        item { ShadeSwatch(argb, "$name shading", t.fill == argb) { state.onShade(argb) } }
                    }
    }
}

/**
 * A 60dp white strip under the formatting rail: a sideways list of tools with feathered edges,
 * a close button and a bottom hairline, shared by the table and image strips.
 */
@Composable
internal fun NoteToolStripShell(
    tag: String,
    closeDescription: String,
    closeTag: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.White,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth().height(60.dp).background(background).testTag(tag)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val listState = rememberLazyListState()
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
                val paper = background
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
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeDescription }
                    .testTag(closeTag),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp)) }
            Box(Modifier.width(6.dp))
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Border))
    }
}

private val TileShape = RoundedCornerShape(10.dp)

@Composable
internal fun ToolTile(
    icon: ImageVector,
    label: String,
    description: String,
    tag: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    tagPrefix: String = "notes_table_tool_",
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(58.dp).height(46.dp).clip(TileShape)
            .background(if (selected) Green.copy(alpha = .10f) else Color.White)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Green else Border, TileShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
                if (!enabled) disabled()
            }
            .testTag("$tagPrefix$tag")
            .alpha(if (enabled) 1f else .38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Green else Ink, modifier = Modifier.size(18.dp))
        Text(label, color = if (selected) Green else Ink, fontSize = 9.5.sp, lineHeight = 11.sp,
            fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun WidthStepper(width: Int?, onStep: (Int) -> Unit) {
    Row(
        Modifier.height(46.dp).clip(TileShape).border(1.dp, Border, TileShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", "Narrower column", "width_down") { onStep(-1) }
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("WIDTH", color = Muted, fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = .6.sp, maxLines = 1)
            Text(width?.toString() ?: "Auto", color = Ink, fontSize = 12.sp, lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.semantics { contentDescription = "Column width ${width?.let { "$it" } ?: "automatic"}" })
        }
        StepButton("+", "Wider column", "width_up") { onStep(1) }
    }
}

@Composable
private fun StepButton(glyph: String, description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.width(34.dp).fillMaxHeight()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("notes_table_tool_$tag"),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Green, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ShadeSwatch(argb: Long?, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .background(argb?.let { Color(it) } ?: Color.White)
            .border(if (selected) 2.dp else 1.dp, if (selected) Green else Border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected }
            .testTag("notes_table_shade_${argb?.toString(16) ?: "none"}"),
        contentAlignment = Alignment.Center,
    ) {
        if (argb == null) Icon(Icons.Outlined.FormatColorReset, contentDescription = null, tint = Muted,
            modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun GroupDivider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(30.dp).background(Border))
}
