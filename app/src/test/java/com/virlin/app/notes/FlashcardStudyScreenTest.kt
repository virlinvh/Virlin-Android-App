package com.virlin.app.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasScrollAction
import com.virlin.app.data.flashcard.FlashcardRating
import com.virlin.app.data.flashcard.SavedFlashcard
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.ui.notes.FlashcardStudyScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlashcardStudyScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newCardRevealsAnswerBeforeRating() {
        val card = SavedFlashcard("one", "task-one", "Notes",
            NoteDocBlock("answer", text = "Saved answer"),
            System.currentTimeMillis(), 5, recallPrompt = "What is the answer?")
        var rating: FlashcardRating? = null
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Pixel 8 Validation", listOf(card), null, false,
                    onBack = {}, onReview = { _, chosen, saved -> rating = chosen; saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_question").assertExists()
        compose.onNodeWithTag("flashcard_recall_quote")
            .assertTextContains("Recalling is kind of remembering.")
        compose.onNodeWithTag("flashcard_rate_hard").assertExists()
        compose.onNodeWithTag("flashcard_reveal").performClick()
        compose.onNodeWithTag("flashcard_answer").assertExists()
        compose.onNodeWithTag("flashcard_recall_quote").assertDoesNotExist()
        compose.onNodeWithTag("flashcard_rate_good").performClick()
        assertEquals(FlashcardRating.GOOD, rating)
    }

    @Test fun swipingBothDirectionsChangesTheVisibleCard() {
        val now = System.currentTimeMillis()
        val first = SavedFlashcard("one", "task-one", "Notes",
            NoteDocBlock("answer-one", text = "First answer"),
            now, 5, recallPrompt = "First question?")
        val second = SavedFlashcard("two", "task-one", "Notes",
            NoteDocBlock("answer-two", text = "Second answer"),
            now + 1, 6, recallPrompt = "Second question?")
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Pixel 8 Validation", listOf(first, second), null, false,
                    onBack = {}, onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_study_card").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("Second question?")
        compose.onNodeWithTag("flashcard_study_card").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("First question?")
    }

    @Test fun longAnswerScrollsWithinTheCard() {
        val longAnswer = (1..60).joinToString("\n") { "Remembered detail $it" }
        val card = SavedFlashcard("long", "task-one", "Notes",
            NoteDocBlock("answer", text = longAnswer),
            System.currentTimeMillis(), 2, recallPrompt = "What did you learn?")
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Pixel 8 Validation", listOf(card), null, false,
                    onBack = {}, onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_reveal").performClick()
        compose.onNodeWithTag("flashcard_answer_scroll").assert(hasScrollAction())
    }
}
