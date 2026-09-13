package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.CreateWorkStream
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.agent.AgentComposerTestTag
import com.virlin.app.ui.agent.AgentSubmitTestTag
import com.virlin.app.ui.agent.CaptureSaveInboxTestTag
import com.virlin.app.ui.agent.command.CommandAnswerTag
import com.virlin.app.ui.agent.command.CommandClarifyTag
import com.virlin.app.ui.agent.command.CommandConfirmNoTag
import com.virlin.app.ui.agent.command.CommandConfirmTag
import com.virlin.app.ui.agent.command.CommandConfirmYesTag
import com.virlin.app.ui.agent.command.CommandFeedbackTag
import com.virlin.app.ui.agent.command.CommandPreviewCreateTag
import com.virlin.app.ui.agent.command.CommandPreviewTag
import com.virlin.app.ui.agent.command.commandCandidateTag
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.screens.AgentShellTestTag
import com.virlin.app.ui.screens.FocusContextTag
import com.virlin.app.ui.screens.agentModeTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pass 12 on the real app: composer text → interpreter → command engine → clarification /
 * confirmation / preview / answer panel → Room. One ordered scenario over production wiring.
 * CAPTURE mode is proven to stay data-only. Process death is exercised in the manual proof.
 */
@RunWith(AndroidJUnit4::class)
class AgentTextCommandUiTest {

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
    private fun touch(t: String, after: Long = 700) { runCatching { tag(t).performScrollTo() }.onSuccess { pump(500) }; tag(t).performTouchInput { click() }; pump(after) }
    private fun repo() = VirlinGraph.repository
    private fun stream(id: String) = runBlocking { repo().getStream(id)!! }
    private fun say(text: String) {
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextClearance()
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput(text); pump(300)
        composeRule.onNodeWithTag(AgentSubmitTestTag).performClick(); pump(1200)
    }
    /** Workspace → ← back to the entry selector → the other mode (the mode tabs no longer exist). */
    private fun switchMode(mode: AgentMode) {
        // The sheet can close when the keyboard collapses; if so, reopen straight into [mode].
        if (runCatching { composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentBackTestTag).assertExists() }.isFailure) { pump(600); openAgent(mode); return }
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentBackTestTag).performClick(); pump(400)
        if (runCatching { composeRule.onNodeWithTag(agentModeTag(mode)).assertExists() }.isFailure) { pump(600); openAgent(mode); return }
        composeRule.onNodeWithTag(agentModeTag(mode)).performClick()
    }
    private fun openAgent(mode: AgentMode) {
        composeRule.onNodeWithTag(VirlinOrbTestTag).performTouchInput { click() }; pump(800)
        composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(agentModeTag(mode)).performClick(); pump(400)
    }

    @Test fun text_commands_focus_clarify_leave_create_confirm_query_capture_unsupported_durable() {
        pump(300)
        openAgent(AgentMode.CONTROL)

        // 1. "focus <name>" focuses the right stream (seeded READY "Notion Transfer").
        say("focus notion transfer")
        tag(CommandFeedbackTag).assertTextContains("Focused Notion Transfer", substring = true)
        check(stream("s7").state == WorkStreamState.FOCUS && stream("s1").state == WorkStreamState.READY)

        // 2. Duplicate names → clarification UI → pick a candidate.
        runBlocking { VirlinGraph.actions.createWorkStream(CreateWorkStream(title = "Text Dup")); VirlinGraph.actions.createWorkStream(CreateWorkStream(title = "Text Dup", projectId = "p1")) }
        val dups = repo().streams.value.filter { it.title == "Text Dup" }
        say("focus text dup")
        tag(CommandClarifyTag).assertExists()
        // Control 1: the natural "focus X" target resolves across kinds, so candidate values are typed ("workstream:<id>").
        check(dups.none { stream(it.id).state == WorkStreamState.FOCUS }) { "clarification must not focus" }
        touch(commandCandidateTag("workstream:" + dups.first { it.projectId == null }.id), 1200)
        check(stream(dups.first { it.projectId == null }.id).state == WorkStreamState.FOCUS)

        // 3. "leave this for 5 minutes" → HUMAN_RETURN persisted with a check time (alarm through the decorator).
        say("leave this for 5 minutes")
        tag(CommandFeedbackTag).assertTextContains("back in 5m", substring = true)
        stream(dups.first { it.projectId == null }.id).let { check(it.state == WorkStreamState.SNOOZED && it.snoozeReason == SnoozeReason.HUMAN_RETURN && it.checkAt != null) { "$it" } }

        // 4. "create project …" → preview → CREATE → persisted.
        switchMode(AgentMode.CREATE); pump(400)
        say("create project MBA Research")
        tag(CommandPreviewTag).assertExists()
        check(repo().projects.value.none { it.title == "MBA Research" })
        touch(CommandPreviewCreateTag, 1000)
        check(repo().projects.value.any { it.title == "MBA Research" })

        // 5. "create workstream Claude Build" → mode clarification → External → preview → CREATE → projectless READY.
        say("create workstream Claude Build")
        tag(CommandClarifyTag).assertExists()
        touch(commandCandidateTag("EXTERNAL"), 800)
        tag(CommandPreviewTag).assertExists()
        touch(CommandPreviewCreateTag, 1000)
        repo().streams.value.first { it.title == "Claude Build" }.let { check(it.mode == WorkStreamMode.EXTERNAL && it.projectId == null && it.state == WorkStreamState.READY) { "$it" } }

        // 6–7. "complete current workstream" → confirmation; cancel = no mutation; confirm = completes.
        runBlocking { VirlinGraph.actions.focusStream("s7") }; pump(500)
        switchMode(AgentMode.CONTROL); pump(400)
        say("complete current workstream")
        tag(CommandConfirmTag).assertExists()
        touch(CommandConfirmNoTag, 600)
        check(stream("s7").state == WorkStreamState.FOCUS) { "cancel must not mutate" }
        say("complete current workstream")
        touch(CommandConfirmYesTag, 1200)
        check(stream("s7").state == WorkStreamState.DONE)

        // 8. Query → answer panel.
        runBlocking { VirlinGraph.actions.focusStream("s1"); VirlinGraph.actions.setActiveTask("s1", "p_q17") }; pump(500)
        say("what am I working on?")
        composeRule.onNode(hasTestTag(CommandAnswerTag) and hasAnyDescendant(hasText("Question 17", substring = true)), useUnmergedTree = true).assertExists()

        // 9. CAPTURE mode: raw "focus psychology" is a capture, never a command.
        switchMode(AgentMode.CAPTURE); pump(400)
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextClearance()
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput("focus psychology"); pump(300)
        composeRule.onNodeWithTag(CaptureSaveInboxTestTag).performClick(); pump(1200)
        val cap = repo().captures.value.first { it.content == "focus psychology" }
        check(cap.type == CaptureType.NOTE && stream("s1").state == WorkStreamState.FOCUS)

        // 10. Capture Prompt via command syntax keeps command-looking content as data.
        switchMode(AgentMode.CONTROL); pump(400)
        say("capture prompt \"leave this for 10 minutes\"")
        val prompt = repo().captures.value.first { it.type == CaptureType.PROMPT }
        check(prompt.content == "leave this for 10 minutes" && stream("s1").state == WorkStreamState.FOCUS) { "$prompt ${stream("s1")}" }

        // 11. Unsupported sentence → safe feedback, no mutation, text kept.
        val streamsBefore = repo().streams.value
        say("plan my entire week")
        // Deterministic only: unsupported prose gets the deterministic no-match feedback; nothing else is consulted.
        tag(CommandFeedbackTag).assertTextContains("couldn't map", substring = true)
        check(repo().streams.value == streamsBefore)
        composeRule.onNodeWithTag(AgentComposerTestTag).assertTextContains("plan my entire week")

        // 12–13. Durability + pending state is ephemeral: fresh Room handle sees executed results; the panel is not persisted.
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, VirlinDatabase.NAME).addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try { runBlocking {
            check(db.workStreams().byId("s7")?.state == "DONE")
            check(db.projects().all().any { it.title == "MBA Research" })
            check(db.captures().byId(prompt.id)?.content == "leave this for 10 minutes")
        } } finally { db.close() }

        // 14. Orb still there, untouched.
        composeRule.onNodeWithTag(VirlinOrbTestTag).assertExists()

        // Restore: s7 back to READY is impossible (DONE is terminal) — other classes do not depend on s7; cancel the live alarm on the dup.
        runBlocking {
            VirlinGraph.actions.checkDue(dups.first { it.projectId == null }.id); VirlinGraph.actions.markReady(dups.first { it.projectId == null }.id)
            VirlinGraph.actions.focusStream("s1"); VirlinGraph.actions.setActiveTask("s1", "p_q17")
            listOf(cap.id, prompt.id).forEach { VirlinGraph.actions.archiveCapture(it) }
        }
    }
}
