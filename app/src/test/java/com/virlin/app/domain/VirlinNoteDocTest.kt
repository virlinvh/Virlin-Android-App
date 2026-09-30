package com.virlin.app.domain

import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteRuns
import com.virlin.app.domain.notedoc.VirlinNoteCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Notes page's own document model. Pure logic only: selection formatting, run survival across
 * edits and round-tripping. No Compose, no database, no clock.
 */
class VirlinNoteDocTest {

    // ---- selection formatting -------------------------------------------------------------

    @Test fun bold_appliesToSelectionRangeOnly() {
        val runs = NoteRuns.applyMark(emptyList(), 11, 0, 5, NoteDocMark.BOLD, add = true)
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(5, runs[0].end)
        assertEquals(setOf(NoteDocMark.BOLD), runs[0].marks)
        assertFalse(NoteRuns.rangeHasMark(runs, 5, 11, NoteDocMark.BOLD))
    }

    @Test fun bold_thenItalic_onSameRangeAccumulates() {
        var runs = NoteRuns.applyMark(emptyList(), 11, 0, 5, NoteDocMark.BOLD, add = true)
        runs = NoteRuns.applyMark(runs, 11, 0, 5, NoteDocMark.ITALIC, add = true)
        assertEquals(1, runs.size)
        assertEquals(setOf(NoteDocMark.BOLD, NoteDocMark.ITALIC), runs[0].marks)
    }

    @Test fun unbold_removesOnlyThatMark() {
        var runs = NoteRuns.applyMark(emptyList(), 11, 0, 5, NoteDocMark.BOLD, add = true)
        runs = NoteRuns.applyMark(runs, 11, 0, 5, NoteDocMark.ITALIC, add = true)
        runs = NoteRuns.applyMark(runs, 11, 0, 5, NoteDocMark.BOLD, add = false)
        assertEquals(setOf(NoteDocMark.ITALIC), runs.single().marks)
    }

    @Test fun unbold_partialRange_splitsTheRun() {
        var runs = NoteRuns.applyMark(emptyList(), 10, 0, 10, NoteDocMark.BOLD, add = true)
        runs = NoteRuns.applyMark(runs, 10, 3, 6, NoteDocMark.BOLD, add = false)
        assertEquals(2, runs.size)
        assertEquals(0 to 3, runs[0].start to runs[0].end)
        assertEquals(6 to 10, runs[1].start to runs[1].end)
    }

    @Test fun overlappingBold_mergesIntoOneRun() {
        var runs = NoteRuns.applyMark(emptyList(), 10, 0, 5, NoteDocMark.BOLD, add = true)
        runs = NoteRuns.applyMark(runs, 10, 3, 8, NoteDocMark.BOLD, add = true)
        assertEquals(1, runs.size)
        assertEquals(0 to 8, runs[0].start to runs[0].end)
    }

    @Test fun rangeHasMark_falseWhenOnlyPartiallyMarked() {
        val runs = NoteRuns.applyMark(emptyList(), 10, 0, 4, NoteDocMark.BOLD, add = true)
        assertTrue(NoteRuns.rangeHasMark(runs, 0, 4, NoteDocMark.BOLD))
        assertTrue(NoteRuns.rangeHasMark(runs, 1, 3, NoteDocMark.BOLD))
        assertFalse(NoteRuns.rangeHasMark(runs, 0, 6, NoteDocMark.BOLD))
    }

    @Test fun collapsedRange_changesNothing() {
        val runs = NoteRuns.applyMark(emptyList(), 10, 4, 4, NoteDocMark.BOLD, add = true)
        assertTrue(runs.isEmpty())
    }

    @Test fun attribute_appliesColourAcrossGapsAndRuns() {
        var runs = NoteRuns.applyMark(emptyList(), 10, 2, 4, NoteDocMark.BOLD, add = true)
        runs = NoteRuns.applyAttribute(runs, 10, 0, 10) { it.copy(color = 0xFFFF0000L) }
        assertTrue(runs.all { it.color == 0xFFFF0000L })
        // Bold must survive the colour pass.
        assertTrue(NoteRuns.rangeHasMark(runs, 2, 4, NoteDocMark.BOLD))
    }

    @Test fun link_storedAsRunAttribute() {
        val runs = NoteRuns.applyAttribute(emptyList(), 12, 0, 5) {
            it.copy(linkUrl = "https://example.com")
        }
        assertEquals("https://example.com", runs.single().linkUrl)
    }

    // ---- edit survival --------------------------------------------------------------------

    @Test fun diff_findsInsertion() {
        assertEquals(Triple(5, 0, 1), NoteRuns.diff("hello", "helloX"))
    }

    @Test fun diff_findsDeletion() {
        assertEquals(Triple(2, 1, 0), NoteRuns.diff("abcd", "abd"))
    }

    @Test fun diff_findsReplacement() {
        assertEquals(Triple(1, 2, 3), NoteRuns.diff("axyd", "aQRSd"))
    }

    @Test fun diff_identicalIsNoEdit() {
        assertEquals(Triple(0, 0, 0), NoteRuns.diff("same", "same"))
    }

    @Test fun typingAfterBoldWord_extendsTheBoldRun() {
        // "bold" bold, caret at 4, type "X".
        val runs = listOf(NoteDocRun(0, 4, setOf(NoteDocMark.BOLD)))
        val next = NoteRuns.shiftForEdit(runs, from = 4, removed = 0, inserted = 1, newLength = 5)
        assertEquals(0 to 5, next[0].start to next[0].end)
    }

    @Test fun typingBeforeBoldWord_doesNotBoldTheNewText() {
        val runs = listOf(NoteDocRun(2, 6, setOf(NoteDocMark.BOLD)))
        val next = NoteRuns.shiftForEdit(runs, from = 0, removed = 0, inserted = 2, newLength = 8)
        assertEquals(4 to 8, next[0].start to next[0].end)
    }

    @Test fun deletionBeforeRun_shiftsItLeft() {
        val runs = listOf(NoteDocRun(5, 9, setOf(NoteDocMark.ITALIC)))
        val next = NoteRuns.shiftForEdit(runs, from = 0, removed = 3, inserted = 0, newLength = 6)
        assertEquals(2 to 6, next[0].start to next[0].end)
    }

    @Test fun deletingTheWholeRun_dropsIt() {
        val runs = listOf(NoteDocRun(2, 6, setOf(NoteDocMark.BOLD)))
        val next = NoteRuns.shiftForEdit(runs, from = 0, removed = 8, inserted = 0, newLength = 0)
        assertTrue(next.isEmpty())
    }

    @Test fun deletionInsideRun_shrinksIt() {
        val runs = listOf(NoteDocRun(0, 10, setOf(NoteDocMark.BOLD)))
        val next = NoteRuns.shiftForEdit(runs, from = 3, removed = 4, inserted = 0, newLength = 6)
        assertEquals(0 to 6, next[0].start to next[0].end)
    }

    @Test fun pasteReplacingSelection_keepsSurroundingRuns() {
        val runs = listOf(
            NoteDocRun(0, 3, setOf(NoteDocMark.BOLD)),
            NoteDocRun(7, 10, setOf(NoteDocMark.ITALIC)),
        )
        // Replace [3,7) with 5 pasted characters.
        val next = NoteRuns.shiftForEdit(runs, from = 3, removed = 4, inserted = 5, newLength = 11)
        assertEquals(setOf(NoteDocMark.BOLD), next.first().marks)
        assertEquals(0 to 3, next.first().start to next.first().end)
        assertEquals(8 to 11, next.last().start to next.last().end)
        assertEquals(setOf(NoteDocMark.ITALIC), next.last().marks)
    }

    @Test fun unicodeText_diffsByCharIndexConsistently() {
        val old = "héllo — wörld"
        val new = "héllo — wörld!"
        val (from, removed, inserted) = NoteRuns.diff(old, new)
        assertEquals(old.length, from)
        assertEquals(0, removed)
        assertEquals(1, inserted)
    }

    // ---- codec ---------------------------------------------------------------------------

    @Test fun codec_roundTripsEveryBlockField() {
        val blocks = listOf(
            NoteDocBlock(
                id = "a", type = NoteDocBlockType.HEADING_2, text = "Title",
                runs = listOf(
                    NoteDocRun(
                        0, 5, setOf(NoteDocMark.BOLD, NoteDocMark.UNDERLINE),
                        color = 0xFF112233L, highlight = 0xFFFFEE00L,
                        fontFamily = "Serif", fontSizeSp = 22, linkUrl = "https://a.test/x?y=1",
                    ),
                ),
                align = NoteDocAlign.CENTER, indent = 2, checked = true,
                lineSpacing = 1.5f, promptTitle = "Ask",
            ),
            NoteDocBlock(id = "b", type = NoteDocBlockType.DIVIDER),
            NoteDocBlock(
                id = "c", type = NoteDocBlockType.TABLE,
                table = NoteDocTable(2, 2, listOf("r1c1", "r1c2", "r2c1", "r2c2")),
            ),
        )
        val decoded = VirlinNoteCodec.decode(VirlinNoteCodec.encode(blocks))
        assertEquals(blocks, decoded)
    }

    @Test fun codec_escapesQuotesNewlinesAndUnicode() {
        val text = "He said \"hi\"\nLine2\tTabbed — ünïcode \\ back"
        val blocks = listOf(NoteDocBlock(id = "x", text = text))
        assertEquals(text, VirlinNoteCodec.decode(VirlinNoteCodec.encode(blocks)).single().text)
    }

    @Test fun codec_writesItsOwnVersion() {
        assertTrue(VirlinNoteCodec.encode(emptyList()).contains("\"v\":${VirlinNoteCodec.VERSION}"))
    }

    @Test fun codec_toleratesGarbageAndUnknownNames() {
        assertTrue(VirlinNoteCodec.decode("not json at all").isEmpty())
        assertTrue(VirlinNoteCodec.decode("").isEmpty())
        val unknown = """{"v":99,"blocks":[{"id":"z","type":"FUTURE_TYPE","text":"t","mystery":5}]}"""
        val b = VirlinNoteCodec.decode(unknown).single()
        assertEquals(NoteDocBlockType.PARAGRAPH, b.type)
        assertEquals("t", b.text)
    }

    @Test fun codec_dropsRunsBeyondTextLength() {
        val json = """{"v":1,"blocks":[{"id":"z","text":"ab","runs":[{"s":0,"e":99,"m":["BOLD"]}]}]}"""
        val b = VirlinNoteCodec.decode(json).single()
        assertTrue(b.runs.all { it.end <= b.text.length })
    }

    @Test fun codec_emptyDocumentRoundTrips() {
        assertTrue(VirlinNoteCodec.decode(VirlinNoteCodec.encode(emptyList())).isEmpty())
    }
}
