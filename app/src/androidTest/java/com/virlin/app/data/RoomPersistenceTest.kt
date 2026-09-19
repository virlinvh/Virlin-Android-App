package com.virlin.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.data.db.RoomWorkStreamRepository
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.domain.DemoSeed
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateProject
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.action.CaptureTaskTarget
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.Priority
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProjectStatus
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.effectiveAttentionState
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.ui.screens.AttentionKind
import com.virlin.app.ui.screens.NowPresentation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Durable persistence proof against a REAL on-disk Room database (not in-memory), so
 * "reload" means: close the database, drop every object, open a fresh database + repository
 * over the same file, read back. Nothing survives except the rows. Deterministic clock.
 */
@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {

    class TestClock(var now: Instant) : VirlinClock { override fun now() = now; fun advance(d: Duration) { now = now.plus(d) } }
    class Ids : IdProvider { private var n = 0; override fun newId(prefix: String) = "$prefix-${++n}" }

    private val t0: Instant = Instant.parse("2026-09-11T18:00:00Z")
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "virlin-persistence-test.db"
    private lateinit var db: VirlinDatabase
    private lateinit var repo: RoomWorkStreamRepository
    private lateinit var clock: TestClock
    private lateinit var actions: DefaultVirlinActions

    private fun open(): RoomWorkStreamRepository = runBlocking {
        db = androidx.room.Room.databaseBuilder(ctx, VirlinDatabase::class.java, dbName).build()
        repo = RoomWorkStreamRepository.create(db)
        actions = DefaultVirlinActions(repo, clock, Ids())
        repo
    }

    /** PHASE B: destroy every in-memory object and reconstruct purely from the file. */
    private fun reload(): RoomWorkStreamRepository { db.close(); return open() }

    @Before fun setUp() { ctx.deleteDatabase(dbName); clock = TestClock(t0); open() }
    @After fun tearDown() { db.close(); ctx.deleteDatabase(dbName) }

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, projectId = project, executionPreference = mode.toPreference(), state = state, activeTaskId = active,
            nextHumanAction = "next-$id", createdAt = t0, updatedAt = t0)
    private fun task(id: String, title: String, ws: String?, project: String?, parent: String?, status: TaskStatus = TaskStatus.TODO, order: Int = 0) =
        Task(id, title, projectId = project, workStreamId = ws, parentTaskId = parent, status = status, order = order,
            estimatedEffort = Duration.ofMinutes(15), createdAt = t0, updatedAt = t0, completedAt = if (status == TaskStatus.DONE) t0 else null)

    /** The two accepted fixtures: project-backed Agent Development and projectless Psychology. */
    private suspend fun seedFixtures() = repo.transaction {
        saveProject(Project("p1", "Virlin Android App", description = "d", status = ProjectStatus.ACTIVE, priority = Priority.HIGH,
            dueAt = t0.plusSeconds(86400), estimatedEffort = Duration.ofHours(40), createdAt = t0, updatedAt = t0))
        saveStream(ws("s4", "Agent Development", WorkStreamState.PROCESSING, "p1", WorkStreamMode.EXTERNAL, "t_daypart")
            .copy(processingStartedAt = t0.minusSeconds(600), checkAt = t0.plusSeconds(1800), tool = "Antigravity"))
        saveStream(ws("s1", "Psychology Unit 23", WorkStreamState.FOCUS, null, active = "p_q17"))
        listOf(
            task("t_create", "Create Mode", "s4", "p1", null),
            task("t_rem", "Reminder Creation", "s4", "p1", "t_create"),
            task("t_nl", "Natural Language", "s4", "p1", "t_rem", TaskStatus.IN_PROGRESS),
            task("t_daypart", "Daypart Parsing", "s4", "p1", "t_nl", TaskStatus.IN_PROGRESS),
            task("t_done", "Control Mode", "s4", "p1", null, TaskStatus.DONE),
            task("t_cancel", "Old idea", "s4", "p1", null, TaskStatus.CANCELLED),
            task("t_apk", "Send APK", null, "p1", null, TaskStatus.DONE),
            task("p_answer", "Answer Questions", "s1", null, null),
            task("p_q2", "Questions 11–20", "s1", null, "p_answer"),
            task("p_q17", "Question 17", "s1", null, "p_q2", TaskStatus.IN_PROGRESS),
            task("p_q11", "Question 11", "s1", null, "p_q2", TaskStatus.DONE)
        ).forEach { saveTask(it) }
    }

    // ================================================================ 1–9, 29–30: structure round trips

    @Test fun structure_roundTrip_projectBacked_projectless_deep_cancelled_progress() = runBlocking {
        seedFixtures()
        val progressBefore = mapOf("s4" to ProgressCalculator.ofWorkStream(repo.tasks.value, "s4"), "s1" to ProgressCalculator.ofWorkStream(repo.tasks.value, "s1"),
            "p1" to ProgressCalculator.ofProject(repo.tasks.value, repo.streams.value, "p1"))
        val r = reload()
        // Project: every field
        val p = r.getProject("p1")!!
        assertEquals("Virlin Android App", p.title); assertEquals("d", p.description); assertEquals(Priority.HIGH, p.priority)
        assertEquals(Duration.ofHours(40), p.estimatedEffort); assertEquals(t0.plusSeconds(86400), p.dueAt); assertEquals(ProjectStatus.ACTIVE, p.status)
        // Project-backed stream + deep active task
        val s4 = r.getStream("s4")!!
        assertEquals("p1", s4.projectId); assertEquals(ExecutionPreference.EXTERNAL, s4.executionPreference); assertEquals("Antigravity", s4.tool)
        assertEquals("t_daypart", s4.activeTaskId)
        assertEquals(listOf("t_daypart", "t_nl", "t_rem", "t_create"), r.getAncestry("t_daypart")!!.map { it.id })   // derived, not stored
        assertEquals(t0.minusSeconds(600), s4.processingStartedAt); assertEquals(t0.plusSeconds(1800), s4.checkAt)
        // Projectless stream keeps null project, hierarchy intact
        val s1 = r.getStream("s1")!!
        assertNull(s1.projectId); assertEquals("p_q17", s1.activeTaskId)
        assertEquals(listOf("p_q17", "p_q2", "p_answer"), r.getAncestry("p_q17")!!.map { it.id })
        assertNull(r.getTask("p_q17")!!.projectId); assertEquals("p_q2", r.getTask("p_q17")!!.parentTaskId)
        // Statuses
        assertEquals(TaskStatus.DONE, r.getTask("t_done")!!.status); assertTrue(r.getTask("t_done")!!.status.isCompleted)
        assertEquals(TaskStatus.CANCELLED, r.getTask("t_cancel")!!.status)
        assertTrue(r.getTask("t_cancel")!!.status.isTerminal); assertFalse(r.getTask("t_cancel")!!.status.isCompleted)
        assertEquals(TaskStatus.TODO, r.getTask("p_answer")!!.status)
        assertEquals(Duration.ofMinutes(15), r.getTask("t_nl")!!.estimatedEffort)
        // Standalone project task
        assertNull(r.getTask("t_apk")!!.workStreamId); assertEquals("p1", r.getTask("t_apk")!!.projectId)
        // Progress identical (cancelled leaf still excluded from the denominator)
        assertEquals(progressBefore["s4"], ProgressCalculator.ofWorkStream(r.tasks.value, "s4"))
        assertEquals(progressBefore["s1"], ProgressCalculator.ofWorkStream(r.tasks.value, "s1"))
        assertEquals(progressBefore["p1"], ProgressCalculator.ofProject(r.tasks.value, r.streams.value, "p1"))
        val s4p = ProgressCalculator.ofWorkStream(r.tasks.value, "s4") as ProgressResult.Structured
        assertEquals(1, s4p.cancelledLeaves)
        // StateFlows are populated on construction
        assertEquals(2, r.streams.value.size); assertEquals(11, r.tasks.value.size); assertEquals(1, r.projects.value.size)
    }

    // ================================================================ 10–21: attention state, sessions, history

    @Test fun attention_roundTrip_sessions_cycles_snapshots_events_noDuplicateSession() = runBlocking {
        seedFixtures()
        // Real actions produce the rows: focus s1 (open session on q17), hand off s4 history
        actions.leaveFocus("s1"); actions.focusStream("s1")
        val openBefore = repo.getOpenFocusSession("s1")!!
        assertEquals("p_q17", openBefore.taskId)
        clock.advance(Duration.ofMinutes(31)); actions.checkDue("s4")
        actions.resultReadyLater("s4", clock.now().plus(Duration.ofMinutes(10)))
        actions.addNote("s4", "parser handles dayparts")
        val eventsBefore = repo.getEvents("s4").map { it.type }
        val snapBefore = repo.getLatestSnapshot("s4")!!
        val cyclesBefore = repo.getCycles("s1")

        val r = reload()
        // Focus survives with the SAME open session — nothing was inserted on reload
        val s1 = r.getStream("s1")!!
        assertEquals(WorkStreamState.FOCUS, s1.state); assertEquals("p_q17", s1.activeTaskId)
        val open = r.getOpenFocusSession("s1")!!
        assertEquals(openBefore, open); assertNull(open.endedAt); assertEquals("p_q17", open.taskId)
        assertEquals(1, r.getFocusSessions("s1").count { it.isOpen })
        assertEquals(Duration.ofMinutes(31), open.duration(clock.now()))                 // derived from timestamp
        assertEquals(cyclesBefore, r.getCycles("s1"))
        // Result-ready snooze survives with its reason; processing fields stay cleared
        val s4 = r.getStream("s4")!!
        assertEquals(WorkStreamState.SNOOZED, s4.state); assertEquals(SnoozeReason.EXTERNAL_RESULT_READY, s4.snoozeReason)
        assertNull(s4.processingStartedAt); assertEquals(clock.now().plus(Duration.ofMinutes(10)), s4.snoozedUntil)
        assertEquals("t_daypart", s4.activeTaskId)
        // Snapshot + history
        assertEquals(snapBefore, r.getLatestSnapshot("s4")); assertEquals("t_daypart", snapBefore.taskId)
        assertEquals(eventsBefore, r.getEvents("s4").map { it.type })
        assertTrue(EventType.RESULT_READY in eventsBefore && EventType.NOTE_ADDED in eventsBefore && EventType.CHECK_DUE in eventsBefore)
    }

    @Test fun humanReturn_roundTrip() = runBlocking {
        seedFixtures()
        actions.leaveFocus("s1"); actions.focusStream("s1")
        val at = clock.now().plus(Duration.ofMinutes(5))
        actions.leaveFocus("s1", at)
        val r = reload()
        val s1 = r.getStream("s1")!!
        assertEquals(WorkStreamState.SNOOZED, s1.state); assertEquals(SnoozeReason.HUMAN_RETURN, s1.snoozeReason)
        assertEquals(at, s1.snoozedUntil); assertEquals(at, s1.checkAt); assertEquals("p_q17", s1.activeTaskId)
        assertEquals("p_q17", r.getLatestSnapshot("s1")!!.taskId)
        assertNull(r.getOpenFocusSession("s1"))
    }

    // ================================================================ 22–24: invariants + atomicity with Room

    @Test fun singleFocus_atomic_andMultipleProcessing() = runBlocking {
        seedFixtures()
        repo.transaction { saveStream(ws("s7", "Notion", WorkStreamState.READY, null)); saveStream(ws("s8", "Codex", WorkStreamState.PROCESSING, null, WorkStreamMode.EXTERNAL)) }
        actions.focusStream("s7")                                                    // displaces s1 in ONE transaction
        val r = reload()
        assertEquals(1, r.streams.value.count { it.state == WorkStreamState.FOCUS })
        assertEquals("s7", r.getActiveFocus()!!.id); assertEquals(WorkStreamState.READY, r.getStream("s1")!!.state)
        assertNull(r.getOpenFocusSession("s1")); assertNotNull(r.getOpenFocusSession("s7"))
        assertEquals(2, r.streams.value.count { it.state == WorkStreamState.PROCESSING })
    }

    @Test fun failedTransaction_leavesNoPartialWrites() = runBlocking {
        seedFixtures()
        val before = repo.streams.value
        try {
            repo.transaction {
                saveStream(getStream("s1")!!.copy(title = "half-written"))
                saveTask(task("t_x", "ghost", "s1", null, null))
                appendEvent(com.virlin.app.domain.model.WorkStreamEvent("ev-x", "s1", EventType.NOTE_ADDED, t0))
                error("boom")
            }
            fail("expected exception")
        } catch (e: IllegalStateException) { assertEquals("boom", e.message) }
        assertEquals(before, repo.streams.value)
        val r = reload()
        assertEquals("Psychology Unit 23", r.getStream("s1")!!.title); assertNull(r.getTask("t_x")); assertTrue(r.getEvents("s1").isEmpty())
    }

    // ================================================================ 25: idempotent demo seed

    @Test fun demoSeed_appliesOnce() = runBlocking {
        assertTrue(DemoSeed.applyIfEmpty(db, t0))
        val n = db.workStreams().count(); val tasks = db.tasks().all().size
        assertTrue(n > 0 && tasks > 0)
        assertFalse(DemoSeed.applyIfEmpty(db, t0.plusSeconds(60)))
        val r = reload()
        assertFalse(DemoSeed.applyIfEmpty(db, t0.plusSeconds(120)))
        assertEquals(n, db.workStreams().count()); assertEquals(tasks, db.tasks().all().size)
        assertEquals("t_nl", r.getStream("s4")!!.activeTaskId); assertNull(r.getStream("s8")!!.projectId)
    }

    // ================================================================ 26–28: reconciliation after downtime

    @Test fun overdueStates_recognizedAfterReload_withoutTicker() = runBlocking {
        seedFixtures()
        actions.leaveFocus("s1", clock.now().plus(Duration.ofMinutes(10)))          // human return at +10
        repo.transaction {
            saveStream(ws("s9", "Codex", WorkStreamState.SNOOZED, null, WorkStreamMode.EXTERNAL, "t_x")
                .copy(snoozeReason = SnoozeReason.EXTERNAL_RESULT_READY, snoozedUntil = t0.plusSeconds(600), checkAt = t0.plusSeconds(600)))
        }
        // s4: PROCESSING with checkAt +30. Before any time passes nothing is due.
        assertTrue(repo.dueBy(clock.now()).isEmpty())
        assertEquals(WorkStreamState.PROCESSING, effectiveAttentionState(repo.getStream("s4")!!, clock.now()))

        // "Process dies", the user comes back 45 minutes later.
        clock.advance(Duration.ofMinutes(45))
        val r = reload()
        val due = r.dueBy(clock.now()).map { it.id }.toSet()
        assertEquals(setOf("s1", "s4", "s9"), due)
        assertEquals(WorkStreamState.CHECK, effectiveAttentionState(r.getStream("s4")!!, clock.now()))   // pure projection
        // Reconcile through the validated action, exactly as VirlinGraph.start() does
        due.forEach { assertTrue(actions.checkDue(it) is ActionResult.Success) }
        val kinds = NowPresentation.attention(r.streams.value)
        assertEquals(AttentionKind.RETURN_DUE, kinds["s1"])          // "Ready to continue"
        assertEquals(AttentionKind.CHECK_DUE, kinds["s4"])           // "Check due"
        assertEquals(AttentionKind.RESULT_READY, kinds["s9"])        // "Result ready"
        assertNull(r.getActiveFocus())                               // nothing stole Focus
        assertEquals("p_q17", r.getStream("s1")!!.activeTaskId); assertEquals("t_daypart", r.getStream("s4")!!.activeTaskId)
        // And it stays that way across yet another reload
        val r2 = reload()
        assertEquals(3, r2.streams.value.count { it.state == WorkStreamState.CHECK })
        assertEquals(SnoozeReason.HUMAN_RETURN, r2.getStream("s1")!!.snoozeReason)
    }

    @Test fun beforeDue_nothingChanges_afterReload() = runBlocking {
        seedFixtures()
        actions.leaveFocus("s1", clock.now().plus(Duration.ofMinutes(10)))
        clock.advance(Duration.ofMinutes(3))
        val r = reload()
        assertTrue(r.dueBy(clock.now()).isEmpty())
        assertEquals(WorkStreamState.SNOOZED, r.getStream("s1")!!.state)
        assertEquals(WorkStreamState.PROCESSING, effectiveAttentionState(r.getStream("s4")!!, clock.now()))
    }

    // ================================================================ 49: same contract, both repositories

    @Test fun contract_sameObservableBehaviour_inMemory_vs_room() = runBlocking {
        val inMemory = com.virlin.app.domain.repository.InMemoryWorkStreamRepository()
        val results = listOf<WorkStreamRepository>(inMemory, repo).map { r -> runScenario(r) }
        assertEquals(results[0], results[1])
        // and the Room result is what a fresh process reads back
        val reloaded = reload()
        assertEquals(results[1], snapshot(reloaded))
    }

    private suspend fun runScenario(r: WorkStreamRepository): List<String> {
        val a = DefaultVirlinActions(r, TestClock(t0), Ids())
        val p = (a.createProject(CreateProject(title = "P")) as ActionResult.Success).value
        r.transaction {
            saveStream(ws("h", "Human", WorkStreamState.READY, null))
            saveStream(ws("e", "External", WorkStreamState.READY, p.id, WorkStreamMode.EXTERNAL))
        }
        val root = (a.createTask(CreateTask(title = "Root", workStreamId = "h")) as ActionResult.Success).value
        val sub = (a.addSubtask(root.id, "Sub", Duration.ofMinutes(5)) as ActionResult.Success).value
        a.setActiveTask("h", sub.id)
        a.focusStream("h"); a.leaveFocus("h", t0.plusSeconds(300))
        a.focusStream("e"); a.handOffStream("e", checkAt = t0.plusSeconds(60))
        val other = (a.createTask(CreateTask(title = "Cancel me", workStreamId = "h")) as ActionResult.Success).value
        a.cancelTask(other.id)
        a.completeTask(sub.id)
        return snapshot(r)
    }

    private suspend fun snapshot(r: WorkStreamRepository): List<String> = buildList {
        r.streams.value.sortedBy { it.id }.forEach { add("${it.id}:${it.state}:${it.snoozeReason}:${it.activeTaskId}:${it.projectId}:${it.executionPreference}:${it.checkAt}:${it.snoozedUntil}") }
        r.tasks.value.sortedBy { it.id }.forEach { add("${it.id}:${it.title}:${it.status}:${it.parentTaskId}:${it.workStreamId}:${it.projectId}") }
        r.projects.value.forEach { add("${it.id}:${it.title}") }
        add("h-events:" + r.getEvents("h").map { it.type })
        add("e-events:" + r.getEvents("e").map { it.type })
        add("h-snap:" + r.getLatestSnapshot("h")?.taskId)
        add("h-sessions:" + r.getFocusSessions("h").map { "${it.taskId}:${it.isOpen}" })
        add("e-cycle:" + r.getCurrentCycle("e")?.let { "${it.number}:${it.handedOffAt != null}" })
        add("h-progress:" + ProgressCalculator.ofWorkStream(r.tasks.value, "h"))
        add("h-path:" + TaskHierarchy.ancestry(r.tasks.value, r.getStream("h")!!.activeTaskId ?: "-")?.map { it.title })
    }

    // ================================================================ Pass 10: Capture round trips (schema v2)

    @Test fun capture_roundTrip_types_statuses_longText_url_context_ordering_archive_reload() = runBlocking {
        seedFixtures()
        val longPrompt = buildString { repeat(400) { append("Line $it of a very long prompt with unicode — ✓ and tabs\t\n") } }
        val a = (actions.createCapture(CreateCapture(CaptureType.PROMPT, content = longPrompt)) as ActionResult.Success).value
        clock.advance(Duration.ofMinutes(5))
        val b = (actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "https://example.com/a?b=c#d", content = "note", title = "Example",
            context = CaptureContext(workStreamId = "s1"))) as ActionResult.Success).value           // projectless WorkStream context
        clock.advance(Duration.ofMinutes(5))
        val c = (actions.createCapture(CreateCapture(CaptureType.NOTE, content = "global", context = CaptureContext(taskId = "t_nl"))) as ActionResult.Success).value
        actions.archiveCapture(a.id)

        // PHASE B: everything from the file only
        reload()
        val all = repo.captures.value
        assertEquals(listOf(c.id, b.id, a.id), all.map { it.id })                       // newest first by createdAt
        val ra = repo.getCapture(a.id)!!; val rb = repo.getCapture(b.id)!!; val rc = repo.getCapture(c.id)!!
        assertEquals(longPrompt.trim(), ra.content); assertEquals(CaptureType.PROMPT, ra.type)   // only outer whitespace trimmed
        assertEquals(CaptureStatus.ARCHIVED, ra.status); assertNotNull(ra.archivedAt)
        assertEquals("https://example.com/a?b=c#d", rb.sourceUrl); assertEquals("Example", rb.title); assertEquals(CaptureType.LINK, rb.type)
        assertEquals("s1", rb.workStreamId); assertNull(rb.projectId); assertNull(rb.taskId); assertEquals(CaptureStatus.INBOX, rb.status)
        assertEquals("t_nl", rc.taskId); assertEquals("s4", rc.workStreamId); assertEquals("p1", rc.projectId)
        assertEquals(t0.plus(Duration.ofMinutes(10)), rc.createdAt)
        // convert after reload → task + ORGANIZED persisted atomically
        val t = (actions.convertCaptureToTask(c.id, CaptureTaskTarget(workStreamId = "s1")) as ActionResult.Success).value
        reload()
        assertEquals(CaptureStatus.ORGANIZED, repo.getCapture(c.id)!!.status); assertEquals(t.id, repo.getCapture(c.id)!!.convertedTaskId)
        assertNotNull(repo.getTask(t.id))
        // and nothing else moved
        assertEquals(WorkStreamState.FOCUS, repo.getStream("s1")!!.state); assertEquals("p_q17", repo.getStream("s1")!!.activeTaskId)
    }
}
