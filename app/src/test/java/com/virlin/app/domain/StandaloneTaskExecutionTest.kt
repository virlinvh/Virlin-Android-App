package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class StandaloneTaskExecutionTest {
    private val now = Instant.parse("2026-10-15T09:00:00Z")

    @Test fun projectOwnedTaskCanFocusWithoutAWorkstream() = runTest {
        val repository = InMemoryWorkStreamRepository(
            seedProjects = listOf(Project("project", "Project", createdAt = now, updatedAt = now)),
            seedTasks = listOf(Task("task", "Standalone", projectId = "project",
                createdAt = now, updatedAt = now)), taskExecutionEnabled = true)
        val actions = DefaultVirlinActions(repository, FakeClock(now), SequentialIdProvider())

        assertTrue(actions.startFocus("task") is ActionResult.Success)
        assertEquals(WorkStreamState.FOCUS, repository.getTask("task")!!.execution!!.state)
        assertEquals("task", repository.getOpenFocusSession("task")!!.workStreamId)
        assertEquals("task", repository.getOpenFocusSession("task")!!.taskId)
        assertTrue(actions.completeTask("task") is ActionResult.Success)
        assertNull(repository.getOpenFocusSession("task"))
    }

    @Test fun projectOwnedExternalTaskCanRunWithoutAWorkstream() = runTest {
        val repository = InMemoryWorkStreamRepository(
            seedProjects = listOf(Project("project", "Project", defaultExecutionMode = EffectiveExecutionMode.EXTERNAL,
                createdAt = now, updatedAt = now)),
            seedTasks = listOf(Task("task", "Standalone", projectId = "project",
                createdAt = now, updatedAt = now)), taskExecutionEnabled = true)
        val actions = DefaultVirlinActions(repository, FakeClock(now), SequentialIdProvider())

        assertTrue(actions.startExternalWork(StartExternalWork("task", workItemId = "task",
            checkInMinutes = 5)) is ActionResult.Success)
        val execution = repository.getTask("task")!!.execution!!
        assertEquals(WorkStreamState.PROCESSING, execution.state)
        assertEquals(now.plusSeconds(300), execution.checkAt)
    }
}
