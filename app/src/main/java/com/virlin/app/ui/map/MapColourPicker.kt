package com.virlin.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.virlin.app.ui.components.SafeDialog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private val swatchHues = listOf(0f, 18f, 35f, 50f, 75f, 120f, 160f, 185f, 205f, 230f, 265f, 300f)
private val swatchShades = listOf(.22f to 1f, .42f to 1f, .65f to .96f, .85f to .86f, .88f to .66f, .82f to .44f)
private val swatchGrid: List<List<Long>> = swatchShades.map { (s, v) -> swatchHues.map { hsvToArgb(it, s, v) } } +
    listOf((0 until swatchHues.size).map { i -> hsvToArgb(0f, 0f, 1f - i / (swatchHues.size - 1f)) })

/**
 * The one colour picker used everywhere in the colour studio: a hue ring around a
 * saturation/brightness square, HSB sliders, or a swatch grid, plus hex entry and the user's
 * saved colours. It edits a local colour only; nothing changes until "Use colour".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MapColourPicker(
    title: String,
    initial: Long,
    accent: Color,
    textColor: Color,
    palette: List<Long>,
    savedColors: List<Long>,
    onSaveColour: (Long) -> Unit,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val start = remember { argbToHsv(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var brightness by remember { mutableFloatStateOf(start[2]) }
    // A typed or tapped colour is kept exactly; HSV rounding must not shift a pasted hex by one.
    var exact by remember { mutableStateOf<Long?>(initial) }
    var hex by remember { mutableStateOf(hexOf(initial)) }
    var mode by remember { mutableIntStateOf(0) }
    val colour = exact ?: hsvToArgb(hue, saturation, brightness)
    fun choose(c: Long) {
        val hsv = argbToHsv(c)
        if (hsv[1] > 0f) hue = hsv[0]
        saturation = hsv[1]; brightness = hsv[2]; exact = c; hex = hexOf(c)
    }
    fun tune(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h; saturation = s; brightness = v; exact = null; hex = hexOf(hsvToArgb(h, s, v))
    }
    // The wheel and sliders consume their own drags, so the card can still scroll on short screens.
    SafeDialog(onDismissRequest = onDismiss, properties = DialogProperties(), scrollContent = true) {
        Surface(shape = RoundedCornerShape(26.dp), color = Color.White) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(Color(colour.toInt()), RoundedCornerShape(14.dp))
                        .border(1.dp, Color.Black.copy(alpha = .1f), RoundedCornerShape(14.dp)))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(title, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("#" + hexOf(colour), color = textColor.copy(alpha = .6f), fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().background(textColor.copy(alpha = .05f), RoundedCornerShape(12.dp)).padding(3.dp)) {
                    listOf("Wheel", "Sliders", "Swatches").forEachIndexed { index, label ->
                        Box(Modifier.weight(1f)
                            .background(if (mode == index) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { mode = index }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = if (mode == index) accent else textColor.copy(alpha = .6f))
                        }
                    }
                }
                when (mode) {
                    0 -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        HueRingSquare(hue, saturation, brightness,
                            onHue = { tune(h = it) }, onSv = { s, v -> tune(s = s, v = v) })
                    }
                    1 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientBar("Hue", hue / 360f, (0..12).map { Color(hsvToArgb(it * 30f, 1f, 1f).toInt()) },
                            "${hue.toInt()}°") { tune(h = it * 360f) }
                        GradientBar("Saturation", saturation,
                            listOf(Color(hsvToArgb(hue, 0f, brightness).toInt()), Color(hsvToArgb(hue, 1f, brightness).toInt())),
                            "${(saturation * 100).toInt()}%") { tune(s = it) }
                        GradientBar("Brightness", brightness,
                            listOf(Color.Black, Color(hsvToArgb(hue, saturation, 1f).toInt())),
                            "${(brightness * 100).toInt()}%") { tune(v = it) }
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        swatchGrid.forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { c ->
                                    Box(Modifier.weight(1f).aspectRatio(1f)
                                        .background(Color(c.toInt()), RoundedCornerShape(6.dp))
                                        .border(if (c == colour) 2.dp else 0.5.dp,
                                            if (c == colour) textColor else Color.Black.copy(alpha = .08f), RoundedCornerShape(6.dp))
                                        .semantics { contentDescription = "Colour #" + hexOf(c) }
                                        .clickable { choose(c) })
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(hex, onValueChange = { value ->
                    hex = value.trim().removePrefix("#").filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
                        .take(6).uppercase()
                    if (hex.length == 6 || hex.length == 3) parseHexColor(hex)?.let { c ->
                        val hsv = argbToHsv(c)
                        if (hsv[1] > 0f) hue = hsv[0]
                        saturation = hsv[1]; brightness = hsv[2]; exact = c
                    }
                }, label = { Text("Hex") }, prefix = { Text("#") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth())
                PickerDots("This palette", palette, colour, textColor, ::choose)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("My colours", color = textColor.copy(alpha = .6f), fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onSaveColour(colour) }, enabled = colour !in savedColors) {
                        Text(if (colour in savedColors) "Saved" else "+ Save this colour", fontSize = 12.sp, color = accent)
                    }
                }
                if (savedColors.isEmpty()) Text("Colours you save appear here for every map.",
                    color = textColor.copy(alpha = .5f), fontSize = 11.sp)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    savedColors.takeLast(16).forEach { c -> PickerDot(c, c == colour, textColor) { choose(c) } }
                }
                if (secondaryLabel != null && onSecondary != null)
                    TextButton(onClick = onSecondary) { Text(secondaryLabel, color = Color(0xFFB4483C), fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel", maxLines = 1) }
                    Button(onClick = { onPick(colour) }, modifier = Modifier.weight(1.4f),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Use colour", maxLines = 1) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerDots(label: String, colours: List<Long>, chosen: Long, textColor: Color, onPick: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = textColor.copy(alpha = .6f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            colours.forEach { c -> PickerDot(c, c == chosen, textColor) { onPick(c) } }
        }
    }
}

@Composable
private fun PickerDot(argb: Long, selected: Boolean, textColor: Color, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).background(Color(argb.toInt()), CircleShape)
        .border(if (selected) 2.5.dp else 1.dp, if (selected) textColor else Color.Black.copy(alpha = .12f), CircleShape)
        .semantics { contentDescription = "Colour #" + hexOf(argb) }
        .clickable(onClick = onClick))
}

/** Hue on the ring, saturation left→right and brightness top→bottom in the square. */
@Composable
private fun HueRingSquare(
    hue: Float, saturation: Float, brightness: Float,
    onHue: (Float) -> Unit, onSv: (Float, Float) -> Unit,
) {
    val currentOnHue by rememberUpdatedState(onHue)
    val currentOnSv by rememberUpdatedState(onSv)
    val ring = remember { (0..12).map { Color(hsvToArgb(it * 30f, 1f, 1f).toInt()) } }
    Canvas(Modifier.size(200.dp).semantics { contentDescription = "Colour wheel" }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val centre = Offset(size.width / 2f, size.height / 2f)
            val thickness = 22.dp.toPx()
            val outer = min(size.width, size.height) / 2f - 4.dp.toPx()
            val inner = outer - thickness - 8.dp.toPx()
            val half = inner / sqrt(2f)
            val onRing = (down.position - centre).getDistance() > inner
            fun handle(p: Offset) {
                if (onRing) {
                    val angle = Math.toDegrees(atan2((p.y - centre.y).toDouble(), (p.x - centre.x).toDouble())).toFloat()
                    currentOnHue((angle + 360f) % 360f)
                } else {
                    currentOnSv(((p.x - (centre.x - half)) / (2 * half)).coerceIn(0f, 1f),
                        (1f - (p.y - (centre.y - half)) / (2 * half)).coerceIn(0f, 1f))
                }
            }
            handle(down.position)
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                handle(change.position)
                change.consume()
            }
        }
    }) {
        val thickness = 22.dp.toPx()
        val outer = min(size.width, size.height) / 2f - 4.dp.toPx()
        val middle = outer - thickness / 2f
        drawCircle(Brush.sweepGradient(ring, center), radius = middle, style = Stroke(thickness))
        val inner = outer - thickness - 8.dp.toPx()
        val half = inner / sqrt(2f)
        val topLeft = Offset(center.x - half, center.y - half)
        val square = Size(half * 2, half * 2)
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hsvToArgb(hue, 1f, 1f).toInt())),
            startX = topLeft.x, endX = topLeft.x + square.width), topLeft, square)
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black),
            startY = topLeft.y, endY = topLeft.y + square.height), topLeft, square)
        val radians = Math.toRadians(hue.toDouble())
        val pin = Offset(center.x + (cos(radians) * middle).toFloat(), center.y + (sin(radians) * middle).toFloat())
        drawCircle(Color.White, 10.dp.toPx(), pin, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = .35f), 11.5.dp.toPx(), pin, style = Stroke(1.dp.toPx()))
        val dot = Offset(topLeft.x + saturation * square.width, topLeft.y + (1f - brightness) * square.height)
        drawCircle(Color.White, 8.dp.toPx(), dot, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = .35f), 9.5.dp.toPx(), dot, style = Stroke(1.dp.toPx()))
    }
}

@Composable
private fun GradientBar(label: String, value: Float, colours: List<Color>, readout: String, onValue: (Float) -> Unit) {
    val currentOnValue by rememberUpdatedState(onValue)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF55606B), modifier = Modifier.weight(1f))
            Text(readout, fontSize = 11.sp, color = Color(0xFF55606B))
        }
        Canvas(Modifier.fillMaxWidth().height(30.dp).semantics { contentDescription = "$label $readout" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val inset = 15.dp.toPx()
                    fun handle(x: Float) = currentOnValue(((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f))
                    handle(down.position.x)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        handle(change.position.x)
                        change.consume()
                    }
                }
            }) {
            val inset = 15.dp.toPx()
            val trackHeight = 14.dp.toPx()
            drawRoundRect(Brush.horizontalGradient(colours, startX = inset, endX = size.width - inset),
                Offset(inset, (size.height - trackHeight) / 2f), Size(size.width - 2 * inset, trackHeight),
                androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f))
            val x = inset + value.coerceIn(0f, 1f) * (size.width - 2 * inset)
            drawCircle(Color.White, 11.dp.toPx(), Offset(x, size.height / 2f))
            drawCircle(Color.Black.copy(alpha = .3f), 11.dp.toPx(), Offset(x, size.height / 2f), style = Stroke(1.dp.toPx()))
        }
    }
}