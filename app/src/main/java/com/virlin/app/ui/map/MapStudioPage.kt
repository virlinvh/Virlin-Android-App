package com.virlin.app.ui.map

import android.graphics.Color as AndroidColor
import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min

private val studioPresets = listOf(
    "Virlin fresh" to listOf(0xFF24A46B, 0xFF618EE1, 0xFFD97867, 0xFF9A70CD, 0xFFE0A73A, 0xFF48A9AD),
    "Quiet earth" to listOf(0xFF70A985, 0xFF698BBD, 0xFFB78FA9, 0xFFD9A57E, 0xFF8D9E76, 0xFF8B80B6),
    "Deep focus" to listOf(0xFF238B78, 0xFF6689A8, 0xFFD47882, 0xFFA885B8, 0xFFBB9957, 0xFF658F77),
    "Bright paths" to listOf(0xFF78A85D, 0xFF658FCE, 0xFFDF8774, 0xFF846FBD, 0xFFD9B45C, 0xFF52A7A5),
)

@Composable
internal fun MapStudioPage(
    projectTitle: String, topics: List<MapTopic>, appearance: MapAppearance, hasChanges: Boolean,
    targetId: String?, search: String, pickedColor: Long?, accent: Color, textColor: Color,
    surface: Color, onSearch: (String) -> Unit, onTarget: (String) -> Unit,
    onColor: (Long) -> Unit, onAppearance: (MapAppearance) -> Unit,
    onBack: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var paletteSlot by rememberSaveable { mutableIntStateOf(0) }
    var scope by rememberSaveable { mutableIntStateOf(0) }
    var hue by rememberSaveable { mutableFloatStateOf(155f) }
    var saturation by rememberSaveable { mutableFloatStateOf(.72f) }
    var hex by rememberSaveable { mutableStateOf("24A46B") }
    val target = topics.firstOrNull { it.id == targetId }
    val chosenColor = pickedColor ?: 0xFF24A46BL
    val activePalette = appearance.customPalette.takeIf { it.isNotEmpty() }
        ?: studioPresets[appearance.paletteIndex.mod(studioPresets.size)].second
    val visibleTopics = remember(topics, search, targetId) {
        val matches = if (search.isBlank()) topics else topics.filter { it.title.contains(search, true) }
        (listOfNotNull(topics.firstOrNull { it.id == targetId }) + matches)
            .distinctBy { it.id }.take(8)
    }
    fun chooseColor(color: Long) {
        onColor(color)
        hex = "%06X".format(color and 0xFFFFFF)
    }
    Column(modifier.fillMaxSize().background(surface)) {
        Column(Modifier.background(Color.White)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 10.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to map") }
                Column {
                    Text("Map studio", color = textColor, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(projectTitle + " · Colours & surfaces", color = textColor.copy(alpha = .6f), fontSize = 12.sp)
                }
            }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp), shape = RoundedCornerShape(18.dp),
                color = accent.copy(alpha = .09f)) {
                Column(Modifier.padding(14.dp)) {
                    Text("LIVE MAP PREVIEW", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    val previewTopics = remember(topics) { layoutPreviewTopics(topics) }
                    val geometry = remember(previewTopics, appearance.layout, appearance.nodeWidths) {
                        arrange(previewTopics, appearance.layout, nodeWidths = appearance.nodeWidths)
                    }
                    Canvas(Modifier.fillMaxWidth().height(76.dp).padding(top = 7.dp)) {
                        val graphWidth = geometry.right - geometry.left
                        val graphHeight = geometry.bottom - geometry.top
                        val scale = min((size.width - 12.dp.toPx()) / graphWidth,
                            (size.height - 8.dp.toPx()) / graphHeight).coerceAtLeast(.01f)
                        drawIntoCanvas { composeCanvas ->
                            val native = composeCanvas.nativeCanvas
                            native.save()
                            native.translate((size.width - graphWidth * scale) / 2f,
                                (size.height - graphHeight * scale) / 2f)
                            native.scale(scale, scale)
                            native.translate(-geometry.left, -geometry.top)
                            paintMap(native, geometry, appearance,
                                RectF(geometry.left - 20f, geometry.top - 20f,
                                    geometry.right + 20f, geometry.bottom + 20f),
                                selectedId = null, showPreviewControls = false)
                            native.restore()
                        }
                    }
                    Text(if (tab == 1) appearance.boxFinish.label + " finish" else "Changes preview before saving",
                        color = textColor.copy(alpha = .55f), fontSize = 10.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 10.dp)) {
                listOf("Palettes", "Box finishes", "Item styling").forEachIndexed { index, label ->
                    Column(Modifier.weight(1f).clickable { tab = index }.padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = if (tab == index) accent else textColor.copy(alpha = .55f),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp)
                            .background(if (tab == index) accent else Color.Transparent))
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                0 -> {
                    item {
                        StudioHeading("Choose a palette",
                            "Give the whole map its mood. Create a colour for your palette or save it for later.",
                            textColor)
                    }
                    items(studioPresets.indices.toList()) { index ->
                        val chosen = appearance.customPalette.isEmpty() && appearance.paletteIndex == index
                        Surface(Modifier.fillMaxWidth().clickable {
                            onAppearance(appearance.copy(paletteIndex = index, customPalette = emptyList()))
                        }, shape = RoundedCornerShape(15.dp),
                            color = if (chosen) accent.copy(alpha = .1f) else Color.White,
                            border = androidx.compose.foundation.BorderStroke(if (chosen) 2.dp else 1.dp,
                                if (chosen) accent else textColor.copy(alpha = .12f))) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(studioPresets[index].first, Modifier.weight(1f), fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, color = textColor)
                                studioPresets[index].second.forEach { StudioDot(it, false, null) }
                            }
                        }
                    }
                    if (appearance.customPalette.isNotEmpty()) item {
                        Surface(shape = RoundedCornerShape(15.dp), color = accent.copy(alpha = .1f),
                            border = androidx.compose.foundation.BorderStroke(2.dp, accent)) {
                            Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("My palette", Modifier.weight(1f), color = textColor)
                                appearance.customPalette.forEach { StudioDot(it, false, null) }
                            }
                        }
                    }
                    item {
                        StudioHeading("Make a colour", "Tap the wheel, tune saturation, or enter a hex value.", textColor)
                        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
                            Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                StudioWheel(hue) { value ->
                                    hue = value
                                    chooseColor(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, .88f)).toLong() and 0xFFFFFFFFL)
                                }
                                Column(Modifier.weight(1f)) {
                                    Box(Modifier.fillMaxWidth().height(32.dp)
                                        .background(Color(chosenColor.toInt()), RoundedCornerShape(9.dp)))
                                    OutlinedTextField(hex, onValueChange = { value ->
                                        hex = value.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6).uppercase()
                                        if (hex.length == 6) hex.toLongOrNull(16)?.let { chooseColor(0xFF000000L or it) }
                                    }, label = { Text("HEX") }, prefix = { Text("#") },
                                        singleLine = true, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        Slider(saturation, onValueChange = {
                            saturation = it
                            chooseColor(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, .88f)).toLong() and 0xFFFFFFFFL)
                        })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                onAppearance(appearance.copy(savedColors = (appearance.savedColors + chosenColor).distinct().takeLast(24)))
                            }, modifier = Modifier.weight(1f)) { Text("Save colour", fontSize = 12.sp) }
                            Button(onClick = {
                                onAppearance(appearance.copy(customPalette = activePalette.toMutableList().also {
                                    it[paletteSlot.coerceIn(it.indices)] = chosenColor
                                }))
                            }, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                                Text("Use in palette", fontSize = 12.sp)
                            }
                        }
                        Text("Palette position", fontSize = 12.sp, color = textColor.copy(alpha = .6f))
                        Row { activePalette.forEachIndexed { index, color ->
                            StudioDot(color, paletteSlot == index) { paletteSlot = index; chooseColor(color) }
                        } }
                    }
                    item {
                        Text("My colours", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Row { appearance.savedColors.takeLast(8).forEach { color ->
                            StudioDot(color, chosenColor == color) { chooseColor(color) }
                        } }
                    }
                }
                1 -> {
                    item { StudioHeading("Choose a box finish",
                        "Pick a subtle surface for every map box. Your map keeps its current layout.",
                        textColor) }
                    val visibleFinishes = MapBoxFinish.entries.filter { it.isHandDrawn() } +
                        MapBoxFinish.entries.filterNot { it.isHandDrawn() }
                    items(visibleFinishes.chunked(2)) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { finish ->
                                val chosen = appearance.boxFinish == finish
                                Surface(Modifier.weight(1f).clickable {
                                    onAppearance(appearance.copy(boxFinish = finish))
                                }, shape = RoundedCornerShape(16.dp),
                                    color = if (chosen) accent.copy(alpha = .1f) else Color.White,
                                    border = androidx.compose.foundation.BorderStroke(if (chosen) 2.dp else 1.dp,
                                        if (chosen) accent else textColor.copy(alpha = .12f))) {
                                    Column(Modifier.padding(10.dp)) {
                                        val gradient = when (finish) {
                                            MapBoxFinish.GLASS -> listOf(Color.White, Color(0xFF9BD8B8), Color(0xFFE6F8E9))
                                            MapBoxFinish.PAPER -> listOf(Color(0xFFF7F3E7), Color(0xFFDFE9D7))
                                            MapBoxFinish.EMBOSSED -> listOf(Color.White, Color(0xFFB9D8C7))
                                            MapBoxFinish.FROSTED -> listOf(Color.White, Color(0xFFD9E7F4))
                                            MapBoxFinish.OUTLINE -> listOf(Color.White, Color.White)
                                            MapBoxFinish.HAND_DRAWN, MapBoxFinish.PENCIL_SKETCH,
                                            MapBoxFinish.DOUBLE_INK, MapBoxFinish.MARKER_EDGE -> listOf(Color.White, Color.White)
                                            else -> listOf(Color(0xFFE7F5EA), Color(0xFFCDE8D6))
                                        }
                                        Box(Modifier.fillMaxWidth().height(58.dp)
                                            .background(Brush.linearGradient(gradient), RoundedCornerShape(10.dp))
                                            .border(if (finish.isHandDrawn()) 0.dp else if (finish == MapBoxFinish.OUTLINE) 2.dp else 1.dp,
                                                accent.copy(alpha = .55f), RoundedCornerShape(10.dp))) {
                                            if (finish.isHandDrawn()) Canvas(Modifier.fillMaxSize()) {
                                                drawIntoCanvas { drawHandDrawnBox(it.nativeCanvas,
                                                    RectF(3f, 3f, size.width - 3f, size.height - 3f),
                                                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), finish) }
                                            }
                                            if (finish == MapBoxFinish.PENCIL_HATCH) Canvas(Modifier.fillMaxSize()) {
                                                val spacing = 7.dp.toPx()
                                                var x = -size.height
                                                while (x < size.width) {
                                                    drawLine(accent.copy(alpha = .24f),
                                                        Offset(x, size.height), Offset(x + size.height, 0f),
                                                        1.dp.toPx())
                                                    x += spacing
                                                }
                                            }
                                            if (finish == MapBoxFinish.LINEN || finish == MapBoxFinish.GRAIN ||
                                                finish == MapBoxFinish.PAPER) Canvas(Modifier.fillMaxSize()) {
                                                val step = if (finish == MapBoxFinish.GRAIN) 8.dp.toPx() else 6.dp.toPx()
                                                var x = step
                                                while (x < size.width) {
                                                    var y = step
                                                    while (y < size.height) {
                                                        if (finish == MapBoxFinish.GRAIN)
                                                            drawCircle(accent.copy(alpha = .22f), 1.dp.toPx(), Offset(x, y))
                                                        else drawLine(accent.copy(alpha = .16f), Offset(x, y),
                                                            Offset(x + 2.dp.toPx(), y), 1.dp.toPx())
                                                        y += step
                                                    }
                                                    if (finish == MapBoxFinish.LINEN)
                                                        drawLine(accent.copy(alpha = .1f), Offset(x, 0f),
                                                            Offset(x, size.height), 1.dp.toPx())
                                                    x += step
                                                }
                                            }
                                        }
                                        Text(finish.label, Modifier.padding(top = 8.dp), color = textColor,
                                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    item {
                        StudioHeading("Style what matters",
                            "Choose an item, then colour just that box or its entire branch.", textColor)
                        OutlinedTextField(search, onSearch, Modifier.fillMaxWidth().padding(top = 9.dp),
                            label = { Text("Find a project, workstream or task") }, singleLine = true)
                        if (search.isBlank() && topics.size > 8) Text(
                            "Showing nearby items. Search to find any task or subtask.",
                            color = textColor.copy(alpha = .55f), fontSize = 11.sp)
                    }
                    items(visibleTopics, key = { it.id }) { topic ->
                        val chosen = topic.id == targetId
                        Surface(Modifier.fillMaxWidth().clickable { onTarget(topic.id) },
                            shape = RoundedCornerShape(14.dp),
                            color = if (chosen) accent.copy(alpha = .1f) else Color.White,
                            border = androidx.compose.foundation.BorderStroke(if (chosen) 2.dp else 1.dp,
                                if (chosen) accent else textColor.copy(alpha = .12f))) {
                            Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(topic.title, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(topic.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontSize = 11.sp, color = textColor.copy(alpha = .55f))
                                }
                                (appearance.nodeColors[topic.id] ?: appearance.branchColors[topic.id])?.let {
                                    StudioDot(it, false, null)
                                }
                                if (chosen) Text("✓", color = accent)
                            }
                        }
                    }
                    if (target != null) item {
                        Text("Apply colour to", fontWeight = FontWeight.Bold, color = textColor)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("This item", "Entire branch").forEachIndexed { index, label ->
                                OutlinedButton(onClick = { scope = index }, modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (scope == index) accent.copy(alpha = .14f) else Color.White)) {
                                    Text(label, fontSize = 12.sp, color = if (scope == index) accent else textColor)
                                }
                            }
                        }
                        Row { (appearance.savedColors + activePalette).distinct().takeLast(7).forEach { color ->
                            StudioDot(color, color == pickedColor) {
                                chooseColor(color)
                                onAppearance(if (scope == 0)
                                    appearance.copy(nodeColors = appearance.nodeColors + (target.id to color))
                                else appearance.copy(branchColors = appearance.branchColors + (target.id to color)))
                            }
                        } }
                        OutlinedButton(onClick = {
                            onAppearance(if (scope == 0)
                                appearance.copy(nodeColors = appearance.nodeColors + (target.id to chosenColor))
                            else appearance.copy(branchColors = appearance.branchColors + (target.id to chosenColor)))
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Apply selected colour " + hex, fontSize = 12.sp)
                        }
                        Text("Box finish for this item", color = textColor, fontWeight = FontWeight.SemiBold)
                        MapBoxFinish.entries.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                row.forEach { finish ->
                                    OutlinedButton(onClick = {
                                        onAppearance(appearance.copy(nodeFinishes = appearance.nodeFinishes + (target.id to finish)))
                                    }, modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor =
                                            if (appearance.nodeFinishes[target.id] == finish) accent.copy(alpha = .15f)
                                            else Color.White)) { Text(finish.label, fontSize = 9.sp) }
                                }
                            }
                        }
                        OutlinedButton(onClick = {
                            onAppearance(appearance.copy(nodeColors = appearance.nodeColors - target.id,
                                branchColors = appearance.branchColors - target.id,
                                nodeFinishes = appearance.nodeFinishes - target.id))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Clear this item's overrides") }
                    }
                }
            }
        }
        AnimatedVisibility(hasChanges) {
            Surface(shadowElevation = 8.dp, color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = onSave, modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Save colours") }
                }
            }
        }
    }
}

@Composable
private fun StudioHeading(title: String, detail: String, textColor: Color) {
    Text(title, color = textColor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    Text(detail, color = textColor.copy(alpha = .64f), fontSize = 12.sp)
}

@Composable
private fun StudioDot(argb: Long, selected: Boolean, onClick: (() -> Unit)?) {
    Box(Modifier.padding(start = 3.dp, top = 4.dp, bottom = 4.dp).size(27.dp)
        .background(Color(argb.toInt()), CircleShape)
        .border(if (selected) 3.dp else 1.dp,
            if (selected) Color(0xFF182B20) else Color.Black.copy(alpha = .12f), CircleShape)
        .semantics { contentDescription = "Colour swatch " + argb.toString(16) }
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier))
}

@Composable
private fun StudioWheel(hue: Float, onHue: (Float) -> Unit) {
    val colors = remember { (0..12).map {
        Color(AndroidColor.HSVToColor(floatArrayOf(it * 30f, .85f, .9f)))
    } }
    Canvas(Modifier.size(110.dp).pointerInput(Unit) {
        detectTapGestures { point ->
            val angle = Math.toDegrees(atan2((point.x - size.width / 2f).toDouble(),
                (size.height / 2f - point.y).toDouble())).toFloat()
            onHue((angle + 360f) % 360f)
        }
    }.semantics { contentDescription = "Colour wheel" }) {
        val radius = size.minDimension / 2f - 10.dp.toPx()
        rotate(-90f) {
            drawCircle(Brush.sweepGradient(colors), radius = radius, style = Stroke(17.dp.toPx()))
        }
        val radians = Math.toRadians(hue.toDouble())
        val pin = Offset(center.x + (sin(radians) * radius).toFloat(),
            center.y - (cos(radians) * radius).toFloat())
        drawCircle(Color.White, 8.dp.toPx(), pin)
        drawCircle(Color.DarkGray, 8.dp.toPx(), pin, style = Stroke(1.dp.toPx()))
    }
}
