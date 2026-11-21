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
    @Test fun organizePauseResumesOnlyItsOwnProjectTimers() = runTest {
        fun stream(id: String, project: String, state: WorkStreamState) = WorkStream(
            id, id, projectId = project, state = state,
            processingStartedAt = if (state == WorkStreamState.PROCESSING) start else null,
            createdAt = start, updatedAt = start)
        val repo = InMemoryWorkStreamRepository(listOf(
            stream("active", "p", WorkStreamState.PROCESSING),
            stream("manual", "p", WorkStreamState.PAUSED),
            stream("other", "q", WorkStreamState.PROCESSING)))
        val clock = FakeClock(start.plusSeconds(60))
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertEquals(ActionResult.Success(1), actions.pauseTimersForOrganize("p"))
        assertEquals(WorkStreamState.PAUSED, repo.getStream("active")!!.state)
        assertEquals(WorkStreamState.PROCESSING, repo.getStream("other")!!.state)
        assertEquals(ActionResult.Success(0), actions.resumeAllTimers("p"))
        clock.current = start.plusSeconds(3600)
        assertEquals(ActionResult.Success(1), actions.resumeTimersForOrganize("p"))
        assertEquals(WorkStreamState.PROCESSING, repo.getStream("active")!!.state)
        assertEquals(WorkStreamState.PAUSED, repo.getStream("manual")!!.state)
        assertEquals(WorkStreamState.PROCESSING, repo.getStream("other")!!.state)
        assertEquals(60L, ExternalTiming.seconds(repo.getStream("active")!!, clock.current))
        assertEquals(ActionResult.Success(0), actions.resumeTimersForOrganize("p"))
    }

    @Test fun organizeDoesNotPartiallyResumeWhenAnotherProjectOwnsHumanFocus() = runTest {
        fun stream(id: String, project: String) = WorkStream(id, id, projectId = project,
            state = WorkStreamState.READY, createdAt = start, updatedAt = start)
        val repo = InMemoryWorkStreamRepository(listOf(stream("p-human", "p"), stream("q-human", "q")))
        val actions = DefaultVirlinActions(repo, FakeClock(start), SequentialIdProvider())
        actions.focusStream("p-human")
        assertEquals(ActionResult.Success(1), actions.pauseTimersForOrganize("p"))
        actions.focusStream("q-human")
        assertEquals(ActionResult.Rejected(DomainError.AnotherTaskIsExecuting),
            actions.resumeTimersForOrganize("p"))
        assertEquals(WorkStreamState.PAUSED, repo.getStream("p-human")!!.state)
        assertEquals(WorkStreamState.FOCUS, repo.getStream("q-human")!!.state)
    }

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

    private fun organizeFixture(): Pair<InMemoryWorkStreamRepository, FakeClock> {
        fun task(id: String, mode: ExecutionPreference, order: Int) = Task(id, id, workStreamId = "s", projectId = "p",
            executionPreference = mode, order = order, createdAt = start, updatedAt = start)
        val repo = InMemoryWorkStreamRepository(
            listOf(WorkStream("s", "s", projectId = "p", state = WorkStreamState.READY, createdAt = start, updatedAt = start)),
            seedTasks = listOf(task("human", ExecutionPreference.HUMAN, 0), task("ext", ExecutionPreference.EXTERNAL, 1),
                task("box", ExecutionPreference.HUMAN, 2)),
            taskExecutionEnabled = true)
        return repo to FakeClock(start)
    }

    private suspend fun DefaultVirlinActions.runBothAndOrganize(clock: FakeClock) {
        startFocus("human")
        startExternalWork(StartExternalWork("s", workItemId = "ext"))
        clock.current = start.plusSeconds(60)
        assertEquals(ActionResult.Success(2), pauseTimersForOrganize("p"))
    }

    @Test fun organizeEditsNeverStrandPausedTimers() = runTest {
        val (repo, clock) = organizeFixture()
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        actions.runBothAndOrganize(clock)
        clock.current = start.plusSeconds(120)
        assertTrue(actions.updateTask("human", TaskUpdate(title = Field.Set("Human renamed"))) is ActionResult.Success)
        assertTrue(actions.updateTask("ext", TaskUpdate(title = Field.Set("Ext renamed"))) is ActionResult.Success)
        val copy = (actions.duplicateTask("human") as ActionResult.Success).value
        assertTrue(actions.moveTasks(listOf("ext"), newParentId = "box", afterId = null, withinStreamId = "s") is ActionResult.Success)
        clock.current = start.plusSeconds(3600)
        assertEquals(ActionResult.Success(2), actions.resumeTimersForOrganize("p"))
        assertEquals(WorkStreamState.FOCUS, repo.getTask("human")!!.execution!!.state)
        assertEquals(WorkStreamState.PROCESSING, repo.getTask("ext")!!.execution!!.state)
        assertEquals(60L, ExternalTiming.seconds(repo.getTask("ext")!!.execution, clock.current))
        assertEquals(WorkStreamState.READY, repo.getTask(copy.id)!!.execution!!.state)
        assertEquals(1, repo.allFocusSessions().count { it.isOpen })
    }

    @Test fun organizeLetsAPausedLeafTakeChildrenAndEndsOnlyItsTimer() = runTest {
        val (repo, clock) = organizeFixture()
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        actions.runBothAndOrganize(clock)
        assertTrue(actions.addSubtask("human", "child") is ActionResult.Success)
        assertTrue(actions.moveTasks(listOf("box"), newParentId = "ext", afterId = null, withinStreamId = "s") is ActionResult.Success)
        assertEquals(WorkStreamState.READY, repo.getTask("human")!!.execution!!.state)
        assertEquals(WorkStreamState.READY, repo.getTask("ext")!!.execution!!.state)
        assertEquals(60L, repo.getTask("ext")!!.execution!!.externalTrackedSeconds)
        assertEquals(0, repo.allFocusSessions().count { it.isOpen })
        assertEquals(ActionResult.Success(0), actions.resumeTimersForOrganize("p"))
        assertTrue(repo.tasks.value.none { it.execution?.state == WorkStreamState.FOCUS })
    }

    @Test fun outsideOrganizeActiveOrManuallyPausedItemsStillRefuseChildren() = runTest {
        val (repo, clock) = organizeFixture()
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        actions.startFocus("human")
        assertTrue((actions.addSubtask("human", "child") as ActionResult.Rejected).reason is DomainError.PlacementRejected)
        assertEquals(ActionResult.Success(1), actions.pauseAllTimers("p"))
        assertTrue((actions.addSubtask("human", "child") as ActionResult.Rejected).reason is DomainError.PlacementRejected)
        assertEquals(WorkStreamState.PAUSED, repo.getTask("human")!!.execution!!.state)
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
