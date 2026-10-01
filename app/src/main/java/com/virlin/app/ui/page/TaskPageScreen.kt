package com.virlin.app.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.TaskPageActions
import com.virlin.app.domain.capture.LinkDocumentCodec
import com.virlin.app.domain.model.*
import kotlinx.coroutines.launch

const val TaskPageRoute = "task_page/{taskId}"
fun taskPage(taskId: String) = "task_page/$taskId"

data class TaskPageRow(val block: TaskPageBlock, val label: String, val title: String, val preview: String)
data class TaskPageState(
    val task: Task? = null,
    val rows: List<TaskPageRow> = emptyList(),
    val destinations: List<Task> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
)

/** Label registry only. Row projection, icons, and open routing are separate guarded seams below. */
object TaskPageBlockRegistry {
    fun label(key: String) = when (key) {
        TaskPageTypeKeys.TODO -> "To-do"
        TaskPageTypeKeys.NOTE -> "Note"
        TaskPageTypeKeys.capture(CaptureType.PROMPT) -> "Prompt"
        TaskPageTypeKeys.capture(CaptureType.LINK) -> "Link"
        TaskPageTypeKeys.capture(CaptureType.FILE) -> "File"
        TaskPageTypeKeys.capture(CaptureType.VOICE) -> "Audio"
        TaskPageTypeKeys.capture(CaptureType.NOTE) -> "Text note"
        else -> "Content"
    }
}

class TaskPageViewModel(private val taskId: String) : ViewModel() {
    private val repository = VirlinGraph.repository
    private val actions = TaskPageActions(repository, VirlinGraph.clock, VirlinGraph.ids)
    var state by mutableStateOf(TaskPageState()); private set

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        runCatching {
            VirlinGraph.ensureReady()
            actions.loadAndReconcile(taskId)
            buildState()
        }.onFailure { state = state.copy(loading = false, message = it.message ?: "Could not open Page") }
    }

    private suspend fun buildState() {
        val task = repository.getTask(taskId)
        if (task == null) { state = TaskPageState(loading = false, message = "Task not found"); return }
        val blocks = repository.getTaskPageBlocks(taskId)
        val captures = repository.captures.value.associateBy { it.id }
        val steps = repository.getTaskSteps(taskId)
        val rows = blocks.map { block ->
            val capture = captures[block.contentId]
            when (block.typeKey) {
                TaskPageTypeKeys.TODO -> TaskPageRow(block, "To-do", "Checklist",
                    if (steps.isEmpty()) "No items yet" else "${steps.count { it.done }} of ${steps.size} complete · " + steps.take(2).joinToString(" · ") { it.text })
                TaskPageTypeKeys.NOTE -> TaskPageRow(block, "Note", "Notes", "Open the rich note document")
                else -> {
                    val label = TaskPageBlockRegistry.label(block.typeKey)
                    val title = capture?.title?.takeIf { it.isNotBlank() } ?: label
                    val preview = when (capture?.type) {
                        CaptureType.LINK -> LinkDocumentCodec.decode(capture.content).note.ifBlank { capture.sourceUrl.orEmpty() }
                        else -> capture?.content.orEmpty()
                    }.lineSequence().firstOrNull().orEmpty().ifBlank { "Content unavailable" }
                    TaskPageRow(block, label, title, preview)
                }
            }
        }
        val destinations = task.projectId?.let { repository.getTasksByProject(it) }.orEmpty().filter { it.id != task.id }
        state = TaskPageState(task, rows, destinations, loading = false)
    }

    fun moveBy(blockId: String, delta: Int) = viewModelScope.launch {
        val ids = state.rows.map { it.block.id }.toMutableList(); val from = ids.indexOf(blockId)
        val to = (from + delta).coerceIn(0, ids.lastIndex); if (from < 0 || from == to) return@launch
        val item = ids.removeAt(from); ids.add(to, item)
        if (actions.reorder(taskId, ids)) buildState()
    }
    fun transfer(blockId: String, destinationId: String, duplicate: Boolean) = viewModelScope.launch {
        val ok = if (duplicate) actions.duplicate(blockId, destinationId) else actions.move(blockId, destinationId)
        state = state.copy(message = if (ok) (if (duplicate) "Block duplicated" else "Block moved") else "This block cannot be ${if (duplicate) "duplicated" else "moved"} safely")
        buildState()
    }
    fun clearMessage() { state = state.copy(message = null) }

    /** Surfaces a notice through the Page's existing message dialog. */
    fun notify(text: String) { state = state.copy(message = text) }

    companion object { fun factory(id: String) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TaskPageViewModel(id) as T
    } }
}

@Composable
fun TaskPageScreen(taskId: String?, navController: NavController) {
    if (taskId == null) return
    val vm: TaskPageViewModel = viewModel(key = "task-page-$taskId", factory = TaskPageViewModel.factory(taskId))
    val s = vm.state
    var organize by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<Pair<TaskPageRow, Boolean>?>(null) }
    val green = Color(0xFF07865B); val ink = Color(0xFF17231E)
    Scaffold(containerColor = Color(0xFFFCFBF8), topBar = {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) { Text(s.task?.title ?: "Page", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = ink); Text("Page", color = Color.Gray) }
            TextButton({ organize = !organize }) { Text(if (organize) "Done" else "Organize", color = green, fontWeight = FontWeight.SemiBold) }
        }
    }) { padding ->
        when {
            s.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator(color = green) }
            s.rows.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(36.dp), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.Description, null, tint = green, modifier = Modifier.size(44.dp)); Spacer(Modifier.height(14.dp)); Text("This Page is ready", fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("Items added from this task's + menu will appear here.", color = Color.Gray) }
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Everything attached to this task", color = Color.Gray, fontSize = 14.sp); Spacer(Modifier.height(2.dp)) }
                items(s.rows, key = { it.block.id }) { row ->
                    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, tonalElevation = 1.dp) {
                        Row(Modifier.fillMaxWidth().clickable { openBlock(navController, row, vm::notify) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconFor(row.block.typeKey), null, tint = green, modifier = Modifier.size(25.dp)); Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) { Text(row.label.uppercase(), color = green, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(row.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1); Text(row.preview, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            if (!organize) IconButton({ openBlock(navController, row, vm::notify) }) { Icon(Icons.Default.Edit, "Edit ${row.label}", tint = Color.Gray) }
                            else Column { Row { IconButton({ vm.moveBy(row.block.id, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }; IconButton({ vm.moveBy(row.block.id, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") } }; Row { IconButton({ transfer = row to false }) { Icon(Icons.Default.DriveFileMove, "Move") }; IconButton({ transfer = row to true }, enabled = row.block.typeKey.startsWith("capture.")) { Icon(Icons.Default.ContentCopy, "Duplicate") } } }
                        }
                    }
                }
            }
        }
    }
    s.message?.let { AlertDialog(onDismissRequest = vm::clearMessage, confirmButton = { TextButton(vm::clearMessage) { Text("OK") } }, text = { Text(it) }) }
    transfer?.let { (row, duplicate) -> AlertDialog(onDismissRequest = { transfer = null }, title = { Text(if (duplicate) "Duplicate block" else "Move block") }, text = { Column { if (s.destinations.isEmpty()) Text("No other task is available in this project.") else s.destinations.forEach { task -> Text(task.title, Modifier.fillMaxWidth().clickable { transfer = null; vm.transfer(row.block.id, task.id, duplicate) }.padding(vertical = 14.dp), fontWeight = FontWeight.Medium) } } }, confirmButton = {}, dismissButton = { TextButton({ transfer = null }) { Text("Cancel") } }) }
}

private fun iconFor(key: String) = when (key) { TaskPageTypeKeys.TODO -> Icons.Default.CheckBox; TaskPageTypeKeys.NOTE -> Icons.Default.Description; TaskPageTypeKeys.capture(CaptureType.LINK) -> Icons.Default.Link; TaskPageTypeKeys.capture(CaptureType.PROMPT) -> Icons.Default.Assignment; else -> Icons.Default.InsertDriveFile }

/**
 * Opens a block in its own canonical editor, always by stable `contentId`.
 *
 * OWNERSHIP: the Audio lane owns this routing seam. The `capture.voice` and `capture.file` cases
 * were added here to fix a defect where both block types rendered and were labelled correctly but
 * their taps did nothing at all - the `when` had no branch and no `else`. Both use the route
 * builders their own features already publish (`voiceEditorRoute`, `fileViewerRoute`); no route is
 * invented here. The PDF lane should reuse the `capture.file` case rather than editing this block.
 *
 * An unknown `typeKey` now reports through the Page's existing message dialog instead of failing
 * silently, which keeps the open-string type-key contract forward-compatible.
 */
private fun openBlock(nav: NavController, row: TaskPageRow, onUnsupported: (String) -> Unit) {
    when (row.block.typeKey) {
        TaskPageTypeKeys.TODO -> nav.navigate(com.virlin.app.ui.todo.taskTodo(row.block.taskId))
        TaskPageTypeKeys.NOTE -> nav.navigate(com.virlin.app.ui.notes.notesForTask(row.block.taskId, row.title))
        TaskPageTypeKeys.capture(CaptureType.PROMPT) -> nav.navigate(com.virlin.app.ui.prompt.promptEditorRoute(row.block.contentId))
        TaskPageTypeKeys.capture(CaptureType.LINK) -> nav.navigate(com.virlin.app.ui.link.linkEditorRoute(row.block.contentId))
        TaskPageTypeKeys.capture(CaptureType.VOICE) -> nav.navigate(com.virlin.app.ui.voice.voiceEditorRoute(row.block.contentId))
        TaskPageTypeKeys.capture(CaptureType.FILE) -> nav.navigate(com.virlin.app.ui.file.fileViewerRoute(row.block.contentId))
        else -> onUnsupported("This build cannot open a ${row.label} block yet.")
    }
}
