package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.virlin.app.ui.screens.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h900dp")
class LeaveChooserDesignTest {
    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()

    private val intents = java.lang.reflect.Proxy.newProxyInstance(AttentionIntents::class.java.classLoader,
        arrayOf(AttentionIntents::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "intents"
            else -> {
                calls += method.name + (args?.drop(1)?.joinToString(",", "(", ")") ?: "")
                if (method.returnType == java.lang.Boolean.TYPE) true else null
            }
        }
    } as AttentionIntents

    private fun show(chooser: NowViewModel.Chooser) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize()) { NowChooserDialog(chooser, intents) } }
        compose.mainClock.advanceTimeBy(500)
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.mainClock.advanceTimeBy(200)
    }

    @Test fun leaveKeepsEveryOptionInTheNewDesign() {
        show(NowViewModel.Chooser.Leave("task"))
        compose.onNodeWithText("Leave this session?").assertIsDisplayed()
        compose.onNodeWithText("Return in:").assertIsDisplayed()
        listOf("leave_3m", "leave_5m", "leave_10m", "leave_15m", "chooser_custom",
            "chooser_leave_no_reminder", "chooser_pause", "chooser_cancel", "chooser_close").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithText("PAUSE").assertIsDisplayed()
        captureScreenRoboImage("build/outputs/leave-chooser-412.png")
        tap("leave_10m")
        tap("chooser_leave_no_reminder")
        tap("chooser_pause")
        tap("chooser_cancel")
        tap("chooser_close")
        assertEquals(listOf("leave(10)", "leave(null)", "pauseFocus()", "dismissChooser", "dismissChooser"), calls)
    }

    @Test fun customReminderStaysInsideTheDialog() {
        show(NowViewModel.Chooser.Leave("task"))
        tap("chooser_custom")
        compose.onNodeWithTag("chooser_custom_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("chooser_custom_minutes").performTextInput("25")
        compose.mainClock.advanceTimeBy(200)
        captureScreenRoboImage("build/outputs/leave-chooser-custom.png")
        tap("chooser_custom_confirm")
        assertEquals(listOf("customMinutes(LEAVE,25)"), calls)
    }

    @Test @Config(qualifiers = "w320dp-h640dp")
    fun handOffFitsANarrowPhone() {
        show(NowViewModel.Chooser.HandOff("task"))
        listOf("handoff_1m", "handoff_15m", "chooser_custom", "chooser_no_check", "chooser_cancel").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        captureScreenRoboImage("build/outputs/handoff-chooser-320.png")
        tap("handoff_5m")
        assertEquals(listOf("handOff(5)"), calls)
    }
}
