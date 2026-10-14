package com.virlin.app.hierarchy

import com.virlin.app.domain.model.*
import com.virlin.app.ui.hierarchy.ProjectSearch
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProjectSearchTest {
    @Test fun titlesOnlyIncludeNestedAndStandaloneTasksWithCorrectOwnershipAndPath() {
        val now = Instant.EPOCH
        fun stream(id: String, project: String) = WorkStream(id, "Stream $id", projectId = project,
            state = WorkStreamState.READY, createdAt = now, updatedAt = now)
        fun task(id: String, title: String, ws: String?, project: String, parent: String? = null) =
            Task(id, title, workStreamId = ws, projectId = project, parentTaskId = parent, createdAt = now, updatedAt = now)
        val items = ProjectSearch.index("p", listOf(stream("s", "p"), stream("other", "q")), listOf(
            task("a", "Release", "s", "p"), task("b", "Release notes", "s", "p", "a"),
            task("c", "Final wording", "s", "p", "b"), task("standalone", "Release plan", null, "p"),
            task("wrong", "Release outside", "other", "p")))
        assertEquals(setOf("s", "a", "b", "c", "standalone"), items.map { it.id }.toSet())
        assertEquals("Stream s › Release › Release notes", items.first { it.id == "c" }.path)
        assertEquals("Subtask", items.first { it.id == "c" }.kind)
        assertEquals(setOf("a", "b", "standalone"), ProjectSearch.matches(items, " RELEASE ").map { it.id }.toSet())
        // A parent's title in a breadcrumb is not a search match for its child.
        assertFalse(ProjectSearch.matches(items, "Release").any { it.id == "c" })
        assertTrue(ProjectSearch.matches(items, "missing").isEmpty())
    }
}
