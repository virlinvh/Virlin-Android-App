package com.virlin.app.ui.notes

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Crop169
import androidx.compose.material.icons.outlined.Crop32
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virlin.app.data.noteimage.NoteImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted
private val EditorCanvas = Color(0xFF1C1C1E)

enum class NoteImageMode(val label: String) { CROP("Crop"), ADJUST("Adjust"), MARKUP("Markup") }

/** Pen colours: strong enough to read on photos and on whitened paper. */
internal val PEN_COLORS = listOf(
    0xFFE53935L to "Red", 0xFFFFD600L to "Yellow", 0xFF43A047L to "Green", 0xFF1E88E5L to "Blue",
    0xFF8E24AAL to "Purple", 0xFFFB8C00L to "Orange", 0xFF212121L to "Black", 0xFFFFFFFFL to "White",
)
/** Pen widths as a fraction of the picture's longer side; the highlighter is three times wider. */
internal val PEN_SIZES = listOf(0.004f, 0.008f, 0.014f, 0.022f)
internal const val HIGHLIGHTER_WIDTH_FACTOR = 3f

private val CROP_RATIOS: List<Triple<String, Float?, ImageVector>> = listOf(
    Triple("Free", null, Icons.Outlined.CropFree),
    Triple("Square", 1f, Icons.Outlined.CropSquare),
    Triple("3:2", 3f / 2f, Icons.Outlined.Crop32),
    Triple("3:4", 3f / 4f, Icons.Outlined.CropPortrait),
    Triple("16:9", 16f / 9f, Icons.Outlined.Crop169),
)

private enum class AdjustSlider(val label: String, val icon: ImageVector) {
    BRIGHTNESS("Brightness", Icons.Outlined.LightMode),
    CONTRAST("Contrast", Icons.Outlined.Contrast),
    SATURATION("Saturation", Icons.Outlined.Palette),
}

private enum class CropHandle(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    NONE(false, false, false, false), MOVE(false, false, false, false),
    TOP_LEFT(true, true, false, false), TOP_RIGHT(false, true, true, false),
    BOTTOM_LEFT(true, false, false, true), BOTTOM_RIGHT(false, false, true, true),
    LEFT(true, false, false, false), TOP(false, true, false, false),
    RIGHT(false, false, true, false), BOTTOM(false, false, false, true),
}

// ------------------------------------------------------------------ strip in the document

class NoteImageToolsStripState(
    val busy: Boolean,
    val onEdit: (NoteImageMode) -> Unit,
    val onRotate: () -> Unit,
    val onReplace: () -> Unit,
    val onDelete: () -> Unit,
    val onClose: () -> Unit,
)

/** Tools for the selected image, under the formatting rail like the other strips. */
@Composable
internal fun NoteImageToolsStrip(state: NoteImageToolsStripState, modifier: Modifier = Modifier) {
    BackHandler { state.onClose() }
    val enabled = !state.busy
    val prefix = "notes_image_tool_"
    NoteToolStripShell("notes_image_tools", "Close image tools", "notes_image_tools_close", state.onClose, modifier) {
        item { ToolTile(Icons.Outlined.Crop, "Crop", "Crop image", "crop", enabled, tagPrefix = prefix) { state.onEdit(NoteImageMode.CROP) } }
        item { ToolTile(Icons.Outlined.Tune, "Adjust", "Brightness, contrast and filters", "adjust", enabled, tagPrefix = prefix) { state.onEdit(NoteImageMode.ADJUST) } }
        item { ToolTile(Icons.Outlined.Draw, "Markup", "Draw and highlight on the image", "markup", enabled, tagPrefix = prefix) { state.onEdit(NoteImageMode.MARKUP) } }
        item { GroupDivider() }
        item { ToolTile(Icons.Outlined.RotateRight, if (state.busy) "Working" else "Rotate", "Rotate image right", "rotate", enabled, tagPrefix = prefix, onClick = state.onRotate) }
        item { GroupDivider() }
        item { ToolTile(Icons.Outlined.SwapHoriz, "Replace", "Replace image", "replace", enabled, tagPrefix = prefix, onClick = state.onReplace) }
        item { ToolTile(Icons.Outlined.DeleteOutline, "Delete", "Delete image", "delete", enabled, tagPrefix = prefix, onClick = state.onDelete) }
    }
}

// ------------------------------------------------------------------ full-screen editor

/**
 * Full-screen editor for one note image: Crop (ratios, rotate), Adjust (whiten / scan filters,
 * brightness, contrast, saturation) and Markup (pen and highlighter, colours, sizes). Edits stay
 * a [NoteImageEdit] until Save, which writes a new file and hands back its name; the original
 * file is never changed.
 */
@Composable
internal fun NoteImageEditor(
    imageName: String,
    startMode: NoteImageMode,
    onCancel: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    val source by produceState<Bitmap?>(null, imageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                NoteImageStore.resolve(context, imageName)?.takeIf { it.isFile }
                    ?.let { NoteImageRender.decode(it, NoteImageRender.PREVIEW_SIDE) }
            }.getOrNull()
        }
        loaded = true
    }
    var edit by remember { mutableStateOf(NoteImageEdit()) }
    val history = remember { mutableStateListOf<NoteImageEdit>() }
    var mode by remember { mutableStateOf(startMode) }
    var ratio by remember { mutableStateOf<Float?>(null) }
    var slider by remember { mutableStateOf(AdjustSlider.BRIGHTNESS) }
    var sliderPushed by remember { mutableStateOf(false) }
    var penColor by remember { mutableLongStateOf(PEN_COLORS[0].first) }
    var penSize by remember { mutableIntStateOf(1) }
    var highlighter by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun commit(next: NoteImageEdit) {
        if (next != edit) { history.add(edit); edit = next }
    }
    val cancel: () -> Unit = { if (edit.isIdentity || saving) { if (!saving) onCancel() } else confirmDiscard = true }
    val save: () -> Unit = save@{
        if (saving || edit.isIdentity) return@save
        saving = true
        failure = null
        val snapshot = edit
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val file = NoteImageStore.resolve(context, imageName)?.takeIf { it.isFile }
                        ?: throw IllegalStateException("The image file is missing")
                    val full = NoteImageRender.decode(file, NoteImageRender.MAX_SIDE)
                        ?: throw IllegalStateException("The image could not be read")
                    val out = NoteImageRender.render(full, snapshot)
                    try {
                        NoteImageStore.saveEdited(context, out, NoteImageStore.keepsTransparency(file))
                    } finally {
                        out.recycle()
                        full.recycle()
                    }
                }
            }
            saving = false
            result.onSuccess(onSaved).onFailure { failure = it.message ?: "Could not save the image" }
        }
    }

    Dialog(
        onDismissRequest = cancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.White).testTag("notes_image_editor")) {
            // Top bar: close · title · undo · save.
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(Icons.Filled.Close, "Close image editor", "notes_image_editor_close", true, onClick = cancel)
                Text("Edit image", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconAction(Icons.AutoMirrored.Outlined.Undo, "Undo last image change", "notes_image_editor_undo",
                    history.isNotEmpty() && !saving) { edit = history.removeAt(history.lastIndex) }
                val canSave = !edit.isIdentity && !saving && source != null
                Box(
                    Modifier.padding(start = 4.dp, end = 6.dp).height(38.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (canSave) Green else Border)
                        .clickable(enabled = canSave, role = Role.Button, onClick = save)
                        .semantics { contentDescription = "Save edited image" }
                        .testTag("notes_image_editor_save")
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (saving) "Saving…" else "Save", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
            failure?.let {
                Text(it, color = Color(0xFFC62828), fontSize = 12.sp, modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(EditorCanvas), contentAlignment = Alignment.Center) {
                val img = source
                if (img == null) {
                    Text(if (loaded) "This image can't be opened." else "Opening…", color = Color.White.copy(alpha = .7f), fontSize = 14.sp)
                } else {
                    EditorCanvasArea(
                        source = img, edit = edit, mode = mode, ratio = ratio,
                        pen = NoteImageStroke(emptyList(), penColor,
                            PEN_SIZES[penSize] * if (highlighter) HIGHLIGHTER_WIDTH_FACTOR else 1f, highlighter),
                        onCropDragStart = { history.add(edit) },
                        onCropChange = { crop -> edit = edit.copy(crop = crop) },
                        onStroke = { stroke -> commit(edit.copy(strokes = edit.strokes + stroke)) },
                    )
                }
            }
            val img = source
            val (turnedW, turnedH) = if (img == null) 1 to 1
                else NoteImageGeometry.turnedSize(img.width, img.height, edit.quarterTurns)
            when (mode) {
                NoteImageMode.CROP -> EditorStrip("notes_image_crop_tools") {
                    CROP_RATIOS.forEach { (label, r, icon) ->
                        item {
                            ToolTile(icon, label, "Crop ratio $label", "ratio_${label.replace(":", "x").lowercase()}",
                                selected = ratio == r, tagPrefix = "notes_image_editor_") {
                                ratio = r
                                commit(edit.copy(crop = NoteImageGeometry.cropForRatio(edit.crop, r, turnedW, turnedH)))
                            }
                        }
                    }
                    item { GroupDivider() }
                    item { ToolTile(Icons.Outlined.RotateLeft, "Left", "Rotate left", "rotate_left", tagPrefix = "notes_image_editor_") { ratio = null; commit(edit.turned(clockwise = false)) } }
                    item { ToolTile(Icons.Outlined.RotateRight, "Right", "Rotate right", "rotate_right", tagPrefix = "notes_image_editor_") { ratio = null; commit(edit.turned(clockwise = true)) } }
                    item { GroupDivider() }
                    item {
                        ToolTile(Icons.Outlined.Restore, "Reset", "Reset crop", "crop_reset", enabled = !edit.crop.isFull,
                            tagPrefix = "notes_image_editor_") { ratio = null; commit(edit.copy(crop = NoteImageCrop.FULL)) }
                    }
                }
                NoteImageMode.ADJUST -> {
                    val value = when (slider) {
                        AdjustSlider.BRIGHTNESS -> edit.brightness
                        AdjustSlider.CONTRAST -> edit.contrast
                        AdjustSlider.SATURATION -> edit.saturation
                    }
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(slider.label, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(76.dp))
                        Slider(
                            value = value,
                            onValueChange = { v ->
                                if (!sliderPushed) { history.add(edit); sliderPushed = true }
                                val snapped = if (abs(v) < 0.04f) 0f else v
                                edit = when (slider) {
                                    AdjustSlider.BRIGHTNESS -> edit.copy(brightness = snapped)
                                    AdjustSlider.CONTRAST -> edit.copy(contrast = snapped)
                                    AdjustSlider.SATURATION -> edit.copy(saturation = snapped)
                                }
                            },
                            onValueChangeFinished = { sliderPushed = false },
                            valueRange = -1f..1f,
                            colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green, inactiveTrackColor = Border),
                            modifier = Modifier.weight(1f).semantics { contentDescription = slider.label }
                                .testTag("notes_image_editor_slider"),
                        )
                        Text("${(value * 100).roundToInt()}", color = Muted, fontSize = 12.sp,
                            modifier = Modifier.width(36.dp).padding(start = 8.dp))
                    }
                    EditorStrip("notes_image_adjust_tools") {
                        NoteImageFilter.entries.forEach { f ->
                            val icon = when (f) {
                                NoteImageFilter.ORIGINAL -> Icons.Outlined.Restore
                                NoteImageFilter.DOCUMENT -> Icons.Outlined.DocumentScanner
                                NoteImageFilter.DOCUMENT_BW -> Icons.Outlined.FilterBAndW
                                NoteImageFilter.GRAYSCALE -> Icons.Outlined.Opacity
                            }
                            item {
                                ToolTile(icon, f.label, "${f.label} filter", "filter_${f.name.lowercase()}",
                                    selected = edit.filter == f, tagPrefix = "notes_image_editor_") { commit(edit.copy(filter = f)) }
                            }
                        }
                        item { GroupDivider() }
                        AdjustSlider.entries.forEach { s ->
                            item {
                                ToolTile(s.icon, s.label.take(10), "Adjust ${s.label.lowercase()}", "slider_${s.name.lowercase()}",
                                    selected = slider == s, tagPrefix = "notes_image_editor_") { slider = s }
                            }
                        }
                        item { GroupDivider() }
                        item {
                            ToolTile(Icons.Outlined.Restore, "Reset", "Reset brightness, contrast and saturation", "adjust_reset",
                                enabled = edit.hasAdjustments, tagPrefix = "notes_image_editor_") {
                                commit(edit.copy(brightness = 0f, contrast = 0f, saturation = 0f))
                            }
                        }
                    }
                }
                NoteImageMode.MARKUP -> EditorStrip("notes_image_markup_tools") {
                    item {
                        ToolTile(Icons.Outlined.Draw, "Pen", "Pen", "pen", selected = !highlighter, tagPrefix = "notes_image_editor_") {
                            if (highlighter && penColor == PEN_COLORS[1].first) penColor = PEN_COLORS[0].first
                            highlighter = false
                        }
                    }
                    item {
                        ToolTile(Icons.Outlined.Highlight, "Highlight", "Highlighter", "highlighter", selected = highlighter, tagPrefix = "notes_image_editor_") {
                            if (!highlighter) penColor = PEN_COLORS[1].first
                            highlighter = true
                        }
                    }
                    item { GroupDivider() }
                    PEN_COLORS.forEach { (argb, name) ->
                        item { PenSwatch(argb, "$name ink", penColor == argb) { penColor = argb } }
                    }
                    item { GroupDivider() }
                    PEN_SIZES.indices.forEach { i ->
                        item { PenSizeDot(i, penSize == i, Color(penColor)) { penSize = i } }
                    }
                    item { GroupDivider() }
                    item {
                        ToolTile(Icons.AutoMirrored.Outlined.Undo, "Undo mark", "Remove last mark", "mark_undo",
                            enabled = edit.strokes.isNotEmpty(), tagPrefix = "notes_image_editor_") {
                            commit(edit.copy(strokes = edit.strokes.dropLast(1)))
                        }
                    }
                    item {
                        ToolTile(Icons.Outlined.LayersClear, "Clear", "Remove all marks", "mark_clear",
                            enabled = edit.strokes.isNotEmpty(), tagPrefix = "notes_image_editor_") {
                            commit(edit.copy(strokes = emptyList()))
                        }
                    }
                }
            }
            // Mode tabs.
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteImageMode.entries.forEach { m ->
                    val icon = when (m) {
                        NoteImageMode.CROP -> Icons.Outlined.Crop
                        NoteImageMode.ADJUST -> Icons.Outlined.Tune
                        NoteImageMode.MARKUP -> Icons.Outlined.Draw
                    }
                    val selected = mode == m
                    Row(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(14.dp))
                            .background(if (selected) Green.copy(alpha = .12f) else Color.Transparent)
                            .clickable(role = Role.Tab) { mode = m }
                            .semantics { contentDescription = "${m.label} mode"; this.selected = selected }
                            .testTag("notes_image_editor_mode_${m.name.lowercase()}"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, contentDescription = null, tint = if (selected) Green else Muted, modifier = Modifier.size(20.dp))
                        Text(m.label, color = if (selected) Green else Muted, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        if (confirmDiscard) AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your edits to this image will be lost. The image in the note stays as it was.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onCancel() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

/** Where the picture sits on screen: the shown region of the turned image, scaled and centred. */
private data class ImageFit(
    val turnedW: Int, val turnedH: Int,
    val originX: Int, val originY: Int, val regionW: Int, val regionH: Int,
    val scale: Float, val dx: Float, val dy: Float,
) {
    val drawnW: Float get() = regionW * scale
    val drawnH: Float get() = regionH * scale

    /** Screen position → point in the original image, clamped to the picture. */
    fun toSource(pos: Offset, quarterTurns: Int): NoteImagePoint {
        val tx = ((originX + (pos.x - dx) / scale) / turnedW).coerceIn(0f, 1f)
        val ty = ((originY + (pos.y - dy) / scale) / turnedH).coerceIn(0f, 1f)
        return NoteImageGeometry.fromTurned(NoteImagePoint(tx, ty), quarterTurns)
    }
}

@Composable
private fun EditorCanvasArea(
    source: Bitmap,
    edit: NoteImageEdit,
    mode: NoteImageMode,
    ratio: Float?,
    pen: NoteImageStroke,
    onCropDragStart: () -> Unit,
    onCropChange: (NoteImageCrop) -> Unit,
    onStroke: (NoteImageStroke) -> Unit,
) {
    val turned = remember(source, edit.quarterTurns) { NoteImageRender.turned(source, edit.quarterTurns) }
    val filter = edit.filter
    val whitened by produceState<Bitmap?>(null, turned, filter) {
        value = null
        if (filter == NoteImageFilter.DOCUMENT || filter == NoteImageFilter.DOCUMENT_BW) {
            value = withContext(Dispatchers.Default) {
                runCatching { NoteImageRender.whitened(turned, filter == NoteImageFilter.DOCUMENT_BW) }.getOrNull()
            }
        }
    }
    val needsWhiten = filter == NoteImageFilter.DOCUMENT || filter == NoteImageFilter.DOCUMENT_BW
    val shown = whitened.takeIf { needsWhiten } ?: turned
    val image = remember(shown) { shown.asImageBitmap() }
    val matrix = NoteImageColor.adjustMatrix(edit.brightness, edit.contrast, edit.saturation,
        gray = filter == NoteImageFilter.GRAYSCALE)
    val colorFilter = if (NoteImageColor.isIdentity(matrix)) null else ColorFilter.colorMatrix(ColorMatrix(matrix))
    val longSide = max(source.width, source.height).toFloat()
    val density = LocalDensity.current
    val current = remember { mutableStateListOf<NoteImagePoint>() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pad = with(density) { (if (mode == NoteImageMode.CROP) 28.dp else 16.dp).toPx() }
        val cw = constraints.maxWidth.toFloat()
        val ch = constraints.maxHeight.toFloat()
        val region = if (mode == NoteImageMode.CROP) intArrayOf(0, 0, turned.width, turned.height)
            else NoteImageGeometry.cropPixels(edit.crop, turned.width, turned.height)
        val scale = max(0.01f, min((cw - 2 * pad) / region[2], (ch - 2 * pad) / region[3]))
        val fit = ImageFit(turned.width, turned.height, region[0], region[1], region[2], region[3], scale,
            (cw - region[2] * scale) / 2f, (ch - region[3] * scale) / 2f)
        val fitState = rememberUpdatedState(fit)
        val editState = rememberUpdatedState(edit)
        val ratioState = rememberUpdatedState(ratio)
        val penState = rememberUpdatedState(pen)
        val cornerTouch = with(density) { 36.dp.toPx() }
        val edgeTouch = with(density) { 24.dp.toPx() }
        val strokes = remember(edit.strokes, fit, edit.quarterTurns) {
            edit.strokes.map { s ->
                NoteImageRender.strokePath(s, edit.quarterTurns, fit.turnedW.toFloat(), fit.turnedH.toFloat(),
                    fit.originX.toFloat(), fit.originY.toFloat(), fit.scale, fit.dx, fit.dy) to
                    NoteImageRender.strokePaint(s, longSide, fit.scale)
            }
        }
        val gestures = when (mode) {
            NoteImageMode.CROP -> Modifier.pointerInput(Unit) {
                var handle = CropHandle.NONE
                detectDragGestures(
                    onDragStart = { pos ->
                        handle = hitCrop(pos, fitState.value, editState.value.crop, ratioState.value != null, cornerTouch, edgeTouch)
                        if (handle != CropHandle.NONE) onCropDragStart()
                    },
                    onDragEnd = { handle = CropHandle.NONE },
                    onDragCancel = { handle = CropHandle.NONE },
                    onDrag = { change, amount ->
                        if (handle != CropHandle.NONE) {
                            change.consume()
                            val f = fitState.value
                            val nx = amount.x / (f.turnedW * f.scale)
                            val ny = amount.y / (f.turnedH * f.scale)
                            val crop = editState.value.crop
                            onCropChange(
                                if (handle == CropHandle.MOVE) NoteImageGeometry.moved(crop, nx, ny)
                                else NoteImageGeometry.resized(crop, nx, ny, handle.left, handle.top, handle.right, handle.bottom,
                                    ratioState.value?.let { it * f.turnedH / f.turnedW }),
                            )
                        }
                    },
                )
            }
            NoteImageMode.MARKUP -> Modifier
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        onStroke(penState.value.copy(points = listOf(fitState.value.toSource(pos, editState.value.quarterTurns))))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            current.clear()
                            current.add(fitState.value.toSource(pos, editState.value.quarterTurns))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            current.add(fitState.value.toSource(change.position, editState.value.quarterTurns))
                        },
                        onDragEnd = {
                            if (current.isNotEmpty()) onStroke(penState.value.copy(points = current.toList()))
                            current.clear()
                        },
                        onDragCancel = { current.clear() },
                    )
                }
            NoteImageMode.ADJUST -> Modifier
        }
        Canvas(Modifier.fillMaxSize().then(gestures).semantics {
            contentDescription = when (mode) {
                NoteImageMode.CROP -> "Image. Drag the corners or edges to crop, or drag inside to move the crop."
                NoteImageMode.MARKUP -> "Image. Drag to draw."
                NoteImageMode.ADJUST -> "Image preview"
            }
        }.testTag("notes_image_editor_canvas")) {
            val f = fit
            drawImage(
                image = image,
                srcOffset = IntOffset(f.originX, f.originY),
                srcSize = IntSize(f.regionW, f.regionH),
                dstOffset = IntOffset(f.dx.roundToInt(), f.dy.roundToInt()),
                dstSize = IntSize(f.drawnW.roundToInt(), f.drawnH.roundToInt()),
                colorFilter = colorFilter,
                filterQuality = FilterQuality.High,
            )
            clipRect(f.dx, f.dy, f.dx + f.drawnW, f.dy + f.drawnH) {
                drawIntoCanvas { canvas ->
                    strokes.forEach { (path, paint) -> canvas.nativeCanvas.drawPath(path, paint) }
                    if (current.isNotEmpty()) {
                        val live = penState.value.copy(points = current.toList())
                        canvas.nativeCanvas.drawPath(
                            NoteImageRender.strokePath(live, edit.quarterTurns, f.turnedW.toFloat(), f.turnedH.toFloat(),
                                f.originX.toFloat(), f.originY.toFloat(), f.scale, f.dx, f.dy),
                            NoteImageRender.strokePaint(live, longSide, f.scale),
                        )
                    }
                }
            }
            if (mode == NoteImageMode.CROP) {
                val c = edit.crop
                val l = f.dx + c.left * f.drawnW
                val t = f.dy + c.top * f.drawnH
                val r = f.dx + c.right * f.drawnW
                val b = f.dy + c.bottom * f.drawnH
                val dim = Color.Black.copy(alpha = .55f)
                drawRect(dim, Offset(f.dx, f.dy), Size(f.drawnW, t - f.dy))
                drawRect(dim, Offset(f.dx, b), Size(f.drawnW, f.dy + f.drawnH - b))
                drawRect(dim, Offset(f.dx, t), Size(l - f.dx, b - t))
                drawRect(dim, Offset(r, t), Size(f.dx + f.drawnW - r, b - t))
                val thin = 1.dp.toPx()
                for (i in 1..2) {
                    val x = l + (r - l) * i / 3f
                    val y = t + (b - t) * i / 3f
                    drawLine(Color.White.copy(alpha = .45f), Offset(x, t), Offset(x, b), thin)
                    drawLine(Color.White.copy(alpha = .45f), Offset(l, y), Offset(r, y), thin)
                }
                drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
                val arm = 18.dp.toPx()
                val thick = 3.dp.toPx()
                listOf(l to t, r to t, l to b, r to b).forEach { (x, y) ->
                    val sx = if (x == l) 1f else -1f
                    val sy = if (y == t) 1f else -1f
                    drawLine(Color.White, Offset(x, y), Offset(x + sx * arm, y), thick)
                    drawLine(Color.White, Offset(x, y), Offset(x, y + sy * arm), thick)
                }
            }
        }
        if (needsWhiten && whitened == null) {
            Text("Applying…", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

private fun hitCrop(pos: Offset, f: ImageFit, crop: NoteImageCrop, ratioLocked: Boolean,
                    corner: Float, edge: Float): CropHandle {
    val l = f.dx + crop.left * f.drawnW
    val t = f.dy + crop.top * f.drawnH
    val r = f.dx + crop.right * f.drawnW
    val b = f.dy + crop.bottom * f.drawnH
    fun near(x: Float, y: Float) = abs(pos.x - x) <= corner && abs(pos.y - y) <= corner
    when {
        near(l, t) -> return CropHandle.TOP_LEFT
        near(r, t) -> return CropHandle.TOP_RIGHT
        near(l, b) -> return CropHandle.BOTTOM_LEFT
        near(r, b) -> return CropHandle.BOTTOM_RIGHT
    }
    val withinX = pos.x in l..r
    val withinY = pos.y in t..b
    if (!ratioLocked) when {
        withinY && abs(pos.x - l) <= edge -> return CropHandle.LEFT
        withinY && abs(pos.x - r) <= edge -> return CropHandle.RIGHT
        withinX && abs(pos.y - t) <= edge -> return CropHandle.TOP
        withinX && abs(pos.y - b) <= edge -> return CropHandle.BOTTOM
    }
    return if (withinX && withinY) CropHandle.MOVE else CropHandle.NONE
}

@Composable
private fun EditorStrip(tag: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().height(60.dp).testTag(tag),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
}

@Composable
private fun IconAction(icon: ImageVector, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; if (!enabled) disabled() }
            .testTag(tag)
            .alpha(if (enabled) 1f else .35f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun PenSwatch(argb: Long, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape)
            .border(if (selected) 2.dp else 0.dp, if (selected) Green else Color.Transparent, CircleShape)
            .padding(4.dp).clip(CircleShape)
            .background(Color(argb))
            .border(1.dp, Border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description; this.selected = selected }
            .testTag("notes_image_pen_${argb.toString(16)}"),
    )
}

@Composable
private fun PenSizeDot(index: Int, selected: Boolean, color: Color, onClick: () -> Unit) {
    val names = listOf("Fine", "Medium", "Thick", "Extra thick")
    Box(
        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Green.copy(alpha = .10f) else Color.White)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Green else Border, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${names[index]} pen"; this.selected = selected }
            .testTag("notes_image_pen_size_$index"),
        contentAlignment = Alignment.Center,
    ) {
        val dot = (4 + index * 4).dp
        Box(Modifier.size(dot).clip(CircleShape).background(if (color == Color.White) Ink else color))
    }
}
