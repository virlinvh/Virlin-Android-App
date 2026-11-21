package com.virlin.app.ui.notes

import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Selecting a note image, its tools strip, and swapping in edited pictures with undo. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteImageToolsTest {
    private val ids = SequentialIdProvider()
    private val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))), FakeClock(Instant.EPOCH), ids)

    private val original = "00000000-0000-0000-0000-000000000001"
    private val edited = "00000000-0000-0000-0000-000000000002"

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    private var notes = 0

    private fun withImage(path: String = original): Pair<NotesViewModel, String> {
        val vm = NotesViewModel("task-image-${notes++}", "Note", actions, ids)
        vm.onFocusChanged(vm.state.value.blocks.single().id, true)
        vm.insertImage(path)
        return vm to vm.state.value.blocks.first { it.type == NoteDocBlockType.IMAGE }.id
    }

    @Test fun tappingAnImageSelectsItAndOpensItsToolsUntilTextTakesTheCaret() = editorTest {
        val (vm, image) = withImage()
        vm.selectImage(image)
        assertEquals(image, vm.state.value.selectedImage?.id)
        assertEquals(NoteDialog.ImageTools, vm.state.value.dialog)
        vm.toggleImageTools()
        assertNull(vm.state.value.dialog)
        vm.toggleImageTools()
        assertEquals(NoteDialog.ImageTools, vm.state.value.dialog)
        vm.onFocusChanged(vm.state.value.blocks.last().id, true)
        assertNull(vm.state.value.selectedImage)
        assertNull(vm.state.value.dialog)
    }

    @Test fun anEditedPictureReplacesTheBlockAndUndoBringsTheOriginalBack() = editorTest {
        val (vm, image) = withImage()
        vm.selectImage(image)
        vm.openImageEditor(NoteImageMode.CROP)
        assertEquals(NoteDialog.ImageEditor(image, NoteImageMode.CROP), vm.state.value.dialog)
        vm.replaceImage(image, edited)
        assertEquals(edited, vm.state.value.blocks.first { it.id == image }.imagePath)
        assertEquals(NoteDialog.ImageTools, vm.state.value.dialog)
        vm.undo()
        assertEquals(original, vm.state.value.blocks.first { it.id == image }.imagePath)
    }

    @Test fun deletingTheSelectedImageRemovesOnlyItAndUndoes() = editorTest {
        val (vm, image) = withImage()
        val before = vm.state.value.blocks.size
        vm.selectImage(image)
        vm.deleteSelectedImage()
        assertEquals(before - 1, vm.state.value.blocks.size)
        assertTrue(vm.state.value.blocks.none { it.type == NoteDocBlockType.IMAGE })
        assertNull(vm.state.value.dialog)
        vm.undo()
        assertEquals(original, vm.state.value.blocks.first { it.id == image }.imagePath)
    }

    @Test fun linkedImagesAreNotOpenedInTheEditor() = editorTest {
        val (vm, image) = withImage("https://example.com/picture.png")
        vm.selectImage(image)
        vm.openImageEditor(NoteImageMode.ADJUST)
        assertTrue(vm.state.value.dialog is NoteDialog.Message)
    }
}
