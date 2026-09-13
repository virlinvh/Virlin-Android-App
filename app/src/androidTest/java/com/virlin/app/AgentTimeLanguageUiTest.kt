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
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.agent.AgentComposerTestTag
import com.virlin.app.ui.agent.AgentSubmitTestTag
import com.virlin.app.ui.agent.CaptureSaveInboxTestTag
import com.virlin.app.ui.agent.command.CommandClarifyTag
import com.virlin.app.ui.agent.command.CommandFeedbackTag
import com.virlin.app.ui.agent.command.commandCandidateTag
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.screens.AgentShellTestTag
import com.virlin.app.ui.screens.agentModeTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pass 13 on the real app: calendar language through the composer over production wiring
 * (device zone, real clock, Room, scheduling decorator). Future clock times are chosen at run
 * time so the test takes seconds; nothing waits for an alarm to fire.
 */
@RunWith(AndroidJUnit4::class)
class AgentTimeLanguageUiTest {

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
    private val zone: ZoneId = ZoneId.systemDefault()
    private val clock12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    /** A clock time [minutes] ahead, on the minute, in the device zone — and the exact Instant it denotes. */
    private fun futureClock(minutes: Long): Pair<String, ZonedDateTime> {
        val z = ZonedDateTime.now(zone).plusMinutes(minutes).withSecond(0).withNano(0)
        return clock12.format(z).lowercase() to z
    }

    @Test fun time_language_leave_until_tomorrow_past_handoff_checkAgain_resultReady_capture_durable() {
        pump(300)
        openAgent(AgentMode.CONTROL)
        // Skip the on-the-day edge where "tomorrow morning" (09:00) would be < 24h away but a same-day clock time cannot be found.
        val (t5, at5) = futureClock(5)

        // 1. "leave this until <5 min from now>" → HUMAN_RETURN at that exact instant.
        say("leave this until $t5")
        tag(CommandFeedbackTag).assertTextContains("Left", substring = true)
        stream("s1").let { check(it.state == WorkStreamState.SNOOZED && it.snoozeReason == SnoozeReason.HUMAN_RETURN && it.checkAt == at5.toInstant()) { "$it (expected ${at5.toInstant()})" } }
        runBlocking { VirlinGraph.actions.checkDue("s1"); VirlinGraph.actions.focusStream("s1") }; pump(400)

        // 2. "leave this until tomorrow morning" → feedback shows the exact resolved local time; persisted 09:00 tomorrow.
        say("leave this until tomorrow morning")
        tag(CommandFeedbackTag).assertTextContains("Tomorrow · 9:00 AM", substring = true)
        val nine = ZonedDateTime.now(zone).toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant()
        check(stream("s1").checkAt == nine) { "${stream("s1").checkAt} vs $nine" }
        runBlocking { VirlinGraph.actions.checkDue("s1"); VirlinGraph.actions.focusStream("s1") }; pump(400)

        // 3. A passed clock time → clarification (tomorrow?) and no mutation.
        val (past, _) = ZonedDateTime.now(zone).minusMinutes(30).withSecond(0).withNano(0).let { clock12.format(it).lowercase() to it }
        say("leave this until $past")
        tag(CommandClarifyTag).assertExists()
        check(stream("s1").state == WorkStreamState.FOCUS) { "past time must not schedule" }
        touch("agent_command_dismiss", 300)

        // 7–8. "tomorrow" alone asks for a time; "at 9" asks AM/PM. Nothing mutates.
        say("leave this until tomorrow")
        composeRule.onNode(hasTestTag(CommandClarifyTag) and hasAnyDescendant(hasText("What time tomorrow?")), useUnmergedTree = true).assertExists()
        touch("agent_command_dismiss", 300)
        say("leave this until 9")
        composeRule.onNode(hasTestTag(CommandClarifyTag) and hasAnyDescendant(hasText("9 AM or PM?")), useUnmergedTree = true).assertExists()
        touch("agent_command_dismiss", 300)
        check(stream("s1").state == WorkStreamState.FOCUS)

        // 4. Hand off an EXTERNAL stream until a future time → PROCESSING with that checkAt.
        runBlocking { VirlinGraph.actions.checkDue("s4"); VirlinGraph.actions.focusStream("s4") }; pump(400)   // seeded external "Claude · Virlin" (PROCESSING → CHECK → FOCUS)
        val (t7, at7) = futureClock(7)
        say("hand this off until $t7")
        stream("s4").let { check(it.state == WorkStreamState.PROCESSING && it.checkAt == at7.toInstant()) { "$it" } }

        // 5. "check again tomorrow at 9 am" on that processing stream ("this" = Control selection) → still running path.
        runBlocking { VirlinGraph.actions.checkDue("s4") }; pump(400)
        touch(com.virlin.app.ui.agent.control.controlActionTag("s4", com.virlin.app.ui.agent.control.ControlAction.TASKS), 600)   // the open task picker makes s4 "this"
        say("check again tomorrow at 9 am")
        tag(CommandFeedbackTag).assertTextContains("still running · check at Tomorrow · 9:00 AM", substring = true)
        stream("s4").let { check(it.state == WorkStreamState.PROCESSING && it.checkAt == nine) { "$it" } }

        // 6. Result ready → remind tomorrow morning → EXTERNAL_RESULT_READY at 09:00 tomorrow.
        runBlocking { VirlinGraph.actions.checkDue("s4") }; pump(400)
        say("result ready remind me tomorrow morning")
        stream("s4").let { check(it.state == WorkStreamState.SNOOZED && it.snoozeReason == SnoozeReason.EXTERNAL_RESULT_READY && it.checkAt == nine) { "$it" } }

        // 9. CAPTURE mode: time-language text is stored, never scheduled.
        val alarmsBefore = repo().streams.value.count { it.checkAt != null }
        switchMode(AgentMode.CAPTURE); pump(600)
        if (runCatching { composeRule.onNodeWithTag(AgentComposerTestTag).assertExists() }.isFailure) {
            // The sheet can close when the keyboard collapses behind a mode switch; reopen it in CAPTURE.
            pump(600); openAgent(AgentMode.CAPTURE)
        }
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextClearance()
        composeRule.onNodeWithTag(AgentComposerTestTag).performTextInput("leave this tomorrow morning"); pump(300)
        composeRule.onNodeWithTag(CaptureSaveInboxTestTag).performClick(); pump(1200)
        val cap = repo().captures.value.first { it.content == "leave this tomorrow morning" }
        check(cap.type == CaptureType.NOTE && repo().streams.value.count { it.checkAt != null } == alarmsBefore)

        // 11. Existing duration grammar still works.
        runBlocking { VirlinGraph.actions.focusStream("s1") }
        switchMode(AgentMode.CONTROL); pump(600)
        val before = java.time.Instant.now()
        say("leave this for 5 minutes")
        stream("s1").let { check(it.state == WorkStreamState.SNOOZED && it.checkAt!!.isAfter(before.plus(Duration.ofMinutes(4))) && it.checkAt!!.isBefore(before.plus(Duration.ofMinutes(6)))) { "$it" } }

        // 10. Durability: fresh Room handle sees the absolute due times.
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, VirlinDatabase.NAME).addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try { runBlocking { check(db.workStreams().byId("s4")?.checkAt == nine); check(db.workStreams().byId("s4")?.snoozeReason == "EXTERNAL_RESULT_READY") } } finally { db.close() }

        // 12. Orb untouched.
        composeRule.onNodeWithTag(VirlinOrbTestTag).assertExists()

        // Restore seed-ish state for the other classes (cancel live alarms).
        runBlocking {
            VirlinGraph.actions.checkDue("s4"); VirlinGraph.actions.focusStream("s4"); VirlinGraph.actions.handOffStream("s4")
            VirlinGraph.actions.checkDue("s1"); VirlinGraph.actions.focusStream("s1"); VirlinGraph.actions.setActiveTask("s1", "p_q17")
            VirlinGraph.actions.archiveCapture(cap.id)
        }
    }
}
