package com.virlin.app

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.ui.components.VirlinOrbContentDescription
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.screens.AgentCloseTestTag
import com.virlin.app.ui.agent.AgentComposerTestTag
import com.virlin.app.ui.agent.AgentSubmitTestTag
import com.virlin.app.ui.screens.AgentShellTestTag
import com.virlin.app.ui.screens.agentModeTag
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline Compose UI + accessibility checks for the approved Now surface, plus the Living
 * Orb interaction lifecycle. Selectors are semantic — never pixel coordinates.
 *
 * The Now screen runs `rememberInfiniteTransition` animations, so the Compose test clock
 * never reports idle; auto-advance is disabled in [setUp]. Interaction tests then drive
 * time explicitly with `mainClock.advanceTimeBy`.
 */
@RunWith(AndroidJUnit4::class)
class VirlinUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        composeRule.mainClock.autoAdvance = false
    }

    /**
     * Advance BOTH clocks together. The ViewModel's transients run on the real main looper
     * (viewModelScope), while recomposition and the open/close Animatable run on the Compose
     * test clock. Sleeping lets the looper fire; advancing a frame applies the result.
     */
    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        // Guarantee the Compose clock advances the full duration too: on a cold app a single
        // frame iteration can take far longer than 16ms of real time.
        val minFrames = (realMs / 16).toInt()
        var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) {
            composeRule.mainClock.advanceTimeByFrame()
            frames++
            Thread.sleep(8)
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun tapOrb() {
        // A real touch at the Orb's visible position — proves hit-testing, not just semantics.
        composeRule.onNodeWithTag(VirlinOrbTestTag).performTouchInput { click() }
        pump(500) // Opening (260ms) -> Ready, sheet fully risen
    }

    private fun closeAgent() {
        composeRule.onNodeWithTag(AgentCloseTestTag).performClick()
        pump(800) // Closing (360ms) -> Idle, sheet fully descended and uncomposed
    }

    // ---------------------------------------------------------------- Now baseline

    @Test
    fun nowScreen_showsCurrentFocusStream() {
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.FocusContextTag).assertIsDisplayed()
        composeRule.onNodeWithText("CURRENT FOCUS").assertIsDisplayed()
    }

    @Test
    fun bottomNavigation_showsAllFourDestinations() {
        composeRule.onNodeWithText("Now").assertIsDisplayed()
        composeRule.onNodeWithText("Streams").assertIsDisplayed()
        composeRule.onNodeWithText("Pulse").assertIsDisplayed()
        composeRule.onNodeWithText("Inbox").assertIsDisplayed()
    }

    @Test
    fun focusTimer_isExposedAsOneAccessibleElement() {
        composeRule.onNodeWithContentDescription("Focus invested", substring = true).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- 1. Orb semantics

    @Test
    fun orb_hasStableSemanticsAndAdequateTouchTarget() {
        composeRule.onNodeWithTag(VirlinOrbTestTag)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(VirlinOrbContentDescription)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    // ---------------------------------------------------------------- 2 & 3. Tappable, opens Agent

    @Test
    fun orb_isTappableAtVisiblePosition_andOpensAgent() {
        composeRule.onNodeWithTag(AgentShellTestTag).assertDoesNotExist()
        tapOrb()
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- 4. Close returns to Now

    @Test
    fun closingAgent_returnsToNow() {
        tapOrb()
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        closeAgent()
        composeRule.onNodeWithTag(AgentShellTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Psychology").assertIsDisplayed()
        composeRule.onNodeWithTag(VirlinOrbTestTag).assertIsDisplayed()
    }

    @Test
    fun androidBack_closesAgent_viaSamePath() {
        tapOrb()
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        Espresso.pressBack()
        pump(800)
        composeRule.onNodeWithTag(AgentShellTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Psychology").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- 5. Rapid double tap

    @Test
    fun rapidDoubleTap_opensExactlyOneAgent() {
        val orb = composeRule.onNodeWithTag(VirlinOrbTestTag)
        orb.performTouchInput { click() }
        orb.performTouchInput { click() }
        orb.performTouchInput { click() }
        pump(600)
        composeRule.onAllNodesWithTag(AgentShellTestTag).assertCountEquals(1)
        composeRule.onAllNodesWithTag(VirlinOrbTestTag).assertCountEquals(1)
    }

    // ---------------------------------------------------------------- 6. Content beneath untouched

    @Test
    fun orbTap_doesNotReachContentBeneath() {
        // The Orb overlaps the "Claude · Virlin" Needs You card, whose tap would make that
        // stream the Current Focus. After tapping the Orb, Psychology must still be in focus.
        tapOrb()
        closeAgent()
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.FocusContextTag).assertIsDisplayed()
        composeRule.onNodeWithText("CURRENT FOCUS").assertIsDisplayed()
    }

    // ---------------------------------------------------------------- 7. Mode controls visible

    @Test
    fun agent_showsModeControls() {
        tapOrb()
        // Entry step first: "How can I help?" with one card per mode; choosing Control transforms the sheet into the
        // CONTROL workspace only (← back · mode header · composer) — the mode tabs are never shown again.
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentEntryTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("How can I help?").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Control, Work with what you have").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Create, Build new structure").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Capture, Save information").assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(com.virlin.app.ui.orb.AgentMode.CONTROL)).performClick(); pump(400)
        composeRule.onNodeWithContentDescription("Control mode, Work with what you have").assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(com.virlin.app.ui.orb.AgentMode.CREATE)).assertDoesNotExist()
        composeRule.onNodeWithTag(agentModeTag(com.virlin.app.ui.orb.AgentMode.CAPTURE)).assertDoesNotExist()
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentBackTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentComposerTestTag).assertIsDisplayed()
    }

    // ---------------------------------------------------------------- 8. Close from transient

    @Test
    fun closingFromTransientState_returnsSafely_noStaleSuccess() {
        tapOrb()
        composeRule.onNodeWithTag(agentModeTag(com.virlin.app.ui.orb.AgentMode.CONTROL)).performClick(); pump(400)   // entry sheet → CONTROL
        pump(200)
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput("do it")
        pump(1_100) // settle -> ReadyWithInput
        composeRule.onNodeWithTag(AgentSubmitTestTag).performClick()
        pump(850) // Understanding -> Acting
        closeAgent()                              // dismiss mid-Acting
        pump(2_500) // well past when Success would have fired
        composeRule.onNodeWithTag(AgentShellTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Psychology").assertIsDisplayed()
        composeRule.onNodeWithTag(VirlinOrbTestTag).assertIsDisplayed()
    }
}
