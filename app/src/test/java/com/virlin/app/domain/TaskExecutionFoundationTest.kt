package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TaskExecutionFoundationTest {
    @Test fun completeWorkstream_closesWholeHierarchyAndPreservesOtherWorkstreams() = runTest {
        val other = WorkStream("other", "Other", state = WorkStreamState.READY, createdAt = now, updatedAt = now)
        val repository = InMemoryWorkStreamRepository(
            listOf(WorkStream("s", "Stream", state = WorkStreamState.READY, createdAt = now, updatedAt = now), other),
            seedTasks = listOf(task("parent"), task("human", parent = "parent"),
                task("external", ExecutionPreference.EXTERNAL, "parent"),
                task("cancelled").copy(status = TaskStatus.CANCELLED),
                task("unrelated", ExecutionPreference.EXTERNAL).copy(workStreamId = "other")), taskExecutionEnabled = true)
        val clock = FakeClock(now)
        val actions = DefaultVirlinActions(repository, clock, SequentialIdProvider())
        actions.startFocus("human")
        actions.startExternalWork(StartExternalWork("s", workItemId = "external", checkInMinutes = 5))
        actions.startExternalWork(StartExternalWork("other", workItemId = "unrelated", checkInMinutes = 5))
        clock.current = now.plusSeconds(120)
        assertTrue(actions.completeStream("s") is ActionResult.Success)
        listOf("parent", "human", "external").forEach { assertEquals(TaskStatus.DONE, repository.getTask(it)!!.status) }
        assertEquals(TaskStatus.CANCELLED, repository.getTask("cancelled")!!.status)
        assertEquals(120L, repository.getTask("external")!!.execution!!.externalTrackedSeconds)
        assertNull(repository.getTask("external")!!.execution!!.checkAt)
        assertEquals(0, repository.allFocusSessions().count { it.isOpen })
        assertEquals(now.plusSeconds(120), repository.allFocusSessions().single().endedAt)
        assertEquals(WorkStreamState.PROCESSING, repository.getTask("unrelated")!!.execution!!.state)
        assertEquals(WorkStreamState.DONE, repository.getStream("s")!!.state)
        assertTrue(actions.completeStream("s") is ActionResult.Rejected)
    }
    private val now = Instant.parse("2026-10-04T08:00:00Z")
    private fun task(id: String, preference: ExecutionPreference = ExecutionPreference.HUMAN, parent: String? = null) =
        Task(id, id, workStreamId = "s", parentTaskId = parent, executionPreference = preference, createdAt = now, updatedAt = now)
    private fun repo(tasks: List<Task>) = InMemoryWorkStreamRepository(
        listOf(WorkStream("s", "Stream", state = WorkStreamState.READY, createdAt = now, updatedAt = now)), seedTasks = tasks, taskExecutionEnabled = true)

    @Test fun oneFocusAndTwoExternalRuns_coexistAndCompleteIndependently() = runTest {
        val repo = repo(listOf(task("human"), task("e1", ExecutionPreference.EXTERNAL), task("e2", ExecutionPreference.EXTERNAL)))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.startFocus("human") is ActionResult.Success)
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "e1", checkInMinutes = 5)) is ActionResult.Success)
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "e2", checkInMinutes = 7)) is ActionResult.Success)
        assertEquals(WorkStreamState.FOCUS, repo.getTask("human")!!.execution!!.state)
        assertEquals(2, repo.tasks.value.count { it.execution?.state == WorkStreamState.PROCESSING })
        assertTrue(actions.completeTask("e1") is ActionResult.Success)
        assertEquals(WorkStreamState.PROCESSING, repo.getTask("e2")!!.execution!!.state)
        assertEquals(WorkStreamState.FOCUS, repo.getTask("human")!!.execution!!.state)
    }

    @Test fun focusSwitch_pausesOnlyPreviousTask_andClosesItsSession() = runTest {
        val repo = repo(listOf(task("a"), task("b")))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.startFocus("a") is ActionResult.Success)
        assertTrue(actions.startFocus("b") is ActionResult.Success)
        assertEquals(WorkStreamState.PAUSED, repo.getTask("a")!!.execution!!.state)
        assertEquals(1, repo.allFocusSessions().count { it.isOpen })
        assertEquals("b", repo.allFocusSessions().last().taskId)
        assertEquals("s", repo.allFocusSessions().last().workStreamId)
    }

    @Test fun externalPauseCheckResultAndModeChange_remainTaskScoped() = runTest {
        val repo = repo(listOf(task("e", ExecutionPreference.EXTERNAL)))
        val clock = FakeClock(now)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "e", checkInMinutes = 5)) is ActionResult.Success)
        assertTrue(actions.pauseExternalTracking("e", now.plusSeconds(600)) is ActionResult.Success)
        assertTrue(repo.getTask("e")!!.execution!!.externalTrackingPaused)
        assertTrue(actions.resumeExternalTracking("e") is ActionResult.Success)
        assertTrue(actions.markExternalResultReady("e") is ActionResult.Success)
        assertNull(repo.getTask("e")!!.execution!!.processingStartedAt)
        assertTrue(actions.setTaskExecutionPreference("e", ExecutionPreference.HUMAN) is ActionResult.Success)
    }

    @Test fun mixedDescendants_changeInheritedModeWithoutTouchingExplicitExternal() = runTest {
        val repo = repo(listOf(task("parent"), task("human", ExecutionPreference.INHERIT, "parent"),
            task("external", ExecutionPreference.EXTERNAL, "parent")))
        val clock = FakeClock(now)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(actions.startFocus("human") is ActionResult.Success)
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "external")) is ActionResult.Success)
        assertEquals(com.virlin.app.ui.hierarchy.WorkstreamState.MULTI_RUN,
            com.virlin.app.ui.hierarchy.activityState(repo.tasks.value))
        clock.current = now.plusSeconds(120)
        assertTrue(actions.setTaskExecutionPreference("parent", ExecutionPreference.EXTERNAL) is ActionResult.Rejected)
        assertEquals(WorkStreamState.FOCUS, repo.getTask("human")!!.execution!!.state)
        assertEquals(WorkStreamState.PROCESSING, repo.getTask("external")!!.execution!!.state)
        assertEquals(1, repo.allFocusSessions().count { it.isOpen })
        assertTrue(actions.setTaskExecutionPreference("external", ExecutionPreference.HUMAN) is ActionResult.Success)
        assertEquals(120L, repo.getTask("external")!!.execution!!.externalTrackedSeconds)
        assertEquals(WorkStreamState.READY, repo.getTask("external")!!.execution!!.state)
    }

    @Test fun containersCannotExecuteOrChangeMode_butLeavesAndManagementWork() = runTest {
        val repo = repo(listOf(task("parent"), task("leaf", parent = "parent")))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        assertTrue(actions.startFocus("parent") is ActionResult.Rejected)
        assertTrue(actions.focusStream("s") is ActionResult.Rejected)
        assertTrue(actions.startExternalWork(StartExternalWork("s")) is ActionResult.Rejected)
        assertTrue(actions.setWorkStreamExecutionPreference("s", ExecutionPreference.EXTERNAL) is ActionResult.Rejected)
        assertTrue(actions.setTaskExecutionPreference("parent", ExecutionPreference.EXTERNAL) is ActionResult.Rejected)
        assertTrue(actions.updateTask("parent", TaskUpdate(title = Field.Set("Renamed parent"))) is ActionResult.Success)
        assertTrue(actions.startFocus("leaf") is ActionResult.Success)
    }

    @Test fun pausedHumanChangedToExternal_becomesReadyNotResumeFocus() = runTest {
        val repo = repo(listOf(task("a"), task("b")))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        actions.startFocus("a"); actions.startFocus("b")
        assertTrue(actions.setTaskExecutionPreference("a", ExecutionPreference.EXTERNAL) is ActionResult.Success)
        assertEquals(WorkStreamState.READY, repo.getTask("a")!!.execution!!.state)
        assertTrue(actions.focusStream("a") is ActionResult.Rejected)
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "a")) is ActionResult.Success)
        assertEquals(WorkStreamState.FOCUS, repo.getTask("b")!!.execution!!.state)
    }

    @Test fun addingChildToActiveLeaf_requiresEndingActivityFirst() = runTest {
        val repo = repo(listOf(task("leaf")))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        actions.startFocus("leaf")
        assertTrue(actions.createTask(CreateTask(title = "child", parentTaskId = "leaf")) is ActionResult.Rejected)
        actions.markReady("leaf")
        assertTrue(actions.createTask(CreateTask(title = "child", parentTaskId = "leaf")) is ActionResult.Success)
        assertTrue(actions.startFocus("leaf") is ActionResult.Rejected)
    }

    @Test fun startupReconciliation_stopsLegacyParentAndPreservesTrackedTime() = runTest {
        val parent = task("parent").copy(execution = TaskExecution(state = WorkStreamState.PROCESSING,
            processingStartedAt = now.minusSeconds(90), externalTrackedSeconds = 30))
        val leaf = task("leaf", parent = "parent").copy(execution = TaskExecution(state = WorkStreamState.FOCUS))
        val repo = repo(listOf(parent, leaf))
        reconcileContainerExecution(repo, now)
        assertEquals(120L, repo.getTask("parent")!!.execution!!.externalTrackedSeconds)
        assertEquals(WorkStreamState.READY, repo.getTask("parent")!!.execution!!.state)
        assertEquals(WorkStreamState.FOCUS, repo.getTask("leaf")!!.execution!!.state)
        reconcileContainerExecution(repo, now.plusSeconds(30))
        assertEquals(120L, repo.getTask("parent")!!.execution!!.externalTrackedSeconds)
    }

    @Test fun startupReconciliation_repairsExternalLeafWithOldHumanPause() = runTest {
        val repo = repo(listOf(task("external", ExecutionPreference.EXTERNAL).copy(execution = TaskExecution(WorkStreamState.PAUSED))))
        reconcileContainerExecution(repo, now)
        assertEquals(WorkStreamState.READY, repo.getTask("external")!!.execution!!.state)
    }
}
