package com.virlin.app.ui.hierarchy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** Collapse before the list scrolls up; reveal only after the list exhausts downward motion.
 * Used by project overview and the recursive task/subtask list at every depth.
 */
internal class ProjectHeaderScrollConnection(
    private val enabled: () -> Boolean,
    private val height: () -> Float,
    private val offset: () -> Float,
    private val updateOffset: (Float) -> Unit,
) : NestedScrollConnection {
    private fun consume(delta: Float): Offset {
        if (!enabled()) return Offset.Zero
        val previous = offset()
        val next = (previous + delta).coerceIn(-height().coerceAtLeast(0f), 0f)
        if (next == previous) return Offset.Zero
        updateOffset(next)
        return Offset(0f, next - previous)
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f) consume(available.y) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f) consume(available.y) else Offset.Zero
}
