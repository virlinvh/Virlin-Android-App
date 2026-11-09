package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType

/** The deepest indent a block may have; a wrap that would push a block past it is refused. */
internal const val MAX_NOTE_INDENT = 5

/** Indices of the blocks a cross-block selection [start, end) in [selectableNoteText] touches. */
internal fun projectedBlockRange(blocks: List<NoteDocBlock>, start: Int, end: Int): IntRange? {
    if (start >= end) return null
    val spans = projectedBlocks(blocks)
    val touched = spans.indices.filter { i ->
        val span = spans[i]
        (span.start < end && span.end > start) || (span.start == span.end && span.start in (start + 1) until end)
    }
    return if (touched.isEmpty()) null else touched.first()..touched.last()
}

/**
 * Puts the blocks in [range] inside a new, empty-titled Toggle block placed before them.
 *
 * The toggle takes the shallowest indent in the range and every wrapped block moves one level
 * deeper, so the hierarchy inside the selection is kept. Blocks nested under the last selected
 * block (a deeper indent right after it, e.g. a toggle's hidden children) move with it, so a
 * child is never separated from its parent. Text, marks and ids are untouched.
 * Returns null when the range is empty or a block is already at [MAX_NOTE_INDENT].
 */
internal fun wrapBlocksInToggle(blocks: List<NoteDocBlock>, range: IntRange, toggleId: String): List<NoteDocBlock>? {
    if (range.isEmpty() || range.first < 0 || range.last >= blocks.size) return null
    val base = blocks.subList(range.first, range.last + 1).minOf { it.indent }
    var last = range.last
    while (last + 1 < blocks.size && blocks[last + 1].indent > base) last++
    val wrapped = blocks.subList(range.first, last + 1)
    if (wrapped.any { it.indent >= MAX_NOTE_INDENT }) return null
    val toggle = NoteDocBlock(id = toggleId, type = NoteDocBlockType.TOGGLE, text = "", indent = base)
    return buildList(blocks.size + 1) {
        addAll(blocks.subList(0, range.first))
        add(toggle)
        wrapped.forEach { add(it.copy(indent = it.indent + 1)) }
        addAll(blocks.subList(last + 1, blocks.size))
    }
}
