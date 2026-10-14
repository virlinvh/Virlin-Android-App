package com.virlin.app.map

import com.virlin.app.ui.map.*
import org.junit.Assert.*
import org.junit.Test

class MapViewportControlsTest {
    @Test fun fitCanFrameHugeMapsBelowPreviousEighteenPercentLimit() {
        val zoom = fitMapZoom(360f, 700f, 1200f, 18000f)
        assertTrue(zoom < .18f)
        assertTrue(18000f * zoom <= 700f)
        assertTrue(1200f * zoom <= 360f)
    }
    @Test fun zoomKeepsCentreWorldPointFixedAndRoundTrips() {
        val offset = zoomMapOffset(-120f, 180f, .2f, .25f)
        assertEquals((180f + 120f) / .2f, (180f - offset) / .25f, .001f)
        assertEquals(-120f, zoomMapOffset(offset, 180f, .25f, .2f), .001f)
    }
    @Test fun taskFilterKeepsSubtasksAndAncestorsButRemovesEmptyBranchesWithoutMutatingSource() {
        val topics = listOf(
            MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("w", "p", "Work", kind = MapKind.WORKSTREAM),
            MapTopic("empty", "p", "Empty", kind = MapKind.WORKSTREAM),
            MapTopic("t", "w", "Task", detail = "knowledge", kind = MapKind.TASK),
            MapTopic("s", "t", "Subtask", kind = MapKind.TASK))
        val filtered = taskOnlyMapTopics(topics)
        assertEquals(listOf("p", "w", "t", "s"), filtered.map { it.id })
        assertEquals("knowledge", topics[3].detail)
        assertEquals("", filtered[2].detail)
        assertEquals(5, topics.size)
    }
}
