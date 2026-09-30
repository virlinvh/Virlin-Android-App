package com.virlin.app.hierarchy

import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.hierarchy.taskHierarchyDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TaskHierarchyDestinationTest {
    private val now = Instant.parse("2026-09-30T00:00:00Z")

    @Test fun nestedWorkStreamTask_opensItsExactGreenHierarchyLevel() {
        val stream = WorkStream(
            id = "ws",
            title = "Stream",
            state = WorkStreamState.READY,
            projectId = "project",
            createdAt = now,
            updatedAt = now
        )
        val root = Task("root", "Root", workStreamId = stream.id, createdAt = now, updatedAt = now)
        val child = Task("child", "Child", workStreamId = stream.id, parentTaskId = root.id, createdAt = now, updatedAt = now)

        assertEquals(
            "workstream_detail/ws?path=root,child",
            taskHierarchyDestination(child.id, listOf(root, child), listOf(stream))
        )
    }

    @Test fun standaloneTask_opensTheGreenProjectTaskIndex() {
        val task = Task("task", "Standalone", projectId = "project", createdAt = now, updatedAt = now)
        assertEquals("project_task_index/project", taskHierarchyDestination(task.id, listOf(task), emptyList()))
    }

    @Test fun missingOrUnplacedTask_hasNoInventedDestination() {
        assertNull(taskHierarchyDestination("missing", emptyList(), emptyList()))
        val orphan = Task("orphan", "Orphan", createdAt = now, updatedAt = now)
        assertNull(taskHierarchyDestination(orphan.id, listOf(orphan), emptyList()))
    }

    @Test fun corruptParentCycle_isBoundedAndStillUsesStableIds() {
        val stream = WorkStream(
            id = "ws",
            title = "Stream",
            state = WorkStreamState.READY,
            createdAt = now,
            updatedAt = now
        )
        val first = Task(
            id = "first",
            title = "First",
            workStreamId = stream.id,
            parentTaskId = "second",
            createdAt = now,
            updatedAt = now
        )
        val second = Task(
            id = "second",
            title = "Second",
            workStreamId = stream.id,
            parentTaskId = first.id,
            createdAt = now,
            updatedAt = now
        )

        assertEquals(
            "workstream_detail/ws?path=second,first",
            taskHierarchyDestination(first.id, listOf(first, second), listOf(stream))
        )
    }
}
