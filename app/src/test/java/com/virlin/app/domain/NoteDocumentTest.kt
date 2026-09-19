package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.InlineStyle
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.model.TextMark
import com.virlin.app.domain.note.NoteDocumentCodec
import com.virlin.app.domain.note.NotePdfModelBuilder
import com.virlin.app.domain.note.NotePlainTextSerializer
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NoteDocumentTest {

    private val clock = FakeClock(Instant.parse("2026-09-14T12:00:00Z"))
    private val ids = SequentialIdProvider()
    private val repo = InMemoryWorkStreamRepository()
    private val actions = DefaultVirlinActions(repo, clock, ids)

    @Test fun codec_roundTripsNestedToggleAndMarks() {
        val child = NoteBlock("c1", NoteBlockType.TEXT, "child")
        val root = NoteBlock(
            id = "t1",
            type = NoteBlockType.TOGGLE,
            plainText = "Open",
            children = listOf(child),
            marks = listOf(TextMark(0, 4, InlineStyle.BOLD))
        )
        val json = NoteDocumentCodec.encodePayload(listOf(root))
        val back = NoteDocumentCodec.decodePayload(json)
        assertEquals(1, back.size)
        assertEquals(NoteBlockType.TOGGLE, back[0].type)
        assertEquals("Open", back[0].plainText)
        assertEquals(InlineStyle.BOLD, back[0].marks.single().style)
        assertEquals("child", back[0].children.single().plainText)
    }

    @Test fun plainText_serializesListsCheckboxToggle() {
        val doc = NoteDocument(
            id = "n", captureItemId = "c", title = "Title",
            blocks = listOf(
                NoteBlock("1", NoteBlockType.HEADING_1, "H"),
                NoteBlock("2", NoteBlockType.BULLETED_LIST, "a"),
                NoteBlock("3", NoteBlockType.NUMBERED_LIST, "b"),
                NoteBlock("4", NoteBlockType.CHECKBOX, "todo", checked = true),
                NoteBlock("5", NoteBlockType.TOGGLE, "tog", children = listOf(NoteBlock("6", NoteBlockType.TEXT, "in"))),
                NoteBlock("7", NoteBlockType.DIVIDER),
                NoteBlock("8", NoteBlockType.QUOTE, "q"),
                NoteBlock("9", NoteBlockType.CODE, "x = 1")
            ),
            createdAt = clock.now(), updatedAt = clock.now()
        )
        val text = NotePlainTextSerializer.serialize(doc)
        assertTrue(text.contains("Title"))
        assertTrue(text.contains("• a"))
        assertTrue(text.contains("1. b"))
        assertTrue(text.contains("✓ todo"))
        assertTrue(text.contains("▸ tog"))
        assertTrue(text.contains("in"))
        assertTrue(text.contains("────────"))
        assertTrue(text.contains("```"))
    }

    @Test fun pdfModel_includesExpandedToggleChildren() {
        val doc = NoteDocument(
            id = "n", captureItemId = "c", title = "T",
            blocks = listOf(
                NoteBlock("1", NoteBlockType.TOGGLE, "Parent", children = listOf(NoteBlock("2", NoteBlockType.TEXT, "Kid")))
            ),
            createdAt = clock.now(), updatedAt = clock.now()
        )
        val model = NotePdfModelBuilder.build(doc)
        assertEquals("T", model.title)
        assertTrue(model.lines.any { it.toString().contains("Parent") })
        assertTrue(model.lines.any { it.toString().contains("Kid") })
    }

    @Test fun createTextNote_persistsCaptureAndDocument() = runTest {
        val blocks = listOf(NoteBlock("b1", NoteBlockType.TEXT, "Hello world"))
        val r = actions.createTextNote(title = "My note", blocks = blocks)
        val doc = (r as ActionResult.Success).value
        assertEquals("My note", doc.title)
        assertNotNull(repo.getCapture(doc.captureItemId))
        assertEquals(doc.id, repo.getNoteByCaptureId(doc.captureItemId)!!.id)
        assertTrue(repo.getCapture(doc.captureItemId)!!.content.contains("Hello"))
    }

    @Test fun createTextNote_rejectsEmpty() = runTest {
        val r = actions.createTextNote(title = null, blocks = listOf(NoteBlock("b1", NoteBlockType.TEXT, "")))
        assertEquals(DomainError.EmptyCapture, (r as ActionResult.Rejected).reason)
        assertTrue(repo.captures.value.isEmpty())
    }

    @Test fun saveTextNote_updatesPreviewAndCheckbox() = runTest {
        val created = (actions.createTextNote("T", listOf(NoteBlock("b1", NoteBlockType.CHECKBOX, "do", checked = false))) as ActionResult.Success).value
        val saved = (actions.saveTextNote(
            created.captureItemId, "T2",
            listOf(NoteBlock("b1", NoteBlockType.CHECKBOX, "do", checked = true))
        ) as ActionResult.Success).value
        assertTrue(saved.blocks.single().checked)
        assertEquals("T2", repo.getCapture(created.captureItemId)!!.title)
        assertTrue(repo.getCapture(created.captureItemId)!!.content.contains("✓"))
    }

    @Test fun hydrate_legacyPlainNote() = runTest {
        val cap = (actions.createCapture(CreateCapture(type = CaptureType.NOTE, content = "legacy body", title = "L")) as ActionResult.Success).value
        val hydrated = (actions.getOrHydrateTextNote(cap.id) as ActionResult.Success).value
        assertEquals("legacy body", hydrated.blocks.single().plainText)
        assertNull(repo.getNoteByCaptureId(cap.id)) // not persisted until save
        actions.saveTextNote(cap.id, hydrated.title, hydrated.blocks)
        assertNotNull(repo.getNoteByCaptureId(cap.id))
    }

    @Test fun nestedToggle_orderingPreserved() = runTest {
        val blocks = listOf(
            NoteBlock("a", NoteBlockType.TEXT, "one"),
            NoteBlock(
                "b", NoteBlockType.TOGGLE, "sec",
                children = listOf(
                    NoteBlock("c", NoteBlockType.BULLETED_LIST, "x"),
                    NoteBlock("d", NoteBlockType.BULLETED_LIST, "y")
                )
            )
        )
        val doc = (actions.createTextNote("N", blocks) as ActionResult.Success).value
        val loaded = repo.getNoteByCaptureId(doc.captureItemId)!!
        assertEquals(listOf("one", "sec"), loaded.blocks.map { it.plainText })
        assertEquals(listOf("x", "y"), loaded.blocks[1].children.map { it.plainText })
    }

    @Test fun attachContext_preservedOnNoteCapture() = runTest {
        val doc = (actions.createTextNote("N", listOf(NoteBlock("b", NoteBlockType.TEXT, "hi")), CaptureContext.None) as ActionResult.Success).value
        // no project in empty repo — attach with None is fine; create with context None
        assertFalse(repo.getCapture(doc.captureItemId)!!.hasContext)
        assertEquals(CaptureType.NOTE, repo.getCapture(doc.captureItemId)!!.type)
    }

    @Test fun archive_keepsNoteDocument() = runTest {
        val doc = (actions.createTextNote("N", listOf(NoteBlock("b", NoteBlockType.TEXT, "hi"))) as ActionResult.Success).value
        actions.archiveCapture(doc.captureItemId)
        assertNotNull(repo.getNoteByCaptureId(doc.captureItemId))
        assertEquals(com.virlin.app.domain.model.CaptureStatus.ARCHIVED, repo.getCapture(doc.captureItemId)!!.status)
    }

    @Test fun emptyDraft_hasMeaningfulFalse() {
        val d = NoteDocument.emptyDraft("n", "c", clock.now(), "b")
        assertFalse(d.hasMeaningfulContent())
        assertTrue(d.copy(title = "x").hasMeaningfulContent())
        assertTrue(d.copy(blocks = listOf(NoteBlock("b", NoteBlockType.DIVIDER))).hasMeaningfulContent())
    }
}
