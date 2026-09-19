package com.virlin.app.domain

import com.virlin.app.domain.model.FocusInvestment
import com.virlin.app.domain.model.FocusSession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FocusInvestmentFormatTest {

    @Test
    fun formatCompact_policyExamples() {
        assertEquals("<1m", FocusInvestment.formatCompact(0))
        assertEquals("<1m", FocusInvestment.formatCompact(59))
        assertEquals("1m", FocusInvestment.formatCompact(60))
        assertEquals("25m", FocusInvestment.formatCompact(25 * 60L))
        assertEquals("59m", FocusInvestment.formatCompact(59 * 60L))
        assertEquals("1h", FocusInvestment.formatCompact(60 * 60L))
        assertEquals("1h 05m", FocusInvestment.formatCompact(65 * 60L))
        assertEquals("2h 18m", FocusInvestment.formatCompact(138 * 60L))
    }

    @Test
    fun formatInvested_appendsSuffix() {
        assertEquals("<1m invested", FocusInvestment.formatInvested(30))
        assertEquals("9m invested", FocusInvestment.formatInvested(9 * 60L))
        assertEquals("1h 05m invested", FocusInvestment.formatInvested(65 * 60L))
    }

    @Test
    fun priorClosed_excludesOpenSession() {
        val t0 = Instant.parse("2026-09-17T10:00:00Z")
        val sessions = listOf(
            FocusSession("a", "ws", null, t0, endedAt = t0.plusSeconds(300), taskId = "task"),
            FocusSession("b", "ws", null, t0.plusSeconds(400), endedAt = null, taskId = "task")
        )
        assertEquals(300L, FocusInvestment.priorClosedSeconds(sessions, "task"))
        assertEquals(0L, FocusInvestment.priorClosedSeconds(sessions, "other"))
        assertEquals(0L, FocusInvestment.priorClosedSeconds(sessions, null))
    }

    @Test
    fun total_includesOpenAtNow() {
        val t0 = Instant.parse("2026-09-17T10:00:00Z")
        val now = t0.plusSeconds(500)
        val sessions = listOf(
            FocusSession("a", "ws", null, t0, endedAt = t0.plusSeconds(300), taskId = "task"),
            FocusSession("b", "ws", null, t0.plusSeconds(400), endedAt = null, taskId = "task")
        )
        // closed 300 + open 100 = 400
        assertEquals(400L, FocusInvestment.total(sessions, "task", now).seconds)
    }

    @Test
    fun liveTotal_addsCurrentSessionWithoutDoubleCount() {
        assertEquals(9L * 60, FocusInvestment.liveTotalSeconds(5L * 60, 4 * 60))
        assertEquals(5L * 60, FocusInvestment.liveTotalSeconds(5L * 60, 0))
    }
}
