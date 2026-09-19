package com.virlin.app.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.agent.capture.AgentCaptureArea
import com.virlin.app.ui.agent.capture.AgentCaptureTag
import com.virlin.app.ui.agent.capture.AgentCaptureViewModel
import com.virlin.app.ui.agent.capture.CaptureChooseContextTag
import com.virlin.app.ui.agent.capture.CaptureContextLineTag
import com.virlin.app.ui.agent.capture.CaptureFileImageTag
import com.virlin.app.ui.agent.capture.CaptureInboxTag
import com.virlin.app.ui.agent.capture.CaptureVoiceTag
import com.virlin.app.ui.agent.capture.captureTypeTag
import com.virlin.app.ui.theme.VirlinColors
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Capture launcher content (five cards). Shell golden `agent_capture.png` is untouched.
 * Live golden `agent_capture_live.png` awaits explicit re-record approval after this layout pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentCaptureScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")

    @Test fun agentCaptureLive_launcherOnly() {
        val repo = InMemoryWorkStreamRepository()
        val clock = FakeClock(t0)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        runBlocking {
            actions.createCapture(CreateCapture(CaptureType.NOTE, content = "hidden from launcher", id = "hidden"))
        }
        val vm = AgentCaptureViewModel(actions, repo, clock)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            VirlinTheme {
                Box(
                    Modifier
                        .width(411.dp)
                        .height(720.dp)
                        .background(VirlinColors.Background)
                        .padding(20.dp)
                ) {
                    AgentCaptureArea(vm, Modifier.fillMaxSize())
                }
            }
        }
        repeat(5) { composeRule.mainClock.advanceTimeByFrame() }

        composeRule.onNodeWithTag(AgentCaptureTag).assertIsDisplayed()
        composeRule.onNodeWithTag(captureTypeTag(CaptureType.NOTE), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(captureTypeTag(CaptureType.PROMPT), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(captureTypeTag(CaptureType.LINK), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(CaptureFileImageTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(CaptureVoiceTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(CaptureContextLineTag).assertDoesNotExist()
        composeRule.onNodeWithTag(CaptureChooseContextTag).assertDoesNotExist()
        composeRule.onNodeWithTag(CaptureInboxTag).assertDoesNotExist()
        composeRule.onNodeWithText("Global · Inbox").assertDoesNotExist()
        composeRule.onNodeWithText("hidden from launcher", substring = true).assertDoesNotExist()

        // Do not overwrite golden without explicit approval — capture to a distinct path for review only.
        composeRule.onRoot().captureRoboImage("src/test/screenshots/agent_capture_live_launcher.png")
    }
}
