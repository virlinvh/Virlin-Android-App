package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.ui.hierarchy.AddTaskButtonTag
import com.virlin.app.ui.hierarchy.AddTaskConfirmTag
import com.virlin.app.ui.hierarchy.AddTaskTitleTag
import com.virlin.app.ui.hierarchy.ProjectDetailTag
import com.virlin.app.ui.hierarchy.WorkStreamDetailTag
import com.virlin.app.ui.hierarchy.taskCompleteTag
import com.virlin.app.ui.hierarchy.taskRowTag
import com.virlin.app.ui.hierarchy.taskToggleTag
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pass 2 hierarchy navigation and actions on the real app (Pixel 8). Streams → Project →
 * WorkStream → Task, plus complete / cancel / add through the screens. The domain is a
 * process-wide singleton, so each test uses its own tasks and unique titles.
 */
@RunWith(AndroidJUnit4::class)
class HierarchyUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) {
            composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8)
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun openStreams() { composeRule.onNodeWithText("Projects").performClick(); pump(1000) }

    /**
     * The directory lists PROJECTS, so a WorkStream is reached through the one that owns it —
     * the same path a person walks. The owning project comes from the domain, not from a guess.
     */
    private fun openStream(id: String) {
        val projectId = com.virlin.app.domain.VirlinGraph.repository.streams.value
            .first { it.id == id }.projectId
        requireNotNull(projectId) { "stream $id has no project to open it through" }
        openStreams()
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.StreamsListTag)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("project_row_$projectId")); pump(400)
        // Tap near the row's left edge: a full-width row's centre can sit under the floating Orb.
        composeRule.onNodeWithTag("project_row_$projectId")
            .performTouchInput { click(androidx.compose.ui.geometry.Offset(24f, centerY)) }; pump(1000)
        composeRule.onNodeWithTag(ProjectDetailTag).assertIsDisplayed()
        composeRule.onNodeWithTag("stream_row_$id").performScrollTo(); pump(300)
        composeRule.onNodeWithTag("stream_row_$id").performClick(); pump(1000)
        composeRule.onNodeWithTag(WorkStreamDetailTag).assertIsDisplayed()
    }

    @Test fun projectPath_streams_project_workStream_task() {
        openStreams()
        composeRule.onNodeWithTag("project_row_p1").performClick(); pump(1000)
        composeRule.onNodeWithTag(ProjectDetailTag).assertIsDisplayed()
        composeRule.onNodeWithTag("stream_row_s4").performClick(); pump(1000)
        composeRule.onNodeWithTag(WorkStreamDetailTag).assertIsDisplayed()
        // The task page shows one level at a time, so the path is walked: Create Mode ->
        // Reminder Creation -> Natural Language, each opening its own level.
        composeRule.onNodeWithTag(taskRowTag("t_create")).performScrollTo(); pump(300)
        composeRule.onNodeWithTag(taskRowTag("t_create")).performClick(); pump(900)
        composeRule.onNodeWithTag(taskRowTag("t_rem")).performScrollTo(); pump(300)
        composeRule.onNodeWithTag(taskRowTag("t_rem")).performClick(); pump(900)
        composeRule.onNodeWithTag(taskRowTag("t_nl")).performScrollTo(); pump(300)
        composeRule.onNodeWithTag(taskRowTag("t_nl")).performClick(); pump(900)
        // A leaf opens its own level and carries its own actions.
        composeRule.onNodeWithContentDescription("Actions for Natural Language").assertIsDisplayed()
        composeRule.onNodeWithText("Create Mode / Reminder Creation / Natural Language")
            .assertIsDisplayed()
    }

    // s8 is deliberately projectless, and the redesigned directory lists PROJECTS only, so there
    // is currently no browsing path to a WorkStream that belongs to no project. The coverage is
    // kept intact and disabled rather than retargeted, because it is the projectless path itself
    // that it proves; re-enable it once such a stream is reachable again.
    @org.junit.Ignore("No UI path to a projectless WorkStream since the projects-directory redesign")
    @Test fun projectlessPath_expandCollapse_completeLeaf() {
        openStream("s8")
        composeRule.onNodeWithText("83% COMPLETE").assertDoesNotExist()
        composeRule.onNodeWithTag(taskRowTag("n_gaps")).assertIsDisplayed()
        // complete a leaf via its marker → row updates, progress from domain
        composeRule.onNodeWithTag(taskCompleteTag("n_gaps")).performClick(); pump(1000)
        composeRule.onNodeWithText("66% COMPLETE").assertExists()
    }

    /**
     * The task page shows ONE level at a time rather than an expanding tree, so descending and
     * climbing back is what has to hold: a child is not visible from its grandparent, and the
     * level control returns to the parent with the same rows.
     */
    @Test fun nestedLevels_descendAndReturn() {
        // s1 is the seeded Focus: open it from the Now card (the Streams list can be long after other classes).
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.FocusContextTag).performClick(); pump(1000)
        composeRule.onNodeWithTag(WorkStreamDetailTag).assertIsDisplayed()
        // Question 17 lives under Question 2, which lives under Answer Questions.
        composeRule.onNodeWithTag(taskRowTag("p_q17")).assertDoesNotExist()
        composeRule.onNodeWithTag(taskRowTag("p_answer")).performScrollTo(); pump(300)
        composeRule.onNodeWithTag(taskRowTag("p_answer")).performClick(); pump(900)
        composeRule.onNodeWithTag(taskRowTag("p_q2")).performScrollTo(); pump(300)
        composeRule.onNodeWithTag(taskRowTag("p_q2")).performClick(); pump(900)
        composeRule.onNodeWithTag(taskRowTag("p_q17")).assertIsDisplayed()
        // Climb one level: Question 2's siblings are back and its child is gone.
        composeRule.onNodeWithContentDescription("Back").performClick(); pump(900)
        composeRule.onNodeWithTag(taskRowTag("p_q2")).assertIsDisplayed()
        composeRule.onNodeWithTag(taskRowTag("p_q17")).assertDoesNotExist()
    }

    @Test fun addTask_thenAddSubtask_thenCancelWithConfirm() {
        // The stream task page: a task is added at the level you are on, every task opens its
        // own level whether or not it has children, and cancelling asks first.
        openStream("s4")
        composeRule.onNodeWithTag(AddTaskButtonTag).performClick(); pump(700)
        composeRule.onNodeWithTag(AddTaskTitleTag).performTextInput("Instrumented root task")
        composeRule.onNodeWithTag(AddTaskConfirmTag).performClick(); pump(900)
        val root = com.virlin.app.domain.VirlinGraph.repository.tasks.value
            .last { it.title == "Instrumented root task" }

        // A leaf is not a dead end: opening it is where its first subtask is added.
        composeRule.onNodeWithTag(taskRowTag(root.id)).performScrollTo(); pump(300)
        composeRule.onNodeWithTag(taskRowTag(root.id)).performClick(); pump(1000)
        composeRule.onNodeWithTag(AddTaskButtonTag).performClick(); pump(700)
        composeRule.onNodeWithTag(AddTaskTitleTag).performTextInput("Instrumented subtask")
        composeRule.onNodeWithTag(AddTaskConfirmTag).performClick(); pump(900)
        composeRule.onNodeWithText("Instrumented subtask").assertIsDisplayed()
        val child = com.virlin.app.domain.VirlinGraph.repository.tasks.value
            .last { it.title == "Instrumented subtask" }
        org.junit.Assert.assertEquals(root.id, child.parentTaskId)

        // Cancel the open task from its own menu; a parent with a descendant confirms first.
        composeRule.onNodeWithContentDescription("Actions for ${root.title}").performClick(); pump(400)
        composeRule.onNodeWithText("Cancel task").performClick(); pump(400)
        composeRule.onNodeWithText("Cancel tasks").performClick(); pump(900)
        // Virlin cancels rather than erases: the task stays, out of active work.
        val cancelled = com.virlin.app.domain.VirlinGraph.repository.tasks.value
            .first { it.id == root.id }
        org.junit.Assert.assertEquals(
            com.virlin.app.domain.model.TaskStatus.CANCELLED, cancelled.status)
    }
}
