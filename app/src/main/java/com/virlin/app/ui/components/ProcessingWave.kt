package com.virlin.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ProcessingWave(color: Color, bgColor: Color = color.copy(alpha=0.1f)) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    
    val height1 by infiniteTransition.animateFloat(
        initialValue = 0.35f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 900
                0.35f at 0 using EaseInOut
                0.80f at 225 using EaseInOut
                0.45f at 450 using EaseInOut
                0.65f at 675 using EaseInOut
                0.35f at 900
            },
            repeatMode = RepeatMode.Restart
        ), label = "h1"
    )
    val height2 by infiniteTransition.animateFloat(
        initialValue = 0.65f, targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1150
                0.65f at 0 using EaseInOut
                0.35f at 287 using EaseInOut
                0.85f at 575 using EaseInOut
                0.45f at 862 using EaseInOut
                0.65f at 1150
            },
            repeatMode = RepeatMode.Restart
        ), label = "h2"
    )
    val height3 by infiniteTransition.animateFloat(
        initialValue = 0.45f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                0.45f at 0 using EaseInOut
                0.75f at 250 using EaseInOut
                0.30f at 500 using EaseInOut
                0.70f at 750 using EaseInOut
                0.45f at 1000
            },
            repeatMode = RepeatMode.Restart
        ), label = "h3"
    )

    Row(
        modifier = Modifier.size(36.dp).background(bgColor, RoundedCornerShape(12.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(4.dp).height((20 * height1).dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(modifier = Modifier.width(2.dp))
        Box(modifier = Modifier.width(4.dp).height((20 * height2).dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(modifier = Modifier.width(2.dp))
        Box(modifier = Modifier.width(4.dp).height((20 * height3).dp).clip(RoundedCornerShape(50)).background(color))
    }
}
