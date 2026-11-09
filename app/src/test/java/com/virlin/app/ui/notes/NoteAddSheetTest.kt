package com.virlin.app.ui.notes

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class NoteAddSheetTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(tools: NoteToolState = NoteToolState()) {
        compose.setContent {
            NoteAddSheet(
                tools = tools,
                onDismiss = { events += "dismiss" },
                onInsert = { events += "insert:$it" },
                onInsertEquation = { events += "equation" },
                onPickImage = { events += "image" },
                onTool = { events += "tool:$it" },
            )
        }
        compose.waitForIdle()
    }

    @Test fun blocksInsertThroughTheSameActionsAsBefore() {
        show()
        listOf("Quote", "Prompt callout", "Code", "Equation", "Toggle", "Divider", "Table", "Image",
            "Paragraph", "Heading 1", "Heading 2", "Heading 3", "Heading 4", "Heading 5",
            "Bulleted list", "Numbered list", "Checklist",
        ).forEach { label ->
            compose.onNodeWithTag("notes_pick_$label").performScrollTo().performClick()
        }
        assertEquals(listOf(
            "insert:${NoteDocBlockType.QUOTE}", "insert:${NoteDocBlockType.PROMPT}",
            "insert:${NoteDocBlockType.CODE}", "equation", "insert:${NoteDocBlockType.TOGGLE}",
            "insert:${NoteDocBlockType.DIVIDER}", "insert:${NoteDocBlockType.TABLE}", "image",
            "insert:${NoteDocBlockType.PARAGRAPH}", "insert:${NoteDocBlockType.HEADING_1}",
            "insert:${NoteDocBlockType.HEADING_2}", "insert:${NoteDocBlockType.HEADING_3}",
            "insert:${NoteDocBlockType.HEADING_4}", "insert:${NoteDocBlockType.HEADING_5}",
            "insert:${NoteDocBlockType.BULLET}", "insert:${NoteDocBlockType.NUMBERED}",
            "insert:${NoteDocBlockType.CHECKLIST}",
        ), events)
    }

    @Test fun everyRemainingToolbarActionIsInTheToolGrid() {
        val blockTools = setOf(NoteTool.BULLETS, NoteTool.NUMBERING, NoteTool.CHECKLIST, NoteTool.QUOTE,
            NoteTool.PROMPT, NoteTool.TABLE, NoteTool.DIVIDER, NoteTool.IMAGE, NoteTool.TOGGLE, NoteTool.YOUTUBE,
            NoteTool.AUDIO, NoteTool.TEXT_TO_AUDIO)
        show()
        NoteTool.entries.filterNot { it in blockTools }.forEach { tool ->
            compose.onNodeWithTag("notes_add_tool_${tool.name.lowercase()}").performScrollTo().performClick()
        }
        assertEquals(NoteTool.entries.filterNot { it in blockTools }.map { "tool:$it" }, events)
    }

    @Test fun textSizeAdjustsInPlaceWithoutClosingTheSheet() {
        compose.setContent {
            NoteAddSheet(
                tools = NoteToolState(sizeLabel = "18"),
                onDismiss = { events += "dismiss" }, onInsert = {}, onInsertEquation = {}, onPickImage = {},
                onTool = { events += "tool:$it" },
                sizeControl = NoteSizeControl(onStep = { events += "step:$it" }, onEditStart = {}, onSet = {}),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("notes_add_tool_size").performScrollTo()
        compose.onNodeWithTag("notes_add_size_up").performClick()
        compose.onNodeWithTag("notes_add_size_down").performClick()
        assertEquals(listOf("step:1", "step:-1"), events)
    }

    @Test fun toolsFollowTheToolbarEnabledState() {
        show(NoteToolState(enabled = setOf(NoteTool.BOLD)))
        compose.onNodeWithTag("notes_add_tool_italic").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("notes_add_tool_bold").performScrollTo().performClick()
        assertEquals(listOf("tool:${NoteTool.BOLD}"), events)
    }
}
