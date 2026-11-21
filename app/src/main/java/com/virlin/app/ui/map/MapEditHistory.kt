package com.virlin.app.ui.map

import com.virlin.app.domain.structure.Placement
import com.virlin.app.domain.structure.PlacementCommit

/** In-session, project-scoped map edits. A failed command must never advance either stack. */
internal sealed interface MapEdit {
    data class Appearance(val before: MapAppearance, val after: MapAppearance) : MapEdit
    data class Move(val forward: Placement, val commit: PlacementCommit) : MapEdit
    data class Rename(val kind: MapKind, val id: String, val before: String, val after: String) : MapEdit
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

    /** Removes the step that could not be replayed, so the buttons never offer it again. */
    fun discard(edit: MapEdit) {
        past.remove(edit)
        future.remove(edit)
    }

    /**
     * A copy, duplicate or delete reshapes the hierarchy, so recorded moves may no longer replay
     * safely. Look and name steps stay: they are re-applied by id and checked when replayed.
     */
    fun dropMoves() {
        past.removeAll { it is MapEdit.Move }
        future.removeAll { it is MapEdit.Move }
    }

    fun clear() { past.clear(); future.clear() }
}

/** One history per project for the app session, so leaving the map and returning keeps it. */
internal object MapEditHistories {
    private val byProject = HashMap<String, MapEditHistory>()
    fun of(projectId: String): MapEditHistory = byProject.getOrPut(projectId) { MapEditHistory() }
    fun forget(projectId: String) { byProject.remove(projectId) }
}

/**
 * Re-applies only what differs between [from] and [to] onto this appearance, so undoing one
 * step never reverts unrelated changes made since (another box, a desktop edit, a cleanup).
 */
internal fun MapAppearance.applyStep(from: MapAppearance, to: MapAppearance): MapAppearance {
    fun <K, V> Map<K, V>.step(a: Map<K, V>, b: Map<K, V>): Map<K, V> {
        if (a == b) return this
        val out = toMutableMap()
        (a.keys + b.keys).forEach { key ->
            if (a[key] != b[key]) {
                val value = b[key]
                if (value == null) out.remove(key) else out[key] = value
            }
        }
        return out
    }
    fun <T> Set<T>.step(a: Set<T>, b: Set<T>): Set<T> = if (a == b) this else this - (a - b) + (b - a)
    fun <T> T.step(a: T, b: T): T = if (a != b) b else this
    return copy(
        layout = layout.step(from.layout, to.layout),
        paletteIndex = paletteIndex.step(from.paletteIndex, to.paletteIndex),
        nodeColors = nodeColors.step(from.nodeColors, to.nodeColors),
        branchColors = branchColors.step(from.branchColors, to.branchColors),
        customPalette = customPalette.step(from.customPalette, to.customPalette),
        savedColors = savedColors.step(from.savedColors, to.savedColors),
        boxFinish = boxFinish.step(from.boxFinish, to.boxFinish),
        nodeFinishes = nodeFinishes.step(from.nodeFinishes, to.nodeFinishes),
        nodeWidths = nodeWidths.step(from.nodeWidths, to.nodeWidths),
        hiddenNodeIds = hiddenNodeIds.step(from.hiddenNodeIds, to.hiddenNodeIds),
        branchMode = branchMode.step(from.branchMode, to.branchMode),
        singleBranchColor = singleBranchColor.step(from.singleBranchColor, to.singleBranchColor),
        levelColors = levelColors.step(from.levelColors, to.levelColors),
        boxFill = boxFill.step(from.boxFill, to.boxFill),
        boxFillColor = boxFillColor.step(from.boxFillColor, to.boxFillColor),
        boxBorder = boxBorder.step(from.boxBorder, to.boxBorder),
        boxBorderColor = boxBorderColor.step(from.boxBorderColor, to.boxBorderColor),
        boxTextColor = boxTextColor.step(from.boxTextColor, to.boxTextColor),
        backgroundColor = backgroundColor.step(from.backgroundColor, to.backgroundColor),
        savedPalettes = savedPalettes.step(from.savedPalettes, to.savedPalettes),
        boxPaletteMode = boxPaletteMode.step(from.boxPaletteMode, to.boxPaletteMode),
        boxStrength = boxStrength.step(from.boxStrength, to.boxStrength),
        boxLevelColors = boxLevelColors.step(from.boxLevelColors, to.boxLevelColors),
        boxSingleColor = boxSingleColor.step(from.boxSingleColor, to.boxSingleColor),
        boxBranchColors = boxBranchColors.step(from.boxBranchColors, to.boxBranchColors),
        branchPalette = branchPalette.step(from.branchPalette, to.branchPalette),
    )
}

/** A short name for a step, used in the "Undone" / "Redone" message. */
internal fun MapEdit.describe(): String = when (this) {
    is MapEdit.Move -> "move"
    is MapEdit.Rename -> "rename"
    is MapEdit.Appearance -> when {
        before.hiddenNodeIds != after.hiddenNodeIds -> "hide box"
        before.nodeColors != after.nodeColors -> "box colour"
        before.nodeWidths != after.nodeWidths -> "box width"
        before.layout != after.layout -> "layout"
        else -> "map colours"
    }
}
