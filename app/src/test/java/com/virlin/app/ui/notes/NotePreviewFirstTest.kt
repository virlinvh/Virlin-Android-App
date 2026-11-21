package com.virlin.app.ui.notes

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A note opened from a Page shows its preview first; Back from the preview is the editor. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class NotePreviewFirstTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController

    @Test fun onlyNoteEditorsGetAPreview() {
        assertEquals(notesReaderRoute("task/a b", "Plan & notes"),
            notePreviewRouteFor(notesRoute("task/a b", "Plan & notes")))
        assertEquals(notesReaderRoute(CaptureNoteBridge.owner("cap/1"), "Note"),
            notePreviewRouteFor(captureNotesRoute("cap/1")))
        listOf("knowledge_todo/c1", "prompt_editor/c1", "task_todo/t1", "file_viewer/c1", "notes").forEach {
            assertNull(it, notePreviewRouteFor(it))
        }
    }

    private fun host() = compose.setContent {
        nav = rememberNavController()
        NavHost(nav, startDestination = "page") {
            composable("page") { Text("The page") }
            composable(NOTES_ROUTE, arguments = listOf(
                navArgument("ownerKey") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            )) { entry ->
                NotesRoute(nav, entry.arguments?.getString("ownerKey").orEmpty(), entry.arguments?.getString("title").orEmpty())
            }
            composable(NOTES_READER_ROUTE, arguments = listOf(
                navArgument("ownerKey") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            )) { entry ->
                NotesReaderRoute(nav, entry.arguments?.getString("ownerKey").orEmpty(), entry.arguments?.getString("title").orEmpty())
            }
            composable("capture_note/{captureId}") { entry ->
                CaptureNotesRoute(nav, entry.arguments?.getString("captureId").orEmpty())
            }
        }
    }

    @Test fun aPageNoteOpensInPreviewAndBackGoesToItsEditorThenThePage() {
        host()
        compose.runOnIdle { nav.openForReading(notesRoute("task-preview-first", "Plan")) }
        compose.onNodeWithTag("notes_preview").assertIsDisplayed()
        compose.onNodeWithTag("notes_tool_paragraph").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(NOTES_READER_ROUTE, nav.currentDestination?.route)
            assertEquals(NOTES_ROUTE, nav.previousBackStackEntry?.destination?.route)
        }
        compose.onNodeWithTag("notes_preview_close").performClick()
        compose.onNodeWithTag("notes_tool_paragraph").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close note").performClick()
        compose.onNodeWithText("The page").assertIsDisplayed()
    }

    @Test fun aKnowledgeNoteAlsoOpensInPreview() {
        host()
        compose.runOnIdle { nav.openForReading(captureNotesRoute("preview-capture")) }
        compose.onNodeWithTag("notes_preview").assertIsDisplayed()
        compose.onNodeWithTag("notes_preview_close").performClick()
        compose.onNodeWithTag("notes_tool_paragraph").assertIsDisplayed()
    }

    @Test fun anythingElseOpensExactlyAsBefore() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "page") {
                composable("page") { Text("The page") }
                composable("task_todo/{taskId}") { Text("The to-do") }
            }
        }
        compose.runOnIdle { nav.openForReading("task_todo/t1") }
        compose.onNodeWithText("The to-do").assertIsDisplayed()
        compose.runOnIdle { assertEquals("page", nav.previousBackStackEntry?.destination?.route) }
    }
}
