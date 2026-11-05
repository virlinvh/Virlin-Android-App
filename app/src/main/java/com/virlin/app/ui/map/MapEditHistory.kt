package com.virlin.app.ui.map

import com.virlin.app.domain.structure.Placement
import com.virlin.app.domain.structure.PlacementCommit

/** In-session, project-scoped map edits. A failed command must never advance either stack. */
internal sealed interface MapEdit {
    data class Appearance(val before: MapAppearance, val after: MapAppearance) : MapEdit
    data class Move(val forward: Placement, val commit: PlacementCommit) : MapEdit
}

internal class MapEditHistory(private val capacity: Int = 25) {
    private val past = ArrayDeque<MapEdit>()
    private val future = ArrayDeque<MapEdit>()
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()
    fun undoCandidate(): MapEdit? = past.lastOrNull()
    fun redoCandidate(): MapEdit? = future.lastOrNull()

    fun record(edit: MapEdit) {
        past.addLast(edit)
        while (past.size > capacity) past.removeFirst()
        future.clear()
    }

    fun confirmUndo(updated: MapEdit) {
        check(past.isNotEmpty())
        past.removeLast()
        future.addLast(updated)
    }

    fun confirmRedo(updated: MapEdit) {
        check(future.isNotEmpty())
        future.removeLast()
        past.addLast(updated)
    }

    fun clear() { past.clear(); future.clear() }
}
