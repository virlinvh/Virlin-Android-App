package com.virlin.app.ui.notes

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFontsTest {

    @Test fun storedLegacyNamesKeepTheirOriginalRendering() {
        assertSame(FontFamily.SansSerif, NoteFonts.familyFor("Inter"))
        assertSame(FontFamily.Serif, NoteFonts.familyFor("Serif"))
        assertSame(FontFamily.Monospace, NoteFonts.familyFor("Mono"))
        assertNull(NoteFonts.familyFor(null))
        assertNull(NoteFonts.familyFor("Not A Real Font Family"))
    }

    @Test fun catalogOffersWellOverAHundredFontsInEveryStyle() {
        assertTrue(NoteFonts.catalog.size > 1000)
        NoteFontCategory.entries.filter { it != NoteFontCategory.BASIC }.forEach { category ->
            assertTrue("$category", NoteFonts.inCategory(category).size >= 40)
        }
        assertEquals(NoteFonts.catalog.size, NoteFonts.catalog.map { it.name }.toSet().size)
        assertTrue(NoteFonts.catalog.none { it.name == "Inter" || it.name.isBlank() || '|' in it.name })
    }

    @Test fun featuredSectionsResolveToCatalogFonts() {
        val sections = NoteFonts.featured.toMap()
        assertEquals(NoteFonts.basic, sections.getValue("Basic"))
        assertTrue(sections.getValue("Handwritten").any { it.name == "Caveat" })
        assertTrue(sections.getValue("Script & calligraphy").any { it.name == "Great Vibes" })
        assertTrue(sections.getValue("Creative & display").any { it.name == "Permanent Marker" })
        assertTrue(sections.values.sumOf { it.size } > 150)
    }

    @Test fun catalogFamiliesAreCachedSoTextDoesNotReload() {
        val first = NoteFonts.familyFor("Caveat")
        assertNotNull(first)
        assertSame(first, NoteFonts.familyFor("Caveat"))
    }

    @Test fun searchPutsPrefixMatchesFirst() {
        val results = NoteFonts.search("cav").map { it.name }
        assertTrue("Caveat" in results && "Caveat Brush" in results)
        val firstContainsOnly = results.indexOfFirst { !it.startsWith("cav", ignoreCase = true) }
        if (firstContainsOnly >= 0) assertTrue(results.drop(firstContainsOnly).none { it.startsWith("cav", ignoreCase = true) })
        assertTrue(NoteFonts.search("   ").isEmpty())
        assertTrue(NoteFonts.search("mono").any { it.name == "Mono" })
    }
}
