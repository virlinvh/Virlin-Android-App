package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.command.Clarification
import com.virlin.app.domain.command.CommandEngine.Outcome
import com.virlin.app.domain.command.CommandResult
import com.virlin.app.domain.command.QueryResult
import com.virlin.app.domain.command.TargetRef
import com.virlin.app.domain.command.TaskOwnerRef
import com.virlin.app.domain.command.VirlinCommand.Capture
import com.virlin.app.domain.command.VirlinCommand.Control
import com.virlin.app.domain.command.VirlinCommand.Create
import com.virlin.app.domain.command.VirlinCommand.Query
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.components.VirlinOrbTestTag
import com.virlin.app.ui.screens.FocusContextTag
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * Pass 11 on the real app: the command contract over PRODUCTION wiring (`VirlinGraph.commands`
 * → Room → scheduling decorator → Now). Commands are submitted as typed values (no text, no
 * NLP); the screen and a fresh Room handle prove the effects. One ordered scenario.
 */
@RunWith(AndroidJUnit4::class)
class AgentCommandUiTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private val engine get() = VirlinGraph.commands
    private fun repo() = VirlinGraph.repository
    private fun stream(id: String) = runBlocking { repo().getStream(id)!! }
    private fun submit(cmd: com.virlin.app.domain.command.VirlinCommand) = runBlocking { engine.submit(cmd) }
    private fun executed(cmd: com.virlin.app.domain.command.VirlinCommand): CommandResult.Executed {
        val o = submit(cmd); return (o as? Outcome.Done)?.result as? CommandResult.Executed ?: error("not executed: $o")
    }

    @Test fun commands_focus_leave_create_capture_query_ambiguity_confirmation_durable() {
        pump(300)
        // 7. Query CurrentFocus reads production state (seeded Psychology / Question 17).
        val q = ((submit(Query.GetCurrentFocus) as Outcome.Done).result as CommandResult.Answered).result as QueryResult.CurrentFocus
        check(q.workStream.id == "s1" && q.activeTask?.id == "p_q17") { "$q" }

        // 1. Resolved Focus command updates Now.
        executed(Control.FocusStream(TargetRef.ByName("Notion Transfer")))
        pump(1200)
        composeRule.onNode(hasTestTag(FocusContextTag) and hasAnyDescendant(hasText("Notion Transfer", substring = true)), useUnmergedTree = true).assertExists()
        check(stream("s1").state == WorkStreamState.READY)

        // 2. Resolved Leave +5m persists and schedules (through the decorator, not the executor).
        val r = executed(Control.LeaveCurrent(Duration.ofMinutes(5)))
        check(r.summary.endsWith("back in 5m")) { r.summary }
        stream("s7").let { check(it.state == WorkStreamState.SNOOZED && it.snoozeReason == SnoozeReason.HUMAN_RETURN && it.checkAt != null) { "$it" } }
        pump(1200)
        composeRule.onNode(hasTestTag(FocusContextTag) and hasAnyDescendant(hasText("Notion Transfer", substring = true)), useUnmergedTree = true).assertDoesNotExist()

        // 3–5. Create Project / projectless WorkStream / Task through the executor.
        executed(Create.CreateProject("Command Thesis"))
        val project = repo().projects.value.first { it.title == "Command Thesis" }
        executed(Create.CreateWorkStream("Command Walk", project = null, mode = WorkStreamMode.HUMAN))
        val walk = repo().streams.value.first { it.title == "Command Walk" }
        check(walk.projectId == null && walk.state == WorkStreamState.READY && walk.mode == WorkStreamMode.HUMAN)
        executed(Create.CreateTask("Command task", TaskOwnerRef.WorkStream(TargetRef.ByName("Command Walk"))))
        val task = repo().tasks.value.first { it.title == "Command task" }
        check(task.workStreamId == walk.id && walk.activeTaskId == null)

        // 6. CaptureNote persists (global).
        executed(Capture.CaptureNote("Command capture note"))
        val cap = repo().captures.value.first { it.content == "Command capture note" }
        check(cap.status == CaptureStatus.INBOX && !cap.hasContext)

        // 8. Duplicate title → clarification with candidates, nothing executed.
        executed(Create.CreateWorkStream("Command Walk", project = TargetRef.ById(project.id), mode = WorkStreamMode.HUMAN))
        val amb = submit(Control.FocusStream(TargetRef.ByName("Command Walk"))) as Outcome.Clarify
        check(amb.clarification.kind == Clarification.Kind.AMBIGUOUS_TARGET && amb.clarification.candidates.size == 2) { "${amb.clarification}" }
        check(repo().streams.value.none { it.state == WorkStreamState.FOCUS }) { "ambiguity must not execute" }
        val chosen = runBlocking { engine.choose(amb.clarification, walk.id) } as Outcome.Done
        check((chosen.result as CommandResult.Executed).summary.startsWith("Focused Command Walk"))
        check(stream(walk.id).state == WorkStreamState.FOCUS)

        // 9. Destructive command requires confirmation; confirming executes.
        val conf = submit(Control.CompleteStream(TargetRef.ById(walk.id))) as Outcome.Confirm
        check(stream(walk.id).state == WorkStreamState.FOCUS) { "must not execute before confirmation" }
        // 11. A pending confirmation is just a value: dropping it changes nothing.
        check(stream(walk.id).state == WorkStreamState.FOCUS)
        runBlocking { engine.confirm(conf.confirmation) }
        check(stream(walk.id).state == WorkStreamState.DONE)

        // 10. Durability: a fresh Room handle sees every executed result.
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), VirlinDatabase::class.java, VirlinDatabase.NAME)
            .addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                check(db.projects().byId(project.id) != null)
                check(db.workStreams().byId(walk.id)?.state == "DONE")
                check(db.workStreams().byId("s7")?.snoozeReason == "HUMAN_RETURN")
                check(db.tasks().byId(task.id) != null && db.captures().byId(cap.id) != null)
            }
        } finally { db.close() }

        // 12. Orb untouched (still the same Orb node, no new affordance).
        composeRule.onNodeWithTag(VirlinOrbTestTag).assertIsDisplayed()

        // Restore the seeded focus context for the other classes.
        // Restore the seeded focus context for the other classes; cancel the live 5-minute return alarm on s7
        // (checkDue → markReady) so no alarm fires into a later class.
        runBlocking {
            VirlinGraph.actions.checkDue("s7"); VirlinGraph.actions.markReady("s7")
            VirlinGraph.actions.focusStream("s1"); VirlinGraph.actions.setActiveTask("s1", "p_q17"); VirlinGraph.actions.archiveCapture(cap.id)
        }
    }
}
