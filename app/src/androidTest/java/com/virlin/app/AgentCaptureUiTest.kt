package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import com.virlin.app.ui.agent.capture.CaptureInboxTag
import com.virlin.app.ui.agent.capture.captureContextStreamTag
import com.virlin.app.ui.agent.capture.captureRowTag
import com.virlin.app.ui.agent.capture.captureTypeTag
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.screens.AgentShellTestTag
import com.virlin.app.ui.screens.agentModeTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pass 10 on the real app: Orb → Agent CAPTURE over Room. One ordered scenario (the domain is
 * process-wide): note / multiline prompt / link saved through the pinned composer, Inbox
 * newest-first, projectless WorkStream context, detail + copy, archive, durability proven by a
 * fresh Room handle, Focus untouched, SAVE reachable with the keyboard, content above the composer.
 * Process death is exercised in the manual Pixel 8 proof.
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
        runCatching { tag(t).performScrollTo() }.onSuccess { pump(600) }
        tag(t).performTouchInput { click() }; pump(after)
    }
    private fun repo() = VirlinGraph.repository
    private fun typeInComposer(text: String) { composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput(text); pump(300) }
    /** SAVE TO INBOX sits in the pinned composer: reachable with the keyboard open (no closeSoftKeyboard). */
    private fun save() { composeRule.onNodeWithTag(CaptureSaveInboxTestTag).assertIsDisplayed().performClick(); pump(1200) }
    private fun openCapture() {
        composeRule.onNodeWithTag(VirlinOrbTestTag).performTouchInput { click() }; pump(800)
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(AgentMode.CAPTURE)).performClick(); pump(400)
        tag(AgentCaptureTag).assertIsDisplayed()
    }

    @Test fun capture_note_prompt_link_context_detail_copy_archive_durable_focusUntouched() {
        pump(300)
        val focusBefore = runBlocking { repo().getStream("s1") }!!
        val tasksBefore = repo().tasks.value.size
        openCapture()

        // 2–3. NOTE with no context → Inbox immediately, composer cleared.
        typeInComposer("Investigate local music alarms"); save()
        tag(CaptureFeedbackTag).assertExists()
        val note = repo().captures.value.first { it.content == "Investigate local music alarms" }
        check(note.type == CaptureType.NOTE && !note.hasContext && note.status == CaptureStatus.INBOX) { "$note" }
        tag(captureRowTag(note.id)).assertExists()
        composeRule.onNodeWithTag(AgentComposerTestTag).assertTextContains("")

        // 4. PROMPT preserving multiline text.
        touch(captureTypeTag(CaptureType.PROMPT), 300)
        typeInComposer("Refactor the receiver.\nKeep the public API.\n\n- tests green"); save()
        val prompt = repo().captures.value.first { it.type == CaptureType.PROMPT }
        check(prompt.content == "Refactor the receiver.\nKeep the public API.\n\n- tests green") { "verbatim: ${prompt.content}" }

        // 5. LINK.
        touch(captureTypeTag(CaptureType.LINK), 300)
        typeInComposer("https://example.com"); save()
        val link = repo().captures.value.first { it.type == CaptureType.LINK }
        check(link.sourceUrl == "https://example.com")

        // Newest first: link, prompt, note.
        val ids = repo().captures.value.filter { it.status == CaptureStatus.INBOX }.map { it.id }
        check(ids.indexOf(link.id) < ids.indexOf(prompt.id) && ids.indexOf(prompt.id) < ids.indexOf(note.id)) { "order $ids" }

        // 6. Attach a NOTE to the projectless WorkStream (explicit, visible in the context line).
        touch(captureTypeTag(CaptureType.NOTE), 300)
        touch(CaptureChooseContextTag, 500)
        touch(captureContextStreamTag("s8"), 500)                                        // seeded projectless WorkStream
        tag(CaptureContextLineTag).assertTextContains("Attached to", substring = true)
        typeInComposer("Revise Question 17 explanation"); save()
        val ctxNote = repo().captures.value.first { it.content == "Revise Question 17 explanation" }
        check(ctxNote.workStreamId == "s8" && ctxNote.projectId == null) { "$ctxNote" }
        tag(CaptureContextLineTag).assertTextContains("Global", substring = true)             // next capture is global again

        // 8–9. Open the prompt: full content shown, COPY works (clipboard), then back.
        touch(captureRowTag(prompt.id), 600)
        tag(CaptureDetailTag).assertExists()
        tag(CaptureDetailContentTag).assertTextContains("- tests green", substring = true)
        touch(CaptureCopyTag, 400)
        val clip = composeRule.activity.getSystemService(android.content.ClipboardManager::class.java)
        composeRule.runOnUiThread { check(clip.primaryClip?.getItemAt(0)?.text?.toString() == prompt.content) { "clipboard" } }
        touch(CaptureCloseTag, 400)

        // 7. Archive the link → leaves the Inbox, still persisted.
        touch(captureRowTag(link.id), 600)
        touch(CaptureArchiveTag, 900)
        tag(captureRowTag(link.id)).assertDoesNotExist()
        check(runBlocking { repo().getCapture(link.id) }!!.status == CaptureStatus.ARCHIVED)

        // 12. Capture changed nothing else.
        val focusAfter = runBlocking { repo().getStream("s1") }!!
        check(focusAfter.state == WorkStreamState.FOCUS && focusAfter.activeTaskId == focusBefore.activeTaskId && focusAfter.updatedAt == focusBefore.updatedAt) { "focus changed: $focusAfter" }
        check(repo().tasks.value.size == tasksBefore) { "no task created by capture" }

        // 13. Inbox content scrolls fully above the pinned composer.
        tag(CaptureInboxTag).performScrollTo(); pump(500)
        tag(captureRowTag(note.id)).performScrollTo(); pump(500)
        val rowBottom = tag(captureRowTag(note.id)).fetchSemanticsNode().boundsInRoot.bottom
        val composerTop = composeRule.onNodeWithTag(AgentComposerTestTag).fetchSemanticsNode().boundsInRoot.top
        check(rowBottom <= composerTop) { "row ($rowBottom) must sit above the composer ($composerTop)" }

        // 10–11. Durability + existing data: a fresh Room handle sees the captures and the seeded hierarchy.
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, VirlinDatabase.NAME)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                check(db.captures().byId(prompt.id)?.content == prompt.content)
                check(db.captures().byId(link.id)?.status == "ARCHIVED")
                check(db.captures().byId(ctxNote.id)?.workStreamId == "s8")
                check(db.workStreams().byId("s1") != null && db.tasks().byId("p_q17") != null && db.projects().byId("p1") != null)
            }
        } finally { db.close() }

        // Leave the Inbox tidy for the other classes (archive is non-destructive).
        runBlocking { listOf(note.id, prompt.id, ctxNote.id).forEach { VirlinGraph.actions.archiveCapture(it) } }
    }
}
