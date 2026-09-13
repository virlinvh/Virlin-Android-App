package com.virlin.app.command

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CaptureTaskTarget
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.command.CommandEngine
import com.virlin.app.domain.command.CommandEngine.Outcome
import com.virlin.app.domain.command.CommandResult
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.VirlinCommand.Create
import com.virlin.app.domain.command.text.TextCommandInterpreter
import com.virlin.app.domain.command.text.TextInterpretation
import com.virlin.app.domain.command.time.TimeExpressionParser
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.FOCUS
import com.virlin.app.domain.model.WorkStreamState.PROCESSING
import com.virlin.app.domain.model.WorkStreamState.READY
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.ui.agent.capture.AgentCaptureViewModel
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
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Capture V1 — the boundary tests: CAPTURE mode saves raw text through the capture action
 * (never the interpreter), explicit capture prefixes terminate their payload as data, and a
 * capture has no attention / structure / scheduling side effect. Behaviour details of the
 * accepted capture model (types, context, archive, organize, convert) live in `AgentCaptureTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureBoundaryTest {

    private val t0: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private val zone = java.time.ZoneId.of("Asia/Kolkata")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeAttentionScheduler
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var engine: CommandEngine
    private lateinit var interpreter: TextCommandInterpreter

    private fun ws(id: String, title: String, state: WorkStreamState, mode: WorkStreamMode = WorkStreamMode.HUMAN, project: String? = null, active: String? = null, checkAt: Instant? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, checkAt = checkAt,
            processingStartedAt = if (state == PROCESSING) t0 else null, createdAt = t0, updatedAt = t0)
    private fun task(id: String, title: String, stream: String?, project: String? = null, parent: String? = null) =
        Task(id = id, title = title, projectId = project, workStreamId = stream, parentTaskId = parent, createdAt = t0, updatedAt = t0)

    /** Psychology (s1, FOCUS, task t1 active) · Claude Build (s2, EXTERNAL, PROCESSING, check in 2h) · Personal Notes (s3, projectless) · deep chain t4→t5→t6 under s2. */
    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeClock(t0); scheduler = FakeAttentionScheduler()
        val streams = listOf(ws("s1", "Psychology", FOCUS, project = "p1", active = "t1"), ws("s2", "Claude Build", PROCESSING, WorkStreamMode.EXTERNAL, "p2", checkAt = t0.plus(Duration.ofHours(2))), ws("s3", "Personal Notes", READY))
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(streams,
            listOf(Project("p1", "MSc Psychology", createdAt = t0, updatedAt = t0), Project("p2", "Virlin Android App", createdAt = t0, updatedAt = t0)),
            listOf(task("t1", "Notification Receiver", "s1", "p1"), task("t2", "Testing", "s1", "p1"), task("t4", "Agent Development", "s2", "p2"),
                task("t5", "Natural Language", "s2", "p2", "t4"), task("t6", "Parser", "s2", "p2", "t5"), task("t9", "Contact HR", null, "p1"))), scheduler)
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        engine = CommandEngine(actions, repo, clock, zone)
        interpreter = TextCommandInterpreter(TimeExpressionParser(clock, zone))
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun captures() = repo.captures.value
    private suspend fun s(id: String) = repo.getStream(id)!!
    private fun snapshot() = repo.streams.value.map { Triple(it.id, it.state, it.activeTaskId to it.checkAt) } to repo.tasks.value.map { it.id to it.status }

    /** CAPTURE mode: the composer text goes to the capture ViewModel's save — the interpreter is not on that path at all. */
    private fun vm() = AgentCaptureViewModel(actions, repo, clock)

    // ================================================================ 1–7, 41–47: CAPTURE mode saves raw text, no interpretation, no side effect
    @Test fun capture_mode_saves_command_looking_text_verbatim_with_zero_side_effects() = runTest(dispatcher) {
        val before = snapshot(); val schedules = scheduler.current.toMap()
        val vm = vm()
        val inputs = listOf("Hello world", "Leave Psychology for 10 minutes", "Create project Psychology", "Complete Notification Receiver",
            "Switch to Psychology", "Create task Testing", "Block Claude Build", "Hand off Claude Build and check in 5 minutes", "if (state == READY) focus()",
            "Need to fix Claude Build notification bug", "Remember to compare Whisper models")
        var cleared = 0
        inputs.forEach { text -> vm.save(text) { cleared++ }; advanceUntilIdle() }
        assertEquals(inputs.size, cleared)
        assertEquals(inputs.toSet(), captures().map { it.content }.toSet()); assertEquals(inputs.size, captures().size)
        captures().forEach { assertEquals(CaptureType.NOTE, it.type); assertEquals(CaptureStatus.INBOX, it.status); assertNull(it.projectId); assertNull(it.workStreamId); assertNull(it.taskId) }
        // nothing else moved: Focus, active task, processing, check time, task status, scheduler, projects, streams, tasks
        assertEquals(before, snapshot())
        assertEquals(FOCUS, s("s1").state); assertEquals("t1", s("s1").activeTaskId); assertEquals(PROCESSING, s("s2").state); assertEquals(t0.plus(Duration.ofHours(2)), s("s2").checkAt)
        assertEquals(schedules, scheduler.current.toMap()); assertTrue(scheduler.log.isEmpty())
        assertEquals(2, repo.projects.value.size); assertEquals(3, repo.streams.value.size); assertEquals(6, repo.tasks.value.size)
        assertNull(repo.getOpenFocusSession("s2")); assertEquals(0, repo.getEvents("s1").size + repo.getEvents("s2").size)
        // no auto-attach by title: "Claude Build" in the text did not become context
        assertNull(captures().first { it.content.contains("Claude Build notification") }.workStreamId)
        // empty / whitespace rejected, nothing stored
        val n = captures().size
        vm.save(""); advanceUntilIdle(); vm.save("   \n  "); advanceUntilIdle()
        assertEquals(n, captures().size); assertTrue(vm.form.value.error != null)
    }

    // ================================================================ 8–13: types
    @Test fun types_are_explicit_and_payloads_raw() = runTest(dispatcher) {
        val vm = vm()
        vm.setType(CaptureType.PROMPT); vm.save("Complete Psychology and then create project X"); advanceUntilIdle()
        captures().single().let { assertEquals(CaptureType.PROMPT, it.type); assertEquals("Complete Psychology and then create project X", it.content) }
        vm.setType(CaptureType.LINK); vm.save("https://developer.android.com/topic/libraries/architecture/room?x=1&y=2"); advanceUntilIdle()
        captures().first { it.type == CaptureType.LINK }.let { assertEquals("https://developer.android.com/topic/libraries/architecture/room?x=1&y=2", it.sourceUrl) }
        val n = captures().size
        vm.save("developer.android.com"); advanceUntilIdle(); vm.save("ftp://x.y/z"); advanceUntilIdle()
        assertEquals(n, captures().size)                                                                 // invalid links rejected
        vm.setType(CaptureType.NOTE); vm.save("See https://example.com later"); advanceUntilIdle()
        captures().first { it.content == "See https://example.com later" }.let { assertEquals(CaptureType.NOTE, it.type); assertNull(it.sourceUrl) }    // NOTE stays NOTE even with a URL inside
        assertEquals(FOCUS, s("s1").state); assertEquals(TaskStatus.TODO, repo.getTask("t2")!!.status)
    }

    // ================================================================ 14–22, 27–31: context validation (deep task, projectless, stale, mismatch) and organize
    @Test fun context_is_validated_never_inferred() = runTest(dispatcher) {
        suspend fun cap(ctx: CaptureContext) = actions.createCapture(CreateCapture(CaptureType.NOTE, "x", context = ctx))
        assertTrue(cap(CaptureContext.None) is ActionResult.Success)
        assertTrue(cap(CaptureContext(projectId = "p1")) is ActionResult.Success)
        assertTrue(cap(CaptureContext(workStreamId = "s3")) is ActionResult.Success)                       // projectless WorkStream
        (cap(CaptureContext(taskId = "t6")) as ActionResult.Success).value.let { assertEquals("s2", it.workStreamId); assertEquals("p2", it.projectId) }   // deep task derives ancestry
        assertTrue(cap(CaptureContext(taskId = "t404")) is ActionResult.Rejected)                         // stale task
        assertTrue(cap(CaptureContext(projectId = "p1", workStreamId = "s2")) is ActionResult.Rejected)   // s2 belongs to p2
        assertTrue(cap(CaptureContext(projectId = "p2", taskId = "t1")) is ActionResult.Rejected)         // t1 belongs to p1
        // organize later: content untouched; invalid context rejected
        val c = (cap(CaptureContext.None) as ActionResult.Success).value
        (actions.attachCapture(c.id, CaptureContext(taskId = "t6")) as ActionResult.Success).value.let { assertEquals("x", it.content); assertEquals("t6", it.taskId); assertEquals("s2", it.workStreamId) }
        (actions.attachCapture(c.id, CaptureContext(workStreamId = "s3")) as ActionResult.Success).value.let { assertEquals("s3", it.workStreamId); assertNull(it.projectId) }
        (actions.attachCapture(c.id, CaptureContext(projectId = "p1")) as ActionResult.Success).value.let { assertEquals("p1", it.projectId); assertNull(it.workStreamId) }
        assertTrue(actions.attachCapture(c.id, CaptureContext(projectId = "p1", workStreamId = "s2")) is ActionResult.Rejected)
        assertEquals("x", repo.captures.value.first { it.id == c.id }.content)
    }

    // ================================================================ 23–26, 32–40: inbox / archive / restore / convert
    @Test fun archive_restore_and_atomic_conversion() = runTest(dispatcher) {
        val c = (actions.createCapture(CreateCapture(CaptureType.NOTE, "Investigate notification duplicate\nsecond line")) as ActionResult.Success).value
        assertEquals(CaptureStatus.INBOX, c.status)
        assertEquals(CaptureStatus.ARCHIVED, (actions.archiveCapture(c.id) as ActionResult.Success).value.status)
        assertEquals(1, repo.captures.value.size)                                                        // archive never deletes
        assertEquals(CaptureStatus.INBOX, (actions.restoreCapture(c.id) as ActionResult.Success).value.status)
        // convert: exactly one task, convertedTaskId, ORGANIZED, second attempt rejected
        val t = (actions.convertCaptureToTask(c.id, CaptureTaskTarget(workStreamId = "s3")) as ActionResult.Success).value
        assertEquals("Investigate notification duplicate", t.title); assertEquals("s3", t.workStreamId); assertNull(t.projectId)   // projectless WorkStream conversion
        repo.captures.value.single().let { assertEquals(CaptureStatus.ORGANIZED, it.status); assertEquals(t.id, it.convertedTaskId); assertEquals("Investigate notification duplicate\nsecond line", it.content) }
        assertTrue(actions.convertCaptureToTask(c.id, CaptureTaskTarget(workStreamId = "s3")) is ActionResult.Rejected)
        assertEquals(7, repo.tasks.value.size)                                                            // 6 + exactly one
        // standalone Project task and child task targets
        val c2 = (actions.createCapture(CreateCapture(CaptureType.NOTE, "Ask HR"))) as ActionResult.Success
        (actions.convertCaptureToTask(c2.value.id, CaptureTaskTarget(projectId = "p1")) as ActionResult.Success).value.let { assertNull(it.workStreamId); assertEquals("p1", it.projectId) }
        val c3 = (actions.createCapture(CreateCapture(CaptureType.NOTE, "Handle DST"))) as ActionResult.Success
        (actions.convertCaptureToTask(c3.value.id, CaptureTaskTarget(parentTaskId = "t6")) as ActionResult.Success).value.let { assertEquals("t6", it.parentTaskId); assertEquals("s2", it.workStreamId) }
        // a failed conversion (bad owner) leaves the capture in the Inbox — atomic
        val c4 = (actions.createCapture(CreateCapture(CaptureType.NOTE, "Nowhere"))) as ActionResult.Success
        assertFalse(actions.convertCaptureToTask(c4.value.id, CaptureTaskTarget(workStreamId = "s404")) is ActionResult.Success)
        assertEquals(CaptureStatus.INBOX, repo.captures.value.first { it.id == c4.value.id }.status)
        assertEquals(FOCUS, s("s1").state); assertEquals(PROCESSING, s("s2").state); assertTrue(scheduler.log.isEmpty())
    }

    // ================================================================ 48–52 (+34): explicit capture prefixes terminate the payload as data
    @Test fun explicit_prefixes_keep_payload_raw_and_execute_nothing() = runTest(dispatcher) {
        val before = snapshot()
        val cases = mapOf(
            "Remember complete Psychology" to (CaptureType.NOTE to "complete Psychology"),
            "Remember leave Psychology for 10 minutes" to (CaptureType.NOTE to "leave Psychology for 10 minutes"),
            "Save note hand off Claude Build" to (CaptureType.NOTE to "hand off Claude Build"),
            "Save note create project Psychology" to (CaptureType.NOTE to "create project Psychology"),
            "Save note block Claude Build" to (CaptureType.NOTE to "block Claude Build"),
            "Save note check Claude Build in 5 minutes" to (CaptureType.NOTE to "check Claude Build in 5 minutes"),
            "Save prompt complete Notification Receiver" to (CaptureType.PROMPT to "complete Notification Receiver"),
            "Save prompt create project Psychology" to (CaptureType.PROMPT to "create project Psychology"),
            "Save prompt check Claude Build in 5 minutes" to (CaptureType.PROMPT to "check Claude Build in 5 minutes"),
            "Remember create task Testing" to (CaptureType.NOTE to "create task Testing")
        )
        cases.forEach { (text, expected) ->
            val cmd = (interpreter.interpret(text) as TextInterpretation.Parsed).command
            assertTrue(text, cmd is Capture)
            val o = engine.submit(cmd); assertTrue(text, o is Outcome.Done && (o as Outcome.Done).result is CommandResult.Executed)
            val saved = captures().single { it.content == expected.second && it.type == expected.first }; assertEquals(text, expected.second, saved.content)
        }
        val link = (interpreter.interpret("Save link https://example.com/reference?id=42&mode=test") as TextInterpretation.Parsed).command as Capture.CaptureLink
        assertEquals("https://example.com/reference?id=42&mode=test", link.url); engine.submit(link)
        assertEquals(1, captures().count { it.type == CaptureType.LINK && it.sourceUrl == link.url })
        assertEquals(before, snapshot()); assertTrue(scheduler.log.isEmpty()); assertEquals(2, repo.projects.value.size); assertEquals(6, repo.tasks.value.size)
        assertEquals(FOCUS, s("s1").state); assertEquals(PROCESSING, s("s2").state); assertEquals(t0.plus(Duration.ofHours(2)), s("s2").checkAt)
    }

    // ================================================================ 53–58: Control / Create stay exactly what they are outside CAPTURE mode
    @Test fun control_and_create_regressions_outside_capture_mode() {
        fun p(t: String) = (interpreter.interpret(t) as TextInterpretation.Parsed).command
        assertTrue(p("Switch to Psychology") is Control.FocusStream)
        assertTrue(p("Leave Psychology for 10 minutes") is Control.LeaveStream)
        assertTrue(p("Hand off Claude Build and check in 5 minutes") is Control.HandOffStream)
        assertTrue(p("Claude Build is still running, check again in 5 minutes") is Control.StillRunning)
        assertTrue(p("Result ready for Claude Build, focus now") is Control.ResultReadyNow)
        assertTrue(p("Block Claude Build") is Control.BlockStream)
        assertEquals(Create.CreateProject("Psychology"), p("Create project Psychology"))
        assertEquals(Create.CreateWorkStream("Claude Build", null, WorkStreamMode.EXTERNAL), p("Create external workstream Claude Build"))
        assertEquals(Create.CreateWorkStream("Testing", null, null), p("Create workstream Testing"))
        assertTrue(p("Create task Receiver under Claude Build") is Create.CreateTask)
        assertTrue(p("Create subtask Retry under Receiver") is Create.CreateTask)
    }

    // ================================================================ 44: architecture guard — the capture path has no interpreter / scheduler / notification / network dependency
    @Test fun capture_path_is_structurally_isolated() {
        val root = File("src/main/java/com/virlin/app")
        val files = listOf("domain/action/CaptureActions.kt", "ui/agent/capture/AgentCaptureViewModel.kt", "ui/agent/capture/AgentCaptureArea.kt").map { File(root, it) }.filter { it.exists() }
        assertTrue(files.size >= 2)
        val forbidden = listOf("TextCommandInterpreter", "CommandResolver", "CommandEngine", "AttentionScheduler", "AlarmManager", "NotificationManager", "PendingIntent",
            "java.net", "okhttp", "retrofit", "HttpURLConnection", "Gemini", "OpenAI", "Anthropic", "llama", "LiteRT", "androidx.room", "Dao")
        files.forEach { f ->
            val code = f.readLines().filterNot { val t = it.trim(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }.joinToString(" ")
            forbidden.forEach { w -> assertFalse("${f.name} must not reference $w", code.contains(w)) }
        }
        // CAPTURE-mode submit goes to the capture ViewModel, never to the command ViewModel
        val app = File(root, "ui/navigation/VirlinApp.kt").readText()
        assertTrue(app.contains("AgentMode.CAPTURE) captureViewModel.save(composerText)"))
    }
}
