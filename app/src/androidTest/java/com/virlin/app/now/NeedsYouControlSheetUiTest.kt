package com.virlin.app.now

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.DemoStateRule
import com.virlin.app.MainActivity
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.screens.NeedsYouControlBlockTag
import com.virlin.app.ui.screens.NeedsYouControlCheckTabTag
import com.virlin.app.ui.screens.NeedsYouControlCloseTag
import com.virlin.app.ui.screens.NeedsYouControlNotNowTag
import com.virlin.app.ui.screens.NeedsYouControlPriorityTabTag
import com.virlin.app.ui.screens.NeedsYouControlSheetTag
import com.virlin.app.ui.screens.NeedsYouControlStillRunningTag
import com.virlin.app.ui.screens.NeedsYouControlTimerTag
import com.virlin.app.ui.screens.needsYouRankTag
import com.virlin.app.ui.screens.priorityPositionTag
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * PHASE 2 — the unified Needs You control sheet on the real app: both halves of the card pill open
 * the SAME sheet on their own tab, the tabs switch without closing or writing anything, a position
 * applies immediately and leaves the sheet open, and a check action runs an existing transition and
 * closes. Assertions read the domain, never UI copy.
 */
class NeedsYouControlSheetUiTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    val composeRule = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(DemoStateRule()).around(composeRule)

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    private fun touch(t: String, after: Long = 700) {
        runCatching { tag(t).performScrollTo() }.onSuccess { pump(600) }
        tag(t).performClick(); pump(after)
    }
    private fun exists(t: String) = composeRule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun repo() = VirlinGraph.repository
    private fun queue() = NeedsYouOrder.queue(repo().streams.value).map { it.stream.id }
    private fun streamOf(id: String) = repo().streams.value.first { it.id == id }
    private fun first() = queue().first()

    /**
     * Tests share one device app, so an earlier flow may legitimately have emptied Needs You.
     * Refill it the way the app itself does — a PROCESSING run whose check time has arrived —
     * rather than writing state behind the domain's back.
     */
    private fun ensureQueue(min: Int) {
        val actions = VirlinGraph.actions
        kotlinx.coroutines.runBlocking {
            while (queue().size < min) {
                val processing = repo().streams.value.firstOrNull { it.state == WorkStreamState.PROCESSING } ?: break
                actions.continueProcessing(processing.id, VirlinGraph.clock.now().plusSeconds(1))
                actions.checkDue(processing.id)
            }
        }
        pump(600)
        assert(queue().size >= min) { "could not restore a Needs You queue: ${queue()}" }
    }

    // ------------------------------------------------------------------ A / C / D / E / F / G / H / M

    @Test fun rankOpensPriority_tabsSwitch_andAPositionAppliesWithoutClosing() {
        pump(1200)
        ensureQueue(2)
        val ids = queue()
        val target = ids.first()
        val waitingBefore = streamOf(target).let { it.checkAt to it.updatedAt }

        // A: the #n half opens the sheet on PRIORITY.
        touch(needsYouRankTag(target), 1200)
        composeRule.waitUntil(5_000) { exists(NeedsYouControlSheetTag) }
        tag(NeedsYouControlSheetTag).assertIsDisplayed()
        tag(NeedsYouControlTimerTag).assertIsDisplayed()          // P: the same waiting time, in the header
        assert(exists(priorityPositionTag(1))) { "Priority tab is the entry tab" }
        // E: every live position is offered.
        (1..ids.size).forEach { p -> assert(exists(priorityPositionTag(p))) { "position $p missing" } }

        // C/D: tabs switch freely, and switching writes nothing.
        touch(NeedsYouControlCheckTabTag, 700)
        assert(exists(NeedsYouControlStillRunningTag)) { "Check tab content" }
        assert(!exists(priorityPositionTag(2)))
        touch(NeedsYouControlPriorityTabTag, 700)
        assert(exists(priorityPositionTag(2)))
        assert(queue() == ids) { "switching tabs must not reorder anything" }

        // G: choosing another position applies immediately and the sheet STAYS open.
        touch(priorityPositionTag(2), 1200)
        composeRule.waitUntil(5_000) { queue().indexOf(target) == 1 }
        assert(exists(NeedsYouControlSheetTag)) { "the sheet stays open after a priority change" }
        assert(streamOf(target).attentionRank == 2) { "rank persisted through the existing path" }
        // H: the move changed nothing temporal.
        assert(streamOf(target).checkAt == waitingBefore.first) { "waiting target must not move" }

        // S: dismissing writes nothing further.
        val after = queue()
        touch(NeedsYouControlCloseTag, 900)
        composeRule.waitUntil(5_000) { !exists(NeedsYouControlSheetTag) }
        assert(queue() == after)
    }

    // ------------------------------------------------------------------ B / I / J / K / N

    @Test fun checkHalfOpensCheckTab_andAnActionRunsTheExistingTransition() {
        pump(1200)
        ensureQueue(1)
        val target = first()
        val rankBefore = streamOf(target).attentionRank

        // B: the CHECK half opens the SAME sheet, on the Check tab.
        touch("needs_you_primary_$target", 1200)
        composeRule.waitUntil(5_000) { exists(NeedsYouControlSheetTag) }
        assert(exists(NeedsYouControlStillRunningTag)) { "Check tab is the entry tab" }
        // I: only legal transitions are offered — "mark as done" is not one of them (CHECK -> DONE
        // is not in the transition table), so no such row exists.
        assert(exists(NeedsYouControlNotNowTag) && exists(NeedsYouControlBlockTag))
        assert(!exists("needs_you_control_complete"))
        // N: opening the Check tab changed no priority.
        assert(streamOf(target).attentionRank == rankBefore)

        // J/K: an action runs the existing domain transition, the item leaves Needs You and the
        // sheet closes — nothing is removed from UI state by hand.
        touch(NeedsYouControlNotNowTag, 1500)
        composeRule.waitUntil(5_000) { streamOf(target).state == WorkStreamState.READY }
        composeRule.waitUntil(5_000) { !exists(NeedsYouControlSheetTag) }
        assert(target !in queue())
        assert(composeRule.onAllNodesWithTag("needs_you_card_$target", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    // ------------------------------------------------------------------ L: still running reuses the presets

    @Test fun stillRunningUsesTheExistingCheckAgainPresets() {
        pump(1200)
        ensureQueue(1)
        val target = first()
        touch("needs_you_primary_$target", 1200)
        composeRule.waitUntil(5_000) { exists(NeedsYouControlSheetTag) }
        touch(NeedsYouControlStillRunningTag, 700)
        com.virlin.app.domain.attention.AttentionTiming.checkAgainPresets.forEach { m ->
            assert(exists("${NeedsYouControlStillRunningTag}_$m")) { "preset ${m}m missing" }
        }
        val before = streamOf(target).checkAt
        touch("${NeedsYouControlStillRunningTag}_5", 1500)
        // The existing stillRunning transition: back to the external process with a new check time.
        composeRule.waitUntil(5_000) { streamOf(target).state == WorkStreamState.PROCESSING }
        assert(streamOf(target).checkAt != before)
        composeRule.waitUntil(5_000) { !exists(NeedsYouControlSheetTag) }
    }
}
