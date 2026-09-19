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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.agent.AgentComposerTestTag
import com.virlin.app.ui.agent.AgentWorkspaceUiState
import com.virlin.app.ui.agent.ControlContext
import com.virlin.app.ui.agent.Destination
import com.virlin.app.ui.agent.capture.AgentCaptureArea
import com.virlin.app.ui.agent.capture.AgentCaptureTag
import com.virlin.app.ui.agent.capture.AgentCaptureViewModel
import com.virlin.app.ui.agent.capture.CaptureChooseContextTag
import com.virlin.app.ui.agent.capture.CaptureContextLineTag
import com.virlin.app.ui.agent.capture.CaptureFileImageTag
import com.virlin.app.ui.agent.capture.CaptureInboxTag
import com.virlin.app.ui.agent.capture.CaptureLauncherCardsTag
import com.virlin.app.ui.agent.capture.CaptureVoiceTag
import com.virlin.app.ui.agent.capture.captureRowTag
import com.virlin.app.ui.agent.capture.captureTypeTag
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.orb.VirlinOrbInteractionState
import com.virlin.app.ui.screens.AgentShell
import com.virlin.app.ui.theme.VirlinColors
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import kotlin.math.abs

/**
 * Capture launcher: five equal-geometry type cards + pinned composer, no vertical scroll
 * under normal phone dimensions. Voice must never be a clipped leftover.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentCaptureLauncherTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-15T10:00:00Z")

    private val contexts = listOf(ControlContext("s1", "Psychology", "Psychology", "Focus"))
    private val destinations = listOf(Destination("p1", "Virlin Development"))

    private fun newVm(): AgentCaptureViewModel {
        val repo = InMemoryWorkStreamRepository()
        val clock = FakeClock(t0)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        return AgentCaptureViewModel(actions, repo, clock)
    }

    @Test
    fun launcher_showsFiveCards_hidesContextHelperAndInbox() {
        val repo = InMemoryWorkStreamRepository()
        val clock = FakeClock(t0)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        runBlocking {
            actions.createCapture(
                CreateCapture(CaptureType.NOTE, content = "should not appear in launcher", id = "cap_old")
            )
        }
        val vm = AgentCaptureViewModel(actions, repo, clock)
        var noteOpened = false
        var promptOpened = false
        var linkOpened = false
        var fileOpened = false
        var voiceOpened = false

        composeRule.setContent {
            VirlinTheme {
                Box(Modifier.width(411.dp).height(720.dp)) {
                    AgentCaptureArea(
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                        onOpenTextNote = { noteOpened = true },
                        onOpenPrompt = { promptOpened = true },
                        onOpenLink = { linkOpened = true },
                        onOpenFile = { fileOpened = true },
                        onOpenVoice = { voiceOpened = true }
                    )
                }
            }
        }

        composeRule.onNodeWithTag(AgentCaptureTag).assertIsDisplayed()

        val note = composeRule.onNodeWithTag(captureTypeTag(CaptureType.NOTE), useUnmergedTree = true)
        val prompt = composeRule.onNodeWithTag(captureTypeTag(CaptureType.PROMPT), useUnmergedTree = true)
        val link = composeRule.onNodeWithTag(captureTypeTag(CaptureType.LINK), useUnmergedTree = true)
        val file = composeRule.onNodeWithTag(CaptureFileImageTag, useUnmergedTree = true)
        val voice = composeRule.onNodeWithTag(CaptureVoiceTag, useUnmergedTree = true)
        note.assertIsDisplayed()
        prompt.assertIsDisplayed()
        link.assertIsDisplayed()
        file.assertIsDisplayed()
        voice.assertIsDisplayed()

        val noteY = note.fetchSemanticsNode().boundsInRoot.top
        val promptY = prompt.fetchSemanticsNode().boundsInRoot.top
        val linkY = link.fetchSemanticsNode().boundsInRoot.top
        val fileY = file.fetchSemanticsNode().boundsInRoot.top
        val voiceY = voice.fetchSemanticsNode().boundsInRoot.top
        assertTrue(noteY < promptY && promptY < linkY && linkY < fileY && fileY < voiceY)

        composeRule.onNodeWithText("Text Note", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Prompt", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Link", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("File / Image", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Voice", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Record a voice note", useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag(CaptureContextLineTag).assertDoesNotExist()
        composeRule.onNodeWithTag(CaptureChooseContextTag).assertDoesNotExist()
        composeRule.onNodeWithTag(CaptureInboxTag).assertDoesNotExist()
        composeRule.onNodeWithTag(captureRowTag("cap_old")).assertDoesNotExist()
        composeRule.onNodeWithText("Global · Inbox").assertDoesNotExist()
        composeRule.onNodeWithText("CHOOSE CONTEXT").assertDoesNotExist()
        composeRule.onNodeWithText("Tap Text Note", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("quick plain note", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("should not appear in launcher", substring = true).assertDoesNotExist()

        composeRule.onAllNodes(hasTestTag(AgentCaptureTag) and hasScrollAction()).assertCountEquals(0)

        note.performClick()
        prompt.performClick()
        link.performClick()
        file.performClick()
        voice.performClick()
        assertTrue(noteOpened && promptOpened && linkOpened && fileOpened && voiceOpened)
    }

    private fun setCaptureShell(widthDp: Int, heightDp: Int, fontScale: Float = 1f) {
        val vm = newVm()
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
                            workspace = AgentWorkspaceUiState(
                                modeChosen = true,
                                mode = AgentMode.CAPTURE
                            ),
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
                            captureContent = {
                                AgentCaptureArea(
                                    vm = vm,
                                    modifier = Modifier.fillMaxSize(),
                                    onOpenTextNote = {},
                                    onOpenPrompt = {},
                                    onOpenLink = {},
                                    onOpenFile = {},
                                    onOpenVoice = {}
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun assertFiveEqualCardsVisible() {
        val note = composeRule.onNodeWithTag(captureTypeTag(CaptureType.NOTE), useUnmergedTree = true)
        val prompt = composeRule.onNodeWithTag(captureTypeTag(CaptureType.PROMPT), useUnmergedTree = true)
        val link = composeRule.onNodeWithTag(captureTypeTag(CaptureType.LINK), useUnmergedTree = true)
        val file = composeRule.onNodeWithTag(CaptureFileImageTag, useUnmergedTree = true)
        val voice = composeRule.onNodeWithTag(CaptureVoiceTag, useUnmergedTree = true)

        note.assertIsDisplayed()
        prompt.assertIsDisplayed()
        link.assertIsDisplayed()
        file.assertIsDisplayed()
        voice.assertIsDisplayed()

        composeRule.onNodeWithText("Text Note", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Quick thought or idea", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Prompt", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Save a prompt", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Link", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Save a web link", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("File / Image", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Attach a file or photo", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Voice", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("Record a voice note", useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithTag(AgentComposerTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("What would you like to capture…", useUnmergedTree = true).assertIsDisplayed()

        val noteB = note.fetchSemanticsNode().boundsInRoot
        val promptB = prompt.fetchSemanticsNode().boundsInRoot
        val linkB = link.fetchSemanticsNode().boundsInRoot
        val fileB = file.fetchSemanticsNode().boundsInRoot
        val voiceB = voice.fetchSemanticsNode().boundsInRoot
        val composerTop = composeRule.onNodeWithTag(AgentComposerTestTag)
            .fetchSemanticsNode().boundsInRoot.top

        val tol = 2f
        assertEquals("Note/Prompt height", noteB.height, promptB.height, tol)
        assertEquals("Prompt/Link height", promptB.height, linkB.height, tol)
        assertEquals("Link/File height", linkB.height, fileB.height, tol)
        assertEquals("File/Voice height", fileB.height, voiceB.height, tol)

        assertTrue("Note above Prompt", noteB.bottom <= promptB.top + tol)
        assertTrue("Prompt above Link", promptB.bottom <= linkB.top + tol)
        assertTrue("Link above File", linkB.bottom <= fileB.top + tol)
        assertTrue("File above Voice", fileB.bottom <= voiceB.top + tol)
        assertTrue(
            "Voice above composer (voiceBottom=${voiceB.bottom}, composerTop=$composerTop)",
            voiceB.bottom <= composerTop + tol
        )

        val scrollableCards = composeRule.onAllNodes(
            hasTestTag(CaptureLauncherCardsTag) and hasScrollAction(),
            useUnmergedTree = true
        ).fetchSemanticsNodes()
        assertTrue("Capture cards must not be vertically scrollable", scrollableCards.isEmpty())

        composeRule.onAllNodes(hasTestTag(AgentCaptureTag) and hasScrollAction()).assertCountEquals(0)
    }

    @Test
    fun tall411_fiveEqualCardsAndComposerVisibleWithoutScroll() {
        setCaptureShell(widthDp = 411, heightDp = 720)
        assertFiveEqualCardsVisible()
    }

    @Test
    fun normal393_fiveEqualCardsAndComposerVisibleWithoutScroll() {
        setCaptureShell(widthDp = 393, heightDp = 640)
        assertFiveEqualCardsVisible()
    }

    @Test
    fun short360_s21Class_fiveEqualCardsAndComposerVisibleWithoutScroll() {
        // Galaxy S21-class logical size: short content box must densify all five cards together.
        setCaptureShell(widthDp = 360, heightDp = 600)
        assertFiveEqualCardsVisible()
    }

    @Test
    fun narrow320_fiveEqualCardsAndComposerVisibleWithoutScroll() {
        setCaptureShell(widthDp = 320, heightDp = 560)
        assertFiveEqualCardsVisible()
    }

    @Test
    fun short360_fontScale13_fiveEqualCardsAndComposerVisibleWithoutScroll() {
        setCaptureShell(widthDp = 360, heightDp = 640, fontScale = 1.3f)
        assertFiveEqualCardsVisible()
    }

    @Test
    fun contentBox_shortHeight_equalCardHeights_voiceSubtitleVisible() {
        // Direct content-box stress: below Comfortable budget, Compact/Tight must keep Voice whole.
        val vm = newVm()
        composeRule.setContent {
            VirlinTheme {
                Box(Modifier.width(360.dp).height(360.dp)) {
                    AgentCaptureArea(
                        vm = vm,
                        modifier = Modifier.fillMaxSize(),
                        onOpenTextNote = {},
                        onOpenPrompt = {},
                        onOpenLink = {},
                        onOpenFile = {},
                        onOpenVoice = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        val noteB = composeRule.onNodeWithTag(captureTypeTag(CaptureType.NOTE), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val voiceB = composeRule.onNodeWithTag(CaptureVoiceTag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(abs(noteB.height - voiceB.height) <= 2f)
        composeRule.onNodeWithText("Record a voice note", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodes(hasTestTag(CaptureLauncherCardsTag) and hasScrollAction()).assertCountEquals(0)
    }
}
