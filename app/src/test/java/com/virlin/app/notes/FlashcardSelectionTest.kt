package com.virlin.app.notes

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.VirlinNoteCodec
import com.virlin.app.domain.notedoc.flashcardSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlashcardSelectionTest {
    @Test fun selectionKeepsOnlyIntersectingFormattingAndSurvivesPersistenceCodec() {
        val original = NoteDocBlock("source", NoteDocBlockType.PARAGRAPH,
            "A clear contract matters", listOf(
                NoteDocRun(2, 7, setOf(NoteDocMark.BOLD)),
                NoteDocRun(8, 16, setOf(NoteDocMark.CODE), linkUrl = "https://example.com")))
        val selected = flashcardSelection(original, 2, 16)!!
        assertEquals("clear contract", selected.text)
        assertEquals(0, selected.runs.first().start)
        assertEquals(5, selected.runs.first().end)
        assertEquals(6, selected.runs.last().start)
        assertEquals(14, selected.runs.last().end)
        assertEquals("https://example.com", selected.runs.last().linkUrl)
        assertEquals(selected, VirlinNoteCodec.decode(VirlinNoteCodec.encode(listOf(selected))).single())
        assertEquals("A clear contract matters", original.text)
    }

    @Test fun emptyAndNonTextSelectionsDoNotCreateCards() {
        assertNull(flashcardSelection(NoteDocBlock("a", text = "  "), 0, 2))
        assertNull(flashcardSelection(NoteDocBlock("b", NoteDocBlockType.IMAGE), 0, 2))
    }
}
