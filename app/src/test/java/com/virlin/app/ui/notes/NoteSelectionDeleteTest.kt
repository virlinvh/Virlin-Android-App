package com.virlin.app.ui.notes

import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteSelectionDeleteTest {
    private fun b(id: String, type: NoteDocBlockType, text: String = "", runs: List<NoteDocRun> = emptyList(),
                  table: NoteDocTable? = null, title: String? = null, checked: Boolean = false) =
        NoteDocBlock(id, type, text, runs, table = table, promptTitle = title, checked = checked)

    private val mixed = listOf(
        b("h", NoteDocBlockType.HEADING_1, "Title"),
        b("p", NoteDocBlockType.PARAGRAPH, "Plain paragraph"),
        b("bu", NoteDocBlockType.BULLET, "Bullet"),
        b("nu", NoteDocBlockType.NUMBERED, "Numbered"),
        b("ch", NoteDocBlockType.CHECKLIST, "Task", checked = true),
        b("q", NoteDocBlockType.QUOTE, "Quoted"),
        b("tg", NoteDocBlockType.TOGGLE, "Toggle"),
        b("pr", NoteDocBlockType.PROMPT, "Prompt body", title = "Prompt title"),
        b("t", NoteDocBlockType.TABLE, table = NoteDocTable(2, 2, listOf("a", "bb", "ccc", "d"))),
        b("i", NoteDocBlockType.IMAGE),
        b("d", NoteDocBlockType.DIVIDER),
        b("c", NoteDocBlockType.CODE, "val x = 1\nval y = 2"),
        b("e", NoteDocBlockType.PARAGRAPH, ""),
    )

    @Test fun offsetsMatchTheSelectableProjection() {
        val projection = selectableNoteText(mixed).text
        val spans = projectedBlocks(mixed)
        assertEquals(projection.length, spans.last().end)
        mixed.zip(spans).forEach { (block, span) ->
            if (span.textStart >= 0) assertEquals(block.text, projection.substring(span.textStart, span.end))
            else assertEquals(when (block.type) {
                NoteDocBlockType.TABLE -> "a\t|\tbb\nccc\t|\td"
                NoteDocBlockType.IMAGE -> "[Image]"
                else -> "────────"
            }, projection.substring(span.contentStart, span.end))
        }
    }

    @Test fun crossBlockSelectionTrimsEdgesAndRemovesWholeBlocksBetween() {
        val blocks = listOf(
            b("a", NoteDocBlockType.PARAGRAPH, "Hello world"),
            b("h", NoteDocBlockType.HEADING_2, "Middle heading"),
            b("z", NoteDocBlockType.PARAGRAPH, "Second paragraph"),
        )
        val text = selectableNoteText(blocks).text
        val start = text.indexOf("world")
        val end = text.indexOf("ond paragraph")
        val result = deleteProjectedRange(blocks, start, end)!!
        assertEquals(listOf("a" to "Hello ", "z" to "ond paragraph"), result.map { it.id to it.text })
    }

    @Test fun partlySelectedTableIsKeptWholeAndFullySelectedImageIsRemoved() {
        val blocks = listOf(
            b("a", NoteDocBlockType.PARAGRAPH, "Before"),
            b("i", NoteDocBlockType.IMAGE),
            b("t", NoteDocBlockType.TABLE, table = NoteDocTable(1, 2, listOf("left", "right"))),
        )
        val text = selectableNoteText(blocks).text
        val result = deleteProjectedRange(blocks, text.indexOf("fore"), text.indexOf("right"))!!
        assertEquals(listOf("a", "t"), result.map { it.id })
        assertEquals("Be", result[0].text)
        assertEquals(blocks[2], result[1])
    }

    @Test fun selectionInsideOneBlockKeepsTheBlockAndShiftsFormatting() {
        val blocks = listOf(b("a", NoteDocBlockType.PARAGRAPH, "one two three",
            runs = listOf(NoteDocRun(8, 13, setOf(NoteDocMark.BOLD)))))
        val result = deleteProjectedRange(blocks, 3, 7)!!
        assertEquals("one three", result.single().text)
        assertEquals(listOf(NoteDocRun(4, 9, setOf(NoteDocMark.BOLD))), result.single().runs)
        assertEquals("", deleteProjectedRange(blocks, 0, 13)!!.single().text)
    }

    @Test fun collapsedOrPrefixOnlySelectionChangesNothing() {
        val blocks = listOf(b("a", NoteDocBlockType.BULLET, "item"))
        assertNull(deleteProjectedRange(blocks, 2, 2))
        assertNull(deleteProjectedRange(blocks, 0, 2))
    }

    // ---------------------------------------------------------------- view model

    private val ids = SequentialIdProvider()
    private val repo = InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)))
    private val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), ids)

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    @Test fun deleteToolRemovesTheSelectedTextAndUndoRestoresIt() = editorTest {
        val vm = NotesViewModel("task-del", "Note", actions, ids)
        val id = vm.state.value.blocks.single().id
        vm.onFocusChanged(id, true)
        vm.onTextChanged(id, "Hello bold world", TextRange(16))
        assertFalse(NoteTool.DELETE in vm.toolState().enabled)
        vm.onSelectionChanged(id, TextRange(5, 10))
        assertTrue(NoteTool.DELETE in vm.toolState().enabled)
        vm.onTool(NoteTool.DELETE)
        assertEquals("Hello world", vm.state.value.blocks.single().text)
        assertEquals(TextRange(5), vm.state.value.selection)
        vm.undo()
        assertEquals("Hello bold world", vm.state.value.blocks.single().text)
    }

    @Test fun deletingTheWholeDocumentLeavesOneEmptyParagraphAndUndoRestoresIt() = editorTest {
        val vm = NotesViewModel("task-del-all", "Note", actions, ids)
        val id = vm.state.value.blocks.single().id
        vm.onFocusChanged(id, true)
        assertTrue(vm.pasteStructured(id, "# Heading\n\nBody text", "# Heading\n\nBody text"))
        val before = vm.state.value.blocks
        assertEquals(2, before.size)
        val length = selectableNoteText(before).length
        assertTrue(vm.deleteDocumentRange(0, length))
        val after = vm.state.value.blocks.single()
        assertEquals(NoteDocBlockType.PARAGRAPH, after.type)
        assertEquals("", after.text)
        vm.undo()
        assertEquals(before, vm.state.value.blocks)
        assertFalse(vm.deleteDocumentRange(3, 3))
    }
}
