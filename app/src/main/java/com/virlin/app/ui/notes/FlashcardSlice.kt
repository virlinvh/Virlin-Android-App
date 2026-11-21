package com.virlin.app.ui.notes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteRuns

/**
 * The exact note blocks under the cross-block selection [start, end) of [selectableNoteText]:
 * block types (headings, code, prompts, equations, lists, tables, media) and every text run
 * (font, size, colour, highlight, link) are kept. Partly selected text is trimmed; a partly
 * selected table, image, video or recording is taken whole. The note itself is never changed.
 */
internal fun sliceProjectedRange(blocks: List<NoteDocBlock>, start: Int, end: Int): List<NoteDocBlock> {
    val from = minOf(start, end)
    val to = maxOf(start, end)
    if (from >= to) return emptyList()
    val spans = projectedBlocks(blocks)
    val picked = ArrayList<NoteDocBlock>()
    blocks.forEachIndexed { index, block ->
        val span = spans[index]
        if (!(span.start < to && span.end > from)) return@forEachIndexed
        if (span.textStart < 0) { picked += block; return@forEachIndexed }
        val a = (from - span.textStart).coerceIn(0, block.text.length)
        val b = (to - span.textStart).coerceIn(a, block.text.length)
        val titleTouched = block.promptTitle != null && from < span.textStart
        if (a == b && !titleTouched) return@forEachIndexed
        picked += block.copy(
            text = block.text.substring(a, b),
            runs = NoteRuns.normalize(block.runs.mapNotNull { run ->
                val left = maxOf(run.start, a)
                val right = minOf(run.end, b)
                if (left >= right) null else run.copy(start = left - a, end = right - a)
            }, b - a),
            promptTitle = if (titleTouched) block.promptTitle else null,
        )
    }
    return flashcardBlocks(picked)
}

/** Card-local ids, no folded sections and indents starting at zero, so a side always shows fully. */
internal fun flashcardBlocks(blocks: List<NoteDocBlock>): List<NoteDocBlock> {
    val kept = blocks.filter { it.hasFlashcardContent() }
    val minIndent = kept.minOfOrNull { it.indent } ?: 0
    return kept.mapIndexed { index, block ->
        block.copy(id = "card-block-$index", indent = block.indent - minIndent, isCollapsed = false)
    }
}

internal fun NoteDocBlock.hasFlashcardContent(): Boolean =
    !type.isTextual || text.isNotBlank() || !promptTitle.isNullOrBlank()

internal fun List<NoteDocBlock>.hasFlashcardContent(): Boolean = any { it.hasFlashcardContent() }

/** The passage as one selectable text, styled with each run's own font, size and colour. */
internal fun flashcardPassageText(blocks: List<NoteDocBlock>, accent: Color): AnnotatedString {
    val base = selectableNoteText(blocks)
    val spans = projectedBlocks(blocks)
    return buildAnnotatedString {
        append(base)
        blocks.forEachIndexed { index, block ->
            val textStart = spans[index].textStart
            if (textStart < 0) return@forEachIndexed
            block.runs.forEach { run ->
                val a = (textStart + run.start).coerceIn(0, length)
                val b = (textStart + run.end).coerceIn(a, length)
                if (a < b) addStyle(run.toSpanStyle(accent), a, b)
            }
        }
    }
}

/**
 * One plain block standing in for a rich side, kept for older readers of the card file, copying
 * and the 300-character question summary. The rich blocks remain the card's real content.
 */
internal fun flashcardSummaryBlock(blocks: List<NoteDocBlock>): NoteDocBlock {
    val text = selectableNoteText(blocks).text
    return flashcardSourceFromCopiedText(blocks, text, 0)
        ?: NoteDocBlock("flashcard-summary", NoteDocBlockType.PARAGRAPH, text.ifBlank { "[Card]" })
}
