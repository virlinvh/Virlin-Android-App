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
import com.virlin.app.ui.agent.control.AgentControlArea
import com.virlin.app.ui.agent.control.AgentControlViewModel
import com.virlin.app.ui.theme.VirlinColors
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Pass 8 candidate: the live CONTROL content rendered standalone against fixed domain data
 * (fake clock, in-memory repository). The approved `agent_control.png` shell golden is
 * untouched — the shell still renders its original context strip when no control content is
 * injected. GOLDEN RULE: never re-record after a failure without explicit approval.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentControlScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Test fun agentControlLive() {
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
        val vm = AgentControlViewModel(DefaultVirlinActions(repo, clock, SequentialIdProvider()), clock, repo)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            VirlinTheme { Box(Modifier.width(411.dp).background(VirlinColors.Background).padding(20.dp)) { AgentControlArea(vm) } }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/agent_control_live.png")
    }
}
