package com.virlin.app.agent

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateWorkStream
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.ui.agent.create.AgentCreateViewModel
import com.virlin.app.ui.agent.create.CreateKind
import com.virlin.app.ui.agent.create.Created
import com.virlin.app.ui.agent.create.TaskOwnerKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * Pass 9 — Agent CREATE: structured Project / WorkStream / Task creation through the same
 * VirlinActions as everything else. Real domain, in-memory repo behind the scheduling
 * decorator, fake clock, no UI. Form state is ephemeral; created items are durable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentCreateTest {

    private val t0: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeAttentionScheduler
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var vm: AgentCreateViewModel

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeClock(t0); scheduler = FakeAttentionScheduler()
        val streams = listOf(
            ws("s4", "Agent Development", READY, "p1", WorkStreamMode.EXTERNAL),
            ws("s1", "Psychology Unit 23", FOCUS, null, active = "p_q17"),
            ws("s8", "Projectless", READY, null),
            ws("s_done", "Old", DONE, "p1")
        )
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(
            seed = streams, seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0)),
            seedTasks = DemoHierarchySeed.tasks(streams, t0)
        ), scheduler)
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        vm = AgentCreateViewModel(actions, repo)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun form() = vm.form.value
    /** Projection is WhileSubscribed: attach a collector before reading it. */
    private fun kotlinx.coroutines.test.TestScope.projected(): com.virlin.app.ui.agent.create.AgentCreateState {
        backgroundScope.launch { vm.state.collect {} }; advanceUntilIdle(); return vm.state.value
    }
    private suspend fun s(id: String) = repo.getStream(id)!!
    private fun createdProject() = (form().created as Created.ProjectCreated).project
    private fun createdStream() = (form().created as Created.WorkStreamCreated).stream
    private fun createdTask() = (form().created as Created.TaskCreated).task
    private fun submit(title: String) { vm.setTitle(title); vm.create() }

    // ------------------------------------------------------------ §53 Project

    @Test fun project_created_and_persisted() = runTest(dispatcher) {
        vm.choose(CreateKind.PROJECT); submit("Thesis"); advanceUntilIdle()
        val p = createdProject()
        assertEquals("Thesis", p.title)
        assertNotNull(repo.getProject(p.id))
        assertTrue(repo.projects.value.any { it.id == p.id })
    }

    @Test fun project_blank_title_rejected_nothing_persisted() = runTest(dispatcher) {
        vm.choose(CreateKind.PROJECT); submit("   "); advanceUntilIdle()
        assertEquals("Give it a name first", form().error)
        assertNull(form().created)
        assertEquals(1, repo.projects.value.size)
    }

    @Test fun project_creation_does_not_touch_streams_or_focus() = runTest(dispatcher) {
        vm.choose(CreateKind.PROJECT); submit("Thesis"); advanceUntilIdle()
        assertEquals(FOCUS, s("s1").state)
        assertEquals(4, repo.streams.value.size)
        assertTrue(scheduler.current.isEmpty())
    }

    // ------------------------------------------------------------ §54 WorkStream

    @Test fun workstream_in_project_human_mode() = runTest(dispatcher) {
        vm.choose(CreateKind.WORKSTREAM); vm.setProject("p1"); vm.setMode(WorkStreamMode.HUMAN); submit("Write chapter"); advanceUntilIdle()
        val w = s(createdStream().id)
        assertEquals("p1", w.projectId); assertEquals(WorkStreamMode.HUMAN, w.mode); assertEquals(READY, w.state); assertNull(w.activeTaskId)
    }

    @Test fun workstream_without_project_is_valid() = runTest(dispatcher) {
        vm.choose(CreateKind.WORKSTREAM); vm.setProject(null); submit("Walk"); advanceUntilIdle()
        assertNull(s(createdStream().id).projectId)
        assertNull(form().error)
    }

    @Test fun workstream_external_mode_is_explicit_not_inferred() = runTest(dispatcher) {
        // A title that "sounds" external stays HUMAN unless the mode is chosen.
        vm.choose(CreateKind.WORKSTREAM); submit("Claude training run"); advanceUntilIdle()
        assertEquals(WorkStreamMode.HUMAN, s(createdStream().id).mode)
        vm.reset(); vm.choose(CreateKind.WORKSTREAM); vm.setMode(WorkStreamMode.EXTERNAL); submit("Walk the dog"); advanceUntilIdle()
        assertEquals(WorkStreamMode.EXTERNAL, s(createdStream().id).mode)
    }

    @Test fun workstream_defaults_ready_no_focus_no_alarm() = runTest(dispatcher) {
        vm.choose(CreateKind.WORKSTREAM); submit("New"); advanceUntilIdle()
        val id = createdStream().id
        assertEquals(READY, s(id).state)
        assertNull(repo.getOpenFocusSession(id))
        assertEquals(FOCUS, s("s1").state)                       // existing focus untouched
        assertTrue(scheduler.current.none { it.key == id })
    }

    @Test fun workstream_records_created_event() = runTest(dispatcher) {
        vm.choose(CreateKind.WORKSTREAM); vm.setMode(WorkStreamMode.EXTERNAL); submit("New"); advanceUntilIdle()
        val ev = repo.getEvents(createdStream().id)
        assertEquals(listOf(EventType.STREAM_CREATED), ev.map { it.type })
        assertEquals("EXTERNAL", ev.single().detail)
    }

    @Test fun workstream_unknown_project_rejected() = runTest(dispatcher) {
        val r = actions.createWorkStream(CreateWorkStream(title = "X", projectId = "nope"))
        assertTrue(r is ActionResult.Rejected && (r as ActionResult.Rejected).reason is DomainError.ProjectNotFound)
        vm.choose(CreateKind.WORKSTREAM); vm.setProject("nope"); submit("X"); advanceUntilIdle()
        assertEquals("Couldn't create that · that Project no longer exists", form().error)
        assertNull(form().created)
    }

    @Test fun workstream_blank_title_rejected() = runTest(dispatcher) {
        vm.choose(CreateKind.WORKSTREAM); submit(""); advanceUntilIdle()
        assertEquals("Give it a name first", form().error); assertEquals(4, repo.streams.value.size)
    }

    @Test fun terminal_streams_hidden_from_owner_picker() = runTest(dispatcher) {
        val st = projected()
        assertTrue(st.workStreams.none { it.id == "s_done" })
        assertTrue(st.workStreams.any { it.id == "s8" })
    }

    // ------------------------------------------------------------ §55 Task

    @Test fun task_root_in_workstream_project_derived() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setOwnerKind(TaskOwnerKind.WORKSTREAM); vm.setWorkStream("s4"); submit("Root"); advanceUntilIdle()
        val t = repo.getTask(createdTask().id)!!
        assertEquals("s4", t.workStreamId); assertEquals("p1", t.projectId); assertNull(t.parentTaskId); assertEquals(TaskStatus.TODO, t.status)
    }

    @Test fun task_in_projectless_workstream() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s8"); submit("Root"); advanceUntilIdle()
        val t = repo.getTask(createdTask().id)!!
        assertEquals("s8", t.workStreamId); assertNull(t.projectId)
    }

    @Test fun task_standalone_in_project() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setOwnerKind(TaskOwnerKind.PROJECT); vm.setProject("p1"); submit("Standalone"); advanceUntilIdle()
        val t = repo.getTask(createdTask().id)!!
        assertNull(t.workStreamId); assertEquals("p1", t.projectId)
    }

    @Test fun task_child_under_parent_inherits_ownership() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s1"); vm.setParent("p_q17"); submit("Child"); advanceUntilIdle()
        val t = repo.getTask(createdTask().id)!!
        assertEquals("p_q17", t.parentTaskId); assertEquals("s1", t.workStreamId); assertNull(t.projectId)
    }

    @Test fun task_deep_nesting_three_levels() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s8"); submit("L1"); advanceUntilIdle()
        val l1 = createdTask(); vm.addSubtaskTo(l1); submit("L2"); advanceUntilIdle()
        val l2 = createdTask(); vm.addSubtaskTo(l2); submit("L3"); advanceUntilIdle()
        val l3 = createdTask()
        assertEquals(listOf(l3.id, l2.id, l1.id), TaskHierarchy.ancestry(repo.tasks.value, l3.id)!!.map { it.id })
        assertEquals("s8", repo.getTask(l3.id)!!.workStreamId)
    }

    @Test fun task_without_owner_rejected_with_friendly_message() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); submit("Orphan"); advanceUntilIdle()
        assertEquals("Couldn't create that · Choose a WorkStream or Project", form().error)
        assertNull(form().created)
        assertTrue(repo.tasks.value.none { it.title == "Orphan" })
    }

    @Test fun task_project_owner_without_project_rejected() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setOwnerKind(TaskOwnerKind.PROJECT); submit("Orphan"); advanceUntilIdle()
        assertEquals("Couldn't create that · Choose a WorkStream or Project", form().error)
    }

    @Test fun task_blank_title_rejected() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s4"); submit(" "); advanceUntilIdle()
        assertEquals("Give it a name first", form().error)
    }

    @Test fun task_stale_parent_rejected() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s1"); vm.setParent("p_q17")
        actions.cancelTask("p_q17"); advanceUntilIdle()
        submit("Late"); advanceUntilIdle()
        assertEquals("Couldn't create that · the parent task is closed", form().error)
        assertNull(form().created)
    }

    @Test fun task_estimate_optional_and_applied() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s8"); vm.setEstimate("2x5"); submit("Est"); advanceUntilIdle()
        assertEquals(java.time.Duration.ofMinutes(25), repo.getTask(createdTask().id)!!.estimatedEffort)
        vm.reset(); vm.choose(CreateKind.TASK); vm.setWorkStream("s8"); submit("NoEst"); advanceUntilIdle()
        assertNull(repo.getTask(createdTask().id)!!.estimatedEffort)
    }

    @Test fun task_creation_never_sets_active_task_or_focus() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s4"); submit("Root"); advanceUntilIdle()
        assertNull(s("s4").activeTaskId); assertEquals(READY, s("s4").state)
        vm.reset(); vm.choose(CreateKind.TASK); vm.setWorkStream("s1"); submit("Another"); advanceUntilIdle()
        assertEquals("p_q17", s("s1").activeTaskId)                 // existing active task kept
    }

    @Test fun task_creation_feeds_progress_calculator() = runTest(dispatcher) {
        vm.choose(CreateKind.WORKSTREAM); submit("Fresh"); advanceUntilIdle()
        val ws = createdStream().id
        vm.addTaskTo(ws); submit("A"); advanceUntilIdle()
        val a = createdTask(); vm.addSubtaskTo(a); submit("A.1"); advanceUntilIdle()
        val a1 = createdTask(); vm.addSubtaskTo(a); submit("A.2"); advanceUntilIdle()
        actions.completeTask(a1.id)
        val r = ProgressCalculator.ofWorkStream(repo.tasks.value, ws) as ProgressResult.Structured
        assertEquals(1, r.completedLeaves); assertEquals(2, r.totalLeaves)
    }

    // ------------------------------------------------------------ §56 chaining + after-create

    @Test fun chain_project_workstream_task_subtask_each_persisted_immediately() = runTest(dispatcher) {
        vm.choose(CreateKind.PROJECT); submit("Thesis"); advanceUntilIdle()
        val p = createdProject(); assertNotNull(repo.getProject(p.id))
        vm.addWorkStreamTo(p.id); assertEquals(p.id, form().projectId); assertEquals(CreateKind.WORKSTREAM, form().kind)
        submit("Chapter 1"); advanceUntilIdle()
        val w = createdStream(); assertEquals(p.id, s(w.id).projectId)
        vm.addTaskTo(w.id); assertEquals(w.id, form().workStreamId); assertEquals(p.id, form().projectId)
        submit("Outline"); advanceUntilIdle()
        val t = createdTask(); assertEquals(w.id, repo.getTask(t.id)!!.workStreamId)
        vm.addSubtaskTo(t); assertEquals(t.id, form().parentTaskId)
        submit("Headings"); advanceUntilIdle()
        val st = createdTask(); assertEquals(t.id, repo.getTask(st.id)!!.parentTaskId); assertEquals(p.id, repo.getTask(st.id)!!.projectId)
    }

    @Test fun add_standalone_task_from_project() = runTest(dispatcher) {
        vm.choose(CreateKind.PROJECT); submit("Thesis"); advanceUntilIdle()
        vm.addStandaloneTaskTo(createdProject().id)
        assertEquals(TaskOwnerKind.PROJECT, form().ownerKind)
        submit("Buy paper"); advanceUntilIdle()
        assertNull(repo.getTask(createdTask().id)!!.workStreamId)
    }

    @Test fun set_current_is_explicit_and_only_for_workstream_tasks() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s4"); submit("Root"); advanceUntilIdle()
        val t = createdTask(); assertNull(s("s4").activeTaskId)
        vm.setCurrent(t); advanceUntilIdle()
        assertEquals(t.id, s("s4").activeTaskId)
        assertEquals(READY, s("s4").state)                        // set current ≠ focus
        vm.reset(); vm.choose(CreateKind.TASK); vm.setOwnerKind(TaskOwnerKind.PROJECT); vm.setProject("p1"); submit("Standalone"); advanceUntilIdle()
        val standalone = createdTask()
        vm.setCurrent(standalone); advanceUntilIdle()             // no-op by design
        assertTrue(repo.streams.value.none { it.activeTaskId == standalone.id })
    }

    @Test fun focus_now_uses_focus_stream_with_displacement() = runTest(dispatcher) {
        vm.choose(CreateKind.WORKSTREAM); submit("Fresh"); advanceUntilIdle()
        val id = createdStream().id
        vm.focusNow(id); advanceUntilIdle()
        assertEquals(FOCUS, s(id).state); assertEquals(READY, s("s1").state)
        assertNotNull(repo.getOpenFocusSession(id)); assertNull(repo.getOpenFocusSession("s1"))
    }

    @Test fun done_and_cancel_reset_form_only() = runTest(dispatcher) {
        vm.choose(CreateKind.WORKSTREAM); submit("Keep"); advanceUntilIdle()
        val id = createdStream().id
        vm.reset()
        assertNull(form().kind); assertNull(form().created); assertEquals("", form().title)
        assertNotNull(repo.getStream(id))                          // durable regardless of form reset
    }

    @Test fun choose_resets_stale_selection() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s4"); vm.setTitle("x")
        vm.choose(CreateKind.PROJECT)
        assertNull(form().workStreamId); assertEquals("", form().title)
    }

    @Test fun switching_owner_clears_parent() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s1"); vm.setParent("p_q17")
        vm.setOwnerKind(TaskOwnerKind.PROJECT)
        assertNull(form().parentTaskId)
    }

    @Test fun setting_workstream_derives_project_and_expands_to_active_task() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); vm.setWorkStream("s4")
        assertEquals("p1", form().projectId)
        vm.setWorkStream("s1")
        assertNull(form().projectId)
        assertTrue(projected().parentRows.any { it.id == "p_q17" })   // deep active task visible
    }

    // ------------------------------------------------------------ §57 boundaries

    @Test fun no_scheduling_side_effects_from_creation() = runTest(dispatcher) {
        vm.choose(CreateKind.PROJECT); submit("P"); advanceUntilIdle()
        vm.addWorkStreamTo(createdProject().id); submit("W"); advanceUntilIdle()
        vm.addTaskTo(createdStream().id); submit("T"); advanceUntilIdle()
        assertTrue(scheduler.current.isEmpty())
    }

    @Test fun success_clears_title_and_error_and_keeps_context() = runTest(dispatcher) {
        vm.choose(CreateKind.TASK); submit("Orphan"); advanceUntilIdle()
        assertNotNull(form().error)
        vm.setWorkStream("s8"); submit("Ok"); advanceUntilIdle()
        assertNull(form().error); assertEquals("", form().title); assertNotNull(form().created)
    }

    @Test fun no_mock_data_in_pickers_only_repository() = runTest(dispatcher) {
        val st = projected()
        assertEquals(repo.projects.value.map { it.id }, st.projects.map { it.id })
        assertEquals(repo.streams.value.filter { !it.state.isTerminal }.map { it.id }, st.workStreams.map { it.id })
    }
}
