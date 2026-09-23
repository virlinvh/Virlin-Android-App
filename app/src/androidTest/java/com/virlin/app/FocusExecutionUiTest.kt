package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.model.FocusInvestment
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.hierarchy.FocusWorkItemTag
import com.virlin.app.ui.hierarchy.SwitchFocusCancelTag
import com.virlin.app.ui.hierarchy.SwitchFocusConfirmTag
import com.virlin.app.ui.hierarchy.SwitchFocusTag
import com.virlin.app.ui.hierarchy.TaskDetailTag
import com.virlin.app.ui.hierarchy.taskDetail
import com.virlin.app.ui.screens.DoneForNowTag
import com.virlin.app.ui.screens.FocusContextTag
import com.virlin.app.ui.screens.FocusNextTag
import com.virlin.app.ui.navigation.RootDestination
import com.virlin.app.ui.navigation.bottomNavItemTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.Instant

/**
 * Phase 09 flows on the real app: focus an exact work item from the hierarchy, switch focus with
 * confirmation, and the post-COMPLETE continuation. Assertions read the domain, not UI text.
 */
class FocusExecutionUiTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    val composeRule = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(DemoStateRule()).around(composeRule)

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    /** A READY (human) stream from the demo seed: focus requires a legal transition into FOCUS. */
    private val WORK_STREAM = "s7"

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    private fun touch(t: String, after: Long = 900) {
        runCatching { tag(t).performScrollTo() }.onSuccess { pump(900) }
        tag(t).performClick(); pump(after)
    }
    private fun actions() = VirlinGraph.actions
    private fun repo() = VirlinGraph.repository
    private fun focusedStream() = repo().streams.value.firstOrNull { it.state == WorkStreamState.FOCUS }
    /** Streams → Projects → project → the READY stream → the task row → Task Detail. */
    private fun openTaskDetail(taskId: String) {
        composeRule.onNodeWithTag(bottomNavItemTag(RootDestination.STREAMS)).performClick(); pump(900)
        composeRule.onNodeWithTag("streams_filter_projects").performClick(); pump(400)
        touch("project_row_p5", 1200)
        touch("stream_row_$WORK_STREAM", 1200)
        touch(com.virlin.app.ui.hierarchy.taskRowTag(taskId), 1200)
        tag(TaskDetailTag).assertIsDisplayed()
    }

    /** A fresh leaf under the demo's external stream, so the seeded Focus (s1) is the "current" work. */
    private fun newLeaf(title: String, streamId: String): Task = runBlocking {
        (actions().createTask(CreateTask(title = title, workStreamId = streamId)) as ActionResult.Success).value
    }
    private fun invested(streamId: String, taskId: String) = runBlocking {
        FocusInvestment.total(repo().getFocusSessions(streamId), taskId, Instant.now())
    }

    // ------------------------------------------------------------------ FLOW A / I / J

    @Test fun focusFromHierarchy_switchConfirmation_cancelThenSwitch() {
        pump(900)
        tag(FocusContextTag).assertIsDisplayed()
        val before = focusedStream()!!                                   // the demo seeds one human focus
        val beforeTask = before.activeTaskId
        val leaf = newLeaf("Phase 09 leaf", WORK_STREAM)
        pump(600)

        // FLOW I: requesting focus elsewhere asks first and writes nothing on CANCEL.
        openTaskDetail(leaf.id)
        touch(FocusWorkItemTag, 900)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(SwitchFocusTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        touch(SwitchFocusCancelTag, 900)
        assert(focusedStream()?.id == before.id) { "cancel must not switch focus" }
        assert(focusedStream()?.activeTaskId == beforeTask)

        // FLOW J: confirming switches — the old work stays open and keeps its investment.
        val oldInvestment = invested(before.id, beforeTask!!)
        touch(FocusWorkItemTag, 900)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(SwitchFocusTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        touch(SwitchFocusConfirmTag, 1500)
        composeRule.waitUntil(5_000) { focusedStream()?.id == WORK_STREAM }
        assert(focusedStream()!!.activeTaskId == leaf.id) { "the exact work item is now focused" }
        assert(!runBlocking { repo().getTask(beforeTask) }!!.status.isTerminal) { "displaced work stays incomplete" }
        assert(invested(before.id, beforeTask) >= oldInvestment) { "investment is never lost" }
        assert(repo().streams.value.count { it.state == WorkStreamState.FOCUS } == 1)
        assert(runBlocking { repo().getFocusSessions(WORK_STREAM) }.count { it.isOpen } == 1)
    }

    // ------------------------------------------------------------------ FLOW F / G / H / L

    @Test fun completeOfferedNext_focusNextOrDoneForNow_andNoStaleFocus() {
        pump(900)
        val first = newLeaf("Phase 09 first", WORK_STREAM)
        newLeaf("Phase 09 second", WORK_STREAM)
        pump(400)
        runBlocking { actions().startFocus(first.id) }
        composeRule.waitUntil(5_000) { focusedStream()?.activeTaskId == first.id }
        pump(1200)

        // FLOW F: completing the focused item offers what is next, and never auto-starts it.
        composeRule.onNodeWithTag(bottomNavItemTag(RootDestination.NOW)).performClick(); pump(900)
        touch("focus_complete", 1500)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(com.virlin.app.ui.screens.CompletedFocusTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assert(runBlocking { repo().getTask(first.id) }!!.status.isCompleted)
        assert(focusedStream()?.activeTaskId == null) { "FLOW L: no stale focus on a completed item" }

        // FLOW G: FOCUS NEXT starts the OFFERED leaf (the deterministic next candidate), on purpose.
        val offered = runBlocking { (actions().nextTaskCandidate(WORK_STREAM) as ActionResult.Success).value }!!
        touch(FocusNextTag, 1500)
        composeRule.waitUntil(5_000) { focusedStream()?.activeTaskId == offered.id }
        assert(runBlocking { repo().getFocusSessions(WORK_STREAM) }.count { it.isOpen } == 1)

        // FLOW H: completing again and choosing DONE FOR NOW leaves nothing focused.
        touch("focus_complete", 1500)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(com.virlin.app.ui.screens.CompletedFocusTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        touch(DoneForNowTag, 1200)
        assert(runBlocking { repo().getTask(offered.id) }!!.status.isCompleted)
        assert(focusedStream()?.activeTaskId == null)
        composeRule.onAllNodesWithTag(com.virlin.app.ui.screens.CompletedFocusTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    }
}
