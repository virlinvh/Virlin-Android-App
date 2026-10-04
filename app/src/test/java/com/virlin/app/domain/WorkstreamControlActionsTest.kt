package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WorkstreamControlActionsTest {
    @Test fun renameWorkstream_preservesExecutionTimestamps_andRejectsBlank() = runTest {
        val original = stream("e", WorkStreamState.PROCESSING).copy(processingStartedAt = now.minusSeconds(60), checkAt = now.plusSeconds(600))
        val repo = InMemoryWorkStreamRepository(seed = listOf(original))
        val actions = DefaultVirlinActions(repo, FakeClock(now.plusSeconds(30)), SequentialIdProvider())
        assertTrue(actions.updateContext("e", ContextUpdate(title = Field.Set("Renamed"))) is ActionResult.Success)
        assertEquals(original.copy(title = "Renamed"), repo.getStream("e"))
        assertTrue(actions.updateContext("e", ContextUpdate(title = Field.Set(" "))) is ActionResult.Rejected)
    }
    @Test fun completingActiveExternalTask_removesGhostRun_andSavesTime() = runTest {
        val task = Task("leaf", "Leaf", workStreamId = "e", createdAt = now, updatedAt = now)
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream("e", WorkStreamState.PROCESSING).copy(
            activeTaskId = task.id, processingStartedAt = now.minusSeconds(60), checkAt = now.plusSeconds(600))), seedTasks = listOf(task))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.completeTask(task.id) is ActionResult.Success)
        val stored = repo.getStream("e")!!
        assertEquals(WorkStreamState.READY, stored.state)
        assertNull(stored.activeTaskId); assertNull(stored.checkAt); assertNull(stored.processingStartedAt)
        assertEquals(60L, stored.externalTrackedSeconds)
        assertTrue(com.virlin.app.domain.external.ExternalWork.workingForYou(repo.streams.value, now).isEmpty())
        assertTrue(com.virlin.app.ui.screens.NowPresentation.attention(repo.streams.value).isEmpty())
    }
    @Test fun anotherTaskCannotOverwriteAnExternalRun() = runTest {
        val first = Task("first", "First", workStreamId = "e", createdAt = now, updatedAt = now)
        val second = first.copy(id = "second", title = "Second")
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream("e", WorkStreamState.PROCESSING).copy(
            activeTaskId = first.id, processingStartedAt = now)), seedTasks = listOf(first, second))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.startExternalWork(StartExternalWork("e", workItemId = second.id)) is ActionResult.Rejected)
        assertTrue(actions.setActiveTask("e", second.id) is ActionResult.Rejected)
        assertEquals(first.id, repo.getStream("e")!!.activeTaskId)
        assertEquals(now, repo.getStream("e")!!.processingStartedAt)
    }
    @Test fun pausedExternal_canBecomeHuman_andFocus_withoutStaleTrackingMarker() = runTest {
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream("e", WorkStreamState.PAUSED).copy(
            executionPreference = ExecutionPreference.EXTERNAL, externalTrackingPaused = true,
            externalTrackedSeconds = 120)))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.setWorkStreamExecutionPreference("e", ExecutionPreference.HUMAN) is ActionResult.Success)
        assertFalse(repo.getStream("e")!!.externalTrackingPaused)
        assertTrue(actions.focusStream("e") is ActionResult.Success)
        assertEquals(120L, repo.getStream("e")!!.externalTrackedSeconds)
        assertTrue(actions.pauseStream("e") is ActionResult.Success)
        assertEquals(WorkStreamState.PAUSED, repo.getStream("e")!!.state)
        assertTrue(actions.focusStream("e") is ActionResult.Success)
    }
    @Test fun externalPause_savesTime_resumeExcludesPause_andNeverDisplacesHuman() = runTest {
        val clock = FakeClock(now)
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream("h", WorkStreamState.FOCUS),
            stream("e", WorkStreamState.PROCESSING).copy(processingStartedAt = now.minusSeconds(120))))
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(actions.pauseExternalTracking("e", now.plusSeconds(600)) is ActionResult.Success)
        val paused = repo.getStream("e")!!
        assertEquals(120L, paused.externalTrackedSeconds)
        assertNull(paused.processingStartedAt)
        assertTrue(paused.externalTrackingPaused)
        clock.advance(java.time.Duration.ofMinutes(10))
        assertTrue(actions.checkDue("e") is ActionResult.Success)
        assertTrue(repo.getStream("e")!!.externalTrackingPaused)
        assertNull(repo.getStream("e")!!.processingStartedAt)
        // Reconstruct actions from persisted state, as after a process restart.
        val restarted = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(restarted.resumeExternalTracking("e") is ActionResult.Success)
        assertEquals(clock.now(), repo.getStream("e")!!.processingStartedAt)
        clock.advance(java.time.Duration.ofSeconds(30))
        assertTrue(restarted.pauseExternalTracking("e") is ActionResult.Success)
        assertEquals(150L, repo.getStream("e")!!.externalTrackedSeconds)
        assertNull(repo.getStream("e")!!.checkAt)
        assertEquals(WorkStreamState.FOCUS, repo.getStream("h")!!.state)
    }
    private val now = Instant.parse("2026-10-03T10:00:00Z")
    private fun stream(id: String, state: WorkStreamState) = WorkStream(id, id, state = state, createdAt = now, updatedAt = now)
    @Test fun paused_canScheduleReturn_withoutStartingFocus() = runTest {
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream("p", WorkStreamState.PAUSED)))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.snoozeStream("p", now.plusSeconds(300)) is ActionResult.Success)
        val stored = repo.getStream("p")!!
        assertEquals(WorkStreamState.SNOOZED, stored.state)
        assertEquals(SnoozeReason.HUMAN_RETURN, stored.snoozeReason)
        assertEquals(now.plusSeconds(300), stored.checkAt)
    }
    @Test fun blockReason_editsInPlace_andUnblockDoesNotFocus() = runTest {
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream("b", WorkStreamState.BLOCKED)))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.updateContext("b", ContextUpdate(blockerReason = Field.Set("Awaiting input"))) is ActionResult.Success)
        assertEquals("Awaiting input", repo.getStream("b")!!.blockerReason)
        assertEquals(WorkStreamState.BLOCKED, repo.getStream("b")!!.state)
        assertTrue(actions.unblockStream("b") is ActionResult.Success)
        assertEquals(WorkStreamState.READY, repo.getStream("b")!!.state)
        assertNull(repo.getStream("b")!!.blockerReason)
    }
    @Test fun blockReason_rejectsNonBlockedWork() = runTest {
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream("r", WorkStreamState.READY)))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.updateContext("r", ContextUpdate(blockerReason = Field.Set("Wrong"))) is ActionResult.Rejected)
        assertNull(repo.getStream("r")!!.blockerReason)
    }
    @Test fun externalStart_doesNotDisplaceOtherHumanFocus() = runTest {
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream("h", WorkStreamState.FOCUS),
            stream("e", WorkStreamState.READY).copy(executionPreference = ExecutionPreference.EXTERNAL)))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.startExternalWork(StartExternalWork("e", checkInMinutes = 5)) is ActionResult.Success)
        assertEquals(WorkStreamState.FOCUS, repo.getStream("h")!!.state)
        assertEquals(WorkStreamState.PROCESSING, repo.getStream("e")!!.state)
        assertEquals(now.plusSeconds(300), repo.getStream("e")!!.checkAt)
    }
    @Test fun confirmedResult_stopsTracking_andKeepsReviewReason_withoutCompleting() = runTest {
        val repo = InMemoryWorkStreamRepository(seed = listOf(stream("e", WorkStreamState.PROCESSING)
            .copy(processingStartedAt = now.minusSeconds(120))))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.markExternalResultReady("e") is ActionResult.Success)
        val result = repo.getStream("e")!!
        assertEquals(WorkStreamState.CHECK, result.state)
        assertNull(result.processingStartedAt)
        assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, result.snoozeReason)
        assertTrue(actions.deferReturn("e", now.plusSeconds(300)) is ActionResult.Success)
        assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, repo.getStream("e")!!.snoozeReason)
    }
}
