package com.virlin.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** Presentation-only controls. Options open vertically above the bottom-right launcher. */
@Composable
internal fun MapGlassControls(
    tasksOnly: Boolean,
    onFit: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onTasksOnly: () -> Unit,
    onExpandAll: () -> Unit,
    modifier: Modifier = Modifier,
    anyCollapsed: Boolean = false,
    onCollapseAll: (() -> Unit)? = null,
    expandDescription: String = "Open next branch level",
    collapseDescription: String = "Fold deepest visible level",
    locked: Boolean = false,
    lockEnabled: Boolean = false,
    onToggleLock: () -> Unit = {},
    focusMode: Boolean = false,
    onToggleFocus: () -> Unit = {},
    height: Dp = 440.dp,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.height(height), verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.End) {
        if (open) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Bottom) {
            GlassMapButton("lock", if (locked) "Unlock focused branch" else "Focus selected branch",
                active = locked, enabled = locked || lockEnabled, onClick = onToggleLock)
            if (onCollapseAll != null) {
                GlassMapButton("collapse", collapseDescription, active = anyCollapsed,
                    onClick = onCollapseAll)
            }
            GlassMapButton("expand", expandDescription, onClick = onExpandAll)
            GlassMapButton("tasks", "Tasks and subtasks only", tasksOnly, onClick = onTasksOnly)
            GlassMapButton("plus", "Zoom in", onClick = onZoomIn)
            GlassMapButton("minus", "Zoom out", onClick = onZoomOut)
            GlassMapButton("fit", "Fit to screen", onClick = onFit)
            }
            GlassMapButton("focus", if (focusMode) "Exit map focus mode" else "Enter map focus mode",
                active = focusMode, onClick = { open = false; onToggleFocus() })
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }
        GlassMapButton("stack", "Map controls", open, dimmed = focusMode && !open,
            onClick = { open = !open })
    }
}

@Composable
private fun GlassMapButton(
    kind: String, label: String, active: Boolean = false, enabled: Boolean = true,
    dimmed: Boolean = false, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val projectPalette = com.virlin.app.ui.hierarchy.LocalProjectPalette.current
    val ink = projectPalette?.let { Color(it.secondary) } ?: Color(0xFF07533E)
    val activeWash = projectPalette?.let {
        androidx.compose.ui.graphics.lerp(Color.White, Color(it.main), .18f)
    } ?: Color(0xDDE0F9EF)
    val passiveWash = projectPalette?.let {
        androidx.compose.ui.graphics.lerp(Color.White, Color(it.main), .07f)
    } ?: Color(0xB3ECF4F0)
    val lockScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (kind == "lock" && active) 1.12f else 1f,
        animationSpec = androidx.compose.animation.core.spring(), label = "map-lock-pulse")
    // A 36dp visual card within a separate 48dp accessible hit target. No tiny tap targets.
    Box(Modifier.size(48.dp).testTag("map_control_$kind")
        .graphicsLayer { alpha = if (dimmed) .48f else 1f }
        .semantics {
            contentDescription = label
            if (kind == "tasks" || kind == "stack" || kind == "lock" || kind == "focus") selected = active
            if (!enabled) disabled()
        }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(36.dp)
            .graphicsLayer { scaleX = lockScale; scaleY = lockScale; alpha = if (enabled) 1f else .45f }
            .shadow(3.dp, shape, ambientColor = Color(0x22305546), spotColor = Color(0x22305546))
            .background(Brush.linearGradient(if (active) listOf(activeWash, activeWash.copy(alpha = .7f))
                else listOf(Color(0xF5FFFFFF), passiveWash.copy(alpha = .7f))), shape)
            .border(1.dp, Color.White.copy(alpha = .9f), shape)
            .padding(8.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.8.dp.toPx()
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
                ink, Offset(w*x1, h*y1), Offset(w*x2, h*y2), stroke, StrokeCap.Round)
            when (kind) {
                "plus", "minus" -> {
                    line(.15f,.5f,.85f,.5f)
                    if (kind == "plus") line(.5f,.15f,.5f,.85f)
                }
                "fit" -> {
                    for (x in listOf(.12f,.88f)) for (y in listOf(.12f,.88f)) {
                        line(x,y,if(x < .5f) .34f else .66f,y)
                        line(x,y,x,if(y < .5f) .34f else .66f)
                    }
                }
                "focus" -> {
                    // A target at the centre distinguishes immersion from the Fit brackets.
                    for (x in listOf(.10f, .90f)) for (y in listOf(.10f, .90f)) {
                        line(x, y, if (x < .5f) .36f else .64f, y)
                        line(x, y, x, if (y < .5f) .36f else .64f)
                    }
                    drawCircle(ink, w * .14f, Offset(w * .5f, h * .5f), style = Stroke(stroke))
                    drawCircle(ink, w * if (active) .08f else .045f, Offset(w * .5f, h * .5f))
                }
                "stack" -> {
                    val path = Path().apply {
                        moveTo(w*.5f,h*.08f); lineTo(w*.92f,h*.35f)
                        lineTo(w*.5f,h*.62f); lineTo(w*.08f,h*.35f); close()
                    }
                    drawPath(path, ink)
                    line(.08f,.57f,.5f,.84f); line(.5f,.84f,.92f,.57f)
                }
                "tasks" -> {
                    line(.2f,.2f,.2f,.8f)
                    for (y in listOf(.2f,.5f,.8f)) {
                        drawCircle(ink, stroke, Offset(w*.2f,h*y))
                        line(.49f,y,.62f,y+.1f); line(.62f,y+.1f,.86f,y-.1f)
                    }
                }
                "collapse", "expand" -> {
                    // The shared tree silhouette distinguishes these from viewport zoom.
                    drawCircle(ink, stroke, Offset(w*.3f,h*.12f))
                    line(.3f,.15f,.3f,.64f)
                    line(.12f,.45f,.48f,.45f)
                    line(.12f,.45f,.12f,.78f); line(.48f,.45f,.48f,.78f)
                    drawCircle(ink, stroke, Offset(w*.12f,h*.82f))
                    drawCircle(ink, stroke, Offset(w*.48f,h*.82f))
                    line(.63f,.28f,.95f,.28f)
                    if (kind == "expand") line(.79f,.12f,.79f,.44f)
                }
                "lock" -> {
                    // A closing shackle and solid body read as a lock even at Fit zoom.
                    val top = if (active) .12f else .25f
                    line(.27f,.48f,.27f,top+.18f)
                    line(.27f,top+.18f,.39f,top)
                    line(.39f,top,.62f,top)
                    line(.62f,top,.74f,top+.18f)
                    line(.74f,top+.18f,.74f,if (active) .48f else .35f)
                    drawRoundRect(ink, topLeft = Offset(w*.16f,h*.45f),
                        size = androidx.compose.ui.geometry.Size(w*.68f,h*.45f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w*.08f))
                    drawCircle(Color.White, w*.055f, Offset(w*.5f,h*.64f))
                }
            }
        }
    }
}
