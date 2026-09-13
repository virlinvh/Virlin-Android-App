package com.virlin.app.command

import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.TaskOwnerRef
import com.virlin.app.domain.command.VirlinCommand
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.VirlinCommand.Create
import com.virlin.app.domain.command.VirlinCommand.Query
import com.virlin.app.domain.command.text.DurationParser
import com.virlin.app.domain.command.text.TextCommandInterpreter
import com.virlin.app.domain.command.text.TextInterpretation
import com.virlin.app.domain.model.WorkStreamMode
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration

/** Pass 12 — the deterministic command language. Pure: no repository, no actions, no execution. */
class TextCommandInterpreterTest {

    // Fixed clock + fixed zone: 2026-09-12 10:00 Asia/Kolkata (a Saturday). Never Instant.now().
    private val zone = java.time.ZoneId.of("Asia/Kolkata")
    private val now = java.time.ZonedDateTime.of(2026, 9, 12, 10, 0, 0, 0, zone).toInstant()
    private val interpreter = TextCommandInterpreter(com.virlin.app.domain.command.time.TimeExpressionParser(com.virlin.app.domain.FakeClock(now), zone))

    private fun parsed(text: String): VirlinCommand = (interpreter.interpret(text) as? TextInterpretation.Parsed)?.command
        ?: error("not parsed: '$text' → ${interpreter.interpret(text)}")
    private fun invalid(text: String) = interpreter.interpret(text) as? TextInterpretation.Invalid ?: error("expected Invalid for '$text'")
    private fun unsupported(text: String) = interpreter.interpret(text) as? TextInterpretation.Unsupported ?: error("expected Unsupported for '$text'")
    private fun name(s: String) = TargetRef.ByName(s)
    /** Control 1: natural `[ACTION] + [TARGET]` forms carry a kind-less Named target; the resolver decides the entity. */
    private fun named(s: String) = TargetRef.Named(s)

    // ================================================================ CONTROL

    @Test fun focus_plain_and_quoted() {
        assertEquals(Control.FocusStream(named("psychology")), parsed("focus psychology"))
        assertEquals(Control.FocusStream(named("Agent Development")), parsed("focus \"Agent Development\""))
        assertEquals(Control.FocusStream(named("Agent Development")), parsed("focus on Agent Development"))
        assertEquals(Control.FocusStream(TargetRef.ThisStream), parsed("focus this"))
    }
    @Test fun resume() {
        assertEquals(Control.ResumeStream(named("psychology")), parsed("resume psychology"))
        assertEquals(Control.ResumeStream(TargetRef.ThisStream), parsed("resume this"))
        assertEquals(Control.ResumeStream(TargetRef.CurrentStream), parsed("resume current"))
    }
    @Test fun leave_forms() {
        assertEquals(Control.LeaveCurrent(returnAt = null), parsed("leave"))
        assertEquals(Control.LeaveCurrent(returnAt = null), parsed("leave this"))
        assertEquals(Control.LeaveCurrent(Duration.ofMinutes(10)), parsed("leave for 10 minutes"))
        assertEquals(Control.LeaveCurrent(Duration.ofMinutes(5)), parsed("leave this for 5m"))
        assertEquals(Control.LeaveCurrent(Duration.ofMinutes(15)), parsed("leave current for 15 min"))
        assertTrue(interpreter.interpret("leave for tomorrow") is TextInterpretation.Invalid)
    }
    @Test fun hand_off_forms() {
        assertEquals(Control.HandOffCurrent(checkAt = null), parsed("hand off"))
        assertEquals(Control.HandOffCurrent(checkAt = null), parsed("handoff"))
        assertEquals(Control.HandOffCurrent(checkAt = null), parsed("hand this off"))
        assertEquals(Control.HandOffCurrent(Duration.ofMinutes(5)), parsed("hand off for 5 minutes"))
        assertEquals(Control.HandOffCurrent(Duration.ofMinutes(5)), parsed("hand this off for 5m"))
        assertEquals(Control.HandOffCurrent(checkAt = null), parsed("hand off with no check"))
        assertEquals(Control.HandOffCurrent(checkAt = null), parsed("hand this off with no check"))
    }
    @Test fun still_running_and_check_again() {
        assertEquals(Control.StillRunning(TargetRef.ThisStream, Duration.ofMinutes(10)), parsed("still running for 10m"))
        assertEquals(Control.StillRunning(TargetRef.ThisStream, Duration.ofMinutes(5)), parsed("check again in 5m"))
        assertEquals("How long until the next check?", invalid("still running").reason)
    }
    @Test fun result_ready_forms() {
        assertEquals(Control.ResultReadyNow(TargetRef.ThisStream), parsed("result ready"))
        assertEquals(Control.ResultReadyNow(TargetRef.ThisStream), parsed("result is ready"))
        assertEquals(Control.ResultReadyNow(TargetRef.ThisStream), parsed("focus result now"))
        assertEquals(Control.ResultReadyLater(TargetRef.ThisStream, Duration.ofMinutes(10)), parsed("result ready remind me in 10m"))
        assertEquals(Control.ResultReadyLater(TargetRef.ThisStream, Duration.ofMinutes(20)), parsed("remind me about the result in 20 minutes"))
    }
    @Test fun block_forms() {
        assertEquals(Control.BlockStream(TargetRef.ThisStream), parsed("block this"))
        assertEquals(Control.BlockStream(TargetRef.ThisStream), parsed("mark this blocked"))
        assertEquals(Control.BlockStream(TargetRef.ThisStream), parsed("blocked"))
        assertEquals(Control.BlockStream(named("Cloud Sync")), parsed("block Cloud Sync"))
    }
    @Test fun task_forms() {
        assertEquals(Control.CompleteTask(TargetRef.CurrentTask), parsed("complete current task"))
        assertEquals(Control.CompleteTask(TargetRef.CurrentTask), parsed("complete this task"))
        assertEquals(Control.CompleteTask(name("Question 17")), parsed("complete task Question 17"))
        assertEquals(Control.CancelTask(TargetRef.CurrentTask), parsed("cancel current task"))
        assertEquals(Control.CancelTask(TargetRef.CurrentTask), parsed("cancel this task"))
        assertEquals(Control.SetCurrentTask(TargetRef.CurrentStream, named("Question 18")), parsed("set Question 18 as current"))
        assertEquals(Control.SetCurrentTask(TargetRef.CurrentStream, named("Question 18")), parsed("make Question 18 current"))
        assertEquals(Control.SetCurrentTask(TargetRef.CurrentStream, named("Question 18")), parsed("work on Question 18"))
    }
    @Test fun complete_workstream_explicit_only() {
        assertEquals(Control.CompleteStream(TargetRef.CurrentStream), parsed("complete current workstream"))
        assertEquals(Control.CompleteStream(TargetRef.ThisStream), parsed("complete this workstream"))
        listOf("complete", "done", "finish").forEach { assertTrue(it, interpreter.interpret(it) is TextInterpretation.Invalid) }
    }

    // ================================================================ CREATE

    @Test fun create_project_plain_and_quoted() {
        assertEquals(Create.CreateProject("MBA Research"), parsed("create project MBA Research"))
        assertEquals(Create.CreateProject("MBA Research"), parsed("create project \"MBA Research\""))
        assertEquals(Create.CreateProject("Thesis"), parsed("new project Thesis"))
    }
    @Test fun create_workstream_modes() {
        assertEquals(Create.CreateWorkStream("Claude Build", null, null), parsed("create workstream Claude Build"))       // mode missing → resolver asks
        assertEquals(Create.CreateWorkStream("Psychology", null, WorkStreamMode.HUMAN), parsed("create human workstream Psychology"))
        assertEquals(Create.CreateWorkStream("Claude Build", null, WorkStreamMode.EXTERNAL), parsed("create external workstream Claude Build"))
        assertEquals(Create.CreateWorkStream("Claude Build", null, WorkStreamMode.EXTERNAL), parsed("create workstream Claude Build as external"))
        assertEquals(Create.CreateWorkStream("Claude Build", named("Virlin"), WorkStreamMode.EXTERNAL), parsed("create external workstream \"Claude Build\" in project \"Virlin\""))
        assertEquals(Create.CreateWorkStream("Claude Build", named("Virlin"), null), parsed("create workstream Claude Build in project Virlin"))
    }
    @Test fun mode_never_inferred_from_title() {
        assertNull((parsed("create workstream Claude training run") as Create.CreateWorkStream).mode)
        assertNull((parsed("create workstream Antigravity fix") as Create.CreateWorkStream).mode)
    }
    @Test fun create_task_contextual_and_explicit_owner() {
        assertEquals(Create.CreateTask("Read Chapter", TaskOwnerRef.WorkStream(TargetRef.ThisStream)), parsed("create task Read Chapter"))
        assertEquals(Create.CreateTask("Read Chapter", TaskOwnerRef.Any(named("Psychology"))), parsed("create task Read Chapter in Psychology"))   // owner kind: resolver decides
        assertEquals(Create.CreateTask("Outline", TaskOwnerRef.ParentTask(TargetRef.ThisTask)), parsed("create subtask Outline"))
    }

    // ================================================================ CAPTURE

    @Test fun capture_note_forms() {
        assertEquals(Capture.CaptureNote("Investigate alarm precision"), parsed("capture note Investigate alarm precision"))
        assertEquals(Capture.CaptureNote("Investigate alarm precision"), parsed("capture note \"Investigate alarm precision\""))
        assertEquals(Capture.CaptureNote("buy paper"), parsed("remember buy paper"))
        assertEquals(Capture.CaptureNote("Ask HR"), parsed("note Ask HR"))
    }
    @Test fun capture_prompt_verbatim_multiline_and_quoted() {
        val body = "Refactor the receiver.\n\n- keep   tests green\n\tindent"
        assertEquals(Capture.CapturePrompt(body), parsed("capture prompt \"$body\""))
        assertEquals(Capture.CapturePrompt("Keep   formatting"), parsed("save prompt \"Keep   formatting\""))
    }
    @Test fun capture_prompt_containing_a_command_stays_data() {
        assertEquals(Capture.CapturePrompt("leave this for 10 minutes"), parsed("capture prompt \"leave this for 10 minutes\""))
        assertEquals(Capture.CapturePrompt("focus psychology"), parsed("capture prompt focus psychology"))
        assertEquals(Capture.CaptureNote("complete current task"), parsed("capture note complete current task"))
    }
    @Test fun capture_link() {
        assertEquals(Capture.CaptureLink("https://example.com", null), parsed("capture link https://example.com"))
        assertEquals(Capture.CaptureLink("https://example.com/x", "the docs"), parsed("save link https://example.com/x the docs"))
    }

    // ================================================================ QUERY

    @Test fun query_forms() {
        assertEquals(Query.GetCurrentFocus, parsed("what am I working on"))
        assertEquals(Query.GetCurrentFocus, parsed("What am i working on?"))
        assertEquals(Query.GetCurrentFocus, parsed("current focus"))
        assertEquals(Query.GetNeedsAttention, parsed("what needs my attention"))
        assertEquals(Query.GetNeedsAttention, parsed("show needs attention"))
        assertEquals(Query.GetProcessingStreams, parsed("what is working for me"))
        assertEquals(Query.GetProcessingStreams, parsed("what's working for me?"))
        assertEquals(Query.GetReadyStreams, parsed("show ready"))
        assertEquals(Query.GetReadyStreams, parsed("what can I work on"))
        assertEquals(Query.GetCaptureInbox, parsed("show inbox"))
        assertEquals(Query.GetCaptureInbox, parsed("what did I capture?"))
        assertEquals(Query.GetTasks(name("Psychology")), parsed("show tasks in Psychology"))
    }

    // ================================================================ DURATION

    @Test fun durations_supported() {
        assertEquals(Duration.ofMinutes(1), DurationParser.parse("1 minute"))
        assertEquals(Duration.ofMinutes(2), DurationParser.parse("2 minutes"))
        assertEquals(Duration.ofMinutes(5), DurationParser.parse("5 min"))
        assertEquals(Duration.ofMinutes(10), DurationParser.parse("10 mins"))
        assertEquals(Duration.ofMinutes(15), DurationParser.parse("15m"))
        assertEquals(Duration.ofHours(1), DurationParser.parse("1 hour"))
        assertEquals(Duration.ofHours(2), DurationParser.parse("2 hours"))
        assertEquals(Duration.ofMinutes(90), DurationParser.parse("90 minutes"))
        assertEquals(Duration.ofMinutes(90), DurationParser.parse("1h 30m"))
    }
    @Test fun durations_rejected() {
        assertNull(DurationParser.parse("0 min")); assertNull(DurationParser.parse("-5 min")); assertNull(DurationParser.parse("tomorrow"))
        assertNull(DurationParser.parse("this evening")); assertNull(DurationParser.parse("5")); assertNull(DurationParser.parse("25 hours"))
        assertTrue(invalid("leave for 0 minutes").reason.contains("between"))
        assertTrue(interpreter.interpret("leave for -5 min") is TextInterpretation.Invalid)
        assertTrue(interpreter.interpret("hand off for tomorrow") is TextInterpretation.Invalid)
    }
    @Test fun intent_recognised_before_duration() {
        assertEquals(Create.CreateProject("5 min"), parsed("create project 5 min"))          // a title, not a Leave
        assertEquals(Capture.CaptureNote("leave for 10 minutes"), parsed("note leave for 10 minutes"))
    }

    // ================================================================ GENERAL

    @Test fun unsupported_blank_casing_whitespace() {
        assertEquals(TextCommandInterpreter.UnsupportedMessage, unsupported("help me plan my week").reason)
        assertTrue(interpreter.interpret("make me more productive") is TextInterpretation.Unsupported)
        assertTrue(interpreter.interpret("do something useful") is TextInterpretation.Unsupported)
        assertTrue(interpreter.interpret("   ") is TextInterpretation.Invalid)
        assertEquals(Control.LeaveCurrent(Duration.ofMinutes(5)), parsed("  LEAVE   For 5 MIN  "))
        assertEquals(Control.FocusStream(named("Psychology")), parsed("FOCUS Psychology"))
        assertEquals(Create.CreateProject("MBA Research"), parsed("  create   project   MBA Research "))
    }
    @Test fun payload_case_and_quotes_preserved() {
        assertEquals(Create.CreateProject("MBA Research"), parsed("CREATE PROJECT MBA Research"))
        assertEquals(Capture.CaptureNote("Keep   THIS  spacing"), parsed("capture note \"Keep   THIS  spacing\""))
        assertEquals(Capture.CaptureNote("He said \"hi\" twice"), parsed("capture note He said \"hi\" twice"))
        assertEquals(Control.FocusStream(named("agent development")), parsed("focus agent development"))
    }
    @Test fun interpreter_is_pure_no_domain_dependencies() {
        val src = java.io.File("src/main/java/com/virlin/app/domain/command/text").listFiles()!!.filter { it.extension == "kt" }
        assertTrue(src.isNotEmpty())
        src.forEach { f ->
            val text = f.readText()
            listOf("VirlinActions", "CommandExecutor", "CommandEngine", "WorkStreamRepository", "com.virlin.app.data", "androidx.room", "AlarmManager", "Notification", "reflect")
                .forEach { forbidden -> assertFalse("${f.name} must not reference $forbidden", text.contains(forbidden)) }
        }
    }
}
