package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.getOrNull
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.TaskHierarchy
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Depth is unbounded in the model. A task is a task at any level: it can be opened, given
 * children, made current and resolved for execution the same way whether it sits at level one
 * or level twelve. Nothing in the domain gates on "is a leaf" or on a depth limit.
 */
class TaskDepthTest {

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

    /** Builds a chain of [depth] tasks, each the child of the last, and returns their ids. */
    private suspend fun chain(depth: Int): List<String> {
        var parent: String? = null
        return (1..depth).map { level ->
            val created = a.createTask(
                CreateTask(title = "Level $level", workStreamId = "psych", parentTaskId = parent,
                    id = "L$level")
            ) as ActionResult.Success
            parent = created.value.id
            created.value.id
        }
    }

    @Test fun aChainOfTwelveLevelsPersists_andEachLevelKnowsItsParent() = runTest {
        val ids = chain(12)
        assertEquals(12, ids.size)
        ids.drop(1).forEachIndexed { index, id ->
            assertEquals(ids[index], repo.getTask(id)!!.parentTaskId)
        }
        // The whole branch hangs off the first task and stays in one stream.
        assertEquals(11, TaskHierarchy.descendants(repo.tasks.value, ids.first()).size)
        assertTrue(repo.tasks.value.all { it.workStreamId == "psych" })
        // Ancestry resolves from stable ids alone, which is what a deep link would rebuild from.
        assertEquals(12, repo.getAncestry(ids.last())!!.size)
    }

    @Test fun aChildCanBeAddedToATaskAtAnyDepth_includingWhatWasALeaf() = runTest {
        val ids = chain(6)
        val deepest = ids.last()
        assertTrue(TaskHierarchy.children(repo.tasks.value, deepest).isEmpty())

        val added = a.createTask(
            CreateTask(title = "Level 7", workStreamId = "psych", parentTaskId = deepest)
        ).getOrNull()
        assertNotNull("a leaf must accept a child", added)
        assertEquals(deepest, added!!.parentTaskId)
        assertEquals(listOf("Level 7"), TaskHierarchy.children(repo.tasks.value, deepest).map { it.title })
    }

    @Test fun theCurrentTaskCanBeADeepLeaf_andIsTheSameCanonicalId() = runTest {
        val ids = chain(8)
        val deepest = ids.last()
        a.setActiveTask("psych", deepest)
        // Now reads exactly this id off the WorkStream — there is no second "current" record.
        assertEquals(deepest, repo.getStream("psych")!!.activeTaskId)

        // Adding a child below the current task does not move or clear the marker.
        a.createTask(CreateTask(title = "deeper", workStreamId = "psych", parentTaskId = deepest))
        assertEquals(deepest, repo.getStream("psych")!!.activeTaskId)
    }

    @Test fun executionInheritanceResolvesThroughManyLevels_andAnOverrideStops() = runTest {
        val ids = chain(7)
        fun byId() = repo.tasks.value.associateBy { t -> t.id }
        val project = repo.getProject("p1")
        val stream = repo.getStream("psych")!!

        // Nothing explicit anywhere below the stream: the deepest task follows the stream.
        assertEquals(
            com.virlin.app.domain.model.EffectiveExecutionMode.HUMAN,
            com.virlin.app.domain.model.ExecutionModeResolver.resolveTask(
                repo.getTask(ids.last())!!, byId(), stream,
                com.virlin.app.domain.model.ExecutionModeResolver.projectDefault(project)
            )
        )

        // An explicit ancestor at level 3 now decides for everything below it.
        a.setTaskExecutionPreference(ids[2], ExecutionPreference.EXTERNAL)
        assertEquals(
            com.virlin.app.domain.model.EffectiveExecutionMode.EXTERNAL,
            com.virlin.app.domain.model.ExecutionModeResolver.resolveTask(
                repo.getTask(ids.last())!!, byId(), stream,
                com.virlin.app.domain.model.ExecutionModeResolver.projectDefault(project)
            )
        )

        // ...until a descendant states its own, which wins for itself.
        a.setTaskExecutionPreference(ids[5], ExecutionPreference.HUMAN)
        assertEquals(
            com.virlin.app.domain.model.EffectiveExecutionMode.HUMAN,
            com.virlin.app.domain.model.ExecutionModeResolver.resolveTask(
                repo.getTask(ids[5])!!, byId(), stream,
                com.virlin.app.domain.model.ExecutionModeResolver.projectDefault(project)
            )
        )
        // A sibling branch under the explicit ancestor still inherits EXTERNAL.
        val sibling = a.createTask(
            CreateTask(title = "sibling", workStreamId = "psych", parentTaskId = ids[2])
        ).getOrNull()!!
        assertEquals(
            com.virlin.app.domain.model.EffectiveExecutionMode.EXTERNAL,
            com.virlin.app.domain.model.ExecutionModeResolver.resolveTask(
                sibling, byId(), stream,
                com.virlin.app.domain.model.ExecutionModeResolver.projectDefault(project)
            )
        )
    }

    @Test fun aCyclicMoveIsRejectedAtDepth() = runTest {
        val ids = chain(6)
        // Level 2 into level 6, which is its own descendant.
        assertEquals(
            DomainError.CyclicParent,
            (a.moveTasks(listOf(ids[1]), ids[5], null, "psych") as ActionResult.Rejected).reason
        )
        assertEquals(ids[0], repo.getTask(ids[1])!!.parentTaskId)
    }

    @Test fun aDeepBranchMovesWholeAndKeepsEveryId() = runTest {
        val ids = chain(9)
        val branchRoot = ids[4]
        val descendants = TaskHierarchy.descendants(repo.tasks.value, branchRoot).map { it.id }

        a.moveTasks(listOf(branchRoot), null, null, "psych")
        assertEquals(null, repo.getTask(branchRoot)!!.parentTaskId)
        // Same ids, same shape, still in the same stream.
        assertEquals(descendants, TaskHierarchy.descendants(repo.tasks.value, branchRoot).map { it.id })
        assertTrue(repo.tasks.value.all { it.workStreamId == "psych" })
    }
}
