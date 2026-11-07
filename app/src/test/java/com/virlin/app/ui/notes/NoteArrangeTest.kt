package com.virlin.app.ui.notes

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.rich.RichNoteAdapter
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Arrange mode's editor action: a move is one undo step, is saved, and is refused safely. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteArrangeTest {
    private val ids = SequentialIdProvider()
    private val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))), FakeClock(Instant.EPOCH), ids)
    private fun p(id: String) = NoteDocBlock(id, NoteDocBlockType.PARAGRAPH, id)
    private fun bullet(id: String) = NoteDocBlock(id, NoteDocBlockType.BULLET, id)

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    @Test fun theToolIsAlwaysAvailableAndLeavesTheDocumentAlone() = editorTest {
        actions.saveNoteDoc("task-a", "Note", listOf(p("a"), p("b")))
        val vm = NotesViewModel("task-a", "Note", actions, ids)
        vm.onTool(NoteTool.ARRANGE_BLOCKS)
        assertEquals(listOf("a", "b"), vm.state.value.blocks.map { it.id })
    }

    @Test fun movingABlockIsOneUndoStepAndIsSaved() = editorTest {
        actions.saveNoteDoc("task-b", "Note", listOf(p("a"), p("b"), p("c")))
        val vm = NotesViewModel("task-b", "Note", actions, ids)
        assertTrue(vm.moveBlock("a", 2))
        assertEquals(listOf("b", "c", "a"), vm.state.value.blocks.map { it.id })
        assertFalse("dropping it where it is changes nothing", vm.moveBlock("a", 2))
        advanceUntilIdle()
        assertEquals(listOf("b", "c", "a"), actions.loadNoteDoc("task-b")!!.blocks.map { it.id })
        vm.undo()
        assertEquals(listOf("a", "b", "c"), vm.state.value.blocks.map { it.id })
    }

    @Test fun aRichNoteMovesWholePartsAndRefusesSplittingOne() = editorTest {
        val blocks = listOf(p("a"), bullet("l1"), bullet("l2"), p("b"))
        actions.saveRichNoteDoc("task-c", "Note", RichNoteAdapter.fromLegacy(blocks), expectedRevision = null)
        val vm = NotesViewModel("task-c", "Note", actions, ids)
        assertTrue(vm.moveBlock("b", 0))
        assertEquals(listOf("b", "a", "l1", "l2"), vm.state.value.blocks.map { it.id })
        advanceUntilIdle()
        val stored = actions.loadNoteDoc("task-c")!!
        assertEquals(listOf("b", "a", "l1", "l2"), RichNoteAdapter.project(stored.richDocument!!).map { it.id })
        assertFalse(vm.moveBlock("l2", 0))
        assertEquals(listOf("b", "a", "l1", "l2"), vm.state.value.blocks.map { it.id })
        assertTrue(vm.state.value.dialog is NoteDialog.Message)
    }
}
