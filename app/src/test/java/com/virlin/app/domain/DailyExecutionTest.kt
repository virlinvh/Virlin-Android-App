package com.virlin.app.domain

import com.virlin.app.domain.model.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class DailyExecutionTest {
    @Test fun parallelRunsCountOnceForFreedAndSeparatelyForExternal() {
        val start = Instant.parse("2026-10-11T00:00:00Z")
        val end = start.plusSeconds(3600)
        val runs = listOf(ExternalRunInterval("a", "a", start, end), ExternalRunInterval("b", "b", start, end))
        val focus = listOf(FocusSession("f", "s", null, start, end, "other"))
        assertEquals(DailyExecutionTotals(7200, 3600), DailyExecution.calculate(runs, focus, end, ZoneOffset.UTC))
        val same = listOf(FocusSession("f", "s", null, start, end, "a"))
        assertEquals(DailyExecutionTotals(3600, 0), DailyExecution.calculate(runs.take(1), same, end, ZoneOffset.UTC))
    }
    @Test fun clipsMidnightAndDeduplicatesRecords() {
        val now = Instant.parse("2026-10-11T01:00:00Z")
        val run = ExternalRunInterval("a", "a", now.minusSeconds(7200), now.plusSeconds(600))
        assertEquals(DailyExecutionTotals(3600, 3600), DailyExecution.calculate(listOf(run, run), emptyList(), now, ZoneOffset.UTC))
    }
}
