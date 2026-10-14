package com.virlin.app.streams

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.ExecutionLegend
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExecutionDisclosureTest {
    @get:Rule val rule = createComposeRule()
    @Test fun onlyArrowRevealsDetailsAndSecondClickHidesThem() {
        rule.setContent { VirlinTheme { ExecutionLegend(1, 2, 1, 1, "project") } }
        rule.onNodeWithText("3 active").assertIsDisplayed()
        rule.onNodeWithTag("execution_pulse_icon").assertIsDisplayed()
        rule.onNodeWithText("Live modes inside each workstream").assertDoesNotExist()
        rule.onNodeWithTag("execution_details").assertDoesNotExist()
        rule.onNodeWithText("Execution").performTouchInput { click() }
        rule.onNodeWithTag("execution_details").assertDoesNotExist()
        rule.onNodeWithTag("execution_disclosure").performClick()
        listOf("Focus 1", "Running 2", "Multi-run 1", "Review 1").forEach {
            rule.onNodeWithContentDescription(it).assertIsDisplayed()
        }
        rule.onNodeWithTag("execution_disclosure").performClick()
        rule.onNodeWithTag("execution_details").assertDoesNotExist()
    }
}
