package com.virlin.app.ui.notes

import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NotePasteSafety
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NoteEditorStabilityTest {

    private val ids = SequentialIdProvider()
    private val repo = InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)))
    private val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), ids)

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    private fun TestScope.vm(owner: String) =
        NotesViewModel(owner, "Note", actions, ids, pasteDispatcher = StandardTestDispatcher(testScheduler))

    @Test fun largePasteFormatsInBackgroundAndKeepsEveryCharacter() = editorTest {
        val vm = vm("task-big")
        val original = vm.state.value.blocks.single()
        val text = (1..3_000).joinToString("\n\n") { "Paragraph $it with plain words only" }
        assertTrue(text.length > NotePasteSafety.BACKGROUND_CHARS)
        assertTrue(vm.pasteStructured(original.id, text, text))
        assertTrue(vm.state.value.pasting)
        assertEquals(listOf(original), vm.state.value.blocks)
        advanceUntilIdle()
        assertFalse(vm.state.value.pasting)
        assertNull(vm.state.value.dialog)
        assertEquals(3_000, vm.state.value.blocks.size)
        assertEquals(text.replace("\n", ""), vm.state.value.blocks.joinToString("") { it.text })
        assertEquals("Paragraph 3000 with plain words only",
            actions.loadNoteDoc("task-big")!!.blocks.last().text)
        vm.undo()
        assertEquals(listOf(original), vm.state.value.blocks)
    }

    @Test fun oneMillionCharacterGeneratedAnswerPastesWithoutLoss() = editorTest {
        val vm = vm("task-huge")
        val original = vm.state.value.blocks.single()
        val section = "## Section\n\nSome **bold** words and a [link](https://example.com) here.\n\n" +
            "```python\nprint('hello')  # comment\n```\n\n"
        val text = section.repeat(1_000_000 / section.length + 1)
        assertTrue(text.length >= 1_000_000)
        assertTrue(vm.pasteStructured(original.id, text, text))
        advanceUntilIdle()
        val blocks = vm.state.value.blocks
        assertFalse(vm.state.value.pasting)
        val sections = text.length / section.length
        assertEquals(sections, blocks.count { it.type == NoteDocBlockType.HEADING_2 })
        assertEquals(sections, blocks.count { it.type == NoteDocBlockType.CODE && it.text == "print('hello')  # comment" })
        assertTrue(blocks.filter { it.type == NoteDocBlockType.PARAGRAPH }
            .all { it.runs.any { run -> run.linkUrl == "https://example.com" } })
    }

    @Test fun veryLongSingleLinePasteIsKeptWhole() = editorTest {
        val vm = vm("task-line")
        val original = vm.state.value.blocks.single()
        val text = "x".repeat(250_000)
        assertTrue(vm.pasteStructured(original.id, text, text))
        advanceUntilIdle()
        assertEquals(text, vm.state.value.blocks.joinToString("") { it.text })
    }

    @Test fun editsMadeWhileFormattingAreNeverOverwritten() = editorTest {
        val vm = vm("task-race")
        val id = vm.state.value.blocks.single().id
        vm.onTextChanged(id, "typed", TextRange(5))
        val pasted = (1..2_000).joinToString("\n\n") { "Line $it of pasted text" }
        assertTrue(vm.pasteStructured(id, "typed$pasted", pasted))
        vm.onTextChanged(id, "typed!", TextRange(6))
        advanceUntilIdle()
        val blocks = vm.state.value.blocks
        assertEquals("typed!", blocks.first().text)
        assertEquals("Line 1 of pasted text", blocks[1].text)
        assertEquals(2_001, blocks.size)
    }

    @Test fun pastingIntoCodeStaysLiteral() = editorTest {
        val vm = vm("task-code")
        val id = vm.state.value.blocks.single().id
        val fence = "```python\nx = 1\n```"
        assertTrue(vm.pasteStructured(id, fence, fence))
        val code = vm.state.value.blocks.single { it.type == NoteDocBlockType.CODE }
        val addition = "\n# not a heading\n**not bold**"
        assertFalse(vm.pasteStructured(code.id, code.text + addition, addition))
    }

    @Test fun typingNeverParsesALargeClipboard() = editorTest {
        val vm = vm("task-typing")
        val id = vm.state.value.blocks.single().id
        val html = "<p>" + "big ".repeat(80_000) + "</p>"
        assertFalse(vm.pasteStructured(id, "a", "big ".repeat(80_000), html))
        assertFalse(vm.state.value.pasting)
    }

    @Test fun newerDesktopVersionIsNeverOverwrittenAndBothAreKept() = editorTest {
        actions.saveNoteDoc("task-c", "T", listOf(NoteDocBlock("a", NoteDocBlockType.PARAGRAPH, "base")))
        val vm = vm("task-c")
        actions.saveNoteDoc("task-c", "T", listOf(NoteDocBlock("a", NoteDocBlockType.PARAGRAPH, "desktop")),
            expectedRevision = 1)
        vm.onTextChanged("a", "phone", TextRange(5))
        advanceUntilIdle()
        val refused = vm.state.value.dialog as NoteDialog.SaveRefused
        assertTrue(refused.stale)
        assertFalse(refused.closeAfter)
        assertEquals(NoteSaveState.FAILED, vm.state.value.save)
        assertEquals("desktop", actions.loadNoteDoc("task-c")!!.blocks.single().text)

        vm.saveEditsAsNewNote("p") {}
        advanceUntilIdle()
        assertEquals("desktop", vm.state.value.blocks.single().text)
        val copy = repo.captures.value.single { it.projectId == "p" }
        assertEquals("phone", actions.loadNoteDoc(CaptureNoteBridge.owner(copy.id))!!.blocks.single().text)
        assertEquals("desktop", actions.loadNoteDoc("task-c")!!.blocks.single().text)

        vm.onTextChanged("a", "desktop then phone", TextRange(18))
        advanceUntilIdle()
        assertEquals(NoteSaveState.SAVED, vm.state.value.save)
        assertEquals("desktop then phone", actions.loadNoteDoc("task-c")!!.blocks.single().text)
    }

    @Test fun identicalWriteElsewhereIsNotAConflict() = editorTest {
        actions.saveNoteDoc("task-same", "T", listOf(NoteDocBlock("a", NoteDocBlockType.PARAGRAPH, "base")))
        val vm = vm("task-same")
        actions.saveNoteDoc("task-same", "T", listOf(NoteDocBlock("a", NoteDocBlockType.PARAGRAPH, "same")),
            expectedRevision = 1)
        vm.onTextChanged("a", "same", TextRange(4))
        advanceUntilIdle()
        assertNull(vm.state.value.dialog)
        assertEquals(NoteSaveState.SAVED, vm.state.value.save)
    }

    @Test fun refusedSaveOnCloseOffersAChoiceInsteadOfDoingNothing() = editorTest {
        actions.saveNoteDoc("task-close", "T", listOf(NoteDocBlock("a", NoteDocBlockType.PARAGRAPH, "base")))
        val vm = vm("task-close")
        actions.saveNoteDoc("task-close", "T", listOf(NoteDocBlock("a", NoteDocBlockType.PARAGRAPH, "desktop")),
            expectedRevision = 1)
        vm.onTextChanged("a", "phone", TextRange(5))
        vm.closeDialog()
        var closed = false
        vm.saveAndClose { closed = true }
        advanceUntilIdle()
        assertFalse(closed)
        val refused = vm.state.value.dialog as NoteDialog.SaveRefused
        assertTrue(refused.closeAfter)
        vm.discardEdits { closed = true }
        assertTrue(closed)
        assertEquals("desktop", actions.loadNoteDoc("task-close")!!.blocks.single().text)
    }

    @Test fun damagedNoteShowsRecoveryInsteadOfABlankPage() = editorTest {
        val raw = "{\"v\":1,\"blocks\":[{\"text\":\"keep me\""
        repo.transaction { saveNoteDoc(VirlinNoteDoc("task-d", "Broken", revision = 4,
            loadError = "not JSON", damagedRaw = raw)) }
        val vm = vm("task-d")
        assertTrue(vm.state.value.damaged)
        assertEquals(raw, vm.damagedPayload())
        vm.recoverDamaged()
        advanceUntilIdle()
        assertFalse(vm.state.value.damaged)
        assertEquals("keep me", vm.state.value.blocks.single().text)
        assertNull(actions.loadNoteDoc("task-d")!!.loadError)
    }
}
