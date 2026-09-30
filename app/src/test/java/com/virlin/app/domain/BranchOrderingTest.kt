package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.ui.map.dropNodes
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.structure.Placement
import com.virlin.app.domain.structure.PlacementCommit
import com.virlin.app.domain.structure.PlacementOperation
import com.virlin.app.domain.structure.inverseOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Precise sibling ordering through the placement command.
 *
 * Ordering is a DOMAIN fact: the caller names the destination parent and the sibling to land in
 * front of, and the command decides the numbers. These tests pin the positions a user can ask
 * for — top, bottom and anywhere between — and the one that historically goes wrong: moving an
 * item DOWNWARD past its own old slot, where forgetting to remove the source from the sibling
 * list first lands it one place short.
 *
 * Every case asserts the persisted `order` values are contiguous from zero, because a gap or a
 * duplicate would leave the next insertion ambiguous even when this one looked right.
 */
class BranchOrderingTest {

    private val t0: Instant = Instant.parse("2026-09-28T09:00:00Z")
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var a: DefaultVirlinActions

    @Before fun setUp() {
        repo = InMemoryWorkStreamRepository(
            seed = listOf(
                WorkStream(id = "alpha", title = "Alpha", state = WorkStreamState.READY,
                    projectId = "p1", executionPreference = ExecutionPreference.HUMAN,
                    sortOrder = 0, createdAt = t0, updatedAt = t0),
                WorkStream(id = "beta", title = "Beta", state = WorkStreamState.READY,
                    projectId = "p1", executionPreference = ExecutionPreference.HUMAN,
                    sortOrder = 1, createdAt = t0, updatedAt = t0)
            ),
            seedProjects = listOf(Project("p1", "Main", createdAt = t0, updatedAt = t0))
        )
        a = DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider())
    }

    private suspend fun task(id: String, parent: String? = null, stream: String? = "alpha"): Task =
        (a.createTask(
            CreateTask(title = id, projectId = "p1", workStreamId = stream, parentTaskId = parent, id = id)
        ) as ActionResult.Success).value

    private fun tasks() = repo.tasks.value

    /** The sibling group as the user sees it, plus proof the stored numbers are 0..n-1. */
    private fun siblings(parent: String?, stream: String? = "alpha"): List<String> {
        val group = tasks()
            .filter { it.parentTaskId == parent && it.workStreamId == stream }
            .sortedWith(compareBy({ it.order }, { it.id }))
        assertEquals(
            "order must be contiguous from zero",
            group.indices.toList(), group.map { it.order }
        )
        return group.map { it.id }
    }

    private suspend fun place(source: String, target: String, before: String?) = a.placeBranch(
        "p1",
        Placement(
            sourceId = source, targetParentId = target, insertBeforeId = before,
            operation = PlacementOperation.MOVE, expectedRevision = a.hierarchyRevision("p1")
        )
    )

    private suspend fun four(): List<String> {
        task("root")
        listOf("w", "x", "y", "z").forEach { task(it, parent = "root") }
        assertEquals(listOf("w", "x", "y", "z"), siblings("root"))
        return listOf("w", "x", "y", "z")
    }

    // ------------------------------------------------------------------ same parent

    @Test fun lastMovesToFirst() = runTest {
        four()
        place("z", "root", before = "w")
        assertEquals(listOf("z", "w", "x", "y"), siblings("root"))
    }

    @Test fun firstMovesToLast() = runTest {
        four()
        // Null means "append": the end of the group is a position like any other.
        place("w", "root", before = null)
        assertEquals(listOf("x", "y", "z", "w"), siblings("root"))
    }

    @Test fun middleMovesToMiddle() = runTest {
        four()
        place("x", "root", before = "z")
        assertEquals(listOf("w", "y", "x", "z"), siblings("root"))
    }

    /**
     * The off-by-one case. Moving `w` in front of `y` must put it BETWEEN x and y. If the source
     * is still in the sibling list when the insertion index is taken, `y` is found at index 2
     * instead of 1 and `w` lands after `y` instead of before it.
     */
    @Test fun movingDownwardByOnePositionIsNotOffByOne() = runTest {
        four()
        place("w", "root", before = "y")
        assertEquals(listOf("x", "w", "y", "z"), siblings("root"))
    }

    @Test fun movingUpwardByOnePosition() = runTest {
        four()
        place("y", "root", before = "x")
        assertEquals(listOf("w", "y", "x", "z"), siblings("root"))
    }

    // ------------------------------------------------------------------ branches travel whole

    @Test fun aReorderedBranchKeepsItsDescendantsAndTheirOrder() = runTest {
        four()
        listOf("k1", "k2", "k3").forEach { task(it, parent = "y") }
        assertEquals(listOf("k1", "k2", "k3"), siblings("y"))
        place("y", "root", before = "w")
        assertEquals(listOf("y", "w", "x", "z"), siblings("root"))
        // The subtree is untouched: same children, same order, same parent.
        assertEquals(listOf("k1", "k2", "k3"), siblings("y"))
    }

    // ------------------------------------------------------------------ cross parent

    @Test fun aBranchIsInsertedAtAnExactPositionUnderAnotherTask() = runTest {
        four()
        task("host"); listOf("h1", "h2").forEach { task(it, parent = "host") }
        place("x", "host", before = "h2")
        assertEquals(listOf("h1", "x", "h2"), siblings("host"))
        // and it left a contiguous group behind it
        assertEquals(listOf("w", "y", "z"), siblings("root"))
        assertEquals("host", tasks().first { it.id == "x" }.parentTaskId)
    }

    @Test fun aBranchIsInsertedFirstAndLastInAnotherWorkstream() = runTest {
        four()
        task("b1", stream = "beta"); task("b2", stream = "beta")
        place("w", "beta", before = "b1")
        assertEquals(listOf("w", "b1", "b2"), siblings(null, stream = "beta"))
        place("x", "beta", before = null)
        assertEquals(listOf("w", "b1", "b2", "x"), siblings(null, stream = "beta"))
        // Both left the old stream entirely.
        assertEquals(listOf("y", "z"), siblings("root"))
        listOf("w", "x").forEach { assertEquals("beta", tasks().first { t -> t.id == it }.workStreamId) }
    }

    @Test fun aCrossParentMoveCarriesDescendantsIntoTheNewStream() = runTest {
        four()
        task("k", parent = "y")
        place("y", "beta", before = null)
        assertEquals("beta", tasks().first { it.id == "k" }.workStreamId)
        assertEquals("y", tasks().first { it.id == "k" }.parentTaskId)
    }

    // ------------------------------------------------------------------ undo

    @Test fun undoRestoresBothLocationAndExactSiblingPosition() = runTest {
        four()
        val commit = (place("x", "root", before = null) as ActionResult.Success).value
        assertEquals(listOf("w", "y", "z", "x"), siblings("root"))
        undo(commit)
        assertEquals(listOf("w", "x", "y", "z"), siblings("root"))
    }

    @Test fun undoAfterACrossParentInsertionRestoresThePreviousSlot() = runTest {
        four()
        // `host` is a top-level task of the stream, so it is a sibling of `root`, not of w..z.
        task("host")
        val commit = (place("x", "host", before = null) as ActionResult.Success).value
        assertEquals(listOf("x"), siblings("host"))
        assertEquals(listOf("w", "y", "z"), siblings("root"))
        undo(commit)
        assertEquals(listOf("w", "x", "y", "z"), siblings("root"))
        assertEquals("root", tasks().first { it.id == "x" }.parentTaskId)
        assertEquals(emptyList<String>(), siblings("host"))
    }

    // ------------------------------------------------------------------ reads never write

    /** Every task's identity, position and last-modified stamp: the things a read must not touch. */
    private fun fingerprint() = tasks()
        .associate { it.id to listOf(it.parentTaskId, it.workStreamId, it.order, it.updatedAt) }

    /**
     * Opening a project, computing its revision and drawing its map are READS. None of them may
     * write a sibling order.
     *
     * This exists because five real task orders in a live database changed without any drop being
     * committed, and "it must have been startup" is not something to assume. A read path that
     * renumbers would corrupt a user's arrangement simply by looking at it, so the invariant is
     * pinned here rather than argued about.
     */
    @Test fun readingAndDrawingTheHierarchyNeverChangesAnyOrder() = runTest {
        four()
        listOf("k1", "k2", "k3").forEach { task(it, parent = "y") }
        seedThirdStream()
        val before = fingerprint()

        // The reads a project screen and a map perform on open, several times over.
        repeat(3) {
            a.hierarchyRevision("p1")
            val topics = com.virlin.app.ui.map.projectMapTopics(
                repo.projects.value.first { p -> p.id == "p1" },
                repo.streams.value,
                tasks(),
            )
            val geometry = com.virlin.app.ui.map.arrange(
                topics, com.virlin.app.ui.map.MapLayout.RIGHT_TREE
            )
            // And the drop planner, hovering everywhere without ever releasing.
            val byId = topics.associateBy { t -> t.id }
            val nodes = geometry.dropNodes(byId, density = 2.625f, zoom = 1f, offsetX = 0f, offsetY = 0f)
            nodes.forEach { node ->
                com.virlin.app.ui.map.planMapDrop(
                    pointer = node.bounds.center, sourceId = "x", nodes = nodes,
                    canPlaceInto = { _, _ -> true }, canPlaceBeside = { _, _ -> true },
                    density = 2.625f,
                )
            }
        }

        assertEquals(before, fingerprint())
        // Not one event either: a read is not history.
        assertEquals(0, repo.getEvents("alpha").count { e -> e.at > t0 })
    }

    // ------------------------------------------------------------------ conflict rollback

    /**
     * A stale revision must roll the WHOLE placement back, ordering included.
     *
     * Order is the easy thing to leak here: the renumbering touches several rows, so a command
     * that bailed out halfway could leave a sibling group partly renumbered even though the
     * branch itself never moved. Validation happens inside the transaction and before any write,
     * so the group must come back byte-identical.
     */
    @Test fun aStaleRevisionChangesNeitherLocationNorOrder() = runTest {
        four()
        val stale = a.hierarchyRevision("p1")
        // Someone else reorders first, which moves the revision on.
        place("z", "root", before = "w")
        assertEquals(listOf("z", "w", "x", "y"), siblings("root"))
        val orderBefore = tasks().associate { it.id to (it.order to it.parentTaskId) }

        val result = a.placeBranch(
            "p1",
            Placement(
                sourceId = "y", targetParentId = "root", insertBeforeId = "w",
                operation = PlacementOperation.MOVE, expectedRevision = stale
            )
        )

        assertEquals(ActionResult.Rejected::class, result::class)
        // Nothing moved and nothing was renumbered.
        assertEquals(listOf("z", "w", "x", "y"), siblings("root"))
        assertEquals(orderBefore, tasks().associate { it.id to (it.order to it.parentTaskId) })
    }

    /** The same guarantee for a refusal that is about the shape of the tree, not the revision. */
    @Test fun anIllegalInsertionPointChangesNothing() = runTest {
        four()
        task("kid", parent = "y")
        val orderBefore = tasks().associate { it.id to (it.order to it.parentTaskId) }
        // An insertion point inside the branch being moved is not a position it can take.
        val result = a.placeBranch(
            "p1",
            Placement(
                sourceId = "y", targetParentId = "y", insertBeforeId = "kid",
                operation = PlacementOperation.MOVE, expectedRevision = a.hierarchyRevision("p1")
            )
        )
        assertEquals(ActionResult.Rejected::class, result::class)
        assertEquals(orderBefore, tasks().associate { it.id to (it.order to it.parentTaskId) })
    }

    // ------------------------------------------------------------------ workstream order (v14)

    /** The project's workstreams as the user arranged them, with the numbering checked. */
    private fun streams(): List<String> {
        val group = repo.streams.value.filter { it.projectId == "p1" }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        assertEquals(
            "workstream order must be contiguous from zero",
            group.indices.toList(), group.map { it.sortOrder }
        )
        return group.map { it.id }
    }

    private suspend fun seedThirdStream() {
        (a.createWorkStream(
            com.virlin.app.domain.action.CreateWorkStream(
                title = "Gamma", projectId = "p1",
                executionPreference = ExecutionPreference.HUMAN, id = "gamma"
            )
        ) as ActionResult.Success).value
    }

    @Test fun aWorkstreamIsReorderedUnderTheProjectRoot() = runTest {
        seedThirdStream()
        assertEquals(listOf("alpha", "beta", "gamma"), streams())
        place("gamma", "p1", before = "alpha")
        assertEquals(listOf("gamma", "alpha", "beta"), streams())
    }

    @Test fun aWorkstreamMovesToTheEndOfItsOwnGroup() = runTest {
        seedThirdStream()
        place("alpha", "p1", before = null)
        assertEquals(listOf("beta", "gamma", "alpha"), streams())
    }

    /** The same downward off-by-one that catches tasks out, on the stream group. */
    @Test fun aWorkstreamMovingDownwardByOnePositionIsNotOffByOne() = runTest {
        seedThirdStream()
        place("alpha", "p1", before = "gamma")
        assertEquals(listOf("beta", "alpha", "gamma"), streams())
    }

    @Test fun aNewWorkstreamJoinsTheEndRatherThanTheMiddle() = runTest {
        seedThirdStream()
        place("gamma", "p1", before = "alpha")
        assertEquals(listOf("gamma", "alpha", "beta"), streams())
        (a.createWorkStream(
            com.virlin.app.domain.action.CreateWorkStream(
                title = "Delta", projectId = "p1",
                executionPreference = ExecutionPreference.HUMAN, id = "delta"
            )
        ) as ActionResult.Success)
        assertEquals(listOf("gamma", "alpha", "beta", "delta"), streams())
    }

    @Test fun undoRestoresAWorkstreamToItsPreviousPosition() = runTest {
        seedThirdStream()
        val commit = (place("alpha", "p1", before = null) as ActionResult.Success).value
        assertEquals(listOf("beta", "gamma", "alpha"), streams())
        undo(commit)
        assertEquals(listOf("alpha", "beta", "gamma"), streams())
    }

    /** Reordering a stream must not disturb the tasks living inside it. */
    @Test fun reorderingAWorkstreamLeavesItsTasksAlone() = runTest {
        seedThirdStream()
        four()
        place("alpha", "p1", before = null)
        assertEquals(listOf("w", "x", "y", "z"), siblings("root"))
        assertEquals("alpha", tasks().first { it.id == "w" }.workStreamId)
    }

    /** Undo goes back through the same command, revalidated against the hierarchy as it is now. */
    private suspend fun undo(commit: PlacementCommit) {
        val inverse = inverseOf(commit, "p1", a.hierarchyRevision("p1"))!!
        val result = a.placeBranch("p1", inverse)
        assertEquals(ActionResult.Success::class, result::class)
    }
}
