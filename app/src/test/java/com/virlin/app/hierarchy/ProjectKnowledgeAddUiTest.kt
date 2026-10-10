package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.material3.Text
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import com.virlin.app.ui.hierarchy.ProjectKnowledgeAdd
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class ProjectKnowledgeAddUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noteOpensExistingEditorInsteadOfGenericEntryDialog() {
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "add") {
                composable("add") { ProjectKnowledgeAdd("project", "My project", nav) {} }
                composable("knowledge_note/new/{projectId}") { Text("Project note editor: " + it.arguments?.getString("projectId")) }
            }
        }
        compose.onNodeWithText("Note").performClick()
        compose.onNodeWithText("Project note editor: project").assertIsDisplayed()
        compose.onNodeWithText("Title (optional)").assertDoesNotExist()
    }

    @Test fun audioSupportsRecordingAndImportWithoutSavingAnEmptyCapture() {
        compose.setContent { ProjectKnowledgeAdd("project", "My project", rememberNavController()) {} }
        compose.onNodeWithText("Audio").performClick()
        compose.onNodeWithText("Record audio").assertIsDisplayed()
        compose.onNodeWithText("Import audio").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Note").assertExists()
    }

    @Test fun todoPromptAndLinkHaveProjectScopedRoutes() {
        org.junit.Assert.assertEquals("knowledge_todo/new/project", com.virlin.app.ui.hierarchy.projectKnowledgeEditorRoute(com.virlin.app.ui.map.MapAddKind.TODO, "project"))
        org.junit.Assert.assertEquals("prompt_editor/new/project/project", com.virlin.app.ui.hierarchy.projectKnowledgeEditorRoute(com.virlin.app.ui.map.MapAddKind.TASK, "project"))
        org.junit.Assert.assertEquals("link_editor/new/project/project", com.virlin.app.ui.hierarchy.projectKnowledgeEditorRoute(com.virlin.app.ui.map.MapAddKind.LINK, "project"))
        org.junit.Assert.assertEquals("knowledge_note/new/project%2Ftwo", com.virlin.app.ui.hierarchy.projectKnowledgeEditorRoute(com.virlin.app.ui.map.MapAddKind.NOTE, "project/two"))
        org.junit.Assert.assertEquals("image_workspace/project/project", com.virlin.app.ui.hierarchy.projectKnowledgeEditorRoute(com.virlin.app.ui.map.MapAddKind.IMAGE, "project"))
        org.junit.Assert.assertEquals("pdf_workspace/new/project/project", com.virlin.app.ui.hierarchy.projectKnowledgeEditorRoute(com.virlin.app.ui.map.MapAddKind.PDF, "project"))
        org.junit.Assert.assertEquals("attachment_workspace/project/project", com.virlin.app.ui.hierarchy.projectKnowledgeEditorRoute(com.virlin.app.ui.map.MapAddKind.ATTACHMENT, "project"))
    }

    @Test fun knowledgeNotesUseMindMapToolbarAndPersistThroughItsEditor() {
        val now = java.time.Instant.EPOCH
        val repo = com.virlin.app.domain.repository.InMemoryWorkStreamRepository(seedProjects = listOf(
            com.virlin.app.domain.model.Project("p", "Project", createdAt = now, updatedAt = now)))
        val ids = com.virlin.app.domain.SequentialIdProvider()
        val actions = com.virlin.app.domain.action.DefaultVirlinActions(repo, com.virlin.app.domain.FakeClock(now), ids)
        val owner = com.virlin.app.domain.notedoc.CaptureNoteBridge.draftOwner("p", "ui-note")
        compose.setContent {
            val vm = androidx.compose.runtime.remember { com.virlin.app.ui.notes.NotesViewModel(owner, "New note", actions, ids) }
            com.virlin.app.ui.notes.NotesRoute(rememberNavController(), owner, "New note", vm)
        }
        compose.onNodeWithTag("notes_tool_paragraph").assertIsDisplayed()
        compose.onNodeWithTag("notes_tool_font").assertIsDisplayed()
        compose.onNodeWithTag("notes_add_block").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("Knowledge note body")
        compose.onNodeWithContentDescription("Close note").performClick()
        compose.waitUntil(5000) { repo.captures.value.any { it.id == "ui-note" } }
        org.junit.Assert.assertEquals("p", repo.captures.value.single().projectId)
        org.junit.Assert.assertTrue(repo.tasks.value.isEmpty())
    }

    @Test fun sharedTodoKeepsDraftUntilAsyncWriteSucceeds() {
        var completion: ((Boolean) -> Unit)? = null
        compose.setContent {
            com.virlin.app.ui.todo.VirlinTodoScreen("capture", "Project", "List", "No changes", emptyList(),
                actions = com.virlin.app.ui.todo.TodoActions(
                    onBack = {}, onRenameList = {}, onAdd = { error("Must not block") }, onEdit = { _, _ -> },
                    onToggle = { _, _ -> }, onMoveBefore = { _, _ -> }, onDelete = {}, onClearCompleted = {}, onUndo = {},
                    onAddAsync = { _, done -> completion = done }), footer = {})
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Keep my input")
        compose.onNodeWithTag("todo_add").performClick()
        compose.onNodeWithText("Keep my input").assertExists()
        compose.runOnIdle { completion!!(false) }
        compose.onNodeWithText("Keep my input").assertExists()
        compose.onNodeWithTag("todo_add").performClick()
        compose.runOnIdle { completion!!(true) }
        compose.onNodeWithText("Keep my input").assertDoesNotExist()
    }
}
