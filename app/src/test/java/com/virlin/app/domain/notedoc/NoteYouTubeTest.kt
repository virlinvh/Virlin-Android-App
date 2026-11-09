package com.virlin.app.domain.notedoc

import com.virlin.app.domain.notedoc.rich.RichNoteAdapter
import com.virlin.app.ui.notes.clipScript
import com.virlin.app.ui.notes.embedUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTube links and clip times, and video blocks surviving storage in both note formats. */
class NoteYouTubeTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun everyCommonLinkShapeGivesTheVideoAndItsStart() {
        assertEquals(NoteYouTube.Link(id), NoteYouTube.parse("https://www.youtube.com/watch?v=$id"))
        assertEquals(NoteYouTube.Link(id, 42), NoteYouTube.parse("https://youtu.be/$id?si=abc&t=42"))
        assertEquals(NoteYouTube.Link(id, 90), NoteYouTube.parse("https://m.youtube.com/watch?v=$id&t=1m30s"))
        assertEquals(NoteYouTube.Link(id), NoteYouTube.parse("https://www.youtube.com/shorts/$id"))
        assertEquals(NoteYouTube.Link(id), NoteYouTube.parse("https://youtube.com/live/$id?feature=share"))
        assertEquals(NoteYouTube.Link(id, 30), NoteYouTube.parse("https://www.youtube-nocookie.com/embed/$id?start=30"))
        assertEquals(NoteYouTube.Link(id), NoteYouTube.parse("youtube.com/watch?v=$id"))
        assertEquals(NoteYouTube.Link(id), NoteYouTube.parse("Watch this: https://youtu.be/$id."))
        assertEquals(NoteYouTube.Link(id), NoteYouTube.parse(id))
    }

    @Test fun otherLinksAndBrokenIdsAreNotVideos() {
        assertNull(NoteYouTube.parse(""))
        assertNull(NoteYouTube.parse("https://vimeo.com/123456"))
        assertNull(NoteYouTube.parse("https://www.youtube.com/@channel"))
        assertNull(NoteYouTube.parse("https://www.youtube.com/watch?v=bad\"id<script>"))
        assertNull(NoteYouTube.parse("https://evil.example/watch?v=$id"))
    }

    @Test fun clipTimesReadAndPrint() {
        assertEquals(90, NoteYouTube.parseTime("1:30"))
        assertEquals(3723, NoteYouTube.parseTime("1:02:03"))
        assertEquals(75, NoteYouTube.parseTime("75"))
        assertEquals(3723, NoteYouTube.parseTime("1h2m3s"))
        assertEquals(120, NoteYouTube.parseTime("2m"))
        assertNull(NoteYouTube.parseTime("1:75"))
        assertNull(NoteYouTube.parseTime("abc"))
        assertNull(NoteYouTube.parseTime(""))
        assertEquals("0:05", NoteYouTube.formatTime(5))
        assertEquals("1:02:03", NoteYouTube.formatTime(3723))
        assertEquals("1:30 – 2:45", NoteYouTube.rangeLabel(NoteDocVideo(id, 90, 165)))
        assertEquals("From 1:30", NoteYouTube.rangeLabel(NoteDocVideo(id, 90)))
    }

    @Test fun onlySaneClipsAreValid() {
        assertTrue(NoteDocVideo(id, 0, null).isValid)
        assertTrue(NoteDocVideo(id, 10, 20).isValid)
        assertFalse(NoteDocVideo(id, 20, 20).isValid)
        assertFalse(NoteDocVideo(id, -1).isValid)
        assertFalse(NoteDocVideo("x\"y", 0).isValid)
    }

    @Test fun thePlayerUrlCarriesTheClipAndTheScriptItsBounds() {
        val url = embedUrl(NoteDocVideo(id, 90, 165))
        assertEquals("https://www.youtube.com/embed/$id?playsinline=1&controls=0&rel=0&iv_load_policy=3&autoplay=1&start=90&end=165", url)
        assertTrue(clipScript(NoteDocVideo(id, 90, 165)).contains("var S = 90, E = 165"))
        assertTrue(clipScript(NoteDocVideo(id, 90)).contains("var S = 90, E = -1"))
    }

    @Test fun clipTimesAreTypedAsDigitsFilledFromTheRight() {
        assertEquals("130", NoteYouTube.clockDigits("1:30"))
        assertEquals("130", NoteYouTube.clockDigits("0130x"))
        assertEquals("123456", NoteYouTube.clockDigits("1234567"))
        assertEquals("", NoteYouTube.formatClockDigits(""))
        assertEquals("0:05", NoteYouTube.formatClockDigits("5"))
        assertEquals("1:30", NoteYouTube.formatClockDigits("130"))
        assertEquals("10:05", NoteYouTube.formatClockDigits("1005"))
        assertEquals("1:02:30", NoteYouTube.formatClockDigits("10230"))
        assertEquals(90, NoteYouTube.clockDigitsSeconds("130"))
        assertEquals(90, NoteYouTube.clockDigitsSeconds("90"))
        assertEquals(3750, NoteYouTube.clockDigitsSeconds("10230"))
        assertEquals("130", NoteYouTube.secondsToClockDigits(90))
        assertEquals("", NoteYouTube.secondsToClockDigits(0))
    }

    @Test fun thePlayersStatusIsReadSafely() {
        assertEquals(com.virlin.app.ui.notes.NoteVideoStatus(1, 12.5, 300.0, false),
            com.virlin.app.ui.notes.parseVideoStatus("\"1,12.5,300,0\""))
        assertTrue(com.virlin.app.ui.notes.parseVideoStatus("\"2,4,0,1\"")!!.ad)
        assertEquals(null, com.virlin.app.ui.notes.parseVideoStatus("\"\""))
        assertEquals(null, com.virlin.app.ui.notes.parseVideoStatus("null"))
        assertEquals(null, com.virlin.app.ui.notes.parseVideoStatus("\"x,1,2,0\""))
    }

    private val video = NoteDocVideo(id, 90, 165, "A \"quoted\" title", "Channel")
    private val blocks = listOf(
        NoteDocBlock("p1", NoteDocBlockType.PARAGRAPH, "Before"),
        NoteDocBlock("v1", NoteDocBlockType.VIDEO, video = video, indent = 1),
        NoteDocBlock("p2", NoteDocBlockType.PARAGRAPH, "After"),
    )

    @Test fun videoBlocksSurviveTheBlockCodec() {
        assertEquals(blocks, VirlinNoteCodec.decode(VirlinNoteCodec.encode(blocks)))
        val damaged = VirlinNoteCodec.encode(blocks).replace("\"id\":\"$id\"", "\"id\":\"no good\"")
        assertNull("a damaged clip is dropped, the block kept", VirlinNoteCodec.decode(damaged)[1].video)
        assertEquals(NoteDocBlockType.VIDEO, VirlinNoteCodec.decode(damaged)[1].type)
    }

    @Test fun videoBlocksSurviveTheRichTreeAndOnlyTheirClipCanChange() {
        val tree = RichNoteAdapter.fromLegacy(blocks)
        assertEquals(blocks, RichNoteAdapter.project(tree))
        val retimed = blocks.map { if (it.id == "v1") it.copy(video = video.copy(startSeconds = 0, endSeconds = null, title = null)) else it }
        val saved = RichNoteAdapter.applyBlockEdits(tree, retimed)!!
        assertEquals(retimed, RichNoteAdapter.project(saved))
        assertFalse(saved.contains("\"title\""))
        assertNull("moving it is refused", RichNoteAdapter.applyBlockEdits(tree, blocks.map { if (it.id == "v1") it.copy(indent = 0) else it }))
        assertNull("a broken clip is refused", RichNoteAdapter.applyBlockEdits(tree,
            blocks.map { if (it.id == "v1") it.copy(video = video.copy(endSeconds = 10)) else it }))
    }

    @Test fun aVideoAddedToARichNoteLandsWhereItWasPlaced() {
        val tree = RichNoteAdapter.fromLegacy(listOf(blocks[0], blocks[2]))
        val saved = RichNoteAdapter.applyBlockEdits(tree, blocks)!!
        assertEquals(blocks.map { it.id }, RichNoteAdapter.project(saved).map { it.id })
        assertEquals(video, RichNoteAdapter.project(saved)[1].video)
    }

    @Test fun textViewsShowTheVideoAsALink() {
        assertEquals("[Video: A \"quoted\" title · 1:30 – 2:45] https://www.youtube.com/watch?v=$id&t=90s",
            NoteYouTube.plainText(video))
        assertEquals(0, NoteWordCount.of(listOf(blocks[1])).words)
    }
}
