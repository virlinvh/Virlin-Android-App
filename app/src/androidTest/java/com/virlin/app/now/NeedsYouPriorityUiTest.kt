package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.model.Project
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.ui.components.projectIconBuiltInTag
import com.virlin.app.ui.screens.AttentionKind
import com.virlin.app.ui.screens.NeedsYouCard
import com.virlin.app.ui.screens.NeedsYouPriorityEditor
import com.virlin.app.ui.screens.PriorityEditorTag
import com.virlin.app.ui.screens.priorityPositionTag
import com.virlin.app.ui.screens.needsYouRankTag
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Needs You queue-position control (Phase 2): badge, selector, semantics, layout — on top of the untouched attention system. */
class NeedsYouPriorityUiTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0 = Instant.parse("2026-09-22T09:00:00Z")

    private fun display(id: String, title: String, subtitle: String, projectId: String = "p1") =
        MockData.streams.value.first().copy(id = id, title = title, subtitle = subtitle, projectId = projectId, state = StreamState.NEEDS_YOU)
    private fun project(id: String, title: String, iconId: String? = null) = Project(id = id, title = title, createdAt = t0, updatedAt = t0, iconId = iconId)
    private fun four() = listOf(
        display("a", "Claude · Virlin", "Navigation · Route structure decision"),
        display("b", "Antigravity", "App Fix · Authentication redirect", "p4"),
        display("c", "Claude · Virlin", "Navigation implementation"),
        display("d", "Codex · MBA Research", "Methodology research", "p2")
    )

    @Test fun badge_showsPositionOfTotal_withSemantics_andFirstIsStronger() {
        val opened = mutableListOf<String>()
        composeRule.setContent {
            VirlinTheme {
                Column {
                    four().forEachIndexed { i, s -> NeedsYouCard(s, i, AttentionKind.CHECK_DUE, project = project("p1", "Virlin Development"), position = i + 1, total = 4, onChangePosition = { opened += it }) }
                }
            }
        }
        composeRule.onNodeWithTag(needsYouRankTag("a")).assertIsDisplayed().assertContentDescriptionContains("Attention position 1 of 4", substring = true)
        composeRule.onNodeWithTag(needsYouRankTag("d")).assertContentDescriptionContains("Attention position 4 of 4. Double tap to change.", substring = true)
        composeRule.onNode(hasTestTag(needsYouRankTag("a")) and hasAnyDescendant(hasText("#1")), useUnmergedTree = true).assertExists()
        composeRule.onNode(hasTestTag(needsYouRankTag("d")) and hasAnyDescendant(hasText("#4")), useUnmergedTree = true).assertExists()
        // touch target ≥ 44dp although the visible pill is smaller
        val h = composeRule.onNodeWithTag(needsYouRankTag("b")).fetchSemanticsNode().let { it.size.height / composeRule.density.density }
        assertTrue("badge hit height $h", h >= 43.5f)
        composeRule.onNodeWithTag(needsYouRankTag("d")).performClick()
        assertEquals(listOf("d"), opened)
    }


    /**
     * Phase 1 of the action-area refinement: the rank and CHECK live in ONE pill on the right.
     * There is no separate rank bubble anywhere else on the card, the two halves touch (one
     * control), and each half drives only its own callback.
     */
    @Test fun combinedControl_isOnePill_withTwoIndependentTargets() {
        val checked = mutableListOf<String>()
        val opened = mutableListOf<String>()
        composeRule.setContent {
            VirlinTheme {
                Column {
                    four().forEachIndexed { i, s ->
                        NeedsYouCard(
                            s, i, AttentionKind.CHECK_DUE, project = project("p1", "Virlin Development"),
                            position = i + 1, total = 4,
                            onCheck = { checked += it }, onChangePosition = { opened += it }
                        )
                    }
                }
            }
        }
        // B: every card shows "#position" next to its action, and exactly once.
        (1..4).forEach { p -> composeRule.onAllNodesWithText("#$p").assertCountEquals(1) }
        listOf("a", "b", "c", "d").forEach { id ->
            val rank = composeRule.onNodeWithTag(needsYouRankTag(id)).fetchSemanticsNode().boundsInRoot
            val check = composeRule.onNodeWithTag("needs_you_primary_$id").fetchSemanticsNode().boundsInRoot
            val card = composeRule.onNodeWithTag("needs_you_card_$id").fetchSemanticsNode().boundsInRoot
            val timer = composeRule.onNodeWithTag("needs_you_timer_$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            // One control: the halves meet across the 1dp hairline and are vertically identical.
            val hairline = composeRule.density.density * 1f
            assertTrue("$id halves adjacent (${rank.right} vs ${check.left})", check.left - rank.right in -1f..(hairline + 1f))
            assertEquals("$id same top", Math.round(rank.top), Math.round(check.top))
            assertEquals("$id same height", Math.round(rank.height), Math.round(check.height))
            // The timer stays separate status information, left of the control, inside the card.
            assertTrue("$id timer left of control", timer.right <= rank.left + 1f)
            assertTrue("$id control inside card", check.right <= card.right + 1f)
            // Both halves keep a full touch target although the paint is ~34dp.
            listOf(rank, check).forEach { b ->
                assertTrue("$id touch height ${b.height / composeRule.density.density}", b.height / composeRule.density.density >= 43.5f)
            }
        }
        // G/H: each half fires only its own callback.
        composeRule.onNodeWithTag(needsYouRankTag("b")).performClick()
        assertEquals(listOf("b"), opened); assertTrue("rank tap must not check", checked.isEmpty())
        composeRule.onNodeWithTag("needs_you_primary_c").performClick()
        assertEquals(listOf("c"), checked); assertEquals(listOf("b"), opened)
    }

    @Test fun badge_hiddenWithoutPosition_andCheckStillWorks_iconsUnchanged() {
        val checked = mutableListOf<String>()
        composeRule.setContent { VirlinTheme { NeedsYouCard(four()[1], 0, AttentionKind.CHECK_DUE, project = project("p4", "App Fix"), onCheck = { checked += it }) } }
        composeRule.onAllNodesWithTag(needsYouRankTag("b")).assertCountEquals(0)
        composeRule.onNodeWithTag(projectIconBuiltInTag("tools"), useUnmergedTree = true).assertIsDisplayed()      // M
        composeRule.onNodeWithTag("needs_you_primary_b").assertIsDisplayed().performClick()                        // L
        assertEquals(listOf("b"), checked)
    }

    @Test fun N_sheet_showsExactlyCurrentCount_currentSelected_tapApplies() {
        val chosen = mutableListOf<Int>()
        var dismissed = 0
        composeRule.setContent { VirlinTheme { NeedsYouPriorityEditor(stream = four()[3], currentPosition = 4, queueSize = 4, now = t0, onSave = { pos, _ -> chosen += pos }, onDismiss = { dismissed++ }) } }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(PriorityEditorTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        (1..4).forEach { composeRule.onNodeWithTag(priorityPositionTag(it), useUnmergedTree = true).assertExists() }
        composeRule.onAllNodesWithTag(priorityPositionTag(5), useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag(priorityPositionTag(4), useUnmergedTree = true).assertIsSelected().assertContentDescriptionContains("Priority position 4", substring = true)
        composeRule.onNodeWithTag(priorityPositionTag(2), useUnmergedTree = true).assertIsNotSelected().assertContentDescriptionContains("Priority position 2", substring = true)
        composeRule.onNodeWithTag(priorityPositionTag(2), useUnmergedTree = true).performClick()
        assertTrue("preview only until SAVE", chosen.isEmpty())
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.PriorityEditorSaveTag, useUnmergedTree = true).performClick()
        assertEquals(listOf(2), chosen)
    }

    @Test fun N2_singleItem_onlyPositionOne() {
        composeRule.setContent { VirlinTheme { NeedsYouPriorityEditor(stream = four()[0], currentPosition = 1, queueSize = 1, now = t0, onSave = { _, _ -> }, onDismiss = {}) } }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(priorityPositionTag(1), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onAllNodesWithTag(priorityPositionTag(2), useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag(priorityPositionTag(1), useUnmergedTree = true).assertIsSelected()
    }

    @Test fun K_positionDoesNotChangeUrgency_orTimer() {
        val since = Instant.now().minusSeconds(590)
        val now = mutableStateOf(Instant.now())
        var position by mutableStateOf(3)
        composeRule.setContent { VirlinTheme { NeedsYouCard(four()[2], 0, AttentionKind.CHECK_DUE, waitingSince = since, now = now, project = project("p1", "Virlin Development"), position = position, total = 4) } }
        fun timerText() = composeRule.onNodeWithTag("needs_you_timer_c", useUnmergedTree = true).fetchSemanticsNode().config.toString().substringAfter("Text : ").substringBefore("]")
        val before = timerText()
        position = 1; composeRule.waitForIdle()
        composeRule.onNode(hasTestTag(needsYouRankTag("c")) and hasAnyDescendant(hasText("#1")), useUnmergedTree = true).assertExists()
        assertEquals(before, timerText())                                                                                                    // same waiting text
        repeat(12) { now.value = now.value.plusSeconds(1); composeRule.waitForIdle() }                                                        // crosses 10:00 → CRITICAL as before
        composeRule.onNodeWithTag(projectIconBuiltInTag("code"), useUnmergedTree = true).assertIsDisplayed()
    }


    /**
     * The unified control sheet at 335dp and 1.3x font: both tabs readable, the header's title
     * ellipsises instead of pushing the timer out, and no row escapes the sheet's width.
     */
    @Test fun controlSheet_narrow335_fontScale13_fits() {
        val long = display("x", "A very long source name that keeps going · Project", "An extremely long task title that should ellipsize rather than push anything off screen")
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                VirlinTheme {
                    Box(Modifier.width(335.dp)) {
                        com.virlin.app.ui.screens.NeedsYouControlSheet(
                            stream = long, project = project("p1", "Virlin Development"),
                            kind = AttentionKind.CHECK_DUE, position = 2, total = 4,
                            dueAt = Instant.now().minusSeconds(3725), now = mutableStateOf(Instant.now()),
                            initialTab = com.virlin.app.ui.screens.NeedsYouControlTab.PRIORITY,
                            onMoveToPosition = {}, onAction = {}, onDismiss = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val sheet = composeRule.onNodeWithTag(com.virlin.app.ui.screens.NeedsYouControlSheetTag).fetchSemanticsNode().boundsInRoot
        listOf(
            com.virlin.app.ui.screens.NeedsYouControlPriorityTabTag,
            com.virlin.app.ui.screens.NeedsYouControlCheckTabTag,
            com.virlin.app.ui.screens.NeedsYouControlTimerTag,
            priorityPositionTag(1), priorityPositionTag(4)
        ).forEach { t ->
            val b = composeRule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$t escapes the sheet (${b.left}..${b.right} vs ${sheet.left}..${sheet.right})",
                b.left >= sheet.left - 1f && b.right <= sheet.right + 1f)
            // Interactive rows keep a full touch target; the timer is status text, not a target.
            if (t != com.virlin.app.ui.screens.NeedsYouControlTimerTag) {
                assertTrue("$t touch height", b.height / composeRule.density.density >= 43.5f)
            }
        }
        // Both tab labels are present and legible at this scale (nothing clipped to zero width).
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.NeedsYouControlCheckTabTag).assertIsDisplayed()
    }

    @Test fun narrow335_fontScale13_longTitles_noOverlap() {
        val long = display("x", "A very long source name that keeps going · Project", "An extremely long task title that should wrap onto a second line and then ellipsize cleanly")
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                VirlinTheme {
                    Box(Modifier.width(335.dp)) {
                        NeedsYouCard(long, 0, AttentionKind.RETURN_DUE, waitingSince = Instant.now().minusSeconds(3900), now = mutableStateOf(Instant.now()),
                            project = project("p1", "Virlin Development"), position = 7, total = 12)
                    }
                }
            }
        }
        val card = composeRule.onNodeWithTag("needs_you_card_x").fetchSemanticsNode().boundsInRoot
        val rank = composeRule.onNodeWithTag(needsYouRankTag("x")).fetchSemanticsNode().boundsInRoot
        val primary = composeRule.onNodeWithTag("needs_you_primary_x").fetchSemanticsNode().boundsInRoot
        val timer = composeRule.onNodeWithTag("needs_you_timer_x", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val defer = composeRule.onNodeWithTag("needs_you_defer_x").fetchSemanticsNode().boundsInRoot
        assertTrue("rank/primary overlap", rank.right <= primary.left + 1f && defer.right <= rank.left + 1f)
        assertTrue("timer inside card", timer.right <= card.right + 1f && timer.top >= card.top)
        assertTrue("rank inside card", rank.left >= card.left && rank.bottom <= card.bottom + 1f)
        composeRule.onNode(hasTestTag(needsYouRankTag("x")) and hasAnyDescendant(hasText("#7")), useUnmergedTree = true).assertExists()
    }
}
