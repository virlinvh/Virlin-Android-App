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
    @Test fun fourColumnRanksAndInlineReturnPolicySave() {
        var saved: com.virlin.app.domain.attention.PriorityScope? = null
        compose.setContent {
            NeedsYouControlSheet(stream = WorkStream(id = "s", title = "Task", subtitle = "Task", projectId = null,
                state = com.virlin.app.model.StreamState.NEEDS_YOU), position = 2, total = 8,
                initialTab = NeedsYouControlTab.PRIORITY, onMoveToPosition = {},
                onSavePreference = { _, scope, done -> saved = scope; done(true) },
                onAction = {}, onDismiss = {})
        }
        val first = compose.onNodeWithTag(priorityPositionTag(1)).fetchSemanticsNode().boundsInRoot
        val fourth = compose.onNodeWithTag(priorityPositionTag(4)).fetchSemanticsNode().boundsInRoot
        val fifth = compose.onNodeWithTag(priorityPositionTag(5)).fetchSemanticsNode().boundsInRoot
        val custom = compose.onNodeWithTag("needs_you_custom_position").fetchSemanticsNode().boundsInRoot
        assertEquals(first.top, fourth.top)
        assertEquals(fifth.top, custom.top)
        assertTrue(fifth.top > first.top)
        compose.onNodeWithTag("needs_you_policy_ten").performScrollTo().performClick()
        compose.onNodeWithTag("needs_you_policy_discard").assertExists()
        compose.onNodeWithTag("needs_you_policy_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(com.virlin.app.domain.attention.PriorityScope.NextReturns(10), saved) }
        compose.onNodeWithText("✓ Saved").assertExists()
    }
    @Test fun scrollingCheckChoicesKeepsHeaderAndTabsFixed() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                NeedsYouControlSheet(stream = WorkStream(id = "s", title = "Task", subtitle = "Task", projectId = null,
                    state = com.virlin.app.model.StreamState.NEEDS_YOU), position = 1, total = 3,
                    kind = AttentionKind.CHECK_DUE, initialTab = NeedsYouControlTab.CHECK,
                    onMoveToPosition = {}, onAction = {}, onDismiss = {})
            }
        }
        val header = compose.onNodeWithTag(NeedsYouControlHeaderTag).fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag(NeedsYouControlCheckTabTag).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(NeedsYouControlOptionsTag).performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(header, compose.onNodeWithTag(NeedsYouControlHeaderTag).fetchSemanticsNode().boundsInRoot)
        assertEquals(tabs, compose.onNodeWithTag(NeedsYouControlCheckTabTag).fetchSemanticsNode().boundsInRoot)
    }
    @Test fun tappingValidPositionMovesImmediatelyAndShowsSuccess() {
        val position = androidx.compose.runtime.mutableStateOf(1)
        compose.setContent {
            NeedsYouControlSheet(stream = WorkStream(id = "s", title = "Task", subtitle = "Task", projectId = null,
                state = com.virlin.app.model.StreamState.NEEDS_YOU), position = position.value, total = 8,
                initialTab = NeedsYouControlTab.PRIORITY, onMoveToPosition = { position.value = it },
                onAction = {}, onDismiss = {})
        }
        compose.onNodeWithTag(priorityPositionTag(3)).performClick()
        compose.onNodeWithTag("needs_you_position_saved").assertExists()
        assertEquals(3, position.value)
    }
    @Test fun externalCheckOffersCompletedWithoutStartingFocus() {
        val actions = mutableListOf<NeedsYouControlAction>()
        compose.setContent {
            NeedsYouControlSheet(stream = WorkStream(id = "s", title = "Task", subtitle = "Task", projectId = null,
                state = com.virlin.app.model.StreamState.NEEDS_YOU), position = 1, total = 1,
                kind = AttentionKind.CHECK_DUE, initialTab = NeedsYouControlTab.CHECK,
                onMoveToPosition = {}, onAction = { actions += it }, onDismiss = {})
        }
        val complete = compose.onNodeWithTag("needs_you_control_complete").fetchSemanticsNode().boundsInRoot
        val notNow = compose.onNodeWithTag(NeedsYouControlNotNowTag).fetchSemanticsNode().boundsInRoot
        val blocked = compose.onNodeWithTag(NeedsYouControlBlockTag).fetchSemanticsNode().boundsInRoot
        assertTrue(notNow.bottom <= complete.top)
        assertTrue(complete.bottom <= blocked.top)
        compose.onNodeWithTag("needs_you_control_complete").assertIsDisplayed().performClick()
        assertEquals(listOf(NeedsYouControlAction.Complete), actions)
    }
    @Test fun onlyAvailablePositionsAppearAndCustomRejectsInvalidInput() {
        var moved: Int? = null
        var dismissed = false
        val position = androidx.compose.runtime.mutableStateOf(1)
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
            NeedsYouControlSheet(stream = WorkStream(id = "s", title = "Task", subtitle = "Task", projectId = null,
                state = com.virlin.app.model.StreamState.NEEDS_YOU), position = position.value, total = 12,
                initialTab = NeedsYouControlTab.PRIORITY, onMoveToPosition = { moved = it; position.value = it },
                onAction = {}, onDismiss = { dismissed = true })
            }
            Text("Now Projects Pulse Apps", Modifier.fillMaxWidth().height(70.dp))
            }
        }
        compose.onNodeWithText("Now Projects Pulse Apps").assertIsDisplayed()
        val popup = compose.onNodeWithTag(NeedsYouControlSheetTag).fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText("Now Projects Pulse Apps").fetchSemanticsNode().boundsInRoot
        assertTrue(popup.bottom <= footer.top)
        compose.onNodeWithTag("needs_you_policy_always").assertExists()
        compose.onNodeWithTag(NeedsYouControlCheckTabTag).performClick()
        compose.waitForIdle()
        assertEquals(popup, compose.onNodeWithTag(NeedsYouControlSheetTag).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag(NeedsYouControlPriorityTabTag).performClick()
        compose.waitForIdle()
        assertEquals(popup, compose.onNodeWithTag(NeedsYouControlSheetTag).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag(priorityPositionTag(7)).assertExists()
        compose.onNodeWithTag(priorityPositionTag(8)).assertDoesNotExist()
        compose.onNodeWithTag("needs_you_apply_position").assertDoesNotExist()
        compose.onNodeWithTag(priorityPositionTag(7)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(7, moved) }
        assertEquals(popup, compose.onNodeWithTag(NeedsYouControlSheetTag).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("needs_you_policy_ten").assertExists()
        compose.onNodeWithTag("needs_you_custom_position").performScrollTo().performClick()
        val input = compose.onNodeWithTag("needs_you_custom_position_input")
        input.performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("needs_you_apply_position").assertIsNotEnabled()
        input.performTextReplacement("999999999999999")
        compose.onNodeWithTag("needs_you_apply_position").assertIsNotEnabled()
        input.performTextReplacement("12")
        compose.runOnIdle { assertEquals(7, moved) }
        compose.onNodeWithTag("needs_you_apply_position").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(12, moved); assertFalse(dismissed) }
    }

    @Test fun twoWaitingItemsShowSevenAndCustomButDisableUnavailablePositions() {
        val position = androidx.compose.runtime.mutableStateOf(2)
        compose.setContent {
            NeedsYouControlSheet(stream = WorkStream(id = "s", title = "Task", subtitle = "Task", projectId = null,
                state = com.virlin.app.model.StreamState.NEEDS_YOU), position = position.value, total = 2,
                initialTab = NeedsYouControlTab.PRIORITY, onMoveToPosition = { position.value = it },
                onAction = {}, onDismiss = {})
        }
        compose.onNodeWithTag(priorityPositionTag(3)).assertExists().assertIsNotEnabled()
        compose.onNodeWithTag(priorityPositionTag(7)).assertExists().assertIsNotEnabled()
        compose.onNodeWithTag("needs_you_custom_position").assertExists()
        compose.onNodeWithTag(priorityPositionTag(1)).performClick()
        compose.onNodeWithTag("needs_you_position_pending").assertDoesNotExist()
        compose.onNodeWithTag("needs_you_position_saved").assertExists()
        assertEquals(1, position.value)
    }
}
