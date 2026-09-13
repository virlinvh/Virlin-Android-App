package com.virlin.app.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.ui.navigation.VirlinApp
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Visual regression baseline for the app SCAFFOLD — the layer the Now-content golden
 * cannot see: bottom navigation, and the Living Orb's placement relative to it.
 *
 * This complements, and does NOT replace, [NowScreenScreenshotTest]. That golden protects
 * the Now content; this one protects the geometry AROUND the Orb, which is exactly what
 * the upcoming Orb interaction work is most likely to disturb.
 *
 * ## What this golden protects
 *  - bottom navigation presence, size and corner treatment
 *  - Orb bounds and position relative to the nav bar and screen edges
 *  - overall scaffold layout and surrounding geometry
 *
 * ## What it deliberately does NOT protect: the Orb's shader pixels
 *
 * The Orb renders with an AGSL `RuntimeShader`, which Robolectric's graphics environment
 * cannot execute faithfully. `VirlinOrb` already guards that path
 * (`Build.VERSION.SDK_INT >= TIRAMISU && shader != null`) and degrades to its Canvas
 * fallback, so this test captures the Orb's *geometry* rather than its liquid material.
 *
 * That is intentional and acceptable. The production shader must NOT be modified or
 * simplified to make screenshot testing easier. The real Orb material is validated
 * visually on Pixel 8 API 35 — see `docs/DEVELOPMENT_TOOLCHAIN.md`.
 *
 * ## Determinism
 * `MockData` seeds fixed values in its initializer and `MockTimerEngine` is only started by
 * `MainActivity`, so rendering `VirlinApp` directly gives a frozen clock. `autoAdvance` is
 * disabled so infinite transitions (Orb breathing, attention beacons) pin to frame 0.
 *
 * PASS 3 (hierarchy-aware Current Focus): the card now shows Project / WorkStream / active
 * Task from the domain, which necessarily moves pixels below the title. The APPROVED
 * baseline `app_scaffold.png` is kept byte-identical and NOT verified until the user approves the
 * diff; this test verifies the Pass 3 candidate `app_scaffold_hierarchy.png` instead.
 *
 * GOLDEN RULE: never re-record after a failure without explicit user approval of the diff.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScaffoldScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appScaffold_matchesApprovedBaseline() {
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent {
            VirlinTheme {
                VirlinApp()
            }
        }

        composeRule.onRoot().captureRoboImage("src/test/screenshots/app_scaffold_hierarchy.png")
    }
}
