package com.virlin.app.mappreview

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.virlin.app.ui.map.preview.MapNodePreviewPopup
import com.virlin.app.ui.map.preview.MapPreviewDraft
import com.virlin.app.ui.map.preview.MapPreviewEditNameTag
import com.virlin.app.ui.map.preview.MapPreviewNameFieldTag
import com.virlin.app.ui.map.preview.MapPreviewSaveTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapPreviewNameUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun penBesideBranchNameEnablesEditingAndSaveUsesNewName() {
        var saved: String? = null
        compose.setContent {
            MaterialTheme {
                MapNodePreviewPopup(
                    nodeTitle = "Design Review",
                    draft = MapPreviewDraft("workstream"),
                    onDraftChange = {}, onPickImage = {}, onReplaceImage = {},
                    onRequestClear = {}, onCancel = {}, onSave = { saved = it },
                    imageLoader = { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag(MapPreviewEditNameTag).assertIsDisplayed().performClick()
        compose.onNodeWithTag(MapPreviewNameFieldTag).assertIsDisplayed().performTextClearance()
        compose.onNodeWithTag(MapPreviewSaveTag).assertIsNotEnabled()
        compose.onNodeWithTag(MapPreviewNameFieldTag).performTextInput("Updated Review")
        compose.onNodeWithTag(MapPreviewSaveTag).performClick()
        compose.runOnIdle { assertEquals("Updated Review", saved) }
    }
}
