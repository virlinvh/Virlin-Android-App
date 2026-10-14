package com.virlin.app.now

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.screens.MasterTimerButton
import com.virlin.app.ui.screens.NowDaySummary
import com.virlin.app.ui.screens.NowDatePillTag
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MasterTimerButtonUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun oneTapAndBusyProtection() {
        compose.mainClock.autoAdvance = false
        var calls = 0
        var busy by mutableStateOf(false)
        compose.setContent { MasterTimerButton(true, busy, onPause = { calls++; busy = true }) }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("master_timer_button").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("master_timer_button").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, calls) }
    }
    @Test fun grayPausedControlStillHasAccessibleAction() {
        compose.setContent { MasterTimerButton(false, false, onPause = {}) }
        compose.onNodeWithContentDescription("Resume master-paused timers").assertIsEnabled().assertHasClickAction()
    }
    @Test fun timerSitsToRightAndDoesNotChangeDateCardSize() {
        var control by mutableStateOf(false)
        compose.setContent { NowDaySummary(modifier = Modifier.width(360.dp), onPauseAll = if (control) ({}) else null) }
        val original = compose.onNodeWithTag(NowDatePillTag).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { control = true }
        val date = compose.onNodeWithTag(NowDatePillTag).fetchSemanticsNode().boundsInRoot
        val timer = compose.onNodeWithTag("master_timer_button").fetchSemanticsNode().boundsInRoot
        assertEquals(original.width, date.width, .1f)
        assertEquals(original.height, date.height, .1f)
        assertTrue(timer.left >= date.right)
    }
}
