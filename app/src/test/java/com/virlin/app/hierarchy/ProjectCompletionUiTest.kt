package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.domain.model.Project
import com.virlin.app.ui.hierarchy.ProjectSettingsDialog
import com.virlin.app.ui.hierarchy.ProjectIconSettingsEditor
import com.virlin.app.ui.components.projectIconChoiceTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.hierarchy.ProjectStorageSettings

@RunWith(RobolectricTestRunner::class)
class ProjectCompletionUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun compactCopyViewportKeepsFooterActionsVisible() {
        val project = Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        compose.setContent { Box(Modifier.size(320.dp, 320.dp)) { ProjectStorageSettings(project) } }
        compose.onNodeWithText("Duplicate project").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithTag("confirm_duplicate_project").assertIsDisplayed()
        compose.onNodeWithTag("duplicate_project_name").performScrollTo().performTextClearance()
        compose.onNodeWithTag("confirm_duplicate_project").assertIsNotEnabled()
    }
    @Test fun storageOptionsAreLiveInlineAndCopyActionsStayVisible() {
        val project = Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        compose.setContent {
            ProjectSettingsDialog(project, emptyList(), onDismiss = {}, onRename = {},
                onEditIcon = {}, onExecutionDefault = {}, onRestoreTask = {}, onCompleteProject = {})
        }
        compose.onNodeWithText("Storage & export").performScrollTo().performClick()
        compose.onNodeWithText("Planned").assertDoesNotExist()
        compose.onNodeWithText("File and media storage").assertIsDisplayed()
        compose.onNodeWithText("Export project").performClick()
        compose.onNodeWithText("PDF").assertIsDisplayed()
        compose.onNodeWithText("Markdown").assertIsDisplayed()
        compose.onNodeWithText("Backup archive (.zip)").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Duplicate project").performClick()
        compose.onNodeWithText("Structure only").assertIsDisplayed()
        compose.onNodeWithText("Full content").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithTag("confirm_duplicate_project").assertIsDisplayed()
        compose.onNodeWithTag("duplicate_project_name").performTextClearance()
        compose.onNodeWithTag("confirm_duplicate_project").assertIsNotEnabled()
    }
    @Test fun organizationKeepsBlocksWithoutPlannedOptions() {
        val project = Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        compose.setContent {
            ProjectSettingsDialog(project, emptyList(), onDismiss = {}, onRename = {},
                onEditIcon = {}, onExecutionDefault = {}, onRestoreTask = {}, onCompleteProject = {})
        }
        compose.onNodeWithText("Organization").performClick()
        compose.onNodeWithTag("workstream_blocks_switch").assertIsDisplayed()
        listOf("WorkStream icons", "WorkStream ordering", "Task ordering", "Progress calculation", "Planned").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }
    @Test fun defaultsOnlyShowsWorkingExecutionSetting() {
        val project = Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        compose.setContent {
            ProjectSettingsDialog(project, emptyList(), onDismiss = {}, onRename = {},
                onEditIcon = {}, onExecutionDefault = {}, onRestoreTask = {}, onCompleteProject = {})
        }
        compose.onNodeWithText("Defaults & workflow").performClick()
        compose.onNodeWithText("Default execution").assertIsDisplayed()
        listOf("Default task execution", "Default task state", "Completion behavior", "Opening tab", "Planned").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Default execution").performClick()
        compose.onNodeWithText("Internal").assertIsDisplayed()
        compose.onNodeWithText("External").assertIsDisplayed()
    }
    @Test fun iconOpensInlineAndOnlySavesExplicitly() {
        var chosen = ""
        var modalOpens = 0
        val project = Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        compose.setContent {
            ProjectSettingsDialog(project, emptyList(), onDismiss = {}, onRename = {},
                onEditIcon = { modalOpens++ }, onExecutionDefault = {}, onRestoreTask = {},
                onCompleteProject = {}, iconEditor = { close ->
                    ProjectIconSettingsEditor(project, { chosen = it }, {}, {}, close)
                })
        }
        compose.onNodeWithText("Identity & status").performClick()
        compose.onNodeWithText("Project icon").performClick()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText("Save changes").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(projectIconChoiceTag("code")).performClick()
        compose.runOnIdle { assertEquals("", chosen); assertEquals(0, modalOpens) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Project icon").performClick()
        compose.onNodeWithTag(projectIconChoiceTag("code")).performClick()
        compose.onNodeWithText("Save changes").performClick()
        compose.runOnIdle { assertEquals("code", chosen); assertEquals(0, modalOpens) }
    }
    @Test fun completionRequiresInlineConfirmation() {
        var completed = 0
        val now = Instant.EPOCH
        compose.setContent {
            ProjectSettingsDialog(Project("p", "Project", createdAt = now, updatedAt = now), emptyList(),
                onDismiss = {}, onRename = {}, onEditIcon = {}, onExecutionDefault = {},
                onRestoreTask = {}, onCompleteProject = { completed++ })
        }
        compose.onNodeWithText("Project management").performScrollTo().performClick()
        compose.onNodeWithText("Complete project").performClick()
        compose.onNodeWithTag("complete_project_inline_confirmation").assertExists()
        compose.runOnIdle { assertEquals(0, completed) }
        compose.onNodeWithText("Keep project").performScrollTo().performClick()
        compose.onNodeWithTag("complete_project_inline_confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, completed) }
        compose.onNodeWithText("Complete project").performClick()
        compose.onNodeWithTag("confirm_complete_project").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, completed) }
    }
}
