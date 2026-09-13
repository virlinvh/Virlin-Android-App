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
import com.virlin.app.ui.hierarchy.TaskCancelConfirmTag
import com.virlin.app.ui.hierarchy.TaskCancelTag
import com.virlin.app.ui.hierarchy.TaskDetailTag
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

    private fun openStreams() { composeRule.onNodeWithText("Streams").performClick(); pump(1000) }

    /** [filter] narrows the lazy list so the target row is composed (lazy items can't be scrolled to by tag). */
    private fun openStream(id: String, filter: String) {
        openStreams()
        composeRule.onNodeWithTag("streams_filter_$filter").performClick(); pump(300)
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.StreamsListTag)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("stream_row_$id")); pump(400)
        // Tap near the row's left edge: a full-width row's centre can sit under the floating Orb.
        composeRule.onNodeWithTag("stream_row_$id").performTouchInput { click(androidx.compose.ui.geometry.Offset(24f, centerY)) }; pump(1000)
        composeRule.onNodeWithTag(WorkStreamDetailTag).assertIsDisplayed()
    }

    @Test fun projectPath_streams_project_workStream_task() {
        openStreams()
        composeRule.onNodeWithTag("streams_filter_projects").performClick(); pump(200)
        composeRule.onNodeWithTag("project_row_p1").performClick(); pump(1000)
        composeRule.onNodeWithTag(ProjectDetailTag).assertIsDisplayed()
        composeRule.onNodeWithTag("stream_row_s4").performClick(); pump(1000)
        composeRule.onNodeWithTag(WorkStreamDetailTag).assertIsDisplayed()
        composeRule.onNodeWithTag(taskRowTag("t_nl")).performScrollTo(); pump(400)                    // auto-expanded current task
        composeRule.onNodeWithTag(taskRowTag("t_nl")).performClick(); pump(1000)
        composeRule.onNodeWithTag(TaskDetailTag).assertIsDisplayed()
        composeRule.onNodeWithTag("task_breadcrumb").assertIsDisplayed()
        composeRule.onNodeWithText("NATURAL LANGUAGE").assertIsDisplayed()
        Espresso.pressBack(); pump(1000)
        composeRule.onNodeWithTag(WorkStreamDetailTag).assertIsDisplayed()
    }

    @Test fun projectlessPath_expandCollapse_completeLeaf() {
        openStream("s8", "ready")
        composeRule.onNodeWithText("83% COMPLETE").assertDoesNotExist()
        composeRule.onNodeWithTag(taskRowTag("n_gaps")).assertIsDisplayed()
        // complete a leaf via its marker → row updates, progress from domain
        composeRule.onNodeWithTag(taskCompleteTag("n_gaps")).performClick(); pump(1000)
        composeRule.onNodeWithText("66% COMPLETE").assertExists()
    }

    @Test fun nested_expandCollapse_toggle() {
        // s1 is the seeded Focus: open it from the Now card (the Streams list can be long after other classes).
        composeRule.onNodeWithTag(com.virlin.app.ui.screens.FocusContextTag).performClick(); pump(1000)
        composeRule.onNodeWithTag(WorkStreamDetailTag).assertIsDisplayed()
        composeRule.onNodeWithTag(taskRowTag("p_q17")).performScrollTo(); pump(400)                  // path auto-expanded
        composeRule.onNodeWithTag(taskRowTag("p_q17")).assertIsDisplayed()
        composeRule.onNodeWithTag(taskToggleTag("p_answer")).performScrollTo(); pump(400)
        composeRule.onNodeWithContentDescription("Collapse Answer Questions").assertIsDisplayed()
        composeRule.onNodeWithTag(taskToggleTag("p_answer")).performClick(); pump(600)                 // collapse
        composeRule.onNodeWithContentDescription("Expand Answer Questions").assertIsDisplayed()
        composeRule.onNodeWithTag(taskRowTag("p_q17")).assertDoesNotExist()
        composeRule.onNodeWithTag(taskToggleTag("p_answer")).performClick(); pump(300)                 // expand again
        composeRule.onNodeWithTag(taskRowTag("p_q2")).assertIsDisplayed()
    }

    @Test fun addTask_thenAddSubtask_thenCancelWithConfirm() {
        openStream("s8", "ready")
        composeRule.onNodeWithTag(AddTaskButtonTag).performScrollTo(); pump(400)
        composeRule.onNodeWithTag(AddTaskButtonTag).performClick(); pump(600)
        composeRule.onNodeWithTag(AddTaskTitleTag).performTextInput("Instrumented root task")
        composeRule.onNodeWithTag(AddTaskConfirmTag).performClick(); pump(500)
        composeRule.onNodeWithText("Instrumented root task").performScrollTo(); pump(400)
        composeRule.onNodeWithText("Instrumented root task").assertIsDisplayed().performClick(); pump(1000)
        composeRule.onNodeWithTag(TaskDetailTag).assertIsDisplayed()
        composeRule.onNodeWithTag(AddTaskButtonTag).performScrollTo(); pump(400)
        composeRule.onNodeWithTag(AddTaskButtonTag).performClick(); pump(300)       // "+ SUBTASK"
        composeRule.onNodeWithTag(AddTaskTitleTag).performTextInput("Instrumented subtask")
        composeRule.onNodeWithTag(AddTaskConfirmTag).performClick(); pump(500)
        composeRule.onNodeWithText("Instrumented subtask").assertIsDisplayed()
        // cancel the parent: secondary control + explicit confirmation because it has a descendant
        composeRule.onNodeWithTag(TaskCancelTag).performScrollTo(); pump(400)
        composeRule.onNodeWithTag(TaskCancelTag).performClick(); pump(300)
        composeRule.onNodeWithTag(TaskCancelConfirmTag).performClick(); pump(500)
        composeRule.onNodeWithText("CANCELLED").assertIsDisplayed()
    }
}
