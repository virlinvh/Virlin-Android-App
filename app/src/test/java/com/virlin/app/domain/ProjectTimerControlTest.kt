package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProjectTimerControlTest {
    private val start = Instant.parse("2026-10-09T00:00:00Z")
    @Test fun scopesNestedAndLegacyTimersAndLeavesOtherProjectUntouched() = runTest {
        fun stream(id: String, project: String) = WorkStream(id, id, projectId = project,
            state = WorkStreamState.READY, createdAt = start, updatedAt = start)
        fun task(id: String, ws: String, project: String, mode: ExecutionPreference, parent: String? = null) =
            Task(id, id, workStreamId = ws, projectId = project, parentTaskId = parent,
                executionPreference = mode, createdAt = start, updatedAt = start)
        val legacy = stream("legacy", "p").copy(state = WorkStreamState.PROCESSING, processingStartedAt = start)
        val repo = InMemoryWorkStreamRepository(listOf(stream("s", "p"), stream("other", "q"), legacy), seedTasks = listOf(
            task("parent", "s", "p", ExecutionPreference.HUMAN),
            task("human", "s", "p", ExecutionPreference.HUMAN, "parent"),
            task("a", "s", "p", ExecutionPreference.EXTERNAL, "parent"),
            task("b", "other", "q", ExecutionPreference.EXTERNAL)), taskExecutionEnabled = true)
        val clock = FakeClock(start)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        actions.startFocus("human")
        actions.startExternalWork(StartExternalWork("s", workItemId = "a"))
        actions.startExternalWork(StartExternalWork("other", workItemId = "b"))
        val untouched = repo.getTask("b")
        clock.current = start.plusSeconds(600)
        assertEquals(ActionResult.Success(3), actions.pauseAllTimers("p"))
        assertEquals(untouched, repo.getTask("b"))
        assertEquals(ActionResult.Success(0), actions.resumeAllTimers("q"))
        clock.current = start.plusSeconds(3600)
        assertEquals(ActionResult.Success(3), actions.resumeAllTimers("p"))
        clock.current = start.plusSeconds(3660)
        assertEquals(660L, ExternalTiming.seconds(repo.getTask("a")!!.execution, clock.current))
        assertEquals(3660L, ExternalTiming.seconds(repo.getTask("b")!!.execution, clock.current))
        assertEquals(660L, ExternalTiming.seconds(repo.getStream("legacy")!!, clock.current))
        assertEquals(1, repo.allFocusSessions().count { it.isOpen })
    }

    @Test fun projectResumeNeverDisplacesFocusInAnotherProjectAndGlobalCanResumeScopedPause() = runTest {
        fun stream(id: String, project: String) = WorkStream(id, id, projectId = project,
            state = WorkStreamState.READY, createdAt = start, updatedAt = start)
        val repo = InMemoryWorkStreamRepository(listOf(stream("p-human", "p"), stream("q-human", "q")))
        val clock = FakeClock(start)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        actions.focusStream("p-human")
        actions.pauseAllTimers("p")
        actions.focusStream("q-human")
        assertEquals(ActionResult.Success(0), actions.resumeAllTimers("p"))
        assertEquals(WorkStreamState.FOCUS, repo.getStream("q-human")!!.state)
        actions.pauseAllTimers("q")
        assertEquals(ActionResult.Success(1), actions.resumeAllTimers())
        assertEquals(1, repo.streams.value.count { it.state == WorkStreamState.FOCUS })
    }
}
