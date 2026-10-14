package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MasterPauseTest {
    private val start = Instant.parse("2026-10-09T00:00:00Z")
    @Test fun pausesHumanAndMultipleExternalLeavesPreservesTotalsAndIsIdempotent() = runTest {
        val stream = WorkStream("s", "Stream", state = WorkStreamState.READY, createdAt = start, updatedAt = start)
        fun task(id: String, mode: ExecutionPreference) = Task(id, id, workStreamId = "s",
            createdAt = start, updatedAt = start, executionPreference = mode)
        val repo = InMemoryWorkStreamRepository(listOf(stream), seedTasks = listOf(
            task("human", ExecutionPreference.HUMAN), task("a", ExecutionPreference.EXTERNAL),
            task("b", ExecutionPreference.EXTERNAL), task("ready", ExecutionPreference.HUMAN)), taskExecutionEnabled = true)
        val clock = FakeClock(start)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(actions.startFocus("human") is ActionResult.Success)
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "a")) is ActionResult.Success)
        clock.current = start.plusSeconds(120)
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "b")) is ActionResult.Success)
        clock.current = start.plusSeconds(600)
        assertEquals(ActionResult.Success(3), actions.pauseAllTimers())
        for (id in listOf("human", "a", "b")) {
            val execution = repo.getTask(id)!!.execution!!
            assertEquals(WorkStreamState.PAUSED, execution.state)
            assertNull(execution.processingStartedAt)
            assertNull(execution.checkAt)
        }
        assertEquals(WorkStreamState.READY, repo.getTask("ready")!!.executionStream().state)
        assertEquals(start.plusSeconds(600), repo.allFocusSessions().single().endedAt)
        clock.current = start.plusSeconds(3600)
        assertEquals(600L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
        assertEquals(480L, ExternalTiming.seconds(repo.getTask("b")!!.execution, clock.current))
        assertEquals(600L, ExecutionTiming.workStream(stream, repo.tasks.value, repo.allFocusSessions(), clock.current).totalFocusSeconds)
        assertEquals(ActionResult.Success(0), actions.pauseAllTimers())
        val restored = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(restored.resumeExternalTracking("a") is ActionResult.Success)
        clock.current = start.plusSeconds(3660)
        assertEquals(660L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
        assertEquals(480L, ExternalTiming.seconds(repo.getTask("b")!!.execution, clock.current))
    }

    @Test fun includesLegacyExternalCheckAndLeavesInactiveStateUntouched() = runTest {
        val active = WorkStream("external", "External", state = WorkStreamState.CHECK,
            processingStartedAt = start, externalTrackedSeconds = 40, checkAt = start.plusSeconds(60),
            createdAt = start, updatedAt = start)
        val waiting = active.copy(id = "waiting", state = WorkStreamState.SNOOZED, processingStartedAt = null)
        val repo = InMemoryWorkStreamRepository(listOf(active, waiting))
        val clock = FakeClock(start.plusSeconds(600))
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertEquals(ActionResult.Success(1), actions.pauseAllTimers())
        assertEquals(640L, repo.getStream("external")!!.externalTrackedSeconds)
        assertEquals(waiting, repo.getStream("waiting"))
        assertTrue(repo.getStream("external")!!.externalTrackingPaused)
    }

    @Test fun committedPauseCancelsExternalCheckAlarm() = runTest {
        val stream = WorkStream("s", "External", state = WorkStreamState.PROCESSING,
            processingStartedAt = start, checkAt = start.plusSeconds(600), createdAt = start, updatedAt = start)
        val inner = InMemoryWorkStreamRepository(listOf(stream))
        val scheduler = com.virlin.app.domain.schedule.FakeAttentionScheduler()
        scheduler.sync(stream)
        assertTrue(scheduler.current.containsKey("s"))
        val repo = com.virlin.app.domain.schedule.SchedulingWorkStreamRepository(inner, scheduler)
        val actions = DefaultVirlinActions(repo, FakeClock(start.plusSeconds(60)), SequentialIdProvider())
        assertEquals(ActionResult.Success(1), actions.pauseAllTimers())
        assertFalse(scheduler.current.containsKey("s"))
    }

    @Test fun restartThenResumeRestoresOnlyMasterGroupAndDoesNotCountPausedGap() = runTest {
        val stream = WorkStream("s", "Stream", state = WorkStreamState.READY, createdAt = start, updatedAt = start)
        fun task(id: String, mode: ExecutionPreference) = Task(id, id, workStreamId = "s",
            createdAt = start, updatedAt = start, executionPreference = mode)
        val repo = InMemoryWorkStreamRepository(listOf(stream), seedTasks = listOf(
            task("human", ExecutionPreference.HUMAN), task("a", ExecutionPreference.EXTERNAL),
            task("b", ExecutionPreference.EXTERNAL), task("manual", ExecutionPreference.HUMAN)
                .copy(execution = TaskExecution(state = WorkStreamState.PAUSED))), taskExecutionEnabled = true)
        val clock = FakeClock(start)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        actions.startFocus("human")
        actions.startExternalWork(StartExternalWork("s", workItemId = "a"))
        actions.startExternalWork(StartExternalWork("s", workItemId = "b"))
        clock.current = start.plusSeconds(600)
        assertEquals(ActionResult.Success(3), actions.pauseAllTimers())
        clock.current = start.plusSeconds(3600)
        val restored = DefaultVirlinActions(repo, clock, com.virlin.app.domain.id.UuidIdProvider)
        assertEquals(ActionResult.Success(3), restored.resumeAllTimers())
        assertEquals(ActionResult.Success(0), restored.resumeAllTimers())
        assertEquals(WorkStreamState.PAUSED, repo.getTask("manual")!!.execution!!.state)
        assertEquals(1, repo.allFocusSessions().count { it.isOpen })
        clock.current = start.plusSeconds(3660)
        assertEquals(660L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
        assertEquals(660L, ExecutionTiming.workStream(stream, repo.tasks.value, repo.allFocusSessions(), clock.current).totalFocusSeconds)
        assertEquals(ActionResult.Success(3), restored.pauseAllTimers())
        clock.current = start.plusSeconds(7200)
        assertEquals(ActionResult.Success(3), restored.resumeAllTimers())
    }

    @Test fun resumeRestoresRemainingCheckTimeAndDoesNotDisplaceNewHumanFocus() = runTest {
        val external = WorkStream("external", "External", state = WorkStreamState.PROCESSING,
            processingStartedAt = start, checkAt = start.plusSeconds(600), createdAt = start, updatedAt = start)
        val human = external.copy(id = "human", state = WorkStreamState.READY, processingStartedAt = null, checkAt = null)
        val other = human.copy(id = "other")
        val inner = InMemoryWorkStreamRepository(listOf(external, human, other))
        val scheduler = com.virlin.app.domain.schedule.FakeAttentionScheduler()
        val repo = com.virlin.app.domain.schedule.SchedulingWorkStreamRepository(inner, scheduler)
        val clock = FakeClock(start)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        actions.focusStream("human")
        clock.current = start.plusSeconds(120)
        assertEquals(ActionResult.Success(2), actions.pauseAllTimers())
        actions.focusStream("other")
        clock.current = start.plusSeconds(1000)
        assertEquals(ActionResult.Success(1), actions.resumeAllTimers())
        assertEquals(WorkStreamState.FOCUS, repo.getStream("other")!!.state)
        assertEquals(WorkStreamState.PAUSED, repo.getStream("human")!!.state)
        assertEquals(start.plusSeconds(1480), repo.getStream("external")!!.checkAt)
        assertTrue(scheduler.current.containsKey("external"))
    }
}
