package com.virlin.app.ui.hierarchy

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.*

// Shared Waiting-only presentation palette: distinct from blue Running and violet Multi Run.
internal val WaitingStatusInk = Color(0xFF9B426B)
internal val WaitingStatusSurface = Color(0xFFFBEFF4)

/** Status decoration only; preserves the existing dial bounds and card interactions. */
@Composable
internal fun WaitingReviewIcon(review: Boolean, modifier: Modifier) {
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val phase = if (motionEnabled) rememberInfiniteTransition(label = "status-orbit").animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (review) 3000 else 3200, easing = LinearEasing)),
        label = "status-orbit-phase") else null
    Canvas(modifier.semantics { contentDescription = if (review) "needs attention" else "waiting" }) {
        val side = size.minDimension
        val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
        fun point(x: Float, y: Float) = origin + Offset(side * x, side * y)
        val ink = if (review) Color(0xFFDA8900) else WaitingStatusInk
        drawRoundRect(if (review) Color(0xFFFFF7E7) else WaitingStatusSurface, origin,
            Size(side, side), CornerRadius(side * .24f))
        val progress = phase?.value ?: 0f
        if (review) {
            // The cloud stays stationary; only the orbiting point moves.
            val cloud = Path().apply {
                moveTo(point(.34f, .67f).x, point(.34f, .67f).y)
                fun curve(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
                    val p1 = point(a,b); val p2 = point(c,d); val p3 = point(e,f)
                    cubicTo(p1.x,p1.y,p2.x,p2.y,p3.x,p3.y)
                }
                curve(.18f,.67f,.18f,.45f,.34f,.44f)
                curve(.37f,.25f,.61f,.25f,.66f,.45f)
                curve(.82f,.46f,.82f,.67f,.66f,.67f)
                close()
            }
            drawPath(cloud, ink, style = Stroke(side * .045f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(ink.copy(alpha = .25f), side * .40f, point(.5f,.5f), style = Stroke(side * .022f))
            val angle = progress * 2f * PI.toFloat() - PI.toFloat() / 2
            drawCircle(Color(0xFFFFAD00), side * .045f,
                point(.5f + .40f * cos(angle), .5f + .40f * sin(angle)))
        } else {
            repeat(8) { index ->
                val angle = index * PI.toFloat() / 4 - PI.toFloat() / 2
                val distance = abs(index / 8f - progress).let { min(it, 1f - it) }
                val emphasis = (1f - distance / .20f).coerceIn(0f, 1f)
                drawCircle(ink.copy(alpha = .38f + emphasis * .62f), side * .05f,
                    point(.5f + .28f * cos(angle), .5f + .28f * sin(angle)))
            }
        }
    }
}
