package com.virlin.app.agent

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.ScheduleKind
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.ui.agent.control.AgentControlPresentation
import com.virlin.app.ui.agent.control.AgentControlViewModel
import com.virlin.app.ui.agent.control.ControlAction
import com.virlin.app.ui.agent.control.ControlKind
import com.virlin.app.ui.screens.NowViewModel.Chooser
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
import java.time.Duration
import java.time.Instant

/**
 * Pass 8 — Agent CONTROL: projection of persisted state and structured controls that go
 * through the same VirlinActions as Now. Real domain, in-memory repo behind the scheduling
 * decorator (so alarms are proven to follow), fake clock, no UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentControlTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeAttentionScheduler
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var vm: AgentControlViewModel

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, executionPreference = mode.toPreference(), activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeClock(t0); scheduler = FakeAttentionScheduler()
        val streams = listOf(
            ws("s4", "Agent Development", READY, "p1", WorkStreamMode.EXTERNAL, "t_daypart"),
            ws("s1", "Psychology Unit 23", FOCUS, null, active = "p_q17"),
            ws("s9", "Database", READY, "p1", WorkStreamMode.EXTERNAL),
            ws("s10", "Walk", READY, null)
        )
        val deep = Task("t_daypart", "Daypart Parsing", projectId = "p1", workStreamId = "s4", parentTaskId = "t_nl", status = TaskStatus.IN_PROGRESS, createdAt = t0, updatedAt = t0)
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(
            seed = streams, seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0)),
            seedTasks = DemoHierarchySeed.tasks(streams, t0) + deep
        ), scheduler)
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        vm = AgentControlViewModel(actions, clock, repo)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun state() = AgentControlPresentation.build(repo.projects.value, repo.streams.value, repo.tasks.value, clock.now())
    private suspend fun s(id: String) = repo.getStream(id)!!
    private fun at(m: Long) = clock.now().plus(Duration.ofMinutes(m))

    // ------------------------------------------------------------------ current focus projection (1–5)

    @Test fun projectless_focus_and_deep_focus_projections() = runTest {
        val f = state().currentFocus!!
        assertEquals("s1", f.streamId); assertNull(f.projectTitle); assertEquals("Question 17", f.taskTitle)
        assertEquals(ControlKind.FOCUS_HUMAN, f.kind); assertEquals(listOf(ControlAction.LEAVE, ControlAction.COMPLETE, ControlAction.TASKS), f.actions)
        vm.act("s4", ControlAction.FOCUS); advanceUntilIdle()                      // project-backed external, deep task
        val g = state().currentFocus!!
        assertEquals("Virlin Android App", g.projectTitle); assertEquals("Agent Development", g.title); assertEquals("Daypart Parsing", g.taskTitle)
        assertEquals(ControlKind.FOCUS_EXTERNAL, g.kind); assertTrue(ControlAction.HAND_OFF in g.actions)
        assertEquals(1, repo.streams.value.count { it.state == FOCUS })           // displacement, not two Focus
        vm.act("s9", ControlAction.FOCUS); advanceUntilIdle()
        assertNull(state().currentFocus!!.taskTitle)                                // no task → no placeholder
        actions.leaveFocus("s9")
        assertNull(state().currentFocus)                                            // clean empty state
        assertEquals(setOf("s4", "s1", "s9", "s10"), state().ready.map { it.streamId }.toSet())
    }

    // ------------------------------------------------------------------ human (6–9)

    @Test fun agentLeave_noReminder_and_5m() = runTest {
        vm.act("s1", ControlAction.LEAVE); assertEquals(Chooser.Leave("s1"), vm.intents.chooser.value)
        vm.intents.leave("s1", null); advanceUntilIdle()
        assertEquals(READY, s("s1").state); assertEquals("p_q17", s("s1").activeTaskId); assertNull(scheduler.current["s1"])
        vm.act("s1", ControlAction.FOCUS); advanceUntilIdle()
        vm.intents.leave("s1", 5); advanceUntilIdle()
        assertEquals(SNOOZED, s("s1").state); assertEquals(SnoozeReason.HUMAN_RETURN, s("s1").snoozeReason)
        assertEquals(at(5), scheduler.current["s1"]!!.dueAt)                        // alarm through the decorator
        assertEquals("Left Psychology Unit 23 · back in 5m", vm.intents.feedback.value)
        assertEquals(ControlKind.RETURN_PENDING, state().ready.first { it.streamId == "s1" }.kind)
    }

    @Test fun agentComplete_taskOnly_thenWholeStreamNeedsConfirmation() = runTest {
        vm.act("s1", ControlAction.COMPLETE); advanceUntilIdle()
        assertEquals(TaskStatus.DONE, repo.getTask("p_q17")!!.status); assertNull(s("s1").activeTaskId); assertEquals(FOCUS, s("s1").state)
        assertNull(vm.intents.pendingWorkStreamCompletion.value)
        vm.act("s1", ControlAction.COMPLETE); advanceUntilIdle()
        assertEquals("s1", vm.intents.pendingWorkStreamCompletion.value); assertEquals(FOCUS, s("s1").state)
        vm.intents.dismissWorkStreamCompletion(); assertEquals(FOCUS, s("s1").state)
        vm.act("s1", ControlAction.COMPLETE); advanceUntilIdle(); vm.intents.confirmCompleteWorkStream(); advanceUntilIdle()
        assertEquals(DONE, s("s1").state)
    }

    // ------------------------------------------------------------------ external (10–16)

    @Test fun agentHandOff_5m_andNoCheck() = runTest {
        vm.act("s4", ControlAction.FOCUS); advanceUntilIdle()
        vm.act("s4", ControlAction.HAND_OFF); assertEquals(Chooser.HandOff("s4"), vm.intents.chooser.value)
        vm.intents.handOff("s4", 5); advanceUntilIdle()
        assertEquals(PROCESSING, s("s4").state); assertEquals(at(5), s("s4").checkAt); assertEquals(ScheduleKind.EXTERNAL_CHECK, scheduler.current["s4"]!!.kind)
        assertEquals("t_daypart", s("s4").activeTaskId)
        assertEquals("Agent Development", state().processing.single().title)
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("s4"); vm.act("s4", ControlAction.FOCUS_NOW); advanceUntilIdle()
        vm.intents.handOff("s4", null); advanceUntilIdle()
        assertEquals(PROCESSING, s("s4").state); assertNull(s("s4").checkAt); assertNull(scheduler.current["s4"])
    }

    @Test fun agentCheck_noFocus_stillRunning_resultReady_block() = runTest {
        vm.act("s4", ControlAction.FOCUS); advanceUntilIdle(); vm.intents.handOff("s4", 1); advanceUntilIdle()
        clock.advance(Duration.ofMinutes(2)); actions.checkDue("s4")
        val item = state().needsAttention.single(); assertEquals(ControlKind.CHECK_DUE, item.kind); assertEquals("Check due", item.detail)
        vm.act("s4", ControlAction.CHECK); assertEquals(Chooser.CheckOutcome("s4"), vm.intents.chooser.value)
        assertEquals(CHECK, s("s4").state); assertNull(repo.getOpenFocusSession("s4"))                 // 12: no Focus
        vm.intents.openStillRunning("s4"); vm.intents.stillRunning("s4", 5); advanceUntilIdle()         // 13
        assertEquals(PROCESSING, s("s4").state); assertEquals(at(5), s("s4").checkAt); assertNull(repo.getOpenFocusSession("s4"))
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("s4")
        vm.intents.openResultReady("s4"); vm.intents.resultReadyLater("s4", 5); advanceUntilIdle()     // 15
        assertEquals(SNOOZED, s("s4").state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, s("s4").snoozeReason)
        assertEquals(ControlKind.RESULT_READY_PENDING, state().ready.first { it.streamId == "s4" }.kind)
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("s4")
        assertEquals(ControlKind.RESULT_READY_DUE, state().needsAttention.single().kind)
        vm.act("s4", ControlAction.DEFER); assertEquals(Chooser.Defer("s4"), vm.intents.chooser.value)
        vm.intents.deferReturn("s4", 5); advanceUntilIdle()
        assertEquals(SNOOZED, s("s4").state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, s("s4").snoozeReason)
        clock.advance(Duration.ofMinutes(6)); actions.checkDue("s4")
        vm.act("s4", ControlAction.FOCUS_NOW); advanceUntilIdle()                                      // 14
        assertEquals(FOCUS, s("s4").state); assertNull(s("s4").processingStartedAt); assertEquals("t_daypart", repo.getOpenFocusSession("s4")!!.taskId)
        vm.intents.handOff("s4", 1); advanceUntilIdle(); clock.advance(Duration.ofMinutes(2)); actions.checkDue("s4")
        vm.intents.block("s4"); advanceUntilIdle()                                                      // 16
        assertEquals(BLOCKED, s("s4").state); assertEquals("t_daypart", s("s4").activeTaskId); assertNull(scheduler.current["s4"])
        assertEquals(ControlKind.BLOCKED, state().needsAttention.single().kind)
    }

    @Test fun agentResume_humanReturn_andDefer() = runTest {
        vm.intents.leave("s1", 5); advanceUntilIdle(); clock.advance(Duration.ofMinutes(6)); actions.checkDue("s1")
        val item = state().needsAttention.single(); assertEquals(ControlKind.RETURN_DUE, item.kind); assertEquals("Ready to continue", item.detail)
        assertEquals(listOf(ControlAction.RESUME, ControlAction.DEFER, ControlAction.TASKS), item.actions)
        vm.intents.deferReturn("s1", 10); advanceUntilIdle()
        assertEquals(SNOOZED, s("s1").state); assertEquals(SnoozeReason.HUMAN_RETURN, s("s1").snoozeReason); assertNull(s("s1").processingStartedAt)
        clock.advance(Duration.ofMinutes(11)); actions.checkDue("s1")
        vm.act("s1", ControlAction.RESUME); advanceUntilIdle()
        assertEquals(FOCUS, s("s1").state); assertEquals("p_q17", repo.getOpenFocusSession("s1")!!.taskId); assertNull(scheduler.current["s1"])
    }

    // ------------------------------------------------------------------ tasks (17–21)

    @Test fun taskPicker_deep_projectless_setCurrent_complete_cancel() = runTest {
        vm.openTasks("s4")
        val rows = vm.state.value.taskRows.ifEmpty { AgentControlPresentation.build(repo.projects.value, repo.streams.value, repo.tasks.value, clock.now(),
            AgentControlPresentation.Selection("s4", com.virlin.app.ui.hierarchy.HierarchyPresentation.ancestorIds(repo.tasks.value, "t_daypart"))).taskRows }
        assertEquals(listOf("t_create", "t_rem", "t_nl", "t_daypart"), rows.filter { it.id in setOf("t_create", "t_rem", "t_nl", "t_daypart") }.map { it.id })   // auto-expanded to the deep active task
        assertEquals(3, rows.first { it.id == "t_daypart" }.depth); assertTrue(rows.first { it.id == "t_daypart" }.isCurrent)
        vm.setCurrent("t_file"); advanceUntilIdle()                                                     // 17
        assertEquals("t_file", s("s4").activeTaskId)
        assertEquals(TaskStatus.TODO, repo.getTask("t_capture")!!.status)                               // parents untouched
        vm.completeTask("t_file"); advanceUntilIdle()                                                   // 18
        assertEquals(TaskStatus.DONE, repo.getTask("t_file")!!.status); assertNull(s("s4").activeTaskId)
        assertNotNull(vm.selection.value.nextCandidate); assertNull(s("s4").activeTaskId)   // offered only, never selected
        vm.requestCancel("t_voice"); assertEquals(TaskStatus.TODO, repo.getTask("t_voice")!!.status)  // 19: confirmation first
        vm.confirmCancel(); advanceUntilIdle()
        assertEquals(TaskStatus.CANCELLED, repo.getTask("t_voice")!!.status); assertFalse(repo.getTask("t_voice")!!.status.isCompleted)
        val p = ProgressCalculator.ofTask(repo.tasks.value, "t_capture") as com.virlin.app.domain.model.ProgressResult.Structured
        assertEquals(1, p.cancelledLeaves)
        // 21: projectless stream picker
        vm.openTasks("s1")
        val pr = AgentControlPresentation.build(repo.projects.value, repo.streams.value, repo.tasks.value, clock.now(),
            AgentControlPresentation.Selection("s1", com.virlin.app.ui.hierarchy.HierarchyPresentation.ancestorIds(repo.tasks.value, "p_q17"))).taskRows
        assertTrue(pr.any { it.id == "p_q17" && it.isCurrent && it.depth == 2 })
        vm.setCurrent("p_q11"); advanceUntilIdle()
        assertEquals(TaskStatus.DONE, repo.getTask("p_q11")!!.status)
        assertEquals("p_q17", s("s1").activeTaskId)                                                     // DONE task rejected; truth unchanged
        assertTrue(vm.intents.feedback.value!!.startsWith("Couldn't do that"))
    }

    // ------------------------------------------------------------------ invariants (22–25)

    @Test fun staleAction_rejected_noCorruption() = runTest {
        actions.leaveFocus("s1")                                                    // state changed behind the Agent's back
        vm.intents.leave("s1", 5); advanceUntilIdle()                               // stale LEAVE
        assertEquals(READY, s("s1").state); assertNull(s("s1").snoozeReason); assertTrue(vm.intents.feedback.value!!.startsWith("Couldn't"))
        vm.act("s1", ControlAction.FOCUS); vm.act("s4", ControlAction.FOCUS); advanceUntilIdle()
        assertEquals(1, repo.streams.value.count { it.state == FOCUS })
    }
}
