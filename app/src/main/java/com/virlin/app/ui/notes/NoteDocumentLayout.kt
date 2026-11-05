package com.virlin.app.ui.notes

import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType

/** Display-only rhythm shared by editing and preview. Never writes document attributes. */
internal object NoteDocumentLayout {
    fun isHeading(type: NoteDocBlockType) = type in setOf(
        NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3,
    )

    private fun isList(type: NoteDocBlockType) = type in setOf(
        NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED, NoteDocBlockType.CHECKLIST,
    )

    fun beforeDp(block: NoteDocBlock, index: Int): Int =
        if (index > 0 && isHeading(block.type)) 12 else 0

    fun afterDp(block: NoteDocBlock, next: NoteDocBlock?): Int = when {
        isList(block.type) && next?.type == block.type && next.indent == block.indent -> 2
        isHeading(block.type) -> 10
        block.type == NoteDocBlockType.DIVIDER -> 8
        block.type == NoteDocBlockType.QUOTE || block.type == NoteDocBlockType.CODE -> 16
        else -> 14
    }

    fun lineHeightSp(block: NoteDocBlock): Float {
        val base = NotesViewModel.defaultSizeFor(block.type).toFloat()
        // Explicit inline sizes still paint exactly as authored. Allow enough line height for
        // the largest run, without applying the first run's size to the entire paragraph.
        val largest = maxOf(base, block.runs.mapNotNull { it.fontSizeSp }.maxOrNull()?.toFloat() ?: base)
        val ratio = when {
            isHeading(block.type) -> 1.3f
            block.type == NoteDocBlockType.CODE -> 1.6f
            else -> 1.7f
        }
        return largest * ratio * block.lineSpacing.coerceAtLeast(1f)
    }
}
