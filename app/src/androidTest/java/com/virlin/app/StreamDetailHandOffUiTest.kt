package com.virlin.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.screens.NowChooserTag
import com.virlin.app.ui.screens.StreamDetailScreen
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pass 5 §40: the legacy Stream Detail screen (still registered as `stream_detail/{id}`,
 * not linked from any current surface) must use the finalized Hand Off chooser — never a
 * silent fixed-duration hand-off. Rendered directly against the production graph.
 */
@RunWith(AndroidJUnit4::class)
class StreamDetailHandOffUiTest {

    /** The chooser asks for POST_NOTIFICATIONS contextually; keep the system dialog out of the test. */
    @get:Rule
    val permissions: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun handOff_opensSharedChooser_noSilentHandOff() {
        VirlinGraph.init(ApplicationProvider.getApplicationContext())
        VirlinGraph.start()
        runBlocking { VirlinGraph.actions.focusStream("s1") }
        val before = runBlocking { VirlinGraph.repository.getStream("s1")!! }
        check(before.state == WorkStreamState.FOCUS)
        composeRule.setContent { VirlinTheme { StreamDetailScreen("s1", rememberNavController()) } }
        composeRule.onNodeWithText("HAND OFF").performClick()
        composeRule.waitForIdle(); Thread.sleep(800); composeRule.waitForIdle()
        composeRule.onNodeWithTag(NowChooserTag, useUnmergedTree = true).assertIsDisplayed()
        listOf("handoff_1m", "handoff_2m", "handoff_5m", "handoff_10m", "handoff_15m", "chooser_custom", "chooser_no_check")
            .forEach { composeRule.onNodeWithTag(it, useUnmergedTree = true).assertIsDisplayed() }
        // Nothing happened yet — opening the chooser is not a hand-off
        val after = runBlocking { VirlinGraph.repository.getStream("s1")!! }
        check(after.state == WorkStreamState.FOCUS && after.checkAt == null) { "silent hand-off: $after" }
        composeRule.onNodeWithTag("chooser_cancel", useUnmergedTree = true).performClick()
    }
}
