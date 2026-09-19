package com.virlin.app.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.agent.AgentComposerTestTag
import com.virlin.app.ui.agent.AgentWorkspaceUiState
import com.virlin.app.ui.agent.ControlContext
import com.virlin.app.ui.agent.Destination
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.orb.VirlinOrbInteractionState
import com.virlin.app.ui.screens.AgentEntryCardsTestTag
import com.virlin.app.ui.screens.AgentEntryCommandTestTag
import com.virlin.app.ui.screens.AgentEntrySubtitleTestTag
import com.virlin.app.ui.screens.AgentEntryTitleTestTag
import com.virlin.app.ui.screens.AgentOrbSlotTestTag
import com.virlin.app.ui.screens.AgentShell
import com.virlin.app.ui.screens.agentModeTag
import com.virlin.app.ui.theme.VirlinColors
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Agent ENTRY launcher: no vertical scroll — Orb, identity, Control/Create/Capture, and
 * composer must all be visible together via responsive density.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentEntryLayoutTest {

    @get:Rule val composeRule = createComposeRule()

    private val contexts = listOf(ControlContext("s1", "Psychology", "Psychology", "Focus"))
    private val destinations = listOf(Destination("p1", "Virlin Development"))

    private fun setEntry(widthDp: Int, heightDp: Int, fontScale: Float = 1f) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale)
            ) {
                VirlinTheme {
                    Box(
                        Modifier
                            .width(widthDp.dp)
                            .height(heightDp.dp)
                            .background(VirlinColors.Background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    ) {
                        AgentShell(
                            state = VirlinOrbInteractionState.Ready,
                            workspace = AgentWorkspaceUiState(modeChosen = false),
                            composerText = "",
                            clarificationPrompt = null,
                            controlContexts = contexts,
                            destinations = destinations,
                            onModeSelected = {},
                            onControlContextSelected = {},
                            onCreateTypeSelected = {},
                            onCreateDestinationSelected = {},
                            onComposerTextChanged = {},
                            onToggleAttachmentMenu = {},
                            onAddAttachment = {},
                            onRemoveAttachment = {},
                            onSubmit = {},
                            onClarificationAnswered = {},
                            onReceiptAction = {},
                            onDismiss = {},
                            onOrbSlotPositioned = {},
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertLauncherFullyVisible() {
        composeRule.onNodeWithTag(AgentOrbSlotTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentEntryTitleTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentEntrySubtitleTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("How can I help?").assertIsDisplayed()
        composeRule.onNodeWithText("Turn your thoughts into action.").assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CONTROL)).assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CREATE)).assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CAPTURE)).assertIsDisplayed()
        composeRule.onNodeWithText("Or just tell me…").assertIsDisplayed()
        composeRule.onNodeWithTag(AgentComposerTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentEntryCommandTestTag).assertIsDisplayed()
    }

    private fun assertNoEntryCardScroll() {
        val scrollableCards = composeRule.onAllNodes(
            hasTestTag(AgentEntryCardsTestTag) and hasScrollAction(),
            useUnmergedTree = true
        ).fetchSemanticsNodes()
        assertTrue("Entry cards must not be vertically scrollable", scrollableCards.isEmpty())
    }

    private fun assertCaptureAboveCommand() {
        val captureBottom = composeRule.onNodeWithTag(agentModeTag(AgentMode.CAPTURE))
            .fetchSemanticsNode().boundsInRoot.bottom
        val commandTop = composeRule.onNodeWithTag(AgentEntryCommandTestTag)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "Capture (bottom=$captureBottom) must sit above command area (top=$commandTop)",
            captureBottom <= commandTop + 1f
        )
    }

    private fun assertIdentityAboveControl() {
        val subtitleBottom = composeRule.onNodeWithTag(AgentEntrySubtitleTestTag)
            .fetchSemanticsNode().boundsInRoot.bottom
        val controlTop = composeRule.onNodeWithTag(agentModeTag(AgentMode.CONTROL))
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(subtitleBottom <= controlTop + 1f)
    }

    @Test
    fun tall411_allPrimaryRoutesVisibleWithoutScroll() {
        setEntry(widthDp = 411, heightDp = 640)
        assertLauncherFullyVisible()
        assertNoEntryCardScroll()
        assertCaptureAboveCommand()
        assertIdentityAboveControl()
    }

    @Test
    fun normal393_allPrimaryRoutesVisibleWithoutScroll() {
        setEntry(widthDp = 393, heightDp = 560)
        assertLauncherFullyVisible()
        assertNoEntryCardScroll()
        assertCaptureAboveCommand()
    }

    @Test
    fun short360_allPrimaryRoutesVisibleWithoutScroll() {
        setEntry(widthDp = 360, heightDp = 480)
        assertLauncherFullyVisible()
        assertNoEntryCardScroll()
        assertCaptureAboveCommand()
    }

    @Test
    fun short320_allPrimaryRoutesVisibleWithoutScroll() {
        setEntry(widthDp = 320, heightDp = 460)
        assertLauncherFullyVisible()
        assertNoEntryCardScroll()
        assertCaptureAboveCommand()
    }

    @Test
    fun short360_fontScale13_allPrimaryRoutesVisibleWithoutScroll() {
        setEntry(widthDp = 360, heightDp = 520, fontScale = 1.3f)
        assertLauncherFullyVisible()
        assertNoEntryCardScroll()
        assertCaptureAboveCommand()
    }
}
