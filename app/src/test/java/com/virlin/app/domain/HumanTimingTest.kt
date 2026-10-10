package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HumanTimingTest {
    private val start = Instant.parse("2026-10-08T00:00:00Z")
    private fun task(id: String, parent: String? = null, ws: String = "s", project: String = "p") =
        Task(id, id, workStreamId = ws, projectId = project, parentTaskId = parent,
            executionPreference = ExecutionPreference.HUMAN, createdAt = start, updatedAt = start)
    private fun stream(id: String = "s", project: String = "p") =
        WorkStream(id, id, projectId = project, state = WorkStreamState.READY, createdAt = start, updatedAt = start)

    @Test fun crossProjectSwitchResumeAndCompletionKeepExactlyOneHumanTimer() = runTest {
        val streams = listOf(stream(), stream("other", "q"))
        val repo = InMemoryWorkStreamRepository(streams, seedTasks = listOf(task("parent"), task("a", "parent"),
            task("b", ws = "other", project = "q")), taskExecutionEnabled = true)
        val clock = FakeClock(start)
        val ids = SequentialIdProvider()
        var actions = DefaultVirlinActions(repo, clock, ids)
        assertTrue(actions.startFocus("a") is ActionResult.Success)
        clock.current = start.plusSeconds(600)
        assertTrue(actions.startFocus("b") is ActionResult.Success)
        assertEquals(WorkStreamState.PAUSED, repo.getTask("a")!!.execution!!.state)
        assertEquals(start.plusSeconds(600), repo.allFocusSessions().first().endedAt)
        clock.current = start.plusSeconds(900)
        fun timing() = ExecutionTiming.workStream(stream(), repo.tasks.value, repoSessions, clock.current)
        repoSessions = repo.allFocusSessions()
        assertEquals(600L, timing().inclusiveTaskFocusSeconds["parent"])
        assertEquals(600L, ExecutionTiming.projectFocusSeconds("p", streams, repoSessions, clock.current))
        assertEquals(300L, ExecutionTiming.projectFocusSeconds("q", streams, repoSessions, clock.current))
        // Restored action engine uses persisted timestamps, never a new UI counter.
        actions = DefaultVirlinActions(repo, clock, ids)
        assertTrue(actions.startFocus("a") is ActionResult.Success)
        clock.current = start.plusSeconds(1200)
        assertTrue(actions.completeTask("a") is ActionResult.Success)
        clock.current = start.plusSeconds(1800)
        repoSessions = repo.allFocusSessions()
        assertEquals(0, repoSessions.count { it.isOpen })
        assertEquals(900L, timing().inclusiveTaskFocusSeconds["a"])
        assertEquals(900L, timing().inclusiveTaskFocusSeconds["parent"])
        assertEquals(900L, timing().totalFocusSeconds)
        assertEquals(300L, ExecutionTiming.projectFocusSeconds("q", streams, repoSessions, clock.current))
    }
    private var repoSessions: List<FocusSession> = emptyList()

    @Test fun repeatedFocusTapPreservesOriginalStartAndFractionalTime() = runTest {
        val repo = InMemoryWorkStreamRepository(listOf(stream()), seedTasks = listOf(task("a")), taskExecutionEnabled = true)
        val clock = FakeClock(start)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        actions.startFocus("a")
        clock.current = start.plusMillis(1500)
        assertTrue(actions.startFocus("a") is ActionResult.Success)
        clock.current = start.plusMillis(2100)
        val sessions = repo.allFocusSessions()
        assertEquals(1, sessions.size)
        assertEquals(start, sessions.single().startedAt)
        assertEquals(2L, ExecutionTiming.workStream(stream(), repo.tasks.value, sessions, clock.current).totalFocusSeconds)
    }

    @Test fun veryDeepHierarchyAndDuplicateSessionsCountOnce() {
        val tasks = (0..10000).map { task("n$it", if (it == 0) null else "n${it - 1}") }
        val session = FocusSession("one", "s", null, start, start.plusSeconds(600), "n10000")
        val timing = ExecutionTiming.workStream(stream(), tasks, listOf(session, session), start.plusSeconds(900))
        assertEquals(600L, timing.inclusiveTaskFocusSeconds["n0"])
        assertEquals(600L, timing.inclusiveTaskFocusSeconds["n10000"])
        assertEquals(600L, timing.totalFocusSeconds)
        assertEquals(600L, ExecutionTiming.projectFocusSeconds("p", listOf(stream()), listOf(session, session), start))
    }
}
