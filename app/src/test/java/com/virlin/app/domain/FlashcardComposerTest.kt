package com.virlin.app.domain

import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.editFlashcardSide
import com.virlin.app.domain.notedoc.flashcardSideFromSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashcardComposerTest {
    @Test fun choosingSideDoesNotConsumeSourceAndRetainsMarks() {
        val source = NoteDocBlock("source", text = "Heading\nAnswer",
            runs = listOf(NoteDocRun(0, 7, setOf(NoteDocMark.BOLD)),
                NoteDocRun(8, 14, setOf(NoteDocMark.ITALIC))))
        val front = flashcardSideFromSelection(source, TextRange(0, 7))!!
        val back = flashcardSideFromSelection(source, TextRange(8, 14))!!
        assertEquals("Heading", front.text)
        assertEquals("Answer", back.text)
        assertTrue(front.runs.any { NoteDocMark.BOLD in it.marks })
        assertTrue(back.runs.any { NoteDocMark.ITALIC in it.marks })
        assertEquals("Heading\nAnswer", source.text)
    }

    @Test fun editingSideShiftsFormattingButLeavesOriginalAlone() {
        val side = NoteDocBlock("front", text = "Bold text",
            runs = listOf(NoteDocRun(0, 4, setOf(NoteDocMark.BOLD))))
        val changed = editFlashcardSide(side, "Bold new text")
        assertEquals("Bold new text", changed.text)
        assertEquals("Bold text", side.text)
        assertTrue(changed.runs.any { NoteDocMark.BOLD in it.marks })
    }
}
