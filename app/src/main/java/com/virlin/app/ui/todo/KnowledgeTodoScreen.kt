package com.virlin.app.ui.todo

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reuse the checklist UI, but persist Knowledge checkboxes, never task_steps or map nodes. */
@Composable
fun KnowledgeTodoScreen(nav: NavController, projectId: String?, captureId: String?, workStreamId: String? = null) {
    val scope = rememberCoroutineScope()
    val projects by VirlinGraph.repository.projects.collectAsState()
    val captures by VirlinGraph.repository.captures.collectAsState()
    val id = rememberSaveable(projectId, captureId) { captureId ?: VirlinGraph.ids.newId("cap") }
    var blocks by remember(id) { mutableStateOf<List<NoteBlock>>(emptyList()) }
    var title by remember(id) { mutableStateOf("To-do list") }
    var persisted by remember(id) { mutableStateOf(false) }
    var loaded by remember(id) { mutableStateOf(captureId == null) }
    var status by remember(id) { mutableStateOf("No changes") }
    var undo by remember(id) { mutableStateOf<List<NoteBlock>?>(null) }
    val mutex = remember(id) { Mutex() }
    val ownerId = captures.firstOrNull { it.id == id }?.projectId ?: projectId
    val project = projects.firstOrNull { it.id == ownerId }
    LaunchedEffect(id) {
        if (captureId != null) {
            when (val result = VirlinGraph.actions.getOrHydrateTextNote(id)) {
                is ActionResult.Success -> {
                    if (result.value.blocks.any { it.type != NoteBlockType.CHECKBOX }) {
                        status = "This is not a to-do list"
                    } else {
                        blocks = result.value.blocks
                        title = result.value.title ?: "To-do list"
                        persisted = true
                        loaded = true
                        status = "Saved"
                    }
                }
                else -> status = "This list is no longer available"
            }
        }
    }
    // Serialize read-modify-write operations: rapid toggles/reorders cannot overwrite each other.
    suspend fun change(newTitle: String? = null, transform: (List<NoteBlock>) -> List<NoteBlock>): Boolean = mutex.withLock {
        val next = transform(blocks.filter { it.plainText.isNotBlank() }).ifEmpty {
            // Retain the checklist shape when its last row is removed; hide this empty block.
            listOf(NoteBlock("$id-empty", NoteBlockType.CHECKBOX))
        }
        val nextTitle = newTitle ?: title
        status = "Saving…"
        val result = if (persisted) VirlinGraph.actions.saveTextNote(id, nextTitle, next)
            else VirlinGraph.actions.createTextNote(nextTitle, next,
                CaptureContext(projectId = ownerId, workStreamId = workStreamId), captureId = id)
        if (result is ActionResult.Success) {
            undo = blocks
            blocks = result.value.blocks
            title = result.value.title ?: "To-do list"
            persisted = true
            status = "Saved"
            true
        } else {
            status = "Not saved — please try again"
            false
        }
    }
    if (!loaded || project == null) {
        Text(if (project == null) "This project is no longer available" else status)
        return
    }
    VirlinTodoScreen(
        taskId = id, taskTitle = project.title, listTitle = title, saveLabel = status,
        items = blocks.filter { it.plainText.isNotBlank() }.mapIndexed { index, block -> TodoItemUi(block.id, block.plainText, block.checked, index) },
        palette = com.virlin.app.ui.hierarchy.rememberProjectPalette(ownerId),
        canRenameList = true, footer = {},
        actions = TodoActions(
            onBack = { nav.popBackStack() },
            onBulkImport = { names, complete -> scope.launch {
                val accepted = withContext(Dispatchers.IO) {
                    change { list -> list + names.map { name ->
                        NoteBlock(VirlinGraph.ids.newId("blk"), NoteBlockType.CHECKBOX, plainText = name)
                    } }
                }
                complete(accepted)
            } },
            onRenameList = { name -> if (name.isNotBlank()) scope.launch(Dispatchers.IO) { change(name) { it } } },
            onAdd = { false }, // This host uses the non-blocking callback below.
            onAddAsync = { text, complete -> scope.launch {
                val accepted = withContext(Dispatchers.IO) {
                    change { it + NoteBlock(VirlinGraph.ids.newId("blk"), NoteBlockType.CHECKBOX, plainText = text.trim()) }
                }
                complete(accepted)
            } },
            onEdit = { blockId, text -> if (text.isNotBlank()) scope.launch(Dispatchers.IO) {
                change { list -> list.map { if (it.id == blockId) it.copy(plainText = text) else it } }
            } },
            onToggle = { blockId, done -> scope.launch(Dispatchers.IO) {
                change { list -> list.map { if (it.id == blockId) it.copy(checked = done) else it } }
            } },
            onMoveBefore = { source, before -> scope.launch(Dispatchers.IO) {
                change { list ->
                    val moving = list.firstOrNull { it.id == source }
                    if (moving == null || before == source) list else list.filterNot { it.id == source }.toMutableList().also {
                        val index = it.indexOfFirst { item -> item.id == before }
                        it.add(if (index < 0) it.size else index, moving)
                    }
                }
            } },
            onDelete = { blockId -> scope.launch(Dispatchers.IO) { change { list -> list.filterNot { it.id == blockId } } } },
            onClearCompleted = { scope.launch(Dispatchers.IO) { change { list -> list.filterNot { it.checked } } } },
            onUndo = { undo?.let { previous -> scope.launch(Dispatchers.IO) { change { previous } } } },
        ),
    )
}
