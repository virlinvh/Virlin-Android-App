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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attention.InMemoryPriorityPreferences
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.attention.PriorityScope
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
import com.virlin.app.ui.screens.PriorityEditorCancelTag
import com.virlin.app.ui.screens.PriorityEditorCountTag
import com.virlin.app.ui.screens.PriorityEditorSaveTag
import com.virlin.app.ui.screens.PriorityEditorTag
import com.virlin.app.ui.screens.needsYouRankTag
import com.virlin.app.ui.screens.priorityPositionTag
import com.virlin.app.ui.screens.priorityScopeTag
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Phase 04 flows A–G: the rank badge opens the priority editor, the editor is preview-only, SAVE
 * goes through the Phase 03 engine, Cancel/dismiss never write, CHECK and timers are untouched.
 * Wired to the real domain (in-memory repository + `DefaultVirlinActions`), not to fakes.
 */
class PriorityEditorUiTest {

    @get:Rule val composeRule = createComposeRule()

    private val t0: Instant = Instant.parse("2026-09-22T09:00:00Z")
    private val nowState = mutableStateOf(t0)
    private val names = listOf("A", "B", "C", "D", "E")
    private val clock = object : VirlinClock { override fun now() = t0 }
    private val ids = object : IdProvider { private var n = 0; override fun newId(prefix: String) = "$prefix-${++n}" }
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var prefs: InMemoryPriorityPreferences
    private val checked = mutableListOf<String>()

    private fun seed(n: Int = 5) = (0 until n).map { i ->
        WorkStream(id = names[i], title = "Agent ${names[i]}", state = WorkStreamState.CHECK,
            checkAt = t0.minusSeconds((n - i) * 600L), createdAt = t0.minusSeconds(7200), updatedAt = t0.minusSeconds(7200))
    }
    private fun display(id: String) = MockData.streams.value.first().copy(
        id = id, subtitle = "Queue item $id", title = "Agent $id", projectId = "p1", state = StreamState.NEEDS_YOU
    )

    private fun show(n: Int = 5) {
        repo = InMemoryWorkStreamRepository(seed = seed(n))
        prefs = InMemoryPriorityPreferences()
        actions = DefaultVirlinActions(repo, clock, ids, prefs)
        checked.clear()
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
                                    waitingSince = NeedsYouOrder.waitingSince(e.stream), now = nowState,
                                    project = Project(id = "p1", title = "Virlin Development", createdAt = t0, updatedAt = t0),
                                    position = e.rank, total = queue.size,
                                    onCheck = { checked += it },
                                    onChangePosition = { editing = it }
                                )
                            }
                        }
                    }
                    editing?.let { id ->
                        NeedsYouPriorityEditor(
                            stream = queue.firstOrNull { it.stream.id == id }?.let { display(id) },
                            currentPosition = queue.firstOrNull { it.stream.id == id }?.rank,
                            queueSize = queue.size,
                            existing = runBlocking { prefs.get(id) },
                            now = t0,
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
    private fun openEditorOn(id: String) {
        tag(needsYouRankTag(id)).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(PriorityEditorTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun queueIds() = NeedsYouOrder.queue(repo.streams.value).map { it.stream.id }
    private fun timerOf(id: String) = tag("needs_you_timer_$id").fetchSemanticsNode().config.toString().substringAfter("Text : ").substringBefore("]")

    // ------------------------------------------------------------------ FLOW A

    @Test fun flowA_tappingRankOpensEditorOnItsOwnPosition_withTheRealCount() {
        show()
        openEditorOn("D")
        tag(PriorityEditorTag).assertIsDisplayed()
        tag(PriorityEditorCountTag).assertTextEquals("5 activities waiting")
        tag(priorityPositionTag(4)).assertIsSelected()                        // its current rank, not 1
        tag(priorityPositionTag(1)).assertIsNotSelected()
        composeRule.onAllNodesWithTag(priorityPositionTag(6), useUnmergedTree = true).assertCountEquals(0)   // only 1..N
        tag(priorityScopeTag(PriorityScope.OneTime)).assertIsSelected()       // "This time" is the default
        composeRule.onNode(hasTestTag(PriorityEditorTag) and hasAnyDescendant(hasText("Queue item D")), useUnmergedTree = true).assertExists()
    }

    // ------------------------------------------------------------------ FLOW B

    @Test fun flowB_choosePositionThenSave_movesTheItem() {
        show()
        val timersBefore = names.associateWith { timerOf(it) }
        openEditorOn("D")
        tag(priorityPositionTag(1)).performClick()
        tag(priorityPositionTag(1)).assertIsSelected()
        assertEquals(listOf("A", "B", "C", "D", "E"), queueIds())             // still nothing written
        tag(PriorityEditorSaveTag).performClick()
        composeRule.waitUntil(5_000) { queueIds().first() == "D" }
        composeRule.waitForIdle()
        assertEquals(listOf("D", "A", "B", "C", "E"), queueIds())
        composeRule.onAllNodesWithTag(PriorityEditorTag, useUnmergedTree = true).assertCountEquals(0)        // sheet closed
        composeRule.onNode(hasTestTag(needsYouRankTag("D")) and hasAnyDescendant(hasText("#1")), useUnmergedTree = true).assertExists()
        names.forEach { assertEquals("timer $it", timersBefore[it], timerOf(it)) }                            // FLOW G
    }

    // ------------------------------------------------------------------ FLOW C / E

    @Test fun flowC_and_E_cancelOrDismissNeverSaves() {
        show()
        openEditorOn("D")
        tag(priorityPositionTag(1)).performClick()
        tag(PriorityEditorCancelTag).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("A", "B", "C", "D", "E"), queueIds())
        assertTrue(runBlocking { prefs.all() }.isEmpty())
        // Dismiss (back / swipe) takes the same path as Cancel.
        openEditorOn("E")
        tag(priorityPositionTag(2)).performClick()
        androidx.test.espresso.Espresso.pressBack()
        composeRule.waitForIdle()
        assertEquals(listOf("A", "B", "C", "D", "E"), queueIds())
        assertTrue(runBlocking { prefs.all() }.isEmpty())
    }

    // ------------------------------------------------------------------ FLOW D

    @Test fun flowD_alwaysScopeIsStored_andShownWhenReopened() {
        show()
        openEditorOn("E")
        tag(priorityPositionTag(2)).performClick()
        tag(priorityScopeTag(PriorityScope.Always)).performClick()
        tag(priorityScopeTag(PriorityScope.Always)).assertIsSelected()
        tag(PriorityEditorSaveTag).performClick()
        composeRule.waitUntil(5_000) { queueIds()[1] == "E" }
        composeRule.waitForIdle()
        assertEquals(listOf("A", "E", "B", "C", "D"), queueIds())
        assertEquals(2, runBlocking { prefs.get("E") }!!.preferredPosition)
        assertEquals(PriorityScope.Always, runBlocking { prefs.get("E") }!!.scope)
        // Reopening represents the stored preference, not the default.
        openEditorOn("E")
        tag(priorityScopeTag(PriorityScope.Always)).assertIsSelected()
        tag(priorityPositionTag(2)).assertIsSelected()
        tag(PriorityEditorCancelTag).performClick()
    }

    // ------------------------------------------------------------------ FLOW F

    @Test fun flowF_checkStillWorks_independentOfPriority() {
        show()
        openEditorOn("C")
        tag(priorityPositionTag(1)).performClick()
        tag(PriorityEditorSaveTag).performClick()
        composeRule.waitUntil(5_000) { queueIds().first() == "C" }
        composeRule.waitForIdle()
        tag("needs_you_primary_C").performClick()
        assertEquals(listOf("C"), checked)                                     // CHECK fires, unchanged
        assertEquals(listOf("C", "A", "B", "D", "E"), queueIds())              // and priority editing did not consume it
        assertNull(runBlocking { prefs.get("C") })                                             // "This time" stores nothing
    }

    // ------------------------------------------------------------------ small / large queues

    @Test fun smallQueue_offersOnlyItsPositions_andLargeQueueOffersTheRest() {
        show(n = 1)
        openEditorOn("A")
        tag(PriorityEditorCountTag).assertTextEquals("1 activity waiting")
        tag(priorityPositionTag(1)).assertIsSelected()
        composeRule.onAllNodesWithTag(priorityPositionTag(2), useUnmergedTree = true).assertCountEquals(0)
        tag(PriorityEditorCancelTag).performClick()
        composeRule.waitForIdle()

        // A 12-item queue keeps 1–10 direct and exposes 11+ through the compact row.
        repo = InMemoryWorkStreamRepository(seed = (1..12).map {
            WorkStream(id = "i$it", title = "i$it", state = WorkStreamState.CHECK, checkAt = t0.minusSeconds((13 - it) * 600L),
                createdAt = t0.minusSeconds(7200), updatedAt = t0.minusSeconds(7200))
        })
        actions = DefaultVirlinActions(repo, clock, ids, prefs)
        assertEquals(12, NeedsYouOrder.queue(repo.streams.value).size)
        runBlocking { actions.setNeedsYouPriority("i12", 11, PriorityScope.OneTime) }
        assertEquals(11, NeedsYouOrder.effectiveRank(repo.streams.value, "i12"))
    }
}
