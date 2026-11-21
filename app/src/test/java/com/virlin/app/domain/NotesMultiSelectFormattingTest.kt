package com.virlin.app.domain

import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteRuns
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.notes.NoteDialog
import com.virlin.app.ui.notes.NoteTool
import com.virlin.app.ui.notes.NotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** Multi-select formatting: every tool acts on all picked blocks, as one undo step. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesMultiSelectFormattingTest {
    private val clock = FakeClock(Instant.parse("2026-11-08T09:00:00Z"))
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    private fun vm(): NotesViewModel {
        val ids = SequentialIdProvider()
        return NotesViewModel("owner-1", "Notes", DefaultVirlinActions(InMemoryWorkStreamRepository(), clock, ids), ids)
    }

    /** Two paragraphs and a code block; returns their ids in order. */
    private fun NotesViewModel.threeBlocks(): List<String> {
        val first = state.value.blocks.first().id
        onFocusChanged(first, true)
        onTextChanged(first, "Alpha", TextRange(5))
        onTextChanged(first, "Alpha\n", TextRange(6))
        val second = state.value.focusedBlockId!!
        onTextChanged(second, "Beta", TextRange(4))
        insertBlock(NoteDocBlockType.CODE)
        val code = state.value.focusedBlockId!!
        onTextChanged(code, "x = 1", TextRange(5))
        return listOf(first, second, code)
    }

    private fun NotesViewModel.block(id: String) = state.value.blocks.first { it.id == id }

    @Test fun boldFontAndSizeGoOnEveryPickedTextBlockAndUndoInOneStep() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val (a, b, code) = vm.threeBlocks()
        val before = vm.state.value.blocks
        vm.setBlockSelection(setOf(a, b, code))

        vm.onTool(NoteTool.BOLD)
        for (id in listOf(a, b)) {
            val block = vm.block(id)
            assertTrue(NoteRuns.rangeHasMark(block.runs, 0, block.text.length, NoteDocMark.BOLD))
        }
        assertEquals("code keeps its own look", before.first { it.id == code }, vm.block(code))
        assertTrue(NoteTool.BOLD in vm.toolState().active)
        vm.undo()
        assertEquals(before, vm.state.value.blocks)

        vm.setFont("Serif")
        vm.applyFontSize(20)
        for (id in listOf(a, b)) {
            val run = NoteRuns.styleAt(vm.block(id).runs, 0)
            assertEquals("Serif", run?.fontFamily)
            assertEquals(20, run?.fontSizeSp)
        }
        assertEquals("Serif", vm.toolState().fontLabel)
        assertEquals("20", vm.toolState().sizeLabel)

        vm.onTool(NoteTool.ALIGN_CENTER)
        assertTrue(listOf(a, b, code).all { vm.block(it).align == NoteDocAlign.CENTER })
    }

    @Test fun bulletsAndHeadingsChangeEveryPickedLineAndToggleBack() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val (a, b, code) = vm.threeBlocks()
        vm.setBlockSelection(setOf(a, b, code))

        vm.onTool(NoteTool.BULLETS)
        assertEquals(NoteDocBlockType.BULLET, vm.block(a).type)
        assertEquals(NoteDocBlockType.BULLET, vm.block(b).type)
        assertEquals(NoteDocBlockType.CODE, vm.block(code).type)
        assertTrue(NoteTool.BULLETS in vm.toolState().active)
        assertTrue(vm.state.value.dialog is NoteDialog.ListStyle)
        vm.removeListFromFocused()
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.block(a).type)
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.block(b).type)

        vm.setParagraphStyle(NoteDocBlockType.HEADING_2, toggle = false)
        assertEquals(NoteDocBlockType.HEADING_2, vm.block(a).type)
        assertEquals(NoteDocBlockType.HEADING_2, vm.block(b).type)
        assertEquals("Heading 2", vm.toolState().paragraphLabel)
        vm.setParagraphStyle(NoteDocBlockType.HEADING_2, toggle = false)
        assertEquals(NoteDocBlockType.PARAGRAPH, vm.block(a).type)
    }

    @Test fun sizeStepsMoveEachBlockFromItsOwnSizeSoHeadingsStayLarger() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val (a, b, _) = vm.threeBlocks()
        vm.setBlockSelection(setOf(a))
        vm.setParagraphStyle(NoteDocBlockType.HEADING_1, toggle = false)
        vm.setBlockSelection(setOf(a, b))
        vm.stepFontSize(+1)
        vm.stepFontSize(+1)
        assertEquals(NotesViewModel.defaultSizeFor(NoteDocBlockType.HEADING_1) + 2,
            NoteRuns.styleAt(vm.block(a).runs, 0)?.fontSizeSp)
        assertEquals(NotesViewModel.defaultSizeFor(NoteDocBlockType.PARAGRAPH) + 2,
            NoteRuns.styleAt(vm.block(b).runs, 0)?.fontSizeSp)
        vm.undo()
        assertTrue("a run of steps is one undo step", vm.block(b).runs.all { it.fontSizeSp == null })
    }

    @Test fun withNothingPickedToolsStillActOnTheFocusedSelectionOnly() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val (a, b, _) = vm.threeBlocks()
        vm.setBlockSelection(setOf(a, b))
        vm.setBlockSelection(emptySet())
        vm.onFocusChanged(a, true)
        vm.onSelectionChanged(a, TextRange(0, 2))
        vm.onTool(NoteTool.BOLD)
        assertTrue(NoteRuns.rangeHasMark(vm.block(a).runs, 0, 2, NoteDocMark.BOLD))
        assertFalse(NoteRuns.rangeHasMark(vm.block(a).runs, 2, 5, NoteDocMark.BOLD))
        assertTrue(vm.block(b).runs.none { NoteDocMark.BOLD in it.marks })
    }
}
