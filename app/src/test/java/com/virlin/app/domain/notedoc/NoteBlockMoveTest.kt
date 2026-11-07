package com.virlin.app.domain.notedoc

import com.virlin.app.domain.notedoc.rich.RichNoteAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Arrange mode: moving blocks, what moves with them, and what a stored rich tree can hold. */
class NoteBlockMoveTest {
    private fun p(id: String, indent: Int = 0) = NoteDocBlock(id, NoteDocBlockType.PARAGRAPH, id, indent = indent)
    private fun ids(move: NoteBlockMove) = (move as NoteBlockMove.Moved).blocks.map { it.id }

    @Test fun blocksMoveUpAndDown() {
        val blocks = listOf(p("a"), p("b"), p("c"), p("d"))
        assertEquals(listOf("b", "c", "a", "d"), ids(moveNoteBlock(blocks, "a", 2)))
        assertEquals(listOf("d", "a", "b", "c"), ids(moveNoteBlock(blocks, "d", 0)))
        assertEquals(listOf("b", "c", "d", "a"), ids(moveNoteBlock(blocks, "a", 99)))
        assertEquals(NoteBlockMove.Unchanged, moveNoteBlock(blocks, "b", 1))
        assertEquals(NoteBlockMove.Unchanged, moveNoteBlock(blocks, "missing", 0))
    }

    @Test fun aCollapsedToggleCarriesItsHiddenLines() {
        val toggle = NoteDocBlock("t", NoteDocBlockType.TOGGLE, "T", isCollapsed = true)
        val blocks = listOf(p("a"), toggle, p("t1", 1), p("t2", 1), p("b"))
        assertEquals(listOf("a", "t", "b"), arrangeableNoteBlocks(blocks).map { it.id })
        assertEquals(listOf("a", "b", "t", "t1", "t2"), ids(moveNoteBlock(blocks, "t", 2)))
        assertEquals(listOf("t", "t1", "t2", "a", "b"), ids(moveNoteBlock(blocks, "t", 0)))
    }

    @Test fun droppingRightAfterACollapsedToggleKeepsTheBlockInSight() {
        val toggle = NoteDocBlock("t", NoteDocBlockType.TOGGLE, "T", isCollapsed = true)
        val blocks = listOf(p("x", 1), toggle, p("t1", 1), p("b"))
        val moved = (moveNoteBlock(blocks, "x", 1) as NoteBlockMove.Moved).blocks
        assertEquals(listOf("t", "t1", "x", "b"), moved.map { it.id })
        assertEquals(0, moved.first { it.id == "x" }.indent)
        assertEquals(1, moved.first { it.id == "t1" }.indent)
    }

    @Test fun movesThatWouldFoldBlocksAwayAreRefused() {
        val heading = NoteDocBlock("h", NoteDocBlockType.HEADING_2, "H", toggleHeading = true, isCollapsed = true)
        val blocks = listOf(p("a"), heading, p("h1"), NoteDocBlock("h2", NoteDocBlockType.HEADING_1, "Next"), p("b"))
        assertEquals(listOf("a", "h", "h2", "b"), arrangeableNoteBlocks(blocks).map { it.id })
        // Into the collapsed section, or the collapsed heading above other text: both hide blocks.
        assertEquals(NoteBlockMove.WouldHide, moveNoteBlock(blocks, "a", 1))
        assertEquals(NoteBlockMove.WouldHide, moveNoteBlock(blocks, "h", 0))
        // The closing heading moved away would put "b" under the collapsed heading.
        assertEquals(NoteBlockMove.WouldHide, moveNoteBlock(blocks, "h2", 0))
        assertEquals(listOf("b", "a", "h", "h1", "h2"), ids(moveNoteBlock(blocks, "b", 0)))
    }

    @Test fun aRichTreeReordersWholeTopLevelParts() {
        val bullet = { id: String -> NoteDocBlock(id, NoteDocBlockType.BULLET, id) }
        val blocks = listOf(p("a"), bullet("l1"), bullet("l2"), p("b"))
        val tree = RichNoteAdapter.fromLegacy(blocks)
        val paragraphUp = listOf(p("b"), p("a"), bullet("l1"), bullet("l2"))
        assertEquals(paragraphUp, RichNoteAdapter.project(RichNoteAdapter.applyBlockEdits(tree, paragraphUp)!!))
        val listDown = listOf(p("a"), p("b"), bullet("l1"), bullet("l2"))
        assertEquals(listDown, RichNoteAdapter.project(RichNoteAdapter.applyBlockEdits(tree, listDown)!!))
    }

    @Test fun aRichTreeNeverSplitsAPartToReorder() {
        val bullet = { id: String -> NoteDocBlock(id, NoteDocBlockType.BULLET, id) }
        val blocks = listOf(p("a"), bullet("l1"), bullet("l2"), p("b"))
        val tree = RichNoteAdapter.fromLegacy(blocks)
        // A list item pulled out of its list cannot be stored; the tree keeps its order, so the
        // editor's read-back sees the difference and refuses the move.
        val itemOut = listOf(bullet("l2"), p("a"), bullet("l1"), p("b"))
        val merged = RichNoteAdapter.applyBlockEdits(tree, itemOut)
        assertNotNull(merged)
        assertEquals(blocks.map { it.id }, RichNoteAdapter.project(merged!!).map { it.id })
        // Ordinary edits without a reorder still merge exactly as before.
        val edited = blocks.map { if (it.id == "b") it.copy(text = "b2") else it }
        assertEquals(edited, RichNoteAdapter.project(RichNoteAdapter.applyBlockEdits(tree, edited)!!))
        assertEquals(RichNoteAdapter.canonical(tree), RichNoteAdapter.applyBlockEdits(tree, blocks))
    }
}
