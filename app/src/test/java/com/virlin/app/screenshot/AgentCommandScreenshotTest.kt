package com.virlin.app.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.command.CommandEngine
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.agent.command.AgentCommandPanel
import com.virlin.app.ui.agent.command.AgentCommandViewModel
import com.virlin.app.ui.theme.VirlinColors
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Pass 12 candidates: the command panel's three interaction states (clarification with
 * candidates, destructive confirmation, create preview) rendered standalone on fixed data.
 * No approved golden is touched. GOLDEN RULE: never re-record after a failure without approval.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentCommandScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)

    private fun vm(): AgentCommandViewModel {
        val streams = listOf(
            ws("s1", "Psychology Unit 23", WorkStreamState.FOCUS, null, active = "p_q17"),
            ws("s4", "Agent Development", WorkStreamState.READY, "p1", WorkStreamMode.EXTERNAL),
            ws("s9", "Testing", WorkStreamState.READY, "p1"), ws("s10", "Testing", WorkStreamState.READY, "p2")
        )
        val repo = InMemoryWorkStreamRepository(streams,
            listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0), Project("p2", "MBA Project", createdAt = t0, updatedAt = t0)),
            DemoHierarchySeed.tasks(streams, t0))
        val clock = FakeClock(t0)
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val time = com.virlin.app.domain.command.time.TimeExpressionParser(clock, zone)
        return AgentCommandViewModel(CommandEngine(DefaultVirlinActions(repo, clock, SequentialIdProvider()), repo, clock, zone), com.virlin.app.domain.command.text.TextCommandInterpreter(time)::interpret)
    }

    private fun capture(vm: AgentCommandViewModel, name: String) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { VirlinTheme { Box(Modifier.width(411.dp).background(VirlinColors.Background).padding(20.dp)) { AgentCommandPanel(vm) } } }
        repeat(20) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun agentCommandClarification() { val vm = vm(); vm.submit("focus testing"); capture(vm, "agent_command_clarification") }
    @Test fun agentCommandConfirmation() { val vm = vm(); vm.submit("complete current workstream"); capture(vm, "agent_command_confirmation") }
    /** Pass 13 candidate: a temporal clarification ("What time tomorrow?") with daypart candidates showing exact resolved times. */
    @Test fun agentCommandTimeClarification() { val vm = vm(); vm.submit("leave until tomorrow"); capture(vm, "agent_command_time_clarification") }
    @Test fun agentCommandPreview() { val vm = vm(); vm.submit("create external workstream \"Claude Build\" in project \"Virlin Android App\""); capture(vm, "agent_command_preview") }
}
