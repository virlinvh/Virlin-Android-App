package com.virlin.app.ui.notes

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocVideo
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

/** The YouTube tool: its panel, embedding where the caret is, changing the clip, removing it, undo. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteVideoEmbedTest {
    private val ids = SequentialIdProvider()
    private val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))), FakeClock(Instant.EPOCH), ids)
    private val clip = NoteDocVideo("dQw4w9WgXcQ", 90, 165, "Title", "Channel")
    private var notes = 0

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    private fun editor(): NotesViewModel {
        val vm = NotesViewModel("task-video-${notes++}", "Note", actions, ids)
        val first = vm.state.value.blocks.single().id
        vm.onFocusChanged(first, true)
        vm.onTextChanged(first, "First line", androidx.compose.ui.text.TextRange(10))
        return vm
    }

    @Test fun theToolOpensAndClosesThePanel() = editorTest {
        val vm = editor()
        assertTrue(NoteTool.YOUTUBE in vm.toolState().enabled)
        vm.onTool(NoteTool.YOUTUBE)
        assertEquals(NoteDialog.Video(), vm.state.value.dialog)
        vm.onTool(NoteTool.YOUTUBE)
        assertNull(vm.state.value.dialog)
    }

    @Test fun embeddingPlacesTheClipAfterTheCaretLineAndUndoes() = editorTest {
        val vm = editor()
        vm.onTool(NoteTool.YOUTUBE)
        assertTrue(vm.insertVideo(clip))
        val blocks = vm.state.value.blocks
        assertEquals(listOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.VIDEO, NoteDocBlockType.PARAGRAPH), blocks.map { it.type })
        assertEquals("First line", blocks[0].text)
        assertEquals(clip, blocks[1].video)
        assertEquals(blocks[2].id, vm.state.value.focusedBlockId)
        assertNull(vm.state.value.dialog)
        vm.undo()
        assertTrue(vm.state.value.blocks.none { it.type == NoteDocBlockType.VIDEO })
    }

    @Test fun invalidClipsAreNeverEmbedded() = editorTest {
        val vm = editor()
        assertFalse(vm.insertVideo(clip.copy(endSeconds = 10)))
        assertTrue(vm.state.value.blocks.none { it.type == NoteDocBlockType.VIDEO })
    }

    @Test fun changingAndRemovingAClipUndo() = editorTest {
        val vm = editor()
        vm.insertVideo(clip)
        val video = vm.state.value.blocks.first { it.type == NoteDocBlockType.VIDEO }.id
        vm.playVideo(video)
        assertEquals(video, vm.state.value.playingVideoId)
        vm.openDialog(NoteDialog.Video(video))
        assertTrue(vm.updateVideo(video, clip.copy(startSeconds = 10, endSeconds = 20)))
        assertEquals(10, vm.state.value.blocks.first { it.id == video }.video?.startSeconds)
        assertNull("the player reopens on the new clip", vm.state.value.playingVideoId)
        assertNull(vm.state.value.dialog)
        vm.undo()
        assertEquals(clip, vm.state.value.blocks.first { it.id == video }.video)
        assertTrue(vm.deleteVideo(video))
        assertTrue(vm.state.value.blocks.none { it.type == NoteDocBlockType.VIDEO })
        vm.undo()
        assertEquals(clip, vm.state.value.blocks.first { it.id == video }.video)
    }
}
