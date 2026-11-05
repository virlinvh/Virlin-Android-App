package com.virlin.app.domain

import androidx.room.Room
import com.virlin.app.data.db.RoomWorkStreamRepository
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.data.project.ProjectDeletionCleanup
import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.schedule.AttentionScheduler
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HierarchyDeletionTest {
    private val now = Instant.EPOCH
    @Test fun deletesFiveThousandLevelsIterativelyAndKeepsParentsAndSiblings() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        val project = (actions.createProject(CreateProject("Project")) as ActionResult.Success).value
        val stream = (actions.createWorkStream(CreateWorkStream("Stream", project.id)) as ActionResult.Success).value
        val parent = (actions.createTask(CreateTask("Parent", workStreamId = stream.id)) as ActionResult.Success).value
        val sibling = (actions.addSubtask(parent.id, "Keep sibling") as ActionResult.Success).value
        repo.transaction {
            repeat(5000) { i -> saveTask(Task("deep-$i", "Deep $i", projectId = project.id, workStreamId = stream.id,
                parentTaskId = if (i == 0) parent.id else "deep-${i-1}", createdAt = now, updatedAt = now)) }
            saveStream(stream.copy(activeTaskId = "deep-4999", state = WorkStreamState.PROCESSING, processingStartedAt = now, checkAt = now.plusSeconds(60)))
        }
        actions.createCapture(CreateCapture(CaptureType.NOTE, "Owned", context = CaptureContext(taskId = "deep-4999")))
        actions.createCapture(CreateCapture(CaptureType.NOTE, "Project knowledge", context = CaptureContext(projectId = project.id)))
        val otherProject = (actions.createProject(CreateProject("Other project")) as ActionResult.Success).value
        val retained = (actions.createCapture(CreateCapture(CaptureType.NOTE, "Retained source", context = CaptureContext(projectId = otherProject.id))) as ActionResult.Success).value
        repo.transaction { saveCapture(retained.copy(convertedTaskId = "deep-4999")) }
        val result = actions.deleteTask("deep-0") as ActionResult.Success
        assertEquals(5000, result.value.taskIds.size)
        assertEquals(setOf(parent.id, sibling.id), repo.tasks.value.map { it.id }.toSet())
        assertNotNull(repo.getProject(project.id)); assertNotNull(repo.getStream(stream.id))
        assertNull(repo.getStream(stream.id)!!.activeTaskId)
        assertEquals(WorkStreamState.READY, repo.getStream(stream.id)!!.state)
        assertNull(repo.getStream(stream.id)!!.processingStartedAt)
        assertNull(repo.getStream(stream.id)!!.checkAt)
        assertEquals(setOf("Project knowledge", "Retained source"), repo.captures.value.map { it.content }.toSet())
        assertNull(repo.getCapture(retained.id)!!.convertedTaskId)
        assertTrue(actions.deleteTask("deep-0") is ActionResult.NotFound)
        assertEquals(result.value, ProjectDeletion.decode(result.value.encode()))
    }

    @Test fun roomBranchCascadeKeepsProjectSettingsAndNeighbouringWork() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, VirlinDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
            val project = (actions.createProject(CreateProject("Project")) as ActionResult.Success).value
            val stream = (actions.createWorkStream(CreateWorkStream("Delete stream", project.id)) as ActionResult.Success).value
            val other = (actions.createWorkStream(CreateWorkStream("Keep stream", project.id)) as ActionResult.Success).value
            val parent = (actions.createTask(CreateTask("Parent", workStreamId = stream.id)) as ActionResult.Success).value
            val child = (actions.addSubtask(parent.id, "Child") as ActionResult.Success).value
            val sibling = (actions.addSubtask(parent.id, "Sibling") as ActionResult.Success).value
            actions.createTask(CreateTask("Other task", workStreamId = other.id))
            actions.createCapture(CreateCapture(CaptureType.NOTE, "Keep project knowledge", context = CaptureContext(projectId = project.id)))
            actions.createCapture(CreateCapture(CaptureType.NOTE, "Delete task knowledge", context = CaptureContext(taskId = child.id)))
            actions.saveNoteDoc("task-${child.id}", "Owned note", emptyList())
            actions.saveNoteDoc("project-${project.id}", "Keep note", emptyList())
            val icon = File(context.filesDir, "project-icons/${project.id}/icon.png").apply { parentFile!!.mkdirs(); writeText("keep") }
            val prefs = context.getSharedPreferences("project_appearance", 0)
            prefs.edit().putString(project.id, "keep color").commit()
            assertTrue(runCatching { repo.transaction { deleteTaskTree(child.id); error("Simulated rollback") } }.isFailure)
            assertNotNull(repo.getTask(child.id)); assertNotNull(repo.getNoteDoc("task-${child.id}"))
            assertTrue(repo.pendingProjectDeletions().isEmpty())
            assertTrue(actions.deleteTask(child.id) is ActionResult.Success)
            assertNotNull(repo.getTask(parent.id)); assertNotNull(repo.getTask(sibling.id))
            assertNull(repo.getNoteDoc("task-${child.id}"))
            assertTrue(repo.getEvents(stream.id).none { com.virlin.app.domain.activity.TaskEventDetail.decode(it.detail)?.taskId == child.id })
            ProjectDeletionCleanup.flush(context, RoomWorkStreamRepository.create(db), AttentionScheduler.NoOp)
            assertTrue(icon.exists()); assertEquals("keep color", prefs.getString(project.id, null))
            assertNotNull(repo.getNoteDoc("project-${project.id}"))
            val result = actions.deleteWorkStream(stream.id) as ActionResult.Success
            assertEquals(setOf(parent.id, sibling.id), result.value.taskIds)
            ProjectDeletionCleanup.flush(context, repo, AttentionScheduler.NoOp)
            val reopened = RoomWorkStreamRepository.create(db)
            assertNotNull(reopened.getProject(project.id)); assertNotNull(reopened.getStream(other.id))
            assertNull(reopened.getStream(stream.id))
            assertEquals(listOf("Other task"), reopened.tasks.value.map { it.title })
            assertEquals(listOf("Keep project knowledge"), reopened.captures.value.map { it.content })
            assertTrue(reopened.pendingProjectDeletions().isEmpty())
        } finally { db.close() }
    }

    @Test fun malformedCyclesTerminateAndRootIsIncluded() {
        val a = Task("a", "A", projectId = "p", parentTaskId = "b", createdAt = now, updatedAt = now)
        val b = a.copy(id = "b", title = "B", parentTaskId = "a")
        assertEquals(setOf("a", "b"), ProjectDeletion.branch("a", "task", emptyList(), listOf(a, b), emptyList()).taskIds)
    }

    @Test fun roomClearsRetainedConversionReferenceWithoutDeletingOtherProjectKnowledge() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VirlinDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
            val project = (actions.createProject(CreateProject("Delete task here")) as ActionResult.Success).value
            val other = (actions.createProject(CreateProject("Keep source here")) as ActionResult.Success).value
            val task = (actions.createTask(CreateTask("Delete task", projectId = project.id)) as ActionResult.Success).value
            val source = (actions.createCapture(CreateCapture(CaptureType.NOTE, "Retained original", context = CaptureContext(projectId = other.id))) as ActionResult.Success).value
            repo.transaction { saveCapture(source.copy(convertedTaskId = task.id)) }
            assertTrue(actions.deleteTask(task.id) is ActionResult.Success)
            assertEquals("Retained original", repo.getCapture(source.id)!!.content)
            assertNull(repo.getCapture(source.id)!!.convertedTaskId)
            assertNotNull(repo.getProject(other.id))
        } finally { db.close() }
    }
}
