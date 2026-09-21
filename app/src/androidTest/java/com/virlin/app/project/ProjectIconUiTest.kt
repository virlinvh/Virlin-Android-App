package com.virlin.app.project

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.projecticon.ProjectIconStore
import com.virlin.app.ui.components.ProjectIcon
import com.virlin.app.ui.components.ProjectIconChooseTestTag
import com.virlin.app.ui.components.ProjectIconFallbackTestTag
import com.virlin.app.ui.components.projectIconBuiltInTag
import com.virlin.app.ui.components.ProjectIconImageTestTag
import com.virlin.app.ui.components.ProjectIconPicker
import com.virlin.app.ui.components.ProjectIconRemoveTestTag
import com.virlin.app.ui.components.ProjectIconTestTag
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/** ProjectIcon presentation: custom image, deterministic fallback, corrupt reference, picker states. */
class ProjectIconUiTest {

    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun cleanup() { ProjectIconStore.delete(context, "pU") }

    private fun importedIcon(): String {
        val f = File(context.cacheDir, "ui-icon.png")
        Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 90, 200)) }
            .let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return ProjectIconStore.importFromUri(context, "pU", Uri.fromFile(f))
    }

    @Test fun customIcon_rendersImage_notFallback() {
        val rel = importedIcon()
        composeRule.setContent { VirlinTheme { ProjectIcon(projectId = "pU", name = "Claude · Virlin", iconPath = rel) } }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(ProjectIconImageTestTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(ProjectIconImageTestTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithTag(ProjectIconFallbackTestTag, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag(ProjectIconTestTag).assertContentDescriptionContains("Claude · Virlin project icon")
    }

    @Test fun noIcon_rendersDeterministicAutomaticBuiltIn() {
        // Priority: custom → chosen built-in → AUTOMATIC built-in (initials only if no built-in resolves).
        composeRule.setContent { VirlinTheme { ProjectIcon(projectId = "p2", name = "MBA Project", iconPath = null) } }
        composeRule.onNodeWithTag(projectIconBuiltInTag("business"), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithTag(ProjectIconImageTestTag, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithTag(ProjectIconFallbackTestTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun chosenBuiltIn_beatsAutomatic() {
        composeRule.setContent { VirlinTheme { ProjectIcon(projectId = "p2", name = "MBA Project", iconPath = null, iconId = "rocket") } }
        composeRule.onNodeWithTag(projectIconBuiltInTag("rocket"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun corruptOrMissingReference_fallsBackSafely() {
        ProjectIconStore.resolve(context, "pU/icon-bad.png").also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(64) { 1 }) }
        composeRule.setContent { VirlinTheme { ProjectIcon(projectId = "pU", name = "App Fix", iconPath = "pU/icon-bad.png") } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(projectIconBuiltInTag("tools"), useUnmergedTree = true).assertIsDisplayed()   // safe fallback = automatic icon
        composeRule.onAllNodesWithTag(ProjectIconImageTestTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun picker_showsChooseWithoutIcon_andChangeRemoveWithIcon() {
        composeRule.setContent { VirlinTheme { ProjectIconPicker(projectId = "p1", name = "App Fix", iconPath = null, onIconChanged = {}) } }
        composeRule.onNodeWithTag(ProjectIconChooseTestTag).assertTextEquals("Choose image")
        composeRule.onAllNodesWithTag(ProjectIconRemoveTestTag).assertCountEquals(0)
        composeRule.onNodeWithText("Default icon").assertIsDisplayed()
    }

    @Test fun picker_withIcon_offersChangeAndRemove() {
        val rel = importedIcon()
        composeRule.setContent { VirlinTheme { ProjectIconPicker(projectId = "pU", name = "Claude · Virlin", iconPath = rel, onIconChanged = {}) } }
        composeRule.onNodeWithTag(ProjectIconChooseTestTag).assertTextEquals("Change image")
        composeRule.onNodeWithTag(ProjectIconRemoveTestTag).assertIsDisplayed()
        composeRule.onNodeWithText("Custom icon").assertIsDisplayed()
    }
}
