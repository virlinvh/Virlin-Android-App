package com.virlin.app.notes

import com.virlin.app.data.flashcard.SavedFlashcard
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.ui.notes.FlashcardGroup
import com.virlin.app.ui.notes.FlashcardSort
import com.virlin.app.ui.notes.groupedFlashcards
import com.virlin.app.ui.notes.flashcardColor
import com.virlin.app.ui.notes.flashcardEditStart
import com.virlin.app.ui.notes.flashcardSourceBlockId
import com.virlin.app.domain.notedoc.NoteDocBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardStudyGroupingTest {
    @Test fun sourceNoteOpensAtTheCardsOwnBlock() {
        val blocks = listOf(
            NoteDocBlock("intro", text = "Introduction"),
            NoteDocBlock("toggle", NoteDocBlockType.TOGGLE, "Details", isCollapsed = true),
            NoteDocBlock("inside", text = "Mitochondria make energy", indent = 1),
            NoteDocBlock("later", text = "Ribosomes build proteins"),
        )
        val base = SavedFlashcard("c", "task-a", "Notes", NoteDocBlock("a", text = "Ribosomes build proteins"), 0L, 0)
        assertEquals("intro", flashcardSourceBlockId(base.copy(sourceBlockId = "intro"), blocks))
        // Older cards without a recorded block are found by their text.
        assertEquals("later", flashcardSourceBlockId(base, blocks))
        // A block folded inside a collapsed toggle opens at the toggle.
        assertEquals("toggle", flashcardSourceBlockId(base.copy(
            content = NoteDocBlock("a", text = "Mitochondria make energy")), blocks))
        // A line edited after its opening still finds its block.
        assertEquals("later", flashcardSourceBlockId(base.copy(
            content = NoteDocBlock("a", text = "Ribosomes build proteins in every cell")), blocks))
        // A recorded block that was deleted falls back to the text.
        assertEquals("later", flashcardSourceBlockId(base.copy(sourceBlockId = "gone"), blocks))
        assertEquals(null, flashcardSourceBlockId(base.copy(content = NoteDocBlock("a", text = "Nothing here")), blocks))
    }

    @Test fun editStartKeepsRichFacesAndTurnsOlderCardsIntoBlocks() {
        val legacy = SavedFlashcard("c", "task-a", "Notes", NoteDocBlock("a", text = "Answer"), 0L, 0,
            recallPrompt = "Question?", difficulty = "HARD")
        val start = flashcardEditStart(legacy)
        assertEquals(listOf("Question?"), start.front.map { it.text })
        assertEquals(listOf("Answer"), start.back.map { it.text })
        assertEquals("HARD", start.difficulty)
        val rich = legacy.copy(frontBlocks = listOf(NoteDocBlock("f", NoteDocBlockType.HEADING_2, "Q")),
            backBlocks = listOf(NoteDocBlock("b", NoteDocBlockType.CODE, "x = 1")))
        assertEquals(rich.frontBlocks, flashcardEditStart(rich).front)
        assertEquals(rich.backBlocks, flashcardEditStart(rich).back)
    }

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

    @Test fun archivedCardsLeaveEveryQueueButTheArchive() {
        val now = 1_800_000_000_000L
        val base = SavedFlashcard("new", "task-a", "Notes", NoteDocBlock("answer", text = "Answer"), now, 0)
        val cards = listOf(base, base.copy(id = "gone", isArchived = true))
        listOf(FlashcardGroup.NEW, FlashcardGroup.ALL).forEach { group ->
            assertEquals(listOf("new"), groupedFlashcards(cards, group, FlashcardSort.SCHEDULED, now).map { it.id })
        }
        assertEquals(listOf("gone"), groupedFlashcards(cards, FlashcardGroup.ARCHIVED,
            FlashcardSort.SCHEDULED, now).map { it.id })
    }

    @Test fun starredFirstSortPutsStarredCardsAhead() {
        val now = 1_800_000_000_000L
        val base = SavedFlashcard("plain", "task-a", "Notes", NoteDocBlock("answer", text = "Answer"), now, 0)
        val cards = listOf(base, base.copy(id = "starred", createdAt = now + 5, isStarred = true))
        assertEquals(listOf("starred", "plain"), groupedFlashcards(cards, FlashcardGroup.ALL,
            FlashcardSort.STARRED, now).map { it.id })
    }

    @Test fun shuffledOrderHoldsStillWhenACardLeaves() {
        val now = 1_800_000_000_000L
        val base = SavedFlashcard("c0", "task-a", "Notes", NoteDocBlock("answer", text = "Answer"), now, 0)
        val cards = (0 until 12).map { base.copy(id = "c$it", createdAt = now + it) }
        val order = groupedFlashcards(cards, FlashcardGroup.ALL, FlashcardSort.SHUFFLED, now, 42).map { it.id }
        assertEquals(cards.size, order.toSet().size)
        assertTrue(order != cards.map { it.id })
        val gone = order[3]
        assertEquals(order - gone, groupedFlashcards(cards.filterNot { it.id == gone }, FlashcardGroup.ALL,
            FlashcardSort.SHUFFLED, now, 42).map { it.id })
    }

    @Test fun searchMatchesQuestionAnswerAndNoteTitle() {
        val card = SavedFlashcard("s", "task-a", "Kotlin basics", NoteDocBlock("answer", text = "Read-only value"),
            0, 0, recallPrompt = "What does val mean?")
        assertTrue(com.virlin.app.ui.notes.flashcardMatches(card, "VAL mean"))
        assertTrue(com.virlin.app.ui.notes.flashcardMatches(card, "read-only"))
        assertTrue(com.virlin.app.ui.notes.flashcardMatches(card, "basics"))
        assertTrue(com.virlin.app.ui.notes.flashcardMatches(card, "  "))
        assertTrue(!com.virlin.app.ui.notes.flashcardMatches(card, "lambda"))
    }

    @Test fun wrappedIndexLoopsBothWays() {
        assertEquals(0, com.virlin.app.ui.notes.wrappedIndex(2, 1, 3))
        assertEquals(2, com.virlin.app.ui.notes.wrappedIndex(0, -1, 3))
        assertEquals(0, com.virlin.app.ui.notes.wrappedIndex(0, 1, 0))
    }

    @Test fun tenThousandCardsHaveDistinctShadesAndNeighboursDiffer() {
        val shades = (0 until 10_000).map { flashcardColor(it).value }
        assertEquals(10_000, shades.toSet().size)
        (0 until 999).forEach { i ->
            val a = flashcardColor(i)
            val b = flashcardColor(i + 1)
            val distance = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
            assertTrue("Cards $i and ${i + 1} look alike", distance > .08f)
        }
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
