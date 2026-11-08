package com.virlin.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.flashcard.FlashcardColorMode
import com.virlin.app.data.flashcard.FlashcardCorners
import com.virlin.app.data.flashcard.FlashcardDeckStyle
import com.virlin.app.data.flashcard.FlashcardDesign
import com.virlin.app.data.flashcard.FlashcardGradientDirection
import com.virlin.app.data.flashcard.FlashcardTextSize
import com.virlin.app.data.flashcard.FlashcardTypeface
import kotlin.random.Random

/** The deck's style and project accent, read by every tile and practice card of one branch. */
internal class DeckLook(val style: FlashcardDeckStyle, val accent: Color)

internal val LocalDeckLook = staticCompositionLocalOf { DeckLook(FlashcardDeckStyle(), DefaultDeckAccent) }

internal val FlashcardDesign.dark: Boolean
    get() = this == FlashcardDesign.BOLD || this == FlashcardDesign.CHALKBOARD || this == FlashcardDesign.BLUEPRINT

/** A dark design's light partner, used wherever note formatting has to stay readable. */
internal fun FlashcardDesign.readable(): FlashcardDesign = when (this) {
    FlashcardDesign.BOLD -> FlashcardDesign.CLASSIC
    FlashcardDesign.CHALKBOARD -> FlashcardDesign.PAPER
    FlashcardDesign.BLUEPRINT -> FlashcardDesign.DOTTED
    else -> this
}

internal val FlashcardDesign.label: String get() = when (this) {
    FlashcardDesign.CLASSIC -> "Classic"
    FlashcardDesign.PAPER -> "Natural paper"
    FlashcardDesign.HAND_DRAWN -> "Hand-drawn"
    FlashcardDesign.STICKY -> "Sticky note"
    FlashcardDesign.INDEX -> "Index card"
    FlashcardDesign.KRAFT -> "Kraft"
    FlashcardDesign.WATERCOLOR -> "Watercolour"
    FlashcardDesign.DOTTED -> "Dotted"
    FlashcardDesign.POLAROID -> "Polaroid"
    FlashcardDesign.OUTLINE -> "Outline"
    FlashcardDesign.VINTAGE -> "Vintage"
    FlashcardDesign.BOLD -> "Bold"
    FlashcardDesign.CHALKBOARD -> "Chalkboard"
    FlashcardDesign.BLUEPRINT -> "Blueprint"
}

internal fun FlashcardCorners.tileShape() = RoundedCornerShape(when (this) {
    FlashcardCorners.ROUND -> 24.dp; FlashcardCorners.SOFT -> 14.dp; FlashcardCorners.SQUARE -> 4.dp
})

internal fun FlashcardCorners.cardShape() = RoundedCornerShape(when (this) {
    FlashcardCorners.ROUND -> 26.dp; FlashcardCorners.SOFT -> 16.dp; FlashcardCorners.SQUARE -> 6.dp
})

// Handwritten and display faces come through the notes' Google Fonts provider; until a family
// has arrived (or offline) the built-in fallback is drawn.
internal fun FlashcardTypeface.family(): FontFamily = when (this) {
    FlashcardTypeface.SERIF -> CardSerif
    FlashcardTypeface.SANS -> CardSans
    FlashcardTypeface.HANDWRITTEN -> NoteFonts.familyFor("Caveat") ?: FontFamily.Cursive
    FlashcardTypeface.MARKER -> NoteFonts.familyFor("Permanent Marker") ?: FontFamily.Cursive
    FlashcardTypeface.ELEGANT -> NoteFonts.familyFor("Playfair Display") ?: FontFamily.Serif
    FlashcardTypeface.POSTER -> NoteFonts.familyFor("Abril Fatface") ?: FontFamily.Serif
    FlashcardTypeface.ROUNDED -> NoteFonts.familyFor("Nunito") ?: FontFamily.SansSerif
    FlashcardTypeface.TYPEWRITER -> NoteFonts.familyFor("Courier Prime") ?: FontFamily.Monospace
}

internal val FlashcardTypeface.weight: FontWeight
    get() = if (this == FlashcardTypeface.SERIF || this == FlashcardTypeface.SANS) FontWeight.Medium else FontWeight.Normal

internal val FlashcardTypeface.label: String get() = when (this) {
    FlashcardTypeface.SERIF -> "Classic"
    FlashcardTypeface.SANS -> "Clean"
    FlashcardTypeface.HANDWRITTEN -> "Handwritten"
    FlashcardTypeface.MARKER -> "Marker"
    FlashcardTypeface.ELEGANT -> "Elegant"
    FlashcardTypeface.POSTER -> "Poster"
    FlashcardTypeface.ROUNDED -> "Rounded"
    FlashcardTypeface.TYPEWRITER -> "Typewriter"
}

/** Front text scale: the chosen size, evened out across faces of different natural size. */
internal fun FlashcardDeckStyle.frontScale(): Float {
    val face = when (typeface) {
        FlashcardTypeface.HANDWRITTEN -> 1.2f
        FlashcardTypeface.POSTER, FlashcardTypeface.MARKER -> .94f
        FlashcardTypeface.TYPEWRITER -> .92f
        else -> 1f
    }
    val size = when (textSize) {
        FlashcardTextSize.SMALL -> .86f; FlashcardTextSize.MEDIUM -> 1f; FlashcardTextSize.LARGE -> 1.18f
    }
    return face * size
}

/** A small, steady turn per card for the scattered layout. */
internal fun scatterTurn(id: String): Float = (Math.floorMod(id.hashCode(), 7) - 3) * .55f

private val Cream = Color(0xFFFBF7EE)
private val Kraft = Color(0xFFDDBF93)
private val KraftDeep = Color(0xFFCDAB7C)
private val WaterPaper = Color(0xFFFFFCF7)
private val Sepia = Color(0xFFF3E6C8)
private val SepiaDeep = Color(0xFFE7D4AC)
private val SepiaInk = Color(0xFF8B6A3E)
private val Slate = Color(0xFF34413A)
private val SlateDeep = Color(0xFF27312C)
private val Chalk = Color(0xFFF5F2EA)
private val Blueprint = Color(0xFF1F5288)
private val BlueprintDeep = Color(0xFF173F6C)

/** Every colour one card face needs, resolved once from the deck style. */
internal class CardPaint(
    val design: FlashcardDesign, val c1: Color, val c2: Color, val blob: Color,
    val top: Color, val bottom: Color, val ink: Color, val direction: FlashcardGradientDirection,
)

internal fun cardPaint(style: FlashcardDeckStyle, colorIndex: Int, accent: Color, large: Boolean,
                       design: FlashcardDesign = style.design): CardPaint {
    val shade = flashcardColor(colorIndex)
    val (c1, c2) = when (style.colorMode) {
        FlashcardColorMode.MIXED -> shade to shade
        FlashcardColorMode.FILL -> Color(style.color) to Color(style.color)
        FlashcardColorMode.GRADIENT -> Color(style.color) to Color(style.color2)
        FlashcardColorMode.PROJECT -> lerp(accent, Color.White, .72f).let { it to it }
    }
    val gradient = style.colorMode == FlashcardColorMode.GRADIENT
    val mid = lerp(c1, c2, .5f)
    val blob = if (gradient) c2 else hueShift(c1, 40f)
    val (top, bottom) = when (design) {
        FlashcardDesign.CLASSIC, FlashcardDesign.POLAROID ->
            if (gradient) c1 to c2 else lerp(c1, Color.White, .5f) to c1
        FlashcardDesign.PAPER -> lerp(Cream, c1, .22f) to lerp(Cream, c2, .22f)
        FlashcardDesign.HAND_DRAWN -> lerp(Color.White, c1, .45f) to lerp(Color.White, c2, .45f)
        FlashcardDesign.STICKY -> c1 to c2
        FlashcardDesign.INDEX -> lerp(Color.White, c1, .12f) to lerp(Color.White, c2, .12f)
        FlashcardDesign.KRAFT -> lerp(Kraft, c1, .18f) to lerp(KraftDeep, c2, .18f)
        FlashcardDesign.WATERCOLOR -> WaterPaper to WaterPaper
        FlashcardDesign.DOTTED -> lerp(Color.White, c1, .3f) to lerp(Color.White, c2, .3f)
        FlashcardDesign.OUTLINE -> Color.White to Color.White
        FlashcardDesign.VINTAGE -> lerp(Sepia, c1, .16f) to lerp(SepiaDeep, c2, .16f)
        FlashcardDesign.BOLD -> vivid(c1) to (if (gradient) vivid(c2) else lerp(vivid(c1), Color.Black, .18f))
        FlashcardDesign.CHALKBOARD -> Slate to SlateDeep
        FlashcardDesign.BLUEPRINT -> lerp(Blueprint, vivid(c1), .16f) to lerp(BlueprintDeep, vivid(c2), .16f)
    }
    val ink = when (design) {
        FlashcardDesign.CLASSIC, FlashcardDesign.STICKY, FlashcardDesign.DOTTED, FlashcardDesign.POLAROID ->
            lerp(mid, Color.Black, if (large) .82f else .80f)
        FlashcardDesign.PAPER -> Color(0xFF2B2620)
        FlashcardDesign.HAND_DRAWN -> Color(0xFF1E1E1E)
        FlashcardDesign.INDEX -> Color(0xFF22303A)
        FlashcardDesign.KRAFT -> Color(0xFF3A2A18)
        FlashcardDesign.WATERCOLOR -> Color(0xFF2A2A2A)
        FlashcardDesign.OUTLINE -> lerp(mid, Color.Black, .75f)
        FlashcardDesign.VINTAGE -> Color(0xFF3E2C16)
        FlashcardDesign.BOLD -> Color.White
        FlashcardDesign.CHALKBOARD -> lerp(Chalk, c1, .18f)
        FlashcardDesign.BLUEPRINT -> Color(0xFFF2F7FF)
    }
    val direction = if (gradient) style.direction
        else if (large) FlashcardGradientDirection.DIAGONAL else FlashcardGradientDirection.DOWN
    return CardPaint(design, c1, c2, blob, top, bottom, ink, direction)
}

private fun hueShift(color: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** A deep, saturated version of a (usually pastel) colour, for designs with white text. */
private fun vivid(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    val grey = hsv[1] < .06f
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], if (grey) 0f else .55f, if (grey) .32f else .58f)))
}

private fun linear(top: Color, bottom: Color, direction: FlashcardGradientDirection, size: Size): Brush =
    when (direction) {
        FlashcardGradientDirection.DOWN -> Brush.verticalGradient(listOf(top, bottom), 0f, size.height)
        FlashcardGradientDirection.ACROSS -> Brush.horizontalGradient(listOf(top, bottom), 0f, size.width)
        FlashcardGradientDirection.DIAGONAL -> Brush.linearGradient(listOf(top, bottom), Offset.Zero,
            Offset(size.width, size.height))
    }

/**
 * Paints a card face: its base colour and the design's texture (lines, dots, grain, frame,
 * tape...). Everything is built once per size in the draw cache, so flips and swipes, which only
 * move graphics layers, never rebuild it. The caller clips to [shape].
 */
internal fun Modifier.cardSurface(paint: CardPaint, shape: Shape, large: Boolean, seed: Int): Modifier =
    drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val base = linear(paint.top, paint.bottom, paint.direction, size)
        val w = size.width
        val h = size.height
        val dp = density
        val random = Random(seed)
        fun speckles(count: Int, color: Color, maxAlpha: Float): List<Pair<List<Offset>, Color>> =
            listOf(.35f, .65f, 1f).map { level ->
                List(count / 3) { Offset(random.nextFloat() * w, random.nextFloat() * h) } to
                    color.copy(alpha = maxAlpha * level)
            }
        val area = (w * h) / (24f * dp * 24f * dp)
        val decorate: DrawScope.() -> Unit = when (paint.design) {
            FlashcardDesign.CLASSIC -> ({
                if (large) {
                    drawOutline(outline, Color.White.copy(alpha = .7f), style = Stroke(1f * dp))
                    val inset = 7f * dp
                    drawRoundRect(Color.White.copy(alpha = .45f), topLeft = Offset(inset, inset),
                        size = Size(w - inset * 2, h - inset * 2),
                        cornerRadius = CornerRadius(20f * dp), style = Stroke(1f * dp))
                }
            })
            FlashcardDesign.PAPER -> {
                val dots = speckles(area.toInt().coerceIn(30, 240), Color(0xFF6B5A44), .14f)
                ({
                    dots.forEach { (points, color) -> drawPoints(points, PointMode.Points, color, 1.6f * dp, StrokeCap.Round) }
                    drawOutline(outline, Color(0x2A6B5A44), style = Stroke(1.5f * dp))
                })
            }
            FlashcardDesign.HAND_DRAWN -> {
                val first = wobblyFrame(w, h, 7f * dp, 1.3f * dp, Random(seed), dp)
                val second = wobblyFrame(w, h, 10f * dp, 1.6f * dp, Random(seed + 11), dp)
                ({
                    drawPath(first, paint.ink.copy(alpha = .72f), style = Stroke(1.6f * dp, cap = StrokeCap.Round))
                    drawPath(second, paint.ink.copy(alpha = .38f), style = Stroke(1.1f * dp, cap = StrokeCap.Round))
                })
            }
            FlashcardDesign.STICKY -> {
                ({
                    rotate(-3f, Offset(w / 2f, 0f)) {
                        drawRect(Color.White.copy(alpha = .55f), Offset(w * .32f, -4f * dp), Size(w * .36f, 20f * dp))
                    }
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .07f)),
                        h * .78f, h), Offset(0f, h * .78f), Size(w, h * .22f))
                })
            }
            FlashcardDesign.INDEX -> {
                val header = (if (large) 66f else 42f) * dp
                val step = (if (large) 30f else 21f) * dp
                ({
                    var y = header + step
                    while (y < h - 6f * dp) {
                        drawLine(Color(0xFF7FA7D6).copy(alpha = .42f), Offset(0f, y), Offset(w, y), .9f * dp)
                        y += step
                    }
                    drawLine(Color(0xFFE57373).copy(alpha = .8f), Offset(0f, header), Offset(w, header), 1.3f * dp)
                })
            }
            FlashcardDesign.KRAFT -> {
                val dark = speckles(area.toInt().coerceIn(45, 320), Color(0xFF5A3E1E), .22f)
                val light = speckles((area / 3).toInt().coerceIn(12, 90), Color(0xFFFFF4E0), .4f)
                ({
                    (dark + light).forEach { (points, color) ->
                        drawPoints(points, PointMode.Points, color, 1.7f * dp, StrokeCap.Round)
                    }
                    drawOutline(outline, Color(0x335A3E1E), style = Stroke(1.5f * dp))
                })
            }
            FlashcardDesign.WATERCOLOR -> {
                val radius = minOf(w, h)
                val blobs = listOf(paint.c1, paint.blob, lerp(paint.c1, paint.blob, .5f)).map { color ->
                    val center = Offset(w * (.15f + random.nextFloat() * .7f), h * (.12f + random.nextFloat() * .76f))
                    val r = radius * (.55f + random.nextFloat() * .3f)
                    Triple(center, r, Brush.radialGradient(listOf(color.copy(alpha = .62f), color.copy(alpha = .28f),
                        Color.Transparent), center, r))
                }
                ({ blobs.forEach { (center, r, brush) -> drawCircle(brush, r, center) } })
            }
            FlashcardDesign.DOTTED -> {
                val step = (if (large) 18f else 13f) * dp
                val points = buildList {
                    var y = step * .75f
                    while (y < h) { var x = step * .75f; while (x < w) { add(Offset(x, y)); x += step }; y += step }
                }
                ({ drawPoints(points, PointMode.Points, paint.ink.copy(alpha = .16f), 2.2f * dp, StrokeCap.Round) })
            }
            FlashcardDesign.POLAROID -> {
                val side = (if (large) 14f else 9f) * dp
                val bottom = (if (large) 50f else 34f) * dp
                val panelSize = Size(w - side * 2, (h - side - bottom).coerceAtLeast(0f))
                val panel = linear(lerp(paint.c1, Color.White, if (paint.c1 == paint.c2) .5f else 0f), paint.c2,
                    paint.direction, panelSize)
                ({
                    translate(side, side) {
                        drawRoundRect(panel, size = panelSize, cornerRadius = CornerRadius(3f * dp))
                    }
                    drawOutline(outline, Color(0x1A000000), style = Stroke(1.5f * dp))
                })
            }
            FlashcardDesign.OUTLINE -> {
                val edge = lerp(lerp(paint.c1, paint.c2, .5f), Color.Black, .3f)
                ({ drawOutline(outline, edge, style = Stroke(4.5f * dp)) })
            }
            FlashcardDesign.VINTAGE -> {
                val vignette = Brush.radialGradient(listOf(Color.Transparent, SepiaInk.copy(alpha = .22f)),
                    Offset(w / 2f, h / 2f), maxOf(w, h) * .78f)
                ({
                    drawRect(vignette)
                    listOf(7f, 10.5f).forEach { inset ->
                        drawRoundRect(SepiaInk.copy(alpha = .55f), topLeft = Offset(inset * dp, inset * dp),
                            size = Size(w - inset * 2 * dp, h - inset * 2 * dp),
                            cornerRadius = CornerRadius((16f - inset) * dp), style = Stroke(.9f * dp))
                    }
                })
            }
            FlashcardDesign.BOLD -> {
                val sheen = Brush.linearGradient(listOf(Color.White.copy(alpha = .14f), Color.Transparent),
                    Offset.Zero, Offset(w * .6f, h * .6f))
                ({ drawRect(sheen) })
            }
            FlashcardDesign.CHALKBOARD -> {
                val smudges = List(2) {
                    val center = Offset(w * (.2f + random.nextFloat() * .6f), h * (.2f + random.nextFloat() * .6f))
                    center to Brush.radialGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent),
                        center, minOf(w, h) * .6f)
                }
                val dust = speckles((area / 2).toInt().coerceIn(15, 120), Color.White, .1f)
                ({
                    smudges.forEach { (_, brush) -> drawRect(brush) }
                    dust.forEach { (points, color) -> drawPoints(points, PointMode.Points, color, 1.4f * dp, StrokeCap.Round) }
                    drawOutline(outline, Color(0xFF8C6A45), style = Stroke(9f * dp))
                })
            }
            FlashcardDesign.BLUEPRINT -> {
                val step = (if (large) 16f else 12f) * dp
                ({
                    var i = 1
                    var x = step
                    while (x < w) {
                        drawLine(Color.White.copy(alpha = if (i % 4 == 0) .16f else .07f), Offset(x, 0f), Offset(x, h),
                            (if (i % 4 == 0) 1f else .7f) * dp)
                        x += step; i++
                    }
                    i = 1
                    var y = step
                    while (y < h) {
                        drawLine(Color.White.copy(alpha = if (i % 4 == 0) .16f else .07f), Offset(0f, y), Offset(w, y),
                            (if (i % 4 == 0) 1f else .7f) * dp)
                        y += step; i++
                    }
                    drawOutline(outline, Color.White.copy(alpha = .35f), style = Stroke(2f * dp))
                })
            }
        }
        val fill = if (paint.design == FlashcardDesign.WATERCOLOR || paint.design == FlashcardDesign.POLAROID ||
            paint.design == FlashcardDesign.OUTLINE) null else base
        onDrawBehind {
            if (fill != null) drawOutline(outline, fill)
            else drawOutline(outline, paint.top)
            decorate()
        }
    }

/** A frame that looks drawn by hand: points along an inset rectangle, nudged and smoothed. */
private fun wobblyFrame(w: Float, h: Float, inset: Float, wobble: Float, random: Random, dp: Float): Path {
    val step = 16f * dp
    val corners = listOf(Offset(inset, inset), Offset(w - inset, inset), Offset(w - inset, h - inset), Offset(inset, h - inset))
    val points = buildList {
        corners.indices.forEach { side ->
            val a = corners[side]
            val b = corners[(side + 1) % 4]
            val n = (((b - a).getDistance()) / step).toInt().coerceAtLeast(2)
            for (i in 0 until n) {
                val t = i / n.toFloat()
                add(Offset(a.x + (b.x - a.x) * t + (random.nextFloat() * 2 - 1) * wobble,
                    a.y + (b.y - a.y) * t + (random.nextFloat() * 2 - 1) * wobble))
            }
        }
    }
    return Path().apply {
        val start = Offset((points.last().x + points[0].x) / 2, (points.last().y + points[0].y) / 2)
        moveTo(start.x, start.y)
        points.forEachIndexed { i, p ->
            val next = points[(i + 1) % points.size]
            quadraticBezierTo(p.x, p.y, (p.x + next.x) / 2, (p.y + next.y) / 2)
        }
        // A slight overshoot past the start, as a pen stroke would.
        lineTo(start.x + 3f * dp, start.y + wobble)
    }
}

private val Swatches = listOf(
    0xFFFFD8A8, 0xFFFFC48C, 0xFFFFF0A6, 0xFFE2F2A0, 0xFFBFEBCB, 0xFFCFE0C3, 0xFFB5E8E0, 0xFFBFE0FF,
    0xFFC7CEFF, 0xFFD9C8FF, 0xFFEBC6F2, 0xFFFFC9DE, 0xFFFFB8B8, 0xFFFFB59E, 0xFFEBDDC5, 0xFFE3E1DC,
    0xFFC9D3DC, 0xFFFFFFFF,
).map { it.toInt() }

private val SheetInk = Color(0xFF17251D)
private val SheetMuted = Color(0xFF69796E)

/**
 * The deck's design room: a live preview and, under it, every setting as a sideways strip like the
 * app's other tool strips. Each change applies at once to this branch's deck only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlashcardDeckStyleSheet(style: FlashcardDeckStyle, accent: Color, branch: String,
    samples: List<Pair<String, Int>>, onChange: (FlashcardDeckStyle) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).testTag("flashcard_style_sheet")) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Card design", color = SheetInk, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        fontFamily = CardSans)
                    Text("Only for $branch", color = SheetMuted, fontSize = 12.sp, fontFamily = CardSans,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onChange(FlashcardDeckStyle()) }, enabled = style != FlashcardDeckStyle(),
                    modifier = Modifier.testTag("flashcard_style_reset")) {
                    Text("Reset", color = if (style != FlashcardDeckStyle()) accent else SheetMuted,
                        fontFamily = CardSans, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("flashcard_style_close")) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close card design", tint = SheetInk)
                }
            }
            StylePreview(style, accent, samples)
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)) {
                StyleSection("CARD STYLE") {
                    FlashcardDesign.values().forEach { design ->
                        DesignThumb(style.copy(design = design), accent, design == style.design) {
                            onChange(style.copy(design = design))
                        }
                    }
                }
                StyleSection("COLOUR") {
                    listOf(FlashcardColorMode.MIXED to "Mixed", FlashcardColorMode.FILL to "Fill",
                        FlashcardColorMode.GRADIENT to "Gradient", FlashcardColorMode.PROJECT to "Project")
                        .forEach { (mode, label) ->
                            StyleChip(label, style.colorMode == mode, accent, "flashcard_colormode_${mode.name.lowercase()}") {
                                onChange(style.copy(colorMode = mode))
                            }
                        }
                }
                Text(when (style.colorMode) {
                    FlashcardColorMode.MIXED -> "Every card keeps its own shade."
                    FlashcardColorMode.FILL -> "Every card in one colour."
                    FlashcardColorMode.GRADIENT -> "Every card blends from one colour into another."
                    FlashcardColorMode.PROJECT -> "Cards follow the project colour."
                }, color = SheetMuted, fontSize = 12.sp, fontFamily = CardSans,
                    modifier = Modifier.padding(start = 20.dp, top = 2.dp))
                if (style.colorMode == FlashcardColorMode.FILL || style.colorMode == FlashcardColorMode.GRADIENT) {
                    StyleSection(if (style.colorMode == FlashcardColorMode.GRADIENT) "FROM" else "FILL") {
                        Swatches.forEachIndexed { i, color ->
                            Swatch(color, style.color == color, accent, "flashcard_swatch_$i") {
                                onChange(style.copy(color = color))
                            }
                        }
                    }
                }
                if (style.colorMode == FlashcardColorMode.GRADIENT) {
                    StyleSection("TO") {
                        Swatches.forEachIndexed { i, color ->
                            Swatch(color, style.color2 == color, accent, "flashcard_swatch2_$i") {
                                onChange(style.copy(color2 = color))
                            }
                        }
                    }
                    StyleSection("DIRECTION") {
                        listOf(FlashcardGradientDirection.DOWN to "Top to bottom",
                            FlashcardGradientDirection.DIAGONAL to "Diagonal",
                            FlashcardGradientDirection.ACROSS to "Side to side").forEach { (direction, label) ->
                            StyleChip(label, style.direction == direction, accent,
                                "flashcard_direction_${direction.name.lowercase()}") {
                                onChange(style.copy(direction = direction))
                            }
                        }
                    }
                }
                StyleSection("TYPEFACE") {
                    FlashcardTypeface.values().forEach { face ->
                        StyleChip(face.label, style.typeface == face, accent,
                            "flashcard_typeface_${face.name.lowercase()}", sample = face) {
                            onChange(style.copy(typeface = face))
                        }
                    }
                }
                StyleSection("TEXT") {
                    listOf(FlashcardTextSize.SMALL to "Small", FlashcardTextSize.MEDIUM to "Medium",
                        FlashcardTextSize.LARGE to "Large").forEach { (size, label) ->
                        StyleChip(label, style.textSize == size, accent, "flashcard_size_${size.name.lowercase()}") {
                            onChange(style.copy(textSize = size))
                        }
                    }
                    StyleDivider()
                    StyleChip("Left", !style.centered, accent, "flashcard_align_left") { onChange(style.copy(centered = false)) }
                    StyleChip("Centre", style.centered, accent, "flashcard_align_center") { onChange(style.copy(centered = true)) }
                }
                StyleSection("SHAPE & LAYOUT") {
                    listOf(FlashcardCorners.ROUND to "Round", FlashcardCorners.SOFT to "Soft",
                        FlashcardCorners.SQUARE to "Square").forEach { (corners, label) ->
                        StyleChip(label, style.corners == corners, accent, "flashcard_corners_${corners.name.lowercase()}") {
                            onChange(style.copy(corners = corners))
                        }
                    }
                    StyleDivider()
                    StyleChip("Scattered", style.scattered, accent, "flashcard_scattered") {
                        onChange(style.copy(scattered = !style.scattered))
                    }
                    StyleChip("Number & reviews", style.showDetails, accent, "flashcard_details") {
                        onChange(style.copy(showDetails = !style.showDetails))
                    }
                }
            }
        }
    }
}

@Composable
private fun StylePreview(style: FlashcardDeckStyle, accent: Color, samples: List<Pair<String, Int>>) {
    val shown = samples.take(2).ifEmpty { listOf("Photosynthesis" to 0, "What does a closure capture?" to 1) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
        shown.forEachIndexed { i, (text, colorIndex) ->
            val paint = remember(style, colorIndex, accent) { cardPaint(style, colorIndex, accent, large = false) }
            val shape = style.corners.tileShape()
            Box(Modifier.width(132.dp).height(150.dp)
                .graphicsLayer { rotationZ = if (style.scattered) (if (i == 0) -1.6f else 1.4f) else 0f }
                .shadow(5.dp, shape).clip(shape).cardSurface(paint, shape, large = false, seed = colorIndex)
                .testTag("flashcard_style_preview_$i")) {
                Text(text, color = paint.ink, fontFamily = style.typeface.family(), fontWeight = style.typeface.weight,
                    fontSize = (16f * style.frontScale()).sp, lineHeight = (21f * style.frontScale()).sp,
                    textAlign = if (style.centered) TextAlign.Center else TextAlign.Start, maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 22.dp))
                if (style.showDetails) Text("No. ${(i + 1).toString().padStart(2, '0')}",
                    color = paint.ink.copy(alpha = .5f), fontFamily = CardSans, fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
            }
        }
    }
}

@Composable
private fun StyleSection(title: String, content: @Composable () -> Unit) {
    Text(title, color = SheetMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
        fontFamily = CardSans, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
    NoteFeatheredRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), peek = true) {
        Spacer(Modifier.width(4.dp))
        content()
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun StyleDivider() {
    Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(24.dp).background(SheetInk.copy(alpha = .12f)))
}

@Composable
private fun DesignThumb(style: FlashcardDeckStyle, accent: Color, selected: Boolean, onClick: () -> Unit) {
    val paint = remember(style, accent) { cardPaint(style, 3, accent, large = false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.padding(end = 10.dp).width(70.dp)
        .clip(RoundedCornerShape(14.dp))
        .clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected; contentDescription = "${style.design.label} card style" }
        .testTag("flashcard_design_${style.design.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 4.dp).size(width = 62.dp, height = 78.dp)
            .border(if (selected) 2.5.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(15.dp))
            .padding(4.dp).clip(shape).cardSurface(paint, shape, large = false, seed = 3),
            contentAlignment = Alignment.Center) {
            Text("Aa", color = paint.ink, fontFamily = style.typeface.family(), fontWeight = style.typeface.weight,
                fontSize = 17.sp)
        }
        Text(style.design.label, color = if (selected) accent else SheetInk, fontSize = 10.sp, fontFamily = CardSans,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
    }
}

@Composable
private fun StyleChip(label: String, selected: Boolean, accent: Color, tag: String,
                      sample: FlashcardTypeface? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.padding(end = 7.dp).height(38.dp).clip(shape)
        .background(if (selected) accent.copy(alpha = .12f) else Color.White)
        .border(1.dp, if (selected) accent.copy(alpha = .45f) else SheetInk.copy(alpha = .1f), shape)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected }
        .testTag(tag)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (sample != null) {
            Text("Aa", color = if (selected) accent else SheetInk, fontFamily = sample.family(),
                fontWeight = sample.weight, fontSize = 17.sp)
            Spacer(Modifier.width(7.dp))
        } else if (selected) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, color = if (selected) accent else SheetInk, fontFamily = CardSans, fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun Swatch(color: Int, selected: Boolean, accent: Color, tag: String, onClick: () -> Unit) {
    val fill = Color(color)
    Box(Modifier.padding(end = 8.dp).size(40.dp)
        .border(if (selected) 2.5.dp else 0.dp, if (selected) accent else Color.Transparent, CircleShape)
        .padding(4.dp).clip(CircleShape).background(fill)
        .border(1.dp, SheetInk.copy(alpha = .1f), CircleShape)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { this.selected = selected; contentDescription = "Colour ${Integer.toHexString(color).uppercase().takeLast(6)}" }
        .testTag(tag), contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null,
            tint = lerp(fill, Color.Black, .7f), modifier = Modifier.size(16.dp))
    }
}
