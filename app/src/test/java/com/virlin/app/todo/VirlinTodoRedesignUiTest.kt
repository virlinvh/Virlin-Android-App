package com.virlin.app.todo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.todo.TodoActions
import com.virlin.app.ui.todo.TodoItemUi
import com.virlin.app.ui.todo.VirlinTodoScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class VirlinTodoRedesignUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun filtersUseExistingRowsAndDetailsRouteToCanonicalActions() {
        val calls = mutableListOf<String>()
        val rows = mutableStateOf(listOf(
            TodoItemUi("one", "Test camera", false, 0),
            TodoItemUi("two", "Install APK", true, 1),
        ))
        compose.setContent {
            MaterialTheme {
                VirlinTodoScreen("task", "Pixel 8 Validation", "Pixel 8 Validation", "Saved",
                    rows.value, TodoActions(
                        onBack = {}, onRenameList = {}, onAdd = { true },
                        onEdit = { id, text -> calls += "edit:$id:$text" },
                        onToggle = { id, done -> calls += "toggle:$id:$done" },
                        onMoveBefore = { _, _ -> }, onDelete = { calls += "delete:$it" },
                        onClearCompleted = {}, onUndo = {},
                    ), footer = {})
            }
        }
        compose.onNodeWithText("To do 1").performClick()
        compose.onNodeWithText("Install APK").assertDoesNotExist()
        compose.onNodeWithText("Test camera").assertExists()
        compose.onNodeWithContentDescription("Open Test camera").performClick()
        compose.onNodeWithText("To-do details").assertExists()
        compose.onNodeWithText("Mark complete").performClick()
        compose.runOnIdle { assertEquals(listOf("toggle:one:true"), calls) }
        compose.onNodeWithTag("todo_check_one").assertExists()
    }

    @Test fun emptyStateRetainsTheExistingAddCommand() {
        val added = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                VirlinTodoScreen("task", "Pixel 8 Validation", "Pixel 8 Validation", "No changes",
                    emptyList(), TodoActions(
                        onBack = {}, onRenameList = {}, onAdd = { added += it; true },
                        onEdit = { _, _ -> }, onToggle = { _, _ -> },
                        onMoveBefore = { _, _ -> }, onDelete = {},
                        onClearCompleted = {}, onUndo = {},
                    ), footer = {})
            }
        }
        compose.onNodeWithText("Your list starts here").assertExists()
        compose.onNodeWithText("Add a to-do").performTextReplacement("Check camera")
        compose.onNodeWithTag("todo_add").performClick()
        compose.runOnIdle { assertEquals(listOf("Check camera"), added) }
    }

    @Test fun bulkImportIsFirstMenuActionAndUsesPreview() {
        var imported = emptyList<String>()
        compose.setContent {
            MaterialTheme {
                VirlinTodoScreen("task", "Image", "Image", "Saved", emptyList(),
                    TodoActions(onBack = {}, onRenameList = {}, onAdd = { true },
                        onEdit = { _, _ -> }, onToggle = { _, _ -> }, onMoveBefore = { _, _ -> },
                        onDelete = {}, onClearCompleted = {}, onUndo = {},
                        onBulkImport = { names, complete -> imported = names; complete(true) }),
                    footer = {})
            }
        }
        compose.onNodeWithContentDescription("Checklist options").performClick()
        compose.onNodeWithText("Bulk Import").performClick()
        compose.onNodeWithTag("todo_bulk_import_dialog").assertExists()
        compose.onNodeWithText("JSON").performTextInput("""{"version":2,"items":[{"name":"A"},{"name":"B"}]}""")
        compose.onNodeWithText("Preview", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Add 2 items").performClick()
        compose.runOnIdle { assertEquals(listOf("A", "B"), imported) }
    }
}
