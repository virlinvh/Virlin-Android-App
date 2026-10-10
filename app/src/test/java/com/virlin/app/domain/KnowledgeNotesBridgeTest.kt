package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.notedoc.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class KnowledgeNotesBridgeTest {
    private fun setup(projectId: String = "p"): Pair<InMemoryWorkStreamRepository, DefaultVirlinActions> {
        val repo = InMemoryWorkStreamRepository(seedProjects = listOf(Project(projectId, "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)))
        return repo to DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
    }
    @Test fun richKnowledgeNoteIsAtomicProjectOwnedAndIdempotent() = runTest {
        val (repo, actions) = setup("project/with spaces")
        val key = CaptureNoteBridge.draftOwner("project/with spaces", "new")
        val blocks = listOf(NoteDocBlock("a", text = "Formatted", runs = listOf(NoteDocRun(0, 9, setOf(NoteDocMark.BOLD), color = 0xFF123456L))),
            NoteDocBlock("b", NoteDocBlockType.TABLE, table = NoteDocTable(1, 2, listOf("Left", "Right"))))
        val result = actions.saveNoteDoc(key, "Note", blocks) as ActionResult.Success
        assertEquals(blocks, actions.loadNoteDoc(CaptureNoteBridge.owner("new"))!!.blocks)
        assertEquals("project/with spaces", repo.captures.value.single().projectId)
        assertNull(repo.captures.value.single().taskId)
        assertEquals(result.value, (actions.saveNoteDoc(key, "Note", blocks) as ActionResult.Success).value)
        assertEquals(1, repo.captures.value.size)
        assertTrue(repo.tasks.value.isEmpty())
        assertTrue(repo.streams.value.isEmpty())
        assertEquals("Left\tRight", actions.getNoteByCaptureId("new")!!.blocks.last().plainText)
    }
    @Test fun emptyDraftAndMissingProjectLeaveNoRows() = runTest {
        val (repo, actions) = setup()
        actions.saveNoteDoc(CaptureNoteBridge.draftOwner("p", "empty"), "New note", listOf(NoteDocBlock("a")))
        assertTrue(repo.captures.value.isEmpty())
        assertNull(repo.getNoteDoc(CaptureNoteBridge.owner("empty")))
        assertTrue(actions.saveNoteDoc(CaptureNoteBridge.draftOwner("missing", "bad"), "Note", listOf(NoteDocBlock("a", text = "Body"))) !is ActionResult.Success)
        assertNull(repo.getNoteDoc(CaptureNoteBridge.owner("bad")))
        assertTrue(repo.captures.value.isEmpty())
    }
    @Test fun legacyNotesImportWithoutMutatingAndPreserveExactOriginalOnFirstSave() = runTest {
        val (repo, actions) = setup()
        val source = listOf(NoteBlock("parent", NoteBlockType.TOGGLE, "Parent", collapsed = true,
            children = listOf(NoteBlock("child", NoteBlockType.CHECKBOX, "Child", checked = true,
                marks = listOf(TextMark(0, 5, InlineStyle.BOLD))))))
        val legacy = (actions.createTextNote("Legacy", source, CaptureContext(projectId = "p")) as ActionResult.Success).value
        val key = CaptureNoteBridge.owner(legacy.captureItemId)
        val migrated = actions.loadNoteDoc(key)!!
        assertEquals(source, actions.getNoteByCaptureId(legacy.captureItemId)!!.blocks)
        assertNull(repo.getNoteDoc(key))
        assertEquals(listOf("Parent", "Child"), migrated.blocks.map { it.text })
        assertEquals(1, migrated.blocks.last().indent)
        assertTrue(migrated.blocks.last().checked)
        assertTrue(actions.saveNoteDoc(key, migrated.title, migrated.blocks) is ActionResult.Success)
        assertEquals(com.virlin.app.domain.note.NoteDocumentCodec.encodePayload(source),
            repo.getNoteDoc("capture-legacy-${legacy.captureItemId}")!!.blocks.single().text)
    }
    @Test fun duplicationRetainsRichFormattingAndProjectDeletionRemovesAllNoteOwners() = runTest {
        val (repo, actions) = setup()
        val blocks = listOf(NoteDocBlock("a", text = "Body", align = NoteDocAlign.CENTER, runs = listOf(NoteDocRun(0, 4, fontFamily = "Inter", fontSizeSp = 22))))
        actions.saveNoteDoc(CaptureNoteBridge.draftOwner("p", "original"), "Original", blocks)
        val copy = (actions.duplicateCaptureNote("original", "Copy") as ActionResult.Success).value
        assertNotEquals("original", copy.id)
        assertEquals(blocks, repo.getNoteDoc(CaptureNoteBridge.owner(copy.id))!!.blocks)
        assertTrue(actions.deleteProject("p") is ActionResult.Success)
        assertNull(repo.getNoteDoc(CaptureNoteBridge.owner("original")))
        assertNull(repo.getNoteDoc(CaptureNoteBridge.owner(copy.id)))
        assertTrue(actions.saveNoteDoc(CaptureNoteBridge.owner("original"), "Resurrect", blocks) !is ActionResult.Success)
        assertTrue(repo.captures.value.isEmpty())
    }
}
