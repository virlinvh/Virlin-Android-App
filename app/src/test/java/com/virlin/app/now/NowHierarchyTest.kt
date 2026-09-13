package com.virlin.app.now

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.screens.NowPresentation
import com.virlin.app.ui.screens.NowViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Pass 3: the Now Current Focus card's hierarchy projection and COMPLETE / LEAVE semantics.
 * Everything runs against the real domain (`DefaultVirlinActions` over an in-memory
 * repository, fake clock). No UI, no MockData.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowHierarchyTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var vm: NowViewModel

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, active: String? = null, next: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, activeTaskId = active, nextHumanAction = next, createdAt = t0, updatedAt = t0)

    private fun build(focused: String, extraTasks: List<Task> = emptyList(), activeOverride: Map<String, String?> = emptyMap()) {
        val streams = listOf(
            ws("s4", "Agent Development", WorkStreamState.PROCESSING, "p1", "t_nl", next = "Support \"tomorrow morning\""),
            ws("s1", "Psychology Unit 23", WorkStreamState.READY, null, "p_q17", next = "Complete answer"),
            ws("s9", "Database", WorkStreamState.READY, "p1")
        ).map { s -> if (s.id == focused) s.copy(state = WorkStreamState.FOCUS) else s }
         .map { s -> if (s.id in activeOverride) s.copy(activeTaskId = activeOverride[s.id]) else s }
        repo = InMemoryWorkStreamRepository(
            seed = streams,
            seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0)),
            seedTasks = DemoHierarchySeed.tasks(streams, t0) + extraTasks
        )
        vm = NowViewModel(DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider()), FakeClock(t0), repo)
    }

    private fun projection() = NowPresentation.currentFocus(repo.projects.value, repo.streams.value, repo.tasks.value)
    private fun stream(id: String) = repo.streams.value.first { it.id == id }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ------------------------------------------------------------------ presentation

    @Test fun projectBacked_activeTask_showsProject_workStream_deepestTaskOnly() {
        build("s4")
        val p = projection()!!
        assertEquals("Virlin Android App", p.projectTitle)
        assertEquals("Agent Development", p.workStreamTitle)
        assertEquals("Natural Language", p.activeTaskTitle)          // exact active task
        assertEquals("t_nl", p.activeTaskId)
        assertNotEquals("Create Mode", p.activeTaskTitle); assertNotEquals("Reminder Creation", p.activeTaskTitle)
        assertEquals("Support \"tomorrow morning\"", p.nextHumanAction)
        assertEquals(WorkStreamState.FOCUS, p.state)
    }

    @Test fun projectless_activeTask_omitsProject() {
        build("s1")
        val p = projection()!!
        assertNull(p.projectTitle)
        assertEquals("Psychology Unit 23", p.workStreamTitle)
        assertEquals("Question 17", p.activeTaskTitle)
    }

    @Test fun noActiveTask_showsWorkStreamOnly_noPlaceholder() {
        build("s9")
        val p = projection()!!
        assertEquals("Virlin Android App", p.projectTitle)
        assertEquals("Database", p.workStreamTitle)
        assertNull(p.activeTaskId); assertNull(p.activeTaskTitle); assertNull(p.nextHumanAction)
    }

    @Test fun deepActiveTask_showsLeafNotAncestors() {
        val deep = Task("t_daypart", "Daypart Parsing", projectId = "p1", workStreamId = "s4", parentTaskId = "t_nl",
            status = TaskStatus.IN_PROGRESS, order = 0, createdAt = t0, updatedAt = t0)
        build("s4", extraTasks = listOf(deep), activeOverride = mapOf("s4" to "t_daypart"))
        assertEquals("Daypart Parsing", projection()!!.activeTaskTitle)
    }

    @Test fun staleActiveTaskId_handledSafely() {
        build("s4", activeOverride = mapOf("s4" to "t_missing"))
        val p = projection()!!
        assertNull(p.activeTaskId); assertNull(p.activeTaskTitle); assertEquals("Agent Development", p.workStreamTitle)
    }

    @Test fun noFocus_projectionIsNull() {
        build("none")
        assertNull(projection())
    }

    @Test fun nextHumanAction_neverFromCandidate_blankOmitted() {
        build("s9")
        assertNull(projection()!!.nextHumanAction)   // s9 has no nextHumanAction; candidate is not substituted
    }

    // ------------------------------------------------------------------ COMPLETE with active task

    @Test fun completeWithActiveTask_completesTaskOnly_clearsActive_noAutoSelect() = runTest {
        build("s4")
        vm.complete("s4"); advanceUntilIdle()
        assertEquals(TaskStatus.DONE, repo.getTask("t_nl")!!.status)
        val s = stream("s4")
        assertNull(s.activeTaskId)                                    // cleared by the domain
        assertEquals(WorkStreamState.FOCUS, s.state)                  // stream untouched, not DONE
        assertEquals(TaskStatus.TODO, repo.getTask("t_rem")!!.status) // parent not auto-completed
        assertNull(vm.pendingWorkStreamCompletion.value)              // no confirmation asked
        assertNull(projection()!!.activeTaskTitle)                    // card refreshes from domain
    }

    // ------------------------------------------------------------------ COMPLETE without active task

    @Test fun completeWithoutActiveTask_requestsConfirmation_changesNothing() = runTest {
        build("s9")
        vm.complete("s9"); advanceUntilIdle()
        assertEquals("s9", vm.pendingWorkStreamCompletion.value)
        assertEquals(WorkStreamState.FOCUS, stream("s9").state)
    }

    @Test fun cancelConfirmation_changesNothing() = runTest {
        build("s9")
        vm.complete("s9"); advanceUntilIdle()
        vm.dismissWorkStreamCompletion(); advanceUntilIdle()
        assertNull(vm.pendingWorkStreamCompletion.value)
        assertEquals(WorkStreamState.FOCUS, stream("s9").state)
    }

    @Test fun confirmCompletion_usesCompleteStream() = runTest {
        build("s9")
        vm.complete("s9"); advanceUntilIdle()
        vm.confirmCompleteWorkStream(); advanceUntilIdle()
        assertNull(vm.pendingWorkStreamCompletion.value)
        assertEquals(WorkStreamState.DONE, stream("s9").state)
        assertNull(projection())                                      // nothing in focus any more
    }

    // ------------------------------------------------------------------ LEAVE

    @Test fun leave_snapshotCarriesActiveTask_hierarchyUnchanged() = runTest {
        build("s1")
        val tasksBefore = repo.tasks.value
        vm.leave("s1", 5); advanceUntilIdle()                         // LEAVE with a return time (Pass 4)
        val snap = repo.getLatestSnapshot("s1")!!
        assertEquals("p_q17", snap.taskId)
        assertEquals("p_q17", stream("s1").activeTaskId)              // still the active task for restoration
        assertEquals(tasksBefore, repo.tasks.value)                   // no task mutated
        assertEquals(WorkStreamState.SNOOZED, stream("s1").state)     // human return, never PROCESSING
    }
}
