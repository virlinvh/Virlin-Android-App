package com.virlin.app.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.virlin.app.data.flashcard.FlashcardColorMode
import com.virlin.app.data.flashcard.FlashcardDeckStyle
import com.virlin.app.data.flashcard.FlashcardDesign
import com.virlin.app.data.flashcard.FlashcardRating
import com.virlin.app.data.flashcard.FlashcardTypeface
import com.virlin.app.data.flashcard.SavedFlashcard
import androidx.compose.ui.test.onNodeWithText
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
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
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_question").assertExists()
        compose.onNodeWithTag("flashcard_rate_hard").assertExists()
        compose.onNodeWithTag("flashcard_reveal").performClick()
        compose.onNodeWithTag("flashcard_answer").assertExists()
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
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_study_card").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("Second question?")
        compose.onNodeWithTag("flashcard_study_card").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("First question?")
    }

    @Test fun aShortQuickFlickIsEnoughToMoveACard() {
        val now = System.currentTimeMillis()
        val first = SavedFlashcard("one", "task-one", "Notes",
            NoteDocBlock("answer-one", text = "First answer"), now, 5, recallPrompt = "First question?")
        val second = SavedFlashcard("two", "task-one", "Notes",
            NoteDocBlock("answer-two", text = "Second answer"), now + 1, 6, recallPrompt = "Second question?")
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Deck", listOf(first, second), null, false,
                    onBack = {}, onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_study_card").performTouchInput {
            swipeLeft(startX = centerX + width * .1f, endX = centerX - width * .1f, durationMillis = 90)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("Second question?")
        compose.onNodeWithTag("flashcard_study_card").performTouchInput {
            swipeRight(startX = centerX - width * .1f, endX = centerX + width * .1f, durationMillis = 90)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("First question?")
    }

    @Test fun tappingTheCardTurnsItOver() {
        val card = SavedFlashcard("tap", "task-one", "Notes", NoteDocBlock("answer", text = "Tapped answer"),
            System.currentTimeMillis(), 3, recallPrompt = "Tap me?",
            frontBlocks = listOf(NoteDocBlock("f", NoteDocBlockType.PARAGRAPH, "Tap me?")))
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Deck", listOf(card), null, false,
                    onBack = {}, onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_study_card").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_answer").assertExists()
    }

    @Test fun nextWrapsAroundFromTheLastCardToTheFirst() {
        val now = System.currentTimeMillis()
        val first = SavedFlashcard("one", "task-one", "Notes", NoteDocBlock("a1", text = "A1"),
            now, 5, recallPrompt = "First question?")
        val second = SavedFlashcard("two", "task-one", "Notes", NoteDocBlock("a2", text = "A2"),
            now + 1, 6, recallPrompt = "Second question?")
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Deck", listOf(first, second), null, false,
                    onBack = {}, onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("Second question?")
        compose.onNodeWithTag("flashcard_next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("First question?")
        compose.onNodeWithTag("flashcard_previous").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("Second question?")
    }

    @Test fun deckTileOpensPracticeAtThatCardAndArchiveLeavesTheQueue() {
        val now = System.currentTimeMillis()
        var cards by mutableStateOf(listOf(
            SavedFlashcard("one", "task-one", "Notes", NoteDocBlock("a1", text = "A1"),
                now, 5, recallPrompt = "First question?"),
            SavedFlashcard("two", "task-one", "Notes", NoteDocBlock("a2", text = "A2"),
                now + 1, 6, recallPrompt = "Second question?")))
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Deck", cards, null, false, onBack = {},
                    onArchive = { card, archived ->
                        cards = cards.map { if (it.id == card.id) it.copy(isArchived = archived) else it }
                    },
                    onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_deck_grid").performScrollToKey("two")
        compose.onNodeWithTag("flashcard_deck_tile_two").performClick()
        compose.onNodeWithTag("flashcard_question").assertTextContains("Second question?")
        compose.onNodeWithTag("flashcard_archive").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("First question?")
        compose.onNodeWithTag("flashcard_archive_undo").assertExists()
        compose.onNodeWithTag("flashcard_back_to_deck").performClick()
        compose.onNodeWithTag("flashcard_group_archived").performScrollTo().performClick()
        compose.onNodeWithTag("flashcard_deck_grid").performScrollToKey("two")
        compose.onNodeWithTag("flashcard_deck_tile_two").assertExists()
        compose.onNodeWithTag("flashcard_deck_tile_one").assertDoesNotExist()
    }

    @Test fun flipButtonAndRatingsActOnTheCardNowShowing() {
        val now = System.currentTimeMillis()
        var rated: Pair<String, FlashcardRating>? = null
        val cards = (1..3).map { n ->
            SavedFlashcard("c$n", "task-one", "Notes", NoteDocBlock("a$n", text = "Answer $n"),
                now + n, n, recallPrompt = "Question $n?")
        }
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Deck", cards, null, false, onBack = {},
                    onReview = { card, rating, saved -> rated = card.id to rating; saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_question").assertTextContains("Question 3?")
        compose.onNodeWithTag("flashcard_reveal").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_answer").assertTextContains("Answer 3")
        compose.onNodeWithTag("flashcard_rate_easy").performClick()
        compose.waitForIdle()
        assertEquals("c3" to FlashcardRating.EASY, rated)
        compose.onNodeWithTag("flashcard_feedback").assertDoesNotExist()
    }

    @Test fun cardOptionsStripEditsOpensAndSortsTheCardShowing() {
        val now = System.currentTimeMillis()
        var edited: String? = null
        var opened: String? = null
        val cards = (1..2).map { n ->
            SavedFlashcard("c$n", "task-one", "Notes", NoteDocBlock("a$n", text = "Answer $n"),
                now + n, n, recallPrompt = "Question $n?")
        }
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Deck", cards, null, false, onBack = {},
                    onOpenNote = { opened = it.id }, onEdit = { edited = it.id },
                    onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_options_strip").assertDoesNotExist()
        compose.onNodeWithTag("flashcard_options").performClick()
        compose.onNodeWithTag("flashcard_options_strip").assertExists()
        compose.onNodeWithTag("flashcard_option_copy_question").performClick()
        compose.onNodeWithTag("flashcard_option_copy_question").assertTextContains("Copied")
        compose.onNodeWithTag("flashcard_option_sort_newest").performScrollTo().performClick()
        compose.onNodeWithTag("flashcard_option_sort_newest").assertIsSelected()
        compose.onNodeWithTag("flashcard_question").assertTextContains("Question 2?")
        compose.onNodeWithTag("flashcard_option_edit").performScrollTo().performClick()
        assertEquals("c2", edited)
        compose.onNodeWithTag("flashcard_options_strip").assertDoesNotExist()
        compose.onNodeWithTag("flashcard_options").performClick()
        compose.onNodeWithTag("flashcard_option_open_note").performScrollTo().performClick()
        assertEquals("c2", opened)
    }

    @Test fun deckSortOpensAsAStripAndSelects() {
        val now = System.currentTimeMillis()
        val cards = (1..2).map { n ->
            SavedFlashcard("c$n", "task-one", "Notes", NoteDocBlock("a$n", text = "Answer $n"),
                now + n, n, recallPrompt = "Question $n?")
        }
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Deck", cards, null, false, onBack = {}, onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_sort_strip").assertDoesNotExist()
        compose.onNodeWithTag("flashcard_sort").performClick()
        compose.onNodeWithTag("flashcard_sort_strip").assertExists()
        compose.onNodeWithTag("flashcard_option_sort_scheduled").assertIsSelected()
        compose.onNodeWithTag("flashcard_option_sort_newest").performScrollTo().performClick()
        compose.onNodeWithTag("flashcard_option_sort_newest").assertIsSelected()
        compose.onNodeWithTag("flashcard_options_close").performClick()
        compose.onNodeWithTag("flashcard_sort_strip").assertDoesNotExist()
    }

    @Test fun deckSearchStarAndShuffleWorkFromTheGallery() {
        val now = System.currentTimeMillis()
        var starred: String? = null
        val cards = listOf(
            SavedFlashcard("one", "task-one", "Notes", NoteDocBlock("a1", text = "A1"),
                now, 5, recallPrompt = "Kotlin val?"),
            SavedFlashcard("two", "task-one", "Notes", NoteDocBlock("a2", text = "A2"),
                now + 1, 6, recallPrompt = "Lambda syntax?"))
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Deck", cards, null, false, onBack = {},
                    onStar = { starred = it.id }, onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_deck_grid").performScrollToKey("two")
        compose.onNodeWithTag("flashcard_tile_star_two").performClick()
        assertEquals("two", starred)
        compose.onNodeWithTag("flashcard_question").assertDoesNotExist()
        compose.onNodeWithTag("flashcard_search_toggle").performClick()
        compose.onNodeWithTag("flashcard_search").performTextInput("lambda")
        compose.onNodeWithTag("flashcard_deck_tile_one").assertDoesNotExist()
        compose.onNodeWithTag("flashcard_deck_grid").performScrollToKey("two")
        compose.onNodeWithTag("flashcard_deck_tile_two").assertExists()
        compose.onNodeWithTag("flashcard_deck_grid").performScrollToKey("hero")
        compose.onNodeWithTag("flashcard_shuffle").performClick()
        compose.onNodeWithTag("flashcard_question").assertTextContains("Lambda syntax?")
    }

    @Test fun richFacesShowTheNoteBlocksThemselves() {
        val card = SavedFlashcard("rich", "task-one", "Notes",
            NoteDocBlock("summary", text = "val x = 1"),
            System.currentTimeMillis(), 1, recallPrompt = "Kotlin values",
            frontBlocks = listOf(NoteDocBlock("f", NoteDocBlockType.HEADING_1, "Kotlin values")),
            backBlocks = listOf(NoteDocBlock("b", NoteDocBlockType.CODE, "val x = 1")))
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Pixel 8 Validation", listOf(card), null, false,
                    onBack = {}, onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_question").assertExists()
        compose.onNodeWithText("Kotlin values", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("flashcard_reveal").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_answer").assertExists()
        compose.onNodeWithText("val x = 1", substring = true, useUnmergedTree = true).assertExists()
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
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_reveal").performClick()
        compose.onNodeWithTag("flashcard_answer_scroll").assert(hasScrollAction())
    }

    @Test fun cardDesignSheetChangesTheDeckStyleAndPracticeStillFlips() {
        val card = SavedFlashcard("one", "task-one", "Notes",
            NoteDocBlock("answer", text = "Saved answer"),
            System.currentTimeMillis(), 5, recallPrompt = "What is the answer?")
        var style by mutableStateOf(FlashcardDeckStyle())
        compose.setContent {
            MaterialTheme {
                FlashcardStudyScreen("Biology", listOf(card), null, false, onBack = {},
                    style = style, onStyle = { style = it }, onReview = { _, _, saved -> saved() })
            }
        }
        compose.onNodeWithTag("flashcard_style_button").performClick()
        compose.onNodeWithTag("flashcard_style_sheet").assertExists()
        // The sheet may still be sliding up, so options are chosen through their click action.
        fun choose(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        choose("flashcard_design_chalkboard")
        compose.onNodeWithTag("flashcard_design_chalkboard").assertIsSelected()
        choose("flashcard_colormode_gradient")
        choose("flashcard_swatch2_3")
        choose("flashcard_typeface_handwritten")
        choose("flashcard_align_center")
        assertEquals(FlashcardDesign.CHALKBOARD, style.design)
        assertEquals(FlashcardColorMode.GRADIENT, style.colorMode)
        assertEquals(FlashcardTypeface.HANDWRITTEN, style.typeface)
        assertEquals(true, style.centered)
        choose("flashcard_style_reset")
        assertEquals(FlashcardDeckStyle(), style)
        style = style.copy(design = FlashcardDesign.CHALKBOARD)
        choose("flashcard_style_close")
        compose.waitForIdle()
        compose.onNodeWithTag("flashcard_group_new").performClick()
        compose.onNodeWithTag("flashcard_start").performClick()
        compose.onNodeWithTag("flashcard_question").assertTextContains("What is the answer?")
        compose.onNodeWithTag("flashcard_reveal").performClick()
        compose.onNodeWithTag("flashcard_answer").assertExists()
    }
}
