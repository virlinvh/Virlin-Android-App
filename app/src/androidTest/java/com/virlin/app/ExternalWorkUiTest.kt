package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.NewExternalStage
import com.virlin.app.domain.action.StartExternalWork
import com.virlin.app.domain.model.ExternalActor
import com.virlin.app.domain.model.ExternalStageStatus
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.navigation.RootDestination
import com.virlin.app.ui.navigation.bottomNavItemTag
import com.virlin.app.ui.screens.ExternalWorkDetailTag
import com.virlin.app.ui.screens.externalWorkRowTag
import com.virlin.app.ui.screens.externalWorkStageTag
import com.virlin.app.ui.screens.externalWorkTimerTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.Instant

/**
 * Phase 10 flows on the real app: external work appears in Working For You with its actor, work
 * item and countdown; the due run moves to Needs You as the SAME item; still running returns it;
 * and a staged run advances one stage at a time. Assertions read the domain, not UI copy.
 */
class ExternalWorkUiTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    val composeRule = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(DemoStateRule()).around(composeRule)

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    /** A READY (human) demo stream — delegating it is a legal READY → PROCESSING transition. */
    private val WORK_STREAM = "s7"

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    private fun touch(t: String, after: Long = 900) {
        runCatching { tag(t).performScrollTo() }.onSuccess { pump(900) }
        tag(t).performClick(); pump(after)
    }
    private fun actions() = VirlinGraph.actions
    private fun repo() = VirlinGraph.repository
    private fun stream(id: String = WORK_STREAM) = repo().streams.value.first { it.id == id }
    private fun leaf(title: String): Task = runBlocking {
        (actions().createTask(CreateTask(title = title, workStreamId = WORK_STREAM)) as ActionResult.Success).value
    }
    private fun delegate(item: Task?, minutes: Long?, stages: List<NewExternalStage> = emptyList()) = runBlocking {
        actions().startExternalWork(
            StartExternalWork(
                workStreamId = WORK_STREAM, actor = ExternalActor.CLAUDE_CODE,
                instruction = "Implement listening state", workItemId = item?.id,
                checkInMinutes = minutes, stages = stages
            )
        )
    }
    private fun goNow() { composeRule.onNodeWithTag(bottomNavItemTag(RootDestination.NOW)).performClick(); pump(900) }

    // ------------------------------------------------------------------ FLOW A / B / C / D

    @Test fun externalWorkAppears_countsDown_becomesNeedsYou_andStillRunningReturnsIt() {
        pump(900)
        val item = leaf("Phase 10 external item")
        delegate(item, minutes = 5)
        goNow()

        // FLOW A: it is in Working For You, with the actor and the exact work item behind it.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(externalWorkRowTag(WORK_STREAM), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        tag(externalWorkRowTag(WORK_STREAM)).performScrollTo(); pump(900)
        tag(externalWorkRowTag(WORK_STREAM)).assertIsDisplayed()
        assert(stream().state == WorkStreamState.PROCESSING)
        assert(stream().externalActorId == "claude_code")
        assert(stream().activeTaskId == item.id)

        // FLOW B: the countdown is rendered and derived from checkAt (never stored).
        tag(externalWorkTimerTag(WORK_STREAM)).performScrollTo(); pump(600)
        tag(externalWorkTimerTag(WORK_STREAM)).assertIsDisplayed()

        // FLOW C: when the check time arrives the SAME stream becomes attention — no duplicate.
        runBlocking { actions().checkDue(WORK_STREAM) }
        composeRule.waitUntil(5_000) { stream().state == WorkStreamState.CHECK }
        pump(900)
        assert(repo().streams.value.count { it.id == WORK_STREAM } == 1)
        assert(stream().activeTaskId == item.id) { "the work item travels with the item" }
        assert(composeRule.onAllNodesWithTag(externalWorkRowTag(WORK_STREAM), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        // FLOW D: still running → back to Working For You, same run, new check time.
        val before = stream().checkAt
        runBlocking { actions().markExternalStillRunning(WORK_STREAM, VirlinGraph.clock.now().plusSeconds(300)) }
        composeRule.waitUntil(5_000) { stream().state == WorkStreamState.PROCESSING }
        pump(900)
        assert(stream().checkAt != before)
        assert(stream().externalActorId == "claude_code" && stream().activeTaskId == item.id)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(externalWorkRowTag(WORK_STREAM), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ------------------------------------------------------------------ FLOW E / F / I

    @Test fun stagedRun_showsCurrentStage_thenResultReadyFocusNextStage() {
        pump(900)
        val item = leaf("Phase 10 staged item")
        delegate(item, minutes = null, stages = listOf(NewExternalStage("Analyze", 3), NewExternalStage("Implement fix", 8)))
        goNow()

        // FLOW I: the CURRENT stage is what the compact card names.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(externalWorkStageTag(WORK_STREAM), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        touch(externalWorkRowTag(WORK_STREAM), 1200)
        tag(ExternalWorkDetailTag).assertIsDisplayed()
        touch(com.virlin.app.ui.screens.ExternalWorkDetailCloseTag, 900)

        // FLOW E: due → result ready keeps it in Needs You (it is finished, not running).
        runBlocking {
            actions().checkDue(WORK_STREAM)
            actions().markExternalResultReady(WORK_STREAM)
        }
        composeRule.waitUntil(5_000) { stream().state == WorkStreamState.CHECK }
        pump(600)
        assert(runBlocking { repo().getTask(item.id) }!!.status.isTerminal.not()) { "external completion never completes the human item" }
        assert(runBlocking { actions().externalStages(WORK_STREAM) }.first().status == ExternalStageStatus.DONE)

        // FLOW I: START NEXT STAGE returns the same run to Working For You on stage 2.
        runBlocking { actions().startNextExternalStage(WORK_STREAM) }
        composeRule.waitUntil(5_000) { stream().state == WorkStreamState.PROCESSING }
        pump(900)
        val stages = runBlocking { actions().externalStages(WORK_STREAM) }
        assert(stages[1].status == ExternalStageStatus.IN_PROGRESS)
        assert(stream().checkAt!!.isAfter(Instant.now()))
        assert(repo().streams.value.count { it.id == WORK_STREAM } == 1)

        // FLOW F: FOCUS NOW takes the exact work item into Current Focus, creating no second task.
        val tasksBefore = repo().tasks.value.size
        runBlocking { actions().checkDue(WORK_STREAM); actions().markExternalResultReady(WORK_STREAM); actions().focusExternalResult(WORK_STREAM) }
        composeRule.waitUntil(5_000) { stream().state == WorkStreamState.FOCUS }
        assert(stream().activeTaskId == item.id)
        assert(repo().tasks.value.size == tasksBefore)
        assert(repo().streams.value.count { it.state == WorkStreamState.FOCUS } == 1)
    }
}
