package com.virlin.app.agent

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CaptureTaskTarget
import com.virlin.app.domain.action.CaptureUpdate
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.Field
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.WorkStreamState.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.FakeAttentionScheduler
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.ui.agent.capture.AgentCaptureState
import com.virlin.app.ui.agent.capture.AgentCaptureViewModel
import com.virlin.app.ui.agent.capture.CaptureContextChoice
import com.virlin.app.ui.agent.capture.CaptureFilter
import com.virlin.app.ui.agent.capture.CapturePresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * Pass 10 — Capture domain + ViewModel: verbatim preservation through VirlinActions, optional
 * explicit context, Inbox/archive/organize, atomic convert-to-Task. Real domain, in-memory
 * repository behind the scheduling decorator (alarms proven untouched), fake clock, no UI.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentCaptureTest {

    private val t0: Instant = Instant.parse("2026-09-12T10:00:00Z")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: FakeClock
    private lateinit var scheduler: FakeAttentionScheduler
    private lateinit var repo: SchedulingWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var vm: AgentCaptureViewModel

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, executionPreference = mode.toPreference(), activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = FakeClock(t0); scheduler = FakeAttentionScheduler()
        val streams = listOf(
            ws("s4", "Agent Development", READY, "p1", WorkStreamMode.EXTERNAL),
            ws("s1", "Psychology Unit 23", FOCUS, null, active = "p_q17"),
            ws("s8", "Projectless", READY, null)
        )
        repo = SchedulingWorkStreamRepository(InMemoryWorkStreamRepository(
            seed = streams, seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0)),
            seedTasks = DemoHierarchySeed.tasks(streams, t0)
        ), scheduler)
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        vm = AgentCaptureViewModel(actions, repo, clock)
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun TestScope.projected(): AgentCaptureState { backgroundScope.launch { vm.state.collect {} }; advanceUntilIdle(); return vm.state.value }
    private suspend fun note(text: String, ctx: CaptureContext = CaptureContext.None) =
        (actions.createCapture(CreateCapture(CaptureType.NOTE, content = text, context = ctx)) as ActionResult.Success).value
    private fun inbox() = repo.captures.value.filter { it.status == CaptureStatus.INBOX }

    // ------------------------------------------------------------ §55 domain

    @Test fun create_note() = runTest(dispatcher) {
        val c = note("Investigate whether notifications should use local audio.")
        assertEquals(CaptureType.NOTE, c.type); assertEquals(CaptureStatus.INBOX, c.status); assertNull(c.title)
        assertEquals(c, repo.getCapture(c.id))
    }

    @Test fun create_prompt_preserves_line_breaks_verbatim() = runTest(dispatcher) {
        val prompt = "Refactor this repository but preserve the public API.\n\n- keep tests green\n- no renames\n\tindented line"
        val r = actions.createCapture(CreateCapture(CaptureType.PROMPT, content = prompt)) as ActionResult.Success
        assertEquals(prompt, repo.getCapture(r.value.id)!!.content)
    }

    @Test fun create_link() = runTest(dispatcher) {
        val r = actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "https://github.com/virlin/app", content = "the repo")) as ActionResult.Success
        assertEquals("https://github.com/virlin/app", r.value.sourceUrl); assertEquals("the repo", r.value.content)
    }

    @Test fun blank_note_rejected() = runTest(dispatcher) {
        val r = actions.createCapture(CreateCapture(CaptureType.NOTE, content = "   \n "))
        assertEquals(ActionResult.Rejected(DomainError.EmptyCapture), r); assertTrue(repo.captures.value.isEmpty())
    }

    @Test fun blank_prompt_rejected() = runTest(dispatcher) {
        assertEquals(ActionResult.Rejected(DomainError.EmptyCapture), actions.createCapture(CreateCapture(CaptureType.PROMPT, content = "")))
    }

    @Test fun blank_or_invalid_link_rejected() = runTest(dispatcher) {
        assertEquals(ActionResult.Rejected(DomainError.InvalidLink), actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "")))
        assertEquals(ActionResult.Rejected(DomainError.InvalidLink), actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "not a url")))
        assertEquals(ActionResult.Rejected(DomainError.InvalidLink), actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "github.com/x y")))
        assertTrue(repo.captures.value.isEmpty())
    }

    @Test fun optional_project_context() = runTest(dispatcher) {
        val c = note("x", CaptureContext(projectId = "p1"))
        assertEquals("p1", c.projectId); assertNull(c.workStreamId); assertNull(c.taskId)
    }

    @Test fun optional_projectless_workstream_context() = runTest(dispatcher) {
        val c = note("Revise Question 17 explanation", CaptureContext(workStreamId = "s1"))
        assertEquals("s1", c.workStreamId); assertNull(c.projectId)
    }

    @Test fun optional_task_context_derives_ancestry() = runTest(dispatcher) {
        val c = note("x", CaptureContext(taskId = "p_q17"))
        assertEquals("p_q17", c.taskId); assertEquals("s1", c.workStreamId); assertNull(c.projectId)
        val d = note("y", CaptureContext(taskId = "t_nl"))
        assertEquals("s4", d.workStreamId); assertEquals("p1", d.projectId)
    }

    @Test fun no_context_valid() = runTest(dispatcher) {
        val c = note("global"); assertFalse(c.hasContext)
    }

    @Test fun status_defaults_inbox_and_createdAt_from_clock() = runTest(dispatcher) {
        clock.advance(Duration.ofMinutes(7))
        val c = note("x")
        assertEquals(CaptureStatus.INBOX, c.status); assertEquals(t0.plus(Duration.ofMinutes(7)), c.createdAt); assertEquals(c.createdAt, c.updatedAt)
    }

    @Test fun archive_sets_archived_and_leaves_inbox() = runTest(dispatcher) {
        val c = note("x"); clock.advance(Duration.ofMinutes(1))
        val a = (actions.archiveCapture(c.id) as ActionResult.Success).value
        assertEquals(CaptureStatus.ARCHIVED, a.status); assertEquals(clock.now(), a.archivedAt); assertEquals("x", a.content)
        assertTrue(inbox().isEmpty()); assertNotNull(repo.getCapture(c.id))   // never hard-deleted
        val back = (actions.restoreCapture(c.id) as ActionResult.Success).value
        assertEquals(CaptureStatus.INBOX, back.status); assertNull(back.archivedAt)
    }

    @Test fun organized_item_leaves_inbox() = runTest(dispatcher) {
        val c = note("Write summary")
        actions.convertCaptureToTask(c.id, CaptureTaskTarget(workStreamId = "s8"))
        assertTrue(inbox().isEmpty()); assertEquals(CaptureStatus.ORGANIZED, repo.getCapture(c.id)!!.status)
    }

    @Test fun edit_updates_content_and_updatedAt() = runTest(dispatcher) {
        val c = note("draft"); clock.advance(Duration.ofMinutes(3))
        val u = (actions.updateCapture(c.id, CaptureUpdate(content = Field.Set("final"), title = Field.Set("T"))) as ActionResult.Success).value
        assertEquals("final", u.content); assertEquals("T", u.title); assertEquals(clock.now(), u.updatedAt); assertEquals(c.createdAt, u.createdAt)
        assertEquals(ActionResult.Rejected(DomainError.EmptyCapture), actions.updateCapture(c.id, CaptureUpdate(content = Field.Set(" "))))
    }

    @Test fun plain_capture_mutates_nothing_else() = runTest(dispatcher) {
        val before = repo.streams.value to repo.tasks.value
        note("x", CaptureContext(workStreamId = "s1")); note("y", CaptureContext(taskId = "p_q17"))
        assertEquals(before.first, repo.streams.value); assertEquals(before.second, repo.tasks.value)
        assertEquals(FOCUS, repo.getStream("s1")!!.state); assertEquals("p_q17", repo.getStream("s1")!!.activeTaskId)
        assertTrue(scheduler.current.isEmpty()); assertTrue(repo.getEvents("s1").isEmpty())
    }

    // ------------------------------------------------------------ §56 validation

    @Test fun unknown_project_rejected() = runTest(dispatcher) {
        assertEquals(ActionResult.Rejected(DomainError.ProjectNotFound("nope")), actions.createCapture(CreateCapture(CaptureType.NOTE, "x", context = CaptureContext(projectId = "nope"))))
    }

    @Test fun unknown_workstream_rejected() = runTest(dispatcher) {
        assertEquals(ActionResult.NotFound("nope"), actions.createCapture(CreateCapture(CaptureType.NOTE, "x", context = CaptureContext(workStreamId = "nope"))))
    }

    @Test fun unknown_task_rejected() = runTest(dispatcher) {
        assertEquals(ActionResult.Rejected(DomainError.TaskNotFound("nope")), actions.createCapture(CreateCapture(CaptureType.NOTE, "x", context = CaptureContext(taskId = "nope"))))
    }

    @Test fun task_workstream_mismatch_rejected() = runTest(dispatcher) {
        val r = actions.createCapture(CreateCapture(CaptureType.NOTE, "x", context = CaptureContext(workStreamId = "s4", taskId = "p_q17")))
        assertEquals(ActionResult.Rejected(DomainError.OwnershipMismatch), r)
        val r2 = actions.createCapture(CreateCapture(CaptureType.NOTE, "x", context = CaptureContext(projectId = "p1", workStreamId = "s1")))
        assertEquals(ActionResult.Rejected(DomainError.OwnershipMismatch), r2)
        assertTrue(repo.captures.value.isEmpty())
    }

    @Test fun stale_selected_task_fails_safely_via_viewmodel() = runTest(dispatcher) {
        val id = (actions.createTask(com.virlin.app.domain.action.CreateTask(title = "temp", workStreamId = "s8")) as ActionResult.Success).value.id
        vm.setContext(CaptureContextChoice(workStreamId = "s8", taskId = id))
        // the task disappears from under the form (cancelled tasks still exist; simulate a removed one by using a never-existing id)
        vm.setContext(CaptureContextChoice(workStreamId = "s8", taskId = "gone"))
        var saved = false; vm.save("late note") { saved = true }; advanceUntilIdle()
        assertFalse(saved); assertTrue(repo.captures.value.isEmpty())
        assertEquals("That task no longer exists — choose again", vm.form.value.error)
    }

    @Test fun projectless_workstream_attachment_valid_via_attach() = runTest(dispatcher) {
        val c = note("x")
        val a = (actions.attachCapture(c.id, CaptureContext(workStreamId = "s8")) as ActionResult.Success).value
        assertEquals("s8", a.workStreamId); assertNull(a.projectId); assertEquals(CaptureStatus.INBOX, a.status)
    }

    // ------------------------------------------------------------ §57 conversion

    @Test fun convert_to_workstream_task() = runTest(dispatcher) {
        val c = note("Write chapter summary\nwith details", CaptureContext(workStreamId = "s8"))
        val t = (actions.convertCaptureToTask(c.id, CaptureTaskTarget(workStreamId = "s8")) as ActionResult.Success).value
        assertEquals("Write chapter summary", t.title); assertEquals("s8", t.workStreamId); assertEquals(TaskStatus.TODO, t.status)
        assertTrue(t.description!!.contains("with details"))
        val org = repo.getCapture(c.id)!!
        assertEquals(CaptureStatus.ORGANIZED, org.status); assertEquals(t.id, org.convertedTaskId); assertEquals(t.id, org.taskId)
        assertEquals(CaptureType.NOTE, org.type)                                  // conversion never fakes a type change
    }

    @Test fun convert_to_standalone_project_task() = runTest(dispatcher) {
        val c = note("Buy paper")
        val t = (actions.convertCaptureToTask(c.id, CaptureTaskTarget(projectId = "p1")) as ActionResult.Success).value
        assertNull(t.workStreamId); assertEquals("p1", t.projectId)
    }

    @Test fun convert_to_child_task() = runTest(dispatcher) {
        val c = note("Question 17 follow-up")
        val t = (actions.convertCaptureToTask(c.id, CaptureTaskTarget(parentTaskId = "p_q17")) as ActionResult.Success).value
        assertEquals("p_q17", t.parentTaskId); assertEquals("s1", t.workStreamId)
    }

    @Test fun failed_task_creation_leaves_capture_in_inbox() = runTest(dispatcher) {
        val c = note("x")
        val r = actions.convertCaptureToTask(c.id, CaptureTaskTarget())           // no owner → structure rejects
        assertEquals(ActionResult.Rejected(DomainError.OwnershipMismatch), r)
        assertEquals(CaptureStatus.INBOX, repo.getCapture(c.id)!!.status); assertNull(repo.getCapture(c.id)!!.convertedTaskId)
        assertTrue(repo.tasks.value.none { it.title == "x" })
        val r2 = actions.convertCaptureToTask(c.id, CaptureTaskTarget(workStreamId = "nope"))
        assertEquals(ActionResult.NotFound("nope"), r2); assertEquals(CaptureStatus.INBOX, repo.getCapture(c.id)!!.status)
    }

    @Test fun successful_conversion_marks_organized_and_task_visible_immediately() = runTest(dispatcher) {
        val c = note("Visible")
        val t = (actions.convertCaptureToTask(c.id, CaptureTaskTarget(workStreamId = "s4")) as ActionResult.Success).value
        assertTrue(repo.tasks.value.any { it.id == t.id })
        assertEquals(CaptureStatus.ORGANIZED, repo.captures.value.first { it.id == c.id }.status)
    }

    @Test fun no_duplicate_conversion_on_double_action() = runTest(dispatcher) {
        val c = note("Once")
        actions.convertCaptureToTask(c.id, CaptureTaskTarget(workStreamId = "s4"))
        assertEquals(ActionResult.Rejected(DomainError.CaptureAlreadyOrganized), actions.convertCaptureToTask(c.id, CaptureTaskTarget(workStreamId = "s4")))
        assertEquals(1, repo.tasks.value.count { it.title == "Once" })
    }

    @Test fun link_conversion_uses_url_when_no_title() = runTest(dispatcher) {
        val l = (actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "https://example.com/doc")) as ActionResult.Success).value
        val t = (actions.convertCaptureToTask(l.id, CaptureTaskTarget(workStreamId = "s8")) as ActionResult.Success).value
        assertEquals("https://example.com/doc", t.title)
    }

    // ------------------------------------------------------------ ViewModel / presentation

    @Test fun viewmodel_save_note_clears_form_and_keeps_focus() = runTest(dispatcher) {
        projected()                                                                // subscribe like the composable does
        var cleared = false
        vm.save("Investigate local music alarms") { cleared = true }; advanceUntilIdle()
        assertTrue(cleared); assertEquals("Saved to Inbox", vm.form.value.feedback); assertNull(vm.form.value.error)
        assertEquals(FOCUS, repo.getStream("s1")!!.state)
        assertEquals(1, projected().rows.size)
    }

    @Test fun viewmodel_link_uses_composer_text_as_url() = runTest(dispatcher) {
        projected()
        vm.setType(CaptureType.LINK); vm.setLinkNote("docs")
        vm.save("https://example.com"); advanceUntilIdle()
        val c = repo.captures.value.single(); assertEquals("https://example.com", c.sourceUrl); assertEquals("docs", c.content)
        assertEquals("Saved link", vm.form.value.feedback); assertEquals("", vm.form.value.linkNote)
        vm.save("nope"); advanceUntilIdle()
        assertEquals("Enter a full link starting with http:// or https://", vm.form.value.error)
    }

    @Test fun viewmodel_attach_to_current_focus_is_explicit_and_per_capture() = runTest(dispatcher) {
        projected()
        vm.save("global"); advanceUntilIdle()
        vm.attachToCurrentFocus(); assertEquals("s1", vm.form.value.context.workStreamId)
        assertEquals("Psychology Unit 23", projected().contextLabel)
        vm.save("contextual"); advanceUntilIdle()
        assertTrue(vm.form.value.context.isEmpty)                                  // next capture is global again
        val rows = projected().rows
        assertEquals(listOf(null, "Psychology Unit 23").toSet(), rows.map { it.contextLabel }.toSet())
    }

    @Test fun inbox_newest_first_by_createdAt_not_insertion() = runTest(dispatcher) {
        // Insert an OLDER item second: ordering must follow createdAt.
        clock.advance(Duration.ofHours(2)); note("second-inserted-newer")
        clock.current = t0; note("second-inserted-older")
        clock.current = t0.plus(Duration.ofHours(3)); note("third")
        assertEquals(listOf("third", "second-inserted-newer", "second-inserted-older"), projected().rows.map { it.item.content })
        assertEquals(listOf("third", "second-inserted-newer", "second-inserted-older"), repo.captures.value.map { it.content })
    }

    @Test fun filters_and_selection() = runTest(dispatcher) {
        val a = note("a"); val b = note("b"); note("c")
        actions.archiveCapture(a.id); actions.convertCaptureToTask(b.id, CaptureTaskTarget(workStreamId = "s8"))
        assertEquals(listOf("c"), projected().rows.map { it.item.content }); assertEquals(1, projected().inboxCount)
        vm.setFilter(CaptureFilter.ARCHIVED); assertEquals(listOf("a"), projected().rows.map { it.item.content })
        vm.setFilter(CaptureFilter.ORGANIZED); assertEquals(listOf("b"), projected().rows.map { it.item.content })
        vm.select(a.id); assertEquals("a", projected().selected!!.content)
        vm.archive(a.id); advanceUntilIdle()                                       // idempotent
        vm.restore(a.id); advanceUntilIdle(); assertEquals(CaptureStatus.INBOX, repo.getCapture(a.id)!!.status)
    }

    @Test fun presentation_preview_truncates_and_flattens_lines() {
        val long = (1..40).joinToString("\n") { "line $it of a very long prompt" }
        val item = com.virlin.app.domain.model.CaptureItem("x", CaptureType.PROMPT, long, createdAt = t0, updatedAt = t0)
        val p = CapturePresentation.preview(item)
        assertTrue(p.length <= CapturePresentation.PreviewChars); assertFalse(p.contains('\n')); assertTrue(p.endsWith("…"))
        assertEquals("github.com/x", CapturePresentation.displayUrl("https://www.github.com/x/"))
        assertEquals("18m ago", CapturePresentation.age(t0, t0.plus(Duration.ofMinutes(18))))
        assertEquals("now", CapturePresentation.age(t0, t0))
    }
}
