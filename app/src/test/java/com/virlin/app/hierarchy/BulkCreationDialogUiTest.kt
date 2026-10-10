package com.virlin.app.hierarchy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.BulkCreationDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], qualifiers = "w411dp-h891dp")
class BulkCreationDialogUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun workstreamPreviewShowsNestedTasksAndSubtasks() {
        compose.setContent { MaterialTheme {
            BulkCreationDialog("New WorkStream in Base", "Workstream name", "base", {}, {}, startBulk = true)
        } }
        compose.onNode(hasSetTextAction()).performTextInput(com.virlin.app.domain.action.BulkHierarchy.example)
        compose.onNodeWithText("Preview", useUnmergedTree = true).performClick()
        compose.onNodeWithText("3 new items · hierarchy shown below").assertIsDisplayed()
        compose.onNodeWithText("Workstream: Phase 01").assertIsDisplayed()
        compose.onNodeWithText("    Task: Project management").assertIsDisplayed()
        compose.onNodeWithText("        Subtask: Implement Save and Save As").assertIsDisplayed()
        compose.onNodeWithText("Add 3 items").assertIsDisplayed()
    }
    @Test fun redesignedModesHelpAndPreviewRemainUsable() {
        compose.setContent { MaterialTheme {
            BulkCreationDialog("New project", "Project name", null, {}, {})
        } }
        compose.onNodeWithText("Add project").assertIsDisplayed()
        compose.onNodeWithText("Bulk").performClick()
        compose.onNodeWithContentDescription("Import format and AI prompt").performClick()
        compose.onNodeWithText("Import format").assertIsDisplayed()
        compose.onNodeWithText(com.virlin.app.domain.action.PhaseImportPrompt.text).assertExists()
        compose.onNodeWithText("Copy AI prompt").performClick()
        compose.onNodeWithText("Import format").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("""{"version":1,"items":[{"name":"First"},{"name":"Second"}]}""")
        compose.onNodeWithText("Preview", useUnmergedTree = true).performClick()
        compose.onNodeWithText("2 new items · hierarchy shown below").assertIsDisplayed()
        compose.onNodeWithText("Project: First").assertIsDisplayed()
        compose.onNodeWithText("Project: Second").assertIsDisplayed()
        compose.onNodeWithText("Edit input").performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("First", substring = true)
    }
}
