package com.virlin.app.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.ui.screens.AttentionKind
import com.virlin.app.ui.screens.CurrentFocusProjection
import com.virlin.app.ui.screens.FocusHeroCard
import com.virlin.app.ui.screens.NeedsYouCard
import com.virlin.app.ui.theme.Pearl
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Pass 4 candidates: the Current Focus card with the finalized labels (human: LEAVE /
 * COMPLETE; external: LEAVE / HAND OFF) and the three Needs You attention variants. Cards
 * are rendered standalone with fixed display data and fixed projections — no ViewModel, no
 * ticker. The approved `now_screen.png` / `app_scaffold.png` stay untouched.
 *
 * GOLDEN RULE: never re-record after a failure without explicit user approval of the diff.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttentionExitScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val human = CurrentFocusProjection("s1", "Psychology", "Psychology", "p_q17", "Question 17", "Complete Q17 answer", WorkStreamState.FOCUS, isExternal = false)
    private val external = CurrentFocusProjection("s4", "Virlin Development", "Antigravity", "t_nl", "Natural Language", "Review parser output", WorkStreamState.FOCUS, isExternal = true)

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        VirlinTheme { Box(Modifier.width(411.dp).background(Pearl).padding(16.dp)) { content() } }
    }

    private fun capture(name: String) {
        composeRule.mainClock.autoAdvance = false
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun display(id: String, state: StreamState) = MockData.streams.value.first { it.id == id }.copy(state = state)

    @Test fun focusHuman() {
        composeRule.setContent { Host { FocusHeroCard(display("s1", StreamState.FOCUS), rememberNavController(), hierarchy = human) } }
        capture("now_focus_human")
    }

    @Test fun focusExternal() {
        composeRule.setContent { Host { FocusHeroCard(display("s4", StreamState.FOCUS), rememberNavController(), hierarchy = external) } }
        capture("now_focus_external")
    }

    @Test fun needsYouReturnDue() {
        composeRule.setContent { Host { NeedsYouCard(display("s1", StreamState.NEEDS_YOU), 0, kind = AttentionKind.RETURN_DUE) } }
        capture("needs_you_return_due")
    }

    @Test fun needsYouCheckDue() {
        composeRule.setContent { Host { NeedsYouCard(display("s2", StreamState.NEEDS_YOU), 0, kind = AttentionKind.CHECK_DUE) } }
        capture("needs_you_check_due")
    }

    @Test fun needsYouResultReady() {
        composeRule.setContent { Host { NeedsYouCard(display("s2", StreamState.NEEDS_YOU), 1, kind = AttentionKind.RESULT_READY) } }
        capture("needs_you_result_ready")
    }
}
