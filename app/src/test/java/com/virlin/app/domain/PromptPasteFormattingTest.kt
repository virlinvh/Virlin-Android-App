package com.virlin.app.domain

import com.virlin.app.domain.prompt.PromptContentParser
import com.virlin.app.domain.prompt.PromptDocumentCodec
import com.virlin.app.domain.prompt.ResponseSegment
import com.virlin.app.domain.prompt.importedPromptText
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.PromptDocument
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptPasteFormattingTest {
    @Test fun plainClipboardKeepsWhitespaceExactly() {
        val text = "  Heading\n\n  - First point\n\nParagraph  with  spaces\n"
        assertEquals(text, importedPromptText(null, text))
        assertEquals(text, importedPromptText("<div>Heading</div>", text))
    }

    @Test fun richClipboardKeepsHeadingBoldAndListSemantics() {
        val text = importedPromptText("<h2>Important</h2><p><strong>Bold</strong> words</p><ul><li>One</li><li>Two</li></ul>", "Important Bold words One Two")
        assertTrue(text.contains("## Important"))
        assertTrue(text.contains("**Bold** words"))
        assertTrue(text.contains("- One"))
        assertTrue(text.contains("- Two"))
    }

    @Test fun responseSegmentationDoesNotTrimSurroundingParagraphSpacing() {
        val response = "Paragraph\n\n```kotlin\nval x = 1\n```\n\nNext paragraph"
        val segments = PromptContentParser.responseSegments(response)
        assertEquals("Paragraph\n\n", (segments[0] as ResponseSegment.Prose).text)
        assertEquals("\n\nNext paragraph", (segments[2] as ResponseSegment.Prose).text)
    }

    @Test fun formattedPasteSurvivesPromptSaveAndReload() {
        val pasted = importedPromptText("<h1>Title</h1><p><b>Important</b></p><ul><li>First</li></ul>", "")
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val document = PromptDocument("p", "c", null,
            blocks = listOf(NoteBlock("b", NoteBlockType.TEXT, plainText = pasted)),
            sourceText = pasted, responseText = pasted, createdAt = now, updatedAt = now)
        val loaded = PromptDocumentCodec.decodeInto(
            PromptDocumentCodec.Meta("p", "c", null, null, null, now, now),
            PromptDocumentCodec.encodeDocument(document))
        assertEquals(pasted, loaded.sourceText)
        assertEquals(pasted, loaded.responseText)
    }
}
