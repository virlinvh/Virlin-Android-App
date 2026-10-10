package com.virlin.app.hierarchy

import com.virlin.app.domain.model.*
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class StandaloneTaskPresentationTest {
    private val now = Instant.parse("2026-10-05T00:00:00Z")
    private val project = Project("p", "Project", createdAt = now, updatedAt = now)
    private fun task(id: String, parent: String? = null) = Task(id, id, projectId = "p", parentTaskId = parent, createdAt = now, updatedAt = now)

    @Test fun projectsAndWorkstreamsDoNotLeakIntoStandaloneRows() {
        val rows = standaloneRows(project, listOf(task("own"), task("foreign").copy(projectId = "other"), task("stream").copy(workStreamId = "ws")), emptyList(), now)
        assertEquals(listOf("own"), rows.map { it.id })
    }

    @Test fun nestedTasksInheritExecutionAndNavigateAtTheirExactDepth() {
        val root = task("root").copy(executionPreference = ExecutionPreference.EXTERNAL)
        val child = task("child", "root")
        val rows = standaloneRows(project, listOf(root, child), emptyList(), now)
        assertEquals(StreamExecutionMode.EXTERNAL, rows.last().effectiveExecution)
        assertEquals("root", rows.last().inheritedFrom)
        assertEquals(1, rows.first().childCount)
        assertEquals("standalone_tasks/p?path=root,child", taskHierarchyDestination("child", listOf(root, child), emptyList()))
        assertNull(child.workStreamId)
    }
}
