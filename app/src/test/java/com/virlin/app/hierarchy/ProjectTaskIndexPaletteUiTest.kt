package com.virlin.app.hierarchy

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectTaskIndexPaletteUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun projectTimersAreStackedBesideTaskProgress() {
        val actions = object : ProjectTaskIndexActions {
            override fun backToProject(projectId: String) {}
            override fun openTaskInHierarchy(projectId: String, workstreamId: String?, ancestorTaskIds: List<String>, taskId: String?) {}
            override fun searchProjectTasks(projectId: String) {}
            override fun showOptions(projectId: String) {}
            override fun retry(projectId: String) {}
        }
        compose.setContent {
            VirlinProjectTaskIndex(ProjectTaskIndexState("p", "Project", 7, 17, emptyList(),
                externalSeconds = 3661, focusSeconds = 125), actions, {})
        }
        compose.onNodeWithText("7 of 17 tasks complete").assertIsDisplayed()
        compose.onNodeWithContentDescription("External 1:01:01").assertIsDisplayed()
        compose.onNodeWithContentDescription("Focused 02:05").assertIsDisplayed()
        val external = compose.onNodeWithContentDescription("External 1:01:01").fetchSemanticsNode().boundsInRoot
        val focused = compose.onNodeWithContentDescription("Focused 02:05").fetchSemanticsNode().boundsInRoot
        assertTrue(external.bottom <= focused.top)
    }
    @Test fun executionSummaryAppearsBelowFiltersAndKeepsProjectCounts() {
        val actions = object : ProjectTaskIndexActions {
            override fun backToProject(projectId: String) {}
            override fun openTaskInHierarchy(projectId: String, workstreamId: String?, ancestorTaskIds: List<String>, taskId: String?) {}
            override fun searchProjectTasks(projectId: String) {}
            override fun showOptions(projectId: String) {}
            override fun retry(projectId: String) {}
        }
        compose.setContent {
            VirlinProjectTaskIndex(
                ProjectTaskIndexState("p", "Project", 0, 0, emptyList(),
                    humanActiveCount = 1, externalActiveCount = 2, reviewCount = 1),
                actions, {}
            )
        }
        compose.onNodeWithText("Remaining 0").assertIsDisplayed()
        compose.onNodeWithTag("execution_summary").assertIsDisplayed()
        compose.onNodeWithText("3 active").assertIsDisplayed()
        compose.onNodeWithTag("execution_disclosure").performClick()
        compose.onNodeWithContentDescription("Focus 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Running 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Review 1").assertIsDisplayed()
    }
    @Test fun taskIndexShowsProjectActivityIndicatorsAndKeepsNavigation() {
        var opened: String? = null
        val actions = object : ProjectTaskIndexActions {
            override fun backToProject(projectId: String) {}
            override fun openTaskInHierarchy(projectId: String, workstreamId: String?, ancestorTaskIds: List<String>, taskId: String?) {
                opened = taskId
            }
            override fun searchProjectTasks(projectId: String) {}
            override fun showOptions(projectId: String) {}
            override fun retry(projectId: String) {}
        }
        val states = listOf(
            WorkstreamState.EXTERNAL_RUNNING, WorkstreamState.WAITING,
            WorkstreamState.NEEDS_ATTENTION, WorkstreamState.BLOCKED
        )
        val entries = states.mapIndexed { index, state ->
            ProjectTaskIndexEntry("task$index", "p", "stream", "Stream", null,
                emptyList(), emptyList(), "Task $index", false, false, index,
                activityState = state)
        }
        compose.setContent {
            VirlinProjectTaskIndex(ProjectTaskIndexState("p", "Project", 0, entries.size, entries), actions, {})
        }
        listOf("RUNNING", "WAITING", "REVIEW", "BLOCKED").forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
        compose.onNodeWithText("Task 0").performClick()
        compose.runOnIdle { assertEquals("task0", opened) }
    }
    @Test fun selectedFilterUsesConfiguredProjectColor() {
        val palette = ProjectPalette.from(Color(0xFF7652C9))
        val actions = object : ProjectTaskIndexActions {
            override fun backToProject(projectId: String) {}
            override fun openTaskInHierarchy(projectId: String, workstreamId: String?, ancestorTaskIds: List<String>, taskId: String?) {}
            override fun searchProjectTasks(projectId: String) {}
            override fun showOptions(projectId: String) {}
            override fun retry(projectId: String) {}
        }
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalProjectPalette provides palette) {
                VirlinProjectTaskIndex(ProjectTaskIndexState("p", "Project", 0, 1, emptyList()), actions, {})
            }
        } }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Remaining 1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color(palette.secondary), layouts.single().layoutInput.style.color)
        compose.onNodeWithText("Done 0").performClick()
        layouts.clear()
        compose.onNodeWithText("Done 0").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(Color(palette.secondary), layouts.single().layoutInput.style.color)
    }
}
