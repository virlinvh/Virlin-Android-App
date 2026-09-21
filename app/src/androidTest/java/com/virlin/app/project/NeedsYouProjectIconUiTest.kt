package com.virlin.app.project

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.projecticon.ProjectIconStore
import com.virlin.app.domain.model.Project
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.ui.components.ProjectIconFallbackTestTag
import com.virlin.app.ui.components.projectIconBuiltInTag
import com.virlin.app.ui.components.ProjectIconImageTestTag
import com.virlin.app.ui.screens.AttentionKind
import com.virlin.app.ui.screens.NeedsYouCard
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

/** Needs You cards resolve project identity through the project-icon system (never a stored copy). */
class NeedsYouProjectIconUiTest {

    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val t0 = Instant.parse("2026-09-21T09:00:00Z")

    @After fun cleanup() { ProjectIconStore.delete(context, "pN") }

    private fun display(id: String, title: String, subtitle: String, projectId: String) =
        MockData.streams.value.first().copy(id = id, title = title, subtitle = subtitle, projectId = projectId, state = StreamState.NEEDS_YOU)
    private fun project(id: String, title: String, iconPath: String? = null) = Project(id = id, title = title, createdAt = t0, updatedAt = t0, iconPath = iconPath)
    private fun importedIcon(): String {
        val f = File(context.cacheDir, "ny-icon.png")
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 40, 40)) }
            .let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return ProjectIconStore.importFromUri(context, "pN", Uri.fromFile(f))
    }

    @Test fun A_customProjectIcon_rendersOnCard() {
        val rel = importedIcon()
        composeRule.setContent { VirlinTheme { NeedsYouCard(display("s9", "Claude · Virlin", "Route structure decision", "pN"), 0, AttentionKind.CHECK_DUE, project = project("pN", "Virlin Development", rel)) } }
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag(ProjectIconImageTestTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(ProjectIconImageTestTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithTag(ProjectIconFallbackTestTag, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag("needs_you_primary_s9").assertIsDisplayed()          // Check action still there
    }

    @Test fun B_noCustomIcon_rendersProjectInitials() {
        composeRule.setContent { VirlinTheme { NeedsYouCard(display("s9", "Codex · MBA Research", "Methodology research", "p2"), 0, AttentionKind.CHECK_DUE, project = project("p2", "MBA Project")) } }
        composeRule.onNodeWithTag(projectIconBuiltInTag("business"), useUnmergedTree = true).assertIsDisplayed()   // automatic built-in
        composeRule.onAllNodesWithTag(ProjectIconImageTestTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun C_corruptReference_fallsBack() {
        ProjectIconStore.resolve(context, "pN/icon-bad.png").also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(40) { 3 }) }
        composeRule.setContent { VirlinTheme { NeedsYouCard(display("s9", "Antigravity", "Auth redirect", "pN"), 0, AttentionKind.CHECK_DUE, project = project("pN", "App Fix", "pN/icon-bad.png")) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(projectIconBuiltInTag("tools"), useUnmergedTree = true).assertIsDisplayed()      // corrupt → automatic icon, never broken
        composeRule.onAllNodesWithTag(ProjectIconImageTestTag, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun D_E_sameProjectSameIdentity_differentProjectsDiffer() {
        val p1 = project("p1", "Virlin Development"); val p2 = project("p2", "MBA Project")
        composeRule.setContent {
            VirlinTheme {
                androidx.compose.foundation.layout.Column {
                    NeedsYouCard(display("a", "Claude · Virlin", "One", "p1"), 0, AttentionKind.CHECK_DUE, project = p1)
                    NeedsYouCard(display("b", "Build · Release APK", "Two", "p1"), 1, AttentionKind.CHECK_DUE, project = p1)
                    NeedsYouCard(display("c", "Codex · MBA Research", "Three", "p2"), 2, AttentionKind.CHECK_DUE, project = p2)
                }
            }
        }
        composeRule.onAllNodesWithTag(projectIconBuiltInTag("code"), useUnmergedTree = true).assertCountEquals(2)       // same project → same identity
        composeRule.onAllNodesWithTag(projectIconBuiltInTag("business"), useUnmergedTree = true).assertCountEquals(1)   // different project → different identity
    }

    @Test fun F_G_urgencyAndTicks_doNotChangeIdentity() {
        val since = Instant.now().minusSeconds(590)                      // HIGH, about to become CRITICAL
        val now = mutableStateOf(Instant.now())
        composeRule.setContent { VirlinTheme { NeedsYouCard(display("s9", "Claude · Virlin", "Decision", "p1"), 0, AttentionKind.CHECK_DUE, waitingSince = since, now = now, project = project("p1", "Virlin Development")) } }
        composeRule.onAllNodesWithTag(projectIconBuiltInTag("code"), useUnmergedTree = true).assertCountEquals(1)
        repeat(15) { now.value = now.value.plusSeconds(1); composeRule.waitForIdle() }   // ticks across the 10:00 threshold
        composeRule.onAllNodesWithTag(projectIconBuiltInTag("code"), useUnmergedTree = true).assertCountEquals(1)
    }
}
