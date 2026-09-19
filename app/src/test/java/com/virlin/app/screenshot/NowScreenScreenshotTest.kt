package com.virlin.app.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.ui.screens.NowScreen
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Visual regression baseline for the APPROVED / FROZEN Now screen.
 *
 * Determinism notes:
 *  - [com.virlin.app.mock.MockData] seeds fixed values in its initializer, and
 *    MockTimerEngine is only started by MainActivity. Rendering NowScreen directly
 *    therefore yields a frozen, reproducible clock (focusInvestedSec = 0 -> 00:00).
 *    (Previously seeded 1955/32:35; that lied before FocusSession truth and corrupted first flips.)
 *  - mainClock.autoAdvance is disabled so the screen's infinite transitions (pulsing
 *    beacons, colon fade) are pinned to frame 0 instead of sampling at random phases.
 *
 * Scope: this captures the Now content only. The Virlin Orb is NOT part of NowScreen —
 * it lives in VirlinApp's bottomBar — so the AGSL RuntimeShader, which Robolectric
 * cannot render, is deliberately out of frame. Orb visuals are validated on the
 * emulator instead. See docs/DEVELOPMENT_TOOLCHAIN.md.
 *
 * PASS 3 (hierarchy-aware Current Focus): the card now shows Project / WorkStream / active
 * Task from the domain, which necessarily moves pixels below the title. The APPROVED
 * baseline `now_screen.png` is kept byte-identical and NOT verified until the user approves the
 * diff; this test verifies the Pass 3 candidate `now_screen_hierarchy.png` instead.
 *
 * GOLDEN RULE: never re-record after a failure without explicit user approval of the diff.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NowScreenScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun nowScreen_matchesApprovedBaseline() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            VirlinTheme {
                NowScreen(rememberNavController())
            }
        }

        composeRule.onRoot().captureRoboImage("src/test/screenshots/now_screen_hierarchy.png")
    }
}
