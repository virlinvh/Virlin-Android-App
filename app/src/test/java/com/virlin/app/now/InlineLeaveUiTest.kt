package com.virlin.app.now

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.screens.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineLeaveUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun customStaysInLeaveDialogAndSavesExactMinutes() {
        var openedCustom = 0
        var saved = 0L
        val intents = java.lang.reflect.Proxy.newProxyInstance(AttentionIntents::class.java.classLoader,
            arrayOf(AttentionIntents::class.java)) { _, method, args ->
            when (method.name) {
                "openCustom" -> { openedCustom++; null }
                "customMinutes" -> { saved = args!![2] as Long; true }
                else -> null
            }
        } as AttentionIntents
        compose.setContent { NowChooserDialog(NowViewModel.Chooser.Leave("task"), intents) }
        compose.onNodeWithTag("chooser_custom").performClick()
        compose.onNodeWithText("Return in:").assertIsDisplayed()
        compose.onNodeWithTag("leave_3m").assertIsDisplayed()
        compose.onNodeWithTag("chooser_leave_no_reminder").assertIsDisplayed()
        compose.onNodeWithTag("chooser_custom_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("chooser_custom_minutes").performTextInput("25")
        compose.onNodeWithTag("chooser_custom_confirm").performClick()
        compose.runOnIdle { assertEquals(0, openedCustom); assertEquals(25L, saved) }
    }
}
