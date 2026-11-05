package com.virlin.app.ui.hierarchy

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

internal val PausedStatusInk = Color(0xFF80624F)
internal val PausedStatusSurface = Color(0xFFF5EFE9)

/** Progress held in place: geometry is stationary, only the marker halo breathes. */
@Composable
internal fun PausedWorkIcon(modifier: Modifier) {
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val halo = if (motionEnabled) rememberInfiniteTransition(label = "paused-halo").animateFloat(
        0f, .16f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "paused-halo-opacity") else null
    Canvas(modifier.semantics { contentDescription = "paused" }) {
        val side = size.minDimension
        val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
        fun point(x: Float, y: Float) = origin + Offset(side * x, side * y)
        drawRoundRect(PausedStatusSurface, origin, Size(side, side), CornerRadius(side * .24f))
        repeat(3) { index ->
            val x = .13f + index * .12f
            drawLine(PausedStatusInk, point(x, .5f), point(x + .075f, .5f),
                strokeWidth = side * .055f, cap = StrokeCap.Round)
        }
        drawCircle(PausedStatusInk.copy(alpha = halo?.value ?: 0f), side * .13f, point(.51f, .5f))
        drawCircle(PausedStatusInk, side * .067f, point(.51f, .5f))
        drawLine(PausedStatusInk, point(.63f, .35f), point(.63f, .65f),
            strokeWidth = side * .04f, cap = StrokeCap.Round)
        repeat(3) { index ->
            drawCircle(PausedStatusInk.copy(alpha = .26f), side * .018f, point(.73f + index * .065f, .5f))
        }
    }
}
