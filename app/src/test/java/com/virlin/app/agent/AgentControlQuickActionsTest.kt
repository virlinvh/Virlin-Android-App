package com.virlin.app.agent

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.FOCUS
import com.virlin.app.domain.model.WorkStreamState.PROCESSING
import com.virlin.app.domain.model.WorkStreamState.READY
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.ui.agent.control.AgentControlPresentation
import com.virlin.app.ui.agent.control.AgentControlViewModel
import com.virlin.app.ui.agent.control.ControlKind
import com.virlin.app.ui.agent.control.ControlQuickRegistry
import com.virlin.app.ui.agent.control.QuickAction
import com.virlin.app.ui.screens.NowViewModel.Chooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Control visual Quick Actions: ACTION + TARGET selection model, eligibility from
 * [AgentControlPresentation.actionsFor], execution only through existing intents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentControlQuickActionsTest {

    private val t0: Instant = Instant.parse("2026-09-14T10:00:00Z")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: FakeClock
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var vm: AgentControlViewModel

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, executionPreference = mode.toPreference(), activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeClock(t0)
        val seed = listOf(
            ws("s1", "Psychology Unit 23", FOCUS, null, active = "p_q17"),
            ws("s_ext", "Claude · Virlin", READY, "p1", WorkStreamMode.EXTERNAL, "t1"),
            ws("s_ready", "Walk", READY, null),
            ws("s_proc", "Antigravity", PROCESSING, "p1", WorkStreamMode.EXTERNAL)
        )
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(
            seed = seed,
            seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0)),
            seedTasks = DemoHierarchySeed.tasks(seed, t0) + Task("t1", "Ship Control", projectId = "p1", workStreamId = "s_ext", status = TaskStatus.TODO, createdAt = t0, updatedAt = t0)
        ), FakeAttentionScheduler())
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        vm = AgentControlViewModel(actions, clock, repo)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun state() = AgentControlPresentation.build(
        repo.projects.value, repo.streams.value, repo.tasks.value, clock.now(), vm.selection.value
    )
    private var lastCommand: com.virlin.app.domain.command.VirlinCommand? = null
    private val onCommand: (com.virlin.app.domain.command.VirlinCommand) -> Unit = { lastCommand = it }

    @Test fun rail_exposes_all_structured_control_actions_plus_block() {
        assertEquals(
            listOf(QuickAction.FOCUS, QuickAction.RESUME, QuickAction.LEAVE, QuickAction.HAND_OFF, QuickAction.COMPLETE,
                QuickAction.CHECK, QuickAction.FOCUS_NOW, QuickAction.DEFER, QuickAction.BLOCK, QuickAction.TASKS),
            QuickAction.rail
        )
        assertTrue(QuickAction.rail.size > 4)
    }

    @Test fun each_quick_action_has_unique_color_family_and_selected_border() {
        val looks = QuickAction.rail.map { ControlQuickRegistry.look(it) }
        assertEquals(looks.size, looks.map { it.colorFamily }.toSet().size)
        assertEquals(looks.size, looks.map { it.selectedBorder }.toSet().size)
        // Focus (deep emerald) vs Complete (jade) must not share icon color
        assertTrue(ControlQuickRegistry.look(QuickAction.FOCUS).iconColor != ControlQuickRegistry.look(QuickAction.COMPLETE).iconColor)
        assertTrue(ControlQuickRegistry.look(QuickAction.RESUME).iconColor != ControlQuickRegistry.look(QuickAction.HAND_OFF).iconColor)
    }

    @Test fun eligibility_matches_control_kind_table() {
        assertTrue(ControlQuickRegistry.eligibleFor(QuickAction.LEAVE, ControlKind.FOCUS_HUMAN))
        assertFalse(ControlQuickRegistry.eligibleFor(QuickAction.HAND_OFF, ControlKind.FOCUS_HUMAN))
        assertTrue(ControlQuickRegistry.eligibleFor(QuickAction.HAND_OFF, ControlKind.FOCUS_EXTERNAL))
        assertTrue(ControlQuickRegistry.eligibleFor(QuickAction.FOCUS, ControlKind.READY))
        assertFalse(ControlQuickRegistry.eligibleFor(QuickAction.LEAVE, ControlKind.READY))
        assertTrue(ControlQuickRegistry.eligibleFor(QuickAction.CHECK, ControlKind.PROCESSING))
        assertTrue(ControlQuickRegistry.eligibleFor(QuickAction.BLOCK, ControlKind.FOCUS_HUMAN))
        assertFalse(ControlQuickRegistry.eligibleFor(QuickAction.BLOCK, ControlKind.RETURN_PENDING))
    }

    @Test fun selecting_action_alone_does_not_mutate_or_open_chooser() = runTest {
        vm.selectQuick(QuickAction.LEAVE, onCommand)
        advanceUntilIdle()
        assertEquals(QuickAction.LEAVE, state().selectedQuickAction)
        assertNull(vm.intents.chooser.value)
        assertEquals(FOCUS, repo.getStream("s1")!!.state)
        assertNull(lastCommand)
    }

    @Test fun selecting_target_alone_does_not_mutate() = runTest {
        vm.selectTarget("s1")
        advanceUntilIdle()
        assertEquals("s1", state().selectedTargetId)
        assertNull(vm.intents.chooser.value)
        assertEquals(FOCUS, repo.getStream("s1")!!.state)
    }

    @Test fun leave_then_target_opens_leave_chooser_not_processing() = runTest {
        vm.selectQuick(QuickAction.LEAVE, onCommand)
        vm.selectTarget("s1")
        advanceUntilIdle()
        assertEquals(Chooser.Leave("s1"), vm.intents.chooser.value)
        assertEquals(FOCUS, repo.getStream("s1")!!.state) // chooser only; not yet left
        vm.intents.leave("s1", null); advanceUntilIdle()
        assertEquals(READY, repo.getStream("s1")!!.state)
        assertNull(repo.getStream("s1")!!.processingStartedAt)
    }

    @Test fun hand_off_disabled_for_human_focus_enabled_for_external() = runTest {
        vm.selectTarget("s1")
        assertFalse(vm.isQuickEnabled(QuickAction.HAND_OFF))
        // Move focus to external stream
        actions.focusStream("s_ext"); advanceUntilIdle()
        vm.selectTarget("s_ext")
        assertTrue(vm.isQuickEnabled(QuickAction.HAND_OFF))
        vm.selectQuick(QuickAction.HAND_OFF, onCommand)
        advanceUntilIdle()
        assertEquals(Chooser.HandOff("s_ext"), vm.intents.chooser.value)
    }

    @Test fun complete_on_focus_with_active_task_completes_task_not_stream() = runTest {
        vm.selectTarget("s1")
        vm.selectQuick(QuickAction.COMPLETE, onCommand)
        advanceUntilIdle()
        assertEquals(TaskStatus.DONE, repo.getTask("p_q17")!!.status)
        assertEquals(FOCUS, repo.getStream("s1")!!.state)
        assertNull(vm.intents.pendingWorkStreamCompletion.value)
    }

    @Test fun complete_without_active_task_requires_confirmation() = runTest {
        actions.focusStream("s_ready"); advanceUntilIdle()
        vm.selectTarget("s_ready")
        vm.selectQuick(QuickAction.COMPLETE, onCommand)
        advanceUntilIdle()
        assertEquals("s_ready", vm.intents.pendingWorkStreamCompletion.value)
        assertEquals(FOCUS, repo.getStream("s_ready")!!.state)
    }

    @Test fun invalid_pair_does_not_execute() = runTest {
        vm.selectTarget("s_ready") // READY
        vm.selectQuick(QuickAction.LEAVE, onCommand) // invalid for READY
        advanceUntilIdle()
        assertEquals(QuickAction.LEAVE, state().selectedQuickAction)
        assertNull(vm.intents.chooser.value)
        assertEquals(READY, repo.getStream("s_ready")!!.state)
    }

    @Test fun target_then_action_also_executes() = runTest {
        vm.selectTarget("s1")
        vm.selectQuick(QuickAction.LEAVE, onCommand)
        advanceUntilIdle()
        assertEquals(Chooser.Leave("s1"), vm.intents.chooser.value)
    }

    @Test fun retap_focus_without_target_requests_clarification_command() = runTest {
        vm.selectQuick(QuickAction.FOCUS, onCommand)
        assertNull(lastCommand)
        vm.selectQuick(QuickAction.FOCUS, onCommand)
        assertTrue(lastCommand is com.virlin.app.domain.command.VirlinCommand.Control.FocusStream)
    }

    @Test fun tasks_opens_picker_for_selected_target() = runTest {
        vm.selectTarget("s1")
        vm.selectQuick(QuickAction.TASKS, onCommand)
        advanceUntilIdle()
        assertEquals("s1", state().selectedStreamId)
    }

    @Test fun project_has_no_quick_action_surface() {
        // Projects appear only as labels on WorkStream rows — no Project QuickAction exists.
        assertTrue(QuickAction.rail.none { it.name.contains("PROJECT", ignoreCase = true) })
        val built = AgentControlPresentation.build(repo.projects.value, repo.streams.value, repo.tasks.value, clock.now())
        assertTrue(built.suggested.all { it.streamId.isNotBlank() })
    }
}
