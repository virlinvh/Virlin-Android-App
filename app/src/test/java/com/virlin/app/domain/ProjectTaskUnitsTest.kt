package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.progress.ProjectTaskUnits
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * The Task Index lists what Overview counted. These tests hold the two to the same number, so
 * a list and a ratio can never drift apart.
 */
class ProjectTaskUnitsTest {

    private val t0: Instant = Instant.parse("2026-09-27T09:00:00Z")
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var a: DefaultVirlinActions

    @Before fun setUp() {
        repo = InMemoryWorkStreamRepository(
            seed = listOf(
                WorkStream(id = "psych", title = "Psychology", state = WorkStreamState.READY,
                    projectId = "p1", executionPreference = ExecutionPreference.HUMAN,
                    createdAt = t0, updatedAt = t0)
            ),
            seedProjects = listOf(Project("p1", "Psychology", createdAt = t0, updatedAt = t0))
        )
        a = DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider())
    }

    private suspend fun task(id: String, parent: String? = null, stream: String? = "psych") =
        (a.createTask(
            CreateTask(title = id, projectId = "p1", workStreamId = stream, parentTaskId = parent, id = id)
        ) as ActionResult.Success).value

    private fun units() = ProjectTaskUnits.of(repo.tasks.value, repo.streams.value, "p1")

    private fun ratio(): Pair<Int, Int> {
        val p = ProgressCalculator.ofProject(repo.tasks.value, repo.streams.value, "p1")
                as ProgressResult.Structured
        return p.completed.toInt() to p.total.toInt()
    }

    @Test fun theListedUnitsAreExactlyWhatOverviewCounted() = runTest {
        // A parent with leaves, a childless root, and a standalone task.
        task("answer"); task("q11to20", parent = "answer")
        task("q17", parent = "q11to20"); task("q11", parent = "q11to20")
        task("read")
        task("standalone", stream = null)
        a.completeTask("q11"); a.completeTask("read")

        val (completed, total) = ratio()
        val listed = units()
        assertEquals("one row per counted unit", total, listed.size)
        assertEquals(completed, listed.count { it.completed })

        // A grouping parent is not itself a unit — only its leaves are.
        assertTrue(listed.none { it is ProjectTaskUnits.Unit.TaskUnit && it.task.id == "answer" })
        assertTrue(listed.none { it is ProjectTaskUnits.Unit.TaskUnit && it.task.id == "q11to20" })
        assertEquals(
            listOf("q17", "q11", "read", "standalone").sorted(),
            listed.filterIsInstance<ProjectTaskUnits.Unit.TaskUnit>().map { it.task.id }.sorted()
        )
    }

    @Test fun aWorkStreamWithNoTasksIsOneUnitOfItsOwn() = runTest {
        val listed = units()
        assertEquals(1, listed.size)
        assertTrue(listed.single() is ProjectTaskUnits.Unit.EmptyWorkStream)
        assertEquals(ratio().second, listed.size)
    }

    @Test fun aCancelledLeafLeavesBothTheRatioAndTheList() = runTest {
        task("read"); task("drop")
        a.cancelTask("drop")

        val (_, total) = ratio()
        val listed = units()
        assertEquals(total, listed.size)
        assertTrue(listed.none { it is ProjectTaskUnits.Unit.TaskUnit && it.task.id == "drop" })
    }

    @Test fun completingATaskMovesItBetweenTheFilters_withoutChangingTheTotal() = runTest {
        task("read"); task("notes")
        val before = units()
        assertEquals(0, before.count { it.completed })

        a.completeTask("read")
        val after = units()
        assertEquals("the total is the same work, differently done", before.size, after.size)
        assertEquals(1, after.count { it.completed })
        assertEquals(1, after.count { !it.completed })
        assertEquals(ratio(), after.count { it.completed } to after.size)
    }

    @Test fun anEmptyProjectListsNothing() = runTest {
        val empty = InMemoryWorkStreamRepository(
            seedProjects = listOf(Project("p2", "Empty", createdAt = t0, updatedAt = t0))
        )
        assertTrue(ProjectTaskUnits.of(empty.tasks.value, empty.streams.value, "p2").isEmpty())
    }
}
