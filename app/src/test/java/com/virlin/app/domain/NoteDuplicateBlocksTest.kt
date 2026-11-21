package com.virlin.app.domain

import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
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
import java.time.Instant

/** Duplicate in multi-select copies the picked blocks right after themselves, as one undo step. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteDuplicateBlocksTest {
    private fun block(text: String, type: NoteDocBlockType = NoteDocBlockType.PARAGRAPH) =
        NoteDocBlock(id = "", type = type, text = text)

    private fun withNote(blocks: List<NoteDocBlock>, check: (NotesViewModel) -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val ids = SequentialIdProvider()
            val vm = NotesViewModel("task-duplicate-blocks", "Note",
                DefaultVirlinActions(InMemoryWorkStreamRepository(), FakeClock(Instant.EPOCH), ids), ids,
                pasteDispatcher = UnconfinedTestDispatcher(testScheduler))
            assertTrue(vm.insertImportedBlocks(blocks))
            check(vm)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun NotesViewModel.texts() = state.value.blocks.map { it.text }
    private fun NotesViewModel.idOf(text: String) = state.value.blocks.first { it.text == text }.id

    @Test fun oneBlockIsCopiedRightAfterItself() = withNote(listOf(block("A"), block("B"), block("C"))) { vm ->
        val copies = vm.duplicateBlocks(setOf(vm.idOf("B")))!!
        assertEquals(listOf("A", "B", "B", "C"), vm.texts())
        assertEquals(1, copies.size)
        assertEquals(copies.single(), vm.state.value.blocks[2].id)
        assertEquals(vm.state.value.blocks.size, vm.state.value.blocks.map { it.id }.toSet().size)
    }

    @Test fun neighbouringBlocksAreCopiedTogetherAndSeparateOnesEachAfterThemselves() =
        withNote(listOf(block("A"), block("B"), block("C"), block("D"), block("E"))) { vm ->
            val copies = vm.duplicateBlocks(setOf(vm.idOf("A"), vm.idOf("B"), vm.idOf("D")))!!
            assertEquals(listOf("A", "B", "A", "B", "C", "D", "D", "E"), vm.texts())
            assertEquals(3, copies.size)
        }

    @Test fun formattingAndPicturesAreKeptAndUndoRemovesTheCopies() = withNote(listOf(
        block("Title", NoteDocBlockType.HEADING_1),
        NoteDocBlock(id = "", type = NoteDocBlockType.IMAGE, imagePath = "pic.png"),
        block("Point", NoteDocBlockType.BULLET),
    )) { vm ->
        val before = vm.state.value.blocks
        val picked = before.map { it.id }.toSet()
        assertTrue(vm.duplicateBlocks(picked) != null)
        val after = vm.state.value.blocks
        assertEquals(before.map { it.type } + before.map { it.type }, after.map { it.type })
        assertEquals("pic.png", after[4].imagePath)
        vm.undo()
        assertEquals(before, vm.state.value.blocks)
    }

    @Test fun nothingPickedChangesNothing() = withNote(listOf(block("A"))) { vm ->
        val before = vm.state.value.blocks
        assertNull(vm.duplicateBlocks(emptySet()))
        assertNull(vm.duplicateBlocks(setOf("missing")))
        assertEquals(before, vm.state.value.blocks)
    }
}
