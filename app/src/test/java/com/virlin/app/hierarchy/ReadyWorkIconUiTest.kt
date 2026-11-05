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
class ReadyWorkIconUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun readyKeepsStatusSemanticsAndHasNoCounterOrNewAction() {
        compose.setContent { ExecutionDial(WorkstreamState.READY, Modifier.size(44.dp)) }
        compose.onNodeWithContentDescription("ready").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("0").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithContentDescription("ready").assertIsDisplayed()
    }
    @Test fun completeIconIsUnchanged() {
        compose.setContent { ExecutionDial(WorkstreamState.COMPLETE, Modifier.size(44.dp)) }
        compose.onNodeWithText("✓").assertIsDisplayed()
        compose.onNodeWithContentDescription("ready").assertDoesNotExist()
    }
}
