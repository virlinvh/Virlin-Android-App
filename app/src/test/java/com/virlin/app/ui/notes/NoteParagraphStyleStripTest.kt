package com.virlin.app.ui.notes

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.virlin.app.domain.notedoc.NoteDocBlockType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class NoteParagraphStyleStripTest {
    @get:Rule val compose = createComposeRule()

    private val picked = mutableListOf<String>()
    private var closed = 0

    private fun show(current: NoteDocBlockType? = NoteDocBlockType.PARAGRAPH, toggleHeading: Boolean = false) {
        compose.setContent {
            NoteParagraphStyleStrip(NoteParagraphStyleStripState(
                current = current, currentToggleHeading = toggleHeading,
                onPickStyle = { type, toggle -> picked += "$type${if (toggle) "+toggle" else ""}" },
                onPickBlock = { picked += "block:$it" },
                onClose = { closed++ },
            ))
        }
        compose.waitForIdle()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag("notes_paragraph_styles").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    @Test fun everyStyleToggleVariantAndBlockIsOfferedInOneScrollingStrip() {
        show()
        val text = listOf("Normal text", "Heading 1", "Heading 2", "Heading 3", "Heading 4", "Heading 5")
        text.forEach { tap("notes_pick_$it") }
        text.forEach { tap("notes_pick_toggle_$it") }
        listOf("Quote", "Code", "Prompt").forEach { tap("notes_pick_$it") }
        assertEquals(listOf(
            "PARAGRAPH", "HEADING_1", "HEADING_2", "HEADING_3", "HEADING_4", "HEADING_5",
            "PARAGRAPH+toggle", "HEADING_1+toggle", "HEADING_2+toggle", "HEADING_3+toggle",
            "HEADING_4+toggle", "HEADING_5+toggle",
            "block:QUOTE", "block:CODE", "block:PROMPT",
        ), picked)
        assertEquals(0, closed)
    }

    @Test fun currentStyleIsMarkedIncludingToggleHeadings() {
        show(NoteDocBlockType.HEADING_2, toggleHeading = true)
        compose.onNodeWithTag("notes_paragraph_styles").performScrollToNode(hasTestTag("notes_pick_toggle_Heading 2"))
        compose.onNodeWithTag("notes_pick_toggle_Heading 2").assertIsSelected()
        compose.onNodeWithTag("notes_paragraph_styles").performScrollToNode(hasTestTag("notes_pick_Heading 2"))
        compose.onNodeWithTag("notes_pick_Heading 2").assertIsNotSelected()
    }

    @Test fun closeButtonCloses() {
        show()
        compose.onNodeWithTag("notes_paragraph_close").performClick()
        assertEquals(1, closed)
    }
}
