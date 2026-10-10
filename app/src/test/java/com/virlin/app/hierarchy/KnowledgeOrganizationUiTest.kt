package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
class KnowledgeOrganizationUiTest {
    @get:Rule val compose = createComposeRule()
    private var moved: Triple<String, String?, String?>? = null
    private var deleted = ""
    private var duplicated = ""
    private var opened = ""
    private fun show() {
        val actions = KnowledgeActions({}, { opened = it.id }, { id, ws, task -> moved = Triple(id, ws, task) },
            { duplicated = it }, {}, { deleted = it }, {}, {}, { _, _ -> }, { _, _, _ -> }, onMoveMany = { _, _, _ -> })
        compose.setContent {
            Column(Modifier.fillMaxSize()) {
                VirlinProjectKnowledgeLibrary("p", listOf(KnowledgeItem("note", "p", KnowledgeType.DOCUMENT,
                    "Release notes", updatedAtMillis = 1, kindLabel = "Note")),
                    projectTasks = listOf(KnowledgeProjectTask("real", "p", "Executable task", "Todo", 1)),
                    workstreams = listOf(KnowledgeWorkstream("w", "Build", "1 of 2 complete")),
                    tasks = listOf(KnowledgeTask("task", "Prepare release", "w"),
                        KnowledgeTask("sub", "Version highlights", "w", "task")),
                    actions = actions, modifier = Modifier.weight(1f), imagePreview = { _, _ -> })
                Text("Projects footer", Modifier.fillMaxWidth().height(64.dp).testTag("test_footer"))
            }
        }
    }
    private fun menu() = compose.onNodeWithContentDescription("Options for Release notes").performClick()
    @Test fun galleryContainsKnowledgeOnlyAndMenuRoutesCorrectItem() {
        show()
        compose.onNodeWithText("Executable task").assertDoesNotExist()
        menu()
        compose.onNodeWithText("Open task").assertDoesNotExist()
        compose.onNodeWithText("Edit tags").assertDoesNotExist()
        compose.onNodeWithText("Duplicate").performClick()
        compose.runOnIdle { assertEquals("note", duplicated) }
        menu()
        compose.onNodeWithText("Open knowledge").performClick()
        compose.runOnIdle { assertEquals("note", opened) }
    }
    @Test fun deepSearchSelectsSubtaskOnlyAfterExplicitConfirmationAndFooterRemainsVisible() {
        show(); menu()
        compose.onNodeWithText("Move").performClick()
        compose.onNodeWithTag("knowledge_destination_search").performTextInput("Version")
        compose.onNodeWithTag("knowledge_destination_sub").performClick()
        compose.runOnIdle { assertNull(moved) }
        val overlay = compose.onNodeWithTag("knowledge_overlay").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("test_footer").fetchSemanticsNode().boundsInRoot
        assertTrue(overlay.bottom <= footer.top)
        compose.onAllNodesWithText("Move knowledge").assertCountEquals(2)
        compose.onAllNodesWithText("Move knowledge").onLast().performClick()
        compose.runOnIdle { assertEquals(Triple("note", "w", "sub"), moved) }
    }
    @Test fun deleteRequiresConfirmationAndCancelDoesNothing() {
        show(); menu()
        compose.onNodeWithText("Delete knowledge").performClick()
        compose.runOnIdle { assertEquals("", deleted) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("", deleted) }
        menu(); compose.onNodeWithText("Delete knowledge").performClick()
        compose.onNodeWithText("Delete knowledge?").assertExists()
        compose.onNodeWithText("Delete knowledge").performClick()
        compose.runOnIdle { assertEquals("note", deleted) }
    }
}
