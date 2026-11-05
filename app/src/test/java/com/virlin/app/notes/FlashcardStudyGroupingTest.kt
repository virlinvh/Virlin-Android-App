package com.virlin.app.notes

import com.virlin.app.data.flashcard.SavedFlashcard
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.ui.notes.FlashcardGroup
import com.virlin.app.ui.notes.FlashcardSort
import com.virlin.app.ui.notes.groupedFlashcards
import com.virlin.app.ui.notes.flashcardColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardStudyGroupingTest {
    @Test fun newDueAndFutureAreDistinct() {
        val now = 1_800_000_000_000L
        val base = SavedFlashcard("new", "task-a", "Notes",
            NoteDocBlock("answer", text = "Answer"), now, 0)
        val cards = listOf(base, base.copy(id = "due", reviewCount = 1,
            nextReviewAt = now - 1), base.copy(id = "future", reviewCount = 1,
            nextReviewAt = now + 1))
        assertEquals(listOf("new"), groupedFlashcards(cards, FlashcardGroup.NEW,
            FlashcardSort.SCHEDULED, now).map { it.id })
        assertEquals(listOf("due"), groupedFlashcards(cards, FlashcardGroup.DUE,
            FlashcardSort.SCHEDULED, now).map { it.id })
        assertEquals(listOf("future"), groupedFlashcards(cards, FlashcardGroup.FUTURE,
            FlashcardSort.SCHEDULED, now).map { it.id })
    }

    @Test fun firstThousandCardsHaveDistinctShades() {
        val shades = (0 until 1000).map { flashcardColor(it).value }
        assertEquals(1000, shades.toSet().size)
    }

    @Test fun firstTenThousandCardColorsStayLight() {
        (0 until 10_000).forEach { index ->
            val color = flashcardColor(index)
            assertTrue("Card $index must remain pastel",
                color.red > .70f && color.green > .70f && color.blue > .70f)
        }
    }
}
