package com.virlin.app.command

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.command.Clarification
import com.virlin.app.domain.command.CommandContext
import com.virlin.app.domain.command.CommandEngine
import com.virlin.app.domain.command.CommandResolution
import com.virlin.app.domain.command.CommandResult
import com.virlin.app.domain.command.Confirmation
import com.virlin.app.domain.command.ResolvedCommand
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.text.TextCommandInterpreter
import com.virlin.app.domain.command.text.TextInterpretation
import com.virlin.app.domain.command.time.TemporalIntent
import com.virlin.app.domain.command.time.TimeExpressionParser
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.ScheduleKind
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.ui.agent.command.AgentCommandViewModel
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pass 13 §31–32 — calendar language through the whole pipeline (interpreter → resolver →
 * executor → actions → fake scheduler) on a fixed clock/zone. Relative durations are evaluated
 * at execution; absolute targets are revalidated at execution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeCommandIntegrationTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 9, 12)
    private val t0: Instant = ZonedDateTime.of(today, LocalTime.of(10, 0), zone).toInstant()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeAttentionScheduler
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var engine: CommandEngine
    private lateinit var interpreter: TextCommandInterpreter
    private lateinit var vm: AgentCommandViewModel

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)
    private fun local(date: LocalDate, time: LocalTime) = ZonedDateTime.of(date, time, zone).toInstant()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeClock(t0); scheduler = FakeAttentionScheduler()
        val streams = listOf(
            ws("s1", "Psychology", FOCUS, null, active = "p_q17"),
            ws("s4", "Claude Build", READY, "p1", WorkStreamMode.EXTERNAL),
            ws("s2", "Antigravity", PROCESSING, "p1", WorkStreamMode.EXTERNAL).copy(processingStartedAt = t0.minusSeconds(600), checkAt = t0.minusSeconds(1))
        )
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(seed = streams,
            seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0)), seedTasks = DemoHierarchySeed.tasks(streams, t0)), scheduler)
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        engine = CommandEngine(actions, repo, clock, zone)
        interpreter = TextCommandInterpreter(TimeExpressionParser(clock, zone))
        vm = AgentCommandViewModel(engine, interpreter::interpret)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private suspend fun s(id: String) = repo.getStream(id)!!
    private fun parsed(text: String) = (interpreter.interpret(text) as? TextInterpretation.Parsed)?.command ?: error("not parsed: '$text' → ${interpreter.interpret(text)}")
    private fun feedback() = vm.state.value as? CommandPanelState.Feedback ?: error("no feedback: ${vm.state.value}")
    private fun clarify() = (vm.state.value as? CommandPanelState.Clarify)?.clarification ?: error("no clarification: ${vm.state.value}")

    // ================================================================ §31 commands

    @Test fun leave_until_clock_time_persists_exact_instant_and_schedules() = runTest(dispatcher) {
        assertEquals(Control.LeaveCurrent(TemporalIntent.Absolute(local(today, LocalTime.of(15, 0)), "today at 3:00 PM")), parsed("leave until 3 PM"))
        val r = engine.resolve(parsed("leave this until 3 pm")) as CommandResolution.Ready
        assertEquals(listOf("WorkStream" to "Psychology", "Back at" to "Today · 3:00 PM"), r.command.preview.fields.map { it.label to it.value })
        vm.submit("leave this until 3 pm"); advanceUntilIdle()
        assertEquals("Left Psychology · back at Today · 3:00 PM", feedback().text)
        s("s1").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertEquals(local(today, LocalTime.of(15, 0)), it.checkAt) }
        assertEquals(local(today, LocalTime.of(15, 0)), scheduler.current["s1"]!!.dueAt); assertEquals(ScheduleKind.HUMAN_RETURN, scheduler.current["s1"]!!.kind)
    }

    @Test fun leave_until_tomorrow_morning_previews_exact_nine() = runTest(dispatcher) {
        val r = engine.resolve(parsed("leave this until tomorrow morning")) as CommandResolution.Ready
        assertEquals("Tomorrow · 9:00 AM", r.command.preview.fields.last().value)
        vm.submit("leave until tomorrow morning"); advanceUntilIdle()
        assertEquals(local(today.plusDays(1), LocalTime.of(9, 0)), s("s1").checkAt)
    }

    @Test fun hand_off_until_time_and_check_again_tomorrow_and_result_ready_tonight() = runTest(dispatcher) {
        actions.focusStream("s4")
        vm.submit("hand this off until 4 pm"); advanceUntilIdle()
        assertEquals("Handed off Claude Build · check at Today · 4:00 PM", feedback().text)
        s("s4").let { assertEquals(PROCESSING, it.state); assertEquals(local(today, LocalTime.of(16, 0)), it.checkAt) }
        assertEquals(ScheduleKind.EXTERNAL_CHECK, scheduler.current["s4"]!!.kind)

        actions.checkDue("s2"); vm.context = CommandContext(selectedStreamId = "s2")
        vm.submit("check again tomorrow at 9 am"); advanceUntilIdle()
        assertEquals("Antigravity still running · check at Tomorrow · 9:00 AM", feedback().text)
        s("s2").let { assertEquals(PROCESSING, it.state); assertEquals(local(today.plusDays(1), LocalTime.of(9, 0)), it.checkAt) }
        assertNull(repo.getOpenFocusSession("s2"))                                              // still-running path, never focuses

        clock.current = local(today.plusDays(1), LocalTime.of(9, 5)); actions.checkDue("s2")
        vm.submit("result ready remind me tonight"); advanceUntilIdle()
        assertEquals("Antigravity · remind at Today · 8:00 PM", feedback().text)
        s("s2").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, it.snoozeReason); assertEquals(local(today.plusDays(1), LocalTime.of(20, 0)), it.checkAt) }
    }

    @Test fun existing_duration_grammar_unchanged() = runTest(dispatcher) {
        assertEquals(Control.LeaveCurrent(Duration.ofMinutes(5)), parsed("leave for 5m"))
        assertEquals(Control.HandOffCurrent(Duration.ofMinutes(5)), parsed("hand off for 5m"))
        assertEquals(Control.StillRunning(TargetRef.ThisStream, Duration.ofMinutes(5)), parsed("check again in 5m"))
        assertEquals(Control.LeaveCurrent(Duration.ofMinutes(30)), parsed("leave for half an hour"))
        assertEquals(Control.LeaveCurrent(Duration.ofHours(1)), parsed("leave in an hour"))
        vm.submit("leave this for 5 minutes"); advanceUntilIdle()
        assertEquals("Left Psychology · back in 5m", feedback().text); assertEquals(t0.plus(Duration.ofMinutes(5)), s("s1").checkAt)
    }

    // ================================================================ §32 clock safety

    @Test fun relative_time_is_evaluated_at_execution_not_parse_time() = runTest(dispatcher) {
        val r = engine.resolve(parsed("leave for 10 minutes")) as CommandResolution.Ready       // "typed" at 10:00
        clock.advance(Duration.ofMinutes(20))                                                    // preview stayed open until 10:20
        val res = engine.execute(r.command) as CommandResult.Executed
        assertEquals("Left Psychology · back in 10m", res.summary)
        assertEquals(t0.plus(Duration.ofMinutes(30)), s("s1").checkAt)                          // 10:20 + 10m, not 10:10
    }

    @Test fun absolute_time_is_revalidated_at_execution() = runTest(dispatcher) {
        val r = engine.resolve(parsed("leave until 10:30 am")) as CommandResolution.Ready
        clock.advance(Duration.ofMinutes(45))                                                    // now 10:45 — target slipped into the past
        val res = engine.execute(r.command) as CommandResult.Rejected
        assertTrue(res.stale); assertTrue(res.reason.contains("has already passed"))
        assertEquals(FOCUS, s("s1").state); assertTrue(scheduler.current.isEmpty())              // nothing scheduled in the past
        // and at resolution time too
        clock.advance(Duration.ofHours(6))                                                       // 16:45 — the interpreter itself asks
        assertTrue(interpreter.interpret("leave until 3 pm") is TextInterpretation.NeedsTime)
        val earlier = Control.LeaveCurrent(TemporalIntent.Absolute(local(today, LocalTime.of(15, 0)), "today at 3:00 PM"))   // a stale typed command
        assertTrue(engine.resolve(earlier) is CommandResolution.NeedsClarification)
    }

    @Test fun temporal_clarifications_offer_rewritten_commands() = runTest(dispatcher) {
        vm.submit("leave until tomorrow"); advanceUntilIdle()
        val c = clarify(); assertEquals(Clarification.Kind.TIME_REQUIRED, c.kind); assertEquals("What time tomorrow?", c.question)
        assertEquals(listOf("leave until tomorrow morning", "leave until tomorrow afternoon", "leave until tomorrow evening"), c.candidates.map { it.value })
        assertEquals("Morning · 9:00 AM", c.candidates[0].title)
        vm.choose(c, "leave until tomorrow morning"); advanceUntilIdle()
        assertEquals(local(today.plusDays(1), LocalTime.of(9, 0)), s("s1").checkAt)
        actions.focusStream("s1")
        vm.submit("leave until 9"); advanceUntilIdle()
        assertEquals(Clarification.Kind.AM_PM_REQUIRED, clarify().kind)
        clock.current = local(today, LocalTime.of(16, 0))
        vm.submit("leave until 3 pm"); advanceUntilIdle()
        val p = clarify(); assertEquals(Clarification.Kind.TIME_ALREADY_PASSED, p.kind); assertEquals(listOf("leave until tomorrow at 3:00 PM"), p.candidates.map { it.value })
        assertEquals(FOCUS, s("s1").state)                                                       // nothing scheduled by a passed time
    }

    @Test fun parser_and_capture_cause_no_mutation_or_scheduling() = runTest(dispatcher) {
        val streams = repo.streams.value
        listOf("leave until 3 pm", "hand off until tomorrow morning", "check again monday at 9 am", "remind me about the result tonight").forEach { interpreter.interpret(it) }
        assertEquals(streams, repo.streams.value); assertTrue(scheduler.log.isEmpty())
        vm.submit("capture note leave this tomorrow morning"); advanceUntilIdle()
        assertEquals("Saved to Inbox", feedback().text)
        assertEquals("leave this tomorrow morning", repo.captures.value.single().content)
        assertEquals(FOCUS, s("s1").state); assertTrue(scheduler.log.isEmpty())
    }

    @Test fun query_with_time_words_stays_read_only() = runTest(dispatcher) {
        val events = repo.getEvents("s1")
        assertTrue(interpreter.interpret("what am I working on tomorrow") is TextInterpretation.Unsupported)
        vm.submit("what am I working on?"); advanceUntilIdle()
        assertTrue(vm.state.value is CommandPanelState.Answer); assertEquals(events, repo.getEvents("s1"))
    }
}
