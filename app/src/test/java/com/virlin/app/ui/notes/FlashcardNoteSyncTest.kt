package com.virlin.app.ui.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.isRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** Card edits reach the note only when asked, only where they exactly belong, and never delete. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlashcardNoteSyncTest {
    @get:Rule val compose = createComposeRule()

    private val note = listOf(
        NoteDocBlock("h", NoteDocBlockType.HEADING_2, "Backend"),
        NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "Intro. Code that runs on a server. Outro.",
            runs = listOf(NoteDocRun(0, 6, setOf(NoteDocMark.BOLD)))),
        NoteDocBlock("q", NoteDocBlockType.PARAGRAPH, "Say it like this."),
    )
    // The card was made from part of "p" and all of "q".
    private val oldBack = listOf(
        NoteDocBlock("card-block-0", NoteDocBlockType.PARAGRAPH, "Code that runs on a server."),
        NoteDocBlock("card-block-1", NoteDocBlockType.PARAGRAPH, "Say it like this."),
    )
    private val oldFront = listOf(NoteDocBlock("card-block-0", NoteDocBlockType.PARAGRAPH, "What is a backend?"))
    private var n = 0
    private fun plan(newBack: List<NoteDocBlock>, newFront: List<NoteDocBlock> = oldFront, source: String? = "p") =
        planFlashcardNoteSync(note, source, listOf(oldBack, oldFront), listOf(newBack, newFront)) { "new-${n++}" }

    @Test fun anEditReplacesOnlyItsOwnSliceAndKeepsTheRestOfTheBlock() {
        val edited = oldBack[0].copy(text = "Code that runs on your VPS.",
            runs = listOf(NoteDocRun(0, 27, fontFamily = "Caveat")))
        val p = plan(listOf(edited, oldBack[1]))
        assertEquals(1, p.changed)
        val block = p.blocks.first { it.id == "p" }
        assertEquals("Intro. Code that runs on your VPS. Outro.", block.text)
        assertTrue(block.runs.any { it.start == 0 && it.end == 6 && NoteDocMark.BOLD in it.marks })
        assertTrue(block.runs.any { it.start == 7 && it.end == 34 && it.fontFamily == "Caveat" })
        assertEquals(note.map { it.id }, p.blocks.map { it.id })
    }

    @Test fun aPictureLandsBelowOrAboveTheTextItSitsBy() {
        val picture = NoteDocBlock("card-img-x", NoteDocBlockType.IMAGE, imagePath = "x")
        val below = plan(listOf(oldBack[0], picture, oldBack[1]))
        assertEquals(listOf("h", "p", "new-0", "q"), below.blocks.map { it.id })
        assertEquals("x", below.blocks[2].imagePath)
        val above = plan(listOf(picture, oldBack[0], oldBack[1]))
        assertEquals(listOf("h", "new-1", "p", "q"), above.blocks.map { it.id })
        assertEquals(1, above.inserted)
    }

    @Test fun removingAPartNeverDeletesItFromTheNote() {
        val p = plan(listOf(oldBack[0]))
        assertFalse(p.hasChanges)
        assertEquals(1, p.keptInNote)
        assertEquals(note, p.blocks)
    }

    @Test fun textThatIsNotInTheNoteIsSkippedNotGuessed() {
        val front = listOf(oldFront[0].copy(text = "What does a backend do?"))
        val p = plan(oldBack, front)
        assertFalse(p.hasChanges)
        assertEquals(1, p.skipped)
        assertEquals(note, p.blocks)
    }

    @Test fun aRepeatedPassageIsAmbiguousAndLeftAlone() {
        val twice = listOf(NoteDocBlock("a", NoteDocBlockType.PARAGRAPH, "same. same."))
        val old = listOf(NoteDocBlock("card-block-0", NoteDocBlockType.PARAGRAPH, "same."))
        val p = planFlashcardNoteSync(twice, "a", listOf(old), listOf(listOf(old[0].copy(text = "other.")))) { "n" }
        assertEquals(twice, p.blocks)
        assertEquals(1, p.skipped)
    }

    @Test fun aPictureOnAFrontNotFromTheNoteGoesAfterTheSourceBlock() {
        val picture = NoteDocBlock("card-img-y", NoteDocBlockType.IMAGE, imagePath = "y")
        val p = plan(oldBack, oldFront + picture)
        assertEquals(listOf("h", "p", "new-0", "q"), p.blocks.map { it.id })
    }

    private val ids = SequentialIdProvider()
    private val actions = DefaultVirlinActions(InMemoryWorkStreamRepository(seedProjects = listOf(
        Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))), FakeClock(Instant.EPOCH), ids)

    private fun editorTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { body() } finally { Dispatchers.resetMain() }
    }

    @Test fun thePlanSavesAgainstTheRevisionItWasMadeFrom() = editorTest {
        actions.saveNoteDoc("task-s1", "Note", note)
        val doc = actions.loadNoteDoc("task-s1")!!
        val p = plan(listOf(oldBack[0].copy(text = "Code on a server."), oldBack[1]))
        assertTrue(actions.saveNoteDoc("task-s1", doc.title, p.blocks, expectedRevision = doc.revision) is ActionResult.Success)
        assertEquals("Intro. Code on a server. Outro.", actions.loadNoteDoc("task-s1")!!.blocks[1].text)
        // A note changed after it was read is refused, not overwritten.
        assertTrue(actions.saveNoteDoc("task-s1", doc.title, note, expectedRevision = doc.revision) is ActionResult.Rejected)
    }

    @Test fun anOpenNoteWithNothingUnsavedShowsTheUpdatedVersion() = editorTest {
        actions.saveNoteDoc("task-s2", "Note", note)
        val vm = NotesViewModel("task-s2", "Note", actions, ids)
        advanceUntilIdle()
        val doc = actions.loadNoteDoc("task-s2")!!
        actions.saveNoteDoc("task-s2", doc.title, plan(listOf(oldBack[0].copy(text = "Edited."), oldBack[1])).blocks,
            expectedRevision = doc.revision)
        vm.refreshIfStoredChanged()
        advanceUntilIdle()
        assertEquals("Intro. Edited. Outro.", vm.state.value.blocks[1].text)
    }

    @Test fun anOpenNoteWithUnsavedTypingIsNeverReplaced() = editorTest {
        actions.saveNoteDoc("task-s3", "Note", note)
        val vm = NotesViewModel("task-s3", "Note", actions, ids)
        advanceUntilIdle()
        vm.onTextChanged("q", "Typed here", androidx.compose.ui.text.TextRange(10))
        val doc = actions.loadNoteDoc("task-s3")!!
        actions.saveNoteDoc("task-s3", doc.title, note.map { if (it.id == "h") it.copy(text = "Elsewhere") else it },
            expectedRevision = doc.revision)
        vm.refreshIfStoredChanged()
        advanceUntilIdle()
        assertEquals("Typed here", vm.state.value.blocks.first { it.id == "q" }.text)
        assertEquals("Backend", vm.state.value.blocks.first { it.id == "h" }.text)
    }

    private val start = FlashcardEditStart(oldFront, oldBack + NoteDocBlock("card-block-2", NoteDocBlockType.IMAGE,
        imagePath = "00000000-0000-0000-0000-000000000000"), "NORMAL", frontCardStyle = true)

    @Test fun aFontAppliesToTheWholeFaceAndTheFrontKeepsIt() {
        var saved: Pair<List<NoteDocBlock>, Boolean>? = null
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(emptyList(), saving = false, error = null, onDismiss = {}, edit = start,
                    onSave = { front, _, _, _, cardStyle -> saved = front to cardStyle })
            }
        }
        compose.onNodeWithTag("flashcard_prompt_font_Caveat").performScrollTo().performClick()
        compose.onNodeWithTag("flashcard_save").performClick()
        val (front, cardStyle) = saved!!
        assertEquals("Caveat", flashcardFaceFont(front))
        assertFalse(cardStyle)
    }

    @Test fun thePictureMovesAndTheSwitchStartsOffAndHandsOverTheEditedFaces() {
        var on by mutableStateOf(false)
        var handed: List<NoteDocBlock>? = null
        var savedBack: List<NoteDocBlock>? = null
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(emptyList(), saving = false, error = null, onDismiss = {}, edit = start,
                    noteUpdate = FlashcardNoteUpdate(on, { on = it }) { _, back -> handed = back },
                    onSave = { _, back, _, _, _ -> savedBack = back })
            }
        }
        compose.onNodeWithTag("flashcard_answer_block_up_2").performScrollTo().performClick()
        compose.onNodeWithTag("flashcard_answer_add_image").performScrollTo()
        compose.onAllNodes(isRoot())[1].captureRoboImage("build/outputs/flashcard-edit-fonts-image.png")
        compose.onNodeWithTag("flashcard_update_note").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("flashcard_update_note").assertIsOn()
        compose.onAllNodes(isRoot())[1].captureRoboImage("build/outputs/flashcard-edit-update-switch.png")
        assertTrue(on)
        compose.onNodeWithTag("flashcard_save").performClick()
        assertEquals(listOf("card-block-0", "card-block-2", "card-block-1"), handed!!.map { it.id })
        assertEquals(NoteDocBlockType.IMAGE, savedBack!![1].type)
    }

    @Test fun withoutANoteThereIsNoSwitch() {
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(emptyList(), saving = false, error = null, onDismiss = {}, edit = start,
                    onSave = { _, _, _, _, _ -> })
            }
        }
        compose.onNodeWithTag("flashcard_update_note").assertDoesNotExist()
        assertNull(null)
    }
}
