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
import com.virlin.app.domain.command.NavigationTarget
import com.virlin.app.domain.command.ResolvedCommand
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.VirlinCommand
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.VirlinCommand.Create
import com.virlin.app.domain.command.VirlinCommand.Navigate
import com.virlin.app.domain.command.text.TextCommandInterpreter
import com.virlin.app.domain.command.text.TextInterpretation
import com.virlin.app.domain.command.time.TimeExpressionParser
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.FOCUS
import com.virlin.app.domain.model.WorkStreamState.READY
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Control 1 — natural `[ACTION] + [TARGET]` commands with global, action-filtered target
 * resolution. Parser emits a kind-less [TargetRef.Named]; the resolver restricts candidates to the
 * kinds the action supports, clarifies cross-kind and same-kind duplicates with typed, path-aware
 * candidates, and a chosen candidate continues the ORIGINAL pending command by stable id.
 */
class ControlTargetTest {

    private val t0: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private val zone = java.time.ZoneId.of("Asia/Kolkata")

    private class World(seedStreams: List<WorkStream>, projects: List<Project>, tasks: List<Task>) {
        val clock = FakeClock(Instant.parse("2026-09-12T10:00:00Z"))
        val repo = InMemoryWorkStreamRepository(seedStreams, projects, tasks)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        val engine = CommandEngine(actions, repo, clock, java.time.ZoneId.of("Asia/Kolkata"))
        val interpreter = TextCommandInterpreter(TimeExpressionParser(clock, java.time.ZoneId.of("Asia/Kolkata")))
        fun parse(text: String) = (interpreter.interpret(text) as TextInterpretation.Parsed).command
        suspend fun stream(id: String) = repo.getStream(id)!!
        suspend fun task(id: String) = repo.getTask(id)!!
        suspend fun say(text: String, ctx: CommandContext = CommandContext.None): Outcome = engine.submit(parse(text), ctx)
        fun resolve(text: String) = engine.resolve(parse(text))
    }

    private fun ws(id: String, title: String, state: WorkStreamState = READY, project: String? = null, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)
    private fun task(id: String, title: String, stream: String?, project: String? = null, parent: String? = null, status: TaskStatus = TaskStatus.TODO) =
        Task(id = id, title = title, projectId = project, workStreamId = stream, parentTaskId = parent, status = status, createdAt = t0, updatedAt = t0)

    /**
     * Projects: Virlin Android App (p1), MBA Project (p2), Psychology (p3 — a Project sharing a title).
     * Streams: Psychology (s1, FOCUS, p3), Claude Build (s2, ext, p1), MBA Research (s3, p2), Notifications (s4, p1), Assessment Engine (s5, no project).
     * Tasks: Notification Receiver (t1, s4), Test Receiver (t2, s4, child of t1), Unit 23 Questions (t3, s1), Old Test (t4, s4),
     *        Testing (t5, s4 under t1) + Testing (t6, s5), Psychology (t7, s3).
     */
    private fun world(extraStreams: List<WorkStream> = emptyList(), extraTasks: List<Task> = emptyList()) = World(
        listOf(ws("s1", "Psychology", FOCUS, "p3", active = "t3"), ws("s2", "Claude Build", READY, "p1", WorkStreamMode.EXTERNAL), ws("s3", "MBA Research", READY, "p2"),
            ws("s4", "Notifications", READY, "p1"), ws("s5", "Assessment Engine")) + extraStreams,
        listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0), Project("p2", "MBA Project", createdAt = t0, updatedAt = t0), Project("p3", "Psychology", createdAt = t0, updatedAt = t0)),
        listOf(task("t1", "Notification Receiver", "s4", "p1"), task("t2", "Test Receiver", "s4", "p1", parent = "t1"), task("t3", "Unit 23 Questions", "s1", "p3"),
            task("t4", "Old Test", "s4", "p1"), task("t5", "Testing", "s4", "p1", parent = "t1"), task("t6", "Testing", "s5"), task("t7", "Psychology", "s3", "p2")) + extraTasks
    )

    private fun clarification(o: Outcome) = (o as Outcome.Clarify).clarification
    private fun executed(o: Outcome) = ((o as Outcome.Done).result as CommandResult.Executed)

    // ---- parser: target is the COMPLETE remaining text, kind-less
    @Test fun templates_keep_the_whole_target() {
        val w = world()
        assertEquals(Control.FocusStream(TargetRef.Named("Claude Android Build")), w.parse("Switch to Claude Android Build"))
        assertEquals(Control.FocusStream(TargetRef.Named("MBA Research Project")), w.parse("Go back to MBA Research Project"))
        assertEquals(Control.FocusStream(TargetRef.Named("Psychology")), w.parse("Continue Psychology"))
        assertEquals(Control.ResumeStream(TargetRef.Named("Psychology")), w.parse("Resume Psychology"))
        assertEquals(Control.LeaveStream(TargetRef.Named("Psychology")), w.parse("Leave Psychology"))
        assertEquals(Control.Complete(TargetRef.Named("Notification Receiver Testing")), w.parse("Complete Notification Receiver Testing"))
        assertEquals(Control.Complete(TargetRef.Named("Psychology")), w.parse("Finish Psychology"))
        assertEquals(Control.CancelTask(TargetRef.Named("Old Test")), w.parse("Cancel Old Test"))
        assertEquals(Control.SetCurrentTask(TargetRef.CurrentStream, TargetRef.Named("Notification Receiver")), w.parse("Set Notification Receiver as current"))
        assertEquals(Control.SetCurrentTask(TargetRef.CurrentStream, TargetRef.Named("Notification Receiver")), w.parse("Make Notification Receiver current"))
        assertEquals(Control.SetCurrentTask(TargetRef.CurrentStream, TargetRef.Named("Notification Receiver")), w.parse("Work on Notification Receiver"))
        assertEquals(Navigate.Open(TargetRef.Named("Psychology")), w.parse("Open Psychology"))
        assertEquals(Navigate.Open(TargetRef.Named("Test Receiver")), w.parse("Show Test Receiver"))
        // explicit / contextual forms still parse
        assertEquals(Control.LeaveCurrent(returnAt = null), w.parse("leave this"))
        assertEquals(Control.CompleteTask(TargetRef.ByName("Testing")), w.parse("complete task Testing"))
        assertEquals(Control.CompleteStream(TargetRef.ByName("Psychology")), w.parse("complete workstream Psychology"))
        assertTrue(w.interpreter.interpret("open") is TextInterpretation.Invalid)
    }

    // 1 + 2 + 23
    @Test fun switch_to_and_go_back_to_focus_a_unique_workstream() = runTest {
        val w = world()
        assertTrue(executed(w.say("Switch to Claude Build")).summary.startsWith("Focused Claude Build"))
        assertEquals(FOCUS, w.stream("s2").state); assertEquals(READY, w.stream("s1").state)
        assertTrue(executed(w.say("Go back to MBA Research")).summary.startsWith("Focused MBA Research"))
        assertEquals(FOCUS, w.stream("s3").state)
        // determinism: same command, same state → same resolution
        fun shape(r: CommandResolution) = (r as CommandResolution.NeedsClarification).clarification.let { it.kind to it.candidates }
        assertEquals(shape(w.resolve("Focus Testing")), shape(w.resolve("Focus Testing")))
    }

    // 3
    @Test fun focus_task_focuses_owning_workstream_and_sets_active_task() = runTest {
        val w = world()
        assertTrue(executed(w.say("Focus Test Receiver")).summary.startsWith("Focused Notifications"))
        assertEquals(FOCUS, w.stream("s4").state); assertEquals("t2", w.stream("s4").activeTaskId); assertEquals(READY, w.stream("s1").state)
    }

    // 4
    @Test fun leave_named_workstream_leaves_without_reminder() = runTest {
        val w = world()
        // "Psychology" is a WorkStream AND a Task here (the Project is filtered out): typed clarification first, then the choice continues Leave
        val c = clarification(w.say("Leave Psychology"))
        assertEquals(listOf("workstream:s1", "task:t7"), c.candidates.map { it.value })
        assertEquals("Left Psychology", executed(w.engine.choose(c, "workstream:s1")).summary)
        assertEquals(READY, w.stream("s1").state); assertNull(w.stream("s1").snoozedUntil); assertEquals("t3", w.stream("s1").activeTaskId)
        assertTrue((w.say("Leave Claude Build") as Outcome.Rejected).reason.contains("isn't in Focus"))
        assertEquals(READY, w.stream("s2").state)
    }

    // 5
    @Test fun leave_named_task_leaves_owning_workstream_keeping_task_position() = runTest {
        val w = world()
        assertEquals("Left Psychology", executed(w.say("Leave Unit 23 Questions")).summary)
        assertEquals(READY, w.stream("s1").state); assertEquals("t3", w.stream("s1").activeTaskId)
    }

    // 6 + 7
    @Test fun set_current_and_work_on_activate_a_task() = runTest {
        val w = world()
        w.say("Focus Notifications")
        executed(w.say("Set Notification Receiver as current")); assertEquals("t1", w.stream("s4").activeTaskId)
        executed(w.say("Work on Test Receiver")); assertEquals("t2", w.stream("s4").activeTaskId)
        executed(w.say("Make Old Test current")); assertEquals("t4", w.stream("s4").activeTaskId)
        // Project / WorkStream titles are not tasks: refused, nothing mutated
        val r = clarification(w.say("Work on Claude Build")); assertEquals(Clarification.Kind.TARGET_NOT_FOUND, r.kind); assertTrue(r.question, r.question.contains("is a WorkStream"))
        assertEquals("t4", w.stream("s4").activeTaskId)
    }

    // 8 + 9 + 10 + 12(confirmation preserved)
    @Test fun complete_resolves_task_or_workstream_with_confirmation_for_streams() = runTest {
        val w = world()
        assertEquals("Test Receiver completed", executed(w.say("Complete Test Receiver")).summary)
        assertEquals(TaskStatus.DONE, w.task("t2").status)
        val c = (w.say("Complete Claude Build") as Outcome.Confirm).confirmation
        assertTrue(c.command is ResolvedCommand.CompleteStream); assertEquals(READY, w.stream("s2").state)
        val f = (w.say("Finish Claude Build") as Outcome.Confirm).confirmation
        assertEquals(c.command, f.command)
        assertEquals("Claude Build completed", (w.engine.confirm(f) as CommandResult.Executed).summary)
        assertEquals(WorkStreamState.DONE, w.stream("s2").state)
    }

    // 11
    @Test fun cancel_unique_task_confirms_first() = runTest {
        val w = world()
        val c = (w.say("Cancel Old Test") as Outcome.Confirm).confirmation
        assertTrue(c.command is ResolvedCommand.CancelTask); assertEquals(TaskStatus.TODO, w.task("t4").status)
        w.engine.confirm(c); assertEquals(TaskStatus.CANCELLED, w.task("t4").status)
    }

    // 12 + 13 + 14 + 15 + 21
    @Test fun open_and_show_navigate_without_changing_focus() = runTest {
        val w = world()
        fun nav(o: Outcome) = ((o as Outcome.Done).result as CommandResult.Navigate).destination
        assertEquals(NavigationTarget(EntityKind.PROJECT, "p1"), nav(w.say("Open Virlin Android App")))
        assertEquals(NavigationTarget(EntityKind.WORKSTREAM, "s2"), nav(w.say("Open Claude Build")))
        assertEquals(NavigationTarget(EntityKind.TASK, "t2"), nav(w.say("Open Test Receiver")))
        assertEquals(NavigationTarget(EntityKind.TASK, "t2"), nav(w.say("Show Test Receiver")))
        assertEquals(FOCUS, w.stream("s1").state); assertEquals(READY, w.stream("s2").state)   // open ≠ focus
    }

    // 16
    @Test fun open_across_kinds_clarifies_with_typed_candidates() = runTest {
        val w = world()
        val c = clarification(w.say("Open Psychology"))
        assertEquals(Clarification.Kind.AMBIGUOUS_TARGET, c.kind)
        assertEquals(listOf("project:p3" to "Project", "workstream:s1" to "WorkStream", "task:t7" to "Task"), c.candidates.map { it.value to it.kind })
        assertEquals("MBA Project → MBA Research", c.candidates[2].subtitle)
        assertEquals("Psychology", c.candidates[1].subtitle)                            // WorkStream shows its Project
        val chosen = ((w.engine.choose(c, "task:t7") as Outcome.Done).result as CommandResult.Navigate).destination
        assertEquals(NavigationTarget(EntityKind.TASK, "t7"), chosen)
    }

    // 17
    @Test fun complete_task_vs_workstream_ambiguity_is_shown_not_prioritised() = runTest {
        val w = world(extraStreams = listOf(ws("s6", "Old Test", READY, "p1")))
        val c = clarification(w.say("Complete Old Test"))
        assertEquals(setOf("workstream:s6" to "WorkStream", "task:t4" to "Task"), c.candidates.map { it.value to it.kind }.toSet())
        assertEquals(TaskStatus.TODO, w.task("t4").status); assertEquals(READY, w.stream("s6").state)
        assertTrue(w.engine.choose(c, "workstream:s6") is Outcome.Confirm)             // stream completion still confirms
        assertEquals("Old Test completed", executed(w.engine.choose(c, "task:t4")).summary)
    }

    // 18 + 22 + 10
    @Test fun duplicate_tasks_clarify_with_paths_and_selection_continues_the_original_command() = runTest {
        val w = world()
        val c = clarification(w.say("Complete Testing"))
        assertEquals(Clarification.Kind.AMBIGUOUS_TARGET, c.kind)
        assertEquals(listOf("Virlin Android App → Notifications → Notification Receiver", "Assessment Engine"), c.candidates.map { it.subtitle })
        assertEquals(listOf("task:t5", "task:t6"), c.candidates.map { it.value })
        assertEquals(TaskStatus.TODO, w.task("t5").status); assertEquals(TaskStatus.TODO, w.task("t6").status)
        // the continuation is the SAME command with the chosen entity — no title search is re-run
        assertEquals(Control.Complete(TargetRef.Entity(EntityKind.TASK, "t6")), c.choose("task:t6"))
        assertEquals("Testing completed", executed(w.engine.choose(c, "task:t6")).summary)
        assertEquals(TaskStatus.DONE, w.task("t6").status); assertEquals(TaskStatus.TODO, w.task("t5").status)
        // a chosen id resolves by identity even when the title is still ambiguous
        assertTrue(w.engine.resolve(Control.Complete(TargetRef.Entity(EntityKind.TASK, "t5"))) is CommandResolution.Ready)
    }

    // 19
    @Test fun cancel_filters_to_tasks_before_ambiguity() = runTest {
        val w = world(extraStreams = listOf(ws("s7", "Virlin Android App")))
        val c = clarification(w.say("Cancel Virlin Android App"))                     // Project + WorkStream only
        assertEquals(Clarification.Kind.TARGET_NOT_FOUND, c.kind); assertTrue(c.candidates.isEmpty())
        assertTrue(c.question, c.question.contains("Project / WorkStream") && c.question.contains("needs a Task"))
        assertEquals(READY, w.stream("s7").state)
        assertTrue(w.repo.tasks.value.none { it.status == TaskStatus.CANCELLED })
    }

    // 20
    @Test fun project_is_not_focusable() = runTest {
        val w = world()
        val c = clarification(w.say("Focus Virlin Android App"))
        assertEquals(Clarification.Kind.TARGET_NOT_FOUND, c.kind); assertTrue(c.candidates.isEmpty())
        assertTrue(c.question, c.question.contains("is a Project"))
        assertEquals(FOCUS, w.stream("s1").state); assertTrue(w.repo.streams.value.count { it.state == FOCUS } == 1)
        // a Task without a WorkStream (standalone Project task) is not focusable either
        val w2 = world(extraTasks = listOf(task("t9", "Contact HR", null, "p2")))
        assertTrue(clarification(w2.say("Focus Contact HR")).question.contains("standalone"))
    }

    // 24 + 25
    @Test fun capture_and_create_are_unchanged() = runTest {
        val w = world()
        assertEquals(Capture.CaptureNote("complete Psychology"), w.parse("remember complete Psychology"))
        executed(w.say("remember complete Psychology"))
        assertEquals(FOCUS, w.stream("s1").state); assertEquals(1, w.repo.captures.value.size)
        assertEquals(Create.CreateProject("Placement Outreach"), w.parse("create project Placement Outreach"))
        assertEquals(Create.CreateWorkStream("Claude Build", null, null), w.parse("create workstream Claude Build"))
        assertTrue(w.resolve("create workstream Claude Build") is CommandResolution.NeedsClarification)
        // unsupported verbs stay unsupported — never re-routed to a nearby action
        listOf("Delete Psychology", "Rename Psychology", "Move Psychology", "Complete everything", "Cancel everything", "Finish all tasks", "If Claude finishes switch to Psychology").forEach {
            assertTrue(it, w.interpreter.interpret(it) is TextInterpretation.Unsupported) }
    }
}
