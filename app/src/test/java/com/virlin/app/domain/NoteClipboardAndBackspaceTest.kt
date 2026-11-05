package com.virlin.app.domain

import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.note.NoteClipboardImporter
import com.virlin.app.domain.note.NotePlainTextSerializer
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NoteClipboardAndBackspaceTest {

    private val clock = FakeClock(Instant.parse("2026-09-14T12:00:00Z"))
    private val ids = SequentialIdProvider()
    private val dispatcher = UnconfinedTestDispatcher()
    private var seq = 0
    private val newId = { "b${++seq}" }

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun markdown_headingsListsQuoteCodeDividerCheckbox() {
        val md = """
            # Title
            ## Sub
            ### H3

            Paragraph one.

            - bullet
            * also

            1. numbered

            - [ ] open
            - [x] done

            > quoted

            ---

            ```
            code line
            ```
        """.trimIndent()
        val blocks = NoteClipboardImporter.import(null, md, newId)
        assertEquals(NoteBlockType.HEADING_1, blocks[0].type)
        assertEquals("Title", blocks[0].plainText)
        assertEquals(NoteBlockType.HEADING_2, blocks[1].type)
        assertEquals(NoteBlockType.HEADING_3, blocks[2].type)
        assertEquals(NoteBlockType.TEXT, blocks[3].type)
        assertEquals(NoteBlockType.BULLETED_LIST, blocks[4].type)
        assertEquals(NoteBlockType.BULLETED_LIST, blocks[5].type)
        assertEquals(NoteBlockType.NUMBERED_LIST, blocks[6].type)
        assertEquals(NoteBlockType.CHECKBOX, blocks[7].type)
        assertFalse(blocks[7].checked)
        assertEquals(NoteBlockType.CHECKBOX, blocks[8].type)
        assertTrue(blocks[8].checked)
        assertEquals(NoteBlockType.QUOTE, blocks[9].type)
        assertEquals(NoteBlockType.DIVIDER, blocks[10].type)
        assertEquals(NoteBlockType.CODE, blocks[11].type)
        assertEquals("code line", blocks[11].plainText)
    }

    @Test fun html_mapsCommonTags() {
        val html = """
            <h1>Hi</h1><p>Body <b>bold</b> and <em>it</em></p>
            <ul><li>one</li><li>two</li></ul>
            <blockquote>q</blockquote><hr/><pre><code>x</code></pre>
            <script>alert(1)</script>
        """.trimIndent()
        val blocks = NoteClipboardImporter.import(html, "fallback", newId)
        assertEquals(NoteBlockType.HEADING_1, blocks.first().type)
        assertEquals("Hi", blocks.first().plainText)
        assertTrue(blocks.any { it.type == NoteBlockType.BULLETED_LIST && it.plainText == "one" })
        assertTrue(blocks.any { it.type == NoteBlockType.QUOTE })
        assertTrue(blocks.any { it.type == NoteBlockType.DIVIDER })
        assertTrue(blocks.any { it.type == NoteBlockType.CODE && it.plainText.contains("x") })
        assertFalse(blocks.any { it.plainText.contains("alert") })
        val boldPara = blocks.first { it.type == NoteBlockType.TEXT && it.plainText.contains("bold") }
        assertTrue(boldPara.marks.any { it.style.name == "BOLD" })
    }

    @Test fun plain_splitsParagraphs() {
        val blocks = NoteClipboardImporter.import(null, "a\n\nb\n\nc", newId)
        assertEquals(3, blocks.size)
        assertEquals(listOf("a", "b", "c"), blocks.map { it.plainText })
    }

    @Test fun placeholder_neverInSerializer() {
        val doc = NoteDocument(
            id = "n", captureItemId = "c", title = null,
            blocks = listOf(NoteBlock("1", NoteBlockType.TEXT, "")),
            createdAt = clock.now(), updatedAt = clock.now()
        )
        val text = NotePlainTextSerializer.serialize(doc)
        assertFalse(text.contains("Type something"))
        assertFalse(text.contains("/ for blocks"))
        assertEquals("", NotePlainTextSerializer.preview(doc))
    }

    @Test fun currentEditorBackspaceMergesEmptyParagraphAndPreservesText() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = com.virlin.app.ui.notes.NotesViewModel("owner", "Notes", actions, ids)
        val first = vm.state.value.blocks.first().id
        vm.onTextChanged(first, "Hello\n", androidx.compose.ui.text.TextRange(6))
        val second = vm.state.value.blocks.last().id
        assertNotEquals(first, second)
        assertTrue(vm.onBackspaceAtStart(second))
        assertEquals(1, vm.state.value.blocks.size)
        assertEquals("Hello", vm.state.value.blocks.single().text)
    }

    @Test fun currentEditorKeepsItsOnlyEmptyParagraph() = runTest {
        val vm = com.virlin.app.ui.notes.NotesViewModel("owner", "Notes", DefaultVirlinActions(InMemoryWorkStreamRepository(), clock, ids), ids)
        assertFalse(vm.onBackspaceAtStart(vm.state.value.blocks.single().id))
        assertEquals(1, vm.state.value.blocks.size)
    }
}
