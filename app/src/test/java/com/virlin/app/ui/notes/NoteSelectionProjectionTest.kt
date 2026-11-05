package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSelectionProjectionTest {
    @Test fun headingsBodyCodeAndTableStayInDocumentOrder() {
        val blocks = listOf(
            NoteDocBlock("h1", NoteDocBlockType.HEADING_1, "Main heading", isCollapsed = true),
            NoteDocBlock("h2", NoteDocBlockType.HEADING_2, "Subheading"),
            NoteDocBlock("p", text = "Important body"),
            NoteDocBlock("code", NoteDocBlockType.CODE, "\\frac{a}{b}"),
            NoteDocBlock("table", NoteDocBlockType.TABLE,
                table = NoteDocTable(2, 2, listOf("Name", "Value", "Total", "191"))),
        )
        val selected = selectableNoteText(blocks)
        assertEquals("Main heading\n\nSubheading\n\nImportant body\n\n" +
            "\\frac{a}{b}\n\nName\t|\tValue\nTotal\t|\t191", selected.text)
        assertTrue(selected.spanStyles.isNotEmpty())
    }

    @Test fun copiedCrossBlockPassageRetainsHeadingAndInlineBold() {
        val blocks = listOf(
            NoteDocBlock("h", NoteDocBlockType.HEADING_2, "Important heading"),
            NoteDocBlock("p", text = "Read this answer",
                runs = listOf(NoteDocRun(10, 16, setOf(NoteDocMark.BOLD)))),
        )
        val source = flashcardSourceFromCopiedText(blocks,
            "Important heading\n\nRead this answer")!!
        assertEquals("Important heading\n\nRead this answer", source.text)
        assertTrue(source.runs.any { it.start == 0 && NoteDocMark.BOLD in it.marks })
        assertTrue(source.runs.any { it.start >= 19 && NoteDocMark.BOLD in it.marks })
    }

    @Test fun directSelectionUsesExactRepeatedPassageOccurrence() {
        val blocks = listOf(
            NoteDocBlock("first", text = "Same text"),
            NoteDocBlock("second", text = "Same text",
                runs = listOf(NoteDocRun(0, 9, setOf(NoteDocMark.BOLD)))),
        )
        val secondStart = selectableNoteText(blocks).text.lastIndexOf("Same text")
        val source = flashcardSourceFromCopiedText(blocks, "Same text", secondStart)!!
        assertTrue(source.runs.any { NoteDocMark.BOLD in it.marks })
    }
}
