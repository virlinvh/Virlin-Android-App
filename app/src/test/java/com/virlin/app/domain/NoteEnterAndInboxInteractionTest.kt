package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.note.NoteEnterSemantics
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.note.TextNoteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class NoteEnterAndInboxInteractionTest {

    private val clock = FakeClock(Instant.parse("2026-09-14T12:00:00Z"))
    private val ids = SequentialIdProvider()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setMain() { Dispatchers.setMain(dispatcher) }
    @After fun resetMain() { Dispatchers.resetMain() }
    @Test fun enter_text_createsNewTextBlock() {
        val b = NoteBlock("1", NoteBlockType.TEXT, "hello")
        val r = NoteEnterSemantics.applyEnter(b, "hello", "", "2")
        assertEquals("hello", r.updatedCurrent.plainText)
        assertEquals(NoteBlockType.TEXT, r.newSibling!!.type)
        assertEquals("", r.newSibling!!.plainText)
    }

    @Test fun enter_heading_createsTextBlock() {
        val b = NoteBlock("1", NoteBlockType.HEADING_1, "Title")
        val r = NoteEnterSemantics.applyEnter(b, "Title", "", "2")
        assertEquals(NoteBlockType.TEXT, r.newSibling!!.type)
    }

    @Test fun enter_bullet_nonEmpty_createsBulletSibling() {
        val b = NoteBlock("1", NoteBlockType.BULLETED_LIST, "item")
        val r = NoteEnterSemantics.applyEnter(b, "item", "", "2")
        assertEquals(NoteBlockType.BULLETED_LIST, r.newSibling!!.type)
    }

    @Test fun enter_emptyBullet_exitsToText() {
        val b = NoteBlock("1", NoteBlockType.BULLETED_LIST, "")
        val r = NoteEnterSemantics.applyEnter(b, "", "", "2")
        assertEquals(NoteBlockType.TEXT, r.updatedCurrent.type)
        assertNull(r.newSibling)
    }

    @Test fun enter_checkbox_nonEmpty_createsCheckbox() {
        val b = NoteBlock("1", NoteBlockType.CHECKBOX, "todo")
        val r = NoteEnterSemantics.applyEnter(b, "todo", "", "2")
        assertEquals(NoteBlockType.CHECKBOX, r.newSibling!!.type)
    }

    @Test fun enter_emptyCheckbox_exitsToText() {
        val b = NoteBlock("1", NoteBlockType.CHECKBOX, "")
        val r = NoteEnterSemantics.applyEnter(b, "", "", "2")
        assertEquals(NoteBlockType.TEXT, r.updatedCurrent.type)
        assertNull(r.newSibling)
    }

    @Test fun softLineBreak_staysSameBlock() {
        val next = NoteEnterSemantics.insertSoftBreak("ab", 1)
        assertEquals("a\nb", next)
        assertEquals(1, NoteEnterSemantics.newlineInsertion("ab", "a\nb"))
    }

    @Test fun longWrappedText_doesNotSplitWithoutEnter() {
        val long = "word ".repeat(80).trim()
        val b = NoteBlock("1", NoteBlockType.TEXT, long)
        // No Enter applied — still one block
        assertEquals(1, listOf(b).size)
        assertFalse(long.contains('\n'))
        assertTrue(b.plainText.length > 200)
    }

    @Test fun code_enter_staysSameBlockWithNewline() {
        val b = NoteBlock("1", NoteBlockType.CODE, "x")
        val r = NoteEnterSemantics.applyEnter(b, "x", "y", "2")
        assertNull(r.newSibling)
        assertEquals("x\ny", r.updatedCurrent.plainText)
    }

    @Test fun toggleChild_enter_preservesParentChildren() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = TextNoteViewModel(null, actions, repo, ids, clock)
        vm.transformBlock(vm.state.value.blocks.first().id, NoteBlockType.TOGGLE)
        val toggleId = vm.state.value.blocks.first().id
        vm.addChildToToggle(toggleId)
        val childId = vm.state.value.blocks.first().children.first().id
        vm.onBlockTextChange(childId, "kid")
        vm.handleEnter(childId, "kid", "")
        val toggle = vm.state.value.blocks.first()
        assertEquals(2, toggle.children.size)
        assertEquals("kid", toggle.children[0].plainText)
        assertEquals(NoteBlockType.TEXT, toggle.children[1].type)
    }

    @Test fun slashSelection_removesSlashContent() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = TextNoteViewModel(null, actions, repo, ids, clock)
        val id = vm.state.value.blocks.first().id
        vm.onBlockTextChange(id, "/")
        vm.transformBlock(id, NoteBlockType.HEADING_1)
        assertEquals("", vm.state.value.blocks.first().plainText)
        assertEquals(NoteBlockType.HEADING_1, vm.state.value.blocks.first().type)
        assertFalse(vm.state.value.blocks.first().plainText.contains("/"))
    }

    @Test fun back_meaningfulDraft_exactlyOneInboxItem() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = TextNoteViewModel(null, actions, repo, ids, clock)
        vm.onTitleChange("Only one")
        vm.onBlockTextChange(vm.state.value.blocks.first().id, "body")
        val exit = vm.prepareExit()
        assertTrue(exit.showInboxFeedback)
        assertTrue(exit.navigate)
        assertEquals(1, repo.captures.value.size)
        assertTrue(vm.state.value.committedToInbox)
    }

    @Test fun back_emptyDraft_noInboxItem() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = TextNoteViewModel(null, actions, repo, ids, clock)
        val exit = vm.prepareExit()
        assertTrue(exit.navigate)
        assertFalse(exit.showInboxFeedback)
        assertTrue(repo.captures.value.isEmpty())
    }

    @Test fun saveToInbox_staysEditable_noDuplicateOnBack() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = TextNoteViewModel(null, actions, repo, ids, clock)
        val id = vm.state.value.blocks.first().id
        vm.onBlockTextChange(id, "hello")
        vm.saveToInbox()
        advanceUntilIdle()
        assertTrue(vm.state.value.committedToInbox)
        assertEquals(1, repo.captures.value.size)
        vm.onBlockTextChange(id, "hello edited")
        vm.prepareExit()
        assertEquals(1, repo.captures.value.size)
        assertEquals("hello edited", repo.getNoteByCaptureId(repo.captures.value.first().id)!!.blocks.first().plainText)
    }

    @Test fun inboxNote_back_noDuplicate() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val created = (actions.createTextNote("T", listOf(NoteBlock("b", NoteBlockType.TEXT, "x"))) as ActionResult.Success).value
        val vm = TextNoteViewModel(created.captureItemId, actions, repo, ids, clock)
        advanceUntilIdle()
        assertTrue(vm.state.value.committedToInbox)
        vm.onBlockTextChange(vm.state.value.blocks.first().id, "x2")
        vm.prepareExit()
        assertEquals(1, repo.captures.value.size)
    }

    @Test fun pendingText_flushedBeforeInboxCommit() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = TextNoteViewModel(null, actions, repo, ids, clock)
        val id = vm.state.value.blocks.first().id
        vm.onBlockTextChange(id, "latest pending")
        // Do not wait for autosave debounce — Back must flush immediately
        vm.prepareExit()
        assertEquals(1, repo.captures.value.size)
        assertTrue(repo.captures.value.first().content.contains("latest pending"))
    }
}
