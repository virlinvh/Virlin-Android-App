package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType.BULLET
import com.virlin.app.domain.notedoc.NoteDocBlockType.HEADING_2
import com.virlin.app.domain.notedoc.NoteDocBlockType.PARAGRAPH
import com.virlin.app.domain.notedoc.NoteDocBlockType.TOGGLE
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.rich.RichNoteAdapter
import com.virlin.app.domain.notedoc.visibleNoteBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteToggleWrapTest {

    private val doc = listOf(
        NoteDocBlock("h", HEADING_2, "Title"),
        NoteDocBlock("a", PARAGRAPH, "Speak naturally", listOf(NoteDocRun(0, 5, setOf(NoteDocMark.BOLD)))),
        NoteDocBlock("b", PARAGRAPH, "Second"),
        NoteDocBlock("c", PARAGRAPH, "After"),
    )

    @Test fun selectedBlocksMoveInsideANewToggleWithTheirContentUntouched() {
        val wrapped = wrapBlocksInToggle(doc, 1..2, "t")!!
        assertEquals(listOf("h", "t", "a", "b", "c"), wrapped.map { it.id })
        assertEquals(NoteDocBlock("t", TOGGLE, "", indent = 0), wrapped[1])
        assertEquals(doc[1].copy(indent = 1), wrapped[2])
        assertEquals(doc[2].copy(indent = 1), wrapped[3])
        assertEquals(doc[3], wrapped[4])
        val collapsed = wrapped.map { if (it.id == "t") it.copy(isCollapsed = true) else it }
        assertEquals(listOf("h", "t", "c"), visibleNoteBlocks(collapsed).map { it.id })
    }

    @Test fun nestingInsideTheSelectionIsKeptAndHiddenChildrenTravelWithTheirParent() {
        val blocks = listOf(
            NoteDocBlock("x", PARAGRAPH, "x", indent = 1),
            NoteDocBlock("old", TOGGLE, "old toggle", indent = 1, isCollapsed = true),
            NoteDocBlock("kid", PARAGRAPH, "hidden child", indent = 2),
            NoteDocBlock("y", PARAGRAPH, "y", indent = 1),
        )
        val wrapped = wrapBlocksInToggle(blocks, 1..1, "t")!!
        assertEquals(listOf("x" to 1, "t" to 1, "old" to 2, "kid" to 3, "y" to 1), wrapped.map { it.id to it.indent })
    }

    @Test fun wrapsDeeperThanTheLimitAreRefused() {
        val deep = listOf(NoteDocBlock("d", PARAGRAPH, "deep", indent = MAX_NOTE_INDENT))
        assertNull(wrapBlocksInToggle(deep, 0..0, "t"))
        assertNull(wrapBlocksInToggle(doc, 2..1, "t"))
    }

    @Test fun aCrossBlockSelectionMapsToTheBlocksItTouches() {
        val text = selectableNoteText(doc).text
        val start = text.indexOf("naturally")
        val end = text.indexOf("Second") + 3
        assertEquals(1..2, projectedBlockRange(doc, start, end))
        assertNull(projectedBlockRange(doc, 4, 4))
    }

    @Test fun richNotesStoreTheWrapExactlyForTopLevelParagraphs() {
        val tree = RichNoteAdapter.fromLegacy(doc)
        val wrapped = wrapBlocksInToggle(RichNoteAdapter.project(tree), 1..2, "t")!!
        val merged = RichNoteAdapter.applyBlockEdits(tree, wrapped)
        assertNotNull(merged)
        val back = RichNoteAdapter.project(merged!!)
        assertEquals(wrapped.map { Triple(it.id, it.type, it.indent) }, back.map { Triple(it.id, it.type, it.indent) })
        assertEquals(wrapped.map { it.text }, back.map { it.text })
    }

    @Test fun richListItemsCannotBeReindentedSoTheWrapIsDetectedAsUnsafe() {
        val blocks = listOf(NoteDocBlock("p", PARAGRAPH, "intro"), NoteDocBlock("l1", BULLET, "one"),
            NoteDocBlock("l2", BULLET, "two"))
        val tree = RichNoteAdapter.fromLegacy(blocks)
        val wrapped = wrapBlocksInToggle(RichNoteAdapter.project(tree), 1..1, "t")!!
        val merged = RichNoteAdapter.applyBlockEdits(tree, wrapped)
        assertTrue(merged == null || RichNoteAdapter.project(merged).map { it.id to it.indent } !=
            wrapped.map { it.id to it.indent })
    }
}
