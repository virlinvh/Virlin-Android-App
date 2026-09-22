package com.virlin.app.now

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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
import com.virlin.app.ui.screens.NeedsYouSort
import com.virlin.app.ui.screens.NeedsYouSortControl
import com.virlin.app.ui.screens.NeedsYouSortControlTag
import com.virlin.app.ui.screens.NeedsYouSortMode
import com.virlin.app.ui.screens.PriorityEditorSaveTag
import com.virlin.app.ui.screens.PriorityEditorTag
import com.virlin.app.ui.screens.needsYouRankTag
import com.virlin.app.ui.screens.needsYouSortOptionTag
import com.virlin.app.ui.screens.priorityPositionTag
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * Phase 06 flows: the header control switches the DISPLAY order only. Ranks, colours, timers and
 * every Phase 03–05 behaviour keep working underneath.
 */
class NeedsYouSortUiTest {

    @get:Rule val composeRule = createComposeRule()

    private val t0: Instant = Instant.parse("2026-09-22T12:00:00Z")
    private val nowState = mutableStateOf(t0)
    private val clock = object : VirlinClock { override fun now() = nowState.value }
    private val ids = object : IdProvider { private var n = 0; override fun newId(prefix: String) = "$prefix-${++n}" }
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions
    private lateinit var prefs: InMemoryPriorityPreferences
    private val checked = mutableListOf<String>()
    private var mode by mutableStateOf(NeedsYouSortMode.PRIORITY)

    /** Canonical A(#1) B(#2) C(#3); due A 11:40, B 11:58, C 11:45. */
    private fun seed(n: Int = 3) = listOf(
        item("A", "11:40", 1), item("B", "11:58", 2), item("C", "11:45", 3)
    ).take(n)
    private fun item(id: String, hm: String, rank: Int) = WorkStream(
        id = id, title = "Agent $id", state = WorkStreamState.CHECK, checkAt = Instant.parse("2026-09-22T$hm:00Z"),
        attentionRank = rank, createdAt = t0.minusSeconds(86400), updatedAt = t0.minusSeconds(86400)
    )
    private fun display(id: String) = MockData.streams.value.first().copy(
        id = id, subtitle = "Queue item $id", title = "Agent $id", projectId = "p1", state = StreamState.NEEDS_YOU
    )

    private fun show(items: List<WorkStream> = seed()) {
        repo = InMemoryWorkStreamRepository(seed = items)
        prefs = InMemoryPriorityPreferences()
        actions = DefaultVirlinActions(repo, clock, ids, prefs)
        checked.clear(); mode = NeedsYouSortMode.PRIORITY; nowState.value = t0
        composeRule.setContent {
            val streams by repo.streams.collectAsState()
            val queue = NeedsYouOrder.queue(streams)
            val shown = NeedsYouSort.display(queue, mode)
            var editing by remember { mutableStateOf<String?>(null) }
            var sortOpen by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            VirlinTheme {
                Box(Modifier.width(411.dp).testTag("root")) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NeedsYouSortControl(
                                mode = mode, expanded = sortOpen, enabled = shown.isNotEmpty(),
                                onExpandedChange = { sortOpen = it }, onSelect = { mode = it }
                            )
                        }
                        shown.forEach { e ->
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
                            queueSize = queue.size, existing = prefs.get(id), now = t0,
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
    private fun openMenu() {
        tag(NeedsYouSortControlTag).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(needsYouSortOptionTag(NeedsYouSortMode.PRIORITY), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun choose(m: NeedsYouSortMode) { openMenu(); tag(needsYouSortOptionTag(m)).performClick(); composeRule.waitForIdle() }
    /** Order on screen, top to bottom. */
    private fun onScreen() = listOf("A", "B", "C")
        .mapNotNull { id -> composeRule.onAllNodesWithTag("needs_you_card_$id").fetchSemanticsNodes().firstOrNull()?.let { id to it.boundsInRoot.top } }
        .sortedBy { it.second }.map { it.first }
    private fun timerOf(id: String) = tag("needs_you_timer_$id").fetchSemanticsNode().config.toString().substringAfter("Text : [").substringBefore("]")
    private fun rankShown(id: String, rank: Int) =
        composeRule.onNode(hasTestTag(needsYouRankTag(id)) and hasAnyDescendant(hasText("$rank")), useUnmergedTree = true).assertExists()

    // ------------------------------------------------------------------ FLOW A / I

    @Test fun flowA_menuOpensWithPriorityDefault_andCorrectSemantics() {
        show()
        tag(NeedsYouSortControlTag).assertIsDisplayed().assertContentDescriptionContains("Sort Needs You", substring = true)
        openMenu()
        tag(needsYouSortOptionTag(NeedsYouSortMode.PRIORITY)).assertIsSelected().assertContentDescriptionContains("Priority", substring = true)
        tag(needsYouSortOptionTag(NeedsYouSortMode.LONGEST_WAITING)).assertIsNotSelected().assertContentDescriptionContains("Longest waiting", substring = true)
        tag(needsYouSortOptionTag(NeedsYouSortMode.MOST_RECENT)).assertIsNotSelected().assertContentDescriptionContains("Most recent", substring = true)
        assertEquals(listOf("A", "B", "C"), onScreen())
        val touch = tag(NeedsYouSortControlTag).fetchSemanticsNode().size.height / composeRule.density.density
        assertTrue("touch target $touch", touch >= 39.5f)
    }

    // ------------------------------------------------------------------ FLOW B / C / D / G / H

    @Test fun flowB_C_D_switchingViewsKeepsRanksTimersAndItems() {
        show()
        val timers = listOf("A", "B", "C").associateWith { timerOf(it) }
        choose(NeedsYouSortMode.LONGEST_WAITING)
        assertEquals(listOf("A", "C", "B"), onScreen())                    // oldest overdue first
        rankShown("A", 1); rankShown("C", 3); rankShown("B", 2)            // canonical badges unchanged
        tag(NeedsYouSortControlTag).assertContentDescriptionContains("Longest waiting selected", substring = true)
        choose(NeedsYouSortMode.MOST_RECENT)
        assertEquals(listOf("B", "C", "A"), onScreen())                    // newest due first
        rankShown("B", 2)                                                   // still #2, not #1
        choose(NeedsYouSortMode.PRIORITY)
        assertEquals(listOf("A", "B", "C"), onScreen())
        listOf("A", "B", "C").forEach { assertEquals("timer $it", timers[it], timerOf(it)) }        // G
        listOf("A", "B", "C").forEach { composeRule.onAllNodesWithTag("needs_you_card_$it").assertCountEquals(1) }   // H
        assertEquals(listOf(1, 2, 3), NeedsYouOrder.queue(repo.streams.value).map { it.rank })
    }

    // ------------------------------------------------------------------ FLOW E

    @Test fun flowE_priorityEditUnderAlternateSort_keepsTheView() {
        show()
        choose(NeedsYouSortMode.LONGEST_WAITING)
        assertEquals(listOf("A", "C", "B"), onScreen())
        tag(needsYouRankTag("C")).performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(PriorityEditorTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        tag(priorityPositionTag(1)).performClick()
        tag(PriorityEditorSaveTag).performClick()
        composeRule.waitUntil(5_000) { NeedsYouOrder.queue(repo.streams.value).first().stream.id == "C" }
        composeRule.waitForIdle()
        assertEquals(NeedsYouSortMode.LONGEST_WAITING, mode)               // the view is NOT forced back
        assertEquals(listOf("A", "C", "B"), onScreen())                    // display still by due time
        rankShown("C", 1); rankShown("A", 2)                               // canonical ranks did change
    }

    // ------------------------------------------------------------------ FLOW F

    @Test fun flowF_checkAgainUnderAlternateSort_leavesAValidList() {
        show()
        choose(NeedsYouSortMode.LONGEST_WAITING)
        tag("needs_you_primary_A").performClick()
        assertEquals(listOf("A"), checked)                                  // CHECK still fires
        runBlocking { actions.continueProcessing("A", AttentionTiming.checkAgainAt(nowState.value, 5)) }
        composeRule.waitForIdle()
        assertEquals(listOf("C", "B"), onScreen())                          // A left Needs You; view still applies
        assertEquals(NeedsYouSortMode.LONGEST_WAITING, mode)
        rankShown("C", 2); rankShown("B", 1)                                // ranks re-densified by the queue
    }

    // ------------------------------------------------------------------ FLOW J

    @Test fun flowJ_singleItemIsSafe() {
        show(items = seed(1))
        tag(NeedsYouSortControlTag).assertIsDisplayed()
        choose(NeedsYouSortMode.MOST_RECENT)
        assertEquals(listOf("A"), onScreen())
        rankShown("A", 1)
    }

    @Test fun flowJ_emptyQueueIsSafe() {
        show(items = emptyList())
        tag(NeedsYouSortControlTag).assertIsDisplayed()                     // present but inert with nothing to sort
        tag(NeedsYouSortControlTag).performClick(); composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(needsYouSortOptionTag(NeedsYouSortMode.PRIORITY), useUnmergedTree = true).assertCountEquals(0)
        assertTrue(onScreen().isEmpty())
    }

    // ------------------------------------------------------------------ preferences are canonical, not visual

    @Test fun alwaysPreferenceMeansCanonicalPosition_notScreenPosition() {
        show()
        runBlocking { actions.setNeedsYouPriority("B", 1, PriorityScope.Always) }
        composeRule.waitForIdle()
        choose(NeedsYouSortMode.LONGEST_WAITING)
        assertEquals(listOf("A", "C", "B"), onScreen())                     // B is last on screen …
        assertEquals(1, NeedsYouOrder.effectiveRank(repo.streams.value, "B"))  // … and still canonical #1
        rankShown("B", 1)
        assertEquals(1, prefs.get("B")!!.preferredPosition)
    }
}
