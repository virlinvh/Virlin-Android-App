package com.virlin.app.ui.map

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.virlin.app.ui.notes.NoteColorPalettes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.virlin.app.domain.structure.HierarchyNode
import com.virlin.app.domain.structure.NodeKind
import kotlinx.coroutines.delay

const val MapStructureBarTag = "map_structure_bar"
const val MapMoveSheetTag = "map_move_sheet"
const val MapExportStripTag = "map_export_strip"
const val MapConfirmDialogTag = "map_confirm_dialog"
const val MapConfirmCancelTag = "map_confirm_cancel"
const val MapConfirmOkTag = "map_confirm_ok"
const val MapBoxColorHexTag = "map_box_color_hex"
const val MapBoxColorHexFieldTag = "map_box_color_hex_field"
const val MapBoxColorHexApplyTag = "map_box_color_hex_apply"
fun mapDestinationTag(id: String) = "map_destination_$id"

// The map surface's palette. Package-visible so every map overlay - the structure bar, the
// selected-task toolbar - reads the same three values instead of repeating the literals.
internal val MapInk = Color(0xFF17202C)
internal val MapMuted = Color(0xFF647087)
internal val MapGreen = Color(0xFF16A34A)
internal val MapHairline = Color(0xFFE1E8E2)

/** What the host is currently doing with a selected branch. */
enum class StructureIntent { MOVE, COPY }

/**
 * An icon-only overlay for the selected node. Its 44dp touch targets and accessibility labels
 * remain present even though the visible border, selected title, and helper copy are gone.
 */
@Composable
fun MapStructureBar(
    selected: MapTopic?,
    /** The selected node plus its descendants. */
    affectedCount: Int,
    clipboard: MapTopic?,
    organizing: Boolean,
    /**
     * True while the floating selected-task toolbar is on screen. It owns Open Task,
     * while Move remains available here in Organize mode.
     */
    taskToolbarPresent: Boolean = false,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onAddBranch: () -> Unit = {},
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDuplicate: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    resizing: Boolean = false,
    onToggleResize: () -> Unit = {},
    onDelete: () -> Unit = {},
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    colorPickerOpen: Boolean = false,
    selectedBoxColor: Long? = null,
    onToggleColorPicker: () -> Unit = {},
    onChooseBoxColor: (Long) -> Unit = {},
    onClearBoxColor: () -> Unit = {},
    /** Sets a colour without toggling it off, for colour codes and custom swatches. */
    onSetBoxColor: (Long) -> Unit = onChooseBoxColor,
    selectedBoxHidden: Boolean = false,
    hasHiddenBoxes: Boolean = false,
    showHidden: Boolean = false,
    onToggleHideBox: () -> Unit = {},
    onToggleShowHidden: () -> Unit = {},
    /** Map-wide export formats. When present, Organize shows an Export tool that opens them. */
    exportOptions: List<MapExportOption> = emptyList(),
) {
    if (selected == null && !organizing) return
    var exportOpen by remember { mutableStateOf(false) }
    LaunchedEffect(organizing) { if (!organizing) exportOpen = false }
    // One extra strip at a time: opening the colours closes Export, and the reverse below.
    LaunchedEffect(colorPickerOpen, selected?.id) {
        if (colorPickerOpen && selected != null) exportOpen = false
    }
    val openLabel = when (selected?.kind) {
        MapKind.TASK -> "Open Task"
        MapKind.WORKSTREAM -> "Open Workstream"
        else -> "Open Project"
    }
    Column(Modifier.fillMaxWidth().testTag(MapStructureBarTag), horizontalAlignment = Alignment.End) {
        if (organizing) {
            // Grouped like the notes rail: shaping the branch, copying it, its look, history,
            // export, delete. Box tools wait for a selected box; map-wide tools always work.
            val boxSelected = selected != null
            val groups = listOf(
                listOf(
                    MapToolbarAction(Icons.Default.Edit, "Edit", onEdit, enabled = boxSelected),
                    MapToolbarAction(Icons.Default.DriveFileMove, "Into", onMove,
                        enabled = boxSelected && selected?.kind != MapKind.PROJECT),
                    MapToolbarAction(Icons.Default.AccountTree, "Add branch", onAddBranch, enabled = boxSelected),
                ),
                buildList {
                    if (selected?.kind == MapKind.TASK || selected == null) {
                        add(MapToolbarAction(Icons.Default.ContentCopy, "Copy", onCopy, enabled = boxSelected))
                        add(MapToolbarAction(Icons.Default.FileCopy, "Duplicate", onDuplicate, enabled = boxSelected))
                    } else if (!taskToolbarPresent) {
                        add(MapToolbarAction(Icons.Default.OpenInNew, openLabel, onOpen))
                    }
                    if (clipboard != null) add(MapToolbarAction(Icons.Default.ContentPaste, "Paste into", onPaste,
                        enabled = boxSelected))
                },
                listOf(
                    MapToolbarAction(Icons.Default.SwapHoriz, "Resize horizontally", onToggleResize,
                        boxSelected && resizing, enabled = boxSelected),
                    MapToolbarAction(Icons.Default.Palette, "Box color", onToggleColorPicker,
                        selected = boxSelected && selectedBoxColor != null, enabled = boxSelected),
                    MapToolbarAction(Icons.Default.VisibilityOff, "Hide box", onToggleHideBox,
                        selected = selectedBoxHidden, enabled = boxSelected && selected?.kind != MapKind.PROJECT),
                    MapToolbarAction(Icons.Default.Visibility, "Show hidden", onToggleShowHidden,
                        selected = showHidden, enabled = hasHiddenBoxes),
                ),
                listOf(
                    MapToolbarAction(Icons.Default.Undo, "Undo", onUndo, enabled = canUndo),
                    MapToolbarAction(Icons.Default.Redo, "Redo", onRedo, enabled = canRedo),
                ),
                if (exportOptions.isEmpty()) emptyList()
                else listOf(MapToolbarAction(Icons.Default.IosShare, "Export", {
                    val opening = !exportOpen
                    if (opening && colorPickerOpen && selected != null) onToggleColorPicker()
                    exportOpen = opening
                }, selected = exportOpen)),
                listOf(MapToolbarAction(Icons.Outlined.DeleteOutline, "Delete selected box", onDelete,
                    destructive = true, enabled = boxSelected)),
            ).filter { it.isNotEmpty() }
            MapOrganizeStrip(selected?.id.orEmpty(), groups, onClear.takeIf { boxSelected })
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (!taskToolbarPresent) MapIconAction(Icons.Default.OpenInNew, openLabel, false, false, true, 0L, onOpen)
                MapIconAction(Icons.Default.Close, "Clear selection", false, false, true, 0L, onClear)
            }
        }
        if (organizing && colorPickerOpen && selected != null) {
            MapBoxColorPalette(selectedBoxColor, onChooseBoxColor, onClearBoxColor, onSetBoxColor)
        }
        AnimatedVisibility(
            visible = organizing && exportOpen && exportOptions.isNotEmpty(),
            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
        ) {
            MapExportStrip(exportOptions, onClose = { exportOpen = false })
        }
    }
}

/** One export format offered from the Organize strip. */
class MapExportOption(val tag: String, val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Map-wide export formats in a feathered, horizontally scrolling strip under Organize. */
@Composable
private fun MapExportStrip(options: List<MapExportOption>, onClose: () -> Unit) {
    BackHandler { onClose() }
    val palette = com.virlin.app.ui.hierarchy.LocalProjectPalette.current
    val accent = palette?.let { Color(it.secondary) } ?: MapGreen
    Box(Modifier.fillMaxWidth().background(Color.White).testTag(MapExportStripTag)) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(50)).background(accent.copy(alpha = .12f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.IosShare, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            com.virlin.app.ui.notes.NoteFeatheredRow(Modifier.weight(1f).height(52.dp), peek = true) {
                options.forEachIndexed { index, option ->
                    if (index > 0) Spacer(Modifier.width(8.dp))
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.height(38.dp).clip(shape)
                            .background(Color.White)
                            .border(1.dp, MapHairline, shape)
                            .clickable(role = Role.Button) { onClose(); option.onClick() }
                            .semantics { contentDescription = "Export as ${option.label}" }
                            .testTag(option.tag)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(option.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(option.label, color = MapInk, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Close export" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, contentDescription = null, tint = MapMuted, modifier = Modifier.size(20.dp)) }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(MapHairline))
    }
}

private const val MAX_RECENT_BOX_COLORS = 8

private fun loadRecentBoxColors(context: Context): List<Long> =
    context.getSharedPreferences("map_box_colors", Context.MODE_PRIVATE)
        .getString("recent", "").orEmpty()
        .split(',').mapNotNull { it.toLongOrNull() }.take(MAX_RECENT_BOX_COLORS)

private fun saveRecentBoxColors(context: Context, values: List<Long>) {
    context.getSharedPreferences("map_box_colors", Context.MODE_PRIVATE).edit()
        .putString("recent", values.joinToString(",")).apply()
}

/**
 * The box highlight swatches. The pinned "#" switches the strip to a colour-code field; codes
 * applied there are kept at the front as custom swatches for reuse.
 */
@Composable
private fun MapBoxColorPalette(
    selectedBoxColor: Long?,
    onChooseBoxColor: (Long) -> Unit,
    onClearBoxColor: () -> Unit,
    onSetBoxColor: (Long) -> Unit,
) {
    val context = LocalContext.current
    val recents = remember { mutableStateListOf<Long>().apply { addAll(loadRecentBoxColors(context)) } }
    var hexMode by remember { mutableStateOf(false) }
    BackHandler(enabled = hexMode) { hexMode = false }
    val custom = (listOfNotNull(selectedBoxColor) + recents).distinct()
        .filter { it !in boxHighlightColors }
    val paletteScroll = rememberScrollState()
    val surface = MaterialTheme.colorScheme.surface
    val featherPx = with(LocalDensity.current) { 36.dp.toPx() }
    Box(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp).height(52.dp)
            .testTag("map_box_color_palette")
            .clip(RoundedCornerShape(16.dp))
            .background(surface)
            .border(1.dp, MapHairline, RoundedCornerShape(16.dp)),
    ) {
        if (hexMode) MapBoxHexEntry(
            onBack = { hexMode = false },
            onApply = { argb ->
                recents.remove(argb); recents.add(0, argb)
                while (recents.size > MAX_RECENT_BOX_COLORS) recents.removeAt(recents.lastIndex)
                saveRecentBoxColors(context, recents.toList())
                onSetBoxColor(argb)
                hexMode = false
            },
        ) else Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(52.dp)) {
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(paletteScroll)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp)
                            .testTag("map_box_color_none")
                            .semantics {
                                contentDescription = "No box color"
                                this.selected = selectedBoxColor == null
                            }
                            .clickable(role = Role.Button, onClick = onClearBoxColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.FormatColorReset, contentDescription = null,
                            tint = MapMuted, modifier = Modifier.size(24.dp))
                    }
                    custom.forEach { color ->
                        val hex = NoteColorPalettes.hexOf(color)
                        BoxColorSwatch(color, selectedBoxColor == color, "Box color $hex",
                            "map_box_color_custom_${hex.removePrefix("#")}") { onSetBoxColor(color) }
                    }
                    boxHighlightColors.forEachIndexed { index, color ->
                        BoxColorSwatch(color, selectedBoxColor == color, "Box color ${index + 1}",
                            "map_box_color_$index") { onChooseBoxColor(color) }
                    }
                }
                // Stationary feathered edges make swatches emerge and disappear as the row moves.
                // These are decorative overlays; horizontal gestures still reach the row below.
                if (paletteScroll.value > 0) {
                    Box(Modifier.align(Alignment.CenterStart).width(36.dp).height(52.dp)
                        .graphicsLayer { alpha = (paletteScroll.value / featherPx).coerceIn(0f, 1f) }
                        .background(Brush.horizontalGradient(
                            0f to surface, .35f to surface.copy(alpha = .76f),
                            1f to surface.copy(alpha = 0f))))
                }
                if (paletteScroll.value < paletteScroll.maxValue) {
                    Box(Modifier.align(Alignment.CenterEnd).width(36.dp).height(52.dp)
                        .graphicsLayer { alpha = ((paletteScroll.maxValue - paletteScroll.value) / featherPx).coerceIn(0f, 1f) }
                        .background(Brush.horizontalGradient(
                            0f to surface.copy(alpha = 0f), .65f to surface.copy(alpha = .76f),
                            1f to surface)))
                }
            }
            Box(Modifier.width(1.dp).height(24.dp).background(MapHairline))
            Box(
                Modifier.padding(horizontal = 4.dp).size(40.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { hexMode = true }
                    .semantics { contentDescription = "Type a colour code" }
                    .testTag(MapBoxColorHexTag),
                contentAlignment = Alignment.Center,
            ) { Text("#", color = MapInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun BoxColorSwatch(color: Long, active: Boolean, label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp)
            .testTag(tag)
            .semantics {
                contentDescription = label
                this.selected = active
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(if (active) 30.dp else 26.dp)
                .background(Color(color.toInt()), RoundedCornerShape(50))
                .then(if (active) Modifier.border(2.dp,
                    MaterialTheme.colorScheme.onSurface,
                    RoundedCornerShape(50)) else Modifier),
        )
    }
}

@Composable
private fun MapBoxHexEntry(onBack: () -> Unit, onApply: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = NoteColorPalettes.parseHex(text)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val apply = { parsed?.let(onApply); Unit }
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back to colours" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MapMuted, modifier = Modifier.size(20.dp)) }
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.weight(1f).height(40.dp).clip(shape)
                .border(1.dp, if (text.isNotEmpty() && parsed == null) Color(0xFFD32F2F) else MapGreen.copy(alpha = .6f), shape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#", color = MapMuted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = text,
                onValueChange = { next ->
                    text = next.trim().removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                },
                singleLine = true,
                textStyle = TextStyle(color = MapInk, fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
                cursorBrush = SolidColor(MapGreen),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                    autoCorrect = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                modifier = Modifier.weight(1f).padding(start = 4.dp).focusRequester(focus)
                    .semantics { contentDescription = "Colour code" }
                    .testTag(MapBoxColorHexFieldTag),
            )
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(50))
                    .background(parsed?.let { Color(it.toInt()) } ?: Color.White)
                    .border(1.dp, MapInk.copy(alpha = .18f), RoundedCornerShape(50)),
            )
        }
        Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (parsed != null) MapGreen else MapHairline)
                .clickable(enabled = parsed != null, role = Role.Button, onClick = apply)
                .semantics { contentDescription = "Apply colour code" }
                .testTag(MapBoxColorHexApplyTag)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Done", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The organize tools as a strip under the map's top bar, made like the notes rail: bordered
 * rounded buttons in groups, scrolling sideways under feathered edges. Clear stays put at the
 * right, directly under the three-dot menu.
 */
@Composable
private fun MapOrganizeStrip(selectedId: String, groups: List<List<MapToolbarAction>>, onClear: (() -> Unit)?) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(200)) }
    val palette = com.virlin.app.ui.hierarchy.LocalProjectPalette.current
    val accent = palette?.let { Color(it.secondary) } ?: MapGreen
    // The strip is white whatever the map's colours, so icons use the dark map ink.
    val ink = MapInk
    Box(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * -8.dp.toPx()
            }
            .background(Color.White)
            .testTag("map_organize_strip"),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            com.virlin.app.ui.notes.NoteFeatheredRow(Modifier.weight(1f).height(56.dp), peek = true) {
                groups.forEachIndexed { index, group ->
                    if (index > 0) Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(24.dp)
                        .background(MapHairline))
                    group.forEachIndexed { i, action ->
                        if (i > 0) Spacer(Modifier.width(6.dp))
                        key(selectedId, action.label) { MapStripTool(action, accent, ink) }
                    }
                }
            }
            if (onClear != null) MapIconAction(Icons.Default.Close, "Clear selection", false, false, true, 0L, onClear)
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(MapHairline))
    }
}

@Composable
private fun MapStripTool(action: MapToolbarAction, accent: Color, ink: Color) {
    val shape = RoundedCornerShape(12.dp)
    val color = when {
        !action.enabled -> MapMuted.copy(alpha = .45f)
        action.destructive -> MaterialTheme.colorScheme.error
        action.selected -> accent
        else -> ink
    }
    Box(
        Modifier.size(40.dp)
            .clip(shape)
            .background(if (action.selected) accent.copy(alpha = .12f) else Color.White)
            .border(1.dp, if (action.selected) accent.copy(alpha = .38f) else MapHairline, shape)
            .semantics { contentDescription = action.label; this.selected = action.selected }
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .testTag("map_tool_${action.label.lowercase().replace(' ', '_')}"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(action.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
    }
}

private data class MapToolbarAction(
    val icon: ImageVector, val label: String, val onClick: () -> Unit,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
)

@Composable
private fun MapIconAction(icon: ImageVector, label: String, selected: Boolean,
    destructive: Boolean,
    enabled: Boolean,
    entranceDelayMs: Long, onClick: () -> Unit) {
    val entrance = remember { Animatable(0f) }
    val tint = if (destructive) MaterialTheme.colorScheme.error else
        com.virlin.app.ui.hierarchy.LocalProjectPalette.current?.let { Color(it.secondary) } ?: MapGreen
    LaunchedEffect(Unit) {
        delay(entranceDelayMs)
        entrance.animateTo(1f, tween(180))
    }
    Box(
        Modifier.size(44.dp)
            .background(if (selected) tint.copy(alpha = .16f) else Color.Transparent,
                RoundedCornerShape(12.dp))
            .graphicsLayer {
                alpha = entrance.value
                translationX = (1f - entrance.value) * 10.dp.toPx()
            }
            .semantics { contentDescription = label; this.selected = selected }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = if (enabled) 1f else .35f),
            modifier = Modifier.size(20.dp))
    }
}

/**
 * Choosing where a branch goes. Every legal destination in this project is listed with the
 * breadcrumb that says where it is, so a cross-workstream move is never a guess.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapDestinationSheet(
    intent: StructureIntent,
    source: MapTopic,
    affectedCount: Int,
    destinations: List<MapDestination>,
    onDismiss: () -> Unit,
    onChoose: (MapDestination) -> Unit,
) {
    val palette = com.virlin.app.ui.hierarchy.LocalProjectPalette.current
    val ink = palette?.let { Color(it.text) } ?: MapInk
    val muted = palette?.let { Color(it.muted) } ?: MapMuted
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text(
                if (intent == StructureIntent.MOVE) "Move \"${source.title}\""
                else "Copy \"${source.title}\"",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink
            )
            Text(
                if (affectedCount == 1) "1 item will move." else "$affectedCount items will move.",
                fontSize = 12.sp, color = muted
            )
            Spacer(Modifier.height(10.dp))
        }
        if (destinations.isEmpty()) {
            Text(
                "There is nowhere else this branch can go.",
                Modifier.padding(18.dp), fontSize = 13.sp, color = muted
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag(MapMoveSheetTag)) {
            items(destinations, key = { it.node.id }) { destination ->
                Column(
                    Modifier.fillMaxWidth()
                        .testTag(mapDestinationTag(destination.node.id))
                        .clickable(role = Role.Button) { onChoose(destination) }
                        .semantics {
                            contentDescription = destination.title + ", " + destination.breadcrumb
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(destination.title, fontSize = 15.sp, color = ink, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(destination.breadcrumb, fontSize = 12.sp, color = muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** One legal landing place, with the path that identifies it. */
data class MapDestination(
    val node: HierarchyNode,
    val title: String,
    val breadcrumb: String,
)

/**
 * The destinations a branch may legally land in, each with its breadcrumb. The list is built
 * from the same node projection the validator uses, so the sheet can never offer something the
 * command would refuse.
 */
fun destinationsFor(
    nodes: List<HierarchyNode>,
    titles: Map<String, String>,
    sourceId: String,
    projectTitle: String,
): List<MapDestination> {
    val byId = nodes.associateBy { it.id }
    val source = byId[sourceId] ?: return emptyList()
    // The branch itself and everything under it are never destinations.
    val children = nodes.groupBy { it.parentId }
    val inBranch = HashSet<String>()
    val stack = ArrayDeque<String>()
    stack.addLast(sourceId)
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (!inBranch.add(id)) continue
        children[id].orEmpty().forEach { stack.addLast(it.id) }
    }

    fun breadcrumb(node: HierarchyNode): String {
        val path = ArrayList<String>()
        var current: HierarchyNode? = node
        while (current != null) {
            path += if (current.kind == NodeKind.PROJECT) projectTitle
            else titles[current.id] ?: current.id
            current = current.parentId?.let(byId::get)
        }
        return path.asReversed().joinToString(" · ")
    }

    return nodes.asSequence()
        .filter { it.id !in inBranch }
        .filter { candidate ->
            when (source.kind) {
                NodeKind.WORKSTREAM -> candidate.kind == NodeKind.PROJECT
                NodeKind.TASK -> true
                NodeKind.PROJECT -> false
            }
        }
        // Dropping back on the current parent with no reordering is a no-op the command refuses.
        .filter { it.id != source.parentId }
        .map { candidate ->
            MapDestination(
                node = candidate,
                title = when (candidate.kind) {
                    NodeKind.PROJECT -> "$projectTitle (standalone)"
                    else -> titles[candidate.id] ?: candidate.id
                },
                breadcrumb = breadcrumb(candidate)
            )
        }
        .sortedBy { it.breadcrumb.lowercase() }
        .toList()
}

/** A refusal, shown with the validator's own words. */
@Composable
fun PlacementRefusedDialog(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("That move is not allowed") },
        text = { Text(reason, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/**
 * The one name editor for a map box: the Organize strip's Edit and the pencil in the preview
 * editor both open it. Styled like the preview editor card. It only hands the new name back.
 */
@Composable
fun MapRenameDialog(
    current: String,
    label: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(current) }
    val palette = com.virlin.app.ui.hierarchy.LocalProjectPalette.current
    val accent = palette?.let { Color(it.secondary) } ?: Color(0xFF0E7A54)
    val ink = palette?.let { Color(it.text) } ?: Color(0xFF192229)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag(MapRenameDialogTag),
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Edit name", modifier = Modifier.weight(1f), fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold, color = ink)
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFF1F3F2))
                            .semantics { contentDescription = "Close without saving" }
                            .clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Close, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.height(14.dp))
                Text(label, fontSize = 13.sp, color = accent, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.OutlinedTextField(
                    // Not single line: a pasted name can hold line breaks, and a single-line field shows
                    // only its first line, so the rest could never be seen or removed.
                    value = text, onValueChange = { text = it },
                    minLines = 1, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink, fontSize = 16.sp),
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFF7F9F8), unfocusedContainerColor = Color(0xFFF7F9F8),
                        focusedBorderColor = accent, unfocusedBorderColor = Color.Transparent, cursorColor = accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(MapRenameFieldTag)
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp).testTag(MapRenameCancelTag)) {
                        Text("Cancel", color = ink)
                    }
                    androidx.compose.material3.Button(
                        onClick = { onSave(text.trim()) },
                        enabled = text.isNotBlank() && text.trim() != current,
                        modifier = Modifier.weight(1f).height(48.dp).testTag(MapRenameSaveTag),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = accent,
                            contentColor = com.virlin.app.ui.hierarchy.readableOn(accent)),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Save") }
                }
            }
        }
    }
}

/** A yes/no question in the same card as [MapRenameDialog]: round close, Cancel and a thick confirm. */
@Composable
fun MapConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = com.virlin.app.ui.hierarchy.LocalProjectPalette.current
    val accent = palette?.let { Color(it.secondary) } ?: Color(0xFF0E7A54)
    val ink = palette?.let { Color(it.text) } ?: Color(0xFF192229)
    val muted = palette?.let { Color(it.muted) } ?: MapMuted
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag(MapConfirmDialogTag),
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, modifier = Modifier.weight(1f), fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold, color = ink)
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFFF1F3F2))
                            .semantics { contentDescription = "Close" }
                            .clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Close, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.height(10.dp))
                Text(message, fontSize = 15.sp, color = muted, lineHeight = 21.sp)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)
                        .testTag(MapConfirmCancelTag)) { Text("Cancel", color = ink) }
                    androidx.compose.material3.Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f).height(48.dp).testTag(MapConfirmOkTag),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = accent,
                            contentColor = com.virlin.app.ui.hierarchy.readableOn(accent)),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text(confirm) }
                }
            }
        }
    }
}

/**
 * A new child under [parentTitle]. Only the title is asked for; everything else about the new
 * task - its project, its workstream, its position among its siblings - is decided by the
 * canonical `addSubtask` action from the parent, so this dialog cannot disagree with the domain.
 */
@Composable
fun MapAddSubtaskDialog(
    parentTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val muted = com.virlin.app.ui.hierarchy.LocalProjectPalette.current?.let { Color(it.muted) } ?: MapMuted
    com.virlin.app.ui.components.SafeDialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = Color.White) {
            com.virlin.app.ui.hierarchy.TaskCreationCard(parentTitle, "Add subtask", onDismiss, onSave,
                titleTag = MapAddSubtaskFieldTag, confirmTag = MapAddSubtaskSaveTag) {
                Text("Use parent · At end", fontSize = 12.sp, color = muted)
            }
        }
    }
}

/** One short piece of text confirmed before anything is written. */
@Composable
fun MapAddTextDialog(
    title: String,
    subtitle: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    initial: String = "",
    singleLine: Boolean = true,
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }
    val muted = com.virlin.app.ui.hierarchy.LocalProjectPalette.current?.let { Color(it.muted) } ?: MapMuted
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(subtitle, fontSize = 13.sp, color = muted)
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(label) }, singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3,
                    modifier = Modifier.fillMaxWidth().testTag(MapAddTextFieldTag)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(MapAddTextSaveTag)
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

const val MapAddTextFieldTag = "map_add_text_field"
const val MapAddTextSaveTag = "map_add_text_save"

const val MapAddSubtaskFieldTag = "map_add_subtask_field"
const val MapAddSubtaskSaveTag = "map_add_subtask_save"

const val MapRenameDialogTag = "map_rename_dialog"
const val MapRenameCancelTag = "map_rename_cancel"
const val MapRenameFieldTag = "map_rename_field"
const val MapRenameSaveTag = "map_rename_save"


/** A plain message that is not about a placement. */
@Composable
fun MapNoticeDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
