package com.virlin.app.ui.notes

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The solid disclosure triangle in front of toggle headings and Toggle blocks: ▶ when collapsed,
 * ▼ when open. It is sized from the line's text size and centred on the first line, so it reads
 * as part of the heading. [onClick] null draws it without a touch target (read-only preview).
 */
@Composable
internal fun NoteToggleArrow(
    collapsed: Boolean,
    textSizeSp: Int,
    lineHeightSp: Float,
    color: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    testTag: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val lineHeight = with(density) { lineHeightSp.sp.toDp() }
    val triangle = with(density) { (textSizeSp * 0.62f).sp.toDp() }
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) 0f else 90f,
        animationSpec = if (reducedMotion) snap() else tween(160),
        label = "note_toggle_arrow",
    )
    val height = if (onClick != null) maxOf(lineHeight, 28.dp) else lineHeight
    var box = modifier.width(maxOf(triangle + 14.dp, 28.dp)).height(height)
    if (onClick != null) {
        box = box.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
    }
    if (contentDescription != null) box = box.semantics { this.contentDescription = contentDescription }
    if (testTag != null) box = box.testTag(testTag)
    Box(box, contentAlignment = Alignment.TopCenter) {
        Canvas(
            Modifier.padding(top = ((lineHeight - triangle) / 2).coerceAtLeast(0.dp))
                .size(triangle).rotate(rotation),
        ) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.2f, h * 0.1f)
                lineTo(w * 0.9f, h * 0.5f)
                lineTo(w * 0.2f, h * 0.9f)
                close()
            }
            drawPath(path, color)
            drawPath(path, color, style = Stroke(width = w * 0.12f, join = StrokeJoin.Round))
        }
    }
}
