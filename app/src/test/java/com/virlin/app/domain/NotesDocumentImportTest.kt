package com.virlin.app.domain

import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.notes.NoteDialog
import com.virlin.app.ui.notes.NoteSaveState
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

/** Imported document sections land in the note as one undoable step. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotesDocumentImportTest {
    private val clock = FakeClock(Instant.parse("2026-11-08T09:00:00Z"))
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }

    private fun vm(): NotesViewModel {
        val ids = SequentialIdProvider()
        return NotesViewModel("owner-1", "Notes", DefaultVirlinActions(InMemoryWorkStreamRepository(), clock, ids), ids)
    }

    private val imported = listOf(
        NoteDocBlock(id = "import-0", type = NoteDocBlockType.HEADING_1, text = "Plants"),
        NoteDocBlock(id = "import-1", type = NoteDocBlockType.BULLET, text = "Leaves"),
    )

    @Test fun theImportToolOpensTheExplainingPopup() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onTool(NoteTool.IMPORT)
        assertEquals(NoteDialog.ImportDocument, vm.state.value.dialog)
    }

    @Test fun aBlankNoteBecomesTheImportedDocument() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.insertImportedBlocks(imported))
        val blocks = vm.state.value.blocks
        assertEquals(listOf("Plants", "Leaves"), blocks.map { it.text })
        assertEquals(listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.BULLET), blocks.map { it.type })
        assertTrue("fresh ids", blocks.none { it.id.startsWith("import-") })
        assertEquals(NoteSaveState.EDITED, vm.state.value.save)
    }

    @Test fun aWrittenNoteGetsTheImportAfterTheCurrentLineAndUndoRemovesIt() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val first = vm.state.value.blocks.first().id
        vm.onFocusChanged(first, true)
        vm.onTextChanged(first, "Intro", TextRange(5))
        vm.onTextChanged(first, "Intro\n", TextRange(6))
        val second = vm.state.value.focusedBlockId!!
        vm.onTextChanged(second, "Outro", TextRange(5))
        vm.onFocusChanged(first, true)
        val before = vm.state.value.blocks

        assertTrue(vm.insertImportedBlocks(imported))
        assertEquals(listOf("Intro", "Plants", "Leaves", "Outro"), vm.state.value.blocks.map { it.text })

        vm.undo()
        assertEquals(before, vm.state.value.blocks)
    }

    @Test fun withNoLineBeingEditedTheImportGoesAtTheEnd() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val first = vm.state.value.blocks.first().id
        vm.onFocusChanged(first, true)
        vm.onTextChanged(first, "Intro", TextRange(5))
        vm.onTextChanged(first, "Intro\n", TextRange(6))
        val second = vm.state.value.focusedBlockId!!
        vm.onTextChanged(second, "Outro", TextRange(5))
        vm.onFocusChanged(second, false)

        assertTrue(vm.insertImportedBlocks(imported))
        assertEquals(listOf("Intro", "Outro", "Plants", "Leaves"), vm.state.value.blocks.map { it.text })
    }

    @Test fun nothingToImportChangesNothing() = runTest {
        val vm = vm()
        advanceUntilIdle()
        val before = vm.state.value.blocks
        assertFalse(vm.insertImportedBlocks(emptyList()))
        assertEquals(before, vm.state.value.blocks)
    }
}
