package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.ClearedSteps
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * A task's to-do steps: reordering by a stable anchor, and clearing completed ones reversibly.
 *
 * The anchor matters. An index picked in the UI describes the list as it looked when the finger
 * went down; resolving "put it in front of THIS row" inside the transaction is what makes the
 * step land where the user pointed even if the list moved underneath.
 */
class TaskStepOrderingTest {

    private val t0: Instant = Instant.parse("2026-09-29T09:00:00Z")
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var a: DefaultVirlinActions

    @Before fun setUp() {
        repo = InMemoryWorkStreamRepository(
            seed = listOf(
                WorkStream(
                    id = "ws", title = "Stream", projectId = "p1", state = WorkStreamState.READY,
                    executionPreference = ExecutionPreference.HUMAN, createdAt = t0, updatedAt = t0
                )
            ),
            seedProjects = listOf(Project("p1", "Main", createdAt = t0, updatedAt = t0))
        )
        a = DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider())
    }

    private suspend fun task(id: String = "t1") = (a.createTask(
        CreateTask(title = id, projectId = "p1", workStreamId = "ws", id = id)
    ) as ActionResult.Success).value

    private suspend fun step(taskId: String, text: String) =
        (a.addStep(taskId, text) as ActionResult.Success).value

    /** The list as the user sees it, with the stored numbering checked for contiguity. */
    private fun steps(taskId: String = "t1"): List<String> {
        val group = repo.taskSteps.value.filter { it.taskId == taskId }
            .sortedWith(compareBy({ it.order }, { it.id }))
        assertEquals("step order must be contiguous from zero", group.indices.toList(), group.map { it.order })
        return group.map { it.text }
    }

    private suspend fun four(): List<String> {
        task()
        val ids = listOf("a", "b", "c", "d").map { step("t1", it).id }
        assertEquals(listOf("a", "b", "c", "d"), steps())
        return ids
    }

    private fun idOf(text: String, taskId: String = "t1") =
        repo.taskSteps.value.first { it.taskId == taskId && it.text == text }.id

    // ------------------------------------------------------------------ anchored reorder

    @Test fun addingAlwaysAppends() = runTest {
        four()
        assertEquals(listOf("a", "b", "c", "d"), steps())
    }

    @Test fun lastMovesToFirst() = runTest {
        four()
        a.moveStepBefore(idOf("d"), idOf("a"))
        assertEquals(listOf("d", "a", "b", "c"), steps())
    }

    @Test fun firstMovesToLast() = runTest {
        four()
        // A null anchor means "the end", which is a real position, not a refusal.
        a.moveStepBefore(idOf("a"), null)
        assertEquals(listOf("b", "c", "d", "a"), steps())
    }

    @Test fun middleMovesToMiddle() = runTest {
        four()
        a.moveStepBefore(idOf("b"), idOf("d"))
        assertEquals(listOf("a", "c", "b", "d"), steps())
    }

    /** Moving DOWN by one: the source must leave the list before the anchor index is taken. */
    @Test fun movingDownwardByOneIsNotOffByOne() = runTest {
        four()
        a.moveStepBefore(idOf("a"), idOf("c"))
        assertEquals(listOf("b", "a", "c", "d"), steps())
    }

    @Test fun droppingAStepWhereItAlreadySitsWritesNothing() = runTest {
        four()
        val before = repo.taskSteps.value.associate { it.id to (it.order to it.updatedAt) }
        // In front of the row that already follows it, and onto its own position.
        a.moveStepBefore(idOf("b"), idOf("c"))
        a.moveStepBefore(idOf("d"), null)
        assertEquals(listOf("a", "b", "c", "d"), steps())
        assertEquals(before, repo.taskSteps.value.associate { it.id to (it.order to it.updatedAt) })
    }

    @Test fun anAnchorThatNoLongerExistsIsRefused() = runTest {
        four()
        val result = a.moveStepBefore(idOf("a"), "step-that-never-existed")
        assertEquals(ActionResult.NotFound::class, result::class)
        assertEquals(listOf("a", "b", "c", "d"), steps())
    }

    @Test fun completedRowsReorderLikeAnyOther() = runTest {
        four()
        a.setStepDone(idOf("b"), true)
        a.moveStepBefore(idOf("b"), idOf("a"))
        assertEquals(listOf("b", "a", "c", "d"), steps())
        // Completion is untouched by a move, and the row did not jump on being ticked.
        assertTrue(repo.taskSteps.value.first { it.text == "b" }.done)
    }

    @Test fun oneTaskSStepsAreNeverTouchedByAnother() = runTest {
        four()
        task("t2"); step("t2", "x"); step("t2", "y")
        a.moveStepBefore(idOf("d"), idOf("a"))
        assertEquals(listOf("x", "y"), steps("t2"))
    }

    // ------------------------------------------------------------------ clear + restore

    @Test fun clearingRemovesOnlyCompletedStepsAndRenumbersTheRest() = runTest {
        four()
        a.setStepDone(idOf("b"), true)
        a.setStepDone(idOf("d"), true)
        val cleared = (a.clearCompletedSteps("t1") as ActionResult.Success).value
        assertEquals(listOf("a", "c"), steps())
        assertEquals(listOf("b", "d"), cleared.removed.map { it.text })
    }

    @Test fun undoRestoresIdsTextDoneAndExactPositions() = runTest {
        four()
        a.setStepDone(idOf("b"), true)
        a.setStepDone(idOf("d"), true)
        val before = repo.taskSteps.value.filter { it.taskId == "t1" }
            .sortedBy { it.order }.map { Triple(it.id, it.text, it.done) }

        val cleared = (a.clearCompletedSteps("t1") as ActionResult.Success).value
        assertEquals(listOf("a", "c"), steps())

        val restored = a.restoreSteps(cleared)
        assertEquals(ActionResult.Success::class, restored::class)
        val after = repo.taskSteps.value.filter { it.taskId == "t1" }
            .sortedBy { it.order }.map { Triple(it.id, it.text, it.done) }
        assertEquals(before, after)
    }

    /** A restore that could not be faithful is refused rather than approximated. */
    @Test fun undoIsRefusedWhenTheListChangedAfterTheClear() = runTest {
        four()
        a.setStepDone(idOf("b"), true)
        val cleared = (a.clearCompletedSteps("t1") as ActionResult.Success).value
        assertEquals(listOf("a", "c", "d"), steps())

        // Someone adds a row before pressing Undo.
        step("t1", "e")
        val result = a.restoreSteps(cleared)
        assertEquals(ActionResult.Rejected::class, result::class)
        assertEquals(listOf("a", "c", "d", "e"), steps())
    }

    @Test fun clearingWithNothingCompletedChangesNothing() = runTest {
        four()
        val before = repo.taskSteps.value.associate { it.id to it.order }
        val cleared = (a.clearCompletedSteps("t1") as ActionResult.Success).value
        assertTrue(cleared.removed.isEmpty())
        assertEquals(before, repo.taskSteps.value.associate { it.id to it.order })
    }

    @Test fun clearingOneTaskLeavesAnotherTasksStepsAlone() = runTest {
        four()
        task("t2"); step("t2", "x")
        a.setStepDone(idOf("x", "t2"), true)
        a.setStepDone(idOf("b"), true)
        a.clearCompletedSteps("t1")
        assertEquals(listOf("x"), steps("t2"))
    }
}
