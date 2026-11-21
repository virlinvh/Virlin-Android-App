package com.virlin.app.ui.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
class NoteAddFanTest {
    @get:Rule val compose = createComposeRule()

    private var clock = 10_000L
    private var sheetOpened = 0

    private fun show() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                NoteAddFan(NoteToolState(), "Heading folding", onTool = {}, onAddBlock = { sheetOpened++ },
                    modifier = Modifier.fillMaxSize(), now = { clock })
            }
        }
        compose.waitForIdle()
    }

    private fun tapPlus(afterMs: Long) {
        clock += afterMs
        compose.onNodeWithTag("notes_add_block").performClick()
        compose.waitForIdle()
    }

    private fun fanShown(): Boolean =
        compose.onAllNodesWithTagCount("notes_add_fan_flashcard") > 0

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    @Test fun tapOpensSecondTapClosesAndAQuickThirdTapOpensTheSheet() {
        show()
        tapPlus(0)
        assertEquals(true, fanShown())
        tapPlus(250)
        assertEquals(false, fanShown())
        assertEquals(0, sheetOpened)
        tapPlus(250)
        assertEquals(1, sheetOpened)
        assertEquals(false, fanShown())
    }

    @Test fun aPauseBeforeTheThirdTapJustOpensTheFanAgain() {
        show()
        tapPlus(0)
        tapPlus(250)
        tapPlus(ADD_FAN_TAP_RUN_MS + 400)
        assertEquals(0, sheetOpened)
        assertEquals(true, fanShown())
        tapPlus(250)
        assertEquals(false, fanShown())
        assertEquals(0, sheetOpened)
    }

    @Test fun headingFoldKeepsTheFanOpenUntilTheCycleExpandsEverythingAgain() {
        // Same stepping as the notes screen: show controls, fold H2, fold H1, expand all.
        val levels = listOf(2, 1)
        var step = -1
        val tools = mutableListOf<NoteTool>()
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                NoteAddFan(NoteToolState(), "Heading folding",
                    onTool = { tool ->
                        tools += tool
                        if (tool == NoteTool.HEADING_FOLD) step = if (step < levels.size) step + 1 else -1
                    },
                    onAddBlock = { sheetOpened++ }, modifier = Modifier.fillMaxSize(),
                    keepOpenAfter = { it == NoteTool.HEADING_FOLD && step < levels.size }, now = { clock })
            }
        }
        compose.waitForIdle()
        tapPlus(0)
        repeat(3) {
            compose.onNodeWithTag("notes_add_fan_heading_fold").performClick()
            compose.waitForIdle()
            assertEquals("fan stays out on fold tap ${it + 1}", true, fanShown())
        }
        compose.onNodeWithTag("notes_add_fan_heading_fold").performClick()
        compose.waitForIdle()
        assertEquals(-1, step)
        assertEquals(false, fanShown())
        assertEquals(4, tools.size)

        tapPlus(ADD_FAN_TAP_RUN_MS + 400)
        compose.onNodeWithTag("notes_add_fan_flashcard").performClick()
        compose.waitForIdle()
        assertEquals("other tools still close the fan", false, fanShown())
        assertEquals(0, sheetOpened)
    }

    @Test fun screenReadersOpenTheSheetWithAnAction() {
        show()
        val action = compose.onNodeWithTag("notes_add_block").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Add block" }
        compose.runOnIdle { action.action() }
        assertEquals(1, sheetOpened)
    }
}
