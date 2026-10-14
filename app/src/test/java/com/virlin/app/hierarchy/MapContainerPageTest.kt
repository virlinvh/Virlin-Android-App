package com.virlin.app.hierarchy

import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskPageBlock
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.model.TaskStep
import com.virlin.app.ui.hierarchy.KnowledgeItem
import com.virlin.app.ui.hierarchy.KnowledgeType
import com.virlin.app.ui.hierarchy.mapPageSections
import com.virlin.app.ui.hierarchy.mapTaskPageEntries
import com.virlin.app.ui.hierarchy.taskPageBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class MapContainerPageTest {
    private val now = Instant.EPOCH
    private val stream = WorkStream("ws", "Design", projectId = "p", state = WorkStreamState.READY,
        createdAt = now, updatedAt = now)
    private val task = Task("task", "Sketch", projectId = "p", workStreamId = "ws",
        createdAt = now, updatedAt = now)
    private val child = Task("child", "Details", projectId = "p", workStreamId = "ws",
        parentTaskId = "task", createdAt = now, updatedAt = now)
    private val items = listOf(
        KnowledgeItem("general", "p", KnowledgeType.DOCUMENT, "General", updatedAtMillis = 1),
        KnowledgeItem("stream", "p", KnowledgeType.DOCUMENT, "Stream", workstreamId = "ws", updatedAtMillis = 2),
        KnowledgeItem("task-note", "p", KnowledgeType.DOCUMENT, "Task", workstreamId = "ws", taskId = "task", updatedAtMillis = 3),
        KnowledgeItem("child-note", "p", KnowledgeType.DOCUMENT, "Child", workstreamId = "ws", taskId = "child", updatedAtMillis = 4))

    @Test fun projectPageSeparatesDirectKnowledgeFromWholeMap() {
        assertEquals(listOf("general"), mapPageSections(items, listOf(stream), listOf(task, child), null, false)
            .flatMap { it.items }.map { it.id })
        val sections = mapPageSections(items, listOf(stream), listOf(task, child), null, true)
        assertEquals(listOf("general", "stream", "task-note", "child-note"),
            sections.flatMap { it.items }.map { it.id })
        assertEquals(listOf("Project Knowledge", "Workstream · Design", "Task · Sketch", "Subtask · Sketch › Details"),
            sections.map { it.title })
    }

    @Test fun workstreamPageShowsOnlyItsOwnBranch() {
        assertEquals(listOf("stream"), mapPageSections(items, listOf(stream), listOf(task, child), "ws", false)
            .flatMap { it.items }.map { it.id })
        assertEquals(listOf("stream", "task-note", "child-note"),
            mapPageSections(items, listOf(stream), listOf(task, child), "ws", true)
                .flatMap { it.items }.map { it.id })
    }

    @Test fun taskPageBlocksIncludeNotesAndTodosOnlyInsideThisProject() {
        val other = Task("other", "Outside", projectId = "other-project", createdAt = now, updatedAt = now)
        val blocks = listOf(
            TaskPageBlock("todo", "child", TaskPageTypeKeys.TODO, "child", 0, now, now),
            TaskPageBlock("note", "task", TaskPageTypeKeys.NOTE, "task-task", 0, now, now),
            TaskPageBlock("capture", "task", TaskPageTypeKeys.capture(com.virlin.app.domain.model.CaptureType.LINK), "link", 1, now, now),
            TaskPageBlock("outside", "other", TaskPageTypeKeys.TODO, "other", 0, now, now))
        val entries = mapTaskPageEntries(blocks, listOf(task, child, other), "p", "ws")
        assertEquals(listOf("note", "todo"), entries.map { it.block.id })
        assertEquals(listOf("Sketch", "Sketch › Details"), entries.map { it.taskPath })
    }

    @Test fun masterDocumentShowsChecklistContentsNotJustTodoLabel() {
        val steps = listOf(TaskStep("one", "child", "First action", false, 0, now, now),
            TaskStep("two", "child", "Finished action", true, 1, now, now),
            TaskStep("other", "task", "Not in this subtask", false, 0, now, now))
        assertEquals("☐ First action\n☑ Finished action",
            taskPageBody(TaskPageTypeKeys.TODO, "child", steps, null))
    }
}
