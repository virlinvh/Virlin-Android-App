package com.virlin.app.ui.notes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntRect
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
class NoteParagraphStylePopupTest {
    @get:Rule val compose = createComposeRule()

    private val picked = mutableListOf<NoteDocBlockType>()
    private var dismissed = 0

    private fun show(anchor: IntRect?, current: NoteDocBlockType? = NoteDocBlockType.PARAGRAPH) {
        compose.setContent {
            NoteParagraphStylePopup(anchor, current, onPick = { picked += it }, onDismiss = { dismissed++ })
        }
        compose.waitForIdle()
    }

    private val labels = listOf("Normal text", "Heading 1", "Heading 2", "Heading 3", "Heading 4",
        "Heading 5", "Quote", "Code", "Prompt")

    @Test fun everyStyleIsOfferedAndPicksItsType() {
        show(IntRect(40, 300, 360, 400))
        labels.forEach { compose.onNodeWithTag("notes_pick_$it").assertIsDisplayed().performClick() }
        assertEquals(listOf(
            NoteDocBlockType.PARAGRAPH, NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2,
            NoteDocBlockType.HEADING_3, NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5,
            NoteDocBlockType.QUOTE, NoteDocBlockType.CODE, NoteDocBlockType.PROMPT,
        ), picked)
        assertEquals(0, dismissed)
    }

    @Test fun currentStyleIsMarkedSelected() {
        show(anchor = null, current = NoteDocBlockType.HEADING_4)
        compose.onNodeWithTag("notes_pick_Heading 4").assertIsSelected()
        compose.onNodeWithTag("notes_pick_Normal text").assertIsNotSelected()
    }
}
