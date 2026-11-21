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
import com.virlin.app.ui.map.preview.MapPreviewSaveTag
import com.virlin.app.ui.map.MapRenameDialogTag
import com.virlin.app.ui.map.MapRenameFieldTag
import com.virlin.app.ui.map.MapRenameSaveTag
import androidx.compose.ui.test.onNodeWithText
import com.virlin.app.ui.map.MapRenameCancelTag
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
        // The pencil opens the same Edit name dialog as the Organize strip.
        compose.onNodeWithTag(MapPreviewEditNameTag).assertIsDisplayed().performClick()
        compose.onNodeWithTag(MapRenameDialogTag).assertIsDisplayed()
        compose.onNodeWithTag(MapRenameFieldTag).performTextClearance()
        compose.onNodeWithTag(MapRenameSaveTag).assertIsNotEnabled()
        compose.onNodeWithTag(MapRenameFieldTag).performTextInput("Updated Review")
        compose.onNodeWithTag(MapRenameSaveTag).performClick()
        compose.onNodeWithTag(MapRenameDialogTag).assertDoesNotExist()
        compose.onNodeWithText("Updated Review").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, saved) }
        compose.onNodeWithTag(MapPreviewSaveTag).performClick()
        compose.runOnIdle { assertEquals("Updated Review", saved) }
    }

    @Test fun cancellingTheNameDialogKeepsTheOldName() {
        var saved: String? = null
        compose.setContent {
            MaterialTheme {
                MapNodePreviewPopup(
                    nodeTitle = "Design Review\n• first point",
                    draft = MapPreviewDraft("workstream"),
                    onDraftChange = {}, onPickImage = {}, onReplaceImage = {},
                    onRequestClear = {}, onCancel = {}, onSave = { saved = it },
                    imageLoader = { _, _ -> },
                )
            }
        }
        compose.onNodeWithTag(MapPreviewEditNameTag).performClick()
        compose.onNodeWithTag(MapRenameFieldTag).performTextInput("x")
        compose.onNodeWithTag(MapRenameCancelTag).performClick()
        compose.onNodeWithTag(MapRenameDialogTag).assertDoesNotExist()
        compose.onNodeWithTag(MapPreviewSaveTag).performClick()
        compose.runOnIdle { assertEquals("Design Review\n• first point", saved) }
    }
}
