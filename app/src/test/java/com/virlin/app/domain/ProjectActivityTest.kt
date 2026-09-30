package com.virlin.app.domain

import com.virlin.app.domain.activity.ActivityEntryKind
import com.virlin.app.domain.activity.ProjectActivity
import com.virlin.app.domain.activity.TaskEventDetail
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import com.virlin.app.domain.model.WorkStreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Project Activity is a record of what happened, so these tests care about two things above
 * all: nothing from another project can appear, and a past event is described from its own
 * snapshot rather than from the current state of the thing it refers to.
 */
class ProjectActivityTest {

    private val t0 = Instant.parse("2026-09-20T09:00:00Z")
    private val project = Project(id = "p1", title = "Virlin", createdAt = t0, updatedAt = t0)
    private val other = Project(id = "p2", title = "Other", createdAt = t0, updatedAt = t0)

    private fun stream(id: String, projectId: String?) = WorkStream(
        id = id, title = "Stream $id", state = WorkStreamState.READY,
        projectId = projectId, createdAt = t0, updatedAt = t0
    )

    private fun task(id: String, streamId: String?, title: String) = Task(
        id = id, title = title, projectId = project.id, workStreamId = streamId,
        createdAt = t0, updatedAt = t0
    )

    private fun capture(id: String, projectId: String?, type: CaptureType, content: String) =
        CaptureItem(
            id = id, type = type, content = content, projectId = projectId,
            createdAt = t0.plusSeconds(60), updatedAt = t0.plusSeconds(60)
        )

    @Test
    fun onlyThisProjectsStreamsAndCapturesAppear() {
        val streams = listOf(stream("a", project.id), stream("b", other.id))
        val events = listOf(
            WorkStreamEvent("e1", "a", EventType.FOCUS_STARTED, t0),
            WorkStreamEvent("e2", "b", EventType.FOCUS_STARTED, t0)
        )
        val captures = listOf(
            capture("c1", project.id, CaptureType.NOTE, "mine"),
            capture("c2", other.id, CaptureType.NOTE, "theirs"),
            capture("c3", null, CaptureType.NOTE, "unfiled")
        )
        val feed = ProjectActivity.build(project, streams, emptyList(), events, captures)
        assertEquals(listOf("capture:c1", "e1"), feed.map { it.id })
    }

    @Test
    fun newestFirstAndStableAcrossEqualTimestamps() {
        val streams = listOf(stream("a", project.id))
        val events = listOf(
            WorkStreamEvent("e1", "a", EventType.FOCUS_STARTED, t0),
            WorkStreamEvent("e2", "a", EventType.READY, t0.plusSeconds(120))
        )
        val feed = ProjectActivity.build(project, streams, emptyList(), events, emptyList())
        assertEquals(listOf("e2", "capture", "e1").filter { it != "capture" }, feed.map { it.id })
    }

    @Test
    fun aRepeatedEventIdIsOneRow() {
        val streams = listOf(stream("a", project.id))
        val duplicate = WorkStreamEvent("e1", "a", EventType.FOCUS_STARTED, t0)
        val feed = ProjectActivity.build(project, streams, emptyList(), listOf(duplicate, duplicate), emptyList())
        assertEquals(1, feed.size)
    }

    @Test
    fun aTaskEventReadsFromItsOwnSnapshot_notFromTheTaskToday() {
        val streams = listOf(stream("a", project.id))
        val events = listOf(
            WorkStreamEvent(
                "e1", "a", EventType.TASK_COMPLETED, t0,
                detail = TaskEventDetail.encode("t1", "Draft the spec", TaskStatus.IN_PROGRESS, TaskStatus.DONE)
            )
        )
        // The task has since been renamed; the event must not borrow the new title.
        val tasks = listOf(task("t1", "a", "Renamed later"))
        val entry = ProjectActivity.build(project, streams, tasks, events, emptyList()).single()
        assertEquals(ActivityEntryKind.TASK, entry.kind)
        assertEquals("Completed Draft the spec", entry.title)
        assertEquals("In progress", entry.fromStatus)
        assertEquals("Done", entry.toStatus)
        assertTrue(entry.hasSnapshot)
    }

    @Test
    fun historyWrittenBeforeSnapshotsSaysSo() {
        val streams = listOf(stream("a", project.id))
        val events = listOf(WorkStreamEvent("e1", "a", EventType.TASK_COMPLETED, t0, detail = "t1"))
        val entry = ProjectActivity.build(project, streams, listOf(task("t1", "a", "Now")), events, emptyList()).single()
        assertFalse(entry.hasSnapshot)
        // It may still resolve the task it references — it just cannot claim the old title.
        assertEquals("t1", entry.taskId)
    }

    @Test
    fun capturesKeepTheirTextVerbatimAndMayHaveNoTask() {
        val body = "line one\n\n- bullet\n```code```"
        val entry = ProjectActivity.build(
            project, emptyList(), emptyList(), emptyList(),
            listOf(capture("c1", project.id, CaptureType.PROMPT, body))
        ).single()
        assertEquals(ActivityEntryKind.PROMPT, entry.kind)
        assertEquals(body, entry.content)
        assertEquals(null, entry.taskId)
        assertEquals(null, entry.workStreamId)
    }

    @Test
    fun anEmptyProjectHasAnEmptyRecord() {
        assertTrue(
            ProjectActivity.build(project, listOf(stream("a", project.id)), emptyList(), emptyList(), emptyList())
                .isEmpty()
        )
    }

    @Test
    fun preSnapshotDetailStillDecodes() {
        assertEquals("t1", TaskEventDetail.decode("t1")?.taskId)
        assertEquals(null, TaskEventDetail.decode("t1")?.title)
        val encoded = TaskEventDetail.encode("t1", "Title", TaskStatus.TODO, TaskStatus.DONE)
        val decoded = TaskEventDetail.decode(encoded)!!
        assertEquals("Title", decoded.title)
        assertEquals(TaskStatus.DONE, decoded.toStatus)
    }
}
