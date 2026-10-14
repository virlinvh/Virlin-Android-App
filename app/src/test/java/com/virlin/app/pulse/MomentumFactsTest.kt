package com.virlin.app.pulse

import com.virlin.app.ui.pulse.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MomentumFactsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val monday = LocalDate.of(2026, 10, 12)
    private fun at(day: Int, hour: Int) = LocalDate.of(2026, 10, day).atTime(hour, 0).atZone(zone).toInstant()

    @Test fun outcomesAndDaysComeOnlyFromRecordedEvents() {
        val input = PulseInput(
            intervals = listOf(
                PulseInterval("focus", at(12, 9), at(12, 10), PulseKind.FOCUS, "p", "w", "t"),
                PulseInterval("paused", at(13, 9), at(13, 10), PulseKind.FOCUS, "p", "w", "t", paused = true),
                PulseInterval("external", at(14, 9), at(14, 10), PulseKind.EXTERNAL, "p", "w", "t")
            ),
            projects = listOf(PulseProject("p", "Project")),
            workstreams = listOf(PulseWorkstream("w", "p", "Stream")),
            tasks = listOf(PulseTask("t", "Task", "w", at(14, 11), "p")),
            captures = listOf(PulseCapture("c", at(15, 8), "p"))
        )
        val now = at(18, 12)
        val report = calculatePulse(input, PulsePeriod.WEEK, monday, zone, now)
        val facts = momentumFacts(input, report, zone, now)
        assertEquals(2, facts.activeDays)
        assertEquals(7, facts.availableDays)
        assertEquals(1, facts.completedTasks)
        assertEquals(1, facts.advancedWorkstreams)
        assertEquals(1, facts.capturedItems)
    }

    @Test fun allTimeStartsAtFirstRecordedActivityForRing() {
        val input = PulseInput(
            intervals = listOf(PulseInterval("focus", at(12, 9), at(12, 10), PulseKind.FOCUS)),
            projects = emptyList(), workstreams = emptyList(), tasks = emptyList()
        )
        val now = at(14, 12)
        val report = calculatePulse(input, PulsePeriod.ALL, LocalDate.of(2026, 10, 14), zone, now)
        val facts = momentumFacts(input, report, zone, now)
        assertEquals(1, facts.activeDays)
        assertEquals(3, facts.availableDays)
        assertEquals(listOf("12", "13", "14"), report.buckets.map { it.label })
        assertEquals(60 * 60 * 1000L, report.focusMs)
    }
}
