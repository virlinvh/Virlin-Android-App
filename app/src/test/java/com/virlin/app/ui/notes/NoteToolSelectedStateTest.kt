package com.virlin.app.ui.notes

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A tool that is on reads as selected, and reads as not selected once it is off again. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class NoteToolSelectedStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun activeToolsAreSelectedAndOthersAreNot() {
        val on = setOf(NoteTool.SELECT_DOCUMENT, NoteTool.BOLD, NoteTool.WORD_COUNT, NoteTool.PARAGRAPH)
        compose.setContent { NoteFormattingRail(NoteToolState(active = on), onTool = {}) }
        on.forEach { compose.onNodeWithTag("notes_tool_${it.name.lowercase()}", useUnmergedTree = true).assertIsSelected() }
        listOf(NoteTool.ITALIC, NoteTool.ARRANGE_BLOCKS, NoteTool.UNDO).forEach {
            compose.onNodeWithTag("notes_tool_${it.name.lowercase()}", useUnmergedTree = true).assertIsNotSelected()
        }
    }
}
