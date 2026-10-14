package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddTaskDialogUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun keyboardSizedViewportKeepsSingleActionsVisibleAndDraftIntact() {
        val available = mutableStateOf(560.dp)
        var saved = ""
        compose.setContent {
            Box(Modifier.width(320.dp).height(available.value)) {
                TaskCreationCard("Parent", "Add subtask", {}, { saved = it },
                    modeSelector = { Text("Single · Bulk", Modifier.height(48.dp)) },
                    options = { Text("Placement · Execution", Modifier.height(48.dp)) })
            }
        }
        compose.onNodeWithTag(AddTaskTitleTag).performTextInput("Draft subtask")
        compose.runOnIdle { available.value = 240.dp }
        compose.onNodeWithTag("task_creation_cancel").assertIsDisplayed()
        compose.onNodeWithTag(AddTaskConfirmTag).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("Draft subtask", saved) }
        compose.runOnIdle { available.value = 560.dp }
        compose.onNodeWithTag(AddTaskTitleTag).assertTextContains("Draft subtask")
    }
    @Test fun taskBulkKeepsOriginalPopupAndPreviewsChildren() = checkBulk(null)
    @Test fun nestedSubtaskBulkKeepsOriginalPopupAndPreviewsChildren() = checkBulk("nested-parent")

    @Test fun bulkCreationUsesExplicitExecutionSelection() {
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                AddTaskSheet("Parent", StreamExecutionMode.HUMAN, emptyList(), {}, { _, _, _ -> },
                    bulkDestination = com.virlin.app.domain.action.CreateTask("", workStreamId = "stream"))
            }
        }
        compose.onNodeWithText("Inherit · Human").performClick()
        compose.onNodeWithText("External").performClick()
        compose.onNodeWithText("Bulk").performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(
            """{"version":2,"items":[{"name":"First"}]}""")
        compose.onNodeWithText("Preview").performClick()
        compose.onNodeWithText("Preview · 1 item").assertIsDisplayed()
        compose.onNodeWithText("Execution: External").assertIsDisplayed()
        compose.onNodeWithTag("add_task_dialog").assertExists()
    }

    private fun checkBulk(parentId: String?) {
        val heading = if (parentId == null) "Add task" else "Add subtask"
        compose.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                AddTaskSheet("Parent", StreamExecutionMode.HUMAN, emptyList(), {}, { _, _, _ -> },
                    heading = heading,
                    bulkDestination = com.virlin.app.domain.action.CreateTask("", workStreamId = "stream", parentTaskId = parentId))
            }
        }
        val before = compose.onNodeWithTag("add_task_dialog").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("add_task_overlay").fetchSemanticsNode().boundsInRoot
        assertTrue("Creation should leave room above and below", before.top > viewport.top && before.bottom < viewport.bottom)
        assertEquals(viewport.center.y, before.center.y, 2f)
        compose.onNodeWithTag(AddTaskTitleTag).performTextInput("Retained name")
        compose.onNodeWithText("Bulk").performClick()
        compose.onNodeWithTag("add_task_dialog").assertExists()
        compose.onNodeWithText(heading).assertExists()
        val after = compose.onNodeWithTag("add_task_dialog").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, after.left, .1f)
        assertEquals(before.width, after.width, .1f)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(
            """{"version":2,"items":[{"name":"First","children":[{"name":"Child"}]}]}""")
        compose.onNodeWithText("Preview").performClick()
        compose.onNodeWithText("Preview · 2 items").assertExists()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Single").performClick()
        compose.onNodeWithTag(AddTaskTitleTag).assertTextContains("Retained name")
    }
    @Test fun compactViewportKeepsFooterAndValidatesThenSaves() {
        var saved = ""
        compose.setContent {
            Column(Modifier.width(320.dp).height(520.dp)) {
                Box(Modifier.weight(1f)) {
                    AddTaskSheet("Parent", StreamExecutionMode.HUMAN, emptyList(), {},
                        { title, after, mode -> saved = title; assertNull(after); assertEquals(StreamExecutionMode.INHERIT, mode) })
                }
                Text("Now · Projects · Pulse · Apps", Modifier.height(80.dp).testTag("test_footer"))
            }
        }
        compose.onNodeWithTag("test_footer").assertIsDisplayed()
        val dialog = compose.onNodeWithTag("add_task_dialog").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("test_footer").fetchSemanticsNode().boundsInRoot
        assertTrue(dialog.bottom <= footer.top)
        compose.onNodeWithTag(AddTaskConfirmTag).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(AddTaskTitleTag).performScrollTo().performTextInput("  New task  ")
        compose.onNodeWithTag(AddTaskConfirmTag).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("New task", saved) }
    }
    @Test fun keyboardHeightViewportKeepsActionsOutsideScrollableOptions() {
        compose.setContent {
            Box(Modifier.size(320.dp, 300.dp)) {
                AddTaskSheet("Parent", StreamExecutionMode.HUMAN,
                    listOf(StreamTaskUi("sibling", null, "Sibling", 0)), {}, { _, _, _ -> })
            }
        }
        val form = compose.onNodeWithTag("task_creation_form").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("task_creation_cancel").fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithTag(AddTaskConfirmTag).fetchSemanticsNode().boundsInRoot
        assertTrue(form.bottom <= cancel.top)
        assertTrue(form.bottom <= add.top)
        compose.onNodeWithTag("task_creation_cancel").assertIsDisplayed()
        compose.onNodeWithTag(AddTaskConfirmTag).assertIsDisplayed()
    }
    @Test fun cancelDoesNotSave() {
        var dismissed = false
        var saved = false
        compose.setContent {
            Box(Modifier.size(360.dp, 560.dp)) {
                AddTaskSheet("Parent", StreamExecutionMode.HUMAN, emptyList(), { dismissed = true }, { _, _, _ -> saved = true })
            }
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(saved) }
    }
    @Test fun optionalPlacementAndExecutionMenusPreserveSelections() {
        var savedMode: StreamExecutionMode? = null
        var savedAfter: String? = "unset"
        compose.setContent {
            Box(Modifier.size(360.dp, 560.dp)) {
                AddTaskSheet("Parent", StreamExecutionMode.HUMAN,
                    listOf(StreamTaskUi("last", null, "Last", 0)), {},
                    { _, after, mode -> savedAfter = after; savedMode = mode })
            }
        }
        compose.onNodeWithText("After Last").performClick()
        compose.onNodeWithText("At beginning").performClick()
        compose.onNodeWithText("Inherit · Human").performClick()
        compose.onNodeWithText("External").performClick()
        compose.onNodeWithTag(AddTaskTitleTag).performTextInput("Name")
        compose.onNodeWithTag(AddTaskConfirmTag).performClick()
        compose.runOnIdle { assertNull(savedAfter); assertEquals(StreamExecutionMode.EXTERNAL, savedMode) }
    }
}
