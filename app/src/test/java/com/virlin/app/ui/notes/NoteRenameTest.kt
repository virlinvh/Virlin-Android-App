package com.virlin.app.ui.notes

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The Rename tool: its strip, the name it keeps, and that it is saved with the note. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteRenameTest {
    private val ids = SequentialIdProvider()
    private val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))), FakeClock(Instant.EPOCH), ids)

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    @Test fun theToolOpensAndClosesTheStrip() = editorTest {
        val vm = NotesViewModel("task-r1", "Box name", actions, ids)
        assertTrue(NoteTool.RENAME in vm.toolState().enabled)
        vm.onTool(NoteTool.RENAME)
        assertEquals(NoteDialog.Rename, vm.state.value.dialog)
        vm.onTool(NoteTool.RENAME)
        assertNull(vm.state.value.dialog)
    }

    @Test fun aNewNameIsTidiedSavedAndClosesTheStrip() = editorTest {
        actions.saveNoteDoc("task-r2", null, listOf(NoteDocBlock("b", NoteDocBlockType.PARAGRAPH, "Body")))
        val vm = NotesViewModel("task-r2", "Box name", actions, ids)
        vm.onTool(NoteTool.RENAME)
        assertTrue(vm.renameNote("  Meeting\n notes   for  Monday "))
        assertEquals("Meeting notes for Monday", vm.state.value.title)
        assertNull(vm.state.value.dialog)
        advanceUntilIdle()
        val stored = actions.loadNoteDoc("task-r2")!!
        assertEquals("Meeting notes for Monday", stored.title)
        assertEquals("Body", stored.blocks.single().text)
        assertEquals("Meeting notes for Monday", NotesViewModel("task-r2", "Box name", actions, ids).state.value.title)
    }

    @Test fun blankNamesAreRefusedAndLongOnesShortened() = editorTest {
        val vm = NotesViewModel("task-r3", "Box name", actions, ids)
        vm.onTool(NoteTool.RENAME)
        assertFalse(vm.renameNote("   "))
        assertEquals(NoteDialog.Rename, vm.state.value.dialog)
        assertTrue(vm.renameNote("x".repeat(500)))
        assertEquals(NotesViewModel.MAX_TITLE, vm.state.value.title.length)
    }
}
