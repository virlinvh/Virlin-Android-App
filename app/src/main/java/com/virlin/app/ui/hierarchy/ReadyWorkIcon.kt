package com.virlin.app.ui.hierarchy

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Decorative status only: no new click target, progress, or changes to the card. */
@Composable
internal fun ReadyWorkIcon(modifier: Modifier, color: Color) {
    val motionEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val sparkle = if (motionEnabled) {
        rememberInfiniteTransition(label = "ready-sparkle").animateFloat(
            initialValue = .35f, targetValue = .9f,
            animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "ready-sparkle-opacity")
    } else null
    Canvas(modifier.semantics { contentDescription = "ready" }) {
        val side = size.minDimension
        val origin = Offset((size.width - side) / 2, (size.height - side) / 2)
        fun point(x: Float, y: Float) = origin + Offset(side * x, side * y)
        val stroke = side * .045f
        drawRoundRect(color.copy(alpha = .10f), origin, Size(side, side), CornerRadius(side * .24f))
        // Clipboard body and clip stay still; only the small sparkle changes opacity.
        drawRoundRect(color, point(.24f, .26f), Size(side * .47f, side * .56f),
            CornerRadius(side * .065f), style = Stroke(stroke))
        drawRoundRect(Color.White, point(.37f, .18f), Size(side * .21f, side * .14f), CornerRadius(side * .04f))
        drawRoundRect(color, point(.37f, .18f), Size(side * .21f, side * .14f),
            CornerRadius(side * .04f), style = Stroke(stroke))
        repeat(3) { index ->
            val y = .43f + index * .12f
            drawLine(color, point(.35f, y), point(.60f, y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        val star = Path().apply {
            moveTo(point(.80f, .16f).x, point(.80f, .16f).y)
            listOf(.83f to .23f, .90f to .26f, .83f to .29f, .80f to .36f,
                .77f to .29f, .70f to .26f, .77f to .23f).forEach { (x, y) ->
                val p = point(x, y); lineTo(p.x, p.y)
            }
            close()
        }
        drawPath(star, color.copy(alpha = sparkle?.value ?: .8f))
    }
}
