package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.domain.model.Project
import com.virlin.app.ui.hierarchy.ProjectSettingsDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ProjectCompletionUiTest {
    @get:Rule val compose = createComposeRule()
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
