package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.screens.NeedsYouTaskUi
import com.virlin.app.ui.screens.VirlinNeedsYouSection
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The capsule's 6.4-second cycle: the timer and CHECK each get their turn, the capsule never
 * changes size while they swap, and it is tappable throughout. The clock is driven by the test,
 * so this does not wait on real time — and the pixels are read back, because alpha is the only
 * thing that changes and semantics alone would not prove what is on screen.
 */
class NeedsYouCapsuleCycleTest {

    @get:Rule val composeRule = createComposeRule()

    private val t0 = 1_700_000_000_000L
    private val clicked = mutableListOf<String>()

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun show() {
        composeRule.setContent {
            VirlinTheme {
                Box(Modifier.width(411.dp)) {
                    VirlinNeedsYouSection(
                        tasks = listOf(
                            NeedsYouTaskUi(
                                id = "a", title = "Navigation · Route structure decision",
                                sourceAndContext = "Claude · Virlin",
                                checkDueAtEpochMillis = t0 - 85_000L
                            )
                        ),
                        nowEpochMillis = t0,
                        onCheck = { clicked += it },
                        onFilterClick = {},
                        animate = true,                    // the real cycle, not the resting state
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(16)
    }

    private fun capsuleBounds() =
        composeRule.onNodeWithTag("needs_you_primary_a").fetchSemanticsNode().boundsInRoot

    /** How much ink the capsule is showing — the two states draw different amounts of it. */
    private fun capsuleInk(): Int {
        val pixels = composeRule.onNodeWithTag("needs_you_primary_a").captureToImage().toPixelMap()
        var dark = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (c.red + c.green + c.blue < 1.8f) dark++      // glyph ink, not the pale surface
        }
        return dark
    }

    @Test fun bothStatesAppearInTheCycle_sameSize_andTappableThroughout() {
        show()
        val size = capsuleBounds()
        val samples = mutableListOf<Int>()

        // Walk one full 6.4s cycle in 400ms steps, sampling what the capsule is showing and
        // proving it stays the same size and stays clickable the whole way.
        repeat(16) { step ->
            composeRule.mainClock.advanceTimeBy(400)
            composeRule.mainClock.advanceTimeByFrame()
            val now = capsuleBounds()
            assertEquals("capsule width at step $step", Math.round(size.width), Math.round(now.width))
            assertEquals("capsule height at step $step", Math.round(size.height), Math.round(now.height))
            samples += capsuleInk()
            composeRule.onNodeWithTag("needs_you_primary_a").performClick()
        }

        // Two distinct states were on screen during the cycle: the timer (clock + eight glyphs)
        // paints noticeably more ink than "CHECK →".
        val min = samples.min()
        val max = samples.max()
        assertTrue("both states must appear (ink $min..$max)", max - min > 40)
        // Every tap landed, in both states.
        assertEquals(16, clicked.size)
        assertTrue(clicked.all { it == "a" })
    }
}
