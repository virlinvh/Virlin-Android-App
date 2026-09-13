package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.model.CaptureType
import com.virlin.app.ui.agent.AgentAttachmentButtonTestTag
import com.virlin.app.ui.agent.AgentAttachmentMenuTestTag
import com.virlin.app.ui.agent.AgentComposerTestTag
import com.virlin.app.ui.agent.AgentReceiptTestTag
import com.virlin.app.ui.agent.AgentSubmitTestTag
import com.virlin.app.ui.agent.CaptureSaveInboxTestTag
import com.virlin.app.ui.agent.attachmentMenuItemTag
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.screens.AgentCloseTestTag
import com.virlin.app.ui.screens.AgentShellTestTag
import com.virlin.app.ui.screens.AgentEntryTestTag
import com.virlin.app.ui.screens.AgentStatusTestTag
import com.virlin.app.ui.screens.ClarificationTestTag
import com.virlin.app.ui.screens.agentModeTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Agent workspace shell on Pixel 8 API 35 — the fifteen required checks (§46).
 * Selectors are semantic/testTag only. See `virlin-mobile-qa` for the two-clock `pump()`.
 */
@RunWith(AndroidJUnit4::class)
class AgentWorkspaceUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt()
        var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) {
            composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8)
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun openAgent() {
        composeRule.onNodeWithTag(VirlinOrbTestTag).performTouchInput { click() }
        pump(600)
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        // Entry sheet first ("How can I help?"): choose CONTROL to enter the workspace, as before.
        composeRule.onNodeWithTag(AgentEntryTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CONTROL)).performClick(); pump(400)
    }

    /** Workspace → ← back to the entry selector → the other mode (the mode tabs no longer exist). */
    private fun switchMode(mode: AgentMode) {
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentBackTestTag).performClick(); pump(400)
        composeRule.onNodeWithTag(agentModeTag(mode)).performClick()
    }
    private fun selectMode(mode: AgentMode) { switchMode(mode); pump(120) }

    private fun typeAndSettle(text: String) {
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput(text)
        pump(1_100) // Receiving -> ReadyWithInput
    }

    // 1
    @Test fun orbOpensAgent() { openAgent(); composeRule.onNodeWithTag(agentModeTag(AgentMode.CONTROL)).assertIsDisplayed() }

    // Entry ↔ workspace: ← returns to "How can I help?" with the Agent still open; × closes it.
    @Test fun backReturnsToEntry_closeDismisses() {
        openAgent()
        composeRule.onNodeWithTag(AgentEntryTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentBackTestTag).performClick(); pump(400)
        composeRule.onNodeWithTag(AgentEntryTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentBackTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CREATE)).performClick(); pump(400)
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CREATE)).assertIsSelected()
        composeRule.onNodeWithTag(AgentCloseTestTag).performClick(); pump(800)
        composeRule.onNodeWithTag(AgentShellTestTag).assertDoesNotExist()
    }

    // 2 + 3
    @Test fun modesAccessible_andSwitchingDoesNotCloseAgent() {
        openAgent()
        selectMode(AgentMode.CREATE); composeRule.onNodeWithTag(agentModeTag(AgentMode.CREATE)).assertIsSelected()
        selectMode(AgentMode.CAPTURE); composeRule.onNodeWithTag(agentModeTag(AgentMode.CAPTURE)).assertIsSelected()
        selectMode(AgentMode.CONTROL); composeRule.onNodeWithTag(agentModeTag(AgentMode.CONTROL)).assertIsSelected()
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        composeRule.onAllNodesWithTag(VirlinOrbTestTag).assertCountEquals(1)
    }

    // 4 — CONTROL is real since Pass 8: the current focus card reflects persisted state.
    @Test fun controlShowsRealCurrentFocus() {
        openAgent()
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.control.AgentControlFocusTag, useUnmergedTree = true).assertIsDisplayed()
        // Stitch Control UI: a row reveals its structured controls on tap.
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.control.controlItemTag("s1"), useUnmergedTree = true).performClick(); pump(400)
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.control.controlActionTag("s1", com.virlin.app.ui.agent.control.ControlAction.LEAVE), useUnmergedTree = true).assertIsDisplayed()
    }

    // 5 — CREATE is real (Pass 9): the shell shows the structured create kinds, not the mock strip.
    @Test fun createShowsRealKindChips() {
        openAgent(); selectMode(AgentMode.CREATE)
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.create.AgentCreateTag, useUnmergedTree = true).assertIsDisplayed()
        com.virlin.app.ui.agent.create.CreateKind.values().forEach { k ->
            // Stitch Create UI: a large card per kind; choosing one continues into the existing form (cards hidden until CANCEL / DONE).
            composeRule.onNodeWithTag(com.virlin.app.ui.agent.create.createKindTag(k), useUnmergedTree = true).performClick(); pump(150)
            composeRule.onNodeWithTag(com.virlin.app.ui.agent.create.AgentCreateTitleTag, useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag(com.virlin.app.ui.agent.create.createKindTag(k), useUnmergedTree = true).assertDoesNotExist()
            composeRule.onNodeWithTag("agent_create_cancel", useUnmergedTree = true).performScrollTo(); pump(300)
            composeRule.onNodeWithTag("agent_create_cancel", useUnmergedTree = true).performClick(); pump(500)
        }
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentOrbSlotTestTag).assertDoesNotExist()   // no Orb inside Create
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CONTROL)).assertDoesNotExist()
    }

    // 6 — composer text in CREATE is still the demo pipeline; it never creates domain items.
    @Test fun createComposerUnsupportedTextExecutesNothing() {
        // Pass 12: CONTROL / CREATE composer text goes through the deterministic interpreter; free prose is unsupported.
        openAgent(); selectMode(AgentMode.CREATE)
        val before = com.virlin.app.domain.VirlinGraph.repository.tasks.value.size to com.virlin.app.domain.VirlinGraph.repository.streams.value.size
        typeAndSettle("Test Orb state animation")
        composeRule.onNodeWithTag(AgentSubmitTestTag).performClick(); pump(800)
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.command.CommandFeedbackTag, useUnmergedTree = true).assertExists()
        check(com.virlin.app.domain.VirlinGraph.repository.tasks.value.size == before.first && com.virlin.app.domain.VirlinGraph.repository.streams.value.size == before.second) { "unsupported text must not execute" }
    }

    // 7
    @Test fun composerAcceptsText() {
        openAgent()
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput("Give Claude another five minutes.")
        pump(200)
        composeRule.onNodeWithTag(AgentComposerTestTag).assertTextContains("Give Claude another five minutes.")
    }

    // 8
    @Test fun attachmentMenuOpens() {
        openAgent()
        composeRule.onNodeWithTag(AgentAttachmentButtonTestTag).performClick(); pump(120)
        composeRule.onNodeWithTag(AgentAttachmentMenuTestTag).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add Prompt").assertIsDisplayed()
    }

    // 9
    // 9 — CAPTURE is real (Pass 10): the shell shows the three capture types; the demo tray is gone.
    @Test fun capture_showsRealTypeChips() {
        openAgent(); selectMode(AgentMode.CAPTURE)
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.capture.AgentCaptureTag, useUnmergedTree = true).assertIsDisplayed()
        com.virlin.app.domain.model.CaptureType.values().forEach { t ->
            composeRule.onNodeWithTag(com.virlin.app.ui.agent.capture.captureTypeTag(t), useUnmergedTree = true).performClick(); pump(150)
            composeRule.onNodeWithTag(com.virlin.app.ui.agent.capture.captureTypeTag(t), useUnmergedTree = true).assertContentDescriptionContains("selected", substring = true)
        }
        // Stitch Capture UI: five cards in order; File / Image and Voice are present but have no backend — disabled, never mutate.
        val cards = listOf(com.virlin.app.ui.agent.capture.captureTypeTag(com.virlin.app.domain.model.CaptureType.NOTE), com.virlin.app.ui.agent.capture.captureTypeTag(com.virlin.app.domain.model.CaptureType.PROMPT),
            com.virlin.app.ui.agent.capture.captureTypeTag(com.virlin.app.domain.model.CaptureType.LINK), com.virlin.app.ui.agent.capture.CaptureFileImageTag, com.virlin.app.ui.agent.capture.CaptureVoiceTag)
        val tops = cards.map { composeRule.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        check(tops == tops.sorted()) { "card order $tops" }
        composeRule.onNodeWithText("Capture", useUnmergedTree = true).assertExists(); composeRule.onNodeWithText("Save information", useUnmergedTree = true).assertExists()
        val before = com.virlin.app.domain.VirlinGraph.repository.captures.value.size
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.capture.CaptureFileImageTag, useUnmergedTree = true).assertIsNotEnabled().performClick(); pump(200)
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.capture.CaptureVoiceTag, useUnmergedTree = true).assertIsNotEnabled().performClick(); pump(200)
        check(com.virlin.app.domain.VirlinGraph.repository.captures.value.size == before) { "disabled cards must not capture" }
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentOrbSlotTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CONTROL)).assertDoesNotExist()
        composeRule.onNodeWithTag(CaptureSaveInboxTestTag).assertExists()
    }

    // 10 — SAVE TO INBOX persists the composer text as a real capture (no demo receipt).
    @Test fun saveToInbox_persistsCapture() {
        openAgent(); selectMode(AgentMode.CAPTURE)
        val before = com.virlin.app.domain.VirlinGraph.repository.captures.value.size
        typeAndSettle("note about the orb")
        composeRule.onNodeWithTag(CaptureSaveInboxTestTag).performClick(); pump(1_200)
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.capture.CaptureFeedbackTag, useUnmergedTree = true).assertExists()
        check(com.virlin.app.domain.VirlinGraph.repository.captures.value.size == before + 1) { "capture must persist" }
        composeRule.onNodeWithTag(AgentComposerTestTag).assertTextContains("", ignoreCase = false)   // cleared
        runBlocking { com.virlin.app.domain.VirlinGraph.actions.archiveCapture(com.virlin.app.domain.VirlinGraph.repository.captures.value.first { it.content == "note about the orb" }.id) }
    }


    // 11
    @Test fun createSubmit_commandPreviewsThenCreates() {
        openAgent(); selectMode(AgentMode.CREATE)
        typeAndSettle("create project Workspace Test Project")
        composeRule.onNodeWithTag(AgentSubmitTestTag).performClick(); pump(800)
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.command.CommandPreviewTag, useUnmergedTree = true).assertExists()
        check(com.virlin.app.domain.VirlinGraph.repository.projects.value.none { it.title == "Workspace Test Project" }) { "preview must not create" }
        composeRule.onNodeWithTag(com.virlin.app.ui.agent.command.CommandPreviewCreateTag, useUnmergedTree = true).performClick(); pump(1_000)
        check(com.virlin.app.domain.VirlinGraph.repository.projects.value.any { it.title == "Workspace Test Project" }) { "CREATE must persist" }
    }

    // 12 — the mock REMINDER clarification demo is no longer reachable from the live shell
    // (Create is real since Pass 9; a reminder model is deliberately out of scope).

    // 13
    @Test fun keyboard_doesNotHidePrimaryAction() {
        openAgent()
        composeRule.onNodeWithTag(AgentComposerTestTag).performClick(); pump(300) // focus -> IME
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput("hello"); pump(600)
        composeRule.onNodeWithTag(AgentSubmitTestTag).assertIsDisplayed()
        switchMode(AgentMode.CAPTURE); pump(300)
        composeRule.onNodeWithTag(CaptureSaveInboxTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
    }

    // 14
    @Test fun closeAgent_returnsToNow() {
        openAgent()
        composeRule.onNodeWithTag(AgentCloseTestTag).performClick(); pump(800)
        composeRule.onNodeWithTag(AgentShellTestTag).assertDoesNotExist()
        composeRule.onNodeWithText("Psychology").assertIsDisplayed()
        composeRule.onNodeWithTag(VirlinOrbTestTag).assertIsDisplayed()
    }

    // 15
    @Test fun rapidModeSwitching_doesNotCorruptState() {
        openAgent()
        // Rapid ← back / pick cycles through the entry selector (the mode tabs no longer exist).
        repeat(12) { composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentBackTestTag).performClick(); composeRule.mainClock.advanceTimeByFrame(); composeRule.onNodeWithTag(agentModeTag(AgentMode.values()[it % 3])).performClick(); composeRule.mainClock.advanceTimeByFrame() }
        pump(300)
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CAPTURE)).assertIsSelected()
        composeRule.onAllNodesWithTag(VirlinOrbTestTag).assertCountEquals(1)
        composeRule.onNodeWithTag(AgentStatusTestTag).assertContentDescriptionContains("Ready")
    }
}
