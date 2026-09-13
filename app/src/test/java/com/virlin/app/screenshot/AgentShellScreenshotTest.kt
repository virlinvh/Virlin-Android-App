package com.virlin.app.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.ui.agent.AgentWorkspaceUiState
import com.virlin.app.ui.agent.ControlContext
import com.virlin.app.ui.agent.CreateType
import com.virlin.app.ui.agent.Destination
import com.virlin.app.ui.agent.InputObject
import com.virlin.app.ui.agent.Receipt
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.orb.VirlinOrbInteractionState
import com.virlin.app.ui.screens.AgentShell
import com.virlin.app.ui.theme.VirlinColors
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Deterministic goldens for the Agent workspace shell, one per mode. The shell is rendered
 * standalone with fixed state (no ViewModel, no Orb travel, no animation) at the sheet's
 * Pixel-8 size. The Orb slot is intentionally empty here — the Orb is drawn by VirlinApp.
 *
 * GOLDEN RULE: never re-record after a failure without explicit user approval of the diff.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentShellScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contexts = listOf(
        ControlContext("s1", "Psychology", "Psychology", "Focus"),
        ControlContext("s2", "Antigravity", "App Fix", "Check due"),
        ControlContext("s3", "Claude", "Virlin Development", "Check due"),
        ControlContext("s4", "Claude", "Virlin Development", "Processing")
    )
    private val destinations = listOf(
        Destination("p1", "Virlin Development"), Destination("p2", "MBA Project"),
        Destination("p3", "Psychology"), Destination("p4", "App Fix")
    )

    @Composable
    private fun Host(state: VirlinOrbInteractionState, ws: AgentWorkspaceUiState, text: String, prompt: String? = null) {
        VirlinTheme {
            Box(
                Modifier.width(411.dp).height(548.dp)
                    .background(VirlinColors.Background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            ) {
                AgentShell(
                    state = state, workspace = ws, composerText = text, clarificationPrompt = prompt,
                    controlContexts = contexts, destinations = destinations,
                    onModeSelected = {}, onControlContextSelected = {}, onCreateTypeSelected = {},
                    onCreateDestinationSelected = {}, onComposerTextChanged = {}, onToggleAttachmentMenu = {},
                    onAddAttachment = {}, onRemoveAttachment = {}, onSubmit = {}, onClarificationAnswered = {},
                    onReceiptAction = {}, onDismiss = {}, onOrbSlotPositioned = {}
                )
            }
        }
    }

    /** Entry step ("How can I help?"): compact Orb slot · heading · subtitle · three mode cards · "Ask anything…". */
    @Test
    fun agentEntry() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { Host(VirlinOrbInteractionState.Ready, AgentWorkspaceUiState(modeChosen = false), text = "") }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/agent_entry.png")
    }

    @Test
    fun agentControl() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Host(
                VirlinOrbInteractionState.Ready,
                AgentWorkspaceUiState(mode = AgentMode.CONTROL, selectedControlContextId = "s3"),
                text = ""
            )
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/agent_control.png")
    }

    @Test
    fun agentCreate() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Host(
                VirlinOrbInteractionState.Ready,
                AgentWorkspaceUiState(
                    mode = AgentMode.CREATE, createType = CreateType.TASK, createDestinationId = "p1",
                    receipt = Receipt(Receipt.Kind.SUCCESS, "Task added", "Test Orb state animation",
                        "Virlin Development", listOf("Open", "Undo"))
                ),
                text = ""
            )
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/agent_create.png")
    }

    @Test
    fun agentCapture() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            Host(
                VirlinOrbInteractionState.ReadyWithInput,
                AgentWorkspaceUiState(
                    mode = AgentMode.CAPTURE,
                    attachments = listOf(
                        InputObject.Voice("a1", 38),
                        InputObject.Prompt("a2", "Claude implementation prompt",
                            "You are implementing the Virlin Living Orb…\n1. Preserve the shader\n2. Modulate by state", 14),
                        InputObject.Link("a3", "https://github.com/android/compose-samples", "github.com", "android/compose-samples")
                    )
                ),
                text = "Reference for the Orb → Agent transition"
            )
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/agent_capture.png")
    }
}
