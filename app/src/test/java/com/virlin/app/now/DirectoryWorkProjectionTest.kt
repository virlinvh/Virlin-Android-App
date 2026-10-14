package com.virlin.app.now

import com.virlin.app.domain.model.*
import com.virlin.app.ui.screens.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DirectoryWorkProjectionTest {
    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val project = Project("p", "Project", createdAt = now, updatedAt = now)
    private val stream = WorkStream("w", "Stream", projectId = "p", state = WorkStreamState.READY, createdAt = now, updatedAt = now)
    private fun task(id: String, state: WorkStreamState, parent: String? = null) =
        Task(id, id, workStreamId = "w", parentTaskId = parent, createdAt = now, updatedAt = now,
            execution = TaskExecution(state))
    @Test fun independentNestedExecutionsExcludeContainers() {
        val tasks = listOf(task("parent", WorkStreamState.READY),
            task("external", WorkStreamState.PROCESSING, "parent"), task("human", WorkStreamState.FOCUS))
        val running = DirectoryWorkProjection.items(listOf(project), listOf(stream), tasks, emptyList(),
            ProjectDirectoryFilter.PROCESSING, "", now)
        assertEquals(listOf("external"), running.map { it.work.title })
        assertEquals("p", running.single().project?.id)
        assertEquals(listOf("parent", "external"), running.single().path)
        assertEquals("parent › Stream › Project", running.single().hierarchy)
        assertEquals(2, DirectoryWorkProjection.items(listOf(project), listOf(stream), tasks,
            emptyList(), ProjectDirectoryFilter.ALL, "", now).size)
    }
    @Test fun overdueRunningCanAlsoNeedAttention() {
        val work = stream.copy(state = WorkStreamState.PROCESSING,
            processingStartedAt = now.minusSeconds(600), checkAt = now.minusSeconds(1))
        assertTrue(DirectoryWorkProjection.matches(work, ProjectDirectoryFilter.PROCESSING, now))
        assertTrue(DirectoryWorkProjection.matches(work, ProjectDirectoryFilter.NEED_YOU, now))
        assertFalse(DirectoryWorkProjection.matches(work.copy(state = WorkStreamState.CHECK,
            processingStartedAt = null), ProjectDirectoryFilter.PROCESSING, now))
    }
    @Test fun pausedExternalIsNotReadyOrRunning() {
        val work = stream.copy(externalTrackingPaused = true)
        assertTrue(DirectoryWorkProjection.matches(work, ProjectDirectoryFilter.PAUSED, now))
        assertFalse(DirectoryWorkProjection.matches(work, ProjectDirectoryFilter.READY, now))
        assertFalse(DirectoryWorkProjection.matches(work, ProjectDirectoryFilter.PROCESSING, now))
    }
    @Test fun scheduledTransitionsAtDeadline() {
        val work = stream.copy(state = WorkStreamState.SNOOZED, snoozedUntil = now.plusSeconds(1), checkAt = now.plusSeconds(1))
        assertTrue(DirectoryWorkProjection.matches(work, ProjectDirectoryFilter.SNOOZED, now))
        assertTrue(DirectoryWorkProjection.matches(work, ProjectDirectoryFilter.NEED_YOU, now.plusSeconds(1)))
    }
    @Test fun searchMatchesAncestryAndCompletedLeavesAreExcluded() {
        val tasks = listOf(task("leaf", WorkStreamState.READY), task("done", WorkStreamState.DONE).copy(status = TaskStatus.DONE))
        assertEquals(listOf("leaf"), DirectoryWorkProjection.items(listOf(project), listOf(stream), tasks,
            emptyList(), ProjectDirectoryFilter.READY, "Project", now).map { it.work.id })
    }
    @Test fun emptyStreamAndProjectlessTaskRemainVisible() {
        val orphan = task("orphan", WorkStreamState.READY).copy(workStreamId = null)
        val rows = DirectoryWorkProjection.items(listOf(project), listOf(stream), listOf(orphan),
            emptyList(), ProjectDirectoryFilter.READY, "", now)
        assertEquals(2, rows.size)
        assertNull(rows.first { it.work.id == "orphan" }.project)
    }
}
