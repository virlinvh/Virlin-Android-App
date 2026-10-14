package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.model.Project
import com.virlin.app.ui.components.SafeDialog
import com.virlin.app.ui.hierarchy.ProjectSettingsDialog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], qualifiers = "w360dp-h640dp")
class PopupViewportUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keyboardSpaceAlreadyExcludedByAndroidIsNotReservedTwice() {
        org.junit.Assert.assertEquals(900, com.virlin.app.ui.components.remainingDialogIme(900, 2400, 2400))
        org.junit.Assert.assertEquals(0, com.virlin.app.ui.components.remainingDialogIme(900, 2400, 1500))
        org.junit.Assert.assertEquals(400, com.virlin.app.ui.components.remainingDialogIme(900, 2400, 1900))
        org.junit.Assert.assertEquals(0, com.virlin.app.ui.components.remainingDialogIme(0, 2400, 1500))
    }

    @Test fun taskCreationCanScrollToActionsInSmallViewport() {
        compose.setContent {
            Box(Modifier.height(220.dp)) {
                com.virlin.app.ui.hierarchy.TaskCreationCard("A long parent task title", "Add subtask", {}, {})
            }
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Child")
        compose.onNodeWithTag(com.virlin.app.ui.hierarchy.AddTaskConfirmTag).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun dialogUsesSingleKeyboardAvoidanceOwner() {
        var dialogWindow: android.view.Window? = null
        compose.setContent {
            SafeDialog(onDismissRequest = {}) {
                val view = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.SideEffect {
                    dialogWindow = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                }
                Surface { TextField("", {}, label = { Text("Name") }) }
            }
        }
        compose.runOnIdle {
            org.junit.Assert.assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
                dialogWindow!!.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h300dp")
    fun projectFormInKeyboardSizedViewportCanTypeAndReachActions() {
        compose.setContent {
            com.virlin.app.ui.hierarchy.BulkCreationDialog("New project", "Project name", null,
                onDismiss = {}, onSingle = {})
        }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextInput("Keyboard test")
        compose.onNodeWithText("Create").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Keyboard test").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h300dp")
    fun bulkFormInKeyboardSizedViewportCanTypeAndReachPreview() {
        compose.setContent {
            com.virlin.app.ui.hierarchy.BulkCreationDialog("New project", "Project name", null,
                onDismiss = {}, onSingle = {}, startBulk = true)
        }
        compose.onNode(hasSetTextAction()).performScrollTo()
            .performTextInput("""{"version":2,"items":[{"name":"Phase 1"}]}""")
        compose.onNodeWithText("Preview").assertIsDisplayed().performClick()
        compose.onNodeWithText("Add 1 items").assertIsDisplayed()
    }

    @Test fun cancelFollowsConfiguredProjectColorAndLiveChanges() {
        val prefs = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("project_appearance", 0)
        val gold = com.virlin.app.ui.hierarchy.ProjectPalette.from(androidx.compose.ui.graphics.Color(0xFFEAB308))
        val blue = com.virlin.app.ui.hierarchy.ProjectPalette.from(androidx.compose.ui.graphics.Color(0xFF2563EB))
        prefs.edit().putString("color-page", gold.encode()).commit()
        val now = Instant.parse("2026-10-05T00:00:00Z")
        compose.setContent {
            ProjectSettingsDialog(Project("color-page", "Project", createdAt = now, updatedAt = now), emptyList(),
                onDismiss = {}, onRename = {}, onEditIcon = {}, onExecutionDefault = {},
                onRestoreTask = {}, onCompleteProject = {})
        }
        fun checkColor(expected: Int) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("Cancel").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            org.junit.Assert.assertEquals(androidx.compose.ui.graphics.Color(expected), layouts.single().layoutInput.style.color)
        }
        checkColor(gold.secondary)
        compose.runOnIdle { prefs.edit().putString("color-page", blue.encode()).commit() }
        checkColor(blue.secondary)
    }

    @Test fun settingsUsesFullWidthAndBackNavigatesBeforeClosing() {
        var closed = false
        val now = Instant.parse("2026-10-05T00:00:00Z")
        compose.setContent {
            ProjectSettingsDialog(Project("page", "Project", createdAt = now, updatedAt = now), emptyList(),
                onDismiss = { closed = true }, onRename = {}, onEditIcon = {}, onExecutionDefault = {},
                onRestoreTask = {}, onCompleteProject = {})
        }
        val bounds = compose.onNodeWithTag(com.virlin.app.ui.hierarchy.ProjectSettingsTestTag).fetchSemanticsNode().boundsInRoot
        compose.onNode(isDialog()).assertDoesNotExist()
        // No SafeDialog's 16dp gutter or 460dp maximum-width card.
        assertTrue(bounds.left <= 1f)
        compose.onNodeWithText("Identity & status").performClick()
        compose.onNodeWithContentDescription("Back to project settings").performClick()
        compose.onNodeWithText("Defaults & workflow").assertExists()
        assertTrue(!closed)
        compose.onNodeWithContentDescription("Back to project").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun settingsFooterRemainsVisibleWithLargeTextAndListScrolls() {
        val now = Instant.parse("2026-10-05T00:00:00Z")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                ProjectSettingsDialog(Project("viewport", "Project", createdAt = now, updatedAt = now), emptyList(),
                    onDismiss = {}, onRename = {}, onEditIcon = {}, onExecutionDefault = {},
                    onRestoreTask = {}, onCompleteProject = {})
            }
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Save changes").assertIsDisplayed()
        compose.onNodeWithTag(com.virlin.app.ui.components.ProjectIconTestTag, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Project management"))
        compose.onNodeWithText("Project management").assertIsDisplayed()
        compose.onNodeWithText("Save changes").assertIsDisplayed()
    }

    @Test fun longCustomDialogCanScrollToActionsWithoutClipping() {
        var saved = false
        compose.setContent {
            SafeDialog(onDismissRequest = {}, scrollContent = true) {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        repeat(30) { Text("Optional setting $it", Modifier.padding(8.dp)) }
                        Button(onClick = { saved = true }) { Text("Confirm options") }
                    }
                }
            }
        }
        compose.onNodeWithText("Confirm options").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp")
    fun landscapeSettingsActionsRemainReachable() {
        val now = Instant.parse("2026-10-05T00:00:00Z")
        compose.setContent {
            ProjectSettingsDialog(Project("landscape", "Project", createdAt = now, updatedAt = now), emptyList(),
                onDismiss = {}, onRename = {}, onEditIcon = {}, onExecutionDefault = {},
                onRestoreTask = {}, onCompleteProject = {})
        }
        compose.onNodeWithText("Save changes").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
