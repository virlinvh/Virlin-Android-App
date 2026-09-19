package com.virlin.app.command

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.command.CommandContext
import com.virlin.app.domain.command.CommandEngine
import com.virlin.app.domain.command.Clarification
import com.virlin.app.domain.command.QueryResult
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.ScheduleKind
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.ui.agent.command.AgentCommandViewModel
import com.virlin.app.domain.command.text.TextCommandInterpreter
import com.virlin.app.domain.command.time.TimeExpressionParser
import com.virlin.app.ui.agent.command.CommandPanelState
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
 * Pass 12 §51 — composer text end to end: interpreter → engine → panel state → execution,
 * over the real domain with a fake scheduler and clock. No Compose, no Room.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextCommandIntegrationTest {

    private val t0: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private val dispatcher = StandardTestDispatcher()
    private val zone = java.time.ZoneId.of("Asia/Kolkata")
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeAttentionScheduler
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var vm: AgentCommandViewModel

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, executionPreference = mode.toPreference(), activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeClock(t0); scheduler = FakeAttentionScheduler()
        val streams = listOf(
            ws("s4", "Agent Development", READY, "p1", WorkStreamMode.EXTERNAL),
            ws("s1", "Psychology", FOCUS, null, active = "p_q17"),
            ws("s9", "Testing", READY, "p1"), ws("s10", "Testing", READY, "p2")
        )
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(seed = streams,
            seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0), Project("p2", "MBA Project", createdAt = t0, updatedAt = t0)),
            seedTasks = DemoHierarchySeed.tasks(streams, t0)), scheduler)
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        vm = AgentCommandViewModel(CommandEngine(actions, repo, clock, zone), TextCommandInterpreter(TimeExpressionParser(clock, zone))::interpret)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private suspend fun s(id: String) = repo.getStream(id)!!
    /** Submit and let the (now asynchronous) interpretation finish; returns true when the text was recognised. */
    private fun kotlinx.coroutines.test.TestScope.submit(text: String): Boolean { var ok = false; vm.submit(text) { ok = true }; advanceUntilIdle(); return ok }
    private fun state() = vm.state.value
    private fun feedback() = (state() as? CommandPanelState.Feedback) ?: error("no feedback: ${state()}")
    private fun clarify() = (state() as? CommandPanelState.Clarify)?.clarification ?: error("no clarification: ${state()}")

    @Test fun text_focus_resolves_and_executes() = runTest(dispatcher) {
        assertTrue(submit("focus agent development"))
        assertEquals("Focused Agent Development · Psychology set aside", feedback().text)
        assertEquals(FOCUS, s("s4").state); assertEquals(READY, s("s1").state)
    }

    @Test fun duplicate_target_clarifies_then_choice_executes_by_stable_id() = runTest(dispatcher) {
        submit("focus testing"); advanceUntilIdle()
        val c = clarify(); assertEquals(Clarification.Kind.AMBIGUOUS_TARGET, c.kind); assertEquals(2, c.candidates.size)
        assertEquals(FOCUS, s("s1").state)                                          // nothing happened
        vm.choose(c, "workstream:s10"); advanceUntilIdle()
        assertTrue(feedback().text.startsWith("Focused Testing")); assertEquals(FOCUS, s("s10").state); assertEquals(READY, s("s9").state)
    }

    @Test fun missing_mode_clarifies_then_mode_choice_previews_then_creates() = runTest(dispatcher) {
        submit("create workstream Claude Build"); advanceUntilIdle()
        val c = clarify(); assertEquals(Clarification.Kind.MISSING_MODE, c.kind)
        vm.choose(c, "EXTERNAL"); advanceUntilIdle()
        val p = state() as CommandPanelState.Preview
        assertEquals("Create WorkStream", p.command.preview.title)
        assertTrue(repo.streams.value.none { it.title == "Claude Build" })          // preview only
        vm.accept(p.command); advanceUntilIdle()
        assertEquals("Created WorkStream · Claude Build", feedback().text)
        repo.streams.value.first { it.title == "Claude Build" }.let { assertEquals(ExecutionPreference.EXTERNAL, it.executionPreference); assertNull(it.projectId); assertEquals(READY, it.state) }
    }

    @Test fun complete_stream_confirmation_rejected_then_accepted() = runTest(dispatcher) {
        submit("complete current workstream"); advanceUntilIdle()
        val conf = (state() as CommandPanelState.Confirm).confirmation
        assertEquals("Complete Psychology? The whole WorkStream is marked done.", conf.question)
        vm.dismiss(); advanceUntilIdle()
        assertEquals(FOCUS, s("s1").state)                                          // rejected → no mutation
        submit("complete current workstream"); advanceUntilIdle()
        vm.confirm((state() as CommandPanelState.Confirm).confirmation); advanceUntilIdle()
        assertEquals("Psychology completed", feedback().text); assertEquals(DONE, s("s1").state)
    }

    @Test fun capture_prompt_text_is_stored_not_executed() = runTest(dispatcher) {
        submit("capture prompt \"focus agent development\""); advanceUntilIdle()
        assertEquals("Saved prompt", feedback().text)
        assertEquals("focus agent development", repo.captures.value.single().content)
        assertEquals(CaptureType.PROMPT, repo.captures.value.single().type)
        assertEquals(FOCUS, s("s1").state); assertEquals(READY, s("s4").state)
        submit("capture note complete current task"); advanceUntilIdle()
        assertEquals(TaskStatus.IN_PROGRESS, repo.getTask("p_q17")!!.status)
    }

    @Test fun query_returns_structured_result() = runTest(dispatcher) {
        submit("what am I working on?"); advanceUntilIdle()
        val a = (state() as CommandPanelState.Answer).result as QueryResult.CurrentFocus
        assertEquals("s1", a.workStream.id); assertEquals("p_q17", a.activeTask!!.id); assertNull(a.project)
        assertTrue(repo.getEvents("s1").isEmpty())
    }

    @Test fun stale_target_at_execution_rejected_safely() = runTest(dispatcher) {
        submit("cancel current task"); advanceUntilIdle()
        val conf = (state() as CommandPanelState.Confirm).confirmation
        actions.completeTask("p_q17")                                              // changed elsewhere before confirming
        vm.confirm(conf); advanceUntilIdle()
        assertTrue(feedback().isError); assertEquals("That task is already closed", feedback().text)
        assertEquals(TaskStatus.DONE, repo.getTask("p_q17")!!.status)               // not cancelled on top
    }

    @Test fun leave_text_schedules_through_existing_architecture() = runTest(dispatcher) {
        submit("leave this for 5 minutes"); advanceUntilIdle()
        assertEquals("Left Psychology · back in 5m", feedback().text)
        s("s1").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertEquals(t0.plus(Duration.ofMinutes(5)), it.checkAt) }
        assertEquals(ScheduleKind.HUMAN_RETURN, scheduler.current["s1"]!!.kind)
    }

    @Test fun unsupported_and_invalid_give_safe_feedback_and_keep_text() = runTest(dispatcher) {
        val before = repo.streams.value
        assertFalse(submit("help me plan my week"))
        assertTrue(feedback().isError); assertTrue(feedback().examples.isNotEmpty())
        assertFalse(submit("leave for tomorrow")); assertTrue(feedback().isError)
        assertEquals(before, repo.streams.value); assertTrue(scheduler.log.isEmpty())
    }

    @Test fun this_uses_control_selection_context() = runTest(dispatcher) {
        submit("block this"); advanceUntilIdle()
        assertEquals(Clarification.Kind.NO_SELECTED_STREAM, clarify().kind)           // no selection → ask, never guess
        vm.context = CommandContext(selectedStreamId = "s4")
        submit("block this"); advanceUntilIdle()
        assertEquals(BLOCKED, s("s4").state)
    }

    @Test fun create_project_previews_before_creating() = runTest(dispatcher) {
        submit("create project MBA Research"); advanceUntilIdle()
        val p = state() as CommandPanelState.Preview
        assertEquals(listOf("Title" to "MBA Research"), p.command.preview.fields.map { it.label to it.value })
        assertTrue(repo.projects.value.none { it.title == "MBA Research" })
        vm.dismiss(); assertTrue(repo.projects.value.none { it.title == "MBA Research" })
        submit("create project MBA Research"); vm.accept((state() as CommandPanelState.Preview).command); advanceUntilIdle()
        assertEquals("Created project · MBA Research", feedback().text); assertTrue(repo.projects.value.any { it.title == "MBA Research" })
    }
}
