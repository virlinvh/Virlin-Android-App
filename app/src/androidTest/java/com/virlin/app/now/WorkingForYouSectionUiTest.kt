package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.screens.VirlinWorkingForYouSection
import com.virlin.app.ui.screens.WorkingTaskUi
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Working For You section against the CHECK WINDOW it renders: a 5-minute and a 10-minute
 * interval at start, midpoint and deadline, a run with no scheduled check, and fifteen at once.
 * Time is passed in, so every case is deterministic — nothing here waits on a clock.
 */
class WorkingForYouSectionUiTest {

    @get:Rule val composeRule = createComposeRule()

    private val t0 = 1_700_000_000_000L          // fixed "now" base
    private val fiveMinutes = 5 * 60_000L
    private val tenMinutes = 10 * 60_000L

    private fun advanceTo(now: Long) { nowState.value = now; composeRule.waitForIdle() }

    private fun task(id: String, title: String, start: Long?, due: Long?) = WorkingTaskUi(
        id = id, title = title, detail = "Work item · instruction",
        checkStartedAtEpochMillis = start, nextCheckAtEpochMillis = due
    )

    /** One `setContent` per test (Compose's rule), so "now" is state the test advances. */
    private val nowState = androidx.compose.runtime.mutableStateOf(t0)

    private fun show(
        tasks: List<WorkingTaskUi>,
        now: Long = t0,
        widthDp: Int = 411,
        clicked: MutableList<String> = mutableListOf()
    ) {
        nowState.value = now
        composeRule.setContent {
            VirlinTheme {
                // The page owns the only scroll container, exactly like the Now screen.
                Box(Modifier.width(widthDp.dp).testTag("page").verticalScroll(rememberScrollState())) {
                    VirlinWorkingForYouSection(
                        tasks = tasks, nowEpochMillis = nowState.value, onTaskClick = { clicked += it },
                        animate = false, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    // ------------------------------------------------------------------ 5 and 10 minute windows

    @Test fun fiveMinuteWindow_start_midpoint_deadline() {
        // ONE five-minute window; the clock moves through it.
        show(listOf(task("a", "Claude", t0, t0 + fiveMinutes)))
        composeRule.onNodeWithText("◷  05:00 to check").assertIsDisplayed()     // START
        composeRule.onNodeWithText("Working now").assertIsDisplayed()

        advanceTo(t0 + fiveMinutes / 2)                                          // MIDPOINT
        composeRule.onNodeWithText("◷  02:30 to check").assertIsDisplayed()
        composeRule.onNodeWithText("Working now").assertIsDisplayed()

        advanceTo(t0 + fiveMinutes)                                              // DEADLINE
        composeRule.onNodeWithText("Ready to check").assertIsDisplayed()
        composeRule.onNodeWithText("Check now").assertIsDisplayed()
    }

    @Test fun tenMinuteWindow_start_midpoint_deadline() {
        show(listOf(task("b", "Codex", t0, t0 + tenMinutes)))
        composeRule.onNodeWithText("◷  10:00 to check").assertIsDisplayed()

        advanceTo(t0 + tenMinutes / 2)
        composeRule.onNodeWithText("◷  05:00 to check").assertIsDisplayed()

        advanceTo(t0 + tenMinutes)
        composeRule.onNodeWithText("Ready to check").assertIsDisplayed()
        composeRule.onNodeWithText("Check now").assertIsDisplayed()
    }

    /** A newly scheduled check restarts the window: the label jumps back to the full interval. */
    @Test fun aNewCheckRestartsTheWindow() {
        val task = androidx.compose.runtime.mutableStateOf(task("f", "Claude", t0, t0 + fiveMinutes))
        composeRule.setContent {
            VirlinTheme {
                Box(Modifier.width(411.dp).testTag("page").verticalScroll(rememberScrollState())) {
                    VirlinWorkingForYouSection(
                        tasks = listOf(task.value), nowEpochMillis = nowState.value,
                        onTaskClick = {}, animate = false, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        advanceTo(t0 + fiveMinutes)
        composeRule.onNodeWithText("Check now").assertIsDisplayed()
        // "Still running — check again in 10 minutes": new window start AND new deadline.
        task.value = task("f", "Claude", t0 + fiveMinutes, t0 + fiveMinutes + tenMinutes)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("◷  10:00 to check").assertIsDisplayed()
        composeRule.onNodeWithText("Working now").assertIsDisplayed()
    }

    /** Past the deadline the card stays "ready" rather than counting negative. */
    @Test fun overdueStaysReady() {
        show(listOf(task("c", "Build", t0 - tenMinutes, t0 - 30_000L)))
        composeRule.onNodeWithText("Ready to check").assertIsDisplayed()
        composeRule.onNodeWithText("Check now").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Working now").fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------------------ no scheduled check

    @Test fun noScheduledCheck_showsWorkingNow_andNoCheck() {
        show(listOf(task("d", "Render", start = null, due = null)))
        composeRule.onNodeWithText("Working now").assertIsDisplayed()
        composeRule.onNodeWithText("No check").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Ready to check").fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------------------ many at once

    @Test fun fifteenTasks_allRender_fitTheWidth_andTapReportsTheRightId() {
        val clicked = mutableListOf<String>()
        val many = (1..15).map { i ->
            task("run-$i", "Actor $i", t0 - i * 10_000L, t0 + i * 20_000L)
        }
        show(many, clicked = clicked)
        val page = composeRule.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        (1..15).forEach { i ->
            val card = composeRule.onNodeWithText("Actor $i").performScrollTo().fetchSemanticsNode().boundsInRoot
            assertTrue("card $i inside the page", card.left >= page.left - 1f && card.right <= page.right + 1f)
        }
        assertEquals(15, composeRule.onAllNodesWithText("Working now").fetchSemanticsNodes().size)
        composeRule.onNode(hasText("15")).assertExists()                       // the count badge
        composeRule.onNodeWithText("Actor 7").performScrollTo().performClick()
        assertEquals(listOf("run-7"), clicked)
    }

    @Test fun narrow335_doesNotClip() {
        show(listOf(task("e", "Antigravity", t0 - fiveMinutes / 2, t0 + fiveMinutes / 2)), widthDp = 335)
        val page = composeRule.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        listOf("Antigravity", "Working now", "◷  02:30 to check").forEach { text ->
            val node = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
            assertTrue("$text inside the page", node.right <= page.right + 1f && node.left >= page.left - 1f)
        }
    }
}
