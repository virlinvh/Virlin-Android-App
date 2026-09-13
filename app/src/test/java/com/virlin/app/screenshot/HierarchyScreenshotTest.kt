package com.virlin.app.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.domain.DemoHierarchySeed
import com.virlin.app.domain.FakeClock
import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.hierarchy.HierarchyViewModel
import com.virlin.app.ui.hierarchy.ProjectDetailScreen
import com.virlin.app.ui.hierarchy.TaskDetailScreen
import com.virlin.app.ui.hierarchy.WorkStreamDetailScreen
import com.virlin.app.ui.theme.VirlinColors
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Pass 2 goldens — the hierarchy screens rendered against a fixed in-memory repository
 * (DemoHierarchySeed at a fixed instant, fake clock, no MockTimerEngine). New screens only;
 * the five frozen goldens are untouched by this class.
 *
 * GOLDEN RULE: never re-record after a failure without explicit user approval of the diff.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HierarchyScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val t0: Instant = Instant.parse("2026-09-11T10:00:00Z")

    private fun ws(id: String, title: String, state: WorkStreamState, project: String?, tool: String? = null, active: String? = null) =
        WorkStream(id = id, title = title, state = state, projectId = project, tool = tool, activeTaskId = active,
            nextHumanAction = "Review the reminder parser output", createdAt = t0, updatedAt = t0)

    private fun vm(): HierarchyViewModel {
        val streams = listOf(
            ws("s4", "Agent Development", WorkStreamState.PROCESSING, "p1", "Antigravity", "t_nl"),
            ws("s1", "Psychology Unit 23", WorkStreamState.FOCUS, null, active = "p_q17"),
            ws("s9", "Notifications", WorkStreamState.READY, "p1")
        )
        val repo = InMemoryWorkStreamRepository(
            seed = streams,
            seedProjects = listOf(Project("p1", "Virlin Android App", createdAt = t0, updatedAt = t0)),
            seedTasks = DemoHierarchySeed.tasks(streams, t0)
        )
        return HierarchyViewModel(repo, DefaultVirlinActions(repo, FakeClock(t0), SequentialIdProvider()))
    }

    @Composable
    private fun Host(content: @Composable (HierarchyViewModel) -> Unit) {
        val model = vm()
        VirlinTheme {
            Box(Modifier.width(411.dp).height(891.dp).background(VirlinColors.Background)) { content(model) }
        }
    }

    private fun capture(name: String) {
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun projectDetail() {
        composeRule.setContent { Host { ProjectDetailScreen("p1", rememberNavController(), it) } }
        capture("project_detail")
    }

    @Test fun workStreamDetailNested() {
        composeRule.setContent { Host { WorkStreamDetailScreen("s4", rememberNavController(), it) } }
        capture("workstream_detail_nested")
    }

    @Test fun projectlessWorkStreamDetail() {
        composeRule.setContent { Host { WorkStreamDetailScreen("s1", rememberNavController(), it) } }
        capture("projectless_workstream_detail")
    }

    @Test fun taskDetail() {
        composeRule.setContent { Host { TaskDetailScreen("t_rem", rememberNavController(), it) } }
        capture("task_detail")
    }
}
