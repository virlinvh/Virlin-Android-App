package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.repository.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class BulkHierarchyTest {
    @Test fun projectImportCreatesAllLevelsWithCorrectOwners() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
        val source = """{"version":2,"items":[{"name":"Project","children":[{"name":"Stream","children":[{"name":"Task","children":[{"name":"Child"}]}]}]}]}"""
        assertEquals(4, (actions.bulkCreateNames(source, null) as ActionResult.Success).value)
        val project = repo.projects.value.single()
        val stream = repo.streams.value.single()
        assertEquals(project.id, stream.projectId)
        val parent = repo.tasks.value.single { it.parentTaskId == null }
        val child = repo.tasks.value.single { it.parentTaskId != null }
        assertEquals(parent.id, child.parentTaskId)
        assertTrue(repo.tasks.value.all { it.projectId == project.id && it.workStreamId == stream.id })
    }

    @Test fun taskAndSubtaskImportsKeepExistingParentAndOrder() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Base")) as ActionResult.Success).value
        val stream = (actions.createWorkStream(CreateWorkStream("Stream", project.id)) as ActionResult.Success).value
        val source = """{"version":2,"items":[{"name":"First","children":[{"name":"Child","children":[{"name":"Deep"}]}]},{"name":"Second"}]}"""
        assertEquals(4, (actions.bulkCreateTasks(source, CreateTask("", workStreamId = stream.id)) as ActionResult.Success).value)
        val roots = repo.tasks.value.filter { it.parentTaskId == null }.sortedBy { it.order }
        assertEquals(listOf("First", "Second"), roots.map { it.title })
        val child = repo.tasks.value.single { it.title == "Child" }
        assertEquals(roots.first().id, child.parentTaskId)
        assertEquals(child.id, repo.tasks.value.single { it.title == "Deep" }.parentTaskId)
        assertEquals(4, (actions.bulkCreateTasks(source, CreateTask("", parentTaskId = child.id)) as ActionResult.Success).value)
        assertEquals(listOf("Deep", "First", "Second"), repo.tasks.value.filter { it.parentTaskId == child.id }.sortedBy { it.order }.map { it.title })
        assertTrue(repo.tasks.value.all { it.projectId == project.id && it.workStreamId == stream.id })
        assertEquals(4, (actions.bulkCreateTasks(source, CreateTask("", projectId = project.id)) as ActionResult.Success).value)
        assertEquals(4, repo.tasks.value.count { it.workStreamId == null })
    }

    @Test fun destinationSpecificPromptsAndPreview() {
        assertTrue(BulkHierarchy.prompt("projects").contains("Top-level items become new projects"))
        assertTrue(BulkHierarchy.prompt("tasks").contains("Top-level items become tasks"))
        assertTrue(BulkHierarchy.prompt("subtasks").contains("Do not repeat the existing parent"))
        assertEquals(listOf("Project: A", "    Workstream: B", "        Task: C"),
            BulkNode("A", listOf(BulkNode("B", listOf(BulkNode("C"))))).preview(rootType = "projects"))
    }
    @Test fun aiPromptRequestsOutlineOnlyNotGeneratedRequirements() {
        val prompt = BulkHierarchy.prompt()
        assertTrue(prompt.contains("NOT task planning or requirement extraction"))
        assertTrue(prompt.contains("acceptance criteria, capability lists"))
        assertTrue(prompt.contains("Never convert prose into tasks"))
        assertTrue(prompt.contains("A heading with only descriptive content is a leaf"))
        assertFalse(prompt.contains("Extract all explicitly described"))
    }
    private val json = """{"version":2,"items":[{"name":"Phase","children":[{"name":"Build","children":[{"name":"Save"},{"name":"Open"}]},{"name":"Test"}]},{"name":"Release"}]}"""
    @Test fun createsOrderedOwnedHierarchy() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Base")) as ActionResult.Success).value
        val result = actions.bulkCreateNames(json, project.id)
        assertEquals(6, (result as ActionResult.Success).value)
        val streams = repo.streams.value.sortedBy { it.sortOrder }
        assertEquals(listOf("Phase", "Release"), streams.map { it.title })
        val tasks = repo.tasks.value
        assertTrue(tasks.all { it.projectId == project.id && it.workStreamId == streams.first().id })
        val roots = tasks.filter { it.parentTaskId == null }.sortedBy { it.order }
        assertEquals(listOf("Build", "Test"), roots.map { it.title })
        assertEquals(listOf("Save", "Open"), tasks.filter { it.parentTaskId == roots.first().id }.sortedBy { it.order }.map { it.title })
    }
    @Test fun descendantFailureRollsBackStreamsAndTasks() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val failing = object : WorkStreamRepository by repo {
            override suspend fun <T> transaction(block: suspend WorkStreamWriter.() -> T): T = repo.transaction {
                val staged = this
                block(object : WorkStreamWriter by staged {
                    override suspend fun saveTask(task: com.virlin.app.domain.model.Task) {
                        if (task.title == "Open") error("Simulated failure")
                        staged.saveTask(task)
                    }
                })
            }
        }
        val actions = DefaultVirlinActions(failing, FakeClock(Instant.EPOCH), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Base")) as ActionResult.Success).value
        assertTrue(actions.bulkCreateNames(json, project.id) is ActionResult.Failure)
        assertTrue(repo.tasks.value.isEmpty())
        assertTrue(repo.streams.value.isEmpty())
        val projectSource = """{"version":2,"items":[{"name":"New project","children":[{"name":"New stream","children":[{"name":"First","children":[{"name":"Open"}]}]}]}]}"""
        assertTrue(actions.bulkCreateNames(projectSource, null) is ActionResult.Failure)
        assertEquals(listOf(project.id), repo.projects.value.map { it.id })
        assertTrue(repo.tasks.value.isEmpty())
        assertTrue(repo.streams.value.isEmpty())
        val taskSource = """{"version":2,"items":[{"name":"First","children":[{"name":"Open"}]}]}"""
        assertTrue(actions.bulkCreateTasks(taskSource, CreateTask("", projectId = project.id)) is ActionResult.Failure)
        assertTrue(repo.tasks.value.isEmpty())
    }
    @Test fun strictValidationAndCompatibility() {
        assertEquals(6, BulkHierarchy.parse(json).sumOf { it.count })
        assertEquals(1, BulkHierarchy.parse("""{"version":1,"items":[{"name":"Legacy"}]}""").size)
        listOf(json.replace("\"version\":2", "\"version\":1"),
            json.replace("\"name\":\"Open\"", "\"name\":\"save\""),
            json.replace("\"name\":\"Open\"", "\"id\":\"injected\""), json + " trailing")
            .forEach { assertTrue(runCatching { BulkHierarchy.parse(it) }.isFailure) }
        val oversized = "{\"version\":2,\"items\":[" + (1..501).joinToString(",") { "{\"name\":\"Item $it\"}" } + "]}"
        assertTrue(runCatching { BulkHierarchy.parse(oversized) }.isFailure)
        assertTrue(runCatching { BulkHierarchy.parse(" ".repeat(BulkHierarchy.MAX_CHARACTERS + 1)) }.isFailure)
        var deep = "{\"name\":\"Leaf\"}"
        repeat(6) { deep = "{\"name\":\"Parent $it\",\"children\":[$deep]}" }
        assertTrue(runCatching { BulkHierarchy.parse("{\"version\":2,\"items\":[$deep]}") }.isFailure)
    }
    @Test fun exactly500NestedItemsImportAnd501WritesNothing() = runTest {
        fun batch(children: Int) = "{\"version\":2,\"items\":[{\"name\":\"Phase\",\"children\":[" +
            (1..children).joinToString(",") { "{\"name\":\"Step $it\"}" } + "]}]}"
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Base")) as ActionResult.Success).value
        assertEquals(500, (actions.bulkCreateNames(batch(499), project.id) as ActionResult.Success).value)
        assertEquals(1, repo.streams.value.size)
        assertEquals(499, repo.tasks.value.size)
        assertEquals((1..499).map { "Step $it" }, repo.tasks.value.sortedBy { it.order }.map { it.title })
        assertTrue(actions.bulkCreateNames(batch(500), project.id) is ActionResult.Failure)
        assertEquals(1, repo.streams.value.size)
        assertEquals(499, repo.tasks.value.size)
    }
}
