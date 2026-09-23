package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.hierarchy.toLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Phase 08 — the gaps the existing `StructureActionsTest` / `HierarchyPresentationTest` did not
 * already cover: deterministic NEXT traversal through a nested tree, and that a large hierarchy
 * (1 project · 10 streams · 100+ items · several depths) is projected in one pass.
 */
class WorkHierarchyTest {

    private val t0: Instant = Instant.parse("2026-09-23T09:00:00Z")
    private val clock = FakeClock(t0)

    private fun stream(id: String, project: String? = "p1") = WorkStream(
        id = id, title = id, projectId = project, state = WorkStreamState.READY, createdAt = t0, updatedAt = t0
    )
    private fun harness(streams: List<WorkStream>): Pair<InMemoryWorkStreamRepository, DefaultVirlinActions> {
        val repo = InMemoryWorkStreamRepository(
            seed = streams,
            seedProjects = listOf(Project(id = "p1", title = "Virlin", createdAt = t0, updatedAt = t0))
        )
        return repo to DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }
    private suspend fun DefaultVirlinActions.task(title: String, ws: String? = "ws1", parent: String? = null): Task =
        (createTask(CreateTask(title = title, workStreamId = ws, parentTaskId = parent)) as ActionResult.Success).value
    private suspend fun DefaultVirlinActions.next(ws: String) = (nextTaskCandidate(ws) as ActionResult.Success).value

    // ------------------------------------------------------------------ NEXT traversal (§39)

    /**
     * ws1
     *  ├── Opening ✓
     *  ├── Implement
     *  │    ├── Listening ✓
     *  │    └── Speaking
     *  └── Test
     */
    private suspend fun DefaultVirlinActions.seedTree(): Map<String, Task> {
        val opening = task("Opening")
        val implement = task("Implement")
        val listening = task("Listening", parent = implement.id)
        val speaking = task("Speaking", parent = implement.id)
        val test = task("Test")
        return mapOf("opening" to opening, "implement" to implement, "listening" to listening, "speaking" to speaking, "test" to test)
    }

    @Test fun next_isTheFirstOpenLeafInDepthFirstSiblingOrder() = runBlocking {
        val (_, actions) = harness(listOf(stream("ws1")))
        val t = actions.seedTree()
        assertEquals("Opening", actions.next("ws1")?.title)                       // nothing done yet
        actions.completeTask(t["opening"]!!.id)
        assertEquals("Listening", actions.next("ws1")?.title)                     // descends into the parent
        actions.completeTask(t["listening"]!!.id)
        assertEquals("Speaking", actions.next("ws1")?.title)                      // next sibling inside the subtree
        actions.completeTask(t["speaking"]!!.id)
        assertEquals("Test", actions.next("ws1")?.title)                          // end of subtree → next top-level branch
    }

    @Test fun next_skipsTerminalItems_andParentsAreNeverCandidates() = runBlocking {
        val (_, actions) = harness(listOf(stream("ws1")))
        val t = actions.seedTree()
        actions.completeTask(t["opening"]!!.id)
        actions.cancelTask(t["listening"]!!.id)                                    // cancelled is terminal, not done
        assertEquals("Speaking", actions.next("ws1")?.title)
        // "Implement" has children, so it is a container and can never be the NEXT candidate.
        assertTrue(actions.next("ws1")?.id != t["implement"]!!.id)
    }

    @Test fun next_isNullWhenNothingIsOpen_andSurvivesRenaming() = runBlocking {
        val (repo, actions) = harness(listOf(stream("ws1")))
        val t = actions.seedTree()
        listOf("opening", "listening", "speaking", "test").forEach { actions.completeTask(t[it]!!.id) }
        assertNull(actions.next("ws1"))
        // Reopening is by stable id; a rename never breaks the relationship or the traversal.
        actions.updateTask(t["speaking"]!!.id, com.virlin.app.domain.action.TaskUpdate(title = com.virlin.app.domain.action.Field.Set("Speaking state")))
        assertEquals(t["implement"]!!.id, repo.getTask(t["speaking"]!!.id)!!.parentTaskId)
    }

    @Test fun next_unknownStreamIsSafe() = runBlocking {
        val (_, actions) = harness(listOf(stream("ws1")))
        assertTrue(actions.nextTaskCandidate("nope") is ActionResult.NotFound)
    }

    // ------------------------------------------------------------------ the focus target is an id (§30)

    @Test fun focusTargetIsAStableWorkItemId_notATitleOrPosition() = runBlocking {
        val (repo, actions) = harness(listOf(stream("ws1")))
        val t = actions.seedTree()
        actions.setActiveTask("ws1", t["speaking"]!!.id)
        actions.updateTask(t["speaking"]!!.id, com.virlin.app.domain.action.TaskUpdate(title = com.virlin.app.domain.action.Field.Set("Speaking (revised)")))
        assertEquals(t["speaking"]!!.id, repo.getStream("ws1")!!.activeTaskId)     // rename does not break focus
        // `activePath` is leaf-first (self → root); the UI reverses it for breadcrumbs.
        val path = (actions.activePath("ws1") as ActionResult.Success).value.map { it.title }
        assertEquals(listOf("Speaking (revised)", "Implement"), path)              // and the path still resolves
    }

    // ------------------------------------------------------------------ large hierarchy (§46)

    @Test fun largeHierarchy_isProjectedInOnePass_withCorrectRollUp() = runBlocking {
        val streams = (1..10).map { stream("ws$it") }
        val (repo, actions) = harness(streams)
        // 10 streams × (2 roots × (1 parent with 4 children + 1 leaf)) = 120 work items, depth 3.
        var completed = 0
        streams.forEach { ws ->
            repeat(2) { r ->
                val root = actions.task("root-$r", ws = ws.id)
                repeat(4) { c ->
                    val child = actions.task("child-$r-$c", ws = ws.id, parent = root.id)
                    if (c == 0) { actions.completeTask(child.id); completed++ }
                }
                actions.task("leaf-$r", ws = ws.id)
            }
        }
        val tasks = repo.tasks.value
        assertEquals(10 * 2 * 6, tasks.size)                                        // 120 items
        // Leaves = 4 children + 1 standalone leaf per root → 5 × 2 × 10 = 100; 20 complete.
        val project = ProgressCalculator.ofProject(tasks, repo.streams.value, "p1") as ProgressResult.Structured
        assertEquals(100, project.totalLeaves)
        assertEquals(completed, project.completedLeaves)
        assertEquals(0.2, project.fraction, 0.0001)
        // Per-stream roll-up uses that stream's leaves only.
        val one = ProgressCalculator.ofWorkStream(tasks, "ws3") as ProgressResult.Structured
        assertEquals(10, one.totalLeaves); assertEquals(2, one.completedLeaves)
        // The tree is derived from ONE in-memory snapshot — no per-row query, and it is fast.
        val start = System.nanoTime()
        val rows = com.virlin.app.ui.hierarchy.HierarchyPresentation.rows(
            tasks, "ws3", null, tasks.map { it.id }.toSet()
        )
        val millis = (System.nanoTime() - start) / 1_000_000
        assertEquals(12, rows.size)                                                 // every item of that stream, expanded
        assertTrue("projection took ${millis}ms", millis < 250)
        assertEquals(listOf(0, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 0), rows.map { it.depth })
    }

    @Test fun deepChain_hasNoDepthLimit_andOnlyTheLeafCounts() = runBlocking {
        val (repo, actions) = harness(listOf(stream("ws1")))
        var parent: String? = null
        val ids = (1..25).map { actions.task("level-$it", parent = parent).also { t -> parent = t.id }.id }
        val tasks = repo.tasks.value
        assertEquals(25, tasks.size)
        assertEquals(24, TaskHierarchy.ancestry(tasks, ids.last())!!.size - 1)      // 25-deep chain resolves
        val p = ProgressCalculator.ofWorkStream(tasks, "ws1") as ProgressResult.Structured
        assertEquals("only the deepest item is a leaf", 1, p.totalLeaves)
        actions.completeTask(ids.last())
        assertEquals(1, (ProgressCalculator.ofWorkStream(repo.tasks.value, "ws1") as ProgressResult.Structured).completedLeaves)
        // The parent chain is NOT auto-completed by its child.
        assertTrue(repo.tasks.value.filter { it.id != ids.last() }.none { it.status.isCompleted })
    }

    // ------------------------------------------------------------------ empty scopes (§24)

    @Test fun emptyProjectAndWorkStream_reportNoStructure_notZeroPercent() = runBlocking {
        val (repo, _) = harness(listOf(stream("ws1")))
        assertEquals(ProgressResult.Unstructured, ProgressCalculator.ofWorkStream(repo.tasks.value, "ws1"))
        val emptyProject = ProgressCalculator.ofProject(repo.tasks.value, emptyList(), "p1")
        assertEquals(ProgressResult.Unstructured, emptyProject)
        assertNotNull(emptyProject.toLabel().text)
        assertNull("no misleading 0%", emptyProject.toLabel().fraction)
    }
}
