package com.virlin.app.notes

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.data.flashcard.FlashcardRating
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cardsFollowTheirNoteToANewOwnerKey() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val storage = folder.newFolder("rekey")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = storage
        }
        val content = NoteDocBlock("c", text = "Remember this")
        val moved = FlashcardStore.add(context, "task-a#note-1", "Plan", content)
        val other = FlashcardStore.add(context, "task-a", "Other", content)
        assertEquals(1, FlashcardStore.rekey(context, "task-a#note-1", "task-b#note-2"))
        assertEquals(listOf(moved.copy(ownerKey = "task-b#note-2")), FlashcardStore.list(context, "task-b#note-2"))
        assertEquals(emptyList<Any>(), FlashcardStore.list(context, "task-a#note-1"))
        assertEquals(listOf(other), FlashcardStore.list(context, "task-a"))
    }

    @Test fun savesFormattedCardsAtomicallyAndFiltersByOwner() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val storage = folder.newFolder("cards")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = storage
        }
        val content = NoteDocBlock("card-content", text = "Important text",
            runs = listOf(NoteDocRun(0, 9, setOf(NoteDocMark.BOLD))))
        val first = FlashcardStore.add(context, "task-one", "Research notes", content)
        val second = FlashcardStore.add(context, "task-two", "Other notes", content,
            recallPrompt = "What matters?", difficulty = "HARD", intervalDays = 3,
            frontContent = NoteDocBlock("front", text = "What matters?",
                runs = listOf(NoteDocRun(0, 4, setOf(NoteDocMark.ITALIC)))))
        assertNotEquals(first.colorIndex, second.colorIndex)
        assertEquals(listOf("task-one", "task-two"),
            org.json.JSONObject(File(storage, "flashcards-v1.json").readText())
                .getJSONArray("cards").let { rows ->
                    (0 until rows.length()).map { rows.getJSONObject(it).getString("ownerKey") }
                })
        assertEquals(listOf(first), FlashcardStore.list(context, "task-one"))
        assertEquals(setOf(first, second), FlashcardStore.list(context,
            setOf("task-one", "task-two")).toSet())
        assertEquals(content.runs, FlashcardStore.list(context, "task-one").single().content.runs)
        assertEquals("What matters?", FlashcardStore.list(context, "task-two").single().recallPrompt)
        assertEquals(second.frontContent,
            FlashcardStore.list(context, "task-two").single().frontContent)
        assertEquals("HARD", FlashcardStore.list(context, "task-two").single().difficulty)
        assertEquals(3, FlashcardStore.list(context, "task-two").single().intervalDays)
    }

    @Test fun reviewPersistsScheduleWithoutChangingOtherCards() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val storage = folder.newFolder("review")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = storage
        }
        val content = NoteDocBlock("answer", text = "Remember this")
        val reviewed = FlashcardStore.add(context, "task-a", "Notes", content)
        val other = FlashcardStore.add(context, "task-b", "Notes", content)
        val now = 1_800_000_000_000L
        val first = FlashcardStore.review(context, reviewed.id, FlashcardRating.AGAIN, now)
        assertEquals(now + 600_000L, first.nextReviewAt)
        assertEquals(1, first.reviewCount)
        val second = FlashcardStore.review(context, reviewed.id, FlashcardRating.GOOD, now + 600_000L)
        assertEquals(1, second.intervalDays)
        assertEquals(now + 600_000L + 86_400_000L, second.nextReviewAt)
        assertEquals(2, FlashcardStore.list(context, "task-a").single().reviewCount)
        assertEquals(other, FlashcardStore.list(context, "task-b").single())
        val hard = FlashcardStore.review(context, other.id, FlashcardRating.HARD, now)
        assertEquals(now + 12 * 3_600_000L, hard.nextReviewAt)
        assertEquals(1, hard.reviewCount)
        val starred = FlashcardStore.setStarred(context, reviewed.id, true)
        assertEquals(true, starred.isStarred)
        assertEquals(true, FlashcardStore.list(context, "task-a").single().isStarred)
        assertEquals(false, FlashcardStore.list(context, "task-b").single().isStarred)
    }

    @Test fun editingKeepsScheduleStarAndSourceBlock() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val storage = folder.newFolder("edit")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = storage
        }
        val card = FlashcardStore.add(context, "task-a", "Notes", NoteDocBlock("a", text = "Old answer"),
            recallPrompt = "Old?", sourceBlockId = "block-7")
        val reviewed = FlashcardStore.review(context, card.id, FlashcardRating.GOOD, 1_800_000_000_000L)
        FlashcardStore.setStarred(context, card.id, true)
        val back = listOf(NoteDocBlock("b", text = "New answer"))
        val updated = FlashcardStore.update(context, card.id, NoteDocBlock("a", text = "New answer"),
            "New?", "EASY", NoteDocBlock("f", text = "New?"), listOf(NoteDocBlock("f", text = "New?")), back)
        val stored = FlashcardStore.list(context, "task-a").single()
        assertEquals(updated, stored)
        assertEquals("New?", stored.recallPrompt)
        assertEquals(back, stored.backBlocks)
        assertEquals("EASY", stored.difficulty)
        assertEquals(reviewed.nextReviewAt, stored.nextReviewAt)
        assertEquals(1, stored.reviewCount)
        assertEquals(true, stored.isStarred)
        assertEquals("block-7", stored.sourceBlockId)
    }

    @Test fun frontCardStyleDefaultsOnForOlderCardsAndSurvivesEdits() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val storage = folder.newFolder("style")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = storage
        }
        val card = FlashcardStore.add(context, "task-a", "Notes", NoteDocBlock("a", text = "Answer"),
            recallPrompt = "Q?", frontCardStyle = false)
        assertEquals(false, FlashcardStore.list(context, "task-a").single().frontCardStyle)
        val file = File(storage, "flashcards-v1.json")
        val json = org.json.JSONObject(file.readText())
        json.getJSONArray("cards").getJSONObject(0).remove("frontCardStyle")
        file.writeText(json.toString())
        assertEquals(true, FlashcardStore.list(context, "task-a").single().frontCardStyle)
        FlashcardStore.update(context, card.id, NoteDocBlock("a", text = "Answer"), "Q?", "NORMAL",
            null, listOf(NoteDocBlock("f", text = "Q?")), null, frontCardStyle = false)
        assertEquals(false, FlashcardStore.list(context, "task-a").single().frontCardStyle)
    }
}
