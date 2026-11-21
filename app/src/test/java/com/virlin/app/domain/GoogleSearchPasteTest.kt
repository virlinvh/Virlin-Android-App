package com.virlin.app.domain

import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NotePasteParser
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.notes.NotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** An answer copied from Google Search's AI Mode keeps its paragraphs, headings, highlights, table and list. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GoogleSearchPasteTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("paste/$name")!!.readText()
    private val html = res("google-ai-mode.html")
    private val plain = res("google-ai-mode.txt")

    private fun assertAnswerStructure(blocks: List<NoteDocBlock>) {
        val first = blocks.first()
        assertEquals(NoteDocBlockType.PARAGRAPH, first.type)
        assertTrue(first.text.startsWith("The abbreviation SI most commonly"))
        assertTrue(first.text.endsWith("commerce. [1]"))
        val highlighted = first.runs.single { it.highlight != null }
        assertEquals("International System of Units", first.text.substring(highlighted.start, highlighted.end))
        assertEquals(0xFFD3E3FDL, highlighted.highlight)
        assertTrue(first.runs.any { NoteDocMark.ITALIC in it.marks &&
            first.text.substring(it.start, it.end) == "Système international d'unités" })
        assertTrue(blocks[1].text.startsWith("However, depending on the context"))
        val headings = blocks.filter { it.type == NoteDocBlockType.HEADING_2 }.map { it.text }
        assertEquals(listOf("1. The International System of Units (Science & Measurement)",
            "2. Other Common Meanings of SI"), headings)
        assertEquals(2, blocks.count { it.type == NoteDocBlockType.DIVIDER })
        val table = blocks.single { it.type == NoteDocBlockType.TABLE }.table!!
        assertEquals(8, table.rows)
        assertEquals(3, table.cols)
        assertEquals("Candela", table.cells[7 * 3 + 1])
        val bullets = blocks.filter { it.type == NoteDocBlockType.BULLET }
        assertEquals(4, bullets.size)
        assertTrue(bullets.first().text.startsWith("Chemistry: Si is the chemical symbol"))
        val bipm = blocks.first { it.text.startsWith("This is the universal system") }
        assertTrue(bipm.runs.any { it.linkUrl == "https://www.bipm.org/en/home" })
        assertTrue(blocks.none { it.text.contains("However") && it.text.contains("abbreviation") })
    }

    @Test fun aiModeCopyKeepsItsStructure() {
        assertAnswerStructure(NotePasteParser.parse(plain, html))
    }

    @Test fun chromeInlineWrappersDoNotMergeTheAnswerIntoOneParagraph() {
        val wrapped = "<meta charset='utf-8'><span style=\"color: rgb(31, 31, 31); font-family: Arial; " +
            "font-size: 16px;\"><span>$html</span></span>"
        assertAnswerStructure(NotePasteParser.parse(plain, wrapped))
    }

    @Test fun inlineTextAroundANestedBlockStaysInItsOwnParagraphs() {
        val blocks = NotePasteParser.parse("Lead bold\nBlock\ntail",
            "<span>Lead <b>bold</b><span><div>Block</div></span>tail</span>")
        assertEquals(listOf("Lead bold", "Block", "tail"), blocks.map { it.text })
        assertTrue(blocks.all { it.type == NoteDocBlockType.PARAGRAPH })
        val bold = blocks[0].runs.single()
        assertEquals("bold", blocks[0].text.substring(bold.start, bold.end))
        assertTrue(NoteDocMark.BOLD in bold.marks)
    }

    @Test fun blockStyledSpansBecomeSeparateParagraphs() {
        val blocks = NotePasteParser.parse("One Two",
            "<div><span style=\"display: block; margin: 16px 0\">One <b>bold</b></span>" +
                "<span style=\"display:block\">Two</span></div>")
        assertEquals(listOf("One bold", "Two"), blocks.map { it.text })
        val chips = NotePasteParser.parse("a b c",
            "<div><span style=\"display: inline-block\">a</span> <span style=\"display: table-cell\">b</span> c</div>")
        assertEquals(listOf("a b c"), chips.map { it.text })
    }

    @Test fun inlineOnlyMarkupIsUnchanged() {
        val link = NotePasteParser.parse("Title Desc",
            "<a href=\"https://example.com\"><span><div>Title</div><div>Desc</div></span></a>")
        assertEquals(1, link.size)
        assertEquals("https://example.com", link.single().runs.single().linkUrl)
        val picture = NotePasteParser.parse("Look here",
            "<p><span>Look <img src=\"content://pic/1\"> here</span></p>", imageResolver = { "stored" })
        assertEquals(listOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.IMAGE), picture.map { it.type })
        assertEquals("Look  here", picture.first().text)
        val plainMark = NotePasteParser.parse("hi", "<p><mark>hi</mark></p>").single()
        assertEquals(0xFFFFEB8AL, plainMark.runs.single().highlight)
    }

    @Test fun pastingTheAnswerIntoANoteIsOneUndoStep() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-search-paste", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids,
                pasteDispatcher = UnconfinedTestDispatcher(testScheduler))
            val original = vm.state.value.blocks.single()
            val wrapped = "<span style=\"color: rgb(31, 31, 31);\">$html</span>"
            assertTrue(vm.pasteStructured(original.id, plain, plain, wrapped))
            assertAnswerStructure(vm.state.value.blocks)
            vm.undo()
            assertEquals(listOf(original), vm.state.value.blocks)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
