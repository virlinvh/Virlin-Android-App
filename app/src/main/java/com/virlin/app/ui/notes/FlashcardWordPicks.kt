package com.virlin.app.ui.notes

import androidx.compose.ui.text.TextRange
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteRuns

private fun Char.isWordChar() = isLetterOrDigit() || this == '\'' || this == '’' || this == '-' || this == '_'

/** The word around [offset] in [text] (a tap on a word's last edge still counts), or null. */
internal fun flashcardWordAt(text: String, offset: Int): TextRange? {
    if (text.isEmpty()) return null
    var at = offset.coerceIn(0, text.length)
    if (at == text.length || !text[at].isWordChar()) {
        if (at > 0 && text[at - 1].isWordChar()) at -= 1 else return null
    }
    var start = at
    while (start > 0 && text[start - 1].isWordChar()) start--
    var end = at + 1
    while (end < text.length && text[end].isWordChar()) end++
    return TextRange(start, end)
}

/**
 * Tapping a picked word removes it; tapping a new one adds it at the end, so the list keeps the
 * order the words were tapped in. Only real note text can be picked (not table cells or media).
 */
internal fun togglePickedWord(blocks: List<NoteDocBlock>, picks: List<TextRange>, word: TextRange): List<TextRange> {
    if (word in picks) return picks - word
    val slice = sliceProjectedRange(blocks, word.min, word.max)
    val ok = slice.size == 1 && slice[0].type.isTextual && slice[0].text.isNotBlank() &&
        slice[0].text.length == word.length
    return if (ok) picks + word else picks
}

/** The picked words, in tap order, as one line that keeps each word's own marks and colours. */
internal fun flashcardPickedBlocks(blocks: List<NoteDocBlock>, picks: List<TextRange>): List<NoteDocBlock> {
    val text = StringBuilder()
    val runs = ArrayList<com.virlin.app.domain.notedoc.NoteDocRun>()
    picks.forEach { range ->
        val word = sliceProjectedRange(blocks, range.min, range.max).singleOrNull() ?: return@forEach
        if (text.isNotEmpty()) text.append(' ')
        val at = text.length
        text.append(word.text)
        word.runs.forEach { runs += it.copy(start = it.start + at, end = it.end + at) }
    }
    if (text.isEmpty()) return emptyList()
    return listOf(NoteDocBlock("card-picked-0", NoteDocBlockType.PARAGRAPH, text.toString(),
        runs = NoteRuns.normalize(runs, text.length)))
}
