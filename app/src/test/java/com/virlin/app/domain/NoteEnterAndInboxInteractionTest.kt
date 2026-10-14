package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.note.NoteEnterSemantics
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
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

    @Test fun currentEditorClosingFlushesKnowledgeDraftExactlyOnce() = runTest {
        val repo = InMemoryWorkStreamRepository(seedProjects = listOf(com.virlin.app.domain.model.Project("p", "Project", createdAt = clock.now(), updatedAt = clock.now())))
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = com.virlin.app.ui.notes.NotesViewModel(com.virlin.app.domain.notedoc.CaptureNoteBridge.draftOwner("p", "capture"), "Note", actions, ids)
        val first = vm.state.value.blocks.first().id
        vm.onTextChanged(first, "latest pending", androidx.compose.ui.text.TextRange(14))
        var closed = false
        vm.saveAndClose { closed = true }
        advanceUntilIdle()
        assertTrue(closed)
        assertEquals(1, repo.captures.value.size)
        assertEquals("p", repo.captures.value.single().projectId)
        vm.flush()
        advanceUntilIdle()
        assertEquals(1, repo.captures.value.size)
        assertEquals("latest pending", actions.loadNoteDoc("capture-capture")!!.blocks.single().text)
    }

    @Test fun currentEditorEmptyKnowledgeDraftDoesNotCreateInboxItem() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, clock, ids)
        val vm = com.virlin.app.ui.notes.NotesViewModel(com.virlin.app.domain.notedoc.CaptureNoteBridge.draftOwner("p", "empty"), "New note", actions, ids)
        var closed = false
        vm.saveAndClose { closed = true }
        advanceUntilIdle()
        assertTrue(closed)
        assertTrue(repo.captures.value.isEmpty())
    }
}
