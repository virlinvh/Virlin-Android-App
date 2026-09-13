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
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.SnoozeReason
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.agent.create.AgentCreateArea
import com.virlin.app.ui.agent.create.AgentCreateViewModel
import com.virlin.app.ui.agent.create.CreateKind
import com.virlin.app.ui.theme.VirlinColors
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Pass 9 candidate: the live CREATE content (Task form with owner + parent pickers) rendered
 * standalone against fixed domain data. The approved `agent_create.png` shell golden is
 * untouched — the shell still renders its original create strip when no create content is
 * injected. GOLDEN RULE: never re-record after a failure without explicit approval.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentCreateScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Test fun agentCreateLive() {
        val streams = listOf(
            ws("s1", "Psychology Unit 23", WorkStreamState.FOCUS, null, active = "p_q17"),
            ws("s4", "Agent Development", WorkStreamState.PROCESSING, "p1", WorkStreamMode.EXTERNAL, "t_nl")
                .copy(processingStartedAt = t0.minusSeconds(494), checkAt = t0.plusSeconds(120)),
            ws("s2", "Antigravity", WorkStreamState.CHECK, "p4", WorkStreamMode.EXTERNAL),
            ws("s7", "Notion Transfer", WorkStreamState.CHECK, null).copy(snoozeReason = SnoozeReason.HUMAN_RETURN),
            ws("s10", "Design Review", WorkStreamState.READY, "p1")
        )
        val repo = InMemoryWorkStreamRepository(streams,
            listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0), Project("p4", "App Fix", createdAt = t0, updatedAt = t0)),
            DemoHierarchySeed.tasks(streams, t0))
        val clock = FakeClock(t0)
        val vm = AgentCreateViewModel(DefaultVirlinActions(repo, clock, SequentialIdProvider()), repo)
        vm.choose(CreateKind.TASK); vm.setWorkStream("s1"); vm.setTitle("Question 18 · essay plan")
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            VirlinTheme { Box(Modifier.width(411.dp).background(VirlinColors.Background).padding(20.dp)) { AgentCreateArea(vm) } }
        }
        repeat(5) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/agent_create_live.png")
    }
}
