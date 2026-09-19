package com.virlin.app.domain

import com.virlin.app.domain.model.FocusInvestment
import com.virlin.app.domain.model.FocusSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Startup / empty / malformed FocusSession history must never break cumulative math.
 */
class FocusInvestmentEmptyHistoryTest {

    private val t0 = Instant.parse("2026-09-18T00:00:00Z")

    @Test
    fun emptySessions_andNullTask_areZero() {
        assertEquals(0L, FocusInvestment.priorClosedSeconds(emptyList(), "t"))
        assertEquals(0L, FocusInvestment.priorClosedSeconds(emptyList(), null))
        assertTrue(FocusInvestment.total(emptyList(), "t", t0).isZero)
        assertEquals(0L, FocusInvestment.liveTotalSeconds(0, 0))
        assertEquals("<1m invested", FocusInvestment.formatInvested(0))
    }

    @Test
    fun openOnly_priorZero_totalUsesNow() {
        val open = FocusSession("fs", "ws", null, t0, endedAt = null, taskId = "t")
        assertEquals(0L, FocusInvestment.priorClosedSeconds(listOf(open), "t"))
        assertEquals(120L, FocusInvestment.total(listOf(open), "t", t0.plusSeconds(120)).seconds)
    }

    @Test
    fun invertedTimestamps_degradeToZero_notThrow() {
        val bad = FocusSession(
            "fs", "ws", null,
            startedAt = t0.plusSeconds(100),
            endedAt = t0,
            taskId = "t"
        )
        assertEquals(0L, FocusInvestment.priorClosedSeconds(listOf(bad), "t"))
        assertTrue(FocusInvestment.total(listOf(bad), "t", t0).isZero)
    }

    @Test
    fun deletedTaskIdMismatch_ignored() {
        val closed = FocusSession("fs", "ws", null, t0, endedAt = t0.plusSeconds(60), taskId = "gone")
        assertEquals(0L, FocusInvestment.priorClosedSeconds(listOf(closed), "current"))
    }
}
