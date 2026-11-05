package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ExternalTimingTest {
    private val start = Instant.parse("2026-10-07T00:00:00Z")
    private fun task(id: String, parent: String? = null) = Task(id, id, projectId = "p", workStreamId = "s",
        parentTaskId = parent, executionPreference = ExecutionPreference.EXTERNAL, createdAt = start, updatedAt = start)
    private fun stream() = WorkStream("s", "Stream", projectId = "p", state = WorkStreamState.READY, createdAt = start, updatedAt = start)
    @Test fun pauseOneLeafResumeAndRestartPreserveIndependentTotalsAndDates() = runTest {
        val repo = InMemoryWorkStreamRepository(listOf(stream()), seedTasks = listOf(task("parent"), task("a", "parent"), task("b", "parent")), taskExecutionEnabled = true)
        val clock = FakeClock(start)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "a")) is ActionResult.Success)
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "b")) is ActionResult.Success)
        clock.current = start.plusSeconds(600)
        assertTrue(actions.pauseExternalTracking("a") is ActionResult.Success)
        clock.current = start.plusSeconds(900)
        val totals = ExternalTiming.inclusive(repo.tasks.value, clock.current)
        assertEquals(600L, totals["a"])
        assertEquals(900L, totals["b"])
        assertEquals(1500L, totals["parent"])
        assertEquals(1500L, ExternalTiming.workStream(stream(), repo.tasks.value, clock.current))
        assertEquals(1500L, ExternalTiming.project("p", listOf(stream()), repo.tasks.value, clock.current))
        // Recreating the action engine simulates restored persisted records, not a fresh clock.
        val restored = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(restored.resumeExternalTracking("a") is ActionResult.Success)
        clock.current = start.plusSeconds(1200)
        assertEquals(900L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
        assertEquals(1200L, ExternalTiming.seconds(repo.getTask("b")!!.execution, clock.current))
        assertEquals(start.plusSeconds(900), repo.getTask("a")!!.execution!!.processingStartedAt)
        assertEquals(start, repo.getTask("b")!!.execution!!.processingStartedAt)
        assertTrue(restored.completeTask("a") is ActionResult.Success)
        clock.current = start.plusSeconds(1500)
        assertEquals(900L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
        assertEquals(1500L, ExternalTiming.seconds(repo.getTask("b")!!.execution, clock.current))
    }
    @Test fun restartRunningWorkDoesNotDiscardItsPreviousSegment() = runTest {
        val repo = InMemoryWorkStreamRepository(listOf(stream()), seedTasks = listOf(task("a")), taskExecutionEnabled = true)
        val clock = FakeClock(start)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        actions.startExternalWork(StartExternalWork("s", workItemId = "a"))
        clock.current = start.plusSeconds(600)
        actions.startExternalWork(StartExternalWork("s", workItemId = "a"))
        clock.current = start.plusSeconds(900)
        assertEquals(900L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
    }
    @Test fun pausedAndFutureStartsNeverAddPausedOrNegativeTime() {
        assertEquals(120L, ExternalTiming.seconds(TaskExecution(processingStartedAt = start, externalTrackedSeconds = 120, externalTrackingPaused = true), start.plusSeconds(600)))
        assertEquals(120L, ExternalTiming.seconds(TaskExecution(processingStartedAt = start.plusSeconds(600), externalTrackedSeconds = 120), start))
    }
    @Test fun databaseMappingPreservesTimingAndFiftySixChildrenCountOnce() {
        val execution = TaskExecution(state = WorkStreamState.PROCESSING, processingStartedAt = start,
            externalTrackedSeconds = 123, checkAt = start.plusSeconds(3600))
        val original = task("leaf").copy(execution = execution)
        val restored = with(com.virlin.app.data.db.VirlinMappers) { original.toEntity().toDomain() }
        assertEquals(execution, restored.execution)
        assertEquals(723L, ExternalTiming.seconds(restored.execution, start.plusSeconds(600)))
        val parent = task("parent")
        val children = (1..56).map { task("c$it", "parent").copy(execution = execution.copy(processingStartedAt = null, externalTrackedSeconds = 600)) }
        val all = listOf(parent) + children
        assertEquals(33600L, ExternalTiming.inclusive(all, start)[parent.id])
        assertEquals(33600L, ExternalTiming.project("p", listOf(stream()), all, start))
        assertEquals(33600L, ExternalTiming.project("p", listOf(stream()), all.map { it.copy(projectId = null) }, start))
        assertEquals(0L, ExternalTiming.project("unrelated", listOf(stream()), all, start))
    }
    @Test fun changingStagesBanksTheRunningSegmentAndCheckDueDoesNotStopIt() = runTest {
        val repo = InMemoryWorkStreamRepository(listOf(stream()), seedTasks = listOf(task("a")), taskExecutionEnabled = true)
        val clock = FakeClock(start)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "a", checkInMinutes = 1,
            stages = listOf(NewExternalStage("First"), NewExternalStage("Second")))) is ActionResult.Success)
        clock.current = start.plusSeconds(600)
        assertTrue(actions.checkDue("a") is ActionResult.Success)
        assertEquals(600L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
        assertTrue(actions.startNextExternalStage("a") is ActionResult.Success)
        clock.current = start.plusSeconds(900)
        assertEquals(900L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
        assertTrue(actions.markExternalResultReady("a") is ActionResult.Success)
        clock.current = start.plusSeconds(1200)
        assertEquals(900L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
    }
}
