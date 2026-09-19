package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.agent.AgentComposerTestTag
import com.virlin.app.ui.agent.CaptureSaveInboxTestTag
import com.virlin.app.ui.agent.capture.AgentCaptureTag
import com.virlin.app.ui.agent.capture.CaptureArchiveTag
import com.virlin.app.ui.agent.capture.CaptureChooseContextTag
import com.virlin.app.ui.agent.capture.CaptureCloseTag
import com.virlin.app.ui.agent.capture.CaptureContextLineTag
import com.virlin.app.ui.agent.capture.CaptureCopyTag
import com.virlin.app.ui.agent.capture.CaptureDetailContentTag
import com.virlin.app.ui.agent.capture.CaptureDetailTag
import com.virlin.app.ui.agent.capture.CaptureFeedbackTag
import com.virlin.app.ui.agent.capture.CaptureFileImageTag
import com.virlin.app.ui.agent.capture.CaptureInboxTag
import com.virlin.app.ui.agent.capture.CaptureVoiceTag
import com.virlin.app.ui.agent.capture.captureRowTag
import com.virlin.app.ui.agent.capture.captureTypeTag
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.navigation.RootDestination
import com.virlin.app.ui.navigation.bottomNavItemTag
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.screens.AgentCloseTestTag
import com.virlin.app.ui.screens.AgentShellTestTag
import com.virlin.app.ui.screens.InboxScreenTag
import com.virlin.app.ui.screens.agentModeTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Capture launcher (minimal): five cards + pinned composer. Saved items are reviewed on the
 * Inbox tab — not inside the Capture workspace.
 */
@RunWith(AndroidJUnit4::class)
class AgentCaptureUiTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    private fun touch(t: String, after: Long = 700) {
        tag(t).performTouchInput { click() }; pump(after)
    }
    private fun repo() = VirlinGraph.repository
    private fun typeInComposer(text: String) { composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput(text); pump(300) }
    private fun save() { composeRule.onNodeWithTag(CaptureSaveInboxTestTag).assertIsDisplayed().performClick(); pump(1200) }
    private fun openCapture() {
        composeRule.onNodeWithTag(VirlinOrbTestTag).performTouchInput { click() }; pump(800)
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CAPTURE)).performClick(); pump(400)
        tag(AgentCaptureTag).assertIsDisplayed()
    }
    private fun closeAgent() {
        touch(AgentCloseTestTag, 500)
    }
    private fun openInboxTab() {
        composeRule.onNodeWithTag(bottomNavItemTag(RootDestination.INBOX)).performClick(); pump(600)
        tag(InboxScreenTag).assertIsDisplayed()
    }

    @Test fun capture_launcher_composer_saves_review_in_inbox_durable_focusUntouched() {
        pump(300)
        val focusBefore = runBlocking { repo().getStream("s1") }!!
        val tasksBefore = repo().tasks.value.size
        openCapture()

        // Minimal launcher: cards + composer; no context / tips / Inbox projection.
        tag(captureTypeTag(CaptureType.NOTE)).assertIsDisplayed()
        tag(captureTypeTag(CaptureType.PROMPT)).assertIsDisplayed()
        tag(captureTypeTag(CaptureType.LINK)).assertIsDisplayed()
        tag(CaptureFileImageTag).assertIsDisplayed()
        tag(CaptureVoiceTag).assertIsDisplayed()
        composeRule.onNodeWithTag(AgentComposerTestTag).assertIsDisplayed()
        tag(CaptureContextLineTag).assertDoesNotExist()
        tag(CaptureChooseContextTag).assertDoesNotExist()
        tag(CaptureInboxTag).assertDoesNotExist()

        typeInComposer("Investigate local music alarms"); save()
        tag(CaptureFeedbackTag).assertExists()
        val note = repo().captures.value.first { it.content == "Investigate local music alarms" }
        check(note.type == CaptureType.NOTE && !note.hasContext && note.status == CaptureStatus.INBOX) { "$note" }
        composeRule.onNodeWithTag(AgentComposerTestTag).assertTextContains("")

        // Type chips that open editors still exist; composer remains the quick-save path for PROMPT / LINK via setType when callbacks absent — with callbacks, open editors. Save via type selection still works for composer when we don't open editors: set type only when editor callbacks missing. With callbacks, tapping opens editor — so for PROMPT/LINK text save we rely on default NOTE unless we change type without opening. ViewModel setType is not exposed via UI when editors are wired.
        // Preserve: default composer saves as NOTE. For prompt/link, use domain via type — instrumented path uses composer for note; create prompt/link via actions for inbox review, OR temporarily use save with type from form.
        // Save a second note then verify Inbox tab lists captures (review/manage-only).
        typeInComposer("Second quick capture"); save()

        closeAgent()
        openInboxTab()
        tag(CaptureInboxTag).assertIsDisplayed()
        tag(captureRowTag(note.id)).assertIsDisplayed()

        // Detail + archive on Inbox (not Capture launcher).
        touch(captureRowTag(note.id), 600)
        tag(CaptureDetailTag).assertExists()
        tag(CaptureDetailContentTag).assertTextContains("Investigate local music alarms", substring = true)
        touch(CaptureCopyTag, 400)
        touch(CaptureCloseTag, 400)
        touch(captureRowTag(note.id), 600)
        touch(CaptureArchiveTag, 900)
        tag(captureRowTag(note.id)).assertDoesNotExist()
        check(runBlocking { repo().getCapture(note.id) }!!.status == CaptureStatus.ARCHIVED)

        val focusAfter = runBlocking { repo().getStream("s1") }!!
        check(focusAfter.state == WorkStreamState.FOCUS && focusAfter.activeTaskId == focusBefore.activeTaskId && focusAfter.updatedAt == focusBefore.updatedAt) { "focus changed: $focusAfter" }
        check(repo().tasks.value.size == tasksBefore) { "no task created by capture" }

        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, VirlinDatabase.NAME)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                check(db.captures().byId(note.id)?.status == "ARCHIVED")
                check(db.workStreams().byId("s1") != null && db.tasks().byId("p_q17") != null && db.projects().byId("p1") != null)
            }
        } finally { db.close() }

        runBlocking {
            repo().captures.value.filter { it.status == CaptureStatus.INBOX }.forEach {
                VirlinGraph.actions.archiveCapture(it.id)
            }
        }
    }
}
