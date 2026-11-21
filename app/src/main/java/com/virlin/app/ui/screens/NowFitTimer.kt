package com.virlin.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Smallest timer block (20dp tiles) before the card is allowed to grow and scroll instead. */
private val MinTimerBlock = 52.dp

/**
 * True when the height left for the card is shorter than [below], so its spacing should tighten.
 * Reads the budget in composition: it changes only when the window or header is resized.
 */
@Composable
internal fun fitIsCompact(maxHeightPx: (() -> Int?)?, below: Dp): Boolean {
    val limit = with(LocalDensity.current) { below.roundToPx() }
    return maxHeightPx?.invoke()?.let { it < limit } ?: false
}

/**
 * A column whose timer takes only the height left after everything above and below it, so the
 * whole Focus card fits the first screen. [maxHeightPx] is read during layout (no recomposition);
 * null means no limit. Content is never clipped: below the minimum the card simply grows.
 */
@Composable
internal fun FitTimerColumn(
    maxHeightPx: (() -> Int?)?,
    modifier: Modifier = Modifier,
    top: @Composable ColumnScope.() -> Unit,
    timer: @Composable () -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        content = {
            Column(Modifier.layoutId("top").fillMaxWidth(), content = top)
            Box(Modifier.layoutId("timer").fillMaxWidth(), contentAlignment = Alignment.Center) { timer() }
            Column(Modifier.layoutId("bottom").fillMaxWidth(), content = bottom)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val topP = measurables.first { it.layoutId == "top" }.measure(loose)
        val bottomP = measurables.first { it.layoutId == "bottom" }.measure(loose)
        val budget = maxHeightPx?.invoke()
        val timerMax = if (budget == null) Constraints.Infinity
            else (budget - topP.height - bottomP.height).coerceAtLeast(MinTimerBlock.roundToPx())
        val timerP = measurables.first { it.layoutId == "timer" }.measure(loose.copy(maxHeight = timerMax))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(topP.width, timerP.width, bottomP.width)
        layout(width, topP.height + timerP.height + bottomP.height) {
            topP.place(0, 0)
            timerP.place(0, topP.height)
            bottomP.place(0, topP.height + timerP.height)
        }
    }
}
