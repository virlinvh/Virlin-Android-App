package com.virlin.app.domain

import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NotePasteSource
import com.virlin.app.domain.notedoc.NoteSourcePaste
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.notes.NoteDialog
import com.virlin.app.ui.notes.NoteTool
import com.virlin.app.ui.notes.NotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.ui.text.TextRange
import java.time.Instant

/** "Paste from…": each source reads the clipboard the way that source formats its copies. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteSourcePasteTest {
    private fun types(blocks: List<com.virlin.app.domain.notedoc.NoteDocBlock>) = blocks.map { it.type }

    @Test fun everyAiChatReadsItsFormattedCopy() {
        val html = "<h2>Plan</h2><p>Use <strong>Room</strong> now.</p><ul><li>One</li><li>Two</li></ul>"
        val plain = "## Plan\nUse **Room** now.\n- One\n- Two"
        NotePasteSource.entries.filter { it.group == NotePasteSource.Group.AI }.forEach { source ->
            val blocks = NoteSourcePaste.parse(source, plain, html)
            assertEquals(source.label, listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.PARAGRAPH,
                NoteDocBlockType.BULLET, NoteDocBlockType.BULLET), types(blocks))
            val bold = blocks[1].runs.single()
            assertTrue(NoteDocMark.BOLD in bold.marks)
            assertEquals("Room", blocks[1].text.substring(bold.start, bold.end))
        }
    }

    @Test fun aiChatMarkdownWithoutFormattedCopyIsStillStructured() {
        val blocks = NoteSourcePaste.parse(NotePasteSource.CHATGPT, "# Title\n\n1. First\n2. Second\n\n- \nDone", null)
        assertEquals(listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.NUMBERED, NoteDocBlockType.NUMBERED,
            NoteDocBlockType.PARAGRAPH), types(blocks))
        assertEquals("Done", blocks.last().text)
    }

    @Test fun googleSearchPlainCopyDropsRedirectPaths() {
        val blocks = NoteSourcePaste.parse(NotePasteSource.GOOGLE_SEARCH,
            "SI is the metric system (/goto?url=https://x.org/a&ved=1) used worldwide (/search?q=si).", null)
        assertEquals("SI is the metric system used worldwide.", blocks.single().text)
    }

    @Test fun googleDocsPlainCopyKeepsOneParagraphPerLine() {
        val blocks = NoteSourcePaste.parse(NotePasteSource.GOOGLE_DOCS,
            "Meeting notes\nFirst paragraph of text.\n● Point one\no\tNested\n1. Step\no clock is not a bullet", null)
        assertEquals(listOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.PARAGRAPH, NoteDocBlockType.BULLET,
            NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED, NoteDocBlockType.PARAGRAPH), types(blocks))
        assertEquals(1, blocks[3].indent)
        assertEquals("Nested", blocks[3].text)
    }

    @Test fun markdownSourceIgnoresTheFormattedCopy() {
        val blocks = NoteSourcePaste.parse(NotePasteSource.MARKDOWN, "## Heading\n**bold**", "<p>## Heading **bold**</p>")
        assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.PARAGRAPH), types(blocks))
        assertTrue(NoteDocMark.BOLD in blocks[1].runs.single().marks)
    }

    @Test fun plainTextInterpretsNothing() {
        val blocks = NoteSourcePaste.parse(NotePasteSource.PLAIN, "# not a heading\n\n- not a bullet\r\n**raw**",
            "<h1>ignored</h1>")
        assertEquals(listOf("# not a heading", "- not a bullet", "**raw**"), blocks.map { it.text })
        assertTrue(blocks.all { it.type == NoteDocBlockType.PARAGRAPH && it.runs.isEmpty() })
    }

    @Test fun wordListParagraphsBecomeNestedLists() {
        val html = """
            <p class="MsoTitle">Report</p>
            <p class="MsoNormal" style="font-size:11.0pt">Intro text.</p>
            <p class="MsoListParagraphCxSpFirst" style="mso-list:l0 level1 lfo1"><span style="mso-list:Ignore">·<span>&nbsp;</span></span>Alpha</p>
            <p class="MsoListParagraphCxSpMiddle" style="mso-list:l0 level2 lfo1"><span style="mso-list:Ignore">o<span>&nbsp;</span></span>Beta</p>
            <p class="MsoListParagraphCxSpLast" style="mso-list:l0 level1 lfo1"><span style="mso-list:Ignore">·<span>&nbsp;</span></span>Gamma</p>
            <p class="MsoListParagraph" style="mso-list:l1 level1 lfo2"><span style="mso-list:Ignore">1.<span>&nbsp;</span></span>Numbered</p>
        """.trimIndent()
        val blocks = NoteSourcePaste.parse(NotePasteSource.WORD, "Report\nIntro text.\nAlpha\nBeta\nGamma\nNumbered", html)
        assertEquals(NoteDocBlockType.HEADING_1, blocks[0].type)
        assertEquals("Intro text.", blocks[1].text)
        val bullets = blocks.filter { it.type == NoteDocBlockType.BULLET }
        assertEquals(listOf("Alpha", "Beta", "Gamma"), bullets.map { it.text.trim() })
        assertEquals(listOf(0, 1, 0), bullets.map { it.indent })
        assertEquals("Numbered", blocks.single { it.type == NoteDocBlockType.NUMBERED }.text.trim())
        assertTrue(blocks.none { it.text.contains("·") })
    }

    @Test fun wordBodySizeIsNotKeptAsAnExplicitSize() {
        val html = "<p style=\"font-size:11.0pt\">Body one</p><p style=\"font-size:11.0pt\">Body two</p>" +
            "<p><span style=\"font-size:20.0pt\">Big</span></p>"
        val blocks = NoteSourcePaste.parse(NotePasteSource.WORD, "Body one\nBody two\nBig", html)
        assertTrue(blocks.take(2).all { block -> block.runs.none { it.fontSizeSp != null } })
        assertTrue(blocks.last().runs.any { it.fontSizeSp != null })
    }

    @Test fun pdfLinesAreJoinedIntoParagraphs() {
        val text = """
            INTRODUCTION
            The quick brown fox jumps over the lazy dog and keeps running
            across the wide field until it reaches the river bank where it
            finally rests in the shade.
            A second paragraph starts here and continues with more words on
            the next line to describe the experi-
            ment that was performed.
            • First point that wraps onto
            a second line
            2. Numbered item
        """.trimIndent()
        val blocks = NoteSourcePaste.parse(NotePasteSource.PDF, text, null)
        assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.PARAGRAPH, NoteDocBlockType.PARAGRAPH,
            NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED), types(blocks))
        assertTrue(blocks[1].text.startsWith("The quick brown fox"))
        assertTrue(blocks[1].text.endsWith("finally rests in the shade."))
        assertTrue(blocks[2].text.contains("the experiment that was performed."))
        assertEquals("First point that wraps onto a second line", blocks[3].text)
    }

    @Test fun websiteWithoutFormattedCopyKeepsLines() {
        val blocks = NoteSourcePaste.parse(NotePasteSource.WEB_PAGE, "Headline\nBody line", null)
        assertEquals(listOf("Headline", "Body line"), blocks.map { it.text })
    }

    @Test fun emptyClipboardTextGivesNoBlocks() {
        NotePasteSource.entries.forEach { assertTrue(it.label, NoteSourcePaste.parse(it, "  \n ", null).isEmpty()) }
    }

    @Test fun pasteFromToolTogglesTheStripAndPastingIsOneUndoStep() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-source-paste", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids,
                pasteDispatcher = UnconfinedTestDispatcher(testScheduler))
            val original = vm.state.value.blocks
            vm.onTool(NoteTool.PASTE_FROM)
            assertEquals(NoteDialog.PasteFrom, vm.state.value.dialog)
            val blocks = NoteSourcePaste.parse(NotePasteSource.PDF, "TITLE\nBody text.", null)
            assertTrue(vm.insertImportedBlocks(blocks))
            assertNull(vm.state.value.dialog)
            assertEquals(listOf("TITLE", "Body text."), vm.state.value.blocks.map { it.text })
            vm.undo()
            assertEquals(original, vm.state.value.blocks)
            vm.onTool(NoteTool.PASTE_FROM)
            vm.onTool(NoteTool.PASTE_FROM)
            assertNull(vm.state.value.dialog)

            assertTrue(vm.insertImportedBlocks(NoteSourcePaste.parse(NotePasteSource.PLAIN, "First\nSecond", null)))
            val second = vm.state.value.blocks.last()
            vm.onFocusChanged(second.id, true)
            vm.onTextChanged(second.id, "Second\n", TextRange(7))
            assertEquals("", vm.state.value.focusedBlock!!.text)
            assertTrue(vm.insertImportedBlocks(NoteSourcePaste.parse(NotePasteSource.PLAIN, "Third", null),
                fillBlankLine = true))
            assertEquals(listOf("First", "Second", "Third"), vm.state.value.blocks.map { it.text })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun pastingPreservesTheOriginalBlocksWhenFillingIsOff() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-source-paste-2", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids,
                pasteDispatcher = UnconfinedTestDispatcher(testScheduler))
            assertTrue(vm.insertImportedBlocks(NoteSourcePaste.parse(NotePasteSource.PLAIN, "First", null)))
            val first = vm.state.value.blocks.single()
            vm.onFocusChanged(first.id, true)
            vm.onTextChanged(first.id, "First\n", TextRange(6))
            val emptyLine = vm.state.value.focusedBlock!!
            assertTrue(vm.insertImportedBlocks(NoteSourcePaste.parse(NotePasteSource.PLAIN, "Imported", null)))
            assertEquals(listOf("First", "", "Imported"), vm.state.value.blocks.map { it.text })
            assertEquals(emptyLine.id, vm.state.value.blocks[1].id)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
