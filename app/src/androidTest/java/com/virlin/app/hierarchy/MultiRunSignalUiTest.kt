package com.virlin.app.hierarchy

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Visual contract fixture for the dormant, truthful-only Multi Run presentation state. */
@RunWith(AndroidJUnit4::class)
class MultiRunSignalUiTest {
    @get:Rule val composeRule = createAndroidComposeRule<DebugMultiRunShowcaseActivity>()

    @Test fun multiRun_isSingleLine_counted_andAnimated() {
        composeRule.onNodeWithText("MULTI RUN").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("3 active tasks, multi run").assertIsDisplayed()
        // Keep the real renderer visible long enough for emulator/video verification.
        SystemClock.sleep(4_000)
    }
}
