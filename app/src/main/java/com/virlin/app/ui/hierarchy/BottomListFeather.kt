package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Softly separates floating add controls from lists without shortening the list viewport. */
@Composable
internal fun BottomListFeather(surface: Color) {
    Box(
        Modifier.fillMaxWidth().height(72.dp).background(
            Brush.verticalGradient(
                0f to surface.copy(alpha = 0f),
                0.45f to surface.copy(alpha = 0.18f),
                1f to surface.copy(alpha = 0.96f),
            )
        )
    )
}
