package com.virlin.app.hierarchy

import com.virlin.app.ui.hierarchy.flowingWaveHeadOpacity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowingWaveHeadTest {
    @Test fun dotFeathersAtBothEdgesWithoutAVisibleWrapJump() {
        assertEquals(0f, flowingWaveHeadOpacity(0f), 0f)
        assertEquals(0f, flowingWaveHeadOpacity(1f), 0f)
        assertTrue(flowingWaveHeadOpacity(.03f) < flowingWaveHeadOpacity(.10f))
        assertTrue(flowingWaveHeadOpacity(.97f) < flowingWaveHeadOpacity(.90f))
        assertEquals(1f, flowingWaveHeadOpacity(.5f), .001f)
    }
}
