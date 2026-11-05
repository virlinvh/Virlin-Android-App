package com.virlin.app.now

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.screens.MinutePresets
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StillRunningCustomUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun customExpandsInlineAndSavesMinutes() {
        var picked = 0L
        compose.setContent { MinutePresets("running", true) { picked = it } }
        compose.onNodeWithTag("running_3").assertIsDisplayed()
        compose.onNodeWithTag("running_custom").performClick()
        compose.onNodeWithTag("running_custom_save").assertIsNotEnabled()
        compose.onNodeWithTag("running_custom_minutes").performTextInput("25")
        compose.onNodeWithTag("running_custom_save").performClick()
        compose.runOnIdle { assertEquals(25L, picked) }
    }
}
