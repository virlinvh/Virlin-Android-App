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
    @Test fun taskBulkKeepsOriginalPopupAndPreviewsChildren() = checkBulk(null)
    @Test fun nestedSubtaskBulkKeepsOriginalPopupAndPreviewsChildren() = checkBulk("nested-parent")

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
        compose.onNodeWithTag(AddTaskTitleTag).performTextInput("Retained name")
        compose.onNodeWithText("Bulk").performClick()
        compose.onNodeWithTag("add_task_dialog").assertExists()
        compose.onNodeWithText(heading).assertExists()
        val after = compose.onNodeWithTag("add_task_dialog").fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, after.left, .1f)
        assertEquals(before.width, after.width, .1f)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput(
            """{"version":2,"items":[{"name":"First","children":[{"name":"Child"}]}]}""")
        compose.onNodeWithText("Preview").performScrollTo().performClick()
        compose.onNodeWithText("2 new items · hierarchy shown below").assertExists()
        compose.onNodeWithText("Edit input").performScrollTo().performClick()
        compose.onNodeWithText("Single").performScrollTo().performClick()
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
        compose.onNodeWithTag(AddTaskConfirmTag).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(AddTaskTitleTag).performScrollTo().performTextInput("  New task  ")
        compose.onNodeWithTag(AddTaskConfirmTag).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("New task", saved) }
    }
    @Test fun cancelDoesNotSave() {
        var dismissed = false
        var saved = false
        compose.setContent {
            Box(Modifier.size(360.dp, 560.dp)) {
                AddTaskSheet("Parent", StreamExecutionMode.HUMAN, emptyList(), { dismissed = true }, { _, _, _ -> saved = true })
            }
        }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
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
