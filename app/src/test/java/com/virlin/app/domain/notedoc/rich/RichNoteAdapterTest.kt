package com.virlin.app.domain.notedoc.rich

import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocBlockType.*
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.VirlinNoteCodec
import org.junit.Assert.*
import org.junit.Test

class RichNoteAdapterTest {

    private fun b(id: String, type: NoteDocBlockType, text: String = "", indent: Int = 0, vararg runs: NoteDocRun) =
        NoteDocBlock(id, type, text, runs.toList(), indent = indent)

    private val legacy = listOf(
        b("h1", HEADING_1, "Title", 0, NoteDocRun(0, 5, setOf(NoteDocMark.BOLD))),
        b("p1", PARAGRAPH, "Plain with link and colour", 0,
            NoteDocRun(11, 15, linkUrl = "https://example.com"),
            NoteDocRun(20, 26, color = 0xFFD32F2FL, highlight = 0x80FFF59DL, fontFamily = "serif", fontSizeSp = 18)),
        b("l1", BULLET, "one"), b("l2", BULLET, "two", 1), b("l3", BULLET, "three", 2), b("l4", BULLET, "four", 1),
        b("l5", BULLET, "five"),
        b("n1", NUMBERED, "first"), b("n2", NUMBERED, "second"),
        NoteDocBlock("c1", CHECKLIST, "done", checked = true), b("c2", CHECKLIST, "open"),
        NoteDocBlock("t1", TOGGLE, "toggle", isCollapsed = true), b("t1a", PARAGRAPH, "inside", 1), b("t1b", BULLET, "in list", 1),
        b("q1", QUOTE, "quoted", 0, NoteDocRun(0, 6, setOf(NoteDocMark.ITALIC))),
        NoteDocBlock("code", CODE, "val x = 1\nprintln(x)", mathMode = false),
        NoteDocBlock("auto-eq", CODE, "E = mc^2"),
        NoteDocBlock("auto-code", CODE, "npm install"),
        NoteDocBlock("math", CODE, "\\frac{a}{b}", mathMode = true),
        NoteDocBlock("pr", PROMPT, "Summarise this", promptTitle = "System"),
        NoteDocBlock("tb", TABLE, table = NoteDocTable(2, 2, listOf("A", "B", "1", "2"))),
        NoteDocBlock("img", IMAGE, imagePath = "0f8fad5b-d9cb-469f-a165-70867728950e"),
        NoteDocBlock("hr", DIVIDER),
        NoteDocBlock("p2", PARAGRAPH, "centred\nsecond line", align = NoteDocAlign.CENTER, lineSpacing = 1.5f),
        b("p3", PARAGRAPH, "indented", 2),
    )

    @Test fun legacyBlocksSurviveTheRichTreeUnchanged() {
        val tree = RichNoteAdapter.fromLegacy(legacy)
        assertEquals(legacy, RichNoteAdapter.project(tree))
    }

    @Test fun legacyMathIsDetectedOnlyWhenUnmarkedAndEquationLike() {
        val tree = RichJson.parse(RichNoteAdapter.fromLegacy(legacy)) as Map<*, *>
        val types = (tree["content"] as List<*>).map { (it as Map<*, *>)["type"] }
        assertEquals(2, types.count { it == "mathBlock" })
        assertEquals(2, types.count { it == "codeBlock" })
    }

    @Test fun headingLevelsBeyondThreeAreKeptInTheTree() {
        val tree = """{"type":"doc","content":[{"type":"heading","attrs":{"id":"h","level":5},"content":[{"type":"text","text":"Deep"}]}]}"""
        val block = RichNoteAdapter.project(tree).single()
        assertEquals(HEADING_3, block.type)
        val edited = RichNoteAdapter.applyBlockEdits(tree, listOf(block.copy(text = "Deeper")))!!
        assertTrue(edited.contains("\"level\":5"))
        assertTrue(edited.contains("Deeper"))
    }

    @Test fun inlineMathProjectsWithDelimitersAndSurvivesTextEdits() {
        val tree = """{"type":"doc","content":[{"type":"paragraph","attrs":{"id":"p"},"content":[
            {"type":"text","text":"Area "},{"type":"mathInline","attrs":{"latex":"\\pi r^2"}},{"type":"text","text":" units"}]}]}"""
        val block = RichNoteAdapter.project(tree).single()
        assertEquals("Area \\(\\pi r^2\\) units", block.text)
        val edited = RichNoteAdapter.applyBlockEdits(tree, listOf(block.copy(text = "Total area \\(\\pi r^2\\) units")))!!
        val content = ((RichJson.parse(edited) as Map<*, *>)["content"] as List<*>).single() as Map<*, *>
        val inline = content["content"] as List<*>
        assertEquals(listOf("text", "mathInline", "text"), inline.map { (it as Map<*, *>)["type"] })
        // A \( \) typed on desktop that did not come from math stays literal text.
        val literal = RichNoteAdapter.applyBlockEdits(tree, listOf(block.copy(text = "\\(x\\) only")))!!
        assertFalse(literal.contains("mathInline"))
    }

    @Test fun desktopEditsPatchOnlyTheirBlockAndKeepNestedLists() {
        val tree = RichNoteAdapter.fromLegacy(legacy)
        val projected = RichNoteAdapter.project(tree)
        val edited = projected.map { if (it.id == "l3") it.copy(text = "THREE") else it }
        val merged = RichNoteAdapter.applyBlockEdits(tree, edited)!!
        assertEquals(edited, RichNoteAdapter.project(merged))
    }

    @Test fun desktopCanInsertDeleteAndRetypeTopLevelBlocks() {
        val tree = RichNoteAdapter.fromLegacy(legacy)
        val projected = RichNoteAdapter.project(tree).toMutableList()
        projected.removeAll { it.id == "n2" }
        val at = projected.indexOfFirst { it.id == "p1" } + 1
        projected.add(at, b("new", PARAGRAPH, "inserted"))
        projected.replaceAll { if (it.id == "p3") it.copy(type = HEADING_2) else it }
        val merged = RichNoteAdapter.applyBlockEdits(tree, projected)!!
        assertEquals(projected, RichNoteAdapter.project(merged))
    }

    @Test fun structuralEditsTheTreeCannotExpressAreRefused() {
        val tree = RichNoteAdapter.fromLegacy(legacy)
        val projected = RichNoteAdapter.project(tree)
        val resizedTable = projected.map { if (it.id == "tb") it.copy(table = NoteDocTable.blank(3, 2)) else it }
        assertNull(RichNoteAdapter.applyBlockEdits(tree, resizedTable))
        val orphan = projected.filterNot { it.id == "t1" }
        assertNull(RichNoteAdapter.applyBlockEdits(tree, orphan))
        val listToParagraph = projected.map { if (it.id == "l2") it.copy(type = PARAGRAPH) else it }
        assertNull(RichNoteAdapter.applyBlockEdits(tree, listToParagraph))
    }

    @Test fun tableCellEditsAndChecklistTogglesMerge() {
        val tree = RichNoteAdapter.fromLegacy(legacy)
        val projected = RichNoteAdapter.project(tree)
        val edited = projected.map {
            when (it.id) {
                "tb" -> it.copy(table = it.table!!.withCell(1, 1, "22"))
                "c2" -> it.copy(checked = true)
                else -> it
            }
        }
        assertEquals(edited, RichNoteAdapter.project(RichNoteAdapter.applyBlockEdits(tree, edited)!!))
    }

    @Test fun formatRoundTripsV2AndKeepsAV1ProjectionForOlderReaders() {
        val tree = RichNoteAdapter.fromLegacy(legacy)
        val stored = RichNoteFormat.encode(emptyList(), tree)
        val decoded = RichNoteFormat.decode(stored) as RichNoteFormat.Stored.Rich
        assertEquals(RichNoteAdapter.canonical(tree), decoded.document)
        assertEquals(legacy, decoded.blocks)
        assertEquals(legacy, VirlinNoteCodec.decode(stored))
    }

    @Test fun v1DocumentsDecodeAsLegacyWithoutRewriting() {
        val v1 = VirlinNoteCodec.encode(listOf(b("m", PARAGRAPH, "# not a heading **literal**")))
        val decoded = RichNoteFormat.decode(v1) as RichNoteFormat.Stored.Legacy
        assertEquals("# not a heading **literal**", decoded.blocks.single().text)
        assertEquals(PARAGRAPH, decoded.blocks.single().type)
        assertEquals(v1, RichNoteFormat.encode(decoded.blocks, null))
    }

    @Test fun malformedBlankAndFutureDocumentsAreDamagedNotEmpty() {
        for (raw in listOf("", "   ", "{\"v\":1,\"blocks\":[", "{\"v\":2}", "{\"v\":9,\"blocks\":[]}", "[1,2]",
            "{\"v\":2,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"text\"}]},\"blocks\":[]}")) {
            val decoded = RichNoteFormat.decode(raw)
            assertTrue("expected damaged for <$raw>", decoded is RichNoteFormat.Stored.Damaged)
            assertEquals(raw, (decoded as RichNoteFormat.Stored.Damaged).raw)
        }
    }

    @Test fun chunksNeverSplitSurrogatePairs() {
        val text = "a😀b😀😀c".repeat(50)
        for (size in 2..7) {
            val chunks = RichNoteFormat.chunks(text, size)
            assertEquals(text, chunks.joinToString(""))
            chunks.forEach { chunk ->
                assertTrue(chunk.length <= size)
                assertFalse(Character.isHighSurrogate(chunk.last()))
                assertFalse(Character.isLowSurrogate(chunk.first()))
            }
        }
    }

    @Test fun salvageKeepsTextVerbatim() {
        val raw = """{"v":2,"doc":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"**keep** me"}]}"""
        val blocks = RichNoteAdapter.project(RichNoteAdapter.salvage(raw))
        assertEquals("**keep** me", blocks.single().text)
        val opaque = RichNoteAdapter.project(RichNoteAdapter.salvage("garbage"))
        assertEquals("garbage", opaque.single().text)
    }

    @Test fun colourConversionRoundTrips() {
        for (argb in listOf(0xFF000000L, 0xFFFFFFFFL, 0xFFE2F5EAL, 0x80FF0000L, 0x00123456L)) {
            assertEquals(argb, RichNoteAdapter.cssToArgb(RichNoteAdapter.argbToCss(argb)))
        }
        assertEquals(0xFFFF0000L, RichNoteAdapter.cssToArgb("rgb(255, 0, 0)"))
        assertEquals(0xFFAABBCCL, RichNoteAdapter.cssToArgb("#abc"))
    }

    @Test fun strictJsonRejectsTrailingAndTruncatedInput() {
        for (raw in listOf("{}x", "{\"a\":}", "[1,]", "\"abc", "{\"a\":1,}", "nul")) {
            assertThrows(RichJson.MalformedJson::class.java) { RichJson.parse(raw) }
        }
        assertEquals("\u2028", RichJson.parse(RichJson.stringify("\u2028")))
    }
}
