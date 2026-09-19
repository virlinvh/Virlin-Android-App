package com.virlin.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-level: rememberSplitFlapShouldAnimate tracks baseline and only allows n→n+1 flips.
 * Decision stays stable across same-second recompositions (does not cancel mid-flip).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplitFlapShouldAnimateComposeTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun firstValue_thenJump_thenTick_policySequence() {
        var time by mutableIntStateOf(0)
        lateinit var readAnimate: () -> Boolean

        composeRule.setContent {
            val animate = rememberSplitFlapShouldAnimate(time)
            readAnimate = { animate }
            SplitFlapTimer(timeInSeconds = time)
        }
        composeRule.waitForIdle()
        assertFalse(readAnimate())

        time = 1634 // restore / hydrate jump
        composeRule.waitForIdle()
        assertFalse(readAnimate())

        time = 1635 // adjacent tick — stays true while this second is shown
        composeRule.waitForIdle()
        assertTrue(readAnimate())

        composeRule.onNodeWithContentDescription(focusTimeContentDescription(1635)).assertExists()
        composeRule.waitForIdle()
        assertTrue(readAnimate())
    }

    @Test
    fun keyRemount_resetsBaselineToSnap() {
        var identity by mutableStateOf("a")
        var time by mutableIntStateOf(10)
        lateinit var readAnimate: () -> Boolean

        composeRule.setContent {
            androidx.compose.runtime.key(identity) {
                val animate = rememberSplitFlapShouldAnimate(time)
                readAnimate = { animate }
                SplitFlapTimer(timeInSeconds = time)
            }
        }
        composeRule.waitForIdle()
        assertFalse(readAnimate())

        time = 11
        composeRule.waitForIdle()
        assertTrue(readAnimate())

        identity = "b"
        time = 0
        composeRule.waitForIdle()
        assertFalse(readAnimate())
    }
}
