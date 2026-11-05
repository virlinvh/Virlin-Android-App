package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class BulkNamesTest {
    private val json = """{"version":1,"items":[{"name":"First"},{"name":"Second"}]}"""
    @Test fun taskBatchesRespectStreamParentAndStandaloneOwnership() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.parse("2026-10-05T00:00:00Z")), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Base")) as ActionResult.Success).value
        val stream = (actions.createWorkStream(CreateWorkStream("Stream", project.id)) as ActionResult.Success).value
        assertTrue(actions.bulkCreateTasks(json, CreateTask("", workStreamId = stream.id)) is ActionResult.Success)
        val parent = repo.tasks.value.first()
        assertEquals(project.id, parent.projectId)
        assertTrue(actions.bulkCreateTasks(json, CreateTask("", parentTaskId = parent.id)) is ActionResult.Success)
        val children = repo.tasks.value.filter { it.parentTaskId == parent.id }
        assertEquals(2, children.size)
        assertTrue(children.all { it.workStreamId == stream.id && it.projectId == project.id })
        assertTrue(actions.bulkCreateTasks(json, CreateTask("", parentTaskId = children.first().id)) is ActionResult.Success)
        assertEquals(2, repo.tasks.value.count { it.parentTaskId == children.first().id })
        assertTrue(actions.bulkCreateTasks(json, CreateTask("", projectId = project.id)) is ActionResult.Success)
        assertEquals(2, repo.tasks.value.count { it.workStreamId == null && it.parentTaskId == null })
        val count = repo.tasks.value.size
        assertTrue(actions.bulkCreateTasks(json, CreateTask("", parentTaskId = "missing")) is ActionResult.Failure)
        assertEquals(count, repo.tasks.value.size)
    }
    @Test fun secondTaskFailureRollsBackWholeBatch() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val failing = object : com.virlin.app.domain.repository.WorkStreamRepository by repo {
            override suspend fun <T> transaction(block: suspend com.virlin.app.domain.repository.WorkStreamWriter.() -> T): T = repo.transaction {
                val staged = this
                block(object : com.virlin.app.domain.repository.WorkStreamWriter by staged {
                    override suspend fun saveTask(task: com.virlin.app.domain.model.Task) {
                        if (task.title == "Second") error("Simulated failure")
                        staged.saveTask(task)
                    }
                })
            }
        }
        val actions = DefaultVirlinActions(failing, FakeClock(Instant.parse("2026-10-05T00:00:00Z")), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Base")) as ActionResult.Success).value
        assertTrue(actions.bulkCreateTasks(json, CreateTask("", projectId = project.id)) is ActionResult.Failure)
        assertTrue(repo.tasks.value.isEmpty())
    }
    @Test fun rejectsUnsupportedHierarchyAndDuplicates() {
        listOf("""{"version":1,"items":[{"name":"A","children":[]}]}""",
            """{"version":1,"items":[{"name":"A"},{"name":"a"}]}""",
            """{"version":1,"items":[]}""", json + " trailing").forEach {
            assertTrue(runCatching { BulkNames.parse(it) }.isFailure)
        }
        assertEquals(listOf("First", "Second"), BulkNames.parse(json))
    }
    @Test fun projectAndWorkstreamBatchesHaveCorrectOwnersAndInvalidBatchWritesNothing() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.parse("2026-10-05T00:00:00Z")), SequentialIdProvider())
        assertTrue(actions.bulkCreateNames(json, null) is ActionResult.Success)
        assertEquals(2, repo.projects.value.size)
        val base = repo.projects.value.first().id
        assertTrue(actions.bulkCreateNames(json, base) is ActionResult.Success)
        assertTrue(repo.streams.value.all { it.projectId == base })
        assertEquals(listOf(0, 1), repo.streams.value.map { it.sortOrder })
        assertTrue(actions.bulkCreateNames(json, "missing") is ActionResult.Failure)
        assertEquals(2, repo.streams.value.size)
        assertTrue(actions.bulkCreateNames("""{"version":1,"items":[{"name":"Good"},{"name":""}]}""", null) is ActionResult.Failure)
        assertEquals(2, repo.projects.value.size)
    }
    @Test fun failureOnSecondWriteRollsBackFirstItemAndEvents() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val failing = object : com.virlin.app.domain.repository.WorkStreamRepository by repo {
            override suspend fun <T> transaction(block: suspend com.virlin.app.domain.repository.WorkStreamWriter.() -> T): T = repo.transaction {
                val staged = this
                block(object : com.virlin.app.domain.repository.WorkStreamWriter by staged {
                    override suspend fun saveStream(stream: com.virlin.app.domain.model.WorkStream) {
                        if (stream.title == "Second") error("Simulated write failure")
                        staged.saveStream(stream)
                    }
                })
            }
        }
        val actions = DefaultVirlinActions(failing, FakeClock(Instant.parse("2026-10-05T00:00:00Z")), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Base")) as ActionResult.Success).value
        assertTrue(actions.bulkCreateNames(json, project.id) is ActionResult.Failure)
        assertTrue(repo.streams.value.isEmpty())
    }
}
