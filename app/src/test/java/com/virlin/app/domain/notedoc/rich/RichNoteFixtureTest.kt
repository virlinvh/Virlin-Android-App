package com.virlin.app.domain.notedoc.rich

import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType.*
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Cross-language contract with the WebView editor (notes-editor/). Kotlin writes the legacy
 * conversion fixture; the JS test loads it into the real Tiptap editor and writes back the
 * editor's own JSON; this test projects that JSON and must get the original blocks back.
 * Regenerate with UPDATE_FIXTURES=1 (Kotlin first, then `npm test`).
 */
class RichNoteFixtureTest {

    companion object {
        val FIXTURE_DIR = File("../notes-editor/src/test/fixtures")

        val BLOCKS = listOf(
            NoteDocBlock("h1", HEADING_1, "Title", listOf(NoteDocRun(0, 5, setOf(NoteDocMark.BOLD)))),
            NoteDocBlock("h2", HEADING_2, "Sub", isCollapsed = true),
            NoteDocBlock("p1", PARAGRAPH, "Plain with link and colour", listOf(
                NoteDocRun(11, 15, linkUrl = "https://example.com"),
                NoteDocRun(20, 26, color = 0xFFD32F2FL, highlight = 0xFFFFF59DL, fontFamily = "serif", fontSizeSp = 18))),
            NoteDocBlock("l1", BULLET, "one"), NoteDocBlock("l2", BULLET, "two", indent = 1),
            NoteDocBlock("l3", BULLET, "three", indent = 2), NoteDocBlock("l4", BULLET, "four"),
            NoteDocBlock("n1", NUMBERED, "first"), NoteDocBlock("n2", NUMBERED, "second"),
            NoteDocBlock("c1", CHECKLIST, "done", checked = true), NoteDocBlock("c2", CHECKLIST, "open"),
            NoteDocBlock("t1", TOGGLE, "toggle", isCollapsed = true), NoteDocBlock("t1a", PARAGRAPH, "inside", indent = 1),
            NoteDocBlock("q1", QUOTE, "quoted", listOf(NoteDocRun(0, 6, setOf(NoteDocMark.ITALIC)))),
            NoteDocBlock("code", CODE, "val x = 1\nprintln(x)", mathMode = false),
            NoteDocBlock("auto-eq", CODE, "E = mc^2"),
            NoteDocBlock("math", CODE, "\\frac{a}{b}", mathMode = true),
            NoteDocBlock("pr", PROMPT, "Summarise this", promptTitle = "System"),
            NoteDocBlock("tb", TABLE, table = NoteDocTable(2, 2, listOf("A", "B", "1", "2"))),
            NoteDocBlock("img", IMAGE, imagePath = "0f8fad5b-d9cb-469f-a165-70867728950e"),
            NoteDocBlock("hr", DIVIDER),
            NoteDocBlock("p2", PARAGRAPH, "centred\nsecond line", align = NoteDocAlign.CENTER, lineSpacing = 1.5f),
            NoteDocBlock("p3", PARAGRAPH, "# literal markdown stays text", indent = 2),
        )
    }

    private val update = System.getenv("UPDATE_FIXTURES") == "1"

    @Test fun kotlinLegacyFixtureIsCurrent() {
        assumeTrue(FIXTURE_DIR.isDirectory || update)
        val file = File(FIXTURE_DIR, "kotlin-legacy.json")
        val expected = RichNoteAdapter.fromLegacy(BLOCKS)
        if (update) { FIXTURE_DIR.mkdirs(); file.writeText(expected) }
        assertEquals(expected, file.readText())
    }

    @Test fun editorRoundTripProjectsToTheOriginalBlocks() {
        val file = File(FIXTURE_DIR, "tiptap-roundtrip.json")
        assumeTrue(file.isFile)
        assertEquals(BLOCKS, RichNoteAdapter.project(file.readText()))
    }

    @Test fun editorAuthoredFixtureProjectsAsExpected() {
        val file = File(FIXTURE_DIR, "tiptap-authored.json")
        assumeTrue(file.isFile)
        val blocks = RichNoteAdapter.project(file.readText())
        // Every block keeps a non-blank, unique id and the editor's structures survive.
        assertEquals(blocks.size, blocks.map { it.id }.toSet().size)
        assertEquals(true, blocks.all { it.id.isNotBlank() })
        val types = blocks.map { it.type }.toSet()
        listOf(HEADING_1, BULLET, CHECKLIST, CODE, PROMPT, TABLE, QUOTE).forEach {
            assertEquals("missing $it in $types", true, it in types)
        }
        assertEquals(true, blocks.any { it.type == CODE && it.mathMode == true })
        assertEquals(true, blocks.any { it.text.contains("\\(x^2\\)") })
    }
}
