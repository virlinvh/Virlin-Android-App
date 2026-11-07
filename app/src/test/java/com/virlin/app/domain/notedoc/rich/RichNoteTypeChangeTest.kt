package com.virlin.app.domain.notedoc.rich

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocBlockType.*
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocTable
import org.junit.Assert.*
import org.junit.Test

/** Line-style changes the phone editor makes on a note stored as a rich tree. */
class RichNoteTypeChangeTest {

    private fun b(id: String, type: NoteDocBlockType, text: String = "", vararg runs: NoteDocRun) =
        NoteDocBlock(id, type, text, runs.toList())

    private fun retype(tree: String, vararg changes: Pair<String, NoteDocBlockType>): List<NoteDocBlock> {
        val map = changes.toMap()
        return RichNoteAdapter.project(tree).map { block -> map[block.id]?.let { block.copy(type = it) } ?: block }
    }

    /** Applies [edited] and asserts the stored tree reads back exactly as the editor holds it. */
    private fun saves(tree: String, edited: List<NoteDocBlock>): String {
        val merged = RichNoteAdapter.applyBlockEdits(tree, edited)
        assertNotNull("edit was refused", merged)
        assertEquals(edited, RichNoteAdapter.project(merged!!))
        return merged
    }

    private fun count(json: String, type: String) = Regex("\"type\":\"$type\"").findAll(json).count()

    private val reported = RichNoteAdapter.fromLegacy(listOf(
        b("h", HEADING_1, "bhbj").copy(toggleHeading = true),
        b("p", PARAGRAPH, "Cursor's conclusion is the right one.", NoteDocRun(22, 27, setOf(NoteDocMark.BOLD))),
        NoteDocBlock("tb", TABLE, table = NoteDocTable(2, 2, listOf("", "", "", ""))),
    ))

    @Test fun aFormattedParagraphBecomesABulletInANoteWithAToggleHeadingAndATable() {
        val merged = saves(reported, retype(reported, "p" to BULLET))
        assertEquals(1, count(merged, "bulletList"))
        assertTrue("bold survives", merged.contains("\"bold\""))
        // ...and back to Normal text.
        saves(merged, retype(merged, "p" to PARAGRAPH))
    }

    private val lines = RichNoteAdapter.fromLegacy(listOf(
        b("a", BULLET, "one"), b("p", PARAGRAPH, "two"), b("c", BULLET, "three"), b("q", PARAGRAPH, "four"),
    ))

    @Test fun aLineBetweenTwoBulletListsJoinsThemIntoOneList() {
        val merged = saves(lines, retype(lines, "p" to BULLET))
        assertEquals(1, count(merged, "bulletList"))
    }

    @Test fun aMiddleBulletBecomingTextSplitsItsList() {
        val three = RichNoteAdapter.fromLegacy(listOf(b("a", BULLET, "1"), b("b", BULLET, "2"), b("c", BULLET, "3")))
        val merged = saves(three, retype(three, "b" to PARAGRAPH))
        assertEquals(2, count(merged, "bulletList"))
        saves(three, retype(three, "a" to PARAGRAPH))
        saves(three, retype(three, "c" to HEADING_2))
    }

    @Test fun listKindsSwitchAndChecklistStateIsKept() {
        val numbered = saves(lines, retype(lines, "a" to NUMBERED))
        assertEquals(1, count(numbered, "orderedList"))
        val checklist = retype(lines, "q" to CHECKLIST).map { if (it.id == "q") it.copy(checked = true) else it }
        assertTrue(saves(lines, checklist).contains("\"checked\":true"))
    }

    @Test fun headingsQuotesTogglesCodeAndPromptsConvertBothWays() {
        val doc = RichNoteAdapter.fromLegacy(listOf(
            b("h", HEADING_2, "Heading"), b("p", PARAGRAPH, "plain text"), NoteDocBlock("t", TOGGLE, "toggle"),
            b("q", QUOTE, "quoted"), NoteDocBlock("code", CODE, "npm install", mathMode = false),
            NoteDocBlock("pr", PROMPT, "Summarise", promptTitle = ""),
        ))
        saves(doc, retype(doc, "h" to BULLET))
        saves(doc, retype(doc, "t" to NUMBERED))
        saves(doc, retype(doc, "q" to PARAGRAPH))
        saves(doc, retype(doc, "q" to BULLET))
        saves(doc, retype(doc, "p" to QUOTE))
        saves(doc, retype(doc, "p" to TOGGLE))
        saves(doc, retype(doc, "code" to PARAGRAPH).map { if (it.id == "code") it.copy(mathMode = null) else it })
        saves(doc, retype(doc, "pr" to HEADING_1).map { if (it.id == "pr") it.copy(promptTitle = null) else it })
        saves(doc, retype(doc, "p" to CODE).map { if (it.id == "p") it.copy(mathMode = false) else it })
        saves(doc, retype(doc, "p" to PROMPT).map { if (it.id == "p") it.copy(promptTitle = "") else it })
    }

    @Test fun aRetypeAndANewLineInTheSameSaveLandInOrder() {
        val edited = retype(lines, "p" to BULLET).toMutableList()
        edited.add(edited.indexOfFirst { it.id == "p" } + 1, b("new", BULLET, "inserted"))
        saves(lines, edited)
    }

    @Test fun newLinesTypedInsideAListStayWhereTheyWereTyped() {
        val list = RichNoteAdapter.fromLegacy(listOf(b("a", BULLET, "1"), b("b", BULLET, "2"), b("c", BULLET, "3")))
        val projected = RichNoteAdapter.project(list)
        val bullet = projected.toMutableList().apply { add(1, b("n1", BULLET, "1.5")); add(2, b("n2", BULLET, "1.6")) }
        assertEquals(1, count(saves(list, bullet), "bulletList"))
        val text = projected.toMutableList().apply { add(2, b("n", PARAGRAPH, "between")) }
        assertEquals(2, count(saves(list, text), "bulletList"))
    }

    @Test fun shapesThatWouldLoseContentAreStillRefused() {
        val nested = RichNoteAdapter.fromLegacy(listOf(b("a", BULLET, "outer"), NoteDocBlock("b", BULLET, "inner", indent = 1)))
        assertNull("item with a nested list", RichNoteAdapter.applyBlockEdits(nested, retype(nested, "a" to PARAGRAPH)))
        assertNull("nested item", RichNoteAdapter.applyBlockEdits(nested, retype(nested, "b" to PARAGRAPH)))
        val bold = RichNoteAdapter.fromLegacy(listOf(b("p", PARAGRAPH, "bold", NoteDocRun(0, 4, setOf(NoteDocMark.BOLD)))))
        assertNull("formatting would be dropped", RichNoteAdapter.applyBlockEdits(bold, retype(bold, "p" to CODE)))
        val anonymous = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"x"}]}]}"""
        assertNull("no id of its own", RichNoteAdapter.applyBlockEdits(anonymous, retype(anonymous, "anon-0" to BULLET)))
    }
}
