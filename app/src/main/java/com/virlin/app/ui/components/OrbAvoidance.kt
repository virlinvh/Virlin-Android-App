package com.virlin.app.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.sqrt

/** Root coordinates of the shell-owned orb, absent when it is not over page content. */
val LocalOrbBounds = staticCompositionLocalOf<Rect?> { null }

internal fun orbEndClearance(row: Rect?, orb: Rect?, gap: Float): Float {
    if (row == null || orb == null || row.bottom <= orb.top - gap || row.top >= orb.bottom + gap ||
        row.right <= orb.left - gap || row.left >= orb.right + gap) return 0f
    return (row.right - orb.left + gap).coerceIn(0f, row.width)
}

/** Reserve only the intersecting end of a row; rows outside the orb's band stay full width. */
fun Modifier.avoidOrb(): Modifier = composed {
    val orb = LocalOrbBounds.current
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val clearance = orbEndClearance(bounds, orb, with(density) { 8.dp.toPx() })
    this.onGloballyPositioned { bounds = it.boundsInRoot() }
        .padding(end = with(density) { clearance.toDp() })
}

/** Horizontal clearance for this individual text/control band, following the circle's chord. */
internal fun circularOrbClearance(row: Rect?, orb: Rect?, gap: Float): Float {
    if (row == null || orb == null) return 0f
    val center = orb.center
    val radius = maxOf(orb.width, orb.height) / 2f + gap
    val nearestY = center.y.coerceIn(row.top, row.bottom)
    val dy = nearestY - center.y
    if (kotlin.math.abs(dy) >= radius) return 0f
    val half = sqrt(radius * radius - dy * dy)
    if (row.right <= center.x - half || row.left >= center.x + half) return 0f
    return (row.right - (center.x - half)).coerceAtLeast(0f)
}

fun Modifier.avoidOrbCircle(): Modifier = composed {
    val orb = LocalOrbBounds.current
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val clearance = circularOrbClearance(bounds, orb, with(density) { 8.dp.toPx() })
    this.onGloballyPositioned { bounds = it.boundsInRoot() }
        .padding(end = with(density) { clearance.toDp() })
}

/** Clip only the local circular exclusion; this does not change the card or grid measurement. */
fun Modifier.circularOrbCutout(): Modifier = composed {
    val orb = LocalOrbBounds.current
    if (orb == null) return@composed this
    val density = LocalDensity.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val gap = with(density) { 8.dp.toPx() }
    val shape = remember(bounds, orb, gap) { object : Shape {
        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
            val rect = bounds
            val base = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
            if (rect == null || orb == null) return Outline.Generic(base)
            val center = orb.center - rect.topLeft
            val radius = maxOf(orb.width, orb.height) / 2f + gap
            val hole = Path().apply { addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius)) }
            return Outline.Generic(Path.combine(PathOperation.Difference, base, hole))
        }
    } }
    this.onGloballyPositioned { bounds = it.boundsInRoot() }.clip(shape)
}
