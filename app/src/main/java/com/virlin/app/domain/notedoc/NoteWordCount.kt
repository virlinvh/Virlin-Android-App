package com.virlin.app.domain.notedoc

/**
 * Words and characters across a whole note: every block's text (collapsed toggles and folded
 * sections included), table cells and prompt titles. Characters are counted as code points
 * without line breaks, so an emoji is one character and splitting a line changes nothing.
 * A word is a whitespace-separated run containing a letter or digit ("—" alone is not a word).
 */
object NoteWordCount {
    data class Stats(val words: Int, val characters: Int, val charactersWithoutSpaces: Int)

    fun of(blocks: List<NoteDocBlock>): Stats {
        var words = 0
        var characters = 0
        var withoutSpaces = 0
        fun add(text: String) {
            if (text.isEmpty()) return
            var inWord = false
            var wordHasContent = false
            var index = 0
            while (index < text.length) {
                val point = text.codePointAt(index)
                index += Character.charCount(point)
                val space = Character.isWhitespace(point) || Character.isSpaceChar(point)
                if (point != '\n'.code && point != '\r'.code) characters++
                if (!space) withoutSpaces++
                if (space) {
                    if (inWord && wordHasContent) words++
                    inWord = false; wordHasContent = false
                } else {
                    inWord = true
                    if (Character.isLetterOrDigit(point)) wordHasContent = true
                }
            }
            if (inWord && wordHasContent) words++
        }
        blocks.forEach { block ->
            when (block.type) {
                NoteDocBlockType.IMAGE, NoteDocBlockType.DIVIDER, NoteDocBlockType.VIDEO, NoteDocBlockType.AUDIO -> Unit
                NoteDocBlockType.TABLE -> block.table?.cells?.forEach(::add)
                else -> {
                    block.promptTitle?.let(::add)
                    add(block.text)
                }
            }
        }
        return Stats(words, characters, withoutSpaces)
    }
}
