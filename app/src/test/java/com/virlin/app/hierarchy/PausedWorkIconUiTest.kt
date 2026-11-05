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
class PausedWorkIconUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pausedRemainsDecorativeThroughHaloCycle() {
        compose.setContent { ExecutionDial(WorkstreamState.PAUSED, Modifier.size(44.dp)) }
        compose.onNodeWithContentDescription("paused").assertIsDisplayed().assertHasNoClickAction()
        compose.mainClock.advanceTimeBy(3600)
        compose.onNodeWithContentDescription("paused").assertIsDisplayed().assertHasNoClickAction()
    }
}
