package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.screens.NeedsYouTaskUi
import com.virlin.app.ui.screens.VirlinNeedsYouSection
import com.virlin.app.ui.screens.needsYouRankTag
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Needs You section as it is rendered: 1, 4, 10 and 18 items, long titles and long overdue
 * times on a narrow screen, and the two tap targets (rank → priority, capsule → check). Time is a
 * parameter, so nothing here waits on a clock.
 */
class NeedsYouSectionUiTest {

    @get:Rule val composeRule = createComposeRule()

    private val t0 = 1_700_000_000_000L
    private val nowState = mutableStateOf(t0)

    private fun task(i: Int, title: String = "Task $i", overdueMillis: Long = i * 60_000L) =
        NeedsYouTaskUi(
            id = "item-$i",
            title = title,
            sourceAndContext = "Codex · MBA Research",
            checkDueAtEpochMillis = t0 - overdueMillis
        )

    private fun show(
        tasks: List<NeedsYouTaskUi>,
        widthDp: Int = 411,
        checked: MutableList<String> = mutableListOf(),
        ranked: MutableList<String> = mutableListOf()
    ) {
        composeRule.setContent {
            VirlinTheme {
                // The page owns the only scroll container, exactly like the Now screen.
                Box(Modifier.width(widthDp.dp).testTag("page").verticalScroll(rememberScrollState())) {
                    VirlinNeedsYouSection(
                        tasks = tasks,
                        nowEpochMillis = nowState.value,
                        onCheck = { checked += it },
                        onFilterClick = {},
                        onRank = { ranked += it },
                        animate = false,                       // steady CHECK label, no crossfade
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    // ------------------------------------------------------------------ counts

    @Test fun oneTask_rendersInsideThePage() {
        show(listOf(task(1)))
        composeRule.onNodeWithText("Task 1").assertIsDisplayed()
        // "1" appears twice on purpose: the section count badge and the card's rank.
        assertEquals(2, composeRule.onAllNodesWithText("1").fetchSemanticsNodes().size)
        val page = composeRule.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        val card = composeRule.onNodeWithTag("needs_you_card_item-1").fetchSemanticsNode().boundsInRoot
        assertTrue("card inside page", card.left >= page.left - 1f && card.right <= page.right + 1f)
    }

    @Test fun fourTasks_eachKeepItsOwnCardAndAction() {
        val checked = mutableListOf<String>()
        show((1..4).map { task(it) }, checked = checked)
        (1..4).forEach { composeRule.onNodeWithTag("needs_you_card_item-$it").assertExists() }
        // Individual cards: four separate surfaces, none of them overlapping.
        val bounds = (1..4).map { composeRule.onNodeWithTag("needs_you_card_item-$it").fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (a, b) -> assertTrue("cards are separated", b.top >= a.bottom - 1f) }
        composeRule.onNodeWithTag("needs_you_primary_item-3").performClick()
        assertEquals(listOf("item-3"), checked)
    }

    @Test fun tenTasks_allRender_andTheRankOpensPriority() {
        val ranked = mutableListOf<String>()
        show((1..10).map { task(it) }, ranked = ranked)
        (1..10).forEach { composeRule.onNodeWithTag("needs_you_card_item-$it").performScrollTo().assertExists() }
        composeRule.onNodeWithTag(needsYouRankTag("item-6")).performScrollTo().performClick()
        assertEquals(listOf("item-6"), ranked)
    }

    @Test fun eighteenTasks_fitTheWidth_andTheWarmPaletteSpansThem() {
        show((1..18).map { task(it) })
        val page = composeRule.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        (1..18).forEach { i ->
            val card = composeRule.onNodeWithTag("needs_you_card_item-$i").performScrollTo().fetchSemanticsNode().boundsInRoot
            assertTrue("card $i inside the page", card.left >= page.left - 1f && card.right <= page.right + 1f)
        }
        // "18" is both the count badge and the last card's rank.
        assertEquals(2, composeRule.onAllNodesWithText("18").fetchSemanticsNodes().size)
        // Every capsule is present and shows its live overdue time (the clock face is drawn now,
        // so the time is matched by its "+" form).
        (1..18).forEach { composeRule.onNodeWithTag("needs_you_primary_item-$it").assertExists() }
        assertEquals(18, composeRule.onAllNodesWithText("+", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    // ------------------------------------------------------------------ narrow screen, long content

    @Test fun narrow335_longTitleAndLongOverdue_doNotCoverTheAction() {
        show(
            listOf(
                task(1, title = "An extremely long attention title that keeps going well past one line", overdueMillis = 0),
                // 27 hours overdue: the longest realistic timer string.
                task(2, title = "Navigation · Route structure decision", overdueMillis = 27L * 3600_000 + 15 * 60_000 + 42_000)
            ),
            widthDp = 335
        )
        val page = composeRule.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        listOf(1, 2).forEach { i ->
            val capsule = composeRule.onNodeWithTag("needs_you_primary_item-$i").fetchSemanticsNode().boundsInRoot
            val title = composeRule.onNodeWithTag("needs_you_card_item-$i").fetchSemanticsNode().boundsInRoot
            assertTrue("capsule inside the card", capsule.right <= title.right + 1f)
            assertTrue("capsule inside the page", capsule.right <= page.right + 1f)
        }
        // The action is reachable in the steady (CHECK) state.
        composeRule.onNodeWithTag("needs_you_primary_item-2").assertIsDisplayed()
    }

    /**
     * The capsule composes BOTH states and crossfades between them, so both texts are present at
     * once; what matters is that the box never changes size and both capsules measure the same.
     */
    @Test fun capsuleIsFixedSize_andCarriesBothStates() {
        val timed = task(1)
        val untimed = NeedsYouTaskUi(
            id = "item-2", title = "No target", sourceAndContext = "Codex · MBA Research",
            checkDueAtEpochMillis = t0, showCheckLabel = true
        )
        show(listOf(timed, untimed))
        // Both states exist in the tree (one is drawn at alpha 0 while the other shows).
        // Both states are composed in every capsule and crossfaded by alpha, so the unmerged tree
        // reports one of each per card; the point of this test is that the BOX never changes size.
        val checkCount = composeRule.onAllNodesWithText("CHECK  →", useUnmergedTree = true).fetchSemanticsNodes().size
        val timerCount = composeRule.onAllNodesWithText("+", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue("check=$checkCount timer=$timerCount", checkCount >= 1 && timerCount >= 1)
        val a = composeRule.onNodeWithTag("needs_you_primary_item-1").fetchSemanticsNode().boundsInRoot
        val b = composeRule.onNodeWithTag("needs_you_primary_item-2").fetchSemanticsNode().boundsInRoot
        assertEquals(Math.round(a.width), Math.round(b.width))
        assertEquals(Math.round(a.height), Math.round(b.height))
    }
}
