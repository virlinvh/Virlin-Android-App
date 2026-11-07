package com.virlin.app.domain.notedoc

import com.virlin.app.domain.notedoc.rich.RichJson
import com.virlin.app.domain.notedoc.rich.RichNoteAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteListStylesTest {

    private val B = NoteDocBlockType.BULLET
    private val N = NoteDocBlockType.NUMBERED

    @Test fun markersCoverBulletsLettersAndRomanNumerals() {
        assertEquals("•", NoteListStyles.marker(B, null))
        assertEquals("➤", NoteListStyles.marker(B, "arrow"))
        assertEquals("3.", NoteListStyles.marker(N, null, 3))
        assertEquals("3)", NoteListStyles.marker(N, "decimal-paren", 3))
        assertEquals("07.", NoteListStyles.marker(N, "decimal-zero", 7))
        assertEquals("c.", NoteListStyles.marker(N, "lower-alpha", 3))
        assertEquals("AB.", NoteListStyles.marker(N, "upper-alpha", 28))
        assertEquals("iv.", NoteListStyles.marker(N, "lower-roman", 4))
        assertEquals("XLIX.", NoteListStyles.marker(N, "upper-roman", 49))
        // Unknown keys from a newer writer draw the default instead of failing.
        assertEquals("•", NoteListStyles.marker(B, "from-the-future"))
        assertEquals("2.", NoteListStyles.marker(N, "arrow", 2))
    }

    @Test fun defaultsAreStoredAsNothing() {
        assertNull(NoteListStyles.storedKey(B, "disc"))
        assertNull(NoteListStyles.storedKey(N, "decimal"))
        assertNull(NoteListStyles.storedKey(NoteDocBlockType.PARAGRAPH, "arrow"))
        assertEquals("star", NoteListStyles.storedKey(B, "star"))
        assertTrue(NoteListStyles.bullets.size >= 8 && NoteListStyles.numbers.size >= 6)
    }

    @Test fun listGroupIsTheSameLevelRunAndSkipsNestedLines() {
        val blocks = listOf(
            NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "intro"),
            NoteDocBlock("a", N, "a"),
            NoteDocBlock("a1", B, "nested", indent = 1),
            NoteDocBlock("b", N, "b"),
            NoteDocBlock("c", B, "other list"),
            NoteDocBlock("d", N, "new list"),
        )
        assertEquals(listOf(1, 3), NoteListStyles.listGroup(blocks, 3))
        assertEquals(listOf(2), NoteListStyles.listGroup(blocks, 2))
        assertEquals(listOf(5), NoteListStyles.listGroup(blocks, 5))
        assertEquals(emptyList<Int>(), NoteListStyles.listGroup(blocks, 0))
    }

    @Test fun codecKeepsTheStyleAndOldNotesReadUnchanged() {
        val styled = listOf(NoteDocBlock("a", N, "one", listStyle = "upper-roman"))
        assertEquals(styled, VirlinNoteCodec.decode(VirlinNoteCodec.encode(styled)))
        val plain = listOf(NoteDocBlock("a", B, "one"))
        val json = VirlinNoteCodec.encode(plain)
        assertTrue("listStyle" !in json)
        assertEquals(plain, VirlinNoteCodec.decode(json))
    }

    @Test fun richDocumentStoresTheStyleOnTheListAndSplitsDifferentStyles() {
        val legacy = listOf(
            NoteDocBlock("a", B, "one", listStyle = "arrow"),
            NoteDocBlock("b", B, "two", listStyle = "arrow"),
            NoteDocBlock("c", B, "three"),
        )
        val tree = RichNoteAdapter.fromLegacy(legacy)
        assertEquals(legacy, RichNoteAdapter.project(tree))
        val lists = (RichJson.parse(tree) as Map<*, *>)["content"] as List<*>
        assertEquals(2, lists.size)
        assertEquals("arrow", ((lists[0] as Map<*, *>)["attrs"] as Map<*, *>)["listStyle"])
    }

    @Test fun restylingARichListPatchesTheListNodeOnly() {
        val tree = RichNoteAdapter.fromLegacy(listOf(
            NoteDocBlock("a", N, "one"), NoteDocBlock("b", N, "two"),
        ))
        val projected = RichNoteAdapter.project(tree)
        val restyled = projected.map { it.copy(listStyle = "lower-alpha") }
        val merged = RichNoteAdapter.applyBlockEdits(tree, restyled)!!
        assertEquals(restyled, RichNoteAdapter.project(merged))
        val back = RichNoteAdapter.applyBlockEdits(merged, projected)!!
        assertEquals(projected, RichNoteAdapter.project(back))
        // A stale style left on a non-list line is ignored rather than refusing the save.
        val para = RichNoteAdapter.fromLegacy(listOf(NoteDocBlock("p", NoteDocBlockType.PARAGRAPH, "x")))
        val p = RichNoteAdapter.project(para).single()
        assertTrue(RichNoteAdapter.applyBlockEdits(para, listOf(p.copy(listStyle = "arrow"))) != null)
    }
}
