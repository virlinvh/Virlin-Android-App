package com.virlin.app.hierarchy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.domain.model.Project
import com.virlin.app.ui.hierarchy.DeleteProjectDialog
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class DeleteProjectDialogUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun requiresExactNameAndExplicitConfirmation() {
        var deleted = 0
        var dismissed = 0
        val project = Project("delete-ui", "Example", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        compose.setContent { MaterialTheme {
            DeleteProjectDialog(project, false, null, { dismissed++ }, { deleted++ })
        } }
        compose.onNodeWithText("Delete permanently").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Wrong")
        compose.onNodeWithText("Delete permanently").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("Example")
        compose.onNodeWithText("Delete permanently").assertIsEnabled().performClick()
        assertEquals(1, deleted)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
        assertEquals(1, deleted)
    }
}
