package com.virlin.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same visual shell at launch with no invented focused task or running timer. */
@Composable
internal fun NowEmptyFocusCard(
    maxHeightPx: (() -> Int?)? = null,
    chooseTaskLabel: String = "Choose a task in Projects",
    onChooseTask: () -> Unit
) {
    val cardSize = remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxWidth().onSizeChanged { cardSize.value = it }.shadow(6.dp, NowFocusOutline).clip(NowFocusOutline)) {
        NowFocusSurface(Modifier.matchParentSize())
        val compact = fitIsCompact(maxHeightPx, 380.dp)
        // The Focus card's own insets, so the status pill sits exactly where FOCUSING NOW does.
        val padV = if (compact) 14.dp else 22.dp
        val padH = if (compact) 18.dp else 24.dp
        val gap = if (compact) .6f else 1f
        val padPx = with(LocalDensity.current) { (padV * 2).roundToPx() }
        FitTimerColumn(
            maxHeightPx = maxHeightPx?.let { budget -> { budget()?.minus(padPx) } },
            modifier = Modifier.padding(horizontal = padH, vertical = padV),
            top = {
            NowFocusPillRow(cardSize, padH, padV, Modifier.fillMaxWidth(), lowerCost = if (compact) 8f else 2f,
                belowRoom = if (compact) 14.dp * gap - 2.dp else 0.dp,
                start = { LivePill("READY TO FOCUS", breathing = false, compact = compact) })
            Spacer(Modifier.height(14.dp * gap))
            OneLineText("Your attention is free.", TextStyle(fontFamily = NowApprovedFont,
                fontWeight = FontWeight.Black, color = NowApprovedInk),
                maxSize = if (compact) 22.sp else 26.sp, minSize = 16.sp)
            Spacer(Modifier.height(8.dp * gap))
            OneLineText("Choose where you want to make progress next.", TextStyle(fontFamily = NowApprovedFont,
                fontStyle = FontStyle.Italic, color = Color(0xFF654978)),
                maxSize = if (compact) 13.sp else 14.sp, minSize = 10.sp)
            Spacer(Modifier.height(14.dp * gap))
            },
            timer = { NowApprovedTimer(0) },
            bottom = {
            Spacer(Modifier.height(12.dp * gap))
            Text("NO ACTIVE SESSION", Modifier.align(Alignment.CenterHorizontally), fontFamily = NowApprovedFont,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = NowApprovedInk)
            Spacer(Modifier.height(14.dp * gap))
            val shape = NowChooseButtonShape
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val press by animateFloatAsState(
                if (pressed && android.animation.ValueAnimator.areAnimatorsEnabled()) .97f else 1f, tween(120), label = "chooseTaskPress")
            Box(Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 54.dp).testTag("now_choose_task")
                .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = chooseTaskLabel, onClick = onChooseTask)
                .graphicsLayer { scaleX = press; scaleY = press }
                .shadow(5.dp, shape, ambientColor = Color(0xFF0ABFAE), spotColor = Color(0xFF0ABFAE))
                .background(Color(0xFFC7104C), shape)
                .padding(bottom = 5.dp)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFFFF819E), Color(0xFFFF3779))))
                .border(1.5.dp, Color(0xFFFFB0CA), shape)
                .padding(if (compact) 10.dp else 13.dp), contentAlignment = Alignment.Center) {
                Text("CHOOSE A TASK", color = Color.White, fontFamily = NowApprovedFont, fontSize = 15.sp,
                    letterSpacing = .4.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
            })
    }
}

/** One line at [maxSize], stepping down toward [minSize] only when the width demands it. */
@Composable
private fun OneLineText(text: String, style: TextStyle, maxSize: TextUnit, minSize: TextUnit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val width = constraints.maxWidth
        val size = remember(text, style, maxSize, minSize, width, measurer) {
            var sp = maxSize.value
            while (sp > minSize.value && measurer.measure(text, style.copy(fontSize = sp.sp), maxLines = 1,
                    softWrap = false, constraints = Constraints(maxWidth = Constraints.Infinity)).size.width > width) sp -= .5f
            sp.sp
        }
        Text(text, style = style.copy(fontSize = size, lineHeight = size * 1.2f), maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis)
    }
}
