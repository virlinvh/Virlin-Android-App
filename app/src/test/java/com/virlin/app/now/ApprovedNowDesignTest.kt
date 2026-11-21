package com.virlin.app.now

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.ui.navigation.ApprovedNowDock
import com.virlin.app.ui.screens.*
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovedNowDesignTest {
    @get:Rule val compose = createComposeRule()
    private var pauses = 0
    private var clockOpens = 0
    private var leaves = 0
    private var finishes = 0
    private var destination = "now"

    private fun render(width: Dp, scale: Float = 1f, primary: String = "COMPLETE") {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                VirlinTheme {
                    var route by remember { mutableStateOf("now") }
                    Column(Modifier.width(width).height(820.dp).background(Color.White)) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            Spacer(Modifier.height(14.dp))
                            NowDaySummary(NowDaySummaryValues(timeSaved = "0m", savedUnit = "Time freed", timeFocused = "23h 34m", timeExternal = "0m"),
                                timersRunning = true, onPauseAll = { pauses++ })
                            Spacer(Modifier.height(20.dp))
                            VirlinCurrentFocusCard(projectName = "Psychology",
                                taskName = "SI stands for Super Intelligence, and AI stands for Artificial Intelligence.",
                                elapsedTime = "", investedTime = "Task total · <1m", nextAction = "", nextEstimate = "",
                                hierarchyLabels = listOf("Answer", "Questions", "Hello"),
                                onLeave = { leaves++ }, onComplete = { finishes++ }, primaryLabel = primary,
                                timerContent = { Box(Modifier.testTag("approved_timer_tap").clickable { clockOpens++ }) { NowApprovedTimer(9) } })
                            Spacer(Modifier.height(16.dp))
                            VirlinNeedsYouSection(emptyList(), 0L, {}, {}, animate = false)
                        }
                        ApprovedNowDock(route, 14, { route = it; destination = it })
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(32)
    }

    @Test fun samsungWidthKeepsNativeDesignAndExistingCallbacks() {
        render(412.dp)
        compose.onRoot().captureRoboImage("build/outputs/approved-now-412.png")
        compose.onNodeWithTag("master_timer_button").performClick()
        scrollAndTap("approved_timer_tap")
        scrollAndTap(FocusLeaveTag)
        scrollAndTap(FocusCompleteTag)
        assertEquals(listOf(1, 1, 1, 1), listOf(pauses, clockOpens, leaves, finishes))
    }

    @Test fun narrowLargeTextKeepsHandOffReachableAndTimerInsideCard() {
        render(320.dp, 1.3f, "HAND OFF")
        compose.onNodeWithTag("approved_timer_tap").performScrollTo()
        compose.mainClock.advanceTimeBy(500)
        val timer = compose.onNodeWithTag("approved_timer_tap").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(timer.left >= root.left && timer.right <= root.right)
        scrollAndTap(FocusHandOffTag)
        assertEquals(1, finishes)
        compose.onRoot().captureRoboImage("build/outputs/approved-now-320-large-text.png")
    }

    @Test fun dockKeepsAllExistingDestinationsAndBadgeSemantics() {
        render(360.dp)
        compose.onNodeWithTag("bottom_nav_streams").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("streams", destination)
        compose.onNodeWithTag("bottom_nav_pulse").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("pulse", destination)
        compose.onNodeWithContentDescription("Apps, 14 in inbox").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("apps", destination)
        compose.onNodeWithTag("bottom_nav_now").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals("now", destination)
    }

    @Test fun tappedTabHighlightsAtOnceAndNavigatesAfterTheBubbleArrives() {
        render(412.dp)
        compose.onNodeWithTag("bottom_nav_streams").performClick()
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("bottom_nav_streams").assertIsSelected()
        assertEquals("now", destination)
        compose.mainClock.advanceTimeBy(600)
        assertEquals("streams", destination)
    }

    @Test fun rapidTapsOnlyNavigateToTheLastTab() {
        val visited = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var route by remember { mutableStateOf("now") }
            ApprovedNowDock(route, 0, { route = it; visited += it })
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("bottom_nav_streams").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("bottom_nav_pulse").performClick()
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(listOf("pulse"), visited)
        compose.onNodeWithTag("bottom_nav_pulse").assertIsSelected()
    }

    @Test fun titleAreaStillOpensTheWorkStreamWithoutARipple() {
        var opened = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VirlinCurrentFocusCard(projectName = "Psychology", taskName = "SI stands for Super Intelligence.",
                elapsedTime = "", investedTime = "", nextAction = "", nextEstimate = "",
                hierarchyLabels = listOf("Answer", "Hello"), onLeave = { leaves++ }, onComplete = { finishes++ },
                onContextClick = { opened++ })
        }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag(FocusContextTag).assertHasClickAction().performClick()
        compose.onNodeWithTag(FocusLeaveTag).performClick()
        compose.onNodeWithTag(FocusCompleteTag).performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(listOf(1, 1, 1), listOf(opened, leaves, finishes))
    }

    @Test fun longSessionsKeepAllMinuteDigits() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.width(280.dp)) { NowApprovedTimer(125 * 60 + 9) } }
        compose.onNodeWithContentDescription("Focus invested 125 minutes 9 seconds").assertExists()
    }

    private fun scrollAndTap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(32)
    }
}
