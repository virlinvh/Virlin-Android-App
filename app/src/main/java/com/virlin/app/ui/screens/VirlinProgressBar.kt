package com.virlin.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compact white card and honey/apricot inset from the finalized mockup. */
@Composable
fun VirlinProgressBar(timeSaved: String, tasksAdvanced: String, projectsMoved: String, timeFocused: String,
    modifier: Modifier = Modifier, animateSparkles: Boolean = true, savedUnit: String = "saved", timeExternal: String = "—") {
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(modifier.fillMaxWidth().shadow(9.dp, shape, ambientColor = Color(0xFF68477B), spotColor = Color(0xFF68477B))
        .background(Brush.horizontalGradient(listOf(Color.White, Color(0xFFFFFCF7))), shape).border(1.dp, Color.White, shape)) {
        val narrow = maxWidth < 340.dp
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(.33f).heightIn(min = if (narrow) 52.dp else 56.dp)
                .shadow(2.dp, RoundedCornerShape(18.dp), ambientColor = Color(0xFFE59A72), spotColor = Color(0xFFE59A72))
                .background(Brush.linearGradient(listOf(Color(0xFFFFF5D0), Color(0xFFFFE5B9), Color(0xFFF4C2AB))), RoundedCornerShape(18.dp))
                .border(1.dp, Color.White.copy(alpha = .6f), RoundedCornerShape(18.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp), contentAlignment = Alignment.Center) {
                // Shrinks long totals (e.g. "14h 05m") to fit the tile instead of truncating them.
                val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                val fitSp = (maxWidth.value / (timeSaved.length.coerceAtLeast(1) * .62f * fontScale)).coerceIn(10f, if (narrow) 24f else 28f)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(timeSaved, fontFamily = NowApprovedFont, fontWeight = FontWeight.Black,
                        fontSize = fitSp.sp, lineHeight = (fitSp * 1.15f).sp,
                        color = Color(0xFF49204E), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    Text(savedUnit, Modifier.padding(start = if (narrow) 3.dp else 8.dp), fontFamily = NowApprovedFont,
                        fontSize = if (narrow) 11.sp else 13.sp, fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF64415B), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Canvas(Modifier.align(Alignment.BottomStart).size(if (narrow) 10.dp else 13.dp)) {
                    val ink = Color(0xFF28756A)
                    drawLine(ink, Offset(size.width*.45f, size.height), Offset(size.width*.45f, size.height*.38f), 1.2.dp.toPx(), StrokeCap.Round)
                    val leaf = Path().apply {
                        moveTo(size.width*.45f, size.height*.6f)
                        cubicTo(-size.width*.1f, size.height*.7f, 0f, 0f, size.width*.45f, size.height*.6f)
                        cubicTo(size.width*.9f, size.height*.7f, size.width*1.1f, -size.height*.2f, size.width*.45f, size.height*.6f)
                    }
                    drawPath(leaf, ink)
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.width(1.dp).height(38.dp).background(Color(0xFFE9DFEE)))
            Column(Modifier.weight(.58f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SummaryTimeRow(Icons.Outlined.Schedule, "Focus", timeFocused, narrow)
                SummaryTimeRow(Icons.Outlined.Layers, "External", timeExternal, narrow)
            }
            Canvas(Modifier.weight(.1f).height(34.dp)) {
                val gold = Brush.linearGradient(listOf(Color(0xFFFFE783), Color(0xFFFFAF39)))
                val coral = Brush.linearGradient(listOf(Color(0xFFFFC27A), Color(0xFFFF7A59)))
                sparkleStar(Offset(size.width*.48f, size.height*.58f), 10.dp.toPx(), gold)
                sparkleStar(Offset(size.width*.86f, size.height*.18f), 4.dp.toPx(), coral)
                sparkleStar(Offset(size.width*.16f, size.height*.2f), 2.5.dp.toPx(), gold)
            }
        }
    }
}

@Composable
private fun SummaryTimeRow(icon: ImageVector, label: String, value: String, narrow: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFF71458D), modifier = Modifier.size(if (narrow) 14.dp else 17.dp))
        Spacer(Modifier.width(if (narrow) 5.dp else 7.dp))
        Text(label, Modifier.widthIn(min = if (narrow) 46.dp else 64.dp).padding(end = 4.dp), fontFamily = NowApprovedFont,
            fontSize = if (narrow) 11.sp else 14.sp, color = Color(0xFF71458D), maxLines = 1, softWrap = false)
        Text(value, Modifier.weight(1f, fill = false), fontFamily = NowApprovedFont, fontWeight = FontWeight.ExtraBold,
            fontSize = if (narrow) 12.sp else 16.sp, color = NowApprovedInk, maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}
