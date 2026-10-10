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
class ProjectDeletionTest {
    @Test fun removesDescendantsAndKeepsOtherProjectsAndInbox() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Delete")) as ActionResult.Success).value
        val other = (actions.createProject(CreateProject("Keep")) as ActionResult.Success).value
        val stream = (actions.createWorkStream(CreateWorkStream("Stream", project.id)) as ActionResult.Success).value
        val task = (actions.createTask(CreateTask("Task", workStreamId = stream.id)) as ActionResult.Success).value
        val child = (actions.addSubtask(task.id, "Child") as ActionResult.Success).value
        actions.addStep(child.id, "Checklist")
        actions.createCapture(CreateCapture(com.virlin.app.domain.model.CaptureType.NOTE, "Owned", context = CaptureContext(taskId = child.id)))
        actions.createCapture(CreateCapture(com.virlin.app.domain.model.CaptureType.NOTE, "Inbox"))
        actions.createTask(CreateTask("Standalone", projectId = project.id))
        val keepTask = (actions.createTask(CreateTask("Other", projectId = other.id)) as ActionResult.Success).value
        val result = actions.deleteProject(project.id)
        assertTrue(result is ActionResult.Success)
        assertEquals(listOf(other.id), repo.projects.value.map { it.id })
        assertEquals(listOf(keepTask.id), repo.tasks.value.map { it.id })
        assertTrue(repo.streams.value.isEmpty()); assertTrue(repo.taskSteps.value.isEmpty())
        assertEquals(listOf("Inbox"), repo.captures.value.map { it.content })
        assertEquals(project.id, repo.pendingProjectDeletions().single().projectId)
        assertTrue(actions.deleteProject(project.id) is ActionResult.NotFound)
        repo.transaction { acknowledgeProjectDeletion(project.id) }
        assertTrue(actions.saveNoteDoc("task-${child.id}", "Stale", emptyList()) is ActionResult.Failure)
        assertTrue(actions.saveMapNodePreview(child.id, com.virlin.app.domain.mappreview.FormattedText.plain("Stale"), emptyList()) is ActionResult.Failure)
        assertNull(repo.getNoteDoc("task-${child.id}"))
        assertNull(repo.getMapNodePreview(child.id))
    }

    @Test fun failedTransactionLeavesEverythingAndNoCleanupReceipt() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Keep")) as ActionResult.Success).value
        val failing = object : WorkStreamRepository by repo {
            override suspend fun <T> transaction(block: suspend WorkStreamWriter.() -> T): T = repo.transaction {
                block(this)
                error("Simulated commit failure")
            }
        }
        assertTrue(DefaultVirlinActions(failing, FakeClock(Instant.EPOCH), SequentialIdProvider()).deleteProject(project.id) is ActionResult.Failure)
        assertNotNull(repo.getProject(project.id)); assertTrue(repo.pendingProjectDeletions().isEmpty())
    }
}
