package com.virlin.app.now

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.ui.screens.*
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Mirrors NowScreen's wiring: the card gets the height left under the header and summary. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusCardFitsFirstScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun render(width: Dp, viewport: Dp, empty: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VirlinTheme {
                val viewportPx = remember { mutableIntStateOf(0) }
                val headerPx = remember { mutableIntStateOf(0) }
                val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
                val budget: () -> Int? = { if (viewportPx.intValue > 0 && headerPx.intValue > 0) viewportPx.intValue - headerPx.intValue - gap else null }
                Box(Modifier.width(width).height(viewport).background(Color.White).testTag("viewport")
                    .onSizeChanged { viewportPx.intValue = it.height }) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                        Column(Modifier.fillMaxWidth().onSizeChanged { headerPx.intValue = it.height }) {
                            Spacer(Modifier.height(6.dp))
                            NowDaySummary(NowDaySummaryValues(timeSaved = "3m", savedUnit = "Time freed", timeFocused = "14h 30m", timeExternal = "3m"),
                                timersRunning = true, onPauseAll = {})
                            Spacer(Modifier.height(14.dp))
                        }
                        Box(Modifier.testTag("card")) {
                            if (empty) NowEmptyFocusCard(maxHeightPx = budget) {}
                            else VirlinCurrentFocusCard(projectName = "Psychology",
                                taskName = "SI stands for Super Intelligence, and AI stands for Artificial Intelligence.",
                                elapsedTime = "", investedTime = "Task total · 2m", nextAction = "", nextEstimate = "",
                                hierarchyLabels = listOf("Answer", "Questions", "Question", "Hello"),
                                onLeave = {}, onComplete = {}, maxHeightPx = budget,
                                timerContent = { NowApprovedTimer(120) })
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun assertInsideViewport(tag: String) {
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        // boundsInRoot is clipped by the scroll container; the real edge is position + size.
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val bottom = node.positionInRoot.y + node.size.height
        assertTrue("$tag bottom $bottom > viewport ${viewport.bottom}", bottom <= viewport.bottom + 0.5f)
    }

    @Test fun galaxySizedPhoneShowsTheWholeCard() {
        render(384.dp, 610.dp)
        compose.onRoot().captureRoboImage("build/outputs/focus-fit-384x610.png")
        assertInsideViewport(FocusLeaveTag)
        assertInsideViewport(FocusCompleteTag)
        assertInsideViewport("card")
    }

    @Test fun smallPhoneShowsTheWholeCard() {
        render(360.dp, 540.dp)
        compose.onRoot().captureRoboImage("build/outputs/focus-fit-360x540.png")
        assertInsideViewport(FocusCompleteTag)
        assertInsideViewport("card")
    }

    @Test fun tinyPhoneShowsTheWholeCard() {
        render(320.dp, 490.dp)
        compose.onRoot().captureRoboImage("build/outputs/focus-fit-320x490.png")
        assertInsideViewport("card")
    }

    @Test fun tallPhoneKeepsTheFullSizeTimer() {
        render(412.dp, 820.dp)
        compose.onRoot().captureRoboImage("build/outputs/focus-fit-412x820.png")
        assertInsideViewport("card")
    }

    @Test fun emptyCardAlsoFits() {
        render(360.dp, 540.dp, empty = true)
        compose.onRoot().captureRoboImage("build/outputs/focus-fit-empty-360x540.png")
        assertInsideViewport("card")
    }

    @Test fun emptyCardFitsATinyPhone() {
        render(320.dp, 490.dp, empty = true)
        compose.onRoot().captureRoboImage("build/outputs/focus-fit-empty-320x490.png")
        assertInsideViewport("card")
    }
}
