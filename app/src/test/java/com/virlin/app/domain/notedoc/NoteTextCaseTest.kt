package com.virlin.app.domain.notedoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteTextCaseTest {

    private fun next(text: String, start: Int = 0, end: Int = text.length) =
        NoteTextCases.nextCase(listOf(NoteTextCases.Segment(text, start, end)))

    private fun cycle(text: String): String {
        val case = next(text) ?: return text
        return NoteTextCases.convert(text, 0, text.length, case)
    }

    @Test fun tapsCycleAllCapsThenTitleThenLowercase() {
        val first = cycle("hello wide world")
        assertEquals("HELLO WIDE WORLD", first)
        val second = cycle(first)
        assertEquals("Hello Wide World", second)
        val third = cycle(second)
        assertEquals("hello wide world", third)
        assertEquals("MIXED CASE", cycle("mIxEd CaSe"))
    }

    @Test fun aStepThatChangesNothingIsSkipped() {
        // "A" is already caps and title case, so the next tap goes straight to lowercase.
        assertEquals(NoteTextCase.LOWER, next("A"))
        assertNull(next("123 !?"))
    }

    @Test fun titleCaseKeepsApostropheWordsAndPartialSelectionsTogether() {
        assertEquals("Don't Stop", NoteTextCases.convert("DON'T STOP", 0, 10, NoteTextCase.TITLE))
        assertEquals("It\u2019s Fine", NoteTextCases.convert("it\u2019s fine", 0, 9, NoteTextCase.TITLE))
        // Starting mid-word does not capitalise the middle of the word.
        assertEquals("hello", NoteTextCases.convert("heLLO", 2, 5, NoteTextCase.TITLE))
        assertEquals("one TWO three", NoteTextCases.convert("one two three", 4, 7, NoteTextCase.UPPER))
    }

    @Test fun lengthNeverChangesAndMathsKeepsItsCase() {
        val text = "area \\(\\pi r^2\\) straße ǆ"
        val upper = NoteTextCases.convert(text, 0, text.length, NoteTextCase.UPPER)
        assertEquals(text.length, upper.length)
        assertEquals("AREA \\(\\pi r^2\\) STRAßE Ǆ", upper)
        val title = NoteTextCases.convert("ǆungla", 0, 6, NoteTextCase.TITLE)
        assertEquals("ǅungla", title)
    }

    @Test fun wordAtFindsTheWordUnderTheCaret() {
        assertEquals(4..8, NoteTextCases.wordAt("say don't now", 6))
        assertEquals(4..8, NoteTextCases.wordAt("say don't now", 9))
        assertNull(NoteTextCases.wordAt("a  b", 2))
    }
}
