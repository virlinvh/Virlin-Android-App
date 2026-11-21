package com.virlin.app.map

import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.toggleBoxColor
import com.virlin.app.ui.map.clearBoxColor
import com.virlin.app.ui.map.MapTopic
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapLayout
import com.virlin.app.ui.map.arrange
import com.virlin.app.ui.map.mapBranchTints
import com.virlin.app.ui.map.boxHighlightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MapBoxColorTest {
    @Test fun secondTapRemovesOnlyTheSelectedBoxOverride() {
        val original = MapAppearance(nodeColors = mapOf("other" to 0xFF123456))
        val selected = toggleBoxColor(original, "task", 0xFF52A887)
        assertEquals(0xFF52A887, selected.nodeColors["task"])
        assertEquals(original.nodeColors["other"], selected.nodeColors["other"])
        val cleared = toggleBoxColor(selected, "task", 0xFF52A887)
        assertFalse(cleared.nodeColors.containsKey("task"))
        assertEquals(original.nodeColors["other"], cleared.nodeColors["other"])
    }

    @Test fun colourCodeSetsWithoutTogglingOffAndKeepsOtherBoxes() {
        val original = MapAppearance(nodeColors = mapOf("other" to 0xFF123456))
        val once = com.virlin.app.ui.map.setBoxColor(original, "task", 0xFFFF8800)
        val twice = com.virlin.app.ui.map.setBoxColor(once, "task", 0xFFFF8800)
        assertEquals(0xFFFF8800, twice.nodeColors["task"])
        assertEquals(0xFF123456, twice.nodeColors["other"])
    }

    @Test fun nodeOverrideSurvivesProjectPaletteChanges() {
        val selected = toggleBoxColor(MapAppearance(), "task", 0xFF52A887)
        assertEquals(0xFF52A887, selected.copy(paletteIndex = 3).nodeColors["task"])
    }

    @Test fun noColorClearsOnlyThisBoxEvenWhenAnotherSwatchIsSelected() {
        val original = MapAppearance(nodeColors = mapOf(
            "task" to 0xFF52A887, "other" to 0xFF123456))
        val cleared = clearBoxColor(original, "task")
        assertFalse(cleared.nodeColors.containsKey("task"))
        assertEquals(0xFF123456, cleared.nodeColors["other"])
    }

    @Test fun foldTintFollowsBranchAndIgnoresBoxOnlyHighlight() {
        val geometry = arrange(listOf(
            MapTopic("project", null, "Project", kind = MapKind.PROJECT),
            MapTopic("workstream", "project", "Workstream", kind = MapKind.WORKSTREAM),
            MapTopic("task", "workstream", "Task", kind = MapKind.TASK),
        ), MapLayout.RIGHT_TREE)
        val appearance = MapAppearance(
            branchColors = mapOf("workstream" to 0xFFD28CC0),
            nodeColors = mapOf("task" to 0xFF7582D1),
        )
        val tints = mapBranchTints(geometry, appearance)
        assertEquals(0xFFD28CC0, tints["workstream"])
        assertEquals(0xFFD28CC0, tints["task"])
    }

    @Test fun yellowHighlightsAreAddedWithoutChangingExistingSwatches() {
        assertEquals(0xFF52A887, boxHighlightColors.first())
        assertEquals(0xFFE6C26C, boxHighlightColors[11])
        assertEquals(17, boxHighlightColors.size)
        assertEquals(5, boxHighlightColors.drop(12).distinct().size)
    }
}
