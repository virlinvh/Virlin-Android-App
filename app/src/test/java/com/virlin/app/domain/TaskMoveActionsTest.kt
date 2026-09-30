package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.getOrNull
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Moving and duplicating tasks inside a stream. A move changes where a task sits and nothing
 * else: same ids, same children, same current marker. A duplicate is a separate record.
 */
class TaskMoveActionsTest {

    private val t0: Instant = Instant.parse("2026-09-27T09:00:00Z")
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var a: DefaultVirlinActions

    @Before fun setUp() {
        repo = InMemoryWorkStreamRepository(
            seed = listOf(
                WorkStream(id = "psych", title = "Psychology", state = WorkStreamState.READY,
                    projectId = "p1", executionPreference = ExecutionPreference.HUMAN,
                    createdAt = t0, updatedAt = t0),
                WorkStream(id = "other", title = "Other stream", state = WorkStreamState.READY,
                    projectId = "p1", executionPreference = ExecutionPreference.HUMAN,
                    createdAt = t0, updatedAt = t0)
            ),
            seedProjects = listOf(Project("p1", "Study", createdAt = t0, updatedAt = t0))
        )
        a = DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider())
    }

    private suspend fun task(id: String, parent: String? = null, stream: String = "psych") =
        (a.createTask(CreateTask(title = id, projectId = "p1", workStreamId = stream, parentTaskId = parent, id = id))
            as ActionResult.Success).value

    private fun childrenOf(parent: String?) =
        repo.tasks.value.filter { it.parentTaskId == parent }
            .sortedWith(compareBy({ it.order }, { it.id })).map { it.id }

    @Test fun movingReordersSiblings_andRenumbersThemFromZero() = runTest {
        task("read"); task("notes"); task("answer")
        a.moveTasks(listOf("answer"), null, null, "psych")
        assertEquals(listOf("answer", "read", "notes"), childrenOf(null))
        assertEquals(listOf(0, 1, 2), repo.tasks.value.sortedBy { it.order }.map { it.order })

        a.moveTasks(listOf("answer"), null, "read", "psych")
        assertEquals(listOf("read", "answer", "notes"), childrenOf(null))
    }

    @Test fun aBranchKeepsItsChildrenAndIdsWhenItMoves() = runTest {
        task("answer"); task("q11to20", parent = "answer"); task("q17", parent = "q11to20")
        task("read")

        val moved = a.moveTasks(listOf("q11to20"), "read", null, "psych").getOrNull()!!
        assertEquals("q11to20", moved.single().id)
        assertEquals("read", moved.single().parentTaskId)
        // The grandchild travelled with it and kept its own id and parent.
        assertEquals(listOf("q17"), childrenOf("q11to20"))
        assertEquals("q11to20", repo.getTask("q17")!!.parentTaskId)
    }

    @Test fun aTaskCannotMoveIntoItselfOrItsOwnSubtree() = runTest {
        task("answer"); task("q11to20", parent = "answer")

        assertEquals(DomainError.CyclicParent,
            (a.moveTasks(listOf("answer"), "answer", null, "psych") as ActionResult.Rejected).reason)
        assertEquals(DomainError.CyclicParent,
            (a.moveTasks(listOf("answer"), "q11to20", null, "psych") as ActionResult.Rejected).reason)
        // Nothing moved.
        assertEquals(null, repo.getTask("answer")!!.parentTaskId)
    }

    @Test fun aTaskFromAnotherStreamIsRejected_andNothingIsMoved() = runTest {
        task("read"); task("foreign", stream = "other")
        assertEquals(DomainError.OwnershipMismatch,
            (a.moveTasks(listOf("read", "foreign"), null, null, "psych") as ActionResult.Rejected).reason)
        assertEquals("other", repo.getTask("foreign")!!.workStreamId)
    }

    @Test fun movingKeepsTheCurrentMarkerAndCompletionState() = runTest {
        task("answer"); task("q11to20", parent = "answer"); task("q17", parent = "q11to20")
        task("read")
        a.setActiveTask("psych", "q17")
        a.completeTask("read")

        a.moveTasks(listOf("q11to20"), null, null, "psych")
        assertEquals("q17", repo.getStream("psych")!!.activeTaskId)
        assertEquals(TaskStatus.DONE, repo.getTask("read")!!.status)

        // Progress is recomputed from the tree, not carried along: "answer" has no children
        // now, so it counts as an executable leaf itself. That is the existing progress rule,
        // not something the move invents.
        val after = ProgressCalculator.ofWorkStream(repo.tasks.value, "psych")
            as com.virlin.app.domain.model.ProgressResult.Structured
        assertEquals(3, after.totalLeaves)
        assertEquals(1, after.completedLeaves)
    }

    @Test fun duplicateIsASeparateRecordBesideTheOriginal() = runTest {
        task("answer"); task("q11to20", parent = "answer"); task("q17", parent = "q11to20")
        a.completeTask("q17")

        val copy = a.duplicateTask("answer").getOrNull()!!
        assertNotEquals("answer", copy.id)
        assertEquals("answer (copy)", copy.title)
        assertEquals(null, copy.parentTaskId)
        // The subtree was copied, not shared: new ids, and the copy starts open.
        val copiedChild = repo.tasks.value.single { it.parentTaskId == copy.id }
        assertNotEquals("q11to20", copiedChild.id)
        val copiedGrandchild = repo.tasks.value.single { it.parentTaskId == copiedChild.id }
        assertEquals(TaskStatus.TODO, copiedGrandchild.status)
        // The original is untouched.
        assertEquals(TaskStatus.DONE, repo.getTask("q17")!!.status)
        assertTrue(repo.getTask("q11to20") != null)
    }
}
