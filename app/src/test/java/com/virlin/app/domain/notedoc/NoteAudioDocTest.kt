package com.virlin.app.domain.notedoc

import com.virlin.app.domain.notedoc.rich.RichNoteAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voice recordings in notes: what counts as one, and surviving storage in both note formats. */
class NoteAudioDocTest {
    private val file = "0f8fad5b-d9cb-469f-a165-70867728950e.m4a"
    private val audio = NoteDocAudio(file, 12_345, "0123456789abcdef")
    private val blocks = listOf(
        NoteDocBlock("p1", NoteDocBlockType.PARAGRAPH, "Before"),
        NoteDocBlock("a1", NoteDocBlockType.AUDIO, audio = audio, indent = 1),
        NoteDocBlock("p2", NoteDocBlockType.PARAGRAPH, "After"),
    )

    @Test fun onlyManagedRecordingsAreValid() {
        assertTrue(audio.isValid)
        assertFalse(audio.copy(file = "../secret.m4a").isValid)
        assertFalse(audio.copy(file = "0f8fad5b-d9cb-469f-a165-70867728950e.mp3").isValid)
        assertFalse(audio.copy(durationMs = 0).isValid)
        assertFalse(audio.copy(peaks = "xyz").isValid)
        assertFalse(NoteDocBlockType.AUDIO.isTextual)
    }

    @Test fun durationsPrint() {
        assertEquals("0:00", NoteDocAudio.formatDuration(400))
        assertEquals("0:12", NoteDocAudio.formatDuration(12_345))
        assertEquals("1:02:03", NoteDocAudio.formatDuration(3_723_000))
        assertEquals("[Audio · 0:12]", NoteDocAudio.plainText(audio))
    }

    @Test fun spokenTextKeepsItsTitleInBothFormats() {
        assertNull(NoteDocAudio.titleFor("   \n "))
        assertEquals("Hello there world", NoteDocAudio.titleFor("  Hello\n\nthere   world "))
        val long = NoteDocAudio.titleFor("word ".repeat(40))!!
        assertTrue(long.length <= 80)
        assertTrue(long.endsWith("…"))
        val spoken = audio.copy(title = "Hello there")
        assertEquals("[Audio: Hello there · 0:12]", NoteDocAudio.plainText(spoken))
        val titled = blocks.map { if (it.type == NoteDocBlockType.AUDIO) it.copy(audio = spoken) else it }
        assertEquals(titled, VirlinNoteCodec.decode(VirlinNoteCodec.encode(titled)))
        assertEquals(titled, RichNoteAdapter.project(RichNoteAdapter.fromLegacy(titled)))
    }

    @Test fun peaksSqueezeToAFixedOutline() {
        val peaks = NoteDocAudio.encodePeaks(List(500) { if (it < 250) 0f else 1f })
        assertEquals(NoteDocAudio.PEAK_COUNT, peaks.length)
        assertEquals('0', peaks.first())
        assertEquals('f', peaks.last())
        assertTrue(NoteDocAudio.PEAKS.matches(NoteDocAudio.encodePeaks(List(3) { .5f })))
        assertEquals("", NoteDocAudio.encodePeaks(emptyList()))
        assertEquals(listOf(0f, 1f), NoteDocAudio.decodePeaks("0f"))
    }

    @Test fun recordingsSurviveTheBlockCodec() {
        assertEquals(blocks, VirlinNoteCodec.decode(VirlinNoteCodec.encode(blocks)))
        val damaged = VirlinNoteCodec.encode(blocks).replace(file, "../../databases/virlin.db")
        assertNull("a damaged recording is dropped, the block kept", VirlinNoteCodec.decode(damaged)[1].audio)
        assertEquals(NoteDocBlockType.AUDIO, VirlinNoteCodec.decode(damaged)[1].type)
    }

    @Test fun recordingsSurviveTheRichTreeAndAreNeverEditedInPlace() {
        val tree = RichNoteAdapter.fromLegacy(blocks)
        assertEquals(blocks, RichNoteAdapter.project(tree))
        assertNull("a different recording is refused", RichNoteAdapter.applyBlockEdits(tree,
            blocks.map { if (it.id == "a1") it.copy(audio = audio.copy(durationMs = 1)) else it }))
        assertNull("moving it is refused", RichNoteAdapter.applyBlockEdits(tree,
            blocks.map { if (it.id == "a1") it.copy(indent = 0) else it }))
        val edited = blocks.map { if (it.id == "p2") it.copy(text = "After, edited") else it }
        assertEquals(edited, RichNoteAdapter.project(RichNoteAdapter.applyBlockEdits(tree, edited)!!))
        val removed = blocks.filter { it.id != "a1" }
        assertEquals(removed, RichNoteAdapter.project(RichNoteAdapter.applyBlockEdits(tree, removed)!!))
    }

    @Test fun aRecordingAddedToARichNoteLandsWhereItWasPlaced() {
        val tree = RichNoteAdapter.fromLegacy(listOf(blocks[0], blocks[2]))
        val saved = RichNoteAdapter.applyBlockEdits(tree, blocks)!!
        assertEquals(blocks.map { it.id }, RichNoteAdapter.project(saved).map { it.id })
        assertEquals(audio, RichNoteAdapter.project(saved)[1].audio)
    }

    @Test fun recordingsHaveNoWords() {
        assertEquals(0, NoteWordCount.of(listOf(blocks[1])).words)
    }
}
