package com.virlin.app.notes

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.ui.notes.flashcardSummaryBlock
import com.virlin.app.ui.notes.selectableNoteText
import com.virlin.app.ui.notes.sliceProjectedRange
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardRichSidesTest {
    @get:Rule val folder = TemporaryFolder()

    private val note = listOf(
        NoteDocBlock("h", NoteDocBlockType.HEADING_2, "Photosynthesis",
            runs = listOf(NoteDocRun(0, 5, color = 0xFFAA0000, fontFamily = "caveat", fontSizeSp = 22))),
        NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "Light becomes sugar",
            runs = listOf(NoteDocRun(6, 13, setOf(NoteDocMark.BOLD), linkUrl = "https://example.com"))),
        NoteDocBlock("img", NoteDocBlockType.IMAGE, imagePath = "leaf.jpg"),
        NoteDocBlock("code", NoteDocBlockType.CODE, "val x = 1", indent = 2),
    )

    @Test fun wholeSelectionKeepsEveryBlockTypeRunAndMedia() {
        val all = selectableNoteText(note).text
        val sliced = sliceProjectedRange(note, 0, all.length)
        assertEquals(note.map { it.type }, sliced.map { it.type })
        assertEquals(note.map { it.text }, sliced.map { it.text })
        assertEquals(note[0].runs, sliced[0].runs)
        assertEquals(note[1].runs, sliced[1].runs)
        assertEquals("leaf.jpg", sliced[2].imagePath)
        assertEquals(2, sliced[3].indent)
    }

    @Test fun partialSelectionTrimsTextAndShiftsRuns() {
        val all = selectableNoteText(note).text
        val start = all.indexOf("becomes")
        val sliced = sliceProjectedRange(note, start, start + "becomes sugar".length)
        assertEquals(1, sliced.size)
        assertEquals(NoteDocBlockType.PARAGRAPH, sliced[0].type)
        assertEquals("becomes sugar", sliced[0].text)
        assertEquals(listOf(NoteDocRun(0, 7, setOf(NoteDocMark.BOLD), linkUrl = "https://example.com")),
            sliced[0].runs)
    }

    @Test fun mediaTouchedBySelectionIsTakenWholeAndIndentsStartAtZero() {
        val all = selectableNoteText(note).text
        val start = all.indexOf("[Image]") + 2
        val sliced = sliceProjectedRange(note, start, all.length)
        assertEquals(listOf(NoteDocBlockType.IMAGE, NoteDocBlockType.CODE), sliced.map { it.type })
        assertEquals(listOf(0, 2), sliced.map { it.indent })
        val codeOnly = sliceProjectedRange(note, all.indexOf("val x"), all.length)
        assertEquals(0, codeOnly.single().indent)
    }

    @Test fun richSidesRoundTripAndOlderCardsStillLoad() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val storage = folder.newFolder("rich")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = storage
        }
        val legacy = FlashcardStore.add(context, "task-1", "Old", NoteDocBlock("c", text = "Plain answer"),
            recallPrompt = "Plain question")
        val front = note.take(1)
        val back = note.drop(1)
        val rich = FlashcardStore.add(context, "task-1", "Rich", flashcardSummaryBlock(back),
            flashcardSummaryBlock(front).text, frontBlocks = front, backBlocks = back)
        val loaded = FlashcardStore.list(context, "task-1").associateBy { it.id }
        assertNull(loaded.getValue(legacy.id).frontBlocks)
        assertNull(loaded.getValue(legacy.id).backBlocks)
        assertEquals("Plain answer", loaded.getValue(legacy.id).content.text)
        assertEquals(front, loaded.getValue(rich.id).frontBlocks)
        assertEquals(back, loaded.getValue(rich.id).backBlocks)
        assertEquals("Photosynthesis", loaded.getValue(rich.id).recallPrompt)
    }

    @Test fun branchListIncludesCardsMadeInTheTasksOwnNotes() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val storage = folder.newFolder("branch")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = storage
        }
        val block = NoteDocBlock("c", text = "Answer")
        val inNote = FlashcardStore.add(context, "task-1#note-abc", "Note", block)
        val direct = FlashcardStore.add(context, "task-1", "Task", block)
        FlashcardStore.add(context, "task-10#note-x", "Other task", block)
        FlashcardStore.add(context, "capture-1", "Elsewhere", block)
        assertEquals(setOf(inNote.id, direct.id),
            FlashcardStore.list(context, setOf("task-1")).map { it.id }.toSet())
    }
}
