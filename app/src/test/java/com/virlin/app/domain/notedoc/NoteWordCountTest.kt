package com.virlin.app.domain.notedoc

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteWordCountTest {
    private fun block(type: NoteDocBlockType, text: String = "", table: NoteDocTable? = null,
        promptTitle: String? = null) =
        NoteDocBlock(id = "b${text.hashCode()}", type = type, text = text, table = table, promptTitle = promptTitle)

    @Test fun countsWordsAndCharactersAcrossBlocks() {
        val stats = NoteWordCount.of(listOf(
            block(NoteDocBlockType.HEADING_1, "Plan the week"),
            block(NoteDocBlockType.PARAGRAPH, "Call Meekha — then email."),
        ))
        assertEquals(7, stats.words)
        assertEquals(13 + 25, stats.characters)
        assertEquals(11 + 21, stats.charactersWithoutSpaces)
    }

    @Test fun lineBreaksAreNotCharactersAndEmojiCountOnce() {
        val stats = NoteWordCount.of(listOf(block(NoteDocBlockType.PARAGRAPH, "Hi 👋\nthere")))
        assertEquals(2, stats.words)
        assertEquals(9, stats.characters)
        assertEquals(8, stats.charactersWithoutSpaces)
    }

    @Test fun tablesPromptTitlesAndCodeCountImagesAndDividersDoNot() {
        val stats = NoteWordCount.of(listOf(
            block(NoteDocBlockType.TABLE, table = NoteDocTable(1, 2, listOf("Easy", "94"))),
            block(NoteDocBlockType.PROMPT, "Check it", promptTitle = "Opening"),
            block(NoteDocBlockType.CODE, "val x = 1"),
            block(NoteDocBlockType.IMAGE).copy(imagePath = "a"),
            block(NoteDocBlockType.DIVIDER),
        ))
        assertEquals(2 + 3 + 3, stats.words)
        assertEquals(6 + 15 + 9, stats.characters)
    }

    @Test fun emptyNoteIsZero() {
        assertEquals(NoteWordCount.Stats(0, 0, 0), NoteWordCount.of(listOf(block(NoteDocBlockType.PARAGRAPH))))
    }
}
