package com.virlin.app.now

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.screens.MasterTimerButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProjectTimerButtonUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun projectControlHasItsOwnScopeAndClickTarget() {
        var calls = 0
        compose.setContent { MasterTimerButton(false, false, { calls++ },
            runningColor = Color.Magenta, scopeLabel = "Project A", tag = "project_timer_button") }
        compose.onNodeWithContentDescription("Resume paused timers in Project A").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
        compose.onNodeWithTag("master_timer_button").assertDoesNotExist()
    }
}
