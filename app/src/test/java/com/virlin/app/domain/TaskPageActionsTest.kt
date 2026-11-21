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

    @Test fun add_note_reuses_an_unwritten_task_note_then_always_starts_a_new_one() = runTest {
        val pages = TaskPageActions(repo, clock, ids)
        val virlin = DefaultVirlinActions(repo, clock, ids)
        val first = pages.newNote(task.id)!!
        assertEquals(TaskPageTypeKeys.noteOwner(task.id), first.ownerKey)
        assertEquals("Task", first.title)
        assertEquals(first, pages.newNote(task.id))
        virlin.saveNoteDoc(first.ownerKey, first.title, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("a", text = "one")))

        val second = pages.newNote(task.id)!!
        val third = pages.newNote(task.id)!!
        assertTrue(TaskPageTypeKeys.isExtraNoteOwner(second.ownerKey))
        assertNotEquals(second.ownerKey, third.ownerKey)
        assertEquals("Task 2", second.title)
        assertEquals("Task 3", third.title)
        assertEquals(task.id, TaskPageTypeKeys.noteTaskId(second.ownerKey))
        assertEquals(listOf(first.ownerKey, second.ownerKey, third.ownerKey),
            repo.getTaskPageBlocks(task.id).filter { it.typeKey == TaskPageTypeKeys.NOTE }.map { it.contentId })
        assertNull(pages.newNote("missing"))
    }

    @Test fun duplicating_a_note_copies_its_document_and_lists_the_copy_right_after_it() = runTest {
        val pages = TaskPageActions(repo, clock, ids)
        val virlin = DefaultVirlinActions(repo, clock, ids)
        val first = pages.newNote(task.id)!!
        virlin.saveNoteDoc(first.ownerKey, "Plan", listOf(com.virlin.app.domain.notedoc.NoteDocBlock("a", text = "one")))
        val second = pages.newNote(task.id)!!
        virlin.saveNoteDoc(second.ownerKey, second.title, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("b", text = "two")))
        val source = repo.getNoteDoc(first.ownerKey)!!

        val copy = pages.duplicateNote(first.ownerKey)!!
        assertEquals("Plan (copy)", copy.title)
        assertTrue(TaskPageTypeKeys.isExtraNoteOwner(copy.ownerKey))
        val stored = repo.getNoteDoc(copy.ownerKey)!!
        assertEquals(source.blocks, stored.blocks)
        assertEquals(source.richDocument, stored.richDocument)
        assertEquals("Plan (copy)", stored.title)
        assertEquals(listOf(first.ownerKey, copy.ownerKey, second.ownerKey),
            pages.loadAndReconcile(task.id).filter { it.typeKey == TaskPageTypeKeys.NOTE }.map { it.contentId })

        virlin.saveNoteDoc(copy.ownerKey, copy.title, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("c", text = "edited")))
        assertEquals(source.blocks, repo.getNoteDoc(first.ownerKey)!!.blocks)
        assertNull(pages.duplicateNote(TaskPageTypeKeys.extraNoteOwner(task.id, "never-saved")))
        assertNull(pages.duplicateNote("capture-x"))
    }

    @Test fun duplicate_here_copies_notes_and_captures_right_after_the_original_but_not_the_checklist() = runTest {
        val pages = TaskPageActions(repo, clock, ids)
        val virlin = DefaultVirlinActions(repo, clock, ids)
        val note = pages.newNote(task.id)!!
        virlin.saveNoteDoc(note.ownerKey, "Plan", listOf(com.virlin.app.domain.notedoc.NoteDocBlock("a", text = "one")))
        val link = (virlin.createCapture(CreateCapture(type = CaptureType.LINK, content = "{}", title = "Docs",
            sourceUrl = "https://example.com", context = CaptureContext(taskId = task.id))) as ActionResult.Success).value
        pages.ensure(task.id, TaskPageTypeKeys.TODO, task.id)
        val blocks = pages.loadAndReconcile(task.id)

        assertTrue(pages.duplicateHere(blocks.first { it.typeKey == TaskPageTypeKeys.NOTE }.id))
        assertTrue(pages.duplicateHere(blocks.first { it.contentId == link.id }.id))
        assertFalse(pages.duplicateHere(blocks.first { it.typeKey == TaskPageTypeKeys.TODO }.id))

        val after = pages.loadAndReconcile(task.id)
        assertEquals(5, after.size)
        assertEquals(TaskPageTypeKeys.NOTE, after[1].typeKey)
        assertEquals("Plan (copy)", repo.getNoteDoc(after[1].contentId)!!.title)
        assertEquals(link.id, after[2].contentId)
        val copy = repo.getCapture(after[3].contentId)!!
        assertNotEquals(link.id, copy.id)
        assertEquals("Docs (copy)", copy.title)
        assertEquals(task.id, copy.taskId)
        assertEquals("Docs", repo.getCapture(link.id)!!.title)
    }

    @Test fun deleting_from_the_page_removes_a_note_archives_a_capture_and_refuses_the_checklist() = runTest {
        val pages = TaskPageActions(repo, clock, ids)
        val virlin = DefaultVirlinActions(repo, clock, ids)
        val note = pages.newNote(task.id)!!
        virlin.saveNoteDoc(note.ownerKey, "Plan", listOf(com.virlin.app.domain.notedoc.NoteDocBlock("a", text = "one")))
        val kept = pages.newNote(task.id)!!
        virlin.saveNoteDoc(kept.ownerKey, kept.title, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("b", text = "two")))
        val link = (virlin.createCapture(CreateCapture(type = CaptureType.LINK, content = "{}",
            sourceUrl = "https://example.com", context = CaptureContext(taskId = task.id))) as ActionResult.Success).value
        pages.ensure(task.id, TaskPageTypeKeys.TODO, task.id)
        val blocks = pages.loadAndReconcile(task.id)

        assertTrue(pages.delete(blocks.first { it.contentId == note.ownerKey }.id))
        assertNull(repo.getNoteDoc(note.ownerKey))
        assertNotNull(repo.getNoteDoc(kept.ownerKey))
        assertTrue(pages.delete(blocks.first { it.contentId == link.id }.id))
        assertEquals(CaptureStatus.ARCHIVED, repo.getCapture(link.id)!!.status)
        assertFalse(pages.delete(blocks.first { it.typeKey == TaskPageTypeKeys.TODO }.id))

        // Reopening does not bring either back, and the rest keep a dense order.
        val after = pages.loadAndReconcile(task.id)
        assertEquals(listOf(kept.ownerKey, task.id), after.map { it.contentId })
        assertEquals(listOf(0, 1), after.map { it.order })
        // The deleted note's task can still start a fresh note later.
        assertEquals(note.ownerKey, pages.newNote(task.id)!!.ownerKey)
    }

    @Test fun moving_a_note_carries_its_whole_document_to_the_other_task() = runTest {
        val pages = TaskPageActions(repo, clock, ids)
        val virlin = DefaultVirlinActions(repo, clock, ids)
        val note = pages.newNote(task.id)!!
        virlin.saveNoteDoc(note.ownerKey, "Plan", listOf(com.virlin.app.domain.notedoc.NoteDocBlock("a", text = "one")))
        val kept = pages.newNote(task.id)!!
        virlin.saveNoteDoc(kept.ownerKey, kept.title, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("b", text = "two")))
        val source = repo.getNoteDoc(note.ownerKey)!!
        val block = pages.loadAndReconcile(task.id).first { it.contentId == note.ownerKey }

        assertNull(pages.moveNote(block.id, task.id))
        val owner = pages.moveNote(block.id, other.id)!!
        assertEquals(other.id, TaskPageTypeKeys.noteTaskId(owner))
        val moved = repo.getNoteDoc(owner)!!
        assertEquals(source.blocks, moved.blocks)
        assertEquals(source.richDocument, moved.richDocument)
        assertEquals("Plan", moved.title)
        assertNull(repo.getNoteDoc(note.ownerKey))
        assertEquals(listOf(kept.ownerKey), pages.loadAndReconcile(task.id).map { it.contentId })
        assertEquals(listOf(owner), pages.loadAndReconcile(other.id).map { it.contentId })

        // An empty note has nothing to carry: nothing is written.
        val empty = pages.newNote(task.id)!!
        val emptyBlock = repo.getTaskPageBlocks(task.id).first { it.contentId == empty.ownerKey }
        assertNull(pages.moveNote(emptyBlock.id, other.id))
        assertEquals(listOf(owner), repo.getTaskPageBlocks(other.id).map { it.contentId })
    }

    @Test fun reopening_the_page_drops_new_notes_left_unwritten_and_keeps_saved_ones() = runTest {
        val pages = TaskPageActions(repo, clock, ids)
        val virlin = DefaultVirlinActions(repo, clock, ids)
        val canonical = pages.newNote(task.id)!!
        virlin.saveNoteDoc(canonical.ownerKey, null, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("a", text = "one")))
        val written = pages.newNote(task.id)!!
        virlin.saveNoteDoc(written.ownerKey, written.title, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("b", text = "two")))
        val abandoned = pages.newNote(task.id)!!

        val blocks = pages.loadAndReconcile(task.id)
        assertEquals(listOf(canonical.ownerKey, written.ownerKey), blocks.map { it.contentId })
        assertEquals(listOf(0, 1), blocks.map { it.order })
        assertFalse(blocks.any { it.contentId == abandoned.ownerKey })
        assertEquals("two", repo.getNoteDoc(written.ownerKey)!!.blocks.single().text)
    }

    @Test fun deleting_a_task_removes_its_extra_notes_and_refuses_late_saves() = runTest {
        val pages = TaskPageActions(repo, clock, ids)
        val virlin = DefaultVirlinActions(repo, clock, ids)
        val canonical = pages.newNote(task.id)!!
        virlin.saveNoteDoc(canonical.ownerKey, null, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("a", text = "one")))
        val extra = pages.newNote(task.id)!!
        virlin.saveNoteDoc(extra.ownerKey, extra.title, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("b", text = "two")))
        val otherNote = pages.newNote(other.id)!!
        virlin.saveNoteDoc(otherNote.ownerKey, null, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("c", text = "kept")))

        assertTrue(virlin.deleteTask(task.id) is ActionResult.Success)
        assertNull(repo.getNoteDoc(canonical.ownerKey))
        assertNull(repo.getNoteDoc(extra.ownerKey))
        assertNotNull(repo.getNoteDoc(otherNote.ownerKey))
        val late = virlin.saveNoteDoc(extra.ownerKey, extra.title, listOf(com.virlin.app.domain.notedoc.NoteDocBlock("b", text = "late")))
        assertFalse(late is ActionResult.Success)
        assertNull(repo.getNoteDoc(extra.ownerKey))
    }

    @Test fun note_owner_keys_resolve_their_task() {
        val extra = TaskPageTypeKeys.extraNoteOwner("task-abc", "note-1")
        assertEquals("task-task-abc#note-note-1", extra)
        assertEquals("task-abc", TaskPageTypeKeys.noteTaskId(extra))
        assertEquals("task-abc", TaskPageTypeKeys.noteTaskId(TaskPageTypeKeys.noteOwner("task-abc")))
        assertEquals(TaskPageTypeKeys.noteOwner("task-abc"), TaskPageTypeKeys.canonicalNoteOwner(extra))
        assertEquals("capture-x", TaskPageTypeKeys.canonicalNoteOwner("capture-x"))
        assertFalse(TaskPageTypeKeys.isExtraNoteOwner(TaskPageTypeKeys.noteOwner("task-abc")))
        assertNull(TaskPageTypeKeys.noteTaskId("project-p1"))
    }
}
