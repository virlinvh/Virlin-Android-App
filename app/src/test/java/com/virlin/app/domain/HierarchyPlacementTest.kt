package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.structure.Placement
import com.virlin.app.domain.structure.PlacementCommit
import com.virlin.app.domain.structure.PlacementOperation
import com.virlin.app.domain.structure.inverseOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The structural placement command against the real repository.
 *
 * A move re-homes a whole branch and keeps its identity; a copy makes new records and takes
 * nothing that belonged to the original; an illegal placement changes nothing at all.
 */
class HierarchyPlacementTest {

    private val t0: Instant = Instant.parse("2026-09-28T09:00:00Z")
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var a: DefaultVirlinActions

    @Before fun setUp() {
        repo = InMemoryWorkStreamRepository(
            seed = listOf(
                WorkStream(id = "alpha", title = "Alpha", state = WorkStreamState.READY,
                    projectId = "p1", executionPreference = ExecutionPreference.HUMAN,
                    createdAt = t0, updatedAt = t0),
                WorkStream(id = "beta", title = "Beta", state = WorkStreamState.READY,
                    projectId = "p1", executionPreference = ExecutionPreference.HUMAN,
                    createdAt = t0, updatedAt = t0),
                WorkStream(id = "elsewhere", title = "Elsewhere", state = WorkStreamState.READY,
                    projectId = "p2", executionPreference = ExecutionPreference.HUMAN,
                    createdAt = t0, updatedAt = t0)
            ),
            seedProjects = listOf(
                Project("p1", "Main", createdAt = t0, updatedAt = t0),
                Project("p2", "Other", createdAt = t0, updatedAt = t0)
            )
        )
        a = DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider())
    }

    private suspend fun task(
        id: String, parent: String? = null, stream: String? = "alpha", project: String = "p1"
    ): Task = (a.createTask(
        CreateTask(title = id, projectId = project, workStreamId = stream, parentTaskId = parent, id = id)
    ) as ActionResult.Success).value

    private fun tasks() = repo.tasks.value
    private fun byId(id: String) = tasks().first { it.id == id }
    private fun childrenOf(parent: String?, stream: String?) = tasks()
        .filter { it.parentTaskId == parent && it.workStreamId == stream }
        .sortedWith(compareBy({ it.order }, { it.id })).map { it.id }

    private suspend fun place(
        source: String, target: String, before: String? = null,
        operation: PlacementOperation = PlacementOperation.MOVE,
        revision: Long? = null,
    ) = a.placeBranch(
        "p1",
        Placement(
            sourceId = source, targetParentId = target, insertBeforeId = before,
            operation = operation, expectedRevision = revision ?: a.hierarchyRevision("p1")
        )
    )

    private fun reason(result: ActionResult<*>): String =
        ((result as ActionResult.Rejected).reason as DomainError.PlacementRejected).reason

    // ------------------------------------------------------------------ cycles and illegal drops

    @Test fun aBranchCannotMoveBeneathItsOwnDescendant() = runTest {
        task("root"); task("child", parent = "root"); task("grandchild", parent = "child")
        val before = tasks().map { it.id to it.parentTaskId }.toSet()
        val result = place("root", "grandchild")
        assertTrue(reason(result).contains("beneath one of its own subtasks"))
        // Nothing moved.
        assertEquals(before, tasks().map { it.id to it.parentTaskId }.toSet())
    }

    @Test fun aBranchCannotBeDroppedOnItself() = runTest {
        task("root")
        assertTrue(reason(place("root", "root")).contains("on itself"))
    }

    @Test fun aWorkstreamCannotBecomeATaskByDropping() = runTest {
        task("target")
        val result = place("beta", "target")
        assertTrue(reason(result).contains("explicit conversion"))
        assertEquals("p1", byIdStream("beta").projectId)
    }

    private fun byIdStream(id: String) = repo.streams.value.first { it.id == id }

    @Test fun aNoOpPlacementIsRefusedRatherThanRewritten() = runTest {
        task("only")
        assertTrue(reason(place("only", "alpha")).contains("already there"))
    }

    // ------------------------------------------------------------------ move

    @Test fun aSubtreeMovesAcrossWorkstreamsKeepingEveryId() = runTest {
        task("root"); task("child", parent = "root"); task("grandchild", parent = "child")
        val result = place("root", "beta")
        assertTrue(result is ActionResult.Success)
        // Same ids, new home, all the way down.
        listOf("root", "child", "grandchild").forEach { id ->
            assertEquals("beta", byId(id).workStreamId)
        }
        assertNull(byId("root").parentTaskId)
        assertEquals("root", byId("child").parentTaskId)
        assertEquals("child", byId("grandchild").parentTaskId)
    }

    @Test fun movingKeepsStatusNotesAndExecutionPreference() = runTest {
        task("root")
        a.updateTask("root", com.virlin.app.domain.action.TaskUpdate(
            description = com.virlin.app.domain.action.Field.Set("keep me")
        ))
        a.completeTask("root")
        val before = byId("root")
        place("root", "beta")
        val after = byId("root")
        assertEquals(before.status, after.status)
        assertEquals(before.description, after.description)
        assertEquals(before.executionPreference, after.executionPreference)
        assertEquals(before.createdAt, after.createdAt)
    }

    @Test fun aTaskDroppedOnTheProjectRootBecomesStandalone() = runTest {
        task("root"); task("child", parent = "root")
        assertTrue(place("root", "p1") is ActionResult.Success)
        assertNull(byId("root").workStreamId)
        assertNull(byId("child").workStreamId)      // the descendant follows
        assertEquals("p1", byId("child").projectId)
    }

    @Test fun aStandaloneTaskIsPromotedIntoAWorkstream() = runTest {
        task("loose", stream = null)
        assertTrue(place("loose", "beta") is ActionResult.Success)
        assertEquals("beta", byId("loose").workStreamId)
    }

    @Test fun aTaskDroppedOnAnotherTaskBecomesItsSubtask() = runTest {
        task("one"); task("two")
        assertTrue(place("two", "one") is ActionResult.Success)
        assertEquals("one", byId("two").parentTaskId)
        assertEquals("alpha", byId("two").workStreamId)
    }

    @Test fun siblingsReorderAndRenumberFromZero() = runTest {
        task("a"); task("b"); task("c")
        assertTrue(place("c", "alpha", before = "a") is ActionResult.Success)
        assertEquals(listOf("c", "a", "b"), childrenOf(null, "alpha"))
        assertEquals(listOf(0, 1, 2), childrenOf(null, "alpha").map { byId(it).order })
    }

    @Test fun anInsertionPointMustBeAChildOfTheDestination() = runTest {
        task("a"); task("b"); task("far", stream = "beta")
        assertTrue(reason(place("b", "alpha", before = "far")).contains("child of the destination"))
    }

    @Test fun movingAwayClearsAStreamsActiveTaskPointer() = runTest {
        task("root")
        a.setActiveTask("alpha", "root")
        assertEquals("root", repo.streams.value.first { it.id == "alpha" }.activeTaskId)
        place("root", "beta")
        assertNull(repo.streams.value.first { it.id == "alpha" }.activeTaskId)
    }

    // ------------------------------------------------------------------ copy

    @Test fun aCopyGetsNewIdsAndLeavesTheOriginalAlone() = runTest {
        task("root"); task("child", parent = "root")
        val result = place("root", "beta", operation = PlacementOperation.COPY)
        val commit = (result as ActionResult.Success).value
        assertEquals(2, commit.clonedIds.size)
        commit.clonedIds.forEach { (old, new) -> assertNotEquals(old, new) }
        // The original is untouched.
        assertEquals("alpha", byId("root").workStreamId)
        assertEquals("root", byId("child").parentTaskId)
        // The copy mirrors the shape in its new home.
        val copiedRoot = byId(commit.clonedIds.getValue("root"))
        val copiedChild = byId(commit.clonedIds.getValue("child"))
        assertEquals("beta", copiedRoot.workStreamId)
        assertEquals(copiedRoot.id, copiedChild.parentTaskId)
    }

    @Test fun aCopyDoesNotInheritCompletion() = runTest {
        task("root"); task("child", parent = "root")
        a.completeTask("child")
        assertEquals(TaskStatus.DONE, byId("child").status)
        val commit = (place("root", "beta", operation = PlacementOperation.COPY)
            as ActionResult.Success).value
        assertEquals(TaskStatus.TODO, byId(commit.clonedIds.getValue("child")).status)
        assertNull(byId(commit.clonedIds.getValue("child")).completedAt)
        // ... and the original still is done.
        assertEquals(TaskStatus.DONE, byId("child").status)
    }

    @Test fun onlyATaskBranchCanBeCopied() = runTest {
        task("anything")
        val result = place("beta", "p1", operation = PlacementOperation.COPY)
        assertTrue(reason(result).contains("Only a task branch"))
    }

    // ------------------------------------------------------------------ revision and undo

    @Test fun aStaleRevisionIsRefusedAndNothingIsWritten() = runTest {
        task("root"); task("other")
        val stale = a.hierarchyRevision("p1")
        // Someone else edits the hierarchy in between.
        place("other", "root")
        val before = tasks().map { it.id to it.parentTaskId }.toSet()
        val result = place("root", "beta", revision = stale)
        assertTrue(reason(result).contains("changed while you were editing"))
        assertEquals(before, tasks().map { it.id to it.parentTaskId }.toSet())
    }

    @Test fun theRevisionChangesOnlyWhenTheHierarchyDoes() = runTest {
        task("root")
        val first = a.hierarchyRevision("p1")
        a.updateTask("root", com.virlin.app.domain.action.TaskUpdate(
            description = com.virlin.app.domain.action.Field.Set("a note")
        ))
        assertEquals("editing a note is not a structural change", first, a.hierarchyRevision("p1"))
        task("second")
        assertNotEquals(first, a.hierarchyRevision("p1"))
    }

    @Test fun undoingAMovePutsTheBranchBackExactlyWhereItWas() = runTest {
        task("a"); task("b"); task("c")
        val commit = (place("c", "beta") as ActionResult.Success).value
        assertEquals("beta", byId("c").workStreamId)

        val inverse = inverseOf(commit, "p1", a.hierarchyRevision("p1"))!!
        assertTrue(a.placeBranch("p1", inverse) is ActionResult.Success)
        assertEquals("alpha", byId("c").workStreamId)
        assertEquals(listOf("a", "b", "c"), childrenOf(null, "alpha"))
    }

    @Test fun undoIsNotOfferedForACopyBecauseThisAppNeverDeletes() = runTest {
        task("root")
        val commit = (place("root", "beta", operation = PlacementOperation.COPY)
            as ActionResult.Success).value
        assertNull(inverseOf(commit, "p1", a.hierarchyRevision("p1")))
    }

    @Test fun anUndoIsRefusedIfTheHierarchyChangedAfterTheMove() = runTest {
        task("a"); task("b")
        val commit = (place("b", "beta") as ActionResult.Success).value
        val revision = a.hierarchyRevision("p1")
        // Another edit lands first.
        task("c")
        val inverse = inverseOf(commit, "p1", revision)!!
        val result = a.placeBranch("p1", inverse)
        assertTrue(reason(result).contains("changed while you were editing"))
        assertEquals("beta", byId("b").workStreamId)
    }

    // ------------------------------------------------------------------ project boundary

    @Test fun aBranchCannotCrossIntoAnotherProject() = runTest {
        task("root")
        val result = a.placeBranch("p1", Placement(
            sourceId = "root", targetParentId = "elsewhere",
            expectedRevision = a.hierarchyRevision("p1")
        ))
        // The other project's stream is not even in this project's node list.
        assertTrue(reason(result).contains("no longer exists"))
        assertEquals("alpha", byId("root").workStreamId)
    }

    @Test fun deepChainsMoveWithoutRecursion() = runTest {
        // 300 levels: the traversal must be iterative.
        task("n0")
        (1 until 300).forEach { task("n$it", parent = "n${it - 1}") }
        assertTrue(place("n0", "beta") is ActionResult.Success)
        assertEquals("beta", byId("n299").workStreamId)
        assertEquals("n298", byId("n299").parentTaskId)
    }

    @Test fun aCommitNamesEveryAffectedNode() = runTest {
        task("root"); task("child", parent = "root"); task("grandchild", parent = "child")
        val commit: PlacementCommit = (place("root", "beta") as ActionResult.Success).value
        assertEquals(setOf("root", "child", "grandchild"), commit.affectedIds.toSet())
        assertEquals("root", commit.affectedIds.first())        // parent-first
        assertEquals("alpha", commit.previousWorkStreamId)
        assertEquals("beta", commit.targetParentId)
    }
}
