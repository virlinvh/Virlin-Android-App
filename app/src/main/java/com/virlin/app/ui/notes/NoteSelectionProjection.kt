package com.virlin.app.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteRuns

/** One selectable text surface across every note block, without changing the edit model. */
internal fun selectableNoteText(blocks: List<NoteDocBlock>): AnnotatedString = buildAnnotatedString {
    blocks.forEachIndexed { index, block ->
        if (index > 0) append("\n\n")
        val prefix = when (block.type) {
            NoteDocBlockType.BULLET -> "• "
            NoteDocBlockType.NUMBERED -> "${index + 1}. "
            NoteDocBlockType.CHECKLIST -> if (block.checked) "☑ " else "☐ "
            NoteDocBlockType.QUOTE -> "❝ "
            NoteDocBlockType.TOGGLE -> "▸ "
            else -> ""
        }
        append(prefix)
        when (block.type) {
            NoteDocBlockType.TABLE -> {
                block.table?.let { table ->
                    repeat(table.rows) { row ->
                        if (row > 0) append('\n')
                        repeat(table.cols) { col ->
                            if (col > 0) append("\t|\t")
                            append(table.cell(row, col))
                        }
                    }
                }
            }
            NoteDocBlockType.IMAGE -> append("[Image]")
            NoteDocBlockType.VIDEO -> append("[Video]")
            NoteDocBlockType.AUDIO -> append("[Audio]")
            NoteDocBlockType.DIVIDER -> append("────────")
            else -> {
                block.promptTitle?.takeIf(String::isNotBlank)?.let { append(it); append('\n') }
                val start = length
                append(block.text)
                val heading = when (block.type) {
                    NoteDocBlockType.HEADING_1 -> 27.sp
                    NoteDocBlockType.HEADING_2 -> 23.sp
                    NoteDocBlockType.HEADING_3 -> 19.sp
                    NoteDocBlockType.HEADING_4 -> 17.sp
                    NoteDocBlockType.HEADING_5 -> 15.sp
                    else -> null
                }
                if (heading != null) addStyle(SpanStyle(fontSize = heading,
                    fontWeight = FontWeight.SemiBold), start, length)
                if (block.type == NoteDocBlockType.CODE) addStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace), start, length)
                block.runs.forEach { run ->
                    val a = (start + run.start).coerceIn(start, length)
                    val b = (start + run.end).coerceIn(a, length)
                    if (a < b) addStyle(SpanStyle(
                        fontWeight = if (NoteDocMark.BOLD in run.marks) FontWeight.Bold else null,
                        fontStyle = if (NoteDocMark.ITALIC in run.marks) FontStyle.Italic else null,
                        textDecoration = when {
                            NoteDocMark.UNDERLINE in run.marks -> TextDecoration.Underline
                            NoteDocMark.STRIKE in run.marks -> TextDecoration.LineThrough
                            else -> null
                        },
                        fontFamily = if (NoteDocMark.CODE in run.marks) FontFamily.Monospace else null,
                        color = run.color?.let(::Color) ?: Color.Unspecified,
                        background = run.highlight?.let(::Color) ?: Color.Unspecified,
                    ), a, b)
                }
            }
        }
    }
}

/** Rebuilds the copied passage's marks from the note projection before opening the composer. */
internal fun flashcardSourceFromCopiedText(blocks: List<NoteDocBlock>, copied: String,
    selectionStart: Int? = null): NoteDocBlock? {
    if (copied.isBlank()) return null
    val projection = selectableNoteText(blocks)
    val offset = selectionStart?.takeIf { start ->
        start >= 0 && start + copied.length <= projection.length &&
            projection.text.regionMatches(start, copied, 0, copied.length)
    } ?: projection.text.indexOf(copied)
    if (offset < 0) return null
    val end = offset + copied.length
    val runs = projection.spanStyles.mapNotNull { span ->
        val a = maxOf(offset, span.start)
        val b = minOf(end, span.end)
        if (a >= b) return@mapNotNull null
        val style = span.item
        val marks = buildSet {
            if ((style.fontWeight?.weight ?: 0) >= FontWeight.SemiBold.weight) add(NoteDocMark.BOLD)
            if (style.fontStyle == FontStyle.Italic) add(NoteDocMark.ITALIC)
            if (style.textDecoration == TextDecoration.Underline) add(NoteDocMark.UNDERLINE)
            if (style.textDecoration == TextDecoration.LineThrough) add(NoteDocMark.STRIKE)
            if (style.fontFamily == FontFamily.Monospace) add(NoteDocMark.CODE)
        }
        NoteDocRun(a - offset, b - offset, marks,
            color = style.color.takeUnless { it == Color.Unspecified }?.value?.toLong(),
            highlight = style.background.takeUnless { it == Color.Unspecified }?.value?.toLong())
    }
    return NoteDocBlock("flashcard-source", NoteDocBlockType.PARAGRAPH, copied,
        runs = NoteRuns.normalize(runs, copied.length))
}
