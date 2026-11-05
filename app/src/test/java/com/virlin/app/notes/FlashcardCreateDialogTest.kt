package com.virlin.app.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.ui.notes.FlashcardCreateDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class FlashcardCreateDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedSourceCanFillBothSidesAndSaveReviewPlan() {
        val source = NoteDocBlock("selected", text = "Heading\nAnswer text",
            runs = listOf(NoteDocRun(0, 7, setOf(NoteDocMark.BOLD))))
        var submitted: Pair<NoteDocBlock, NoteDocBlock>? = null
        var plan: Pair<String, Int>? = null
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(source, saving = false, error = null, onDismiss = {},
                    onSave = { front, back, difficulty, days ->
                        submitted = front to back
                        plan = difficulty to days
                    })
            }
        }
        compose.onNodeWithTag("flashcard_save").assertIsNotEnabled()
        compose.onNodeWithTag("flashcard_source").performTextInputSelection(TextRange(0, 7))
        compose.onNodeWithTag("flashcard_to_front").performClick()
        compose.onNodeWithTag("flashcard_source").performTextInputSelection(TextRange(8, 19))
        compose.onNodeWithTag("flashcard_to_back").performClick()
        compose.onNodeWithText("Hard").performScrollTo().performClick()
        compose.onNodeWithText("3d").performScrollTo().performClick()
        compose.onNodeWithTag("flashcard_save").performClick()
        assertEquals("Heading", submitted?.first?.text)
        assertEquals("Answer text", submitted?.second?.text)
        assertTrue(submitted!!.first.runs.any { NoteDocMark.BOLD in it.marks })
        assertEquals("HARD" to 3, plan)
    }

    @Test fun bothSidesAreVisibleAndCanBeRemovedWithoutChangingSource() {
        val source = NoteDocBlock("selected", text = "Heading\nAnswer")
        compose.setContent {
            MaterialTheme {
                FlashcardCreateDialog(source, saving = false, error = null,
                    onDismiss = {}, onSave = { _, _, _, _ -> })
            }
        }
        compose.onNodeWithTag("flashcard_source").performTextInputSelection(TextRange(0, 7))
        compose.onNodeWithTag("flashcard_to_front").performClick()
        compose.onNodeWithTag("flashcard_source").performTextInputSelection(TextRange(8, 14))
        compose.onNodeWithTag("flashcard_to_back").performClick()
        compose.onNodeWithTag("flashcard_prompt").assertExists()
        compose.onNodeWithTag("flashcard_answer").assertExists()
        compose.onNodeWithTag("flashcard_prompt_remove").performClick()
        compose.onNodeWithTag("flashcard_save").assertIsNotEnabled()
        assertEquals("Heading\nAnswer", source.text)
    }
}
