package com.virlin.app.now

import com.virlin.app.ui.screens.NeedsYouUrgency
import com.virlin.app.ui.screens.UrgencyLevel
import com.virlin.app.ui.screens.WaitingTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Needs You attention system — Phase 2: urgency = pure function(waitingDuration). Deterministic. */
class NeedsYouUrgencyTest {

    // ------------------------------------------------------------------ thresholds (whole seconds)

    @Test fun level1_from0() = assertEquals(UrgencyLevel.ATTENTION, UrgencyLevel.of(0))
    @Test fun level1_at0m59() = assertEquals(UrgencyLevel.ATTENTION, UrgencyLevel.of(59))
    @Test fun level2_at1m00() = assertEquals(UrgencyLevel.WAITING, UrgencyLevel.of(60))
    @Test fun level2_at2m59() = assertEquals(UrgencyLevel.WAITING, UrgencyLevel.of(179))
    @Test fun level3_at3m00() = assertEquals(UrgencyLevel.ELEVATED, UrgencyLevel.of(180))
    @Test fun level3_at4m59() = assertEquals(UrgencyLevel.ELEVATED, UrgencyLevel.of(299))
    @Test fun level4_at5m00() = assertEquals(UrgencyLevel.HIGH, UrgencyLevel.of(300))
    @Test fun level4_at9m59() = assertEquals(UrgencyLevel.HIGH, UrgencyLevel.of(599))
    @Test fun level5_at10m00() = assertEquals(UrgencyLevel.CRITICAL, UrgencyLevel.of(600))
    @Test fun level5_stays_after_hours() = assertEquals(UrgencyLevel.CRITICAL, UrgencyLevel.of(5 * 3600))
    @Test fun negative_clampsToLevel1() = assertEquals(UrgencyLevel.ATTENTION, UrgencyLevel.of(-10))

    @Test fun levels_areMonotonic_inRank() {
        var last = 0
        for (s in listOf(0L, 59L, 60L, 179L, 180L, 299L, 300L, 599L, 600L, 99_999L)) {
            val r = UrgencyLevel.of(s).rank
            assertTrue("rank must never decrease as waiting grows", r >= last); last = r
        }
    }

    // ------------------------------------------------------------------ palette mapping

    @Test fun eachLevel_hasItsOwnPalette() {
        val palettes = UrgencyLevel.values().map { NeedsYouUrgency.palette(it) }
        assertEquals(5, palettes.map { it.indicator }.distinct().size)
        assertEquals(5, palettes.map { it.cardBg }.distinct().size)
        assertEquals(5, palettes.map { it.chipFg }.distinct().size)
    }

    @Test fun glowStrength_increasesWithUrgency_andStaysRestrained() {
        val strengths = UrgencyLevel.values().map { NeedsYouUrgency.palette(it).glowStrength }
        assertEquals(strengths.sorted(), strengths)
        assertTrue(strengths.all { it in 0.05f..0.3f })
    }

    @Test fun level1_and_level3_keepTheApprovedSurfaces() {
        // The previously approved "due now" and "overdue" card surfaces are levels 1 and 3.
        assertEquals(0xFFFFFDF4.toInt(), NeedsYouUrgency.palette(UrgencyLevel.ATTENTION).cardBg.value.shr(32).toInt())
        assertEquals(0xFFFFF7F2.toInt(), NeedsYouUrgency.palette(UrgencyLevel.ELEVATED).cardBg.value.shr(32).toInt())
    }

    @Test fun glowCycle_withinBreathingBand_andQuickerWithUrgency() {
        val cycles = UrgencyLevel.values().map { NeedsYouUrgency.glowCycleMillis(it) }
        assertTrue(cycles.all { it in 2400..3500 })
        assertEquals(cycles.sortedDescending(), cycles)
    }

    @Test fun glowPhaseOffset_isDeterministic_andDiffersBetweenNeighbours() {
        assertEquals(NeedsYouUrgency.glowPhaseOffsetMillis(2), NeedsYouUrgency.glowPhaseOffsetMillis(2))
        assertTrue(NeedsYouUrgency.glowPhaseOffsetMillis(0) != NeedsYouUrgency.glowPhaseOffsetMillis(1))
    }

    // ------------------------------------------------------------------ timer continues through level transitions

    @Test fun timer_doesNotRestart_whenLevelChanges() {
        val since = Instant.parse("2026-09-20T10:00:00Z")
        fun at(sec: Long) = WaitingTime.elapsedSeconds(since, since.plusSeconds(sec))
        assertEquals(UrgencyLevel.HIGH, UrgencyLevel.of(at(599))); assertEquals("−09:59", WaitingTime.format(at(599)))
        assertEquals(UrgencyLevel.CRITICAL, UrgencyLevel.of(at(600))); assertEquals("−10:00", WaitingTime.format(at(600)))
        assertEquals("−10:01", WaitingTime.format(at(601)))   // keeps counting from the same origin
        assertEquals(UrgencyLevel.WAITING, UrgencyLevel.of(at(60))); assertEquals("−01:00", WaitingTime.format(at(60)))
    }

    // ------------------------------------------------------------------ reduced motion

    @Test fun reducedMotion_glowIsStatic_butVisible_andOrderedByLevel() {
        val a = UrgencyLevel.values().map { NeedsYouUrgency.glowAlpha(it, breath = 0f, reducedMotion = true) }
        val b = UrgencyLevel.values().map { NeedsYouUrgency.glowAlpha(it, breath = 1f, reducedMotion = true) }
        assertEquals(a, b)                              // breath position is ignored → no animation
        assertTrue(a.all { it > 0f })                   // hierarchy still visible
        assertEquals(a.sorted(), a)                     // stronger with urgency
    }

    @Test fun motion_glowBreathesBetweenFloorAndPeak_neverOff() {
        for (l in UrgencyLevel.values()) {
            val peak = NeedsYouUrgency.palette(l).glowStrength
            val lo = NeedsYouUrgency.glowAlpha(l, 0f, reducedMotion = false)
            val hi = NeedsYouUrgency.glowAlpha(l, 1f, reducedMotion = false)
            assertTrue(lo > 0f && lo < hi)
            assertEquals(peak, hi, 1e-6f)
        }
    }
}
