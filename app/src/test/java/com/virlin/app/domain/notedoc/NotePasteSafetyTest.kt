package com.virlin.app.domain.notedoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotePasteSafetyTest {

    @Test fun linksInTheMiddleOfAPlainMarkdownParagraphAreKept() {
        val block = NotePasteParser.parse("See [the docs](https://example.com/a) and [more](https://b.test) here.").single()
        assertEquals("See the docs and more here.", block.text)
        val links = block.runs.filter { it.linkUrl != null }
        assertEquals(listOf("https://example.com/a", "https://b.test"), links.map { it.linkUrl })
        assertEquals("the docs", block.text.substring(links[0].start, links[0].end))
        assertEquals("more", block.text.substring(links[1].start, links[1].end))
    }

    @Test fun unmatchedBracketsStayLiteral() {
        val block = NotePasteParser.parse("arr[0] = [x](not a url) and [open").single()
        assertEquals("arr[0] = [x](not a url) and [open", block.text)
        assertTrue(block.runs.none { it.linkUrl != null })
    }

    @Test fun longParagraphWithBracketsParsesQuickly() {
        val text = "word [bracket and more words ".repeat(4_000)
        val started = System.nanoTime()
        val blocks = NotePasteParser.parse(text)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(text.trim(), blocks.joinToString("") { it.text }.trim())
        assertTrue("took $ms ms", ms < 2_000)
    }

    @Test fun plainFallbackSplitsAtBlankLinesAndKeepsEveryCharacter() {
        val text = "First line\nsecond line\n\n   \nThird\r\n\r\nFourth  "
        val blocks = NotePasteSafety.plainParagraphs(text)
        assertEquals(listOf("First line\nsecond line", "Third", "Fourth  "), blocks.map { it.text })
        assertTrue(blocks.all { it.type == NoteDocBlockType.PARAGRAPH && it.runs.isEmpty() })
    }

    @Test fun oversizedParagraphsAreDividedOnlyAtTheirOwnLineBreaks() {
        val line = "x".repeat(99)
        val text = List(300) { line }.joinToString("\n")
        val blocks = NotePasteSafety.plainParagraphs(text)
        assertTrue(blocks.size > 1)
        assertTrue(blocks.all { it.text.length <= NotePasteSafety.MAX_PARAGRAPH_CHARS })
        assertEquals(text, blocks.joinToString("\n") { it.text })
        val singleLine = "y".repeat(NotePasteSafety.MAX_PARAGRAPH_CHARS * 3)
        assertEquals(listOf(singleLine), NotePasteSafety.plainParagraphs(singleLine).map { it.text })
    }

    @Test fun splittingCarriesFormattingRuns() {
        val text = List(200) { "line $it ".padEnd(60, '.') }.joinToString("\n")
        val boldStart = text.indexOf("line 150")
        val block = NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, text,
            runs = listOf(NoteDocRun(boldStart, boldStart + 8, setOf(NoteDocMark.BOLD))))
        val parts = NotePasteSafety.splitLongParagraphs(listOf(block))
        assertTrue(parts.size > 1)
        val bold = parts.single { part -> part.runs.isNotEmpty() }
        val run = bold.runs.single()
        assertEquals("line 150", bold.text.substring(run.start, run.end))
    }
}
