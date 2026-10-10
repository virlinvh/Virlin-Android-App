package com.virlin.app.streams

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.domain.model.*
import com.virlin.app.ui.screens.*
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilteredProjectTasksTest {
    @get:Rule val rule = createComposeRule()
    @Test fun switchChangesViewWithoutExecutionActions() {
        var tasks by mutableStateOf(true)
        rule.setContent { VirlinTheme { DirectoryViewSwitch(tasks) { tasks = it } } }
        rule.onNodeWithTag("directory_view_projects").performClick()
        rule.runOnIdle { assertFalse(tasks) }
        rule.onNodeWithTag("directory_view_tasks").performClick()
        rule.runOnIdle { assertTrue(tasks) }
    }
    @Test fun projectGroupCollapsesAndExactRowOpens() {
        val now = Instant.parse("2026-10-05T12:00:00Z")
        val p = Project("p", "Project", createdAt = now, updatedAt = now)
        val work = WorkStream("leaf", "Exact task", state = WorkStreamState.PROCESSING, createdAt = now, updatedAt = now)
        val row = DirectoryWorkItem(work, null, p, "Parent › Project", emptyList(), "External 5m")
        var opened: String? = null
        rule.setContent { VirlinTheme { FilteredProjectTasks(listOf(row), ProjectDirectoryFilter.PROCESSING) { opened = it.work.id } } }
        rule.onNodeWithTag("directory_work_leaf").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("leaf", opened) }
        rule.onNodeWithTag("directory_group_p").performClick()
        rule.onNodeWithTag("directory_work_leaf").assertDoesNotExist()
        rule.onNodeWithTag("directory_group_p").performClick()
        rule.onNodeWithTag("directory_work_leaf").assertIsDisplayed()
    }
}
