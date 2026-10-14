package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.hierarchy.projectKnowledgeBlocks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProjectKnowledgeOwnershipTest {
    @Test fun projectChecklistCanBeOrganizedToSubtaskWithoutChangingContentOrId() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.parse("2026-10-05T00:00:00Z")), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Project")) as ActionResult.Success).value
        val stream = (actions.createWorkStream(CreateWorkStream("Stream", project.id)) as ActionResult.Success).value
        val task = (actions.createTask(CreateTask("Task", workStreamId = stream.id)) as ActionResult.Success).value
        val child = (actions.addSubtask(task.id, "Subtask") as ActionResult.Success).value
        val blocks = projectKnowledgeBlocks("First\nSecond", true)
        val note = (actions.createTextNote("Checklist", blocks, CaptureContext(projectId = project.id)) as ActionResult.Success).value
        val before = repo.captures.value.single()
        assertEquals(project.id, before.projectId)
        assertNull(before.taskId)
        assertNull(before.workStreamId)
        assertTrue(actions.attachCapture(before.id, CaptureContext(taskId = child.id)) is ActionResult.Success)
        val after = repo.captures.value.single()
        assertEquals(before.id, after.id)
        assertEquals(before.content, after.content)
        assertEquals(project.id, after.projectId)
        assertEquals(stream.id, after.workStreamId)
        assertEquals(child.id, after.taskId)
        assertEquals(blocks, actions.getNoteByCaptureId(note.captureItemId)?.blocks)
        assertEquals(2, repo.tasks.value.size) // No hidden placeholder tasks.
    }
}
