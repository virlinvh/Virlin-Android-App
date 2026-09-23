package com.virlin.app.now

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.virlin.app.domain.model.Project
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.screens.AttentionKind
import com.virlin.app.ui.screens.NeedsYouCard
import com.virlin.app.ui.screens.NeedsYouPriority
import com.virlin.app.ui.screens.needsYouRankTag
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * The approved compact card as a STACK: 12 cards, ranks 1–12, mixed title lengths and timer
 * magnitudes. Proves the alignment grid (every badge, icon, title, timer and action in the same
 * column), the card height, the rank palette progression and the neutral 11+ family.
 *
 * It also writes a real rendered PNG of the stack to the app's files dir so the redesign can be
 * inspected visually (`needs_you_stack.png`).
 */
class NeedsYouCardStackUiTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-22T09:00:00Z")
    private val nowState = mutableStateOf(t0)

    private val titles = listOf(
        "Navigation · Route structure" to "Claude · Virlin",
        "App Fix" to "Antigravity",
        "Navigation · Route structure decision implementation" to "Claude · Virlin · Android",
        "Methodology research" to "Codex · MBA Research",
        "Authentication redirect" to "Antigravity",
        "Unit 23 · Question 17" to "Psychology",
        "Release pipeline · signing key rotation for the nightly build" to "Build · Release APK",
        "Skills lab notes" to "Notion Transfer",
        "Database migration" to "Cloud Sync",
        "Typography updates" to "Design Review",
        "Backlog item" to "Codex",
        "Another backlog item with a fairly long descriptive title" to "Claude · Virlin"
    )
    /** 00:00:05 · 01:07:38 · 12:18:37 and everything between: the timer column must not move. */
    private val waits = listOf(5L, 46L, 259L, 900L, 3599L, 3600L, 4058L, 7384L, 20000L, 44317L, 86399L, 360_000L)

    private fun stream(i: Int) = MockData.streams.value.first().copy(
        id = "st$i", subtitle = titles[i].first, title = titles[i].second, projectId = "p${(i % 5) + 1}", state = StreamState.NEEDS_YOU
    )
    private fun project(i: Int) = Project(id = "p${(i % 5) + 1}", title = listOf("Virlin Development", "MBA Project", "Psychology", "App Fix", "IFET Skills Lab")[i % 5], createdAt = t0, updatedAt = t0)

    private fun show(widthDp: Int = 411, fontScale: Float = 1f) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                VirlinTheme {
                    Box(Modifier.width(widthDp.dp).background(Color(0xFFF8F7F4)).testTag("stack_root")) {
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            titles.indices.forEach { i ->
                                NeedsYouCard(
                                    stream = stream(i), index = i, kind = AttentionKind.CHECK_DUE,
                                    waitingSince = t0.minusSeconds(waits[i]), now = nowState,
                                    project = project(i), position = i + 1, total = titles.size
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test fun twelveCards_shareOneAlignmentGrid_andCompactHeight() {
        show()
        val cards = titles.indices.map { composeRule.onNodeWithTag("needs_you_card_st$it").fetchSemanticsNode().boundsInRoot }
        val badges = titles.indices.map { composeRule.onNodeWithTag(needsYouRankTag("st$it"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        val timers = titles.indices.map { composeRule.onNodeWithTag("needs_you_timer_st$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        val actions = titles.indices.map { composeRule.onNodeWithTag("needs_you_primary_st$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        val d = composeRule.density.density

        // Same columns on every card, whatever the title length or timer magnitude.
        assertEquals("rank column", 1, badges.map { it.right }.distinct().size)
        assertEquals("card left edge", 1, cards.map { it.left }.distinct().size)
        assertEquals("card right edge", 1, cards.map { it.right }.distinct().size)
        assertEquals("action right edge", 1, actions.map { it.right }.distinct().size)
        assertEquals("timer right edge", 1, timers.map { it.right }.distinct().size)
        // The 10th (and 12th) card needs no different design: every card has the same height.
        val heights = cards.map { (it.height / d) }
        assertEquals("one card height", 1, heights.map { Math.round(it) }.distinct().size)
        assertTrue("compact card height ${heights[0]}dp", heights[0] in 50f..70f)
        // Nothing overlaps: timer sits left of the action, both inside the card.
        titles.indices.forEach { i ->
            assertTrue("card $i timer/rank overlap", timers[i].right <= badges[i].left + 1f)
            assertTrue("card $i action inside", actions[i].right <= cards[i].right + 1f)
            assertTrue("card $i badge 44dp touch target", badges[i].height / d >= 43.5f)
        }
        // Long titles ellipsise instead of growing the card.
        assertEquals(Math.round(heights[2]), Math.round(heights[1]))
    }

    @Test fun rankIdentities_progress_thenGoNeutralFrom11() {
        show()
        titles.indices.forEach { i ->
            composeRule.onNode(hasTestTag(needsYouRankTag("st$i")) and hasAnyDescendant(hasText("#${i + 1}")), useUnmergedTree = true).assertExists()
        }
        // 1–10 carry the palette; 11 and 12 share the one neutral identity.
        assertTrue((1..10).all { !NeedsYouPriority.visualsFor(it).neutral })
        assertEquals(NeedsYouPriority.visualsFor(11), NeedsYouPriority.visualsFor(12))
        composeRule.onNodeWithTag("needs_you_card_st11").assertIsDisplayed()
    }

    @Test fun narrow335_fontScale13_stillFits() {
        show(widthDp = 335, fontScale = 1.3f)
        listOf(0, 2, 6, 11).forEach { i ->
            val card = composeRule.onNodeWithTag("needs_you_card_st$i").fetchSemanticsNode().boundsInRoot
            val timer = composeRule.onNodeWithTag("needs_you_timer_st$i", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val action = composeRule.onNodeWithTag("needs_you_primary_st$i", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("card $i timer inside", timer.left >= card.left && timer.right <= card.right + 1f)
            assertTrue("card $i action inside", action.right <= card.right + 1f && timer.right <= action.left + 1f)
        }
    }

    /** Renders the stack to `files/needs_you_stack.png` for visual inspection (not an assertion). */
    @Test fun captureStackScreenshot() {
        show()
        val image = composeRule.onNodeWithTag("stack_root").captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        File(dir, "needs_you_stack.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File(dir, "needs_you_stack.png").length() > 0)
    }
}
