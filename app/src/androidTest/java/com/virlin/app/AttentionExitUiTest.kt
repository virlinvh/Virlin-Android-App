package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.screens.FocusCompleteTag
import com.virlin.app.ui.screens.FocusHandOffTag
import com.virlin.app.ui.screens.FocusLeaveTag
import com.virlin.app.ui.screens.FocusTaskTag
import com.virlin.app.ui.screens.NowChooserTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pass 4 on the real app (Pixel 8): finalized labels, the Leave / Hand off / Check choosers
 * and the resulting domain states. The domain is process-wide, so this is ONE ordered
 * scenario; state is read back from the repository (never from MockData). Due times are
 * reached through `checkDue` — the same scheduling hook the in-app ticker calls.
 */
@RunWith(AndroidJUnit4::class)
class AttentionExitUiTest {

    /** The chooser asks for POST_NOTIFICATIONS contextually; keep the system dialog out of the test. */
    @get:Rule
    val permissions: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) {
            composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8)
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun stream(id: String) = runBlocking { VirlinGraph.repository.getStream(id)!! }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    /** Dialog windows compose a few frames late under load: wait for the node, then tap. */
    private fun tapWhenShown(t: String, after: Long = 1000) {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end && composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) pump(100)
        tag(t).performClick(); pump(after)
    }
    private fun shown(vararg tags: String) = tags.forEach { t ->
        try { tag(t).assertIsDisplayed() } catch (e: AssertionError) { throw AssertionError("$t: ${e.message}", e) }
    }

    @Test fun human_leave_return_external_handOff_check_outcomes() {
        pump(300)
        // 1. Human Current Focus (Psychology, active Question 17): LEAVE / COMPLETE
        tag(FocusLeaveTag).assertIsDisplayed().assertTextEquals("LEAVE")
        tag(FocusCompleteTag).assertIsDisplayed().assertTextEquals("COMPLETE")

        // 2. LEAVE opens the chooser with presets, custom and leave-without-reminder
        tag(FocusLeaveTag).performClick(); pump(1500)
        tag(NowChooserTag).assertIsDisplayed()
        shown("leave_3m", "leave_5m", "leave_10m", "leave_15m", "chooser_custom", "chooser_leave_no_reminder")

        // 3. Leave without reminder → READY, task kept, gone from Current Focus
        tag("chooser_leave_no_reminder").performClick(); pump(1000)
        composeRule.onNodeWithText("CURRENT FOCUS").assertDoesNotExist()
        stream("s1").let { check(it.state == WorkStreamState.READY && it.activeTaskId == "p_q17" && it.processingStartedAt == null) { "leave→ $it" } }

        // 4. Resume via the action layer, LEAVE + 5m → SNOOZED / HUMAN_RETURN; due → "Ready to continue" + RESUME
        runBlocking { VirlinGraph.actions.focusStream("s1") }; pump(600)
        tag(FocusLeaveTag).performClick(); pump(1500)
        tag("leave_5m").performClick(); pump(1000)
        stream("s1").let { check(it.state == WorkStreamState.SNOOZED && it.snoozeReason == SnoozeReason.HUMAN_RETURN && it.snoozedUntil != null) { "leave 5m→ $it" } }
        runBlocking { VirlinGraph.actions.checkDue("s1") }; pump(800)
        tag("needs_you_kind_s1").assertTextEquals("Ready to continue")
        composeRule.onNodeWithText("CURRENT FOCUS").assertDoesNotExist()             // return due does not steal Focus
        tag("needs_you_primary_s1").performClick(); pump(1000)                          // RESUME
        check(stream("s1").state == WorkStreamState.FOCUS) { "resume" }
        check(runBlocking { VirlinGraph.repository.getOpenFocusSession("s1") }!!.taskId == "p_q17") { "session task" }

        // 5. External Current Focus (Antigravity, s2 is EXTERNAL): LEAVE / HAND OFF
        runBlocking { VirlinGraph.actions.focusStream("s2") }; pump(800)
        tag(FocusHandOffTag).assertIsDisplayed().assertTextEquals("HAND OFF")
        tag(FocusLeaveTag).assertIsDisplayed()

        // 6–7. HAND OFF opens check choices; 5m → PROCESSING with checkAt, task kept
        tag(FocusHandOffTag).performClick(); pump(1500)
        shown("handoff_1m", "handoff_2m", "handoff_5m", "handoff_10m", "handoff_15m", "chooser_custom", "chooser_no_check")
        tag("handoff_5m").performClick(); pump(1000)
        stream("s2").let { check(it.state == WorkStreamState.PROCESSING && it.checkAt != null && it.processingStartedAt != null) { "handoff→ $it" } }

        // 8. Check due does not steal Focus; CHECK opens "What happened?"
        runBlocking { VirlinGraph.actions.checkDue("s2") }; pump(800)
        tag("needs_you_kind_s2").assertTextEquals("Check due")
        composeRule.onNodeWithText("CURRENT FOCUS").assertDoesNotExist()
        tag("needs_you_primary_s2").performClick(); pump(1500)
        shown("chooser_still_running", "chooser_result_ready", "chooser_blocked")
        check(stream("s2").state == WorkStreamState.CHECK) { "check must not focus" }

        // 9. STILL RUNNING + 5m → PROCESSING
        tapWhenShown("chooser_still_running"); tapWhenShown("still_5m")
        stream("s2").let { check(it.state == WorkStreamState.PROCESSING && it.checkAt != null) { "still running→ $it" } }

        // 10–11. RESULT READY → Focus now / Remind later; Remind later → SNOOZED RESULT_READY, not PROCESSING
        runBlocking { VirlinGraph.actions.checkDue("s2") }; pump(800)
        tag("needs_you_primary_s2").performClick(); pump(1500)
        tapWhenShown("chooser_result_ready")
        tag("chooser_focus_now").assertIsDisplayed()
        shown("later_3m", "later_5m", "later_10m", "later_15m")
        tag("later_5m").performClick(); pump(1000)
        stream("s2").let { check(it.state == WorkStreamState.SNOOZED && it.snoozeReason == SnoozeReason.EXTERNAL_RESULT_READY && it.processingStartedAt == null) { "remind later→ $it" } }
        runBlocking { VirlinGraph.actions.checkDue("s2") }; pump(800)
        tag("needs_you_kind_s2").assertTextEquals("Result ready")

        // 12. COMPLETE on a human stream with an active task stays task-only (s10 is unused elsewhere;
        //     s1 is left with Question 17 active for the other instrumented classes)
        val taskId = runBlocking {
            val r = VirlinGraph.actions.createTask(CreateTask(title = "Exit proof task", workStreamId = "s10")) as ActionResult.Success
            VirlinGraph.actions.setActiveTask("s10", r.value.id)
            VirlinGraph.actions.focusStream("s10")
            r.value.id
        }; pump(800)
        tag(FocusTaskTag).assertIsDisplayed().assertTextEquals("Exit proof task")
        tag(FocusCompleteTag).performClick(); pump(800)
        stream("s10").let { check(it.state == WorkStreamState.FOCUS && it.activeTaskId == null) { "complete task-only→ $it" } }
        check(runBlocking { VirlinGraph.repository.getTask(taskId) }!!.status == TaskStatus.DONE) { "task done" }
        check(stream("s1").activeTaskId == "p_q17") { "s1 keeps its active task" }
        runBlocking { VirlinGraph.actions.focusStream("s1") }; pump(600)              // hand the seeded Focus back
    }
}
