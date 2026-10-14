package com.virlin.app.hierarchy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.KnowledgeWorkstreamPicker
import com.virlin.app.ui.hierarchy.KnowledgeWorkstream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp")
class KnowledgeWorkstreamPickerUiTest {
    @get:Rule val compose = createComposeRule()
    private val streams = (0..24).map { KnowledgeWorkstream("w$it", "Phase $it — Application Foundation") }
    @Test fun longListScrollsWithoutHidingCloseAndFooter() {
        var chosen: String? = null
        compose.setContent { MaterialTheme { KnowledgeWorkstreamPicker(streams, null, {}, { chosen = it }) } }
        compose.onNodeWithTag("workstream_picker_list").performScrollToNode(hasText(streams.last().title))
        compose.onNodeWithContentDescription("Close workstream filter").assertIsDisplayed()
        compose.onNodeWithText("Tap a workstream to filter").assertIsDisplayed()
        compose.onNodeWithText(streams.last().title).performClick()
        compose.runOnIdle { assertEquals("w24", chosen) }
    }
    @Test fun searchFiltersWithoutChangingSelectionAndCloseWorks() {
        var dismissed = false
        compose.setContent { MaterialTheme { KnowledgeWorkstreamPicker(streams, "w0", { dismissed = true }, {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("Phase 24")
        compose.onNodeWithText(streams.last().title).assertIsDisplayed()
        compose.onNodeWithText(streams.first().title).assertDoesNotExist()
        compose.onNodeWithContentDescription("Close workstream filter").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
