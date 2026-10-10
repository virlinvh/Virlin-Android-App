package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProjectSearchUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun filtersCardsAndRoutesTheExactMatchingSubtask() {
        var opened = ""
        val actions = ProjectPageActions(onBack = {}, onSearch = {}, onProgress = {}, onOpenWorkstream = { opened = it },
            onAddWorkstream = {}, onOpenTask = { opened = it }, onToggleTask = {}, onTaskMenu = {}, onAddTask = {},
            onOpenDocument = {}, onAddDocument = {}, onProjectSettings = {}, onEditProject = {}, onManageWorkstreams = {})
        val project = ProjectPageUi("p", "Project", "", workstreams = listOf(
            WorkstreamUi("ws", "Build", 2, 5, state = WorkstreamState.WAITING, stateLabel = "Waiting")), standaloneTasks = emptyList(), documents = emptyList(),
            searchTasks = listOf(StreamTaskUi("child", "parent", "Release notes", 0, focusedLabel = "12m")),
            searchItems = listOf(ProjectSearchItem("ws", "Build", "Workstream", ""),
                ProjectSearchItem("child", "Release notes", "Subtask", "Build › Release")))
        compose.setContent { ProjectSearchScreen(project, actions, {}) }
        compose.onNodeWithText("2 of 5 tasks complete · Waiting").assertExists()
        compose.onNodeWithTag("workstream_controls_ws").assertExists()
        compose.onNodeWithText("12m focused").assertExists()
        compose.onNodeWithTag("project_search_query").performTextInput("notes")
        compose.onNodeWithTag("project_search_result_ws").assertDoesNotExist()
        compose.onNodeWithTag("project_search_result_child").performClick()
        compose.runOnIdle { assertEquals("child", opened) }
        compose.onNodeWithTag("project_search_query").performTextReplacement("nothing")
        compose.onNodeWithTag("project_search_empty").assertIsDisplayed()
    }
}
