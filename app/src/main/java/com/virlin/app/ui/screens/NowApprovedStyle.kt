package com.virlin.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.virlin.app.R

/** Bundled, offline type: exact family used by the approved interactive mockup. */
@OptIn(ExperimentalTextApi::class)
private fun nowFont(weight: FontWeight) = Font(R.font.now_inter, weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

internal val NowApprovedFont = FontFamily(
    nowFont(FontWeight.Normal), nowFont(FontWeight.Medium), nowFont(FontWeight.SemiBold),
    nowFont(FontWeight.Bold), nowFont(FontWeight.ExtraBold), nowFont(FontWeight.Black))

internal val NowApprovedInk = Color(0xFF21082F)

/** Plain white page: no corner tints (and the rejected cloud animation stays absent). */
@Composable
internal fun NowApprovedBackground(modifier: Modifier = Modifier) {
    Box(modifier.drawWithCache { onDrawBehind { drawRect(Color.White) } })
}
