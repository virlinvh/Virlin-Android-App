package com.virlin.app.ui.notes

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.notedoc.NoteDocAudio
import com.virlin.app.domain.notedoc.NoteDocBlockType
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

/** The microphone tool: its strip, inserting a recording where the caret is, removing it, undo. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteAudioInsertTest {
    private val ids = SequentialIdProvider()
    private val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))), FakeClock(Instant.EPOCH), ids)
    private val recording = NoteDocAudio("0f8fad5b-d9cb-469f-a165-70867728950e.m4a", 4_200, "13579bdf")
    private var notes = 0

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    private fun editor(): NotesViewModel {
        val vm = NotesViewModel("task-audio-${notes++}", "Note", actions, ids)
        val first = vm.state.value.blocks.single().id
        vm.onFocusChanged(first, true)
        vm.onTextChanged(first, "First line", androidx.compose.ui.text.TextRange(10))
        return vm
    }

    @Test fun theToolOpensAndClosesTheStrip() = editorTest {
        val vm = editor()
        assertTrue(NoteTool.AUDIO in vm.toolState().enabled)
        vm.onTool(NoteTool.AUDIO)
        assertEquals(NoteDialog.Audio, vm.state.value.dialog)
        vm.onTool(NoteTool.AUDIO)
        assertNull(vm.state.value.dialog)
    }

    @Test fun textToAudioOpensItsPopupAndInsertsLikeARecording() = editorTest {
        val vm = editor()
        assertTrue(NoteTool.TEXT_TO_AUDIO in vm.toolState().enabled)
        vm.onTool(NoteTool.TEXT_TO_AUDIO)
        assertEquals(NoteDialog.TextToAudio, vm.state.value.dialog)
        val spoken = recording.copy(title = "Read this aloud")
        assertTrue(vm.insertAudio(spoken))
        assertEquals(spoken, vm.state.value.blocks.first { it.type == NoteDocBlockType.AUDIO }.audio)
        assertNull(vm.state.value.dialog)
    }

    @Test fun longTextIsSpokenInSentenceSizedParts() {
        assertEquals(listOf("Short text."), speechChunks("  Short text.  ", 100))
        assertEquals(emptyList<String>(), speechChunks("   ", 100))
        val text = (1..60).joinToString(" ") { "Sentence number $it ends here." }
        val parts = speechChunks(text, 200)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 200 })
        assertTrue("parts end at sentences", parts.dropLast(1).all { it.endsWith(".") })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
        val unbroken = "x".repeat(450)
        assertEquals(unbroken, speechChunks(unbroken, 200).joinToString(""))
    }

    @Test fun insertingPlacesTheRecordingAfterTheCaretLineAndUndoes() = editorTest {
        val vm = editor()
        vm.onTool(NoteTool.AUDIO)
        assertTrue(vm.insertAudio(recording))
        val blocks = vm.state.value.blocks
        assertEquals(listOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.AUDIO, NoteDocBlockType.PARAGRAPH), blocks.map { it.type })
        assertEquals("First line", blocks[0].text)
        assertEquals(recording, blocks[1].audio)
        assertEquals(blocks[2].id, vm.state.value.focusedBlockId)
        assertNull(vm.state.value.dialog)
        vm.undo()
        assertTrue(vm.state.value.blocks.none { it.type == NoteDocBlockType.AUDIO })
    }

    @Test fun invalidRecordingsAreNeverInserted() = editorTest {
        val vm = editor()
        assertFalse(vm.insertAudio(recording.copy(file = "../escape.m4a")))
        assertTrue(vm.state.value.blocks.none { it.type == NoteDocBlockType.AUDIO })
    }

    @Test fun removingARecordingUndoes() = editorTest {
        val vm = editor()
        vm.insertAudio(recording)
        val audio = vm.state.value.blocks.first { it.type == NoteDocBlockType.AUDIO }.id
        assertTrue(vm.deleteAudio(audio))
        assertTrue(vm.state.value.blocks.none { it.type == NoteDocBlockType.AUDIO })
        vm.undo()
        assertEquals(recording, vm.state.value.blocks.first { it.id == audio }.audio)
    }
}
