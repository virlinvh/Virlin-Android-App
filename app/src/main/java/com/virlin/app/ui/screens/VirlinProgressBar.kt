package com.virlin.app.ui.screens

// The Now summary bar: supplied as a drop-in composable and used as-is.
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Supply the existing formatted values. No business logic or data bindings are changed. */
@Composable
fun VirlinProgressBar(
    timeSaved: String,
    tasksAdvanced: String,
    projectsMoved: String,
    timeFocused: String,
    modifier: Modifier = Modifier,
    animateSparkles: Boolean = true,
) {
    val shape = RoundedCornerShape(18.dp)
    val pulse by rememberInfiniteTransition(label = "sparkle").animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "sparklePulse",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, shape, ambientColor = Color(0x180F4737), spotColor = Color(0x160F4737))
            .clip(shape)
            .background(Color(0xFFFFFEFB))
            .border(0.7.dp, Color(0xFFF5EBD3), shape),
    ) {
        val narrow = maxWidth < 370.dp
        val leftWidth = if (narrow) 92.dp else 104.dp
        val ornamentWidth = if (narrow) 36.dp else 42.dp
        val firstLineSize = if (narrow) 9.sp else 10.sp

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Pale mint waves remain behind the text and are clipped by the card.
            val mintOuter = Path().apply {
                moveTo(0f, h * .54f)
                cubicTo(w * .025f, -h * .10f, w * .16f, -h * .12f, w * .24f, h * .02f)
                cubicTo(w * .34f, -h * .02f, w * .34f, h * .67f, w * .39f, h)
                lineTo(0f, h)
                close()
            }
            drawPath(mintOuter, Brush.linearGradient(listOf(Color(0xFFF0FFF6), Color(0xFFEDFBEA)), Offset.Zero, Offset(w * .4f, h)))
            val mintInner = Path().apply {
                moveTo(w * .045f, h * .77f)
                cubicTo(w * .055f, h * .21f, w * .19f, h * .18f, w * .26f, h * .34f)
                cubicTo(w * .29f, h * .60f, w * .37f, h * .75f, w * .37f, h)
                lineTo(w * .06f, h)
                close()
            }
            drawPath(mintInner, Brush.linearGradient(listOf(Color(0xFFDDF9E9), Color(0xFFF2FFEB)), Offset(w * .04f, 0f), Offset(w * .4f, h)))

            // Warm highlight is deliberately weak in the middle and strongest at the edge.
            val cream = Path().apply {
                moveTo(w * .91f, 0f)
                cubicTo(w * .85f, h * .29f, w * .86f, h * .55f, w * .77f, h * .78f)
                cubicTo(w * .70f, h * .98f, w * .72f, h, w * .72f, h)
                lineTo(w, h)
                lineTo(w, 0f)
                close()
            }
            drawPath(cream, Brush.linearGradient(listOf(Color(0x00FFF8DC), Color(0x59FFE9A9)), Offset(w * .71f, h), Offset(w, 0f)))
            drawCircle(Color(0x45FFE7A0), radius = h * .31f, center = Offset(w * .927f, h * .52f))
            drawCircle(Color(0x34FFE5A1), radius = h * .19f, center = Offset(w * .975f, h * .70f))

            // Accent marks are in their own gap; no painted stroke intersects text.
            // The left block ends at 12dp + leftWidth, so the marks are anchored just past it and
            // before the sentence starts — at Pixel-8 width the supplied ratio landed on the number.
            val x = w * (if (narrow) .295f else .302f)
            drawLine(Color(0xFF9BD984), Offset(x, h * .20f), Offset(x - w * .014f, h * .40f), strokeWidth = h * .052f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(Color(0xFF9BD984), Offset(x + w * .024f, h * .39f), Offset(x + w * .006f, h * .53f), strokeWidth = h * .052f, cap = androidx.compose.ui.graphics.StrokeCap.Round)

            val shimmer = if (animateSparkles) pulse else 1f
            scale(shimmer, pivot = Offset(w * .932f, h * .52f)) {
                sparkle(Offset(w * .932f, h * .52f), h * .19f, Color(0xFFF1AC18))
            }
            sparkle(Offset(w * .963f, h * .26f), h * .075f, Color(0xFFF3AB16))
            sparkle(Offset(w * .978f, h * .77f), h * .065f, Color(0xFFF7C19E))
        }

        Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(leftWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(timeSaved, fontSize = if (narrow) 29.sp else 32.sp, lineHeight = 32.sp,
                        fontWeight = FontWeight.Black, color = Color(0xFF102322), maxLines = 1)
                    Text("saved", fontSize = 12.sp, lineHeight = 14.sp,
                        fontWeight = FontWeight.Medium, color = Color(0xFF4F5B5B))
                }
            }
            Spacer(Modifier.width(if (narrow) 19.dp else 24.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Text("$tasksAdvanced tasks advanced  ·  $projectsMoved projects moved",
                    fontSize = firstLineSize, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                    color = Color(0xFF46515D), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$timeFocused focused", fontSize = 14.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFF25303A), maxLines = 1)
            }
            Spacer(Modifier.width(ornamentWidth))
        }
    }
}

private fun DrawScope.sparkle(center: Offset, radius: Float, color: Color) {
    val p = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticBezierTo(center.x + radius * .18f, center.y - radius * .18f, center.x + radius, center.y)
        quadraticBezierTo(center.x + radius * .18f, center.y + radius * .18f, center.x, center.y + radius)
        quadraticBezierTo(center.x - radius * .18f, center.y + radius * .18f, center.x - radius, center.y)
        quadraticBezierTo(center.x - radius * .18f, center.y - radius * .18f, center.x, center.y - radius)
        close()
    }
    drawPath(p, color)
}
