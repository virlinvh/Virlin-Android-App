package com.virlin.app.now

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.screens.*
import com.virlin.app.model.WorkStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NeedsYouPositionPickerUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun presetsRequireConfirmationAndCustomRejectsInvalidInput() {
        var moved: Int? = null
        var dismissed = false
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
            NeedsYouControlSheet(stream = WorkStream(id = "s", title = "Task", subtitle = "Task", projectId = null,
                state = com.virlin.app.model.StreamState.NEEDS_YOU), position = 1, total = 4,
                initialTab = NeedsYouControlTab.PRIORITY, onMoveToPosition = { moved = it },
                onAction = {}, onDismiss = { dismissed = true })
            }
            Text("Now Projects Pulse Apps", Modifier.fillMaxWidth().height(70.dp))
            }
        }
        compose.onNodeWithText("Now Projects Pulse Apps").assertIsDisplayed()
        val popup = compose.onNodeWithTag(NeedsYouControlSheetTag).fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText("Now Projects Pulse Apps").fetchSemanticsNode().boundsInRoot
        assertTrue(popup.bottom <= footer.top)
        compose.onNodeWithTag(NeedsYouControlPolicyTag).assertDoesNotExist()
        compose.onNodeWithTag(NeedsYouControlCheckTabTag).performClick()
        compose.waitForIdle()
        assertEquals(popup.height, compose.onNodeWithTag(NeedsYouControlSheetTag).fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.onNodeWithTag(NeedsYouControlPriorityTabTag).performClick()
        compose.onNodeWithTag(priorityPositionTag(8)).assertExists()
        compose.onNodeWithTag("needs_you_apply_position").assertIsNotEnabled()
        compose.onNodeWithTag(priorityPositionTag(7)).performScrollTo().performClick()
        compose.onNodeWithTag(NeedsYouControlPolicyTag).assertExists()
        compose.runOnIdle { assertNull(moved) }
        compose.onNodeWithTag("needs_you_custom_position").performScrollTo().performClick()
        compose.onNodeWithTag("needs_you_apply_position").assertIsNotEnabled()
        val input = compose.onNodeWithTag("needs_you_custom_position_input")
        input.performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("needs_you_apply_position").assertIsNotEnabled()
        input.performTextReplacement("999999999999999")
        compose.onNodeWithTag("needs_you_apply_position").assertIsNotEnabled()
        input.performTextReplacement("12")
        compose.runOnIdle { assertNull(moved) }
        compose.onNodeWithTag("needs_you_apply_position").performClick()
        compose.runOnIdle { assertEquals(12, moved); assertTrue(dismissed) }
    }
}
