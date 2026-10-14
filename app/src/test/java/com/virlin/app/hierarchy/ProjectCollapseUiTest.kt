package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProjectCollapseUiTest {
    @get:Rule val compose = createComposeRule()
    private fun showGestureOverview() {
        val ui = ProjectPageUi("gesture-project", "Project name", "",
            workstreams = List(14) { WorkstreamUi("s$it", "Stream $it", 0, 0) },
            standaloneTasks = emptyList(), documents = emptyList())
        val actions = ProjectPageActions(onBack = {}, onSearch = {}, onProgress = {},
            onOpenWorkstream = {}, onAddWorkstream = {}, onOpenTask = {}, onToggleTask = {},
            onTaskMenu = {}, onAddTask = {}, onOpenDocument = {}, onAddDocument = {},
            onProjectSettings = {}, onEditProject = {}, onManageWorkstreams = {})
        compose.setContent { VirlinProjectExperience(
            ProjectExperienceData(ui, emptyList(), emptyList(), activity = emptyList()),
            ProjectExperienceActions(actions,
                KnowledgeActions({}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> },
                    { _, _, _ -> }, onMoveMany = { _, _, _ -> }), ActivityActions({}, {}, {}, {}, {})),
            projectIcon = {}) }
    }

    @Test fun swipeFromProjectTitleCollapsesOverviewHeader() {
        showGestureOverview()
        val header = compose.onNodeWithTag("active_project_collapsible_details")
        val expanded = header.fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("project_title_row").performTouchInput {
            swipe(center, center.copy(y = center.y - 260f), durationMillis = 600)
        }
        assertTrue(header.fetchSemanticsNode().boundsInRoot.height < expanded)
    }

    @Test fun swipeFromToolbarCollapsesOverviewHeader() {
        showGestureOverview()
        val header = compose.onNodeWithTag("active_project_collapsible_details")
        val expanded = header.fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("project_top_bar").performTouchInput {
            swipe(center, center.copy(y = center.y - 260f), durationMillis = 600)
        }
        assertTrue(header.fetchSemanticsNode().boundsInRoot.height < expanded)
    }

    @Test fun swipeFromProgressAndWorkstreamsHeadingScrollsOverview() {
        showGestureOverview()
        val header = compose.onNodeWithTag("active_project_collapsible_details")
        val expanded = header.fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag(ProjectProgressBlockTag).performTouchInput {
            swipe(center, center.copy(y = center.y - 180f), durationMillis = 600)
        }
        val afterProgress = header.fetchSemanticsNode().boundsInRoot.height
        assertTrue(afterProgress < expanded)
        compose.onNodeWithTag("project_workstreams_heading").performTouchInput {
            swipe(center, center.copy(y = center.y - 180f), durationMillis = 600)
        }
        assertTrue(header.fetchSemanticsNode().boundsInRoot.height <= afterProgress)
    }
    @Test fun timerStatusDescriptionsNeverAppearInProjectHeader() {
        var toggles = 0
        val ui = ProjectPageUi("p", "Project", "", workstreams = emptyList(),
            standaloneTasks = emptyList(), documents = emptyList(),
            timerControlMessage = "Project tasks resumed · Time preserved",
            externalSeconds = 3661, focusSeconds = 125)
        val actions = ProjectPageActions(onBack = {}, onSearch = {}, onProgress = {},
            onOpenWorkstream = {}, onAddWorkstream = {}, onOpenTask = {}, onToggleTask = {},
            onTaskMenu = {}, onAddTask = {}, onOpenDocument = {}, onAddDocument = {},
            onProjectSettings = {}, onEditProject = {}, onManageWorkstreams = {},
            onToggleTimers = { toggles++ })
        val experienceActions = ProjectExperienceActions(actions,
            KnowledgeActions({}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> },
                { _, _, _ -> }, onMoveMany = { _, _, _ -> }), ActivityActions({}, {}, {}, {}, {}))
        compose.setContent { VirlinProjectExperience(
            ProjectExperienceData(ui, emptyList(), emptyList(), activity = emptyList()),
            experienceActions, projectIcon = {}) }
        compose.onNodeWithText("Project tasks resumed · Time preserved").assertDoesNotExist()
        compose.onNodeWithTag("project_timer_message").assertDoesNotExist()
        compose.onNodeWithText("External 1:01:01 · Focused 02:05").assertDoesNotExist()
        compose.onNodeWithTag("project_timer_button").performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
    }
    @Test fun overviewHeaderPositionSurvivesScreenRestoration() {
        val ui = ProjectPageUi("p", "Project name", "", workstreams = emptyList(),
            standaloneTasks = List(20) { ProjectTaskUi("t$it", "Task $it", false) }, documents = emptyList())
        val actions = ProjectPageActions(onBack = {}, onSearch = {}, onProgress = {},
            onOpenWorkstream = {}, onAddWorkstream = {}, onOpenTask = {}, onToggleTask = {},
            onTaskMenu = {}, onAddTask = {}, onOpenDocument = {}, onAddDocument = {},
            onProjectSettings = {}, onEditProject = {}, onManageWorkstreams = {})
        val experienceActions = ProjectExperienceActions(actions,
            KnowledgeActions({}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> },
                { _, _, _ -> }, onMoveMany = { _, _, _ -> }), ActivityActions({}, {}, {}, {}, {}))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            VirlinProjectExperience(
                ProjectExperienceData(ui, emptyList(), emptyList(), activity = emptyList()),
                experienceActions, projectIcon = {})
        }
        fun height() = compose.onNodeWithTag("active_project_collapsible_details")
            .fetchSemanticsNode().boundsInRoot.height
        val expanded = height()
        compose.onNodeWithTag("active_project_overview_scroll").performTouchInput { swipeUp() }
        val collapsed = height()
        assertTrue(collapsed < expanded)
        restoration.emulateSavedInstanceStateRestore()
        assertTrue("Overview should reopen at its collapsed scroll position", height() <= collapsed + 1f)
    }
    @Test fun workstreamAddFloatsOverListAndMindMapRemainsInHeading() {
        var addCount = 0
        var mapCount = 0
        val ui = ProjectPageUi("p", "Project", "", workstreams = emptyList(),
            standaloneTasks = emptyList(), documents = emptyList())
        val actions = ProjectPageActions(onBack = {}, onSearch = {}, onProgress = {},
            onOpenWorkstream = {}, onAddWorkstream = { addCount++ }, onOpenMap = { mapCount++ },
            onOpenTask = {}, onToggleTask = {}, onTaskMenu = {}, onAddTask = {},
            onOpenDocument = {}, onAddDocument = {}, onProjectSettings = {},
            onEditProject = {}, onManageWorkstreams = {})
        val experienceActions = ProjectExperienceActions(actions,
            KnowledgeActions({}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> },
                { _, _, _ -> }, onMoveMany = { _, _, _ -> }), ActivityActions({}, {}, {}, {}, {}))
        compose.setContent { VirlinProjectExperience(
            ProjectExperienceData(ui, emptyList(), emptyList(), activity = emptyList()),
            experienceActions, projectIcon = {}) }
        val list = compose.onNodeWithTag("active_project_overview_scroll").fetchSemanticsNode().boundsInRoot
        val add = compose.onNodeWithTag(AddWorkStreamButtonTag).fetchSemanticsNode().boundsInRoot
        assertTrue("Workstream cards should extend beneath the floating add button", add.top < list.bottom)
        assertTrue("The floating add button stays within the overview viewport", add.bottom <= list.bottom + 1f)
        compose.onNodeWithTag(AddWorkStreamButtonTag).assertIsDisplayed().performClick()
        compose.onNodeWithTag(ProjectMapButtonTag).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, addCount); assertEquals(1, mapCount) }
        compose.onNodeWithText("Knowledge", substring = false).performClick()
        compose.onNodeWithTag(AddWorkStreamButtonTag).assertDoesNotExist()
    }
    @Test fun settingsButtonOpensPageDirectlyWithoutPopup() {
        var opened = 0
        val ui = ProjectPageUi("p", "Project", "", workstreams = emptyList(),
            standaloneTasks = emptyList(), documents = emptyList())
        val actions = ProjectPageActions(onBack = {}, onSearch = {}, onProgress = {},
            onOpenWorkstream = {}, onAddWorkstream = {}, onOpenTask = {}, onToggleTask = {},
            onTaskMenu = {}, onAddTask = {}, onOpenDocument = {}, onAddDocument = {},
            onProjectSettings = { opened++ }, onEditProject = {}, onManageWorkstreams = {})
        val experienceActions = ProjectExperienceActions(actions,
            KnowledgeActions({}, {}, { _, _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> },
                { _, _, _ -> }, onMoveMany = { _, _, _ -> }), ActivityActions({}, {}, {}, {}, {}))
        compose.setContent { VirlinProjectExperience(
            ProjectExperienceData(ui, emptyList(), emptyList(), activity = emptyList()),
            experienceActions, projectIcon = {}) }
        compose.onNodeWithContentDescription("Project settings").assertIsDisplayed().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, opened) }
        compose.onNodeWithText("Project settings").assertDoesNotExist()
    }
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
        compose.onNodeWithTag("standalone_section_toggle").performScrollTo().performClick()
        compose.onNodeWithTag("active_project_overview_scroll").performScrollToNode(hasText("Task 19"))
        compose.onNodeWithTag("active_project_overview_scroll").performTouchInput {
            swipe(center, center.copy(y = center.y + 80f), durationMillis = 600)
        }
        val stillCollapsed = compose.onNodeWithTag("active_project_collapsible_details").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Header must remain hidden while list is away from top", stillCollapsed <= collapsed + 1f)
        repeat(10) {
            compose.onNodeWithTag("active_project_overview_scroll").performTouchInput { swipeDown() }
        }
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
