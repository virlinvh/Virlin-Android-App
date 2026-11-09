package com.virlin.app.domain.notedoc.rich

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.NoteDocMessages
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class RichNoteActionsTest {

    private val repo = InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)))
    private val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())

    private val tree = RichNoteAdapter.fromLegacy(listOf(
        NoteDocBlock("h", NoteDocBlockType.HEADING_1, "Heading"),
        NoteDocBlock("a", NoteDocBlockType.BULLET, "outer"),
        NoteDocBlock("b", NoteDocBlockType.BULLET, "inner", indent = 1),
        NoteDocBlock("t", NoteDocBlockType.TABLE, table = NoteDocTable(1, 2, listOf("x", "y"))),
    ))

    private fun reason(result: ActionResult<*>) =
        ((result as ActionResult.Rejected).reason as DomainError.PlacementRejected).reason

    @Test fun richSaveIsIdempotentAndRevisionChecked() = runTest {
        val first = actions.saveRichNoteDoc("task-1", "T", tree, expectedRevision = 0) as ActionResult.Success
        assertEquals(1, first.value.revision)
        val again = actions.saveRichNoteDoc("task-1", "T", tree, expectedRevision = 1) as ActionResult.Success
        assertEquals(1, again.value.revision)
        assertEquals(NoteDocMessages.STALE, reason(actions.saveRichNoteDoc("task-1", "T", tree, expectedRevision = 0)))
        val loaded = actions.loadNoteDoc("task-1")!!
        assertEquals(RichNoteAdapter.canonical(tree), loaded.richDocument)
        assertEquals(RichNoteAdapter.project(tree), loaded.blocks)
    }

    @Test fun invalidRichDocumentsAreRejectedWithoutWriting() = runTest {
        assertEquals(NoteDocMessages.INVALID_DOCUMENT,
            reason(actions.saveRichNoteDoc("task-1", "T", "{\"type\":\"doc\",\"content\":[", null)))
        assertNull(actions.loadNoteDoc("task-1"))
    }

    @Test fun desktopBlockSavesMergeIntoTheRichTreeOrAreRefused() = runTest {
        // A cell holding two paragraphs cannot be rebuilt by the phone table editor.
        val multiPart = tree.replaceFirst("\"text\":\"x\"}]}",
            "\"text\":\"x\"}]},{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"more\"}]}")
        assertNotEquals(tree, multiPart)
        actions.saveRichNoteDoc("task-1", "T", multiPart, null)
        val blocks = actions.loadNoteDoc("task-1")!!.blocks
        val edited = blocks.map { if (it.id == "b") it.copy(text = "inner edited") else it }
        val saved = actions.saveNoteDoc("task-1", "T", edited, expectedRevision = 1) as ActionResult.Success
        assertNotNull(saved.value.richDocument)
        assertEquals(edited, saved.value.blocks)
        val resized = edited.map { if (it.id == "t") it.copy(table = NoteDocTable(1, 1, listOf("x"))) else it }
        assertEquals(NoteDocMessages.RICH_STRUCTURE, reason(actions.saveNoteDoc("task-1", "T", resized, 2)))
        assertEquals(edited, actions.loadNoteDoc("task-1")!!.blocks)
    }

    @Test fun simpleRichTablesSaveRowColumnHeaderAndShadeChanges() = runTest {
        actions.saveRichNoteDoc("task-1", "T", tree, null)
        val blocks = actions.loadNoteDoc("task-1")!!.blocks
        val changed = blocks.map {
            if (it.id == "t") it.copy(table = it.table!!.withoutColumn(1).withRowAt(1).withCell(1, 0, "new")
                .copy(headerRow = true, gridLines = false).withCellFill(1, 0, 0xFFFFF4C2L).withColumnWidth(0, 160))
            else it
        }
        val saved = actions.saveNoteDoc("task-1", "T", changed, expectedRevision = 1) as ActionResult.Success
        assertNotNull(saved.value.richDocument)
        assertEquals(changed, saved.value.blocks)
        assertEquals(changed, actions.loadNoteDoc("task-1")!!.blocks)
    }

    @Test fun phoneEditorTurnsAHeadingIntoABulletAndBackAndEverySaveIsAccepted() = runTest {
        actions.saveRichNoteDoc("task-1", "T", tree, null)
        val blocks = actions.loadNoteDoc("task-1")!!.blocks
        val bulleted = blocks.map { if (it.id == "h") it.copy(type = NoteDocBlockType.BULLET) else it }
        val saved = actions.saveNoteDoc("task-1", "T", bulleted, expectedRevision = 1) as ActionResult.Success
        assertEquals(bulleted, saved.value.blocks)
        val back = bulleted.map { if (it.id == "h") it.copy(type = NoteDocBlockType.PARAGRAPH) else it }
        val again = actions.saveNoteDoc("task-1", "T", back, expectedRevision = 2) as ActionResult.Success
        assertEquals(back, again.value.blocks)
        assertEquals(back, actions.loadNoteDoc("task-1")!!.blocks)
    }

    @Test fun damagedNotesAreNeverOverwrittenExceptByRecovery() = runTest {
        repo.transaction { saveNoteDoc(VirlinNoteDoc("task-1", "Broken", revision = 4,
            loadError = "not JSON", damagedRaw = "{\"v\":1,\"blocks\":[{\"text\":\"keep me\"")) }
        assertEquals(NoteDocMessages.DAMAGED, reason(actions.saveRichNoteDoc("task-1", "T", tree, 4)))
        assertEquals(NoteDocMessages.DAMAGED, reason(actions.saveNoteDoc("task-1", "T", emptyList(), 4)))
        assertEquals(NoteDocMessages.DAMAGED, reason(actions.saveNoteDoc("task-1", "T", emptyList())))
        val recovered = actions.recoverDamagedNote("task-1") as ActionResult.Success
        assertEquals(5, recovered.value.revision)
        assertEquals("keep me", recovered.value.blocks.single().text)
        assertNull(actions.loadNoteDoc("task-1")!!.loadError)
    }

    @Test fun recoveryCopiesKeepTheRawPayloadReadable() = runTest {
        val key = (actions.saveNoteRecoveryCopy("task-1", "conflict", "{broken") as ActionResult.Success).value
        val copy = actions.loadNoteDoc(key)!!
        assertTrue(copy.blocks.any { it.text == "{broken" })
        val keyRich = (actions.saveNoteRecoveryCopy("task-1", "conflict", tree) as ActionResult.Success).value
        assertEquals(RichNoteAdapter.project(tree), actions.loadNoteDoc(keyRich)!!.blocks)
    }

    @Test fun captureDraftRichSaveDualWritesTheCaptureAndLegacyNote() = runTest {
        val owner = CaptureNoteBridge.draftOwner("p", "rich")
        val saved = actions.saveRichNoteDoc(owner, "Draft", tree, 0) as ActionResult.Success
        assertEquals(1, repo.captures.value.size)
        assertNotNull(saved.value.richDocument)
        val loaded = actions.loadNoteDoc(owner)!!
        assertEquals(RichNoteAdapter.canonical(tree), loaded.richDocument)
        assertTrue(repo.captures.value.single().content.orEmpty().contains("Heading"))
    }

    /** Real notes from the baseline backup, when present locally; never committed. */
    @Test fun baselineUserNotesOpenUnchangedInTheRichEditor() {
        val dir = File("../.tmp/notes-rebuild-baseline-20261105/notes-json")
        assumeTrue(dir.isDirectory)
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assumeTrue(files.isNotEmpty())
        for (file in files) {
            val raw = file.readText()
            val stored = RichNoteFormat.decode(raw)
            assertTrue("${file.name} decodes as legacy", stored is RichNoteFormat.Stored.Legacy)
            val blocks = stored.blocks
            assertTrue(blocks.isNotEmpty())
            val richTree = RichNoteAdapter.fromLegacy(blocks)
            assertEquals("${file.name} projection", blocks, RichNoteAdapter.project(richTree))
            assertEquals(blocks, RichNoteFormat.decode(RichNoteFormat.encode(blocks, richTree)).blocks)
        }
    }
}
