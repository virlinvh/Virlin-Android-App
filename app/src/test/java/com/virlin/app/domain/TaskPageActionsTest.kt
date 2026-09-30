package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TaskPageActionsTest {
    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private val project = Project("p1", "Project", createdAt = now, updatedAt = now)
    private val task = Task("t1", "Task", projectId = project.id, createdAt = now, updatedAt = now)
    private val other = Task("t2", "Other", projectId = project.id, createdAt = now, updatedAt = now)
    private val repo = InMemoryWorkStreamRepository(seedProjects = listOf(project), seedTasks = listOf(task, other))
    private val clock = FakeClock(now)
    private val ids = SequentialIdProvider()

    @Test fun ensure_is_idempotent_and_reorder_requires_exact_permutation() = runTest {
        val pages = TaskPageActions(repo, clock, ids)
        pages.ensure(task.id, TaskPageTypeKeys.TODO, task.id)
        pages.ensure(task.id, TaskPageTypeKeys.TODO, task.id)
        pages.ensure(task.id, TaskPageTypeKeys.NOTE, TaskPageTypeKeys.noteOwner(task.id))
        assertEquals(2, repo.getTaskPageBlocks(task.id).size)
        val reversed = repo.getTaskPageBlocks(task.id).map { it.id }.reversed()
        assertTrue(pages.reorder(task.id, reversed))
        assertEquals(reversed, repo.getTaskPageBlocks(task.id).map { it.id })
        assertFalse(pages.reorder(task.id, listOf("unknown")))
    }

    @Test fun every_task_scoped_capture_registers_automatically_and_can_move() = runTest {
        val virlin = DefaultVirlinActions(repo, clock, ids)
        val created = virlin.createCapture(CreateCapture(
            type = CaptureType.LINK, content = "{}", sourceUrl = "https://example.com",
            context = CaptureContext(taskId = task.id)
        )) as com.virlin.app.domain.action.ActionResult.Success
        val block = repo.getTaskPageBlocks(task.id).single()
        assertEquals(TaskPageTypeKeys.capture(CaptureType.LINK), block.typeKey)
        assertEquals(created.value.id, block.contentId)
        assertTrue(TaskPageActions(repo, clock, ids).move(block.id, other.id))
        assertTrue(repo.getTaskPageBlocks(task.id).isEmpty())
        assertEquals(other.id, repo.getCapture(created.value.id)!!.taskId)
    }

    @Test fun unknown_type_key_round_trips_without_enum_parsing() = runTest {
        val pages = TaskPageActions(repo, clock, ids)
        pages.ensure(task.id, "future.canvas", "canvas-1")
        assertEquals("future.canvas", repo.getTaskPageBlocks(task.id).single().typeKey)
    }

    @Test fun existing_redesigned_task_note_reconciles_using_the_shared_owner_key() = runTest {
        val owner = TaskPageTypeKeys.noteOwner(task.id)
        val saved = DefaultVirlinActions(repo, clock, ids).saveNoteDoc(
            ownerKey = owner,
            title = "Task notes",
            blocks = emptyList()
        )
        assertTrue(saved is ActionResult.Success)
        TaskPageActions(repo, clock, ids).loadAndReconcile(task.id)
        val block = repo.getTaskPageBlocks(task.id).single()
        assertEquals(TaskPageTypeKeys.NOTE, block.typeKey)
        assertEquals(owner, block.contentId)
    }
}
