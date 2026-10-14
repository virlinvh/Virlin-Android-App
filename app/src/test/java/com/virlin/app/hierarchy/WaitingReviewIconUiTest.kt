package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.hierarchy.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WaitingReviewIconUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun waitingRemainsDecorativeThroughAnimation() {
        compose.setContent { ExecutionDial(WorkstreamState.WAITING, Modifier.size(44.dp)) }
        compose.onNodeWithContentDescription("waiting").assertIsDisplayed().assertHasNoClickAction()
        compose.mainClock.advanceTimeBy(3200)
        compose.onNodeWithContentDescription("waiting").assertIsDisplayed()
    }
    @Test fun reviewKeepsAttentionSemantics() {
        compose.setContent { ExecutionDial(WorkstreamState.NEEDS_ATTENTION, Modifier.size(44.dp)) }
        compose.onNodeWithContentDescription("needs attention").assertIsDisplayed().assertHasNoClickAction()
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithContentDescription("needs attention").assertIsDisplayed()
    }
}
