package com.virlin.app.ui.image

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val ImagePaper = Color(0xFFFAF9F6)
private val ImageInk = Color(0xFF172019)
private val ImageMuted = Color(0xFF68736C)
private val ImageGreen = Color(0xFF078A5A)
private val ImageMint = Color(0xFFE8F7EF)
private val ImageLine = Color(0xFFDCE4DF)

@Composable
fun ImageWorkspaceScreen(
    navController: NavController,
    taskId: String?,
    captureId: String?,
    vm: ImageWorkspaceViewModel = viewModel(
        key = "image-${taskId.orEmpty()}-${captureId.orEmpty()}",
        factory = ImageWorkspaceViewModel.factory(taskId, captureId, LocalContext.current),
    ),
) {
    val state by vm.state.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.importImages(it.orEmpty())
    }
    fun back() {
        when (imageBackAction(state.mode, hasCaptureOwner = captureId != null)) {
            ImageBackAction.EXIT -> navController.popBackStack()
            ImageBackAction.TO_LIBRARY -> vm.setMode(ImageWorkspaceMode.LIBRARY)
            ImageBackAction.TO_EDIT -> vm.setMode(ImageWorkspaceMode.EDIT)
        }
    }
    BackHandler { back() }

    Scaffold(
        containerColor = ImagePaper,
        topBar = { ImageTopBar(state, vm, ::back) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).testTag(ImageWorkspaceTag)) {
            when (state.mode) {
                ImageWorkspaceMode.LIBRARY -> ImageLibrary(state, vm) { picker.launch(arrayOf("image/*")) }
                ImageWorkspaceMode.EDIT -> ImageEdit(state, vm)
                ImageWorkspaceMode.CROP -> ImageCropPanel(state, vm)
                ImageWorkspaceMode.MARKUP -> ImageMarkupPanel(state, vm)
            }
            if (state.loading || state.importing || state.saving) {
                Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = .72f)), Alignment.Center) {
                    CircularProgressIndicator(color = ImageGreen)
                }
            }
        }
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            confirmButton = { TextButton(vm::clearMessage) { Text("OK") } },
            text = { Text(message) },
        )
    }
}

@Composable
private fun ImageTopBar(state: ImageWorkspaceState, vm: ImageWorkspaceViewModel, back: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ImagePaper).statusBarsPadding().padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = ImageInk) }
        Column(Modifier.weight(1f)) {
            Text(
                when (state.mode) {
                    ImageWorkspaceMode.LIBRARY -> "Images"
                    ImageWorkspaceMode.EDIT -> "Edit image"
                    ImageWorkspaceMode.CROP -> "Crop & rotate"
                    ImageWorkspaceMode.MARKUP -> "Markup"
                },
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ImageInk,
            )
            Text(
                when (state.mode) {
                    ImageWorkspaceMode.LIBRARY -> state.taskTitle.takeIf(String::isNotBlank)?.let { "Attached to · $it" } ?: "Image workspace"
                    ImageWorkspaceMode.EDIT -> "Original remains unchanged"
                    ImageWorkspaceMode.CROP -> "Freeform"
                    ImageWorkspaceMode.MARKUP -> "Layer 1"
                },
                fontSize = 12.sp, color = ImageMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.mode != ImageWorkspaceMode.LIBRARY) {
            IconButton(vm::undo, enabled = state.canUndo) { Icon(Icons.Rounded.Undo, "Undo") }
            IconButton(vm::redo, enabled = state.canRedo) { Icon(Icons.Rounded.Redo, "Redo") }
        }
    }
}

@Composable
private fun ImageLibrary(state: ImageWorkspaceState, vm: ImageWorkspaceViewModel, choose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(18.dp))
                .background(ImageMint).border(1.dp, ImageLine, RoundedCornerShape(18.dp)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.Image, null, tint = ImageGreen, modifier = Modifier.size(30.dp))
            Text("Add images", fontWeight = FontWeight.Bold, color = ImageInk)
            Text("JPG, PNG, WEBP, HEIC and more", fontSize = 12.sp, color = ImageMuted)
            Spacer(Modifier.height(12.dp))
            Button(
                choose, Modifier.fillMaxWidth().testTag(ImageChooseButtonTag),
                colors = ButtonDefaults.buttonColors(containerColor = ImageGreen),
                shape = RoundedCornerShape(13.dp),
            ) { Text("Choose images") }
        }
        if (state.items.isEmpty() && !state.loading) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text("No images added yet", color = ImageMuted)
            }
        } else {
            Text("Saved images · ${state.items.size}", Modifier.padding(horizontal = 18.dp, vertical = 8.dp), color = ImageMuted, fontWeight = FontWeight.SemiBold)
            LazyVerticalGrid(
                GridCells.Fixed(2), Modifier.weight(1f).padding(horizontal = 14.dp),
                contentPadding = PaddingValues(bottom = 90.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items, key = { it.capture.id }) { item -> ImageTile(item) { vm.edit(item) } }
            }
        }
        if (state.items.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().background(ImagePaper).padding(16.dp)) {
                Button(choose, Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = ImageGreen), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text("Add images")
                }
            }
        }
    }
}

@Composable
private fun ImageTile(item: ImageWorkspaceItem, open: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, ImageLine, RoundedCornerShape(14.dp)).clickable(onClick = open).padding(6.dp)) {
        ManagedBitmap(item.absolutePath, 520) { bitmap ->
            if (bitmap == null) Box(Modifier.fillMaxWidth().aspectRatio(1.2f).background(ImageMint), Alignment.Center) { Icon(Icons.Rounded.BrokenImage, null, tint = ImageMuted) }
            else Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        }
        Text(item.document.displayName, Modifier.padding(5.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ImageEdit(state: ImageWorkspaceState, vm: ImageWorkspaceViewModel) {
    var tool by remember { mutableStateOf(ImageEditTool.ADJUST) }
    Column(Modifier.fillMaxSize()) {
        EditPreview(state, Modifier.fillMaxWidth().weight(1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            EditToolButton("Adjust", Icons.Rounded.Tune, tool == ImageEditTool.ADJUST) { tool = ImageEditTool.ADJUST }
            EditToolButton("Crop", Icons.Rounded.Crop, false) { vm.setMode(ImageWorkspaceMode.CROP) }
            EditToolButton("Markup", Icons.Rounded.Draw, false) { vm.setMode(ImageWorkspaceMode.MARKUP) }
            EditToolButton("Text", Icons.Rounded.TextFields, false) { vm.setMode(ImageWorkspaceMode.MARKUP) }
            EditToolButton("Filters", Icons.Rounded.FilterAlt, tool == ImageEditTool.FILTERS) { tool = ImageEditTool.FILTERS }
        }
        if (tool == ImageEditTool.ADJUST) AdjustmentControls(state.edit.adjustments, vm)
        else FilterControls(state.edit.adjustments, vm)
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(vm::reset, Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) { Text("Reset") }
            Button(vm::saveCopy, Modifier.weight(1.4f).height(50.dp).testTag(ImageSaveCopyTag), colors = ButtonDefaults.buttonColors(containerColor = ImageGreen), shape = RoundedCornerShape(14.dp)) { Text("Save copy") }
        }
    }
}

@Composable
private fun AdjustmentControls(value: ImageAdjustments, vm: ImageWorkspaceViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).verticalScroll(rememberScrollState())) {
        AdjustSlider("Brightness", value.brightness, { vm.updateAdjustments(value.copy(brightness = it)) }, vm::commitCurrent)
        AdjustSlider("Contrast", value.contrast, { vm.updateAdjustments(value.copy(contrast = it)) }, vm::commitCurrent)
        AdjustSlider("Saturation", value.saturation, { vm.updateAdjustments(value.copy(saturation = it)) }, vm::commitCurrent)
        AdjustSlider("Warmth", value.warmth, { vm.updateAdjustments(value.copy(warmth = it)) }, vm::commitCurrent)
    }
}

@Composable
private fun FilterControls(value: ImageAdjustments, vm: ImageWorkspaceViewModel) {
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Original" to ImageAdjustments(), "Vivid" to ImageAdjustments(contrast = .12f, saturation = .25f), "Warm" to ImageAdjustments(warmth = .28f, saturation = .08f), "Mono" to ImageAdjustments(saturation = -1f, contrast = .08f)).forEach { (name, preset) ->
            OutlinedButton({ vm.updateAdjustments(preset); vm.commitCurrent() }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text(name, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, change: (Float) -> Unit, finish: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontSize = 12.sp); Text("${(value * 100).toInt()}", color = ImageMuted, fontSize = 11.sp) }
    Slider(value, change, valueRange = -1f..1f, onValueChangeFinished = finish, colors = SliderDefaults.colors(thumbColor = ImageGreen, activeTrackColor = ImageGreen))
}

@Composable
private fun ImageCropPanel(state: ImageWorkspaceState, vm: ImageWorkspaceViewModel) {
    var left by remember(state.selected?.capture?.id) { mutableFloatStateOf(state.edit.crop.left) }
    var top by remember(state.selected?.capture?.id) { mutableFloatStateOf(state.edit.crop.top) }
    var right by remember(state.selected?.capture?.id) { mutableFloatStateOf(state.edit.crop.right) }
    var bottom by remember(state.selected?.capture?.id) { mutableFloatStateOf(state.edit.crop.bottom) }
    var straight by remember { mutableFloatStateOf(state.edit.transform.straightenDegrees) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            EditPreview(state, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().padding(20.dp)) {
                val l = left * size.width; val t = top * size.height; val r = right * size.width; val b = bottom * size.height
                drawRect(Color.White, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), style = Stroke(2f))
                for (i in 1..2) {
                    drawLine(Color.White.copy(alpha = .75f), Offset(l + (r-l)*i/3f, t), Offset(l + (r-l)*i/3f, b), 1f)
                    drawLine(Color.White.copy(alpha = .75f), Offset(l, t + (b-t)*i/3f), Offset(r, t + (b-t)*i/3f), 1f)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            SmallAction("Rotate", Icons.Rounded.RotateRight, vm::rotate)
            SmallAction("Flip H", Icons.Rounded.SwapHoriz, vm::flipHorizontal)
            SmallAction("Flip V", Icons.Rounded.SwapVert, vm::flipVertical)
        }
        Text("Crop edges", Modifier.padding(horizontal = 18.dp), fontWeight = FontWeight.SemiBold)
        RangeSlider(left..right, { range -> left = range.start.coerceAtMost(.95f); right = range.endInclusive.coerceAtLeast(.05f) }, valueRange = 0f..1f, modifier = Modifier.padding(horizontal = 18.dp), colors = SliderDefaults.colors(activeTrackColor = ImageGreen, thumbColor = ImageGreen))
        RangeSlider(top..bottom, { range -> top = range.start.coerceAtMost(.95f); bottom = range.endInclusive.coerceAtLeast(.05f) }, valueRange = 0f..1f, modifier = Modifier.padding(horizontal = 18.dp), colors = SliderDefaults.colors(activeTrackColor = ImageGreen, thumbColor = ImageGreen))
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) { Text("Straighten", Modifier.weight(1f)); Text("${straight.toInt()}°", color = ImageMuted) }
        Slider(straight, { straight = it; vm.straighten(it) }, valueRange = -15f..15f, onValueChangeFinished = vm::commitCurrent, modifier = Modifier.padding(horizontal = 18.dp), colors = SliderDefaults.colors(activeTrackColor = ImageGreen, thumbColor = ImageGreen))
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ left=0f; top=0f; right=1f; bottom=1f; straight=0f; vm.reset() }, Modifier.weight(1f)) { Text("Reset") }
            Button({
                val safeLeft = left.coerceAtMost(right - .01f)
                val safeTop = top.coerceAtMost(bottom - .01f)
                vm.updateCrop(ImageCrop(safeLeft, safeTop, right.coerceAtLeast(safeLeft + .01f), bottom.coerceAtLeast(safeTop + .01f)))
                vm.setMode(ImageWorkspaceMode.EDIT)
            }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ImageGreen)) { Text("Apply") }
        }
    }
}

@Composable
private fun ImageMarkupPanel(state: ImageWorkspaceState, vm: ImageWorkspaceViewModel) {
    var tool by remember { mutableStateOf(ImageMarkupTool.HIGHLIGHTER) }
    var color by remember { mutableIntStateOf(AndroidColor.YELLOW) }
    var width by remember { mutableFloatStateOf(.02f) }
    var opacity by remember { mutableFloatStateOf(.4f) }
    var current by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var textDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().weight(1f).pointerInput(tool, color, width, opacity) {
                detectDragGestures(
                    onDragStart = { p -> current = listOf(p.x / size.width to p.y / size.height) },
                    onDrag = { change, _ -> current = current + (change.position.x / size.width to change.position.y / size.height) },
                    onDragEnd = {
                        if (current.isNotEmpty()) vm.addStroke(ImageMarkupStroke(tool, color, width, opacity, current))
                        current = emptyList()
                    },
                )
            }
        ) {
            EditPreview(state, Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                if (current.size > 1) {
                    val p = Path().apply { moveTo(current.first().first * size.width, current.first().second * size.height); current.drop(1).forEach { lineTo(it.first * size.width, it.second * size.height) } }
                    drawPath(p, Color(color).copy(alpha = opacity), style = Stroke(width * size.width))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            MarkupButton("Pen", Icons.Rounded.Draw, tool == ImageMarkupTool.PEN) { tool = ImageMarkupTool.PEN; opacity = 1f }
            MarkupButton("Highlight", Icons.Rounded.BorderColor, tool == ImageMarkupTool.HIGHLIGHTER) { tool = ImageMarkupTool.HIGHLIGHTER; opacity = .4f }
            MarkupButton("Shape", Icons.Rounded.CropSquare, tool == ImageMarkupTool.SHAPE) { tool = ImageMarkupTool.SHAPE }
            MarkupButton("Arrow", Icons.Rounded.ArrowOutward, tool == ImageMarkupTool.ARROW) { tool = ImageMarkupTool.ARROW }
            MarkupButton("Text", Icons.Rounded.TextFields, false) { textDialog = true }
            MarkupButton("Eraser", Icons.Rounded.AutoFixOff, false, vm::undo)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(AndroidColor.YELLOW, AndroidColor.rgb(0,160,100), AndroidColor.RED, AndroidColor.BLUE, AndroidColor.BLACK).forEach { c -> Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(if (color == c) 3.dp else 1.dp, Color.White, CircleShape).clickable { color = c }) }
        }
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) { Text("Thickness", Modifier.width(80.dp)); Slider(width, { width = it }, valueRange = .005f..08f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(activeTrackColor = ImageGreen, thumbColor = ImageGreen)); Text("${(width*1000).toInt()}", Modifier.width(30.dp)) }
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) { Text("Opacity", Modifier.width(80.dp)); Slider(opacity, { opacity = it }, valueRange = .1f..1f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(activeTrackColor = ImageGreen, thumbColor = ImageGreen)); Text("${(opacity*100).toInt()}%", Modifier.width(42.dp)) }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(vm::clearMarkup, Modifier.weight(1f)) { Text("Clear") }
            Button({ vm.setMode(ImageWorkspaceMode.EDIT) }, Modifier.weight(1.4f), colors = ButtonDefaults.buttonColors(containerColor = ImageGreen)) { Text("Apply markup") }
        }
    }
    if (textDialog) TextMarkupDialog(
        onDismiss = { textDialog = false },
        onSave = { text ->
            vm.addStroke(ImageMarkupStroke(ImageMarkupTool.TEXT, color, width, opacity, listOf(.12f to .18f), text))
            textDialog = false
        }
    )
}

@Composable
private fun TextMarkupDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = { OutlinedTextField(text, { text = it.take(80) }, singleLine = true, label = { Text("Text") }) },
        confirmButton = { TextButton({ if (text.isNotBlank()) onSave(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditPreview(state: ImageWorkspaceState, modifier: Modifier) {
    val item = state.selected
    var bitmap by remember(item?.capture?.id, state.edit) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item?.capture?.id, state.edit) {
        bitmap = withContext(Dispatchers.IO) {
            item?.let { runCatching { ImageRenderEngine.render(File(it.absolutePath), crop = state.edit.crop, transform = state.edit.transform, adjustments = state.edit.adjustments, strokes = state.edit.strokes, maxEdge = ImageRenderEngine.PREVIEW_EDGE) }.getOrNull() }
        }
    }
    // NOTE: the decoded bitmap is handed to Compose with asImageBitmap(), which wraps it without
    // copying. Recycling it here crashed the app with "Canvas: trying to use a recycled bitmap",
    // because a RenderNode display list can be replayed after onDispose runs. Since minSdk is 26,
    // bitmap memory lives on the Java heap and is reclaimed by GC, so no explicit recycle is needed.
    Box(modifier.background(Color(0xFFF0F2EF)), Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: CircularProgressIndicator(color = ImageGreen)
    }
}

@Composable
private fun ManagedBitmap(path: String, maxEdge: Int, content: @Composable (Bitmap?) -> Unit) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) { bitmap = withContext(Dispatchers.IO) { runCatching { ImageRenderEngine.decode(File(path), maxEdge) }.getOrNull() } }
    // Same reason as EditPreview: never recycle a bitmap that Compose may still draw.
    content(bitmap)
}

@Composable
private fun EditToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, click: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(11.dp)).background(if (selected) ImageMint else Color.Transparent).clickable(onClick = click).padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = ImageInk, modifier = Modifier.size(21.dp)); Text(label, fontSize = 10.sp)
    }
}

@Composable
private fun MarkupButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, click: () -> Unit) = EditToolButton(label, icon, selected, click)

@Composable
private fun SmallAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, click: () -> Unit) {
    Column(Modifier.clickable(onClick = click).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null); Text(label, fontSize = 10.sp) }
}
