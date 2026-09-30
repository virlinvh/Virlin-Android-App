package com.virlin.app.now

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.virlin.app.ui.screens.NeedsYouPriority
import com.virlin.app.ui.screens.WaitingTime
import com.virlin.app.ui.theme.Charcoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Needs You rank → visual identity (Phase 02) and the HH:MM:SS timer presentation (Phase 01). */
class NeedsYouPriorityVisualsTest {

    private fun hex(c: Color) = "%08X".format(c.value.shr(32).toInt())

    // ------------------------------------------------------------------ the approved accents

    @Test fun ranks1to10_carryTheApprovedAccents() {
        val expected = listOf(
            0xFFD93636, 0xFFE64A35, 0xFFEF6332, 0xFFE89B00, 0xFFEBAF00,
            0xFFE8C400, 0xFFE6D43A, 0xFFE8E05A, 0xFFF1EA8E, 0xFFF7F5BC
        )
        expected.forEachIndexed { i, argb ->
            val v = NeedsYouPriority.visualsFor(i + 1)
            assertEquals("rank ${i + 1}", Color(argb), v.accent)
            assertFalse("rank ${i + 1} is a priority rank", v.neutral)
        }
    }

    @Test fun progression_isRedToPaleYellow_neverEndingInOrange() {
        val a = NeedsYouPriority.accents
        // Red block first, then a monotonically lighter yellow tail — no hue jump backwards.
        assertTrue("rank 1 darkest", a.first().luminance() < a.last().luminance())
        (4..9).forEach { i -> assertTrue("rank ${i + 1} lighter than rank $i", a[i].luminance() > a[i - 1].luminance()) }
        assertTrue("rank 10 reads pale", a[9].luminance() > 0.8f)
        assertTrue("rank 10 is yellow (r≈g, low b)", a[9].red > 0.9f && a[9].green > 0.9f && a[9].blue < 0.8f)
        assertTrue("rank 1 is red", a[0].red > 0.7f && a[0].green < 0.35f && a[0].blue < 0.35f)
    }

    // ------------------------------------------------------------------ neutral / invalid ranks

    @Test fun rank11AndAbove_andInvalidRanks_areTheSameNeutralIdentity() {
        val neutral = NeedsYouPriority.visualsFor(11)
        listOf(11, 12, 25, 50, 1000).forEach { assertEquals("rank $it", neutral, NeedsYouPriority.visualsFor(it)) }
        listOf(0, -1, -99, null).forEach { assertEquals("invalid rank $it", neutral, NeedsYouPriority.visualsFor(it)) }
        assertTrue(neutral.neutral)
        assertEquals(Color.White, neutral.surface)
        assertEquals(10, NeedsYouPriority.coloredRanks)
    }

    // ------------------------------------------------------------------ derivation + contrast

    @Test fun surfaceBorderContainer_areDerivedFromTheAccent_andStayLight() {
        (1..10).forEach { r ->
            val v = NeedsYouPriority.visualsFor(r)
            assertTrue("rank $r surface stays light", v.surface.luminance() > 0.78f)
            assertTrue("rank $r surface lighter than container", v.surface.luminance() > v.container.luminance())
            assertTrue("rank $r container lighter than border", v.container.luminance() > v.border.luminance())
            assertTrue("rank $r border lighter than accent", v.border.luminance() > v.accent.luminance() || v.accent.luminance() > 0.8f)
            assertEquals("rank $r primary text", Charcoal, v.content)
        }
    }

    @Test fun onAccent_flipsToDarkInkOnPaleAccents() {
        listOf(1, 2, 3).forEach { assertEquals("rank $it", Color.White, NeedsYouPriority.visualsFor(it).onAccent) }
        listOf(7, 8, 9, 10).forEach { assertEquals("rank $it", Charcoal, NeedsYouPriority.visualsFor(it).onAccent) }
    }

    @Test fun readableInk_isDarkerThanTheAccent_forPaleRanks() {
        (1..10).forEach { r ->
            val v = NeedsYouPriority.visualsFor(r)
            val ink = NeedsYouPriority.readableInk(v)
            assertTrue("rank $r ink readable on its container", ink.luminance() < 0.45f)
        }
        assertTrue(NeedsYouPriority.readableInk(NeedsYouPriority.visualsFor(11)).luminance() < 0.45f)
    }

    @Test fun resolver_isDeterministic_andAllocationFree_perCall() {
        assertTrue(NeedsYouPriority.visualsFor(4) === NeedsYouPriority.visualsFor(4))   // cached, not rebuilt per frame
        assertTrue(NeedsYouPriority.visualsFor(99) === NeedsYouPriority.visualsFor(-3))
    }

    // ------------------------------------------------------------------ timer presentation (Phase 01)

    @Test fun clockFormat_isFixedWidthHhMmSs() {
        assertEquals("00:00:00", WaitingTime.formatClock(0))
        assertEquals("00:00:05", WaitingTime.formatClock(5))
        assertEquals("00:04:19", WaitingTime.formatClock(259))
        assertEquals("00:59:59", WaitingTime.formatClock(3599))
        assertEquals("01:00:00", WaitingTime.formatClock(3600))
        assertEquals("01:07:38", WaitingTime.formatClock(4058))
        assertEquals("12:18:37", WaitingTime.formatClock(44317))
        assertEquals("100:00:00", WaitingTime.formatClock(360_000))          // 3-digit hours still render
        assertEquals("00:00:00", WaitingTime.formatClock(-30))               // never negative
        // Same width either side of the hour boundary → nothing moves when a digit changes.
        assertEquals(WaitingTime.formatClock(3599).length, WaitingTime.formatClock(3600).length)
    }

    @Test fun clockFormat_keepsTheExistingElapsedSemantics() {
        // Identical source seconds as the original compact format: presentation only.
        assertEquals("−08:32", WaitingTime.format(512))
        assertEquals("00:08:32", WaitingTime.formatClock(512))
        assertEquals("Waiting for 8 minutes 32 seconds", WaitingTime.describe(512))
    }
}
