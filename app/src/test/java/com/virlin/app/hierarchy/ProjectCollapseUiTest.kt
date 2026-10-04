package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProjectCollapseUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun overviewCollapsesAndReturns_withoutHidingPinnedHeadings() {
        val ui = ProjectPageUi("p", "Project name", "", workstreams = emptyList(),
            standaloneTasks = List(20) { ProjectTaskUi("t$it", "Task $it", false) }, documents = emptyList(),
            knowledgeContent = { Text("Knowledge content") }, activityContent = { Text("Activity content") })
        val actions = ProjectPageActions(onBack = {}, onSearch = {}, onProgress = {},
            onOpenWorkstream = {}, onAddWorkstream = {}, onOpenTask = {}, onToggleTask = {},
            onTaskMenu = {}, onAddTask = {}, onOpenDocument = {}, onAddDocument = {},
            onProjectSettings = {}, onEditProject = {}, onManageWorkstreams = {})
        val experienceActions = ProjectExperienceActions(actions,
            KnowledgeActions({}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> },
                { _, _, _ -> }, onMoveMany = { _, _, _ -> }),
            ActivityActions({}, {}, {}, {}, {}))
        compose.setContent { VirlinProjectExperience(
            ProjectExperienceData(ui, emptyList(), emptyList(), activity = emptyList()),
            experienceActions, projectIcon = {}) }
        val before = compose.onNodeWithTag("active_project_collapsible_details").fetchSemanticsNode().boundsInRoot.height
        assertTrue(before > 0f)
        compose.onNodeWithTag("active_project_overview_scroll").performTouchInput { swipeUp() }
        val collapsed = compose.onNodeWithTag("active_project_collapsible_details").fetchSemanticsNode().boundsInRoot.height
        assertTrue(collapsed < before)
        compose.onNodeWithText("Project name").assertIsDisplayed()
        compose.onNodeWithText("Workstreams").assertIsDisplayed()
        compose.onNodeWithTag("active_project_overview_scroll").performTouchInput { swipeDown() }
        val expanded = compose.onNodeWithTag("active_project_collapsible_details").fetchSemanticsNode().boundsInRoot.height
        assertTrue(expanded > collapsed)
        compose.onNodeWithText("Project name").assertIsDisplayed()
        compose.onNodeWithText("Workstreams").assertIsDisplayed()
        compose.onNodeWithText("Knowledge", substring = false).performClick()
        compose.onNodeWithText("Workstreams").assertDoesNotExist()
        compose.onNodeWithText("Activity", substring = false).performClick()
        compose.onNodeWithText("Workstreams").assertDoesNotExist()
    }
}
