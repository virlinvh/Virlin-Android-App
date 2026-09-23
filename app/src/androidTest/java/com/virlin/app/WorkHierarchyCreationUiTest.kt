package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.ui.hierarchy.AddNameConfirmTag
import com.virlin.app.ui.hierarchy.AddNameTitleTag
import com.virlin.app.ui.hierarchy.AddProjectButtonTag
import com.virlin.app.ui.hierarchy.AddTaskButtonTag
import com.virlin.app.ui.hierarchy.AddTaskConfirmTag
import com.virlin.app.ui.hierarchy.AddTaskTitleTag
import com.virlin.app.ui.hierarchy.AddWorkStreamButtonTag
import com.virlin.app.ui.hierarchy.ProjectDetailTag
import com.virlin.app.ui.hierarchy.WorkStreamDetailTag
import com.virlin.app.ui.hierarchy.taskRowTag
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * Phase 08 flows A–H on the real app: create a Project, a WorkStream inside it, a root task, a
 * nested subtask and a deeper one, then complete a leaf and watch progress follow. Everything goes
 * through `VirlinActions`; the assertions read the domain, not the UI's own state.
 */
class WorkHierarchyCreationUiTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule val rules: RuleChain = RuleChain.outerRule(DemoStateRule()).around(composeRule)

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    private fun touch(t: String, after: Long = 700) {
        runCatching { tag(t).performScrollTo() }.onSuccess { pump(900) }
        tag(t).performClick(); pump(after)
    }
    private fun nameAndCreate(name: String) {
        tag(AddNameTitleTag).performTextInput(name); pump(300)
        tag(AddNameConfirmTag).performClick(); pump(900)
    }
    private fun taskAndCreate(name: String) {
        tag(AddTaskTitleTag).performTextInput(name); pump(300)
        tag(AddTaskConfirmTag).performClick(); pump(900)
    }
    private fun projects() = VirlinGraph.repository.projects.value
    private fun streams() = VirlinGraph.repository.streams.value
    private fun tasks() = VirlinGraph.repository.tasks.value

    @Test fun createProject_workStream_task_subtask_deeper_thenComplete() {
        pump(900)
        // FLOW A/C: a Project is created from Streams in one field.
        composeRule.onNodeWithText("Streams").performClick(); pump(900)
        composeRule.onNodeWithTag("streams_filter_projects").performClick(); pump(400)
        val projectsBefore = projects().size
        touch(AddProjectButtonTag, 900)
        nameAndCreate("Phase 08 Project")
        val project = projects().first { it.title == "Phase 08 Project" }
        assert(projects().size == projectsBefore + 1)

        // FLOW A: its detail opens and is empty but not "0%".
        touch("project_row_${project.id}", 1200)
        tag(ProjectDetailTag).assertIsDisplayed()
        assert(ProgressCalculator.ofProject(tasks(), streams(), project.id) == ProgressResult.Unstructured)

        // FLOW B/C: a WorkStream inside the project, from the project's own screen.
        touch(AddWorkStreamButtonTag, 900)
        nameAndCreate("Android App")
        val stream = streams().first { it.title == "Android App" && it.projectId == project.id }
        touch("stream_row_${stream.id}", 1200)
        tag(WorkStreamDetailTag).assertIsDisplayed()

        // FLOW C: a root task.
        touch(AddTaskButtonTag, 900)
        taskAndCreate("Build Orb Interaction")
        val root = tasks().first { it.title == "Build Orb Interaction" }
        assert(root.workStreamId == stream.id && root.parentTaskId == null)
        tag(taskRowTag(root.id)).assertIsDisplayed()

        // FLOW D/E: a subtask, then a deeper one — the same entity, one level down each time.
        touch(taskRowTag(root.id), 1200)
        touch(AddTaskButtonTag, 900)
        taskAndCreate("Implement states")
        val child = tasks().first { it.title == "Implement states" }
        assert(child.parentTaskId == root.id)
        touch(taskRowTag(child.id), 1200)
        touch(AddTaskButtonTag, 900)
        taskAndCreate("Listening")
        val grandchild = tasks().first { it.title == "Listening" }
        assert(grandchild.parentTaskId == child.id)
        assert(grandchild.workStreamId == stream.id)                       // ownership inherited, not re-entered

        // FLOW G: completing the deepest leaf moves the derived progress, and only that item.
        val before = ProgressCalculator.ofWorkStream(tasks(), stream.id)
        assert(before is ProgressResult.Structured && before.completedLeaves == 0 && before.totalLeaves == 1)
        touch(com.virlin.app.ui.hierarchy.taskCompleteTag(grandchild.id), 1200)
        val after = ProgressCalculator.ofWorkStream(tasks(), stream.id)
        assert(after is ProgressResult.Structured && after.completedLeaves == 1) { "progress did not follow completion: $after" }
        // FLOW G (parent rule): the parents are NOT auto-completed by their child.
        assert(tasks().first { it.id == child.id }.status.isCompleted.not())
        assert(tasks().first { it.id == root.id }.status.isCompleted.not())
    }
}
