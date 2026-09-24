package com.virlin.app.project

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.rule.GrantPermissionRule
import com.virlin.app.MainActivity
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.ui.components.ProjectIconEditorAutoTestTag
import com.virlin.app.ui.components.ProjectIconEditorTestTag
import com.virlin.app.ui.components.projectIconBuiltInTag
import com.virlin.app.ui.components.projectIconChoiceTag
import com.virlin.app.ui.hierarchy.ProjectDetailIconTag
import com.virlin.app.ui.hierarchy.ProjectDetailTag
import com.virlin.app.ui.navigation.RootDestination
import com.virlin.app.ui.navigation.bottomNavItemTag
import com.virlin.app.ui.screens.FocusContextTag
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The user journey: Streams → Psychology → tap the project icon → editor → Brain → Streams row and
 * Needs You reflect it → Use automatic → the deterministic default returns. Real navigation, real
 * repository, real `VirlinActions`; no icon state anywhere but the Project.
 */
class ProjectIconEditorUiTest {

    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) { composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8) }
        composeRule.mainClock.advanceTimeByFrame()
    }
    private fun tag(t: String) = composeRule.onNodeWithTag(t, useUnmergedTree = true)
    private fun touch(t: String, after: Long = 700) { runCatching { tag(t).performScrollTo() }.onSuccess { pump(300) }; tag(t).performTouchInput { click() }; pump(after) }
    private fun rowShows(rowTag: String, iconId: String) =
        composeRule.onNode(hasTestTag(rowTag) and hasAnyDescendant(hasTestTag(projectIconBuiltInTag(iconId))), useUnmergedTree = true).assertExists()

    @Test fun detail_editor_streams_needsYou_journey() {
        pump(800); tag(FocusContextTag).assertIsDisplayed()
        // Streams: project rows carry the automatic identity (K / A)
        tag(bottomNavItemTag(RootDestination.STREAMS)).performClick(); pump(700)
        rowShows("project_row_p3", "brain")            // Psychology → automatic Brain
        rowShows("project_row_p1", "code")             // Virlin Development → automatic Code

        // Project Detail shows the current icon (H) and the tap opens the editor (I)
        touch("project_row_p3", 900)
        tag(ProjectDetailTag).assertIsDisplayed()
        composeRule.onNode(hasTestTag(ProjectDetailIconTag) and hasAnyDescendant(hasTestTag(projectIconBuiltInTag("brain"))), useUnmergedTree = true).assertExists()
        touch(ProjectDetailIconTag, 900)
        tag(ProjectIconEditorTestTag).assertIsDisplayed()

        // Built-in selection applies immediately (J) and persists in the repository (B)
        touch(projectIconChoiceTag("rocket"), 900)
        tag(projectIconChoiceTag("rocket")).assertIsSelected()
        check(runBlocking { VirlinGraph.repository.getProject("p3") }!!.iconId == "rocket")
        composeRule.onNode(hasTestTag(ProjectDetailIconTag) and hasAnyDescendant(hasTestTag(projectIconBuiltInTag("rocket"))), useUnmergedTree = true).assertExists()

        // Reset to automatic (F) → Brain again
        touch(ProjectIconEditorAutoTestTag, 900)
        check(runBlocking { VirlinGraph.repository.getProject("p3") }!!.iconId == null)
        composeRule.onNode(hasTestTag(ProjectDetailIconTag) and hasAnyDescendant(hasTestTag(projectIconBuiltInTag("brain"))), useUnmergedTree = true).assertExists()

        // Choose Brain explicitly, close the sheet, verify Streams (K) and Now (L / M) through the project flow
        touch(projectIconChoiceTag("idea"), 900)
        Espresso.pressBack(); pump(900)                       // dismiss the sheet
        Espresso.pressBack(); pump(900)                       // back to Streams
        rowShows("project_row_p3", "idea")
        rowShows("project_row_p1", "code")                   // N: other projects untouched
        tag(bottomNavItemTag(RootDestination.NOW)).performClick(); pump(900)
        // p1 streams in Needs You (s3 + any due) keep p1's identity; change p1 through the action layer → both update (M)
        runBlocking { VirlinGraph.actions.updateProject("p1", com.virlin.app.domain.action.ProjectUpdate(iconId = com.virlin.app.domain.action.Field.Set("rocket"))) }; pump(900)
        val p1NeedsYou = VirlinGraph.repository.streams.value.filter { it.projectId == "p1" && it.state == com.virlin.app.domain.model.WorkStreamState.CHECK }
        check(p1NeedsYou.isNotEmpty()) { "expected a p1 stream in Needs You" }
        p1NeedsYou.forEach { s ->
            tag("needs_you_timer_${s.id}").performScrollTo(); pump(300)
            composeRule.onNode(hasTestTag(projectIconBuiltInTag("rocket")), useUnmergedTree = true)  // at least one rocket present
            rowShowsCard(s.id, "rocket")
        }
    }

    private fun rowShowsCard(streamId: String, iconId: String) =
        composeRule.onNode(hasTestTag("needs_you_card_$streamId") and hasAnyDescendant(hasTestTag(projectIconBuiltInTag(iconId))), useUnmergedTree = true).assertExists()
}
