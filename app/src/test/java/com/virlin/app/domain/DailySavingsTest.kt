package com.virlin.app.domain

import com.virlin.app.domain.model.*
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DailySavingsTest {
    @Test fun distinguishesNoResultsMissingEstimateAndMeasuredZero() {
        assertEquals("—", DailySavings.calculate(emptyList(), emptyList(), now, zone).displayValue)
        assertEquals("No estimate", DailySavings.calculate(listOf(task("a", null)), emptyList(), now, zone).displayValue)
        val session = FocusSession("s", "w", null, now.minusSeconds(7200), now, "a")
        assertEquals("0m", DailySavings.calculate(listOf(task("a")), listOf(session), now, zone).displayValue)
    }
    @Test fun unfinishedWorkIsNotSavingsAndOpenFocusIsCappedAtCompletion() {
        val unfinished = task("running").copy(status = TaskStatus.IN_PROGRESS, completedAt = null)
        assertEquals("—", DailySavings.calculate(listOf(unfinished), emptyList(), now, zone).displayValue)
        val completed = task("a").copy(completedAt = now.minusSeconds(600))
        val open = FocusSession("s", "w", null, now.minusSeconds(1200), null, "a")
        assertEquals(3000L, DailySavings.calculate(listOf(completed), listOf(open), now, zone).seconds)
    }
    private val now = Instant.parse("2026-10-10T10:00:00Z")
    private val zone = ZoneId.of("UTC")
    private fun task(id: String, minutes: Long? = 60) = Task(id, id,
        status = TaskStatus.DONE, estimatedEffort = minutes?.let(Duration::ofMinutes),
        createdAt = now.minusSeconds(7200), updatedAt = now, completedAt = now,
        execution = TaskExecution(externalTrackedSeconds = 3600))
    @Test fun subtractsHumanEffortOnceAndNeverAddsExternalRuntime() {
        val session = FocusSession("s", "w", null, now.minusSeconds(900), now, "a")
        val result = DailySavings.calculate(listOf(task("a"), task("a")), listOf(session, session), now, zone)
        assertEquals(2700L, result.seconds)
        assertEquals(1, result.estimatedTasks)
    }
    @Test fun excludesParentsCancelledHumanOnlyAndOtherDays() {
        val parent = task("parent")
        val child = task("child").copy(parentTaskId = "parent")
        val excluded = listOf(task("cancelled").copy(status = TaskStatus.CANCELLED),
            task("human").copy(execution = null), task("yesterday").copy(completedAt = now.minusSeconds(86400)))
        assertEquals(3600L, DailySavings.calculate(listOf(parent, child) + excluded, emptyList(), now, zone).seconds)
    }
    @Test fun missingEstimatesAndOverspendAreNotFabricatedSavings() {
        val session = FocusSession("s", "w", null, now.minusSeconds(7200), now, "a")
        val result = DailySavings.calculate(listOf(task("a"), task("b", null)), listOf(session), now, zone)
        assertEquals(0L, result.seconds)
        assertEquals(1, result.missingEstimates)
    }
}
