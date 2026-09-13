package com.virlin.app.command

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.command.Clarification
import com.virlin.app.domain.command.CommandContext
import com.virlin.app.domain.command.CommandEngine
import com.virlin.app.domain.command.CommandEngine.Outcome
import com.virlin.app.domain.command.CommandResolution
import com.virlin.app.domain.command.CommandResult
import com.virlin.app.domain.command.EntityKind
import com.virlin.app.domain.command.ResolvedCommand
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.text.TextCommandInterpreter
import com.virlin.app.domain.command.text.TextInterpretation
import com.virlin.app.domain.command.time.TemporalIntent
import com.virlin.app.domain.command.time.TimeExpressionParser
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamMode.EXTERNAL
import com.virlin.app.domain.model.WorkStreamMode.HUMAN
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.BLOCKED
import com.virlin.app.domain.model.WorkStreamState.CHECK
import com.virlin.app.domain.model.WorkStreamState.FOCUS
import com.virlin.app.domain.model.WorkStreamState.PROCESSING
import com.virlin.app.domain.model.WorkStreamState.READY
import com.virlin.app.domain.model.WorkStreamState.SNOOZED
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.ScheduleKind
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Control 2 — timed leave, external hand-off / check / still running / result ready, reminders
 * and block, all by NAME over the Control 1 target model. Fixed clock (2026-09-12 10:00 UTC =
 * 15:30 IST), fake scheduler: every timed outcome must land as an absolute due time in the
 * repository AND as one scheduled wake-up through the existing decorator — never via the parser.
 */
class ControlTimedTest {

    private val t0: Instant = Instant.parse("2026-09-12T10:00:00Z")          // 15:30 IST
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private fun ist(y: Int, mo: Int, d: Int, h: Int, mi: Int) = java.time.ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant()

    private inner class World(streams: List<WorkStream>, tasks: List<Task> = emptyList()) {
        val clock = FakeClock(t0)
        val scheduler = FakeAttentionScheduler()
        val repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(streams,
            listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0), Project("p2", "MBA Project", createdAt = t0, updatedAt = t0)), tasks), scheduler)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        val engine = CommandEngine(actions, repo, clock, zone)
        val interpreter = TextCommandInterpreter(TimeExpressionParser(clock, zone))
        fun interpret(text: String) = interpreter.interpret(text)
        fun parse(text: String) = (interpret(text) as TextInterpretation.Parsed).command
        suspend fun stream(id: String) = repo.getStream(id)!!
        suspend fun say(text: String, ctx: CommandContext = CommandContext.None): Outcome = engine.submit(parse(text), ctx)
        suspend fun ok(text: String, ctx: CommandContext = CommandContext.None) = ((say(text, ctx) as Outcome.Done).result as CommandResult.Executed)
        fun clarify(text: String, ctx: CommandContext = CommandContext.None) = (engine.resolve(parse(text), ctx) as CommandResolution.NeedsClarification).clarification
        fun rejected(text: String) = (engine.resolve(parse(text)) as CommandResolution.Rejected).reason
    }

    private fun ws(id: String, title: String, state: WorkStreamState = READY, mode: WorkStreamMode = HUMAN, project: String? = null, active: String? = null,
                   snoozeReason: SnoozeReason? = null, checkAt: Instant? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, snoozeReason = snoozeReason, checkAt = checkAt, snoozedUntil = if (state == SNOOZED) checkAt else null,
            processingStartedAt = if (state == PROCESSING) t0 else null, createdAt = t0, updatedAt = t0)
    private fun task(id: String, title: String, stream: String, project: String? = null) =
        Task(id = id, title = title, projectId = project, workStreamId = stream, createdAt = t0, updatedAt = t0)

    /** Psychology (s1, HUMAN, FOCUS, task t3 active) · Claude Build (s2, EXTERNAL, READY) · MBA Research (s3, HUMAN, READY) · Codex Research (s4, EXTERNAL, PROCESSING). */
    private fun world(vararg extra: WorkStream, tasks: List<Task> = emptyList()) = World(
        listOf(ws("s1", "Psychology", FOCUS, HUMAN, "p2", active = "t3"), ws("s2", "Claude Build", READY, EXTERNAL, "p1"), ws("s3", "MBA Research", READY, HUMAN, "p2"),
            ws("s4", "Codex Research", PROCESSING, EXTERNAL, "p1", checkAt = t0.plus(Duration.ofHours(2)))) + extra,
        listOf(task("t1", "Notification Receiver", "s2", "p1"), task("t2", "Test Receiver", "s2", "p1"), task("t3", "Unit 23 Questions", "s1", "p2"), task("t4", "Task Alpha", "s1", "p2")) + tasks)
    /** Claude Build in FOCUS instead of Psychology. */
    private fun externalFocus() = World(
        listOf(ws("s1", "Psychology", READY, HUMAN, "p2", active = "t3"), ws("s2", "Claude Build", FOCUS, EXTERNAL, "p1", active = "t1"), ws("s3", "MBA Research", READY, HUMAN, "p2"),
            ws("s4", "Codex Research", PROCESSING, EXTERNAL, "p1", checkAt = t0.plus(Duration.ofHours(2)))),
        listOf(task("t1", "Notification Receiver", "s2", "p1"), task("t2", "Test Receiver", "s2", "p1"), task("t3", "Unit 23 Questions", "s1", "p2"), task("t4", "Task Alpha", "s1", "p2")))

    private fun rel(m: Long) = TemporalIntent.Relative(Duration.ofMinutes(m))
    /** Absolute intents carry the normalised source phrase; assert the instant. */
    private fun at(c: Any?): Instant? = when (c) {
        is Control.LeaveStream -> (c.returnAt as TemporalIntent.Absolute).at
        is Control.HandOffStream -> (c.checkAt as TemporalIntent.Absolute).at
        is Control.CheckStream -> (c.checkAt as TemporalIntent.Absolute).at
        is Control.StillRunning -> (c.checkAt as TemporalIntent.Absolute).at
        is Control.ResultReadyLater -> (c.returnAt as TemporalIntent.Absolute).at
        is Control.RemindStream -> (c.at as TemporalIntent.Absolute).at
        else -> null
    }
    private val named = { s: String -> TargetRef.Named(s) }

    // ================================================================ parsing: intents, targets kept whole, temporal preserved as typed values
    @Test fun templates_parse_to_typed_commands() {
        val w = world()
        // Pass 1 aliases
        assertEquals(Control.FocusStream(named("Psychology")), w.parse("Back to Psychology"))
        assertEquals(Control.FocusStream(named("Psychology")), w.parse("Return to Psychology"))
        assertEquals(Control.FocusStream(named("Psychology")), w.parse("Switch back to Psychology"))
        assertEquals(Control.Complete(named("Test Receiver")), w.parse("Mark Test Receiver done"))
        // timed leave / come back
        assertEquals(Control.LeaveStream(named("Psychology"), rel(10)), w.parse("Leave Psychology for 10 minutes"))
        assertEquals(Control.LeaveStream(named("MBA Research"), rel(30)), w.parse("Leave MBA Research for half an hour"))
        assertEquals(ist(2026, 9, 12, 16, 0), at(w.parse("Leave Unit 23 until 4 PM")))
        assertEquals(ist(2026, 9, 13, 9, 0), at(w.parse("Leave Psychology until tomorrow morning")))
        assertEquals(Control.LeaveStream(named("Psychology")), w.parse("Leave Psychology"))
        assertEquals(Control.RemindStream(named("Psychology"), rel(10)), w.parse("Come back to Psychology in 10 minutes"))
        // hand off + check compound
        assertEquals(Control.HandOffStream(named("Claude Build")), w.parse("Hand off Claude Build"))
        assertEquals(Control.HandOffStream(named("Codex Research")), w.parse("Let Codex Research run"))
        assertEquals(Control.HandOffStream(named("Antigravity Fix")), w.parse("Leave Antigravity Fix running"))
        assertEquals(Control.HandOffStream(named("Claude Build"), rel(10)), w.parse("Hand off Claude Build for 10 minutes"))
        assertEquals(Control.HandOffStream(named("Claude Build"), rel(5)), w.parse("Hand off Claude Build and check in 5 minutes"))
        assertEquals(ist(2026, 9, 12, 23, 0), at(w.parse("Hand off Codex Research and check at 11 PM")))
        assertEquals(Control.HandOffStream(named("Claude Build"), rel(10)), w.parse("Leave Claude Build running for 10 minutes"))
        // check
        assertEquals(Control.CheckStream(named("Claude Build"), rel(3)), w.parse("Check Claude Build in 3 minutes"))
        assertEquals(Control.CheckStream(named("Claude Build"), rel(10)), w.parse("Check Claude Build after 10 minutes"))
        assertEquals(ist(2026, 9, 12, 23, 0), at(w.parse("Check Codex Research at 11 PM")))
        assertEquals(Control.CheckStream(named("Claude Build"), rel(5)), w.parse("Check Claude Build again in 5 minutes"))
        assertEquals(Control.CheckStream(named("Claude Build"), rel(30)), w.parse("Check Claude Build in half an hour"))
        assertEquals(ist(2026, 9, 13, 9, 0), at(w.parse("Check Claude Build tomorrow morning")))
        assertTrue(w.interpret("Check Claude Build") is TextInterpretation.Invalid)
        // still running
        assertTrue((w.interpret("Claude Build is still running") as TextInterpretation.Invalid).reason.contains("How long"))
        assertEquals(Control.StillRunning(named("Claude Build"), rel(5)), w.parse("Claude Build is still running, check in 5 minutes"))
        assertEquals(Control.StillRunning(named("Codex Research"), rel(10)), w.parse("Codex Research is still working, check in 10 minutes"))
        assertEquals(ist(2026, 9, 12, 23, 0), at(w.parse("Antigravity Fix is still processing, check at 11 PM")))
        // result ready
        assertEquals(Control.ResultReady(named("Claude Build")), w.parse("Claude Build is ready"))
        assertEquals(Control.ResultReady(named("Codex Research")), w.parse("Result ready for Codex Research"))
        assertEquals(Control.ResultReady(named("Antigravity Fix")), w.parse("Antigravity Fix result is ready"))
        assertEquals(Control.ResultReadyNow(named("Claude Build")), w.parse("Focus Claude Build, result ready"))
        assertEquals(Control.ResultReadyNow(named("Codex Research")), w.parse("Result ready for Codex Research, focus now"))
        assertEquals(Control.ResultReadyNow(named("Claude Build")), w.parse("Claude Build is ready, focus now"))
        assertEquals(Control.ResultReadyLater(named("Claude Build"), rel(15)), w.parse("Result ready for Claude Build, remind me in 15 minutes"))
        assertEquals(ist(2026, 9, 12, 23, 0), at(w.parse("Claude Build is ready, remind me at 11 PM")))
        // block
        assertEquals(Control.BlockStream(named("Claude Build")), w.parse("Block Claude Build"))
        assertEquals(Control.BlockStream(named("Psychology")), w.parse("Mark Psychology blocked"))
        assertEquals(Control.BlockStream(named("MBA Research")), w.parse("MBA Research is blocked"))
        // remind / bring back
        assertEquals(Control.RemindStream(named("Psychology"), rel(30)), w.parse("Remind me about Psychology in 30 minutes"))
        assertEquals(ist(2026, 9, 12, 18, 0), at(w.parse("Bring MBA Research back at 6 PM")))
        assertEquals(ist(2026, 9, 14, 10, 0), at(w.parse("Remind me about Psychology Monday at 10 AM")))
        // time clarifications / rejections come from the time layer, the command never forms
        assertEquals(Clarification.Kind.AM_PM_REQUIRED, (w.interpret("Check Claude Build at 4") as TextInterpretation.NeedsTime).clarification.kind)
        assertEquals(Clarification.Kind.TIME_REQUIRED, (w.interpret("Leave Psychology until tomorrow") as TextInterpretation.NeedsTime).clarification.kind)
        assertTrue(w.interpret("Check Claude Build yesterday at 4 PM") is TextInterpretation.Invalid)
        // the AM/PM candidates are full rewritten commands that re-parse to the SAME intent
        val c = (w.interpret("Check Claude Build at 4") as TextInterpretation.NeedsTime).clarification
        assertTrue(c.candidates.isNotEmpty()); c.candidates.forEach { assertTrue(it.value, c.choose(it.value) is Control.CheckStream) }
    }

    // ================================================================ timed leave (5, 6, 7, 8, 9, 41, 47, 59, 61)
    @Test fun timed_leave_is_human_return_never_processing() = runTest {
        val w = world()
        w.ok("Leave Psychology for 10 minutes")
        w.stream("s1").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertEquals(t0.plus(Duration.ofMinutes(10)), it.snoozedUntil); assertEquals("t3", it.activeTaskId) }
        assertEquals(ScheduleKind.HUMAN_RETURN, w.scheduler.current["s1"]?.kind)
        assertEquals(t0.plus(Duration.ofMinutes(10)), w.scheduler.current["s1"]?.dueAt)
        // absolute, weekday, hour
        val w2 = world(); w2.ok("Leave Psychology until 4 PM"); assertEquals(ist(2026, 9, 12, 16, 0), w2.stream("s1").snoozedUntil)
        val w3 = world(); w3.ok("Leave Psychology until Monday at 10 AM"); assertEquals(ist(2026, 9, 14, 10, 0), w3.stream("s1").snoozedUntil)
        val w4 = world(); w4.ok("Leave Psychology for an hour"); assertEquals(t0.plus(Duration.ofHours(1)), w4.stream("s1").snoozedUntil)
        // Task target: owning WorkStream leaves; the Task becomes / stays the position left
        val w5 = world(); w5.ok("Leave Task Alpha for 10 minutes")
        w5.stream("s1").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertEquals("t4", it.activeTaskId) }
        // an EXTERNAL stream left without "running" is STILL a human leave
        val w6 = externalFocus(); w6.ok("Leave Claude Build for 10 minutes")
        w6.stream("s2").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertFalse(it.state == PROCESSING) }
        // plain leave → READY
        val w7 = world(); w7.ok("Leave Psychology"); assertEquals(READY, w7.stream("s1").state); assertNull(w7.stream("s1").snoozedUntil)
    }

    // ================================================================ hand off (10-16, 42)
    @Test fun hand_off_by_name_needs_external_focus() = runTest {
        val w = externalFocus()
        w.ok("Hand off Claude Build")
        w.stream("s2").let { assertEquals(PROCESSING, it.state); assertNull(it.checkAt) }
        assertNull(w.scheduler.current["s2"])
        val w2 = externalFocus(); w2.ok("Let Claude Build run"); assertEquals(PROCESSING, w2.stream("s2").state)
        val w3 = externalFocus(); w3.ok("Leave Claude Build running"); assertEquals(PROCESSING, w3.stream("s2").state)
        val w4 = externalFocus(); w4.ok("Hand off Claude Build for 10 minutes")
        w4.stream("s2").let { assertEquals(PROCESSING, it.state); assertEquals(t0.plus(Duration.ofMinutes(10)), it.checkAt) }
        assertEquals(ScheduleKind.EXTERNAL_CHECK, w4.scheduler.current["s2"]?.kind)
        val w5 = externalFocus(); w5.ok("Hand off Claude Build and check in 5 minutes"); assertEquals(t0.plus(Duration.ofMinutes(5)), w5.stream("s2").checkAt)
        val w6 = externalFocus(); w6.ok("Hand off Claude Build and check at 11 PM"); assertEquals(ist(2026, 9, 12, 23, 0), w6.stream("s2").checkAt)
        // COLLISION 42: "leave X running for D" is a hand-off with a check — never a human return
        val w7 = externalFocus(); w7.ok("Leave Claude Build running for 10 minutes")
        w7.stream("s2").let { assertEquals(PROCESSING, it.state); assertNull(it.snoozeReason); assertEquals(t0.plus(Duration.ofMinutes(10)), it.checkAt) }
        // Task locates the owner and becomes the position handed off
        val w8 = externalFocus(); w8.ok("Hand off Test Receiver"); w8.stream("s2").let { assertEquals(PROCESSING, it.state); assertEquals("t2", it.activeTaskId) }
        // human-mode stream: refused, nothing changes (mode from data, not from the name)
        val w9 = world()
        assertTrue(w9.rejected("Hand off Psychology").contains("human work")); assertEquals(FOCUS, w9.stream("s1").state)
        val w10 = World(listOf(ws("s9", "Claude Notes", FOCUS, HUMAN)))
        assertTrue(w10.rejected("Hand off Claude Notes").contains("human work"))
    }

    // ================================================================ check (17-21, 43, 58, 60)
    @Test fun check_schedules_or_moves_the_external_check() = runTest {
        // from FOCUS: hand off with a check
        val w = externalFocus(); w.ok("Check Claude Build in 5 minutes")
        w.stream("s2").let { assertEquals(PROCESSING, it.state); assertEquals(t0.plus(Duration.ofMinutes(5)), it.checkAt); assertNull(it.snoozeReason) }
        assertEquals(ScheduleKind.EXTERNAL_CHECK, w.scheduler.current["s2"]?.kind)
        // while PROCESSING: moves the next check (Codex Research had a 2h check)
        val w2 = world(); w2.ok("Check Codex Research after 10 minutes"); assertEquals(t0.plus(Duration.ofMinutes(10)), w2.stream("s4").checkAt); assertEquals(PROCESSING, w2.stream("s4").state)
        val w3 = world(); w3.ok("Check Codex Research at 11 PM"); assertEquals(ist(2026, 9, 12, 23, 0), w3.stream("s4").checkAt)
        val w4 = world(); w4.ok("Check Codex Research again in 5 minutes"); assertEquals(t0.plus(Duration.ofMinutes(5)), w4.stream("s4").checkAt)
        val w5 = world(); w5.ok("Check Codex Research in half an hour"); assertEquals(t0.plus(Duration.ofMinutes(30)), w5.stream("s4").checkAt)
        val w6 = world(); w6.ok("Check Codex Research tomorrow morning"); assertEquals(ist(2026, 9, 13, 9, 0), w6.stream("s4").checkAt)
        // due check (CHECK, no snooze reason) → back to PROCESSING with the new time
        val w7 = world(ws("s5", "Gemini Run", CHECK, EXTERNAL)); w7.ok("Check Gemini Run in 15 minutes"); assertEquals(PROCESSING, w7.stream("s5").state)
        // human stream / idle external stream: refused, no processing mutation
        val w8 = world(); assertTrue(w8.rejected("Check Psychology in 5 minutes").contains("human work")); assertEquals(FOCUS, w8.stream("s1").state)
        assertTrue(w8.rejected("Check Claude Build in 5 minutes").contains("isn't processing")); assertEquals(READY, w8.stream("s2").state)
    }

    // ================================================================ still running (22-25)
    @Test fun still_running_by_name() = runTest {
        val w = world()
        assertTrue((w.interpret("Codex Research is still running") as TextInterpretation.Invalid).reason.contains("How long"))   // no invented time
        w.ok("Codex Research is still running, check in 5 minutes"); assertEquals(t0.plus(Duration.ofMinutes(5)), w.stream("s4").checkAt); assertEquals(PROCESSING, w.stream("s4").state)
        w.ok("Codex Research is still working, check in 10 minutes"); assertEquals(t0.plus(Duration.ofMinutes(10)), w.stream("s4").checkAt)
        w.ok("Codex Research is still processing, check at 11 PM"); assertEquals(ist(2026, 9, 12, 23, 0), w.stream("s4").checkAt)
        assertTrue(w.rejected("Psychology is still running, check in 5 minutes").contains("human work"))
    }

    // ================================================================ result ready (26-31, 45, 46)
    @Test fun result_ready_never_focuses_unless_asked() = runTest {
        val w = world(); w.ok("Codex Research is ready")
        w.stream("s4").let { assertEquals(READY, it.state); assertNull(it.checkAt); assertNull(it.processingStartedAt) }
        assertEquals(FOCUS, w.stream("s1").state)                                        // Psychology keeps Focus
        val w2 = world(); w2.ok("Result ready for Codex Research"); assertEquals(READY, w2.stream("s4").state); assertEquals(FOCUS, w2.stream("s1").state)
        val w3 = world(); w3.ok("Codex Research is ready, focus now")
        assertEquals(FOCUS, w3.stream("s4").state); assertEquals(READY, w3.stream("s1").state)                // single-Focus displacement
        val w4 = world(); w4.ok("Result ready for Codex Research, focus now"); assertEquals(FOCUS, w4.stream("s4").state)
        val w5 = world(); w5.ok("Result ready for Codex Research, remind me in 15 minutes")
        w5.stream("s4").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, it.snoozeReason); assertEquals(t0.plus(Duration.ofMinutes(15)), it.snoozedUntil) }
        assertEquals(ScheduleKind.EXTERNAL_RESULT_READY, w5.scheduler.current["s4"]?.kind)
        val w6 = world(); w6.ok("Codex Research is ready, remind me at 11 PM"); assertEquals(ist(2026, 9, 12, 23, 0), w6.stream("s4").snoozedUntil)
        assertTrue(w6.rejected("Psychology is ready").contains("human work"))
    }

    // ================================================================ block (32-36, 48)
    @Test fun block_is_workstream_only() = runTest {
        val w = world(); w.ok("Block Claude Build"); assertEquals(BLOCKED, w.stream("s2").state)
        w.ok("Mark Codex Research blocked"); assertEquals(BLOCKED, w.stream("s4").state)
        w.ok("MBA Research is blocked"); assertEquals(BLOCKED, w.stream("s3").state)
        // a Task never blocks its owner; a Project is never a target
        val c = w.clarify("Block Task Alpha"); assertEquals(Clarification.Kind.TARGET_NOT_FOUND, c.kind); assertTrue(c.question, c.question.contains("is a Task") && c.question.contains("needs a WorkStream"))
        assertEquals(FOCUS, w.stream("s1").state)
        val p = w.clarify("Block Virlin Android App"); assertTrue(p.question, p.question.contains("is a Project"))
        // collision 47/48: leave → READY, blocked → BLOCKED
        val w2 = world(); w2.ok("Leave Psychology"); assertEquals(READY, w2.stream("s1").state)
        val w3 = world(); w3.ok("Psychology is blocked"); assertEquals(BLOCKED, w3.stream("s1").state)
    }

    // ================================================================ remind / bring back (37-40, 44)
    @Test fun reminders_are_state_sensitive_never_generic() = runTest {
        val w = world(); w.ok("Remind me about Psychology in 20 minutes")                       // FOCUS → leave with HUMAN return
        w.stream("s1").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertEquals(t0.plus(Duration.ofMinutes(20)), it.snoozedUntil) }
        w.ok("Bring MBA Research back at 6 PM")                                                 // READY → snooze (HUMAN_RETURN)
        w.stream("s3").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertEquals(ist(2026, 9, 12, 18, 0), it.snoozedUntil) }
        assertEquals(ScheduleKind.HUMAN_RETURN, w.scheduler.current["s3"]?.kind)
        w.ok("Remind me about MBA Research at 7 PM"); assertEquals(ist(2026, 9, 12, 19, 0), w.stream("s3").snoozedUntil)   // SNOOZED → defer, reason kept
        assertEquals(SnoozeReason.HUMAN_RETURN, w.stream("s3").snoozeReason)
        // external result-ready snooze keeps EXTERNAL_RESULT_READY
        val w2 = world(ws("s6", "Gemini Run", SNOOZED, EXTERNAL, snoozeReason = SnoozeReason.EXTERNAL_RESULT_READY, checkAt = t0.plus(Duration.ofMinutes(5))))
        w2.ok("Remind me about Gemini Run in 10 minutes")
        w2.stream("s6").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, it.snoozeReason); assertEquals(t0.plus(Duration.ofMinutes(10)), it.snoozedUntil) }
        // still-processing external work: the reminder is its next check (collision 43/44 stays distinct: HUMAN vs EXTERNAL)
        val w3 = world(); w3.ok("Remind me about Codex Research in 5 minutes"); w3.stream("s4").let { assertEquals(PROCESSING, it.state); assertEquals(t0.plus(Duration.ofMinutes(5)), it.checkAt) }
        w3.ok("Remind me about Psychology in 5 minutes"); assertEquals(SnoozeReason.HUMAN_RETURN, w3.stream("s1").snoozeReason)
        // no safe reading → refusal, nothing changes
        val w4 = world(ws("s7", "Stuck Stream", BLOCKED, HUMAN)); assertTrue(w4.rejected("Remind me about Stuck Stream in 10 minutes").contains("no safe reminder")); assertEquals(BLOCKED, w4.stream("s7").state)
        // "come back to X" is the same human return
        val w5 = world(); w5.ok("Come back to Psychology in 10 minutes"); assertEquals(SnoozeReason.HUMAN_RETURN, w5.stream("s1").snoozeReason)
    }

    // ================================================================ ambiguity keeps the parsed time (49-52)
    @Test fun ambiguity_preserves_temporal_intent_and_continues_by_id() = runTest {
        val w = world(ws("s8", "Claude Build", PROCESSING, EXTERNAL, "p2", checkAt = t0.plus(Duration.ofHours(1))))
        val c = (w.say("Check Claude Build in 5 minutes") as Outcome.Clarify).clarification
        assertEquals(listOf("workstream:s2", "workstream:s8"), c.candidates.map { it.value })
        assertEquals(Control.CheckStream(TargetRef.Entity(EntityKind.WORKSTREAM, "s8"), rel(5)), c.choose("workstream:s8"))   // same typed 5 minutes, no reparse
        w.engine.choose(c, "workstream:s8")
        assertEquals(t0.plus(Duration.ofMinutes(5)), w.stream("s8").checkAt); assertEquals(READY, w.stream("s2").state)
        // Task + WorkStream of the same name for a timed leave → both offered; choosing the Task leaves its owner with the Task position
        val w2 = world(ws("s9", "Testing", READY, HUMAN), tasks = listOf(task("t9", "Testing", "s1", "p2")))
        val c2 = (w2.say("Leave Testing for 10 minutes") as Outcome.Clarify).clarification
        assertEquals(setOf("workstream:s9", "task:t9"), c2.candidates.map { it.value }.toSet())
        w2.engine.choose(c2, "task:t9")
        w2.stream("s1").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertEquals("t9", it.activeTaskId); assertEquals(t0.plus(Duration.ofMinutes(10)), it.snoozedUntil) }
        // duplicate external streams for result ready + remind later
        val w3 = world(ws("s8", "Codex Research", PROCESSING, EXTERNAL, "p2", checkAt = t0.plus(Duration.ofHours(1))))
        val c3 = (w3.say("Result ready for Codex Research, remind me in 15 minutes") as Outcome.Clarify).clarification
        assertEquals(Control.ResultReadyLater(TargetRef.Entity(EntityKind.WORKSTREAM, "s8"), rel(15)), c3.choose("workstream:s8"))
        w3.engine.choose(c3, "workstream:s8")
        w3.stream("s8").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, it.snoozeReason); assertEquals(t0.plus(Duration.ofMinutes(15)), it.snoozedUntil) }
        assertEquals(PROCESSING, w3.stream("s4").state)
        assertTrue(w3.engine.resolve(Control.ResultReadyLater(TargetRef.Entity(EntityKind.WORKSTREAM, "s4"), rel(15))) is CommandResolution.Ready)   // identity, not title search
    }

    // ================================================================ contextual shortcuts (53-57)
    @Test fun contextual_shortcuts_need_one_safe_referent() = runTest {
        val w = world(); w.ok("Leave this for 10 minutes"); assertEquals(SnoozeReason.HUMAN_RETURN, w.stream("s1").snoozeReason)
        val w2 = externalFocus(); w2.ok("Hand this off", CommandContext("s2")); assertEquals(PROCESSING, w2.stream("s2").state)
        val w3 = externalFocus(); w3.ok("Hand this off and check in 5 minutes", CommandContext("s2")); assertEquals(t0.plus(Duration.ofMinutes(5)), w3.stream("s2").checkAt)
        val w4 = world(); w4.ok("Still running, check again in 5 minutes", CommandContext("s4")); assertEquals(t0.plus(Duration.ofMinutes(5)), w4.stream("s4").checkAt)
        w4.ok("Check this in 10 minutes", CommandContext("s4")); assertEquals(t0.plus(Duration.ofMinutes(10)), w4.stream("s4").checkAt)
        w4.ok("Focus it now", CommandContext("s4")); assertEquals(FOCUS, w4.stream("s4").state)
        // no selection → clarification, no mutation
        val w5 = world()
        assertEquals(Clarification.Kind.NO_SELECTED_STREAM, w5.clarify("Remind me in 10 minutes").kind)
        assertTrue(w5.rejected("Hand it off").contains("human work"))                          // "it" = the FOCUS stream; Psychology is human work
        assertEquals(FOCUS, w5.stream("s1").state); assertEquals(READY, w5.stream("s2").state)
        w5.ok("Remind me in 10 minutes", CommandContext("s3")); assertEquals(SNOOZED, w5.stream("s3").state)
    }

    // ================================================================ safety (65-70) + create / capture regression
    @Test fun unsupported_wording_and_capture_stay_safe() = runTest {
        val w = world()
        listOf("Stop Claude Build", "Pause Psychology", "Kill Claude Build", "Close Psychology", "Delete Psychology", "Rename Psychology", "Move Psychology",
            "Leave Psychology and switch to Claude Build", "Complete Testing and open Psychology", "Block Claude Build and remind me about MBA Research",
            "If Claude Build finishes switch to Psychology", "Don't hand off Claude Build", "Can I check Claude Build in 5 minutes?", "Complete everything", "Cancel everything").forEach {
            assertTrue(it, w.interpret(it) is TextInterpretation.Unsupported) }
        assertEquals(Capture.CaptureNote("check Claude Build in 5 minutes"), w.parse("Remember check Claude Build in 5 minutes"))
        w.ok("Remember check Claude Build in 5 minutes")
        assertEquals(READY, w.stream("s2").state); assertTrue(w.scheduler.current.isEmpty())
        assertEquals(1, w.repo.captures.value.size)
        assertTrue(w.engine.resolve(w.parse("create workstream Claude Build")) is CommandResolution.NeedsClarification)
        // existing contextual grammar untouched
        assertEquals(Control.HandOffCurrent(rel(5)), w.parse("hand off for 5m"))
        assertEquals(Control.LeaveCurrent(rel(10)), w.parse("leave for 10 minutes"))
        assertEquals(Control.ResultReadyNow(TargetRef.ThisStream), w.parse("result ready"))
    }
}
