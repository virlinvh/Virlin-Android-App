package com.virlin.app.streams

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.virlin.app.ui.hierarchy.ProjectHeaderScrollConnection
import org.junit.Assert.*
import org.junit.Test

class ProjectHeaderScrollConnectionTest {
    @Test fun upwardCollapsesBeforeListAndDownwardWaitsForListTop() {
        var offset = 0f
        val connection = ProjectHeaderScrollConnection({ true }, { 100f }, { offset }, { offset = it })
        val source = NestedScrollSource.Drag
        assertEquals(-100f, connection.onPreScroll(Offset(0f, -140f), source).y)
        assertEquals(-100f, offset)
        assertEquals(Offset.Zero, connection.onPreScroll(Offset(0f, 60f), source))
        assertEquals(Offset.Zero, connection.onPostScroll(Offset(0f, 60f), Offset.Zero, source))
        assertEquals(-100f, offset)
        // The list consumed 40px to reach its top; only its remaining 20px reveals the header.
        assertEquals(20f, connection.onPostScroll(Offset(0f, 40f), Offset(0f, 20f), source).y)
        assertEquals(-80f, offset)
        assertEquals(80f, connection.onPostScroll(Offset.Zero, Offset(0f, 200f), source).y)
        assertEquals(0f, offset)
    }
    @Test fun otherTabsAndZeroHeightConsumeNothing() {
        var offset = 0f
        val disabled = ProjectHeaderScrollConnection({ false }, { 100f }, { offset }, { offset = it })
        val zero = ProjectHeaderScrollConnection({ true }, { 0f }, { offset }, { offset = it })
        assertEquals(Offset.Zero, disabled.onPreScroll(Offset(0f, -60f), NestedScrollSource.Drag))
        assertEquals(Offset.Zero, zero.onPreScroll(Offset(0f, -60f), NestedScrollSource.Drag))
        assertEquals(0f, offset)
    }
}
