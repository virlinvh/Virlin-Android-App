package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.ui.screens.CompleteTaskCancelTag
import com.virlin.app.ui.screens.CompleteTaskConfirmTag
import com.virlin.app.ui.screens.CurrentFocusProjection
import com.virlin.app.ui.screens.FocusCompleteTag
import com.virlin.app.ui.screens.FocusHeroCard
import com.virlin.app.ui.screens.FocusInvestedTag
import com.virlin.app.ui.screens.FocusLivePillTag
import com.virlin.app.ui.screens.FocusMetricPillTag
import com.virlin.app.ui.screens.VirlinCurrentFocusCard
import com.virlin.app.ui.screens.outlinePoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusPillsAndCompleteTest {
    @get:Rule val compose = createComposeRule()

    private fun card(width: Dp, invested: String, completing: Boolean = false, onComplete: () -> Unit = {}) {
        compose.setContent {
            Box(Modifier.width(width).testTag("host")) {
                VirlinCurrentFocusCard(
                    projectName = "Windows", taskName = "New Project", elapsedTime = "05:36",
                    investedTime = invested, nextAction = "", nextEstimate = "",
                    onLeave = {}, onComplete = onComplete, timerContent = {}, completing = completing,
                )
            }
        }
    }

    @Test fun longDurationsStayOnOneLineClearOfTheBorder() {
        val widthState = mutableStateOf(296.dp)
        val timerState = mutableStateOf(120.dp)
        val labelState = mutableStateOf("")
        compose.setContent {
            Box(Modifier.width(widthState.value).testTag("host")) {
                VirlinCurrentFocusCard(
                    projectName = "Windows", taskName = "New Project", elapsedTime = "05:36",
                    investedTime = labelState.value, nextAction = "", nextEstimate = "",
                    onLeave = {}, onComplete = {}, timerContent = { Spacer(Modifier.height(timerState.value)) },
                )
            }
        }
        val density = compose.density.density
        for (width in listOf(296.dp, 387.dp)) for (timer in listOf(120.dp, 420.dp)) {
            for (label in listOf("5m focused", "4h 12m focused", "Task total · 26h 05m", "Task total · 999h 59m")) {
                widthState.value = width
                timerState.value = timer
                labelState.value = label
                compose.waitForIdle()
                val where = "$label @ $width / $timer"
                val host = compose.onNodeWithTag("host").getUnclippedBoundsInRoot()
                val text = compose.onNodeWithTag(FocusInvestedTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
                val live = compose.onNodeWithTag(FocusLivePillTag).getUnclippedBoundsInRoot()
                val metric = compose.onNodeWithTag(FocusMetricPillTag).getUnclippedBoundsInRoot()
                assertTrue("$where one line ${text.bottom - text.top}", text.bottom - text.top < 22.dp)
                assertTrue("$where same row", kotlin.math.abs(live.top.value - metric.top.value) < 4f)
                assertTrue("$where pills apart", live.right <= metric.left)
                compose.onNodeWithTag(FocusInvestedTag, useUnmergedTree = true).assertTextEquals(label)
                // The wavy border stays clear of both capsules — widest at the corners.
                val cw = (host.right - host.left).value; val ch = (host.bottom - host.top).value
                val pts = outlinePoints(cw * density, ch * density)
                for (pill in listOf(live, metric)) {
                    val l = (pill.left - host.left).value; val t = (pill.top - host.top).value
                    val pw = (pill.right - pill.left).value; val ph = (pill.bottom - pill.top).value
                    val r = ph / 2
                    var i = 0
                    while (i < pts.size) {
                        val px = pts[i] / density; val py = pts[i + 1] / density; i += 2
                        val cx = px.coerceIn(l + r, l + pw - r)
                        val dist = kotlin.math.hypot(px - cx, py - (t + r)) - r
                        val corner = px < cw * .15f || px > cw * .85f
                        assertTrue("$where border ${"%.1f".format(dist)}dp from pill at ($px, $py)", dist >= if (corner) 7f else 3.5f)
                    }
                }
            }
        }
    }

    @Test fun confirmedCompleteTurnsIntoCompletedAndStopsTakingTaps() {
        var completed = 0
        card(360.dp, "Task total · 5m", completing = true) { completed++ }
        compose.onNodeWithTag(FocusCompleteTag).assertTextEquals("COMPLETED").performClick()
        assertEquals(0, completed)
    }

    private val task = CurrentFocusProjection("s1", "Psychology", "Psychology", "p_q17", "Question 17",
        "Complete Q17 answer", WorkStreamState.FOCUS, isExternal = false)

    private fun hero(onComplete: (String) -> Unit, projection: CurrentFocusProjection = task) {
        val stream = MockData.streams.value.first { it.id == "s1" }.copy(state = StreamState.FOCUS)
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                FocusHeroCard(stream, rememberNavController(), hierarchy = projection, onComplete = onComplete)
            }
        }
    }

    @Test fun completingATaskWarnsFirstThenCompletesAfterTheGreenFinish() {
        val completed = mutableListOf<String>()
        hero({ completed += it })
        compose.onNodeWithTag(FocusCompleteTag).performClick()
        compose.onNodeWithText("Complete Question 17?").assertExists()
        assertTrue(completed.isEmpty())
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(CompleteTaskConfirmTag).performClick()
        compose.mainClock.advanceTimeBy(260)
        compose.onRoot().captureRoboImage("build/outputs/focus-complete-sweep.png")
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag(FocusCompleteTag).assertTextEquals("COMPLETED")
        compose.onRoot().captureRoboImage("build/outputs/focus-completed.png")
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        assertEquals(listOf("s1"), completed)
    }

    @Test fun cancellingTheWarningCompletesNothing() {
        val completed = mutableListOf<String>()
        hero({ completed += it })
        compose.onNodeWithTag(FocusCompleteTag).performClick()
        compose.onNodeWithTag(CompleteTaskCancelTag).performClick()
        compose.onNodeWithText("Complete Question 17?").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue(completed.isEmpty())
        compose.onNodeWithTag(FocusCompleteTag).assertTextEquals("COMPLETE")
    }

    @Test fun aWholeWorkStreamKeepsItsOwnSingleConfirmation() {
        val completed = mutableListOf<String>()
        hero({ completed += it }, task.copy(activeTaskId = null, activeTaskTitle = null))
        compose.onNodeWithTag(FocusCompleteTag).performClick()
        compose.onNodeWithTag(CompleteTaskConfirmTag).assertDoesNotExist()
        assertEquals(listOf("s1"), completed)
    }
}
