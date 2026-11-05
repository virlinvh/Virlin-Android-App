package com.virlin.app.map

import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapEdit
import com.virlin.app.ui.map.MapEditHistory
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
}
