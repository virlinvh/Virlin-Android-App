package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteBlockSelectionTest {
    private val blocks = listOf(
        NoteDocBlock("t", NoteDocBlockType.TOGGLE, "Folded", isCollapsed = true),
        NoteDocBlock("c1", NoteDocBlockType.PARAGRAPH, "inside", indent = 1),
        NoteDocBlock("c2", NoteDocBlockType.VIDEO, indent = 1),
        NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "after"),
        NoteDocBlock("a", NoteDocBlockType.AUDIO),
    )
    private val shown = blocks.filter { it.id in setOf("t", "p", "a") }

    @Test fun aFoldedBlockIsSelectedWithWhatItHides() {
        val shownIds = shown.map { it.id }.toSet()
        assertEquals(listOf("t", "c1", "c2"), blockWithFolded(blocks, shownIds, "t"))
        assertEquals(listOf("p"), blockWithFolded(blocks, shownIds, "p"))
    }

    @Test fun aRangeRunsEitherWayAndIncludesFoldedBlocks() {
        assertEquals(setOf("t", "c1", "c2", "p"), blockRange(blocks, shown, "p", "t"))
        assertEquals(setOf("p", "a"), blockRange(blocks, shown, "p", "a"))
    }

    @Test fun onlyNeighbouringBlocksFormAToggleRange() {
        assertEquals(0..3, contiguousBlockRange(blocks, setOf("t", "c1", "c2", "p")))
        assertNull(contiguousBlockRange(blocks, setOf("t", "a")))
        assertNull(contiguousBlockRange(blocks, emptySet()))
    }
}
