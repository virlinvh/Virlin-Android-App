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
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.ui.agent.capture.AgentCaptureArea
import com.virlin.app.ui.agent.capture.AgentCaptureViewModel
import kotlinx.coroutines.runBlocking
import com.virlin.app.ui.theme.VirlinColors
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Pass 10 candidate: the live CAPTURE content (type chips, explicit context line, Inbox with a
 * note / prompt / link newest-first) rendered standalone against fixed domain data. The approved
 * `agent_capture.png` shell golden is untouched — the shell still renders its original demo
 * tray when no capture content is injected. GOLDEN RULE: never re-record after a failure without explicit approval.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentCaptureScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, mode: WorkStreamMode = WorkStreamMode.HUMAN, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, mode = mode, activeTaskId = active, createdAt = t0, updatedAt = t0)

    @Test fun agentCaptureLive() {
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
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        runBlocking {
            actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "https://github.com/virlin/app/pull/42", content = "Receiver fix"))
            clock.advance(java.time.Duration.ofMinutes(16))
            actions.createCapture(CreateCapture(CaptureType.NOTE, content = "Maybe add local song alarms for returns", context = CaptureContext(workStreamId = "s1")))
            clock.advance(java.time.Duration.ofMinutes(2))
            actions.createCapture(CreateCapture(CaptureType.PROMPT, content = "Refactor the notification receiver but preserve the public API.\nKeep every test green."))
        }
        val vm = AgentCaptureViewModel(actions, repo, clock)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            VirlinTheme { Box(Modifier.width(411.dp).background(VirlinColors.Background).padding(20.dp)) { AgentCaptureArea(vm) } }
        }
        repeat(5) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/agent_capture_live.png")
    }
}
