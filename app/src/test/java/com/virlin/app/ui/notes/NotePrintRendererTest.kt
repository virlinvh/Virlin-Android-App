package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocAudio
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteDocVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotePrintRendererTest {
    private val colors = NotePrintColors.of(null)
    private val paragraph = "Printing keeps the note's own formatting, colours and fonts on every page. ".repeat(4)

    private fun longNote() = (1..60).map { i ->
        NoteDocBlock("p$i", NoteDocBlockType.PARAGRAPH, paragraph,
            runs = listOf(NoteDocRun(0, 8, setOf(NoteDocMark.BOLD), color = 0xFFB42318)))
    }

    @Test fun aLongNoteFlowsOntoMorePagesOnSmallerPaper() {
        val source = NotePrintSource("Long note", longNote(), colors)
        val a4 = NotePrintRenderer.layout(source, NotePrintSettings(paper = NotePaper.A4)).size
        val a5 = NotePrintRenderer.layout(source, NotePrintSettings(paper = NotePaper.A5)).size
        val wide = NotePrintRenderer.layout(source, NotePrintSettings(paper = NotePaper.A4, margins = NoteMargins.WIDE)).size
        assertTrue("A4 pages: $a4", a4 > 1)
        assertTrue("A5 $a5 vs A4 $a4", a5 > a4)
        assertTrue("wide margins $wide vs normal $a4", wide >= a4)
    }

    @Test fun everyKindOfBlockLaysOutWithoutFailing() {
        val blocks = listOf(
            NoteDocBlock("h", NoteDocBlockType.HEADING_1, "Plan", toggleHeading = true),
            NoteDocBlock("b", NoteDocBlockType.BULLET, "Point"),
            NoteDocBlock("n1", NoteDocBlockType.NUMBERED, "First", listStyle = "lower-roman"),
            NoteDocBlock("n2", NoteDocBlockType.NUMBERED, "Second"),
            NoteDocBlock("c", NoteDocBlockType.CHECKLIST, "Done", checked = true),
            NoteDocBlock("q", NoteDocBlockType.QUOTE, "A quote"),
            NoteDocBlock("code", NoteDocBlockType.CODE, "fun main() {\n  println(\"hi\")\n}"),
            NoteDocBlock("eq", NoteDocBlockType.CODE, "\\frac{a}{b}", mathMode = true),
            NoteDocBlock("pr", NoteDocBlockType.PROMPT, "Write it", promptTitle = "Prompt"),
            NoteDocBlock("d", NoteDocBlockType.DIVIDER),
            NoteDocBlock("t", NoteDocBlockType.TABLE, table = NoteDocTable.blank(3, 14).copy(headerRow = true)
                .withCell(0, 0, "Fruit")),
            NoteDocBlock("i", NoteDocBlockType.IMAGE, imagePath = "missing"),
            NoteDocBlock("v", NoteDocBlockType.VIDEO, video = NoteDocVideo("M7lc1UVf-VE", 20, 60, "Clip", "Channel")),
            NoteDocBlock("a", NoteDocBlockType.AUDIO, audio = NoteDocAudio(
                "0123abcd-0000-0000-0000-000000000000.m4a", 61_000, "0f0f0f")),
            NoteDocBlock("tg", NoteDocBlockType.TOGGLE, "Hidden part", isCollapsed = true),
        )
        val pages = NotePrintRenderer.layout(NotePrintSource("All blocks", blocks, colors),
            NotePrintSettings(landscape = true, pageNumbers = NotePageNumbers.TOP))
        assertTrue("pages: ${pages.size}", pages.size in 1..4)
        assertTrue(pages.sumOf { it.ops.size } >= blocks.size)
    }

    @Test fun pagesShownWhileEditingMatchThePrintedPages() {
        val blocks = longNote() + NoteDocBlock("v", NoteDocBlockType.VIDEO,
            video = NoteDocVideo("M7lc1UVf-VE", 0, 0, "Clip", "Channel")) + longNote().take(5).map { it.copy(id = "x" + it.id) }
        val source = NotePrintSource("Long note", blocks, colors)
        val settings = NotePrintSettings(keepBlocksWhole = true)
        val pages = NotePrintRenderer.layout(source, settings).size
        val pagination = NotePrintRenderer.paginate(source, settings)
        assertEquals(pages, pagination.pageCount)
        var previous = 0
        blocks.forEach { b ->
            val start = pagination.startPage.getValue(b.id)
            assertTrue("pages run forwards at ${b.id}", start >= previous)
            // Every block here fits on one page, so formatted pages never split one.
            assertEquals("block ${b.id} kept whole", start, pagination.endPage.getValue(b.id))
            previous = start
        }
        assertTrue(pagination.contentBottom.all { it <= settings.pageHeightPt - settings.margins.points + .5f })
    }

    @Test fun biggerPaperNeedsFewerPages() {
        val source = NotePrintSource("Long note", longNote(), colors)
        val a4 = NotePrintRenderer.paginate(source, NotePrintSettings(keepBlocksWhole = true)).pageCount
        val a3 = NotePrintRenderer.paginate(source, NotePrintSettings(paper = NotePaper.A3, keepBlocksWhole = true)).pageCount
        assertTrue("A3 $a3 vs A4 $a4", a3 < a4)
        assertEquals(NotePaper.A4, NotePaper.offered.first())
        assertTrue(NotePaper.A3 in NotePaper.offered)
    }

    @Test fun pageSetupRoundTripsAndIgnoresDamagedEntries() {
        val s = NotePrintSettings(NotePaper.A3, landscape = true, margins = NoteMargins.WIDE,
            pageNumbers = NotePageNumbers.TOP, numberAlign = NoteNumberAlign.RIGHT, includeTitle = false)
        assertEquals(s, NotePageSetupStore.decode(NotePageSetupStore.encode(s)))
        assertEquals(null, NotePageSetupStore.decode("A9|P|NORMAL|BOTTOM|CENTER|T"))
        assertEquals(null, NotePageSetupStore.decode("garbage"))
    }

    @Test fun landscapeSwapsThePageSides() {
        val s = NotePrintSettings(paper = NotePaper.LETTER, landscape = true)
        assertEquals(792f, s.pageWidthPt)
        assertEquals(612f, s.pageHeightPt)
        assertEquals("Letter · Landscape", s.summary)
        assertEquals("My note.pdf", NotePrinting.fileName("My note"))
        assertEquals("a b.pdf", NotePrinting.fileName("a/b"))
    }
}
