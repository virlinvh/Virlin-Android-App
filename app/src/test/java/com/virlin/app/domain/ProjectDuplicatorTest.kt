package com.virlin.app.domain

import com.virlin.app.domain.model.*
import com.virlin.app.domain.mappreview.*
import com.virlin.app.domain.notedoc.*
import com.virlin.app.domain.project.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProjectDuplicatorTest {
    private val at = Instant.parse("2026-10-11T00:00:00Z")
    private suspend fun fixture(): InMemoryWorkStreamRepository {
        val repo = InMemoryWorkStreamRepository()
        repo.transaction {
            saveProject(Project("p", "Original", defaultExecutionMode = EffectiveExecutionMode.EXTERNAL, createdAt = at, updatedAt = at))
            saveProject(Project("other", "Unrelated", createdAt = at, updatedAt = at))
            saveStream(WorkStream("w", "Stream", projectId = "p", state = WorkStreamState.PROCESSING,
                executionPreference = ExecutionPreference.INHERIT, processingStartedAt = at, checkAt = at.plusSeconds(60),
                externalTrackedSeconds = 20, cycleCount = 2, activeTaskId = "deep", createdAt = at, updatedAt = at))
            saveTask(Task("root", "Root", projectId = "p", workStreamId = "w", status = TaskStatus.DONE,
                completedAt = at, dueAt = at, createdAt = at, updatedAt = at))
            saveTask(Task("deep", "Deep", projectId = "p", workStreamId = "w", parentTaskId = "root",
                executionPreference = ExecutionPreference.HUMAN, notes = "Keep me", reminderAt = at,
                execution = TaskExecution(WorkStreamState.FOCUS), createdAt = at, updatedAt = at))
            saveTask(Task("solo", "Standalone", projectId = "p", createdAt = at, updatedAt = at))
            saveStep(TaskStep("step", "deep", "Checklist", done = true, createdAt = at, updatedAt = at))
            saveCapture(CaptureItem("cap", CaptureType.NOTE, "Knowledge", projectId = "p", taskId = "deep", createdAt = at, updatedAt = at))
            saveNoteDocument(NoteDocument("doc", "cap", "Title", listOf(NoteBlock("block", NoteBlockType.CHECKBOX, "Keep text", checked = true)), at, at))
            saveNoteDoc(VirlinNoteDoc("task-deep", "Rich", listOf(NoteDocBlock("r", NoteDocBlockType.CHECKLIST, "Rich text", checked = true)), at, at, 7))
            savePageBlock(TaskPageBlock("pb", "deep", TaskPageTypeKeys.capture(CaptureType.NOTE), "cap", 0, at, at))
            savePageBlock(TaskPageBlock("pn", "deep", TaskPageTypeKeys.NOTE, "task-deep", 1, at, at))
            saveTag(ProjectTag("tag", "p", "Research", at, at))
            saveLink(TagLink("tag", TagTargetType.TASK, "deep", at))
            saveMapNodePreview(MapNodePreview("deep", FormattedText.plain("Preview"), expanded = true))
            saveFocusSession(FocusSession("focus", "w", null, at, taskId = "deep"))
            saveCapture(CaptureItem("unrelated-cap", CaptureType.NOTE, "Not copied", projectId = "other", createdAt = at, updatedAt = at))
        }
        return repo
    }
    @Test fun structureCopyHasNewIdsFreshActivityAndPreservesOriginal() = runTest {
        val repo = fixture(); val source = repo.projectSnapshot("p")
        val result = ProjectDuplicator(repo).duplicate(source, "Structure", ProjectCopyMode.STRUCTURE, at.plusSeconds(1), { _, _, _ -> error("No media") })
        val copy = repo.projectSnapshot(result.id)
        assertEquals(3, copy.tasks.size); assertEquals(1, copy.streams.size)
        assertTrue(copy.tasks.none { it.id in source.tasks.map { t -> t.id } })
        assertTrue(copy.tasks.all { it.status == TaskStatus.TODO && it.execution == null && it.completedAt == null && it.dueAt == null && it.reminderAt == null })
        val ws = copy.streams.single()
        assertEquals(WorkStreamState.READY, ws.state); assertNull(ws.processingStartedAt); assertNull(ws.checkAt)
        assertNull(ws.activeTaskId); assertEquals(0L, ws.externalTrackedSeconds); assertEquals(0, ws.cycleCount)
        assertEquals(ExecutionPreference.INHERIT, ws.executionPreference)
        assertEquals(ExecutionPreference.HUMAN, copy.tasks.first { it.title == "Deep" }.executionPreference)
        assertTrue(copy.captures.isEmpty()); assertTrue(copy.steps.isEmpty()); assertTrue(copy.previews.isEmpty())
        assertTrue(copy.tasks.all { it.notes == null })
        assertEquals(source, repo.projectSnapshot("p")); assertEquals(1, repo.allFocusSessions().size)
    }
    @Test fun fullCopyRemapsAllContentAndResetsCheckboxes() = runTest {
        val repo = fixture(); val source = repo.projectSnapshot("p")
        val result = ProjectDuplicator(repo).duplicate(source, "Full", ProjectCopyMode.FULL, at.plusSeconds(1), { _, _, _ -> error("No media") })
        val copy = repo.projectSnapshot(result.id)
        val deep = copy.tasks.first { it.title == "Deep" }
        assertEquals("Keep me", deep.notes)
        assertEquals(copy.tasks.first { it.title == "Root" }.id, deep.parentTaskId)
        assertEquals(deep.id, copy.captures.single().taskId)
        assertEquals(copy.captures.single().id, copy.notes.single().captureItemId)
        assertEquals("Keep text", copy.notes.single().blocks.single().plainText)
        assertFalse(copy.notes.single().blocks.single().checked)
        assertEquals("task-${deep.id}", copy.richNotes.single().ownerKey)
        assertFalse(copy.richNotes.single().blocks.single().checked)
        assertFalse(copy.steps.single().done)
        assertEquals(deep.id, copy.steps.single().taskId)
        assertEquals(deep.id, copy.links.single().targetId)
        assertEquals(copy.tags.single().id, copy.links.single().tagId)
        assertEquals(deep.id, copy.previews.single().ownerId); assertFalse(copy.previews.single().expanded)
        assertEquals(copy.captures.single().id, copy.pageBlocks.first { it.typeKey.startsWith("capture.") }.contentId)
        assertTrue(copy.captures.none { it.content == "Not copied" })
        assertEquals(source, repo.projectSnapshot("p"))
    }
    @Test fun invalidPageContentRollsBackEntireCopy() = runTest {
        val repo = fixture()
        repo.transaction { savePageBlock(TaskPageBlock("bad", "deep", "unknown.content", "missing", 2, at, at)) }
        val source = repo.projectSnapshot("p")
        try { ProjectDuplicator(repo).duplicate(source, "Bad", ProjectCopyMode.FULL, at, { _, _, _ -> "copy" }); fail("Must reject") }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, repo.projects.value.size); assertEquals(source, repo.projectSnapshot("p"))
    }
    @Test fun changedSourceRefusesStaleCopy() = runTest {
        val repo = fixture(); val source = repo.projectSnapshot("p")
        repo.transaction { saveTask(getTask("deep")!!.copy(title = "Changed")) }
        try { ProjectDuplicator(repo).duplicate(source, "Bad", ProjectCopyMode.STRUCTURE, at, { _, _, _ -> "copy" }); fail("Must reject") }
        catch (_: IllegalArgumentException) { }
        assertEquals(2, repo.projects.value.size)
    }
}
