package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.components.SplitFlapDigit
import com.virlin.app.ui.components.focusTimeContentDescription

/** Presentation only. Reuses the existing digit engine and supplied current-session seconds. */
@Composable
internal fun NowApprovedTimer(seconds: Int, modifier: Modifier = Modifier) {
    val minutes = (seconds.coerceAtLeast(0) / 60).toString().padStart(2, '0')
    val secondsText = (seconds.coerceAtLeast(0) % 60).toString().padStart(2, '0')
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()
        .clearAndSetSemantics { contentDescription = focusTimeContentDescription(seconds) }) {
        val byWidth = (maxWidth - 48.dp) / (minutes.length + 2) - 3.dp
        // 25dp = the group's vertical padding plus each tile's frame; tiles are 1.3x taller than wide.
        val byHeight = if (constraints.hasBoundedHeight) (maxHeight - 25.dp) / 1.3f else 56.dp
        val width = minOf(byWidth, byHeight).coerceIn(20.dp, 56.dp)
        val height = width * 1.3f
        Row(Modifier.align(Alignment.Center)
            .shadow(4.dp, RoundedCornerShape(26.dp), ambientColor = Color(0xFF1FC9B6), spotColor = Color(0xFF1FC9B6))
            .background(Brush.horizontalGradient(listOf(Color(0xFFA3FAF0), Color(0xFFBEFFF0), Color(0xFF95F3E9))), RoundedCornerShape(26.dp))
            .border(1.dp, Color.White.copy(alpha = .8f), RoundedCornerShape(26.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            @Composable fun digit(char: Char) {
                val shape = RoundedCornerShape(12.dp)
                Box(Modifier.padding(horizontal = 1.dp).shadow(3.dp, shape)
                    .background(Color(0xFF16091F), shape).padding(top = 2.dp, bottom = 3.dp)
                    .border(1.dp, Color(0xFFAC6FCE), shape)) {
                    SplitFlapDigit(char, width, height, tileColor = Color(0xFF3A2048), cornerRadius = 12.dp,
                        fontScale = 1f / fontScale.coerceAtLeast(1f), fontFamily = NowApprovedFont,
                        faceBrush = Brush.verticalGradient(listOf(Color(0xFF62406D), Color(0xFF3A2048), Color(0xFF32163E))))
                }
            }
            minutes.forEach { digit(it) }
            Column(Modifier.padding(horizontal = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) { Box(Modifier.size(7.dp).background(Brush.verticalGradient(
                    listOf(Color(0xFF79518D), Color(0xFF2D123B))), CircleShape)) }
            }
            secondsText.forEach { digit(it) }
        }
    }
}
