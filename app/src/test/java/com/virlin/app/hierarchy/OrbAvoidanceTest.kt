package com.virlin.app.hierarchy

import androidx.compose.ui.geometry.Rect
import com.virlin.app.ui.components.orbEndClearance
import com.virlin.app.ui.components.circularOrbClearance
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbAvoidanceTest {
    @Test fun circularClearanceFollowsLocalBandInsteadOfWholeCard() {
        val circle = Rect(290f, 700f, 342f, 752f)
        val middle = circularOrbClearance(Rect(180f, 720f, 340f, 735f), circle, 8f)
        val edge = circularOrbClearance(Rect(180f, 693f, 340f, 695f), circle, 8f)
        assertEquals(58f, middle, 0.01f)
        org.junit.Assert.assertTrue(edge > 0f && edge < middle)
        assertEquals(0f, circularOrbClearance(Rect(180f, 660f, 340f, 680f), circle, 8f), 0f)
        assertEquals(0f, circularOrbClearance(Rect(20f, 720f, 170f, 735f), circle, 8f), 0f)
        assertEquals(0f, circularOrbClearance(Rect(180f, 720f, 340f, 735f), null, 8f), 0f)
    }
    private val orb = Rect(290f, 700f, 342f, 752f)
    @Test fun onlyIntersectingRowsReserveTheOrbAndGap() {
        assertEquals(58f, orbEndClearance(Rect(20f, 690f, 340f, 750f), orb, 8f), 0f)
        assertEquals(0f, orbEndClearance(Rect(20f, 600f, 340f, 650f), orb, 8f), 0f)
        assertEquals(0f, orbEndClearance(Rect(20f, 770f, 340f, 820f), orb, 8f), 0f)
        assertEquals(0f, orbEndClearance(Rect(20f, 700f, 270f, 750f), orb, 8f), 0f)
    }
    @Test fun absentOrbAndUnmeasuredRowsNeverShrink() {
        assertEquals(0f, orbEndClearance(null, orb, 8f), 0f)
        assertEquals(0f, orbEndClearance(Rect(0f, 0f, 320f, 800f), null, 8f), 0f)
    }
    @Test fun largerOrbReservesMoreAndClearanceIsBounded() {
        val row = Rect(20f, 700f, 340f, 750f)
        assertEquals(108f, orbEndClearance(row, Rect(240f, 690f, 342f, 790f), 8f), 0f)
        assertEquals(row.width, orbEndClearance(row, Rect(0f, 690f, 342f, 790f), 8f), 0f)
    }
}
