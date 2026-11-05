package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDocumentLayoutTest {
    @Test fun bodyHasComfortableLineAndParagraphSpacing() {
        val body = NoteDocBlock("body", text = "A readable paragraph")
        assertEquals(27.2f, NoteDocumentLayout.lineHeightSp(body), .01f)
        assertEquals(14, NoteDocumentLayout.afterDp(body, body.copy(id = "next")))
    }

    @Test fun headingHasMoreSeparationAboveThanBelow() {
        val heading = NoteDocBlock("heading", NoteDocBlockType.HEADING_2, "Section")
        assertEquals(0, NoteDocumentLayout.beforeDp(heading, 0))
        assertEquals(12, NoteDocumentLayout.beforeDp(heading, 1))
        assertEquals(10, NoteDocumentLayout.afterDp(heading, null))
        assertEquals(28.6f, NoteDocumentLayout.lineHeightSp(heading), .01f)
    }

    @Test fun consecutiveListItemsStayGroupedButParagraphsAreSeparated() {
        for (type in listOf(NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED, NoteDocBlockType.CHECKLIST)) {
            val item = NoteDocBlock("item", type, "One")
            assertEquals(2, NoteDocumentLayout.afterDp(item, item.copy(id = "next")))
            assertEquals(14, NoteDocumentLayout.afterDp(item, NoteDocBlock("p", text = "After list")))
            assertEquals(14, NoteDocumentLayout.afterDp(item, item.copy(indent = 1)))
        }
    }

    @Test fun explicitRunSizesAndLineSpacingArePreservedWithoutChangingData() {
        val block = NoteDocBlock("styled", text = "Small and large", lineSpacing = 1.5f,
            runs = listOf(NoteDocRun(0, 5, fontSizeSp = 12),
                NoteDocRun(10, 15, marks = setOf(NoteDocMark.BOLD), fontSizeSp = 30)))
        val before = block.copy(runs = block.runs.toList())
        assertEquals(30f * 1.7f * 1.5f, NoteDocumentLayout.lineHeightSp(block), .01f)
        NoteDocumentLayout.beforeDp(block, 1)
        NoteDocumentLayout.afterDp(block, null)
        assertEquals(before, block)
    }

    @Test fun quoteAndCodeHaveDistinctRhythmWithoutRewritingTheirText() {
        val code = NoteDocBlock("code", NoteDocBlockType.CODE, "  val x = 1\n\n")
        assertEquals(14f * 1.6f, NoteDocumentLayout.lineHeightSp(code), .01f)
        assertEquals(16, NoteDocumentLayout.afterDp(code, null))
        assertEquals("  val x = 1\n\n", code.text)
        assertEquals(16, NoteDocumentLayout.afterDp(NoteDocBlock("quote", NoteDocBlockType.QUOTE, "Quote"), null))
    }

    @Test fun everyBlockTypeGetsUsableLayout() {
        NoteDocBlockType.entries.forEach { type ->
            val block = NoteDocBlock("test", type, "Text")
            assertTrue(NoteDocumentLayout.lineHeightSp(block).isFinite())
            assertTrue(NoteDocumentLayout.afterDp(block, null) >= 0)
        }
    }
}
