package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.agent.control.AgentControlFocusTag
import com.virlin.app.ui.agent.control.AgentTaskPickerTag
import com.virlin.app.ui.agent.control.ControlAction
import com.virlin.app.ui.agent.control.controlActionTag
import com.virlin.app.ui.agent.control.controlItemTag
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.hierarchy.taskRowTag
import com.virlin.app.ui.screens.AgentShellTestTag
import com.virlin.app.ui.screens.NowChooserTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pass 8 on the real app: Orb → Agent CONTROL over persisted Room state, every control routed
 * through VirlinActions. One ordered scenario (the domain is process-wide). Due times are
 * reached via `checkDue`, never real waits.
 */
@RunWith(AndroidJUnit4::class)
class AgentControlUiTest {

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
    /** Scroll into the sheet viewport, settle, real touch. */
    private fun touch(t: String, after: Long = 800) {
        runCatching { tag(t).performScrollTo() }.onSuccess { pump(700) }          // dialog chips have no scroll parent
        tag(t).performTouchInput { click() }; pump(after)
    }
    private fun stream(id: String) = runBlocking { VirlinGraph.repository.getStream(id)!! }
    /** Scroll the chip into the sheet's viewport, let the scroll settle, then tap with a real touch. */
    private fun act(id: String, a: ControlAction) {
        // Stitch Control UI: a Recent / Suggested row reveals its structured controls on tap.
        if (runCatching { tag(controlActionTag(id, a)).assertExists() }.isFailure) { touch(controlItemTag(id), 500) }
        tag(controlActionTag(id, a)).performScrollTo(); pump(900)
        tag(controlActionTag(id, a)).performTouchInput { click() }; pump(1800)
    }
    private fun openAgent() {
        composeRule.onNodeWithTag(VirlinOrbTestTag).performTouchInput { click() }; pump(800); composeRule.onNodeWithTag(AgentShellTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.agentModeTag(com.virlin.app.ui.orb.AgentMode.CONTROL)).performClick(); pump(400)   // entry sheet, then CONTROL
    }
    private fun newTask(title: String): String = runBlocking {
        (VirlinGraph.actions.createTask(CreateTask(title = title, workStreamId = "s1")) as ActionResult.Success).value.id
    }
    private fun makeDue(id: String) {
        runBlocking { VirlinGraph.actions.deferReturn(id, VirlinGraph.clock.now().plusSeconds(1)) }; Thread.sleep(1100)
        runBlocking { VirlinGraph.actions.checkDue(id) }; pump(800)
    }

    /** Stitch Control UI: no Orb / tabs inside Control; Quick Actions map to the existing paths; ← returns to the entry selector. */
    @Test fun control_stitch_quickActions_rows_back() {
        pump(300); openAgent()
        tag(com.virlin.app.ui.agent.control.AgentControlQuickActionsTag).assertIsDisplayed()
        com.virlin.app.ui.agent.control.QuickAction.values().forEach { tag(com.virlin.app.ui.agent.control.quickActionTag(it)).assertIsDisplayed() }
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentOrbSlotTestTag).assertDoesNotExist()                 // no Orb slot in Control
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.agentModeTag(com.virlin.app.ui.orb.AgentMode.CREATE)).assertDoesNotExist()
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.agentModeTag(com.virlin.app.ui.orb.AgentMode.CAPTURE)).assertDoesNotExist()
        // rows are real state: seeded focus Psychology first, Antigravity (check due) present
        tag(controlItemTag("s1")).assertContentDescriptionContains("In focus", substring = true)
        tag(controlItemTag("s2")).assertExists()
        // Quick Leave with no expanded row acts on the current focus through the shared chooser
        touch(com.virlin.app.ui.agent.control.quickActionTag(com.virlin.app.ui.agent.control.QuickAction.LEAVE), 900)
        tag(NowChooserTag).assertIsDisplayed(); touch("leave_5m", 1200)
        check(stream("s1").state == WorkStreamState.SNOOZED) { "${stream("s1")}" }
        // Quick Focus with no row selected → existing typed clarification (which WorkStream?)
        touch(com.virlin.app.ui.agent.control.quickActionTag(com.virlin.app.ui.agent.control.QuickAction.FOCUS), 900)
        tag(com.virlin.app.ui.agent.command.CommandClarifyTag).assertExists()
        touch(com.virlin.app.ui.agent.command.commandCandidateTag("s1"), 1500)
        check(stream("s1").state == WorkStreamState.FOCUS) { "${stream("s1")}" }
        // ← back returns to "How can I help?"
        touch(com.virlin.app.ui.screens.AgentBackTestTag, 600)
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.AgentEntryTestTag).assertIsDisplayed()
    }

    @Test fun control_focus_leave_resume_check_stillRunning_focusNow_handOff_tasks() {
        pump(300)
        // 1–3. Orb → Control; real current focus (Psychology, active Question 17)
        openAgent()
        tag(AgentControlFocusTag).assertIsDisplayed()
        // The Now card behind the sheet also says "Question 17"; assert on the Agent's card semantics.
        tag(AgentControlFocusTag).assertContentDescriptionContains("Question 17", substring = true)

        // 4. Leave + 5m through the shared chooser → SNOOZED / HUMAN_RETURN, task kept
        act("s1", ControlAction.LEAVE)
        tag(NowChooserTag).assertIsDisplayed(); touch("leave_5m", 1200)
        stream("s1").let { check(it.state == WorkStreamState.SNOOZED && it.snoozeReason == SnoozeReason.HUMAN_RETURN && it.activeTaskId == "p_q17") { "$it" } }
        composeRule.onNodeWithText("In focus", substring = true, useUnmergedTree = true).assertDoesNotExist()   // nothing in focus now

        // 6. Resume the due human return via the Agent
        makeDue("s1")
        check(stream("s1").state == WorkStreamState.CHECK) { "not due: ${stream("s1")}" }
        act("s1", ControlAction.RESUME)
        check(stream("s1").state == WorkStreamState.FOCUS) { "resume: ${stream("s1")}" }

        // 7, 9, 5. Antigravity (seeded check due): CHECK → What happened? → RESULT READY → FOCUS NOW; then HAND OFF + 5m
        act("s2", ControlAction.CHECK)
        touch("chooser_result_ready", 800); touch("chooser_focus_now", 1200)
        check(stream("s2").state == WorkStreamState.FOCUS) { "focus now" }
        act("s2", ControlAction.HAND_OFF)
        touch("handoff_5m", 1200)
        stream("s2").let { check(it.state == WorkStreamState.PROCESSING && it.checkAt != null) { "hand off $it" } }

        // 8. Check due → Still running + 5m, no Focus
        runBlocking { VirlinGraph.actions.stillRunning("s2", VirlinGraph.clock.now().plusSeconds(1)) }; Thread.sleep(1100)
        runBlocking { VirlinGraph.actions.checkDue("s2") }; pump(800)
        act("s2", ControlAction.CHECK)
        touch("chooser_still_running", 800); touch("still_5m", 1200)
        check(stream("s2").state == WorkStreamState.PROCESSING) { "still running" }
        check(runBlocking { VirlinGraph.repository.getOpenFocusSession("s2") } == null) { "check must not focus" }

        // 10–12. Tasks: hierarchical picker, SET CURRENT, COMPLETE current (task only), CANCEL with confirmation
        runBlocking { VirlinGraph.actions.focusStream("s1") }; pump(800)
        act("s1", ControlAction.TASKS)
        tag(AgentTaskPickerTag).assertExists()
        tag(taskRowTag("p_q17")).assertExists()                                       // deep active task auto-expanded
        val newId = newTask("Agent picker task"); pump(800)
        touch(taskRowTag(newId), 400)
        touch("agent_task_set_current", 1200)
        check(stream("s1").activeTaskId == newId) { "set current" }
        touch("agent_tasks_back", 600)
        act("s1", ControlAction.COMPLETE)
        check(runBlocking { VirlinGraph.repository.getTask(newId) }!!.status == TaskStatus.DONE) { "task done" }
        check(stream("s1").state == WorkStreamState.FOCUS && stream("s1").activeTaskId == null) { "stream untouched" }
        act("s1", ControlAction.TASKS)
        val cancelId = newTask("Agent cancel task"); pump(800)
        touch(taskRowTag(cancelId), 400)
        touch("agent_task_cancel", 400); touch("agent_cancel_confirm", 1200)
        check(runBlocking { VirlinGraph.repository.getTask(cancelId) }!!.status == TaskStatus.CANCELLED) { "cancel" }
        // restore the seeded focus context for the other classes
        runBlocking { VirlinGraph.actions.setActiveTask("s1", "p_q17"); VirlinGraph.actions.markReady("s2") }
    }
}
