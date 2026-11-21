package com.virlin.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.virlin.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The colourful Now palette (coral · purple · teal · yellow on deep navy ink). */
internal object Vivid {
    val Ink = Color(0xFF1E1442)
    val InkSoft = Color(0xFF4A3F6B)
    val Purple = Color(0xFF7C3AED)
    val PurpleText = Color(0xFF5B3DB5)
    val Lavender = Color(0xFFF3EDFF)
    val LavenderBorder = Color(0xFFC6B4F8)
    val Coral = Color(0xFFFF5C6C)
    val CoralLight = Color(0xFFFF8F8F)
    val CoralDeep = Color(0xFFC92A4E)
    val Pink = Color(0xFFFF7EB3)
    val Teal = Color(0xFF14B8A6)
    val TealLight = Color(0xFF5EEAD4)
    val Mint = Color(0xFFD3F8E2)
    val Green = Color(0xFF22C55E)
    val Yellow = Color(0xFFFFD84D)
    val Amber = Color(0xFFFFB020)
}

/** DM Sans is one variable file, so each weight must set the `wght` axis explicitly. */
@OptIn(ExperimentalTextApi::class)
private fun dmSans(weight: FontWeight) = Font(
    R.font.dm_sans_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

internal val VirlinDmSans = FontFamily(
    dmSans(FontWeight.Normal),
    dmSans(FontWeight.Medium),
    dmSans(FontWeight.SemiBold),
    dmSans(FontWeight.Bold),
    dmSans(FontWeight.ExtraBold),
    dmSans(FontWeight.Black),
)

/** Ambient motion runs only with system animations on and while the screen is started. */
@Composable
internal fun rememberAmbientMotion(): Boolean {
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    return android.animation.ValueAnimator.areAnimatorsEnabled() &&
        state.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
}

/** A 0..2π loop for slow colour drift; a fixed resting phase when motion is off. */
@Composable
internal fun rememberDriftPhase(animate: Boolean, periodMs: Int, label: String, resting: Float = 0.9f): State<Float> =
    if (animate) {
        rememberInfiniteTransition(label = label).animateFloat(
            0f, (2 * PI).toFloat(),
            infiniteRepeatable(tween(periodMs, easing = LinearEasing)),
            label = "${label}Phase",
        )
    } else {
        remember { mutableFloatStateOf(resting) }
    }

internal fun DrawScope.glow(brush: Brush, radius: Float, x: Float, y: Float) {
    translate(x, y) { drawCircle(brush, radius = radius, center = Offset.Zero) }
}

internal fun radialGlow(color: Color, alpha: Float, radius: Float): Brush = Brush.radialGradient(
    listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * .4f), color.copy(alpha = 0f)),
    center = Offset.Zero, radius = radius,
)

internal fun cornerBlob(core: Color, rim: Color, radius: Float): Brush = Brush.radialGradient(
    0.0f to core.copy(alpha = .95f),
    0.38f to core.copy(alpha = .80f),
    0.62f to rim.copy(alpha = .45f),
    1.0f to rim.copy(alpha = 0f),
    center = Offset.Zero, radius = radius,
)

/**
 * Soft coral, purple and pink light resting in the screen corners and drifting very slowly.
 * Its own layer; the phase is read only while drawing, so scrolling content never recomposes.
 */
@Composable
internal fun NowAmbientBackground(modifier: Modifier = Modifier) {
    val phase = rememberDriftPhase(rememberAmbientMotion(), 28_000, "nowAmbient")
    Box(
        modifier
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val r = max(w, h)
                val coralR = w * .36f
                val purpleR = w * .34f
                val pinkR = w * .30f
                val lilacR = w * .30f
                // Saturated cores that fade quickly, so the colour stays in the corners and
                // the middle of the screen reads as clean white.
                val coral = cornerBlob(Color(0xFFFF6F6F), Color(0xFFFF9A8B), coralR)
                val purple = cornerBlob(Color(0xFFB07CFF), Color(0xFFD3B8FF), purpleR)
                val pink = cornerBlob(Color(0xFFFF6F8E), Color(0xFFFFA3B8), pinkR)
                val lilac = cornerBlob(Color(0xFFB693FF), Color(0xFFDCCBFF), lilacR)
                onDrawBehind {
                    val t = phase.value
                    drawRect(Color.White)
                    glow(coral, coralR, w * (-.02f + .03f * sin(t)), h * (-.005f + .012f * cos(t)))
                    glow(purple, purpleR, w * (1.03f + .03f * cos(t + 1f)), h * (.0f + .012f * sin(t + 1f)))
                    glow(pink, pinkR, w * (-.04f + .03f * cos(t + 2.5f)), h * (1.0f + .01f * sin(t + 2.5f)))
                    glow(lilac, lilacR, w * (1.04f + .03f * sin(t + 3.6f)), h * (.99f + .01f * cos(t + 3.6f)))
                }
            }
    )
}

/**
 * An organic outline: a smooth closed Catmull-Rom curve through perimeter points given as
 * fractions of the box, so edges undulate gently instead of reading as a plain rectangle.
 */
internal class OrganicBlobShape(private val points: List<Pair<Float, Float>>) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline =
        androidx.compose.ui.graphics.Outline.Generic(organicPath(points, size.width, size.height))
}

internal fun organicPath(points: List<Pair<Float, Float>>, w: Float, h: Float): Path {
    val p = points.map { Offset(it.first * w, it.second * h) }
    val n = p.size
    return Path().apply {
        moveTo(p[0].x, p[0].y)
        for (i in 0 until n) {
            val p0 = p[(i - 1 + n) % n]
            val p1 = p[i]
            val p2 = p[(i + 1) % n]
            val p3 = p[(i + 2) % n]
            cubicTo(
                p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                p2.x, p2.y,
            )
        }
        close()
    }
}

/** The focus card's outline: a lifted top-left, a soft waist on the right, a rolling base. */
internal val FocusBlob = OrganicBlobShape(listOf(
    .035f to .045f, .16f to .004f, .42f to .016f, .70f to .006f, .90f to .012f,
    .985f to .055f, .998f to .20f, .982f to .42f, .992f to .64f, 1.0f to .84f,
    .975f to .965f, .86f to .995f, .62f to .984f, .38f to .996f, .14f to .988f,
    .022f to .955f, .004f to .80f, .016f to .58f, .002f to .36f, .006f to .16f,
))

/** A soft four-point sparkle, used for the small accent stars. */
internal fun DrawScope.sparkleStar(center: Offset, radius: Float, brush: Brush) {
    val p = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticBezierTo(center.x + radius * .18f, center.y - radius * .18f, center.x + radius, center.y)
        quadraticBezierTo(center.x + radius * .18f, center.y + radius * .18f, center.x, center.y + radius)
        quadraticBezierTo(center.x - radius * .18f, center.y + radius * .18f, center.x - radius, center.y)
        quadraticBezierTo(center.x - radius * .18f, center.y - radius * .18f, center.x, center.y - radius)
        close()
    }
    drawPath(p, brush)
}
