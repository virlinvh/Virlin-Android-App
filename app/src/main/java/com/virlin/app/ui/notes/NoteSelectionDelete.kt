package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteRuns

/**
 * Where one block sits inside [selectableNoteText]: [start] includes its list/quote prefix,
 * [contentStart] follows it, and [textStart] is where the block's editable text begins
 * (-1 for tables, images and dividers, whose projection is only a placeholder).
 */
internal data class ProjectedBlock(val start: Int, val contentStart: Int, val end: Int, val textStart: Int)

/** Mirrors the offsets [selectableNoteText] produces; a test keeps the two aligned. */
internal fun projectedBlocks(blocks: List<NoteDocBlock>): List<ProjectedBlock> {
    var at = 0
    return blocks.mapIndexed { index, block ->
        if (index > 0) at += 2
        val start = at
        at += when (block.type) {
            NoteDocBlockType.BULLET, NoteDocBlockType.CHECKLIST,
            NoteDocBlockType.QUOTE, NoteDocBlockType.TOGGLE -> 2
            NoteDocBlockType.NUMBERED -> "${index + 1}. ".length
            else -> 0
        }
        val contentStart = at
        var textStart = -1
        when (block.type) {
            NoteDocBlockType.TABLE -> block.table?.let { table ->
                repeat(table.rows) { row ->
                    if (row > 0) at += 1
                    repeat(table.cols) { col ->
                        if (col > 0) at += 3
                        at += table.cell(row, col).length
                    }
                }
            }
            NoteDocBlockType.IMAGE -> at += "[Image]".length
            NoteDocBlockType.VIDEO -> at += "[Video]".length
            NoteDocBlockType.AUDIO -> at += "[Audio]".length
            NoteDocBlockType.DIVIDER -> at += "────────".length
            else -> {
                block.promptTitle?.takeIf(String::isNotBlank)?.let { at += it.length + 1 }
                textStart = at
                at += block.text.length
            }
        }
        ProjectedBlock(start, contentStart, at, textStart)
    }
}

/**
 * Removes the cross-block selection [start, end) made in [selectableNoteText].
 *
 * When the selection spans several blocks, a block whose content is entirely selected is
 * removed. Partly selected text is trimmed from its block; a partly selected table, image or
 * divider is kept whole rather than cut. Prompt titles are only removed with their block.
 * Returns null when nothing would change.
 */
internal fun deleteProjectedRange(blocks: List<NoteDocBlock>, start: Int, end: Int): List<NoteDocBlock>? {
    if (start >= end) return null
    val spans = projectedBlocks(blocks)
    fun touches(span: ProjectedBlock) =
        (span.start < end && span.end > start) || (span.start == span.end && span.start in (start + 1) until end)
    val multiBlock = spans.count(::touches) > 1
    var changed = false
    val result = ArrayList<NoteDocBlock>(blocks.size)
    blocks.forEachIndexed { index, block ->
        val span = spans[index]
        if (!touches(span)) { result += block; return@forEachIndexed }
        val textual = span.textStart >= 0
        val covered = start <= (if (textual) span.textStart else span.contentStart) && end >= span.end
        when {
            covered && (multiBlock || !textual) -> changed = true
            textual -> {
                val a = (start - span.textStart).coerceIn(0, block.text.length)
                val b = (end - span.textStart).coerceIn(a, block.text.length)
                if (a < b) {
                    val text = block.text.removeRange(a, b)
                    result += block.copy(text = text,
                        runs = NoteRuns.shiftForEdit(block.runs, a, b - a, 0, text.length))
                    changed = true
                } else result += block
            }
            else -> result += block
        }
    }
    return if (changed) result else null
}
