package com.virlin.app.hierarchy

import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.ProgressMode
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.hierarchy.HierarchyPresentation
import com.virlin.app.ui.hierarchy.HierarchyViewModel
import com.virlin.app.ui.hierarchy.toLabel
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

/** Presentation logic + ViewModel intents over the seeded demo hierarchy. Deterministic. */
@OptIn(ExperimentalCoroutinesApi::class)
class HierarchyPresentationTest {

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var vm: HierarchyViewModel

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, tool: String? = null, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, tool = tool, activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val streams = listOf(
            ws("s4", "Agent Development", WorkStreamState.PROCESSING, "p1", "Antigravity", "t_nl"),
            ws("s1", "Psychology Unit 23", WorkStreamState.FOCUS, null, active = "p_q17"),
            ws("s9", "Notifications", WorkStreamState.READY, "p1"),
            ws("s10", "Morning Walking", WorkStreamState.READY, null)
        )
        repo = InMemoryWorkStreamRepository(
            seed = streams,
            seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0), Project("p2", "Empty", createdAt = t0, updatedAt = t0)),
            seedTasks = DemoHierarchySeed.tasks(streams, t0)
        )
        vm = HierarchyViewModel(repo, DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider()))
    }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun snap() = HierarchyViewModel.Snapshot(repo.projects.value, repo.streams.value, repo.tasks.value, emptySet())

    // ---------------------------------------------------------------- Project detail

    @Test fun projectSummary_multipleStreams_standalone_cancelled_fromDomain() = runTest {
        val ps = vm.projectSummaries(snap()).first { it.project.id == "p1" }
        assertEquals(2, ps.streamCount)
        val p = ProgressCalculator.ofProject(repo.tasks.value, repo.streams.value, "p1") as ProgressResult.Structured
        assertEquals("${(p.fraction * 100).toInt()}%", ps.progress.text)      // exactly the domain value
        // cancel a standalone leaf → excluded from denominator
        val before = p.total
        vm.cancelTask("t_notes"); advanceUntilIdle()
        val after = ProgressCalculator.ofProject(repo.tasks.value, repo.streams.value, "p1") as ProgressResult.Structured
        assertEquals(before - 1, after.total); assertEquals(1, after.cancelledLeaves)
    }

    @Test fun projectSummary_emptyProject_isUnstructured_notPercent() = runTest {
        val ps = vm.projectSummaries(snap()).first { it.project.id == "p2" }
        assertEquals("No structured progress", ps.progress.text); assertNull(ps.progress.fraction)
    }

    // ---------------------------------------------------------------- WorkStream detail

    @Test fun streamRows_projectBacked_nested_currentIndicator_parentProgress() = runTest {
        val s = snap()
        val stream = s.streams.first { it.id == "s4" }
        val collapsed = vm.streamRows(s, stream)
        assertEquals(listOf("t_control", "t_create", "t_capture", "t_pixel"), collapsed.map { it.id })   // depth-0 only
        assertTrue(collapsed.first { it.id == "t_create" }.hasChildren)
        assertNotNull(collapsed.first { it.id == "t_create" }.progress)                                   // parent progress
        assertNull(collapsed.first { it.id == "t_control" }.progress)                                     // leaf: none
        vm.expandPathTo("t_nl")                                                                          // auto-expand to current
        val open = vm.streamRows(vm.snapshot.value.copy(expanded = setOf("t_create", "t_rem")), stream)
        val nl = open.first { it.id == "t_nl" }
        assertTrue(nl.isCurrent); assertEquals(2, nl.depth)
        assertTrue(nl.accessibilityLabel.contains("current task"))
        // all three leaves carry estimates → effort-weighted (25m of 70m); the row shows exactly the domain value
        val rem = ProgressCalculator.ofTask(s.tasks, "t_rem") as ProgressResult.Structured
        assertEquals(ProgressMode.EFFORT_WEIGHTED, rem.mode)
        assertEquals(rem.toLabel().text, open.first { it.id == "t_rem" }.progress!!.text)
    }

    @Test fun streamRows_projectless_deep_noProject() = runTest {
        val s = snap().copy(expanded = setOf("p_answer", "p_q2"))
        val stream = s.streams.first { it.id == "s1" }
        assertNull(stream.projectId)
        val rows = vm.streamRows(s, stream)
        assertEquals(listOf("p_read", "p_notes", "p_answer", "p_q1", "p_q2", "p_q11", "p_q12", "p_q17"), rows.map { it.id })
        assertEquals(2, rows.first { it.id == "p_q17" }.depth)
        assertTrue(rows.first { it.id == "p_q17" }.isCurrent)
        assertEquals("83%", vm.streamSummary(s, stream).progress.text)   // leaves: read, notes, q1, q11, q12 done; q17 open = 5/6
    }

    @Test fun streamSummary_noTasks_unstructured_andOneTask() = runTest {
        val s = snap()
        assertEquals("No structured progress", vm.streamSummary(s, s.streams.first { it.id == "s10" }).progress.text)
        vm.addTask("s10", "Stretch", null); advanceUntilIdle()
        val s2 = snap()
        assertEquals(1, vm.streamRows(s2, s2.streams.first { it.id == "s10" }).size)
        assertEquals("0%", vm.streamSummary(s2, s2.streams.first { it.id == "s10" }).progress.text)
    }

    @Test fun cancelledRow_isDistinctFromDone() = runTest {
        vm.cancelTask("t_voice"); advanceUntilIdle()
        val rows = vm.streamRows(snap().copy(expanded = setOf("t_capture")), repo.streams.value.first { it.id == "s4" })
        val voice = rows.first { it.id == "t_voice" }; val prompt = rows.first { it.id == "t_prompt" }
        assertEquals(TaskStatus.CANCELLED, voice.status); assertTrue(voice.accessibilityLabel.contains("cancelled"))
        assertEquals(TaskStatus.DONE, prompt.status); assertTrue(prompt.accessibilityLabel.contains("completed"))
        assertEquals("1 / 3 · 33%", rows.first { it.id == "t_capture" }.progress!!.text)                  // 4 leaves − 1 cancelled
    }

    // ---------------------------------------------------------------- Task detail / breadcrumb

    @Test fun breadcrumb_projectBacked_deep() = runTest {
        val s = snap(); val nl = s.tasks.first { it.id == "t_nl" }
        val b = vm.breadcrumb(s, nl)
        assertEquals("Virlin Android App", b.project); assertEquals("Agent Development", b.workStream)
        assertEquals(listOf("Create Mode", "Reminder Creation"), b.ancestors.map { it.title })
        assertEquals("Natural Language", b.current.title)
    }

    @Test fun breadcrumb_projectless_root_and_nested() = runTest {
        val s = snap()
        val b = vm.breadcrumb(s, s.tasks.first { it.id == "p_q17" })
        assertNull(b.project); assertEquals("Psychology Unit 23", b.workStream)
        assertEquals(listOf("Answer Questions", "Questions 11–20"), b.ancestors.map { it.title })
        val root = vm.breadcrumb(s, s.tasks.first { it.id == "p_read" })
        assertTrue(root.ancestors.isEmpty())
    }

    @Test fun subtaskRows_andNoSubtasks() = runTest {
        val s = snap()
        assertEquals(listOf("p_q11", "p_q12", "p_q17"), vm.subtaskRows(s, s.tasks.first { it.id == "p_q2" }).map { it.id })
        assertTrue(vm.subtaskRows(s, s.tasks.first { it.id == "p_q17" }).isEmpty())
        assertEquals(Duration.ofMinutes(45), s.tasks.first { it.id == "t_nl" }.estimatedEffort)
    }

    // ---------------------------------------------------------------- Actions via VirlinActions only

    @Test fun completeActiveTask_clearsCurrent_andSurfacesCandidate_notAutoSelected() = runTest {
        // Psychology: Q17 was the last open leaf → no candidate at all, nothing auto-selected
        vm.completeTask("p_q17"); advanceUntilIdle()
        assertEquals(TaskStatus.DONE, repo.getTask("p_q17")!!.status)
        assertNull(repo.streams.value.first { it.id == "s1" }.activeTaskId)
        assertNull(vm.nextCandidate("s1"))
        // Agent Development: open leaves remain → a candidate is offered, still not auto-selected
        vm.completeTask("t_nl"); advanceUntilIdle()
        assertNull(repo.streams.value.first { it.id == "s4" }.activeTaskId)
        val next = vm.nextCandidate("s4")
        assertNotNull(next); assertEquals(TaskStatus.TODO, next!!.status); assertNull(next.parentTaskId.takeIf { false })
        vm.setActiveTask("s4", next.id); advanceUntilIdle()                     // explicit START
        assertEquals(next.id, repo.streams.value.first { it.id == "s4" }.activeTaskId)
    }

    @Test fun addTask_addSubtask_inheritOwnership_projectless() = runTest {
        vm.addTask("s1", "Review answers", Duration.ofMinutes(15)); advanceUntilIdle()
        val root = repo.tasks.value.first { it.title == "Review answers" }
        assertEquals("s1", root.workStreamId); assertNull(root.projectId); assertNull(root.parentTaskId)
        vm.addSubtask("p_q17", "Cite sources", null); advanceUntilIdle()
        val sub = repo.tasks.value.first { it.title == "Cite sources" }
        assertEquals("p_q17", sub.parentTaskId); assertEquals("s1", sub.workStreamId); assertNull(sub.projectId)
    }

    @Test fun progressLabels_neverExposeEnumNames() {
        assertEquals("No active planned work", ProgressResult.NoActiveWork.toLabel().text)
        assertEquals("No structured progress", ProgressResult.Unstructured.toLabel().text)
        val l = ProgressCalculator.ofTask(repo.tasks.value, "t_rem").toLabel()
        assertFalse(l.text.contains("EFFORT") || l.text.contains("COUNT"))
    }

    @Test fun ancestorIds_forAutoExpand() {
        assertEquals(setOf("p_answer", "p_q2"), HierarchyPresentation.ancestorIds(repo.tasks.value, "p_q17"))
        assertTrue(HierarchyPresentation.ancestorIds(repo.tasks.value, null).isEmpty())
    }
}
