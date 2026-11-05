package com.virlin.app.ui.screens

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** A status animation, never a task duration. No visible button background. */
@Composable
internal fun MasterTimerButton(running: Boolean, busy: Boolean, onPause: () -> Unit,
    runningColor: Color? = null, scopeLabel: String? = null, tag: String = "master_timer_button",
    runningGradient: List<Color>? = null) {
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    val owner = LocalLifecycleOwner.current
    val motion = remember { ValueAnimator.areAnimatorsEnabled() }
    LaunchedEffect(running, motion, owner) {
        if (running && motion) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var previous = 0L
            while (true) withFrameNanos { now ->
                if (previous != 0L) elapsed += (now - previous).coerceAtMost(100_000_000L) / 1_000_000_000.0
                previous = now
            }
        }
    }
    IconButton(onClick = onPause, enabled = !busy, modifier = Modifier.size(48.dp)
        .testTag(tag).semantics {
            contentDescription = if (scopeLabel != null) {
                if (running) "Pause timers in $scopeLabel" else "Resume paused timers in $scopeLabel"
            } else if (running) "Pause all human and external timers" else "Resume master-paused timers"
            stateDescription = if (running) "Timers running" else "Timers paused"
        }) {
        Canvas(Modifier.width(14.dp).height(20.dp)) {
            val phase = (elapsed % 6.6).toFloat()
            val fraction = (phase / 6f).coerceIn(0f, 1f)
            val turn = if (phase <= 6f) 0f else {
                val t = ((phase - 6f) / .6f).coerceIn(0f, 1f)
                180f * t * t * (3f - 2f * t)
            }
            val ink = if (running) runningColor ?: Color(0xFFA77C35) else Color(0xFF80858A)
            val sand = if (running) runningColor ?: Color(0xFFD6AA55) else Color(0xFF9CA1A5)
            val inkBrush = if (running && runningGradient != null) Brush.verticalGradient(runningGradient)
                else SolidColor(ink)
            val sandBrush = if (running && runningGradient != null) Brush.verticalGradient(runningGradient)
                else SolidColor(sand)
            fun p(x: Float, y: Float) = Offset(size.width * x, size.height * y)
            val glass = Path().apply {
                moveTo(p(.13f, .08f).x, p(.13f, .08f).y)
                cubicTo(size.width*.13f, size.height*.32f, size.width*.39f, size.height*.40f, size.width*.49f, size.height*.50f)
                cubicTo(size.width*.39f, size.height*.60f, size.width*.13f, size.height*.68f, size.width*.13f, size.height*.92f)
                lineTo(size.width*.87f, size.height*.92f)
                cubicTo(size.width*.87f, size.height*.68f, size.width*.61f, size.height*.60f, size.width*.51f, size.height*.50f)
                cubicTo(size.width*.61f, size.height*.40f, size.width*.87f, size.height*.32f, size.width*.87f, size.height*.08f)
                close()
            }
            rotate(turn) {
                clipPath(glass) {
                    // Top surface lowers as sand gathers into the bottom chamber.
                    val top = .12f + .38f * fraction
                    if (fraction < 1f) drawRect(sandBrush, p(0f, top), Size(size.width, size.height * (.50f-top)))
                    if (fraction > 0f) {
                        val mound = Path().apply {
                            moveTo(0f, size.height*.92f)
                            lineTo(size.width*.5f, size.height*(.92f - .38f*fraction))
                            lineTo(size.width, size.height*.92f); close()
                        }
                        drawPath(mound, sandBrush)
                    }
                    if (running && phase < 6f && motion) repeat(3) { i ->
                        val y = .52f + ((elapsed.toFloat()*1.8f + i/3f) % 1f) * .34f
                        drawCircle(sandBrush, size.width*.045f, p(.5f, y))
                    }
                }
                drawPath(glass, inkBrush, style = Stroke(size.width*.075f))
                drawLine(inkBrush, p(.08f,.07f), p(.92f,.07f), size.width*.09f, StrokeCap.Round)
                drawLine(inkBrush, p(.08f,.93f), p(.92f,.93f), size.width*.09f, StrokeCap.Round)
            }
        }
    }
}
