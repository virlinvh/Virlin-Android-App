package com.virlin.app.map

import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapEdit
import com.virlin.app.ui.map.MapEditHistory
import com.virlin.app.ui.map.MapEditHistories
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.applyStep
import com.virlin.app.ui.map.describe
import com.virlin.app.domain.structure.Placement
import com.virlin.app.domain.structure.PlacementCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapEditHistoryTest {
    @Test fun keepsLatestTwentyFiveAndRedoesInOrder() {
        val history = MapEditHistory()
        repeat(30) { index ->
            history.record(MapEdit.Appearance(MapAppearance(paletteIndex = index),
                MapAppearance(paletteIndex = index + 1)))
        }
        repeat(25) {
            val edit = history.undoCandidate()!!
            history.confirmUndo(edit)
        }
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(5, (history.redoCandidate() as MapEdit.Appearance).before.paletteIndex)
        repeat(25) {
            val edit = history.redoCandidate()!!
            history.confirmRedo(edit)
        }
        assertFalse(history.canRedo)
    }

    @Test fun newEditInvalidatesRedoOnlyAfterItIsRecorded() {
        val history = MapEditHistory()
        val first = MapEdit.Appearance(MapAppearance(), MapAppearance(paletteIndex = 1))
        history.record(first)
        history.confirmUndo(first)
        assertTrue(history.canRedo)
        history.record(MapEdit.Appearance(MapAppearance(), MapAppearance(paletteIndex = 2)))
        assertFalse(history.canRedo)
    }

    @Test fun undoingOneBoxColourKeepsChangesMadeSinceToOtherBoxes() {
        val before = MapAppearance(nodeColors = mapOf("a" to 1L))
        val after = before.copy(nodeColors = mapOf("a" to 2L))
        // Since then: another box was coloured and a box was hidden elsewhere.
        val current = after.copy(nodeColors = mapOf("a" to 2L, "b" to 9L), hiddenNodeIds = setOf("c"))
        val undone = current.applyStep(after, before)
        assertEquals(mapOf("a" to 1L, "b" to 9L), undone.nodeColors)
        assertEquals(setOf("c"), undone.hiddenNodeIds)
        val redone = undone.applyStep(before, after)
        assertEquals(mapOf("a" to 2L, "b" to 9L), redone.nodeColors)
    }

    @Test fun undoingAHideOnlyUnhidesThatBoxAndRemovedColoursStayRemoved() {
        val before = MapAppearance(hiddenNodeIds = setOf("x"))
        val after = before.copy(hiddenNodeIds = setOf("x", "y"), nodeColors = mapOf("y" to 5L))
        val undone = after.applyStep(after, before)
        assertEquals(setOf("x"), undone.hiddenNodeIds)
        assertFalse(undone.nodeColors.containsKey("y"))
    }

    @Test fun reshapingDropsOnlyMovesAndADiscardedStepStopsBlockingEarlierOnes() {
        val history = MapEditHistory()
        val look = MapEdit.Appearance(MapAppearance(), MapAppearance(paletteIndex = 1))
        val move = MapEdit.Move(Placement("t", "p", expectedRevision = 1),
            PlacementCommit(affectedIds = listOf("t"), targetParentId = "p"))
        val rename = MapEdit.Rename(MapKind.TASK, "t", "Old", "New")
        history.record(look); history.record(move); history.record(rename)
        history.dropMoves()
        assertEquals(rename, history.undoCandidate())
        history.discard(rename)
        assertEquals(look, history.undoCandidate())
        assertEquals("box colour", MapEdit.Appearance(MapAppearance(),
            MapAppearance(nodeColors = mapOf("t" to 1L))).describe())
    }

    @Test fun historyIsKeptPerProjectUntilForgotten() {
        val first = MapEditHistories.of("project-a")
        first.record(MapEdit.Rename(MapKind.TASK, "t", "Old", "New"))
        assertTrue(MapEditHistories.of("project-a").canUndo)
        assertFalse(MapEditHistories.of("project-b").canUndo)
        MapEditHistories.forget("project-a")
        assertFalse(MapEditHistories.of("project-a").canUndo)
    }
}
