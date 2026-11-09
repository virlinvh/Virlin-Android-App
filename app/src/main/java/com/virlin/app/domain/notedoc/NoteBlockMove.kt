package com.virlin.app.domain.notedoc

/** What dragging a block to a new place does to the note. */
sealed interface NoteBlockMove {
    data class Moved(val blocks: List<NoteDocBlock>) : NoteBlockMove
    /** The block is already there, or is not one of the visible blocks. */
    data object Unchanged : NoteBlockMove
    /** It, or the blocks after it, would end up folded away under a collapsed heading or toggle. */
    data object WouldHide : NoteBlockMove
}

/**
 * The blocks a reader sees while arranging: [visibleNoteBlocks] without heading-fold mode, so only
 * collapsed toggles and collapsed toggle headings hide anything.
 */
fun arrangeableNoteBlocks(blocks: List<NoteDocBlock>): List<NoteDocBlock> =
    visibleNoteBlocks(blocks, foldHeadings = false)

/**
 * Moves visible block [id] to position [to] among the [arrangeableNoteBlocks]. The blocks folded
 * away under it (a collapsed toggle's lines, a collapsed toggle heading's section) move with it.
 * When the drop point is right after a collapsed toggle, the moved piece is outdented just enough
 * to stay visible instead of becoming one of that toggle's hidden lines. A move that would still
 * fold any visible block out of sight is refused. Nothing else changes.
 */
fun moveNoteBlock(blocks: List<NoteDocBlock>, id: String, to: Int): NoteBlockMove {
    val visibleIds = arrangeableNoteBlocks(blocks).mapTo(HashSet()) { it.id }
    val units = ArrayList<List<NoteDocBlock>>()
    for (block in blocks) {
        if (block.id in visibleIds || units.isEmpty()) units += listOf(block)
        else units[units.size - 1] = units.last() + block
    }
    val from = units.indexOfFirst { it.first().id == id }
    if (from < 0) return NoteBlockMove.Unchanged
    val target = to.coerceIn(0, units.size - 1)
    if (target == from) return NoteBlockMove.Unchanged
    val piece = units.removeAt(from)
    for (shift in 0..piece.first().indent) {
        val shifted = if (shift == 0) piece else piece.map { it.copy(indent = (it.indent - shift).coerceAtLeast(0)) }
        val candidate = ArrayList<NoteDocBlock>(blocks.size).apply {
            units.forEachIndexed { index, unit -> if (index == target) addAll(shifted); addAll(unit) }
            if (target >= units.size) addAll(shifted)
        }
        if (arrangeableNoteBlocks(candidate).mapTo(HashSet()) { it.id } == visibleIds) return NoteBlockMove.Moved(candidate)
    }
    return NoteBlockMove.WouldHide
}
