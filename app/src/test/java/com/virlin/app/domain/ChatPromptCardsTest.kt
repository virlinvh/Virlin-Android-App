package com.virlin.app.domain

import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NotePasteParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptCardsTest {
    private val body1 = "I am testing a rich-text editor that supports formatted text."
    private val body2 = "Generate one short example for each programming language below."
    private val body3 = "Preserve indentation, spacing, symbols and formatting. Do not execute the code."

    @Test fun chatGptPromptCardBecomesOnePromptBlockWithTitle() {
        val pasted = NotePasteParser.parse(
            "## 1. Main testing prompt\n\nCOPY THIS INTO YOUR APPLICATION\n\n" +
                "$body1\n\n$body2\n\n$body3\n\nCopy prompt\n\n" +
                "## 2. Programming language test blocks\n\n```python\nprint('hi')\n```")
        assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.PROMPT,
            NoteDocBlockType.HEADING_2, NoteDocBlockType.CODE), pasted.map { it.type })
        val prompt = pasted[1]
        assertEquals("COPY THIS INTO YOUR APPLICATION", prompt.promptTitle)
        assertEquals("$body1\n\n$body2\n\n$body3", prompt.text)
        assertFalse(pasted.any { it.text.trim().equals("Copy prompt", ignoreCase = true) })
        assertEquals("print('hi')", pasted[3].text)
    }

    @Test fun htmlPromptCardWithButtonIsRecognised() {
        val html = "<h2>1. Main testing prompt</h2><div><div>Copy this into your application</div>" +
            "<p>$body1</p><p>$body2</p><p>$body3</p><button>Copy prompt</button></div>" +
            "<h2>2. Programming language test blocks</h2>"
        val plain = "1. Main testing prompt\n\nCopy this into your application\n\n" +
            "$body1\n\n$body2\n\n$body3\n\nCopy prompt\n\n2. Programming language test blocks"
        val pasted = NotePasteParser.parse(plain, html)
        val prompt = pasted.single { it.type == NoteDocBlockType.PROMPT }
        assertEquals("Copy this into your application", prompt.promptTitle)
        assertTrue(prompt.text.startsWith(body1))
        assertTrue(prompt.text.endsWith(body3))
        assertFalse(pasted.any { it.text.trim().equals("Copy prompt", ignoreCase = true) })
        assertTrue(pasted.last().text.contains("Programming language test blocks"))
    }

    @Test fun promptWithoutHeaderAndWithListKeepsAllText() {
        val pasted = NotePasteParser.parse(
            "## Prompt\n\nWrite a summary of the report.\n\n- keep it short\n- use plain words\n\nCopy prompt")
        val prompt = pasted.single { it.type == NoteDocBlockType.PROMPT }
        assertNull(prompt.promptTitle)
        assertEquals("Write a summary of the report.\n\n- keep it short\n- use plain words", prompt.text)
        assertEquals(NoteDocBlockType.HEADING_2, pasted.first().type)
    }

    @Test fun introSentenceBeforeHeaderStaysOutsideThePrompt() {
        val pasted = NotePasteParser.parse(
            "Here is the prompt you can use:\n\nMain prompt\n\n$body1\n\nCopy prompt")
        assertEquals(listOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.PROMPT), pasted.map { it.type })
        assertEquals("Here is the prompt you can use:", pasted[0].text)
        assertEquals("Main prompt", pasted[1].promptTitle)
        assertEquals(body1, pasted[1].text)
    }

    @Test fun codeAndEquationsAboveTheMarkerAreNeverAbsorbed() {
        val pasted = NotePasteParser.parse(
            "```kotlin\nval answer = 42\n```\n\n$$\nx^2 + y^2 = z^2\n$$\n\n$body1\n\nCopy prompt")
        assertEquals(listOf(NoteDocBlockType.CODE, NoteDocBlockType.CODE, NoteDocBlockType.PROMPT),
            pasted.map { it.type })
        assertEquals("val answer = 42", pasted[0].text)
        assertTrue(pasted[1].text.contains("x^2 + y^2"))
        assertEquals(body1, pasted[2].text)
    }

    @Test fun markerWithNothingToWrapStaysAsWritten() {
        val pasted = NotePasteParser.parse("## Heading\n\nCopy prompt")
        assertEquals(listOf(NoteDocBlockType.HEADING_2, NoteDocBlockType.PARAGRAPH), pasted.map { it.type })
        assertEquals("Copy prompt", pasted[1].text)
    }

    @Test fun pastesWithoutTheMarkerAreUnchanged() {
        val source = "# Title\n\nA **bold** sentence.\n\nShort label\n\nAnother paragraph.\n\n" +
            "```prompt\nExisting fenced prompt\n```\n\n$$\nE = mc^2\n$$"
        val pasted = NotePasteParser.parse(source)
        assertEquals(listOf(NoteDocBlockType.HEADING_1, NoteDocBlockType.PARAGRAPH,
            NoteDocBlockType.PARAGRAPH, NoteDocBlockType.PARAGRAPH, NoteDocBlockType.PROMPT,
            NoteDocBlockType.CODE), pasted.map { it.type })
        assertEquals("Existing fenced prompt", pasted[4].text)
        assertEquals("Short label", pasted[2].text)
    }
}
