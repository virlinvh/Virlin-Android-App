package com.virlin.app.command

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.command.Clarification
import com.virlin.app.domain.command.CaptureContextRef
import com.virlin.app.domain.command.CommandContext
import com.virlin.app.domain.command.CommandEngine
import com.virlin.app.domain.command.CommandEngine.Outcome
import com.virlin.app.domain.command.CommandResolution
import com.virlin.app.domain.command.CommandResult
import com.virlin.app.domain.command.QueryResult
import com.virlin.app.domain.command.ResolvedCommand
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.TaskOwnerRef
import com.virlin.app.domain.command.VirlinCommand
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.VirlinCommand.Create
import com.virlin.app.domain.command.VirlinCommand.Query
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.ScheduleKind
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Pass 11 — the deterministic command contract: typed commands, conservative reference
 * resolution, clarification/confirmation as values, execution through the SAME VirlinActions
 * everything else uses (fake scheduler proves alarms follow naturally), read-only queries.
 */
class CommandEngineTest {

    private val t0: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeAttentionScheduler
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var engine: CommandEngine

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        clock = FakeClock(t0); scheduler = FakeAttentionScheduler()
        val streams = listOf(
            ws("s4", "Agent Development", READY, "p1", WorkStreamMode.EXTERNAL),
            ws("s1", "Psychology Unit 23", FOCUS, null, active = "p_q17"),
            ws("s8", "Projectless", READY, null),
            ws("s2", "Antigravity", PROCESSING, "p2", WorkStreamMode.EXTERNAL).copy(processingStartedAt = t0.minusSeconds(600), checkAt = t0.minusSeconds(1)),
            ws("s7", "Notion Transfer", SNOOZED, null).copy(snoozeReason = SnoozeReason.HUMAN_RETURN, checkAt = t0.minusSeconds(1)),
            ws("s9", "Testing", READY, "p1"),
            ws("s10", "Testing", READY, "p2"),
            ws("s_done", "Old", DONE, "p1")
        )
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(
            seed = streams,
            seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0), Project("p2", "MBA Project", createdAt = t0, updatedAt = t0), Project("p3", "Testing", createdAt = t0, updatedAt = t0)),
            seedTasks = DemoHierarchySeed.tasks(streams, t0)
        ), scheduler)
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        engine = CommandEngine(actions, repo, clock, java.time.ZoneId.of("Asia/Kolkata"))
    }

    private suspend fun s(id: String) = repo.getStream(id)!!
    private suspend fun done(cmd: VirlinCommand, ctx: CommandContext = CommandContext.None): CommandResult.Executed {
        val o = engine.submit(cmd, ctx); return (o as? Outcome.Done)?.result as? CommandResult.Executed ?: error("not executed: $o")
    }
    private suspend fun clarify(cmd: VirlinCommand, ctx: CommandContext = CommandContext.None): Clarification =
        (engine.submit(cmd, ctx) as? Outcome.Clarify)?.clarification ?: error("expected clarification for $cmd")
    private suspend fun answered(q: Query): QueryResult = ((engine.submit(q) as Outcome.Done).result as CommandResult.Answered).result

    // ================================================================ §37 CONTROL

    @Test fun focus_resolved_by_unique_exact_name() = runTest {
        val r = done(Control.FocusStream(TargetRef.ByName("Agent Development")))
        assertEquals("Focused Agent Development · Psychology Unit 23 set aside", r.summary)
        assertEquals(FOCUS, s("s4").state); assertEquals(READY, s("s1").state)
    }

    @Test fun duplicate_name_clarifies_with_candidates_never_picks_first() = runTest {
        val c = clarify(Control.FocusStream(TargetRef.ByName("Testing")))
        assertEquals(Clarification.Kind.AMBIGUOUS_TARGET, c.kind)
        assertEquals(listOf("s9" to "Virlin Android App", "s10" to "MBA Project"), c.candidates.map { it.value to it.subtitle })
        assertEquals(READY, s("s9").state); assertEquals(READY, s("s10").state); assertEquals(FOCUS, s("s1").state)
        // choosing resolves by stable id
        val r = (engine.choose(c, "s10") as Outcome.Done).result as CommandResult.Executed
        assertTrue(r.summary.startsWith("Focused Testing")); assertEquals(FOCUS, s("s10").state); assertEquals(READY, s("s9").state)
        assertTrue(engine.choose(c, "s4") is Outcome.Rejected)                 // not an offered candidate
    }

    @Test fun missing_name_not_found() = runTest {
        val c = clarify(Control.FocusStream(TargetRef.ByName("Nonexistent")))
        assertEquals(Clarification.Kind.TARGET_NOT_FOUND, c.kind); assertFalse(c.canChoose)
    }

    @Test fun current_stream_resolves_focus() = runTest {
        val r = engine.resolve(Control.BlockStream(TargetRef.CurrentStream)) as CommandResolution.Ready
        assertEquals("s1", (r.command as ResolvedCommand.BlockStream).streamId)
    }

    @Test fun current_task_resolves_active_task() = runTest {
        val r = engine.resolve(Control.CompleteTask(TargetRef.CurrentTask)) as CommandResolution.Ready
        assertEquals("p_q17", (r.command as ResolvedCommand.CompleteTask).taskId)
    }

    @Test fun no_current_task_clarifies() = runTest {
        actions.clearActiveTask("s1")
        val c = clarify(Control.CompleteTask(TargetRef.CurrentTask))
        assertEquals(Clarification.Kind.NO_CURRENT_TASK, c.kind); assertTrue(c.candidates.isNotEmpty())
        assertTrue(c.candidates.all { cand -> repo.tasks.value.first { it.id == cand.value }.workStreamId == "s1" })
    }

    @Test fun leave_current_with_reminder_maps_to_leaveFocus_and_schedules() = runTest {
        val r = done(Control.LeaveCurrent(Duration.ofMinutes(5)))
        assertEquals("Left Psychology Unit 23 · back in 5m", r.summary)
        s("s1").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.HUMAN_RETURN, it.snoozeReason); assertEquals(t0.plus(Duration.ofMinutes(5)), it.checkAt); assertEquals("p_q17", it.activeTaskId) }
        assertEquals(ScheduleKind.HUMAN_RETURN, scheduler.current["s1"]!!.kind)   // scheduling decorator, not the executor
    }

    @Test fun leave_current_without_reminder() = runTest {
        done(Control.LeaveCurrent(returnAt = null)); assertEquals(READY, s("s1").state); assertNull(scheduler.current["s1"])
    }

    @Test fun handoff_current_with_check() = runTest {
        actions.focusStream("s4")
        done(Control.HandOffCurrent(Duration.ofMinutes(5)))
        s("s4").let { assertEquals(PROCESSING, it.state); assertEquals(t0.plus(Duration.ofMinutes(5)), it.checkAt) }
        assertEquals(ScheduleKind.EXTERNAL_CHECK, scheduler.current["s4"]!!.kind)
    }

    @Test fun handoff_current_without_check() = runTest {
        actions.focusStream("s4"); done(Control.HandOffCurrent(checkAt = null))
        assertEquals(PROCESSING, s("s4").state); assertNull(s("s4").checkAt); assertNull(scheduler.current["s4"])
    }

    @Test fun check_outcomes_do_not_focus() = runTest {
        actions.checkDue("s2")
        done(Control.StillRunning(TargetRef.ByName("Antigravity"), Duration.ofMinutes(10)))
        assertEquals(PROCESSING, s("s2").state); assertNull(repo.getOpenFocusSession("s2")); assertEquals(FOCUS, s("s1").state)
    }

    @Test fun still_running_result_ready_now_later_block() = runTest {
        actions.checkDue("s2")
        done(Control.ResultReadyLater(TargetRef.ById("s2"), Duration.ofMinutes(15)))
        s("s2").let { assertEquals(SNOOZED, it.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, it.snoozeReason) }
        actions.checkDue("s2".also { clock.advance(Duration.ofMinutes(16)) })
        done(Control.ResultReadyNow(TargetRef.ById("s2")))
        assertEquals(FOCUS, s("s2").state); assertEquals(READY, s("s1").state)
        done(Control.BlockStream(TargetRef.ByName("Projectless"), "waiting on data"))
        assertEquals(BLOCKED, s("s8").state)
    }

    @Test fun complete_task_executes() = runTest {
        val r = done(Control.CompleteTask(TargetRef.ByName("Question 17")))
        assertEquals("Question 17 completed", r.summary)
        assertEquals(TaskStatus.DONE, repo.getTask("p_q17")!!.status); assertNull(s("s1").activeTaskId); assertEquals(FOCUS, s("s1").state)
    }

    @Test fun cancel_task_requires_confirmation() = runTest {
        val o = engine.submit(Control.CancelTask(TargetRef.ByName("Question 17"))) as Outcome.Confirm
        assertTrue(o.confirmation.question.startsWith("Cancel Question 17?"))
        assertEquals(TaskStatus.IN_PROGRESS, repo.getTask("p_q17")!!.status)
        engine.confirm(o.confirmation)
        assertEquals(TaskStatus.CANCELLED, repo.getTask("p_q17")!!.status)
    }

    @Test fun complete_stream_requires_confirmation() = runTest {
        val o = engine.submit(Control.CompleteStream(TargetRef.ByName("Agent Development"))) as Outcome.Confirm
        assertEquals("Complete Agent Development? The whole WorkStream is marked done.", o.confirmation.question)
        assertEquals(READY, s("s4").state)
        val r = engine.confirm(o.confirmation) as CommandResult.Executed
        assertEquals("Agent Development completed", r.summary); assertEquals(DONE, s("s4").state)
    }

    @Test fun resume_and_set_current_task() = runTest {
        actions.checkDue("s7")
        done(Control.ResumeStream(TargetRef.ByName("Notion Transfer")))
        assertEquals(FOCUS, s("s7").state); assertEquals(READY, s("s1").state)
        done(Control.SetCurrentTask(TargetRef.ById("s1"), TargetRef.ByName("Answer Questions")))
        assertEquals("p_answer", s("s1").activeTaskId)
    }

    @Test fun this_stream_uses_explicit_context_only() = runTest {
        val c = clarify(Control.BlockStream(TargetRef.ThisStream))
        assertEquals(Clarification.Kind.NO_SELECTED_STREAM, c.kind)
        done(Control.BlockStream(TargetRef.ThisStream), CommandContext(selectedStreamId = "s8"))
        assertEquals(BLOCKED, s("s8").state)
    }

    // ================================================================ §38 CREATE

    @Test fun create_project() = runTest {
        val r = done(Create.CreateProject("Thesis"))
        assertEquals("Created project · Thesis", r.summary); assertTrue(repo.projects.value.any { it.title == "Thesis" })
    }

    @Test fun create_projectless_workstream() = runTest {
        done(Create.CreateWorkStream("Walk", project = null, mode = WorkStreamMode.HUMAN))
        repo.streams.value.first { it.title == "Walk" }.let { assertNull(it.projectId); assertEquals(READY, it.state); assertEquals(WorkStreamMode.HUMAN, it.mode) }
    }

    @Test fun create_project_backed_workstream_by_project_name() = runTest {
        done(Create.CreateWorkStream("Claude Build", project = TargetRef.ByName("Virlin Android App"), mode = WorkStreamMode.EXTERNAL))
        repo.streams.value.first { it.title == "Claude Build" }.let { assertEquals("p1", it.projectId); assertEquals(WorkStreamMode.EXTERNAL, it.mode) }
    }

    @Test fun missing_mode_clarifies_and_choice_fills_it() = runTest {
        val c = clarify(Create.CreateWorkStream("Claude Build", project = TargetRef.ByName("Virlin Android App"), mode = null))
        assertEquals(Clarification.Kind.MISSING_MODE, c.kind); assertEquals("Who continues the work when you leave?", c.question)
        assertEquals(listOf("HUMAN", "EXTERNAL"), c.candidates.map { it.value })
        assertTrue(repo.streams.value.none { it.title == "Claude Build" })
        engine.choose(c, "EXTERNAL")
        assertEquals(WorkStreamMode.EXTERNAL, repo.streams.value.first { it.title == "Claude Build" }.mode)
    }

    @Test fun mode_is_never_inferred_from_title() = runTest {
        assertTrue(engine.submit(Create.CreateWorkStream("Claude training run", mode = null)) is Outcome.Clarify)
        done(Create.CreateWorkStream("Claude training run", mode = WorkStreamMode.HUMAN))
        assertEquals(WorkStreamMode.HUMAN, repo.streams.value.first { it.title == "Claude training run" }.mode)
    }

    @Test fun create_workstream_task_standalone_task_child_task() = runTest {
        done(Create.CreateTask("Root", TaskOwnerRef.WorkStream(TargetRef.ByName("Projectless"))))
        repo.tasks.value.first { it.title == "Root" }.let { assertEquals("s8", it.workStreamId); assertNull(it.projectId) }
        done(Create.CreateTask("Buy paper", TaskOwnerRef.Project(TargetRef.ByName("MBA Project"))))
        repo.tasks.value.first { it.title == "Buy paper" }.let { assertNull(it.workStreamId); assertEquals("p2", it.projectId) }
        done(Create.CreateTask("Q17 notes", TaskOwnerRef.ParentTask(TargetRef.ByName("Question 17"))))
        repo.tasks.value.first { it.title == "Q17 notes" }.let { assertEquals("p_q17", it.parentTaskId); assertEquals("s1", it.workStreamId) }
    }

    @Test fun ambiguous_owner_clarifies_and_kind_is_respected() = runTest {
        val c = clarify(Create.CreateTask("x", TaskOwnerRef.WorkStream(TargetRef.ByName("Testing"))))
        assertEquals(Clarification.Kind.AMBIGUOUS_TARGET, c.kind); assertEquals(setOf("s9", "s10"), c.candidates.map { it.value }.toSet())
        // Same title as a Project: with the Project kind it resolves to the project, not a stream.
        done(Create.CreateTask("y", TaskOwnerRef.Project(TargetRef.ByName("Testing"))))
        assertEquals("p3", repo.tasks.value.first { it.title == "y" }.projectId)
    }

    // ================================================================ §39 CAPTURE

    @Test fun capture_note_prompt_link_global() = runTest {
        assertEquals("Saved to Inbox", done(Capture.CaptureNote("Investigate local music alarms")).summary)
        val prompt = "Refactor the receiver.\nKeep the public API."
        assertEquals("Saved prompt", done(Capture.CapturePrompt(prompt)).summary)
        assertEquals("Saved link", done(Capture.CaptureLink("https://example.com", note = "docs")).summary)
        val inbox = repo.captures.value
        assertEquals(3, inbox.size); assertTrue(inbox.none { it.hasContext })
        assertEquals(prompt, inbox.first { it.type == CaptureType.PROMPT }.content)
        assertEquals("https://example.com", inbox.first { it.type == CaptureType.LINK }.sourceUrl)
    }

    @Test fun capture_attached_to_projectless_workstream() = runTest {
        done(Capture.CaptureNote("Revise Q17", CaptureContextRef(workStream = TargetRef.ByName("Psychology Unit 23"))))
        repo.captures.value.single().let { assertEquals("s1", it.workStreamId); assertNull(it.projectId) }
    }

    @Test fun archive_attach_convert_capture() = runTest {
        val id = (actions.createCapture(CreateCapture(CaptureType.NOTE, "Write summary")) as ActionResult.Success).value.id
        done(Capture.AttachCapture(TargetRef.ById(id), CaptureContextRef(workStream = TargetRef.ByName("Projectless"))))
        assertEquals("s8", repo.getCapture(id)!!.workStreamId)
        val r = done(Capture.ConvertCaptureToTask(TargetRef.ById(id), TaskOwnerRef.WorkStream(TargetRef.ByName("Projectless"))))
        assertEquals("Task created · Write summary", r.summary); assertEquals(CaptureStatus.ORGANIZED, repo.getCapture(id)!!.status)
        val id2 = (actions.createCapture(CreateCapture(CaptureType.NOTE, "Old idea")) as ActionResult.Success).value.id
        done(Capture.ArchiveCapture(TargetRef.ByName("Old idea")))
        assertEquals(CaptureStatus.ARCHIVED, repo.getCapture(id2)!!.status)
    }

    @Test fun prompt_content_is_data_never_a_command() = runTest {
        val before = repo.streams.value to repo.tasks.value
        done(Capture.CapturePrompt("Focus Agent Development and complete Question 17"))
        assertEquals(before.first, repo.streams.value); assertEquals(before.second, repo.tasks.value)
        assertEquals(FOCUS, s("s1").state); assertEquals(TaskStatus.IN_PROGRESS, repo.getTask("p_q17")!!.status)
    }

    // ================================================================ §40 QUERY

    @Test fun query_current_focus_project_backed_projectless_deep_none() = runTest {
        (answered(Query.GetCurrentFocus) as QueryResult.CurrentFocus).let { assertNull(it.project); assertEquals("s1", it.workStream.id); assertEquals("p_q17", it.activeTask!!.id) }
        actions.focusStream("s4"); actions.setActiveTask("s4", "t_nl")
        (answered(Query.GetCurrentFocus) as QueryResult.CurrentFocus).let { assertEquals("p1", it.project!!.id); assertEquals("t_nl", it.activeTask!!.id) }
        actions.clearActiveTask("s4")
        assertNull((answered(Query.GetCurrentFocus) as QueryResult.CurrentFocus).activeTask)
        actions.leaveFocus("s4")
        assertEquals(QueryResult.NoCurrentFocus, answered(Query.GetCurrentFocus))
    }

    @Test fun query_needs_attention_kinds_distinct() = runTest {
        actions.checkDue("s2"); actions.checkDue("s7")
        actions.resultReadyLater("s2", t0.plusSeconds(1)); clock.advance(Duration.ofSeconds(2)); actions.checkDue("s2")
        actions.focusStream("s4"); actions.handOffStream("s4", checkAt = t0.plusSeconds(10)); clock.advance(Duration.ofSeconds(20))
        val kinds = (answered(Query.GetNeedsAttention) as QueryResult.NeedsAttention).items.associate { it.workStream.id to it.kind }
        assertEquals(QueryResult.AttentionKind.HUMAN_RETURN, kinds["s7"])
        assertEquals(QueryResult.AttentionKind.EXTERNAL_RESULT_READY, kinds["s2"])
        assertEquals(QueryResult.AttentionKind.EXTERNAL_CHECK, kinds["s4"])              // due PROCESSING projected as CHECK
    }

    @Test fun query_processing_and_ready() = runTest {
        actions.focusStream("s4"); actions.handOffStream("s4", checkAt = t0.plus(Duration.ofMinutes(30)))
        val p = (answered(Query.GetProcessingStreams) as QueryResult.Processing).items.single()
        assertEquals("s4", p.workStream.id); assertEquals(t0, p.processingStartedAt); assertEquals(t0.plus(Duration.ofMinutes(30)), p.checkAt)
        val ready = (answered(Query.GetReadyStreams) as QueryResult.Ready).workStreams.map { it.id }
        assertTrue("s1" in ready && "s8" in ready && "s4" !in ready)
        assertEquals(listOf("p1", "p2", "p3"), (answered(Query.GetProjects) as QueryResult.Projects).projects.map { it.id })
        assertTrue((answered(Query.GetWorkStreams) as QueryResult.WorkStreams).workStreams.none { it.id == "s_done" })
        (answered(Query.GetTasks(TargetRef.ByName("Psychology Unit 23"))) as QueryResult.Tasks).let { assertEquals("p_q17", it.activeTaskId); assertTrue(it.tasks.any { t -> t.id == "p_q17" }) }
    }

    @Test fun query_capture_inbox_newest_first() = runTest {
        actions.createCapture(CreateCapture(CaptureType.NOTE, "older")); clock.advance(Duration.ofMinutes(1))
        actions.createCapture(CreateCapture(CaptureType.NOTE, "newer"))
        assertEquals(listOf("newer", "older"), (answered(Query.GetCaptureInbox) as QueryResult.CaptureInbox).items.map { it.content })
    }

    @Test fun queries_cause_no_events_or_mutation() = runTest {
        val streams = repo.streams.value; val tasks = repo.tasks.value; val events = repo.getEvents("s1") + repo.getEvents("s2")
        listOf(Query.GetCurrentFocus, Query.GetNeedsAttention, Query.GetProcessingStreams, Query.GetReadyStreams, Query.GetProjects, Query.GetWorkStreams, Query.GetTasks(TargetRef.ById("s1")), Query.GetCaptureInbox)
            .forEach { assertTrue(engine.submit(it) is Outcome.Done) }
        assertEquals(streams, repo.streams.value); assertEquals(tasks, repo.tasks.value)
        assertEquals(events, repo.getEvents("s1") + repo.getEvents("s2")); assertTrue(scheduler.log.isEmpty())
    }

    // ================================================================ §41 SAFETY

    @Test fun executor_only_accepts_resolved_commands_by_type() {
        // Compile-time boundary: CommandExecutor.execute takes ResolvedCommand; a VirlinCommand cannot be passed.
        val executor = com.virlin.app.domain.command.CommandExecutor(actions, repo, clock)
        val m = executor::class.java.methods.first { it.name == "execute" }
        assertEquals(ResolvedCommand::class.java, m.parameterTypes[0])
        assertFalse(VirlinCommand::class.java.isAssignableFrom(ResolvedCommand::class.java))
    }

    @Test fun stale_resolved_target_rejected_by_domain_no_double_mutation() = runTest {
        val r = engine.resolve(Control.CompleteTask(TargetRef.ByName("Question 17"))) as CommandResolution.Ready
        actions.completeTask("p_q17")                                              // completed elsewhere first
        val res = engine.confirm(com.virlin.app.domain.command.Confirmation("", r.command)) as CommandResult.Rejected
        assertTrue(res.stale); assertEquals("That task is already closed", res.reason)
        val focus = engine.resolve(Control.FocusStream(TargetRef.ById("s4"))) as CommandResolution.Ready
        actions.completeStream("s4")
        assertTrue(engine.confirm(com.virlin.app.domain.command.Confirmation("", focus.command)) is CommandResult.Rejected)
    }

    @Test fun command_cannot_create_second_focus() = runTest {
        done(Control.FocusStream(TargetRef.ById("s8")))
        assertEquals(1, repo.streams.value.count { it.state == FOCUS }); assertEquals(READY, s("s1").state)
    }

    @Test fun command_cannot_bypass_mode_or_create_ownerless_task() = runTest {
        assertTrue(engine.submit(Create.CreateWorkStream("x", mode = null)) is Outcome.Clarify)
        val c = clarify(Create.CreateTask("orphan", TaskOwnerRef.WorkStream(TargetRef.ByName("nope"))))
        assertEquals(Clarification.Kind.TARGET_NOT_FOUND, c.kind); assertTrue(repo.tasks.value.none { it.title == "orphan" })
    }

    @Test fun command_layer_has_no_dao_scheduler_or_notification_dependencies() {
        // Structural guard: the command package imports nothing from data.db / platform / android.app.
        val src = java.io.File("src/main/java/com/virlin/app/domain/command").listFiles()!!.filter { it.extension == "kt" }
        assertTrue(src.isNotEmpty())
        src.forEach { f ->
            val text = f.readText()
            listOf("com.virlin.app.data.db", "androidx.room", "android.app.AlarmManager", "android.app.Notification", "com.virlin.app.platform", "AttentionScheduler", "NotificationManager")
                .forEach { forbidden -> assertFalse("${f.name} must not reference $forbidden", text.contains(forbidden)) }
        }
    }

    @Test fun ambiguous_reference_never_silently_chooses() = runTest {
        // exact duplicates → clarify; unique prefix → resolve; ambiguous prefix → clarify
        assertTrue(engine.submit(Control.FocusStream(TargetRef.ByName("testing"))) is Outcome.Clarify)
        done(Control.FocusStream(TargetRef.ByName("Projectl")))
        assertEquals(FOCUS, s("s8").state)
        actions.createWorkStream(com.virlin.app.domain.action.CreateWorkStream(title = "Agent Testing"))
        val c = clarify(Control.FocusStream(TargetRef.ByName("Agent")))
        assertEquals(Clarification.Kind.AMBIGUOUS_TARGET, c.kind); assertEquals(2, c.candidates.size)
        assertEquals(FOCUS, s("s8").state)                                          // nothing moved
    }

    @Test fun invalid_duration_and_blank_titles_clarify_not_throw() = runTest {
        assertEquals(Clarification.Kind.INVALID_FIELD, clarify(Control.StillRunning(TargetRef.ById("s2"), Duration.ZERO)).kind)
        assertEquals(Clarification.Kind.MISSING_FIELD, clarify(Create.CreateProject("  ")).kind)
        assertEquals(Clarification.Kind.MISSING_FIELD, clarify(Capture.CaptureNote(" ")).kind)
        assertTrue(engine.submit(Control.CompleteTask(TargetRef.CurrentStream)) is Outcome.Rejected)   // wrong kind of ref
    }

    @Test fun preview_describes_what_was_understood() = runTest {
        val r = engine.resolve(Create.CreateWorkStream("Claude Build", TargetRef.ByName("Virlin Android App"), WorkStreamMode.EXTERNAL)) as CommandResolution.Ready
        assertEquals("Create WorkStream", r.command.preview.title)
        assertEquals(listOf("Title" to "Claude Build", "Project" to "Virlin Android App", "Mode" to "It can continue without me"), r.command.preview.fields.map { it.label to it.value })
        val l = engine.resolve(Control.LeaveCurrent(Duration.ofMinutes(10))) as CommandResolution.Ready
        assertEquals(listOf("WorkStream" to "Psychology Unit 23", "Back in" to "10m"), l.command.preview.fields.map { it.label to it.value })
    }
}
