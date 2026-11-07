package com.virlin.app.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteColorPalettesTest {

    @Test fun palettesAreLargeDistinctAndKeepTheOldColours() {
        val text = NoteColorPalettes.text
        val highlight = NoteColorPalettes.highlight
        assertNull("first text swatch is Default", text.first().argb)
        assertNull("first highlight swatch is None", highlight.first().argb)
        assertTrue(text.size >= 50)
        assertTrue(highlight.size >= 45)
        assertEquals(text.size, text.map { it.argb }.toSet().size)
        assertEquals(highlight.size, highlight.map { it.argb }.toSet().size)
        listOf(0xFF17221DL, 0xFF087848L, 0xFFB3261EL, 0xFF1A56DBL, 0xFF8A5A00L, 0xFF66758AL)
            .forEach { assertTrue(text.any { s -> s.argb == it }) }
        listOf(0xFFE2F5EAL, 0xFFFFF3BFL, 0xFFFFE0E0L, 0xFFE0ECFFL)
            .forEach { assertTrue(highlight.any { s -> s.argb == it }) }
    }

    @Test fun hexCodesParseInEveryCommonFormAndInvalidOnesAreRefused() {
        assertEquals(0xFFFFEB3BL, NoteColorPalettes.parseHex("#FFEB3B"))
        assertEquals(0xFFFFEB3BL, NoteColorPalettes.parseHex("ffeb3b"))
        assertEquals(0xFFFFEEBBL, NoteColorPalettes.parseHex("#FEB"))
        assertNull(NoteColorPalettes.parseHex("#GGGGGG"))
        assertNull(NoteColorPalettes.parseHex("12345"))
        assertNull(NoteColorPalettes.parseHex(""))
        assertEquals("#FFEB3B", NoteColorPalettes.hexOf(0xFFFFEB3BL))
    }

    @Test fun darkPastedHighlightsAreSoftenedButStayOpaque() {
        val soft = NoteColorPalettes.softenForHighlight(0xFF1A56DBL)
        assertEquals(0xFF, ((soft shr 24) and 0xFF).toInt())
        assertTrue(soft != 0xFF1A56DBL)
        assertEquals(0xFFFFF3BFL, NoteColorPalettes.softenForHighlight(0xFFFFF3BFL))
    }
}
