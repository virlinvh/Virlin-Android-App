package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateProject
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.Field
import com.virlin.app.domain.action.ProjectUpdate
import com.virlin.app.domain.action.TaskUpdate
import com.virlin.app.domain.action.getOrNull
import com.virlin.app.domain.model.*
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Project / recursive Task / active task / progress engine. Deterministic. */
class StructureActionsTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")
    private lateinit var clock: FakeClock
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var a: DefaultVirlinActions

    private fun ws(id: String, state: WorkStreamState, project: String? = "app", pref: ExecutionPreference = ExecutionPreference.EXTERNAL) =
        WorkStream(id = id, title = id, state = state, projectId = project, executionPreference = pref, createdAt = t0, updatedAt = t0)

    @Before
    fun setUp() {
        clock = FakeClock(t0)
        repo = InMemoryWorkStreamRepository(
            seed = listOf(ws("agent", READY), ws("orb", PROCESSING), ws("done", DONE), ws("orphan", READY, project = null)),
            seedProjects = listOf(Project("app", "Virlin Android App", createdAt = t0, updatedAt = t0),
                                  Project("mba", "MBA", createdAt = t0, updatedAt = t0))
        )
        a = DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }

    private fun <T> ActionResult<T>.rej() = (this as ActionResult.Rejected).reason
    private suspend fun task(title: String, ws: String? = "agent", parent: String? = null, effort: Long? = null, id: String? = null, project: String? = null) =
        a.createTask(CreateTask(title, projectId = project, workStreamId = ws, parentTaskId = parent,
            estimatedEffort = effort?.let { Duration.ofMinutes(it) }, id = id)).getOrNull()!!
    private suspend fun tasks() = repo.tasks.value

    /** The brief's example tree under "agent": Create Mode → {Task Creation, WorkStream Creation, Reminder Creation → {Presets, Custom Time, Natural Language}} */
    private suspend fun seedTree(withEffort: Boolean = false) {
        val e: (Long) -> Long? = { if (withEffort) it else null }
        task("Create Mode", id = "create")
        task("Task Creation", parent = "create", effort = e(10), id = "tc")
        task("WorkStream Creation", parent = "create", effort = e(10), id = "wc")
        task("Reminder Creation", parent = "create", id = "rc")
        task("Presets", parent = "rc", effort = e(5), id = "presets")
        task("Custom Time", parent = "rc", effort = e(20), id = "custom")
        task("Natural Language", parent = "rc", effort = e(45), id = "nl")
    }

    // ================================================================ Project

    @Test fun project_create_update_complete() = runTest {
        val p = a.createProject(CreateProject("Release 1", estimatedEffort = Duration.ofHours(2))).getOrNull()!!
        assertEquals(ProjectStatus.ACTIVE, p.status); assertEquals("proj-1", p.id)
        val u = a.updateProject(p.id, ProjectUpdate(title = Field.Set("Release 1.0"), dueAt = Field.Set(t0.plusSeconds(3600)))).getOrNull()!!
        assertEquals("Release 1.0", u.title); assertEquals(Duration.ofHours(2), u.estimatedEffort) // Keep
        clock.advance(Duration.ofMinutes(1))
        val c = a.completeProject(p.id).getOrNull()!!
        assertEquals(ProjectStatus.DONE, c.status); assertEquals(clock.now(), c.completedAt)
        assertEquals(DomainError.ProjectAlreadyDone, a.completeProject(p.id).rej())
        assertEquals(DomainError.ProjectAlreadyDone, a.updateProject(p.id, ProjectUpdate()).rej())
    }

    @Test fun project_invalid() = runTest {
        assertEquals(DomainError.EmptyTitle, a.createProject(CreateProject("  ")).rej())
        assertEquals(DomainError.InvalidEffort, a.createProject(CreateProject("x", estimatedEffort = Duration.ZERO)).rej())
        assertTrue(a.updateProject("nope", ProjectUpdate()).rej() is DomainError.ProjectNotFound)
        assertTrue(a.completeProject("nope").rej() is DomainError.ProjectNotFound)
    }

    // ================================================================ Task creation & ownership

    @Test fun task_workStreamTask_inheritsProject() = runTest {
        val t = task("Design agent")
        assertEquals("app", t.projectId); assertEquals("agent", t.workStreamId); assertNull(t.parentTaskId)
        assertEquals(listOf(EventType.TASK_CREATED), repo.getEvents("agent").map { it.type })
    }

    @Test fun task_standaloneProjectTask() = runTest {
        val t = task("Prepare release notes", ws = null, project = "app")
        assertTrue(t.isStandalone); assertEquals("app", t.projectId); assertNull(t.workStreamId)
    }

    @Test fun task_ownershipRequired() = runTest {
        assertEquals(DomainError.OwnershipMismatch, a.createTask(CreateTask("no owner")).rej())
        assertTrue(a.createTask(CreateTask("x", projectId = "ghost")).rej() is DomainError.ProjectNotFound)
        assertTrue(a.createTask(CreateTask("x", workStreamId = "ghost")) is ActionResult.NotFound)
        assertNull(a.createTask(CreateTask("x", workStreamId = "orphan")).getOrNull()!!.projectId) // projectless stream is VALID
        assertEquals(DomainError.OwnershipMismatch, a.createTask(CreateTask("x", workStreamId = "orphan", projectId = "app")).rej()) // must not disagree
        assertEquals(DomainError.OwnershipMismatch, a.createTask(CreateTask("x", projectId = "mba", workStreamId = "agent")).rej())
        assertEquals(DomainError.EmptyTitle, a.createTask(CreateTask(" ", workStreamId = "agent")).rej())
        assertEquals(DomainError.InvalidEffort, a.createTask(CreateTask("x", workStreamId = "agent", estimatedEffort = Duration.ofMinutes(-1))).rej())
    }

    @Test fun task_child_grandchild_arbitraryDepth() = runTest {
        seedTree()
        val nl = repo.getTask("nl")!!
        assertEquals("rc", nl.parentTaskId); assertEquals("agent", nl.workStreamId); assertEquals("app", nl.projectId)
        // Go 10 deeper.
        var parent = "nl"
        repeat(10) { i -> parent = task("deep $i", parent = parent).id }
        assertEquals(13, repo.getAncestry(parent)!!.size) // deep9..deep0 (10) + nl + rc + create
    }

    @Test fun task_selfParent_cycle_rejected() = runTest {
        seedTree()
        assertEquals(DomainError.SelfParent, a.createTask(CreateTask("x", parentTaskId = "loop", id = "loop")).rej())
        // Cycle: cannot create a task whose id already exists as an ancestor of its parent.
        assertEquals(DomainError.CyclicParent, a.createTask(CreateTask("x", parentTaskId = "nl", id = "create")).rej())
        assertTrue(a.createTask(CreateTask("x", parentTaskId = "ghost")).rej() is DomainError.TaskNotFound)
    }

    @Test fun task_crossStream_crossProject_child_rejected() = runTest {
        seedTree()
        assertEquals(DomainError.OwnershipMismatch, a.createTask(CreateTask("x", parentTaskId = "nl", workStreamId = "orb")).rej())
        assertEquals(DomainError.OwnershipMismatch, a.createTask(CreateTask("x", parentTaskId = "nl", projectId = "mba")).rej())
        // Matching explicit ownership is fine.
        assertTrue(a.createTask(CreateTask("ok", parentTaskId = "nl", workStreamId = "agent", projectId = "app")).isSuccess)
    }

    @Test fun task_siblingOrder_autoAssigned() = runTest {
        seedTree()
        assertEquals(listOf("tc", "wc", "rc"), repo.getChildren("create").map { it.id })
        assertEquals(listOf(0, 1, 2), repo.getChildren("create").map { it.order })
    }

    @Test fun task_update_keepsAndClears_inProgress() = runTest {
        seedTree()
        val u = a.updateTask("nl", TaskUpdate(notes = Field.Set("tricky"), inProgress = Field.Set(true))).getOrNull()!!
        assertEquals(TaskStatus.IN_PROGRESS, u.status); assertEquals("tricky", u.notes); assertEquals("Natural Language", u.title)
        val u2 = a.updateTask("nl", TaskUpdate(notes = Field.Clear, inProgress = Field.Set(false))).getOrNull()!!
        assertNull(u2.notes); assertEquals(TaskStatus.TODO, u2.status)
        assertEquals(DomainError.EmptyTitle, a.updateTask("nl", TaskUpdate(title = Field.Set(" "))).rej())
        assertEquals(DomainError.InvalidEffort, a.updateTask("nl", TaskUpdate(estimatedEffort = Field.Set(Duration.ZERO))).rej())
        assertTrue(a.updateTask("ghost", TaskUpdate()).rej() is DomainError.TaskNotFound)
    }

    // ================================================================ Completion

    @Test fun completeLeaf_setsDoneAndTimestamp_rejectsTwice() = runTest {
        seedTree(); clock.advance(Duration.ofMinutes(5))
        val d = a.completeTask("presets").getOrNull()!!
        assertEquals(TaskStatus.DONE, d.status); assertEquals(clock.now(), d.completedAt)
        assertEquals(DomainError.TaskAlreadyClosed, a.completeTask("presets").rej())
        assertEquals(DomainError.TaskAlreadyClosed, a.updateTask("presets", TaskUpdate()).rej())
        assertTrue(EventType.TASK_COMPLETED in repo.getEvents("agent").map { it.type })
    }

    @Test fun completeParent_doesNotMutateChildren_progressIsDerived() = runTest {
        seedTree()
        a.completeTask("rc") // parent closed explicitly
        assertEquals(TaskStatus.TODO, repo.getTask("nl")!!.status)           // children untouched
        // Parent status is never auto-mutated the other way either:
        a.completeTask("tc"); a.completeTask("wc")
        assertEquals(TaskStatus.TODO, repo.getTask("create")!!.status)
        // …but its derived progress is what matters.
        val p = ProgressCalculator.ofTask(tasks(), "create") as ProgressResult.Structured
        assertEquals(2, p.completedLeaves); assertEquals(5, p.totalLeaves)
    }

    // ================================================================ Active task

    @Test fun activeTask_set_clear_path() = runTest {
        seedTree()
        val s = a.setActiveTask("agent", "nl").getOrNull()!!
        assertEquals("nl", s.activeTaskId)
        assertEquals(listOf("nl", "rc", "create"), a.activePath("agent").getOrNull()!!.map { it.id })
        assertEquals(listOf("Natural Language", "Reminder Creation", "Create Mode"), a.activePath("agent").getOrNull()!!.map { it.title })
        a.clearActiveTask("agent")
        assertNull(repo.getStream("agent")!!.activeTaskId)
        assertTrue(a.activePath("agent").getOrNull()!!.isEmpty())
        val ev = repo.getEvents("agent").map { it.type }
        assertTrue(EventType.ACTIVE_TASK_SET in ev && EventType.ACTIVE_TASK_CLEARED in ev)
    }

    @Test fun activeTask_validation() = runTest {
        seedTree()
        task("orb work", ws = "orb", id = "orbtask")
        assertEquals(DomainError.TaskNotInWorkStream, a.setActiveTask("agent", "orbtask").rej())
        assertTrue(a.setActiveTask("agent", "ghost").rej() is DomainError.TaskNotFound)
        a.completeTask("presets")
        assertEquals(DomainError.TaskAlreadyClosed, a.setActiveTask("agent", "presets").rej())
        assertEquals(DomainError.StreamAlreadyDone, a.setActiveTask("done", null).rej())
        assertTrue(a.setActiveTask("ghost", null) is ActionResult.NotFound)
        // Independent of attention state: PROCESSING stream may remember its task.
        assertEquals("orbtask", a.setActiveTask("orb", "orbtask").getOrNull()!!.activeTaskId)
        assertEquals(PROCESSING, repo.getStream("orb")!!.state)
    }

    @Test fun completingActiveTask_clearsIt_noAutoAdvance_candidateExposed() = runTest {
        seedTree()
        a.setActiveTask("agent", "tc")
        a.completeTask("tc")
        assertNull(repo.getStream("agent")!!.activeTaskId)
        assertEquals("wc", a.nextTaskCandidate("agent").getOrNull()!!.id)   // first open leaf in order
        a.completeTask("wc")
        assertEquals("presets", a.nextTaskCandidate("agent").getOrNull()!!.id) // descends into rc
        listOf("presets", "custom", "nl").forEach { a.completeTask(it) }
        assertNull(a.nextTaskCandidate("agent").getOrNull())
    }

    // ================================================================ Focus / snapshot attribution

    @Test fun focusSession_capturesActiveTaskAtStart_historyNeverRewritten() = runTest {
        seedTree()
        a.setActiveTask("agent", "nl")
        a.focusStream("agent")
        assertEquals("nl", repo.getOpenFocusSession("agent")!!.taskId)
        a.setActiveTask("agent", "custom")                      // change mid-session
        assertEquals("nl", repo.getOpenFocusSession("agent")!!.taskId) // unchanged
        a.handOffStream("agent")
        assertEquals("custom", repo.getLatestSnapshot("agent")!!.taskId) // snapshot has the current one
        a.checkDue("agent"); a.focusStream("agent")
        assertEquals("custom", repo.getOpenFocusSession("agent")!!.taskId) // new session, new attribution
        assertEquals(listOf("nl", "custom"), repo.getFocusSessions("agent").map { it.taskId })
    }

    // ================================================================ Progress

    @Test fun progress_noTasks_unstructured() = runTest {
        assertEquals(ProgressResult.Unstructured, ProgressCalculator.ofWorkStream(tasks(), "agent"))
        assertEquals(ProgressResult.Unstructured, ProgressCalculator.ofProject(tasks(), emptyList(), "mba"))
    }

    @Test fun progress_oneTask_allIncomplete_allComplete() = runTest {
        task("solo", id = "solo")
        var p = ProgressCalculator.ofWorkStream(tasks(), "agent") as ProgressResult.Structured
        assertEquals(0L, p.completed); assertEquals(1L, p.total); assertEquals(0.0, p.fraction, 0.0)
        a.completeTask("solo")
        p = ProgressCalculator.ofWorkStream(tasks(), "agent") as ProgressResult.Structured
        assertTrue(p.isComplete); assertEquals(1.0, p.fraction, 0.0)
    }

    @Test fun progress_countBased_parentsNotDoubleCounted() = runTest {
        seedTree()
        listOf("tc", "wc", "presets").forEach { a.completeTask(it) }
        val p = ProgressCalculator.ofWorkStream(tasks(), "agent") as ProgressResult.Structured
        assertEquals(ProgressMode.COUNT_BASED, p.mode)
        assertEquals(3L, p.completed); assertEquals(5L, p.total)   // 7 tasks, 2 are parents → 5 leaves
        assertEquals(60.0, p.percent, 0.0)
    }

    @Test fun progress_effortWeighted_stableRounding() = runTest {
        seedTree(withEffort = true)
        listOf("tc", "wc", "presets").forEach { a.completeTask(it) }
        val p = ProgressCalculator.ofWorkStream(tasks(), "agent") as ProgressResult.Structured
        assertEquals(ProgressMode.EFFORT_WEIGHTED, p.mode)
        assertEquals(25L, p.completed); assertEquals(90L, p.total)
        assertEquals(0.2778, p.fraction, 0.0)                       // 25/90 = 0.27777… → HALF_UP 4dp
        assertEquals(27.78, p.percent, 0.0001)
    }

    @Test fun progress_partialEstimates_fallBackToCount_neverMixed() = runTest {
        seedTree(withEffort = true)
        a.updateTask("nl", TaskUpdate(estimatedEffort = Field.Clear))   // one leaf unestimated
        listOf("tc", "wc", "presets").forEach { a.completeTask(it) }
        val p = ProgressCalculator.ofWorkStream(tasks(), "agent") as ProgressResult.Structured
        assertEquals(ProgressMode.COUNT_BASED, p.mode)
        assertEquals(3L, p.completed); assertEquals(5L, p.total)
    }

    @Test fun progress_taskScope_leafIsItsOwnUnit() = runTest {
        seedTree()
        assertEquals(ProgressMode.COUNT_BASED, (ProgressCalculator.ofTask(tasks(), "nl") as ProgressResult.Structured).mode)
        assertEquals(1L, (ProgressCalculator.ofTask(tasks(), "nl") as ProgressResult.Structured).total)
        val rc = ProgressCalculator.ofTask(tasks(), "rc") as ProgressResult.Structured
        assertEquals(3L, rc.total)
        assertEquals(ProgressResult.Unstructured, ProgressCalculator.ofTask(tasks(), "ghost"))
    }

    @Test fun progress_project_mixed_streams_standalone_andEmptyStream() = runTest {
        seedTree()                                             // agent: 5 leaves
        task("Prepare release notes", ws = null, project = "app", id = "rn")
        task("Send APK", ws = null, project = "app", id = "apk")   // 2 standalone leaves
        // "orb" and "done" belong to project app with NO tasks → each is one unit; "done" is DONE.
        listOf("tc", "rn").forEach { a.completeTask(it) }
        val p = ProgressCalculator.ofProject(tasks(), repo.streams.value, "app") as ProgressResult.Structured
        assertEquals(ProgressMode.COUNT_BASED, p.mode)          // task-less streams force count mode
        assertEquals(9L, p.total)                               // 5 + 2 + 2 streams
        assertEquals(3L, p.completed)                           // tc + rn + "done" stream
        // Project with only task-less streams still gets an honest count.
        repo.transaction { saveStream(ws("mba-ws", READY, project = "mba")) }
        val m = ProgressCalculator.ofProject(tasks(), repo.streams.value, "mba") as ProgressResult.Structured
        assertEquals(1L, m.total); assertEquals(0L, m.completed)
    }

    @Test fun progress_deepNesting_onlyLeavesCount() = runTest {
        seedTree()
        var parent = "nl"; repeat(6) { i -> parent = task("d$i", parent = parent).id }
        // nl is no longer a leaf; the single deepest task is.
        val p = ProgressCalculator.ofWorkStream(tasks(), "agent") as ProgressResult.Structured
        assertEquals(5L, p.total)
        a.completeTask(parent)
        assertEquals(1L, (ProgressCalculator.ofWorkStream(tasks(), "agent") as ProgressResult.Structured).completed)
    }

    // ================================================================ Transactions

    @Test fun failedHierarchyMutation_publishesNothing() = runTest {
        seedTree()
        val tasksBefore = repo.tasks.value; val streamsBefore = repo.streams.value; val projBefore = repo.projects.value
        try {
            repo.transaction {
                saveProject(Project("p2", "leak", createdAt = t0, updatedAt = t0))
                saveTask(getTask("nl")!!.copy(status = TaskStatus.DONE))
                saveStream(getStream("agent")!!.copy(activeTaskId = "nl"))
                throw IllegalStateException("boom")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(tasksBefore, repo.tasks.value); assertEquals(streamsBefore, repo.streams.value); assertEquals(projBefore, repo.projects.value)
        assertNull(repo.getProject("p2"))
        // Progress sees only the committed graph.
        assertEquals(0L, (ProgressCalculator.ofWorkStream(repo.tasks.value, "agent") as ProgressResult.Structured).completed)
    }

    // ================================================================ Existing invariants still hold

    @Test fun attentionInvariants_unaffected() = runTest {
        seedTree(); a.setActiveTask("agent", "nl")
        a.focusStream("agent")
        assertEquals(1, repo.streams.value.count { it.state == FOCUS })
        assertEquals(1, repo.streams.value.count { it.state == PROCESSING })
        assertEquals(DomainError.StreamAlreadyDone, a.focusStream("done").rej())
        a.handOffStream("agent")
        assertEquals("nl", repo.getStream("agent")!!.activeTaskId) // remembered while PROCESSING
    }

    // ================================================================ CORRECTION PASS: projectless WorkStreams

    /** Psychology Unit 23, projectId = null, per the brief. */
    private suspend fun seedPsych() {
        task("Read Chapter", ws = "orphan", id = "read")
        task("Make Notes", ws = "orphan", id = "notes")
        task("Answer Questions", ws = "orphan", id = "answer")
        task("Questions 1–10", ws = "orphan", parent = "answer", id = "q1_10")
        task("Questions 11–20", ws = "orphan", parent = "answer", id = "q11_20")
        task("Question 17", ws = "orphan", parent = "q11_20", id = "q17")
    }

    @Test fun projectless_rootChildDeep_ownership() = runTest {
        seedPsych()
        listOf("read", "q1_10", "q17").forEach { id ->
            val t = repo.getTask(id)!!; assertEquals("orphan", t.workStreamId); assertNull(t.projectId)
        }
        var parent = "q17"; repeat(8) { i -> parent = task("d$i", ws = "orphan", parent = parent).id }
        assertEquals(11, repo.getAncestry(parent)!!.size)   // d7..d0 + q17 + q11_20 + answer
        assertNull(repo.getTask(parent)!!.projectId)
    }

    @Test fun projectless_child_cannotJumpStreamOrProject() = runTest {
        seedPsych()
        assertEquals(DomainError.OwnershipMismatch, a.createTask(CreateTask("x", parentTaskId = "q17", workStreamId = "agent")).rej())
        assertEquals(DomainError.OwnershipMismatch, a.createTask(CreateTask("x", parentTaskId = "q17", projectId = "app")).rej())
        // And a project-owned tree cannot be re-rooted into the projectless stream.
        seedTree()
        assertEquals(DomainError.OwnershipMismatch, a.createTask(CreateTask("x", parentTaskId = "nl", workStreamId = "orphan")).rej())
        // Standalone project tree cannot jump to another project.
        task("standalone", ws = null, project = "app", id = "sa")
        assertEquals(DomainError.OwnershipMismatch, a.createTask(CreateTask("x", parentTaskId = "sa", projectId = "mba")).rej())
    }

    @Test fun projectless_activeTask_path_focusAndSnapshotAttribution() = runTest {
        seedPsych()
        assertEquals("q17", a.setActiveTask("orphan", "q17").getOrNull()!!.activeTaskId)
        assertEquals(listOf("Question 17", "Questions 11–20", "Answer Questions"), a.activePath("orphan").getOrNull()!!.map { it.title })
        task("agent task", id = "at")
        assertEquals(DomainError.TaskNotInWorkStream, a.setActiveTask("orphan", "at").rej())
        a.focusStream("orphan")
        assertEquals("q17", repo.getOpenFocusSession("orphan")!!.taskId)
        a.setActiveTask("orphan", "q1_10")
        assertEquals("q17", repo.getOpenFocusSession("orphan")!!.taskId)          // history not rewritten
        a.handOffStream("orphan")
        assertEquals("q1_10", repo.getLatestSnapshot("orphan")!!.taskId)
        a.completeTask("q1_10")                                                     // clears while PROCESSING too
        assertNull(repo.getStream("orphan")!!.activeTaskId)
        assertEquals(DomainError.TaskAlreadyClosed, a.setActiveTask("orphan", "q1_10").rej())
        a.setActiveTask("orphan", "q17"); a.clearActiveTask("orphan"); assertNull(repo.getStream("orphan")!!.activeTaskId)
    }

    @Test fun projectless_progress_flat_andNested() = runTest {
        seedPsych()
        listOf("read", "notes").forEach { a.completeTask(it) }
        // Leaves: read, notes, q1_10, q17  → 2/4
        val p = ProgressCalculator.ofWorkStream(tasks(), "orphan") as ProgressResult.Structured
        assertEquals(ProgressMode.COUNT_BASED, p.mode); assertEquals(2L, p.completed); assertEquals(4L, p.total)
        a.completeTask("q1_10")
        assertEquals(3L, (ProgressCalculator.ofWorkStream(tasks(), "orphan") as ProgressResult.Structured).completed)
        assertEquals(1L, (ProgressCalculator.ofTask(tasks(), "q11_20") as ProgressResult.Structured).total) // parent not counted
    }

    // ================================================================ CORRECTION PASS: CANCELLED semantics

    @Test fun cancelled_isTerminal_notCompleted() = runTest {
        seedTree()
        val c = a.cancelTask("presets").getOrNull()!!
        assertEquals(TaskStatus.CANCELLED, c.status); assertNull(c.completedAt)
        assertTrue(TaskStatus.CANCELLED.isTerminal); assertFalse(TaskStatus.CANCELLED.isCompleted)
        assertTrue(TaskStatus.DONE.isTerminal); assertTrue(TaskStatus.DONE.isCompleted)
        assertEquals(DomainError.TaskAlreadyClosed, a.completeTask("presets").rej())
        assertEquals(DomainError.TaskAlreadyClosed, a.cancelTask("presets").rej())
        assertEquals(DomainError.TaskAlreadyClosed, a.setActiveTask("agent", "presets").rej())
    }

    @Test fun cancelled_excludedFromCountDenominator() = runTest {
        // A DONE, B DONE, C CANCELLED, D TODO → 2/3
        task("A", id = "A"); task("B", id = "B"); task("C", id = "C"); task("D", id = "D")
        a.completeTask("A"); a.completeTask("B"); a.cancelTask("C")
        val p = ProgressCalculator.ofWorkStream(tasks(), "agent") as ProgressResult.Structured
        assertEquals(2L, p.completed); assertEquals(3L, p.total); assertEquals(1, p.cancelledLeaves)
        assertEquals(0.6667, p.fraction, 0.0)
    }

    @Test fun cancelled_excludedFromEffortNumeratorAndDenominator() = runTest {
        // A 10m DONE, B 20m CANCELLED, C 30m TODO → 10/40
        task("A", effort = 10, id = "A"); task("B", effort = 20, id = "B"); task("C", effort = 30, id = "C")
        a.completeTask("A"); a.cancelTask("B")
        val p = ProgressCalculator.ofWorkStream(tasks(), "agent") as ProgressResult.Structured
        assertEquals(ProgressMode.EFFORT_WEIGHTED, p.mode); assertEquals(10L, p.completed); assertEquals(40L, p.total)
        assertEquals(25.0, p.percent, 0.0001)
    }

    @Test fun parent_effectiveCompletion_withCancellation() = runTest {
        seedTree()
        // CASE A: all leaves DONE
        listOf("presets", "custom", "nl").forEach { a.completeTask(it) }
        assertTrue((ProgressCalculator.ofTask(tasks(), "rc") as ProgressResult.Structured).isComplete)
        // CASE B: DONE + CANCELLED, nothing unfinished → effectively complete; wc stays CANCELLED
        a.completeTask("tc"); a.cancelTask("wc")
        val create = ProgressCalculator.ofTask(tasks(), "create") as ProgressResult.Structured
        assertTrue(create.isComplete); assertEquals(4L, create.total); assertEquals(1, create.cancelledLeaves)
        assertEquals(TaskStatus.CANCELLED, repo.getTask("wc")!!.status)
        assertEquals(TaskStatus.TODO, repo.getTask("create")!!.status)   // parent status never mutated
    }

    @Test fun allCancelledTree_isNoActiveWork_notComplete() = runTest {
        task("P", id = "P"); task("x", parent = "P", id = "x"); task("y", parent = "P", id = "y")
        a.cancelTask("x"); a.cancelTask("y")
        assertEquals(ProgressResult.NoActiveWork, ProgressCalculator.ofTask(tasks(), "P"))
        assertEquals(ProgressResult.NoActiveWork, ProgressCalculator.ofWorkStream(tasks(), "agent"))
        assertEquals(ProgressResult.Unstructured, ProgressCalculator.ofWorkStream(tasks(), "orphan")) // distinct from no leaves
    }

    @Test fun projectProgress_excludesCancelled_standaloneAndMixed() = runTest {
        seedTree()                                             // 5 leaves under agent (project app)
        task("Release notes", ws = null, project = "app", id = "rn")
        task("Send APK", ws = null, project = "app", id = "apk")
        a.completeTask("tc"); a.cancelTask("wc"); a.cancelTask("rn"); a.completeTask("apk")
        // units: 4 active agent leaves + apk + 2 task-less streams (orb, done) = 7; completed: tc, apk, done-stream = 3
        val p = ProgressCalculator.ofProject(tasks(), repo.streams.value, "app") as ProgressResult.Structured
        assertEquals(7L, p.total); assertEquals(3L, p.completed); assertEquals(2, p.cancelledLeaves)
        // Projectless stream's tasks never leak into any project.
        seedPsych(); a.completeTask("read")
        assertEquals(7L, (ProgressCalculator.ofProject(tasks(), repo.streams.value, "app") as ProgressResult.Structured).total)
    }

    @Test fun failedProjectlessOrNestedMutation_publishesNothing() = runTest {
        seedPsych()
        val before = repo.tasks.value
        try { repo.transaction { saveTask(getTask("q17")!!.copy(workStreamId = "agent")); saveTask(getTask("read")!!.copy(status = TaskStatus.DONE)); throw IllegalStateException() } } catch (_: IllegalStateException) { }
        assertEquals(before, repo.tasks.value)
        assertEquals("orphan", repo.getTask("q17")!!.workStreamId)
    }
}
