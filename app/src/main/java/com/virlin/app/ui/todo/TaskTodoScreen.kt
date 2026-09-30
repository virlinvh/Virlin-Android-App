package com.virlin.app.ui.todo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.model.ClearedSteps
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

const val TaskTodoRoute = "task_todo/{id}"
fun taskTodo(id: String) = "task_todo/$id"

/** What the header says about persistence, from real write outcomes only. */
private enum class TodoSave { Idle, Saving, Saved, Failed }

/**
 * A task's To-do list, as its own page.
 *
 * The rows ARE the task's `task_steps` - the same records the task detail screen shows - read as
 * a reactive flow and written through the canonical step actions. Nothing here invents a record:
 * no new Task, no subtask, no map node, no capture.
 *
 * The task id comes from the route and every callback resolves against it at the moment it runs,
 * so a callback can never act on a task the user has since left.
 */
@Composable
fun TaskTodoScreen(
    taskId: String?,
    navController: NavHostController,
    footer: @Composable () -> Unit = {},
) {
    // The app has no single app-wide host; the map screen owns one the same way, so this page
    // owns its own and draws it above the shared footer.
    val snackbar = remember { SnackbarHostState() }
    val repository = VirlinGraph.repository
    val tasks by repository.tasks.collectAsState()
    val allSteps by repository.taskSteps.collectAsState()
    val scope = rememberCoroutineScope()

    val task = remember(tasks, taskId) { tasks.firstOrNull { it.id == taskId } }
    if (taskId == null || task == null) {
        Box(Modifier.fillMaxSize()) {
            Text("That task no longer exists.", Modifier.padding(24.dp), color = Color(0xFF647087))
        }
        return
    }

    // This task's steps only, in their persisted order. Re-derived on every emission, so the
    // list on screen is always what the database says.
    val items = remember(allSteps, taskId) {
        allSteps.filter { it.taskId == taskId }
            .sortedWith(compareBy({ it.order }, { it.id }))
            .map { TodoItemUi(id = it.id, text = it.text, done = it.done, order = it.order) }
    }

    var save by remember(taskId) { mutableStateOf(TodoSave.Idle) }

    /** Runs one canonical action and lets the header tell the truth about the outcome. */
    fun write(block: suspend () -> ActionResult<*>) {
        scope.launch {
            save = TodoSave.Saving
            save = if (block() is ActionResult.Success) TodoSave.Saved else TodoSave.Failed
        }
    }

    /** A refusal the user should read, shown on the app's own snackbar. */
    fun complain(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, withDismissAction = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
    VirlinTodoScreen(
        taskId = taskId,
        taskTitle = task.title,
        // `task_steps` has no list-title column, so the heading is derived from the task and
        // Rename is inert by design rather than pretending to persist something.
        listTitle = task.title,
        saveLabel = when (save) {
            TodoSave.Idle -> "No changes"
            TodoSave.Saving -> "Saving…"
            TodoSave.Saved -> "Saved"
            TodoSave.Failed -> "Not saved"
        },
        items = items,
        footer = footer,
        actions = TodoActions(
            onBack = { navController.popBackStack() },
            // There is nowhere to store a list title, so this does nothing and the page hides it.
            onRenameList = { },
            // The draft is cleared only when the write is accepted, so a refused add keeps the
            // text the user typed. Blocking here is what lets the field report acceptance.
            onAdd = { text ->
                val clean = text.trim()
                if (clean.isEmpty()) false else {
                    save = TodoSave.Saving
                    val result = runBlocking { VirlinGraph.actions.addStep(taskId, clean) }
                    val ok = result is ActionResult.Success
                    save = if (ok) TodoSave.Saved else TodoSave.Failed
                    if (!ok) complain("That to-do could not be added.")
                    ok
                }
            },
            onEdit = { id, text ->
                if (text.isBlank()) complain("A to-do needs some text.")
                else write { VirlinGraph.actions.editStep(id, text) }
            },
            // The explicit desired state, never a flip of whatever the row last rendered.
            onToggle = { id, done -> write { VirlinGraph.actions.setStepDone(id, done) } },
            // One MOVE against a stable anchor; null means the end of the list.
            onMoveBefore = { sourceId, beforeId ->
                write { VirlinGraph.actions.moveStepBefore(sourceId, beforeId) }
            },
            onDelete = { id ->
                val removed = allSteps.firstOrNull { it.id == id }
                scope.launch {
                    save = TodoSave.Saving
                    val result = VirlinGraph.actions.deleteStep(id)
                    save = if (result is ActionResult.Success) TodoSave.Saved else TodoSave.Failed
                    if (result is ActionResult.Success && removed != null) {
                        // A single delete is reversible by the same restore path as a clear.
                        val survivors = repository.taskSteps.value
                            .filter { it.taskId == taskId }
                            .sortedWith(compareBy({ it.order }, { it.id })).map { it.id }
                        offerUndo(
                            snackbar, scope, ClearedSteps(taskId, listOf(removed), survivors),
                            "Deleted \"" + removed.text + "\"",
                        ) { onFail -> complain(onFail) }
                    }
                }
            },
            onClearCompleted = {
                scope.launch {
                    save = TodoSave.Saving
                    when (val result = VirlinGraph.actions.clearCompletedSteps(taskId)) {
                        is ActionResult.Success -> {
                            save = TodoSave.Saved
                            val cleared = result.value
                            if (cleared.removed.isNotEmpty()) {
                                offerUndo(
                                    snackbar, scope, cleared,
                                    "Cleared " + cleared.removed.size + " completed",
                                ) { onFail -> complain(onFail) }
                            }
                        }
                        else -> {
                            save = TodoSave.Failed
                            complain("Those completed items could not be cleared.")
                        }
                    }
                }
            },
            // Undo is offered on the snackbar that belongs to the operation, so there is no
            // separate always-present Undo that could apply to something older.
            onUndo = { },
        ),
    )
        androidx.compose.material3.SnackbarHost(
            snackbar,
            Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(12.dp),
        )
    }
}

/**
 * Shows one Undo for [cleared] and performs it if taken.
 *
 * The previous snackbar is dismissed first, so the visible Undo always belongs to the newest
 * operation; and because the restore is keyed to the task the clear happened on, one task's Undo
 * can never land on another's list.
 */
private fun offerUndo(
    snackbar: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    cleared: ClearedSteps,
    message: String,
    onRefused: (String) -> Unit,
) {
    scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        val outcome = snackbar.showSnackbar(
            message = message, actionLabel = "Undo",
            withDismissAction = true, duration = SnackbarDuration.Long,
        )
        if (outcome == SnackbarResult.ActionPerformed) {
            when (VirlinGraph.actions.restoreSteps(cleared)) {
                is ActionResult.Success -> Unit
                else -> onRefused("This list changed, so it could not be put back exactly.")
            }
        }
    }
}
