package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.model.Project
import com.virlin.app.ui.components.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ProjectIconFooterUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun iconPickerStaysAboveFooterAndCallbacksRemainConnected() {
        var choice: String? = null
        var dismissed = false
        val now = Instant.parse("2026-10-08T00:00:00Z")
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    ProjectIconEditorSheet(Project("p", "Project", createdAt = now, updatedAt = now),
                        onSelectBuiltIn = { choice = it }, onCustomImported = {}, onRemoveCustom = {},
                        onUseAuto = {}, onDismiss = { dismissed = true }, keepFooterVisible = true)
                }
                Text("Projects footer", modifier = Modifier.fillMaxWidth().height(70.dp))
            }
        }
        compose.onNodeWithText("Projects footer").assertIsDisplayed()
        val popup = compose.onNodeWithTag(ProjectIconEditorTestTag).fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithText("Projects footer").fetchSemanticsNode().boundsInRoot
        assertTrue(popup.bottom <= footer.top)
        compose.onNodeWithTag(projectIconChoiceTag("code")).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("code", choice) }
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
