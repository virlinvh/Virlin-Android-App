package com.virlin.app.now

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.attention.InMemoryPriorityPreferences
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.attention.PriorityScope
import com.virlin.app.domain.attention.TimeState
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.ui.screens.AttentionKind
import com.virlin.app.ui.screens.NeedsYouCard
import com.virlin.app.ui.screens.NeedsYouPriorityEditor
import com.virlin.app.ui.screens.PriorityEditorSaveTag
import com.virlin.app.ui.screens.needsYouRankTag
import com.virlin.app.ui.screens.priorityPositionTag
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Phase 05 flows on the rendered card: the timer is `dueAt − now` in every state, CHECK AGAIN
 * writes a new `dueAt` through the existing action, and priority editing leaves time alone.
 */
class AttentionTimingUiTest {

    @get:Rule val composeRule = createComposeRule()

    private val t0: Instant = Instant.parse("2026-09-22T10:00:00Z")
    private var nowValue by mutableStateOf(t0)
    private val nowState = mutableStateOf(t0)
    private val clock = object : VirlinClock { override fun now() = nowState.value }
    private val ids = object : IdProvider { private var n = 0; override fun newId(prefix: String) = "$prefix-${++n}" }
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var prefs: InMemoryPriorityPreferences
    private val checked = mutableListOf<String>()

    /** A · overdue 3:42 · B · due in 5:00 · C · long overdue (27h) */
    private fun seed() = listOf(
        WorkStream(id = "A", title = "Agent A", state = WorkStreamState.CHECK, checkAt = t0.minusSeconds(222), createdAt = t0.minusSeconds(9000), updatedAt = t0.minusSeconds(9000)),
        WorkStream(id = "B", title = "Agent B", state = WorkStreamState.CHECK, checkAt = t0.plusSeconds(300), createdAt = t0.minusSeconds(9000), updatedAt = t0.minusSeconds(9000)),
        WorkStream(id = "C", title = "Agent C", state = WorkStreamState.CHECK, checkAt = t0.minusSeconds(98142), createdAt = t0.minusSeconds(99000), updatedAt = t0.minusSeconds(99000))
    )
    private fun display(id: String) = MockData.streams.value.first().copy(
        id = id, subtitle = "Queue item $id", title = "Agent $id", projectId = "p1", state = StreamState.NEEDS_YOU
    )

    private fun show() {
        repo = InMemoryWorkStreamRepository(seed = seed())
        prefs = InMemoryPriorityPreferences()
        actions = DefaultVirlinActions(repo, clock, ids, prefs)
        checked.clear(); nowState.value = t0
        composeRule.setContent {
            val streams by repo.streams.collectAsState()
            val queue = NeedsYouOrder.queue(streams)
            var editing by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            VirlinTheme {
                Box(Modifier.width(411.dp).testTag("root")) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        queue.forEach { e ->
                            androidx.compose.runtime.key(e.stream.id) {
                                NeedsYouCard(
                                    stream = display(e.stream.id), index = e.rank - 1, kind = AttentionKind.CHECK_DUE,
                                    waitingSince = NeedsYouOrder.waitingSince(e.stream), dueAt = e.stream.checkAt, now = nowState,
                                    project = Project(id = "p1", title = "Virlin Development", createdAt = t0, updatedAt = t0),
                                    position = e.rank, total = queue.size,
                                    onCheck = { checked += it }, onChangePosition = { editing = it }
                                )
                            }
                        }
                    }
                    editing?.let { id ->
                        NeedsYouPriorityEditor(
                            stream = queue.firstOrNull { it.stream.id == id }?.let { display(id) },
                            currentPosition = queue.firstOrNull { it.stream.id == id }?.rank,
                            queueSize = queue.size, existing = runBlocking { prefs.get(id) }, now = t0,
                            onSave = { pos, sc -> editing = null; scope.launch { actions.setNeedsYouPriority(id, pos, sc) } },
                            onDismiss = { editing = null }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    private fun timerOf(id: String) = tag("needs_you_timer_$id").fetchSemanticsNode().config.toString().substringAfter("Text : [").substringBefore("]")
    private fun advance(seconds: Long) { nowState.value = nowState.value.plusSeconds(seconds); composeRule.waitForIdle() }
    private fun checkAgain(id: String, minutes: Long) = runBlocking { actions.continueProcessing(id, AttentionTiming.checkAgainAt(nowState.value, minutes)) }

    // ------------------------------------------------------------------ FLOW A / H

    @Test fun flowA_and_H_timerStatesRender_andLongDurationsStayAligned() {
        show()
        assertEquals("+00:03:42", timerOf("A"))              // overdue
        assertEquals("00:05:00", timerOf("B"))               // waiting
        assertEquals("+27:15:42", timerOf("C"))              // 27h, not wrapped
        listOf("A", "B", "C").forEach { tag("needs_you_primary_$it").assertIsDisplayed() }   // CHECK reachable
        val cards = listOf("A", "B", "C").map { tag("needs_you_card_$it").fetchSemanticsNode().boundsInRoot }
        assertEquals("same right edge", 1, cards.map { it.right }.distinct().size)
        assertEquals("same height", 1, cards.map { Math.round(it.height) }.distinct().size)
        val timer = tag("needs_you_timer_C").fetchSemanticsNode().boundsInRoot
        val action = tag("needs_you_primary_C").fetchSemanticsNode().boundsInRoot
        assertTrue("long timer does not collide", timer.right <= action.left + 1f)
    }

    // ------------------------------------------------------------------ DUE transition (16/17 of the spec)

    @Test fun waitingBecomesDueThenOverdue_withoutResetOrReorder() {
        show()
        val orderBefore = NeedsYouOrder.queue(repo.streams.value).map { it.stream.id }
        advance(299); assertEquals("00:00:01", timerOf("B"))
        advance(1); assertEquals("00:00:00", timerOf("B"))
        advance(1); assertEquals("+00:00:01", timerOf("B"))
        advance(41); assertEquals("+00:00:42", timerOf("B"))
        assertEquals(orderBefore, NeedsYouOrder.queue(repo.streams.value).map { it.stream.id })   // no reordering
        composeRule.onAllNodesWithTag("needs_you_card_B").assertCountEquals(1)                    // no duplicate item
    }

    // ------------------------------------------------------------------ FLOW B / C / E: check again

    @Test fun flowB_C_E_checkAgainSetsANewCountdown() {
        show()
        assertEquals(listOf(3L, 5L, 10L), AttentionTiming.checkAgainPresets)
        fun dueOf(id: String) = repo.streams.value.first { it.id == id }.checkAt
        assertEquals(TimeState.OVERDUE, AttentionTiming.state(dueOf("A"), nowState.value))
        checkAgain("A", 5)                                                     // exactly what the chooser preset calls
        composeRule.waitForIdle()
        // Existing Virlin contract: "still running" hands the item back to the external process
        // until its new due time, so it leaves Needs You and reappears when due.
        assertEquals(t0.plusSeconds(300), dueOf("A"))
        assertEquals(TimeState.WAITING, AttentionTiming.state(dueOf("A"), nowState.value))
        assertEquals("00:05:00", AttentionTiming.format(dueOf("A"), nowState.value))
        composeRule.onAllNodesWithTag("needs_you_card_A").assertCountEquals(0)
        nowState.value = t0.plusSeconds(300)
        runBlocking { actions.checkDue("A") }
        composeRule.waitForIdle()
        assertEquals("00:00:00", timerOf("A"))                                 // back, due, no duplicate
        composeRule.onAllNodesWithTag("needs_you_card_A").assertCountEquals(1)
        checkAgain("C", 3); assertEquals(nowState.value.plusSeconds(180), dueOf("C"))
        checkAgain("C", 10); assertEquals(nowState.value.plusSeconds(600), dueOf("C"))
    }

    // ------------------------------------------------------------------ FLOW D / I / J: time ⟂ priority

    @Test fun flowD_I_J_priorityEditingLeavesTimeAlone_andCheckStaysIndependent() {
        show()
        val before = listOf("A", "B", "C").associateWith { timerOf(it) }
        tag(needsYouRankTag("C")).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(com.virlin.app.ui.screens.PriorityEditorTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tag(priorityPositionTag(1)).performClick()
        tag(PriorityEditorSaveTag).performClick()
        composeRule.waitUntil(5_000) { NeedsYouOrder.queue(repo.streams.value).first().stream.id == "C" }
        composeRule.waitForIdle()
        listOf("A", "B", "C").forEach { assertEquals("timer $it", before[it], timerOf(it)) }      // D: no reset
        // I: the editor still opens afterwards; J: CHECK is untouched by rank editing.
        tag(needsYouRankTag("C")).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(com.virlin.app.ui.screens.PriorityEditorTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tag(com.virlin.app.ui.screens.PriorityEditorCancelTag).performClick(); composeRule.waitForIdle()
        tag("needs_you_primary_A").performClick()
        assertEquals(listOf("A"), checked)
    }

    // ------------------------------------------------------------------ FLOW F / G: cancel / dismiss change nothing

    @Test fun flowF_G_cancellingLeavesDueAtUntouched() {
        show()
        val before = repo.streams.value.associate { it.id to it.checkAt }
        tag(needsYouRankTag("A")).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(com.virlin.app.ui.screens.PriorityEditorTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tag(priorityPositionTag(2)).performClick()
        androidx.test.espresso.Espresso.pressBack(); composeRule.waitForIdle()
        repo.streams.value.forEach { assertEquals("dueAt ${it.id}", before[it.id], it.checkAt) }
        assertEquals("+00:03:42", timerOf("A"))
    }

    // ------------------------------------------------------------------ check again keeps the priority policy

    @Test fun checkAgainKeepsPriorityPolicyAndRank() {
        show()
        runBlocking { actions.setNeedsYouPriority("C", 1, PriorityScope.Always) }
        composeRule.waitForIdle()
        checkAgain("C", 5)
        nowState.value = t0.plus(Duration.ofMinutes(5))
        runBlocking { actions.checkDue("C") }
        composeRule.waitForIdle()
        assertEquals(1, NeedsYouOrder.effectiveRank(repo.streams.value, "C"))
        assertEquals(PriorityScope.Always, runBlocking { prefs.get("C") }!!.scope)
        assertEquals("00:00:00", timerOf("C"))
    }
}
