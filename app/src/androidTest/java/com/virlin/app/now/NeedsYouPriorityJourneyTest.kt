package com.virlin.app.now

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.MainActivity
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.screens.FocusContextTag
import com.virlin.app.ui.screens.PriorityEditorSaveTag
import com.virlin.app.ui.screens.priorityPositionTag
import com.virlin.app.ui.screens.needsYouRankTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The user journey through the real app: see position → tap → choose → the card moves; the change
 * goes through `VirlinActions.reorderNeedsYou` (Phase 1) and lands in the repository; waiting
 * timestamps and `updatedAt` never move.
 */
class NeedsYouPriorityJourneyTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** This test drives real actions (ranks, checkDue, markReady): start from the demo fixture, whatever ran before. */
    @get:Rule val rules: org.junit.rules.RuleChain =
        org.junit.rules.RuleChain.outerRule(com.virlin.app.DemoStateRule()).around(composeRule)

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    /** Semantic click (the accessibility action): position-independent, like TalkBack's double tap. */
    private fun touch(t: String, after: Long = 700) {
        // Let the scroll-into-view animation SETTLE before tapping: a tap on still-moving content is read as a scroll and cancelled.
        runCatching { tag(t).performScrollTo() }.onSuccess { pump(1200) }
        tag(t).performTouchInput { click() }; pump(after)
    }
    private fun needsYou() = NeedsYouOrder.order(VirlinGraph.repository.streams.value)

    @Test fun tapBadge_choosePosition_cardMoves_persisted_timersUntouched() {
        pump(900); tag(FocusContextTag).assertIsDisplayed()
        // G: bring every PROCESSING stream into Needs You through the real action (new arrivals are unranked, after the ranked block)
        runBlocking { VirlinGraph.repository.streams.value.filter { it.state == WorkStreamState.PROCESSING }.forEach { VirlinGraph.actions.checkDue(it.id) } }; pump(900)
        val before = needsYou()
        assertTrue(before.all { it.attentionRank == null })                                             // A: nothing ranked yet → waiting-time order
        assertEquals(before.map { NeedsYouOrder.waitingSince(it) }, before.map { NeedsYouOrder.waitingSince(it) }.sorted())
        assertTrue("need ≥ 3 Needs You items, have ${before.size}", before.size >= 3)
        val stamps = before.associate { it.id to Triple(NeedsYouOrder.waitingSince(it), it.updatedAt, it.checkAt) }
        val last = before.last()

        // B: last → 2
        touch(needsYouRankTag(last.id), 1500)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(priorityPositionTag(2), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before.size, (1..before.size).count { composeRule.onAllNodesWithTag(priorityPositionTag(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })   // N
        assertTrue(composeRule.onAllNodesWithTag(priorityPositionTag(before.size + 1), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        touch(priorityPositionTag(2), 1200); touch(com.virlin.app.ui.screens.NeedsYouControlCloseTag, 900)

        val after = needsYou()
        val expected = before.toMutableList().also { it.add(1, it.removeAt(it.lastIndex)) }.map { it.id }
        assertEquals(expected, after.map { it.id })
        assertEquals((1..before.size).toList(), after.map { it.attentionRank })                          // E: dense, unique
        assertEquals(2, runBlocking { VirlinGraph.repository.getStream(last.id) }!!.attentionRank)     // persisted through the repository
        after.forEach { s ->                                                                             // J: waiting basis + updatedAt untouched
            assertEquals(stamps[s.id]!!.first, NeedsYouOrder.waitingSince(s)); assertEquals(stamps[s.id]!!.second, s.updatedAt); assertEquals(stamps[s.id]!!.third, s.checkAt)
        }
        // The card now sits second on screen and says so
        tag(needsYouRankTag(last.id)).performScrollTo(); pump(300)
        composeRule.onNodeWithTag(needsYouRankTag(last.id), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(2, after.indexOfFirst { it.id == last.id } + 1)

        // D: third → 1, then C: first → last
        val third = after[2]
        touch(needsYouRankTag(third.id), 900); touch(priorityPositionTag(1), 1200); touch(com.virlin.app.ui.screens.NeedsYouControlCloseTag, 900)
        assertEquals(third.id, needsYou().first().id)
        val first = needsYou().first()
        touch(needsYouRankTag(first.id), 900); touch(priorityPositionTag(needsYou().size), 1200); touch(com.virlin.app.ui.screens.NeedsYouControlCloseTag, 900)
        val finalOrder = needsYou()
        assertEquals(first.id, finalOrder.last().id)
        assertEquals((1..finalOrder.size).toList(), finalOrder.map { it.attentionRank })

        // H: leaving Needs You clears the rank (Phase 1 rule) — the remaining order is unchanged
        runBlocking { VirlinGraph.actions.markReady(finalOrder.first().id) }; pump(600)
        assertEquals(null, runBlocking { VirlinGraph.repository.getStream(finalOrder.first().id) }!!.attentionRank)
        assertEquals(finalOrder.drop(1).map { it.id }, needsYou().map { it.id })
        assertTrue(VirlinGraph.repository.streams.value.first { it.id == finalOrder.first().id }.state == WorkStreamState.READY)
    }
}
