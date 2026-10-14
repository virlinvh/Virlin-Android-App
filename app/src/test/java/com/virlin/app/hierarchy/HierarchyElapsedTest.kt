package com.virlin.app.hierarchy

import com.virlin.app.ui.hierarchy.hierarchyElapsed
import org.junit.Assert.assertEquals
import org.junit.Test

class HierarchyElapsedTest {
    @Test fun unusedHoursAreHidden() {
        assertEquals("0:00", hierarchyElapsed(0))
        assertEquals("12:34", hierarchyElapsed(754))
        assertEquals("1:12:34", hierarchyElapsed(4354))
        assertEquals("0:00", hierarchyElapsed(-1))
    }
}
