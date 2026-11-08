package com.virlin.app.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.TaskPageActions
import com.virlin.app.domain.capture.LinkDocumentCodec
import com.virlin.app.domain.activity.NoteBlockText
import com.virlin.app.domain.model.*
import com.virlin.app.ui.hierarchy.MasterDocumentEntry
import com.virlin.app.ui.hierarchy.MasterDocumentItem
import com.virlin.app.ui.hierarchy.taskPageBody
import com.virlin.app.ui.hierarchy.previewableNoteBlocks
import com.virlin.app.ui.hierarchy.noteCardTitle
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.ui.hierarchy.rememberProjectPalette
import kotlinx.coroutines.launch

const val TaskPageRoute = "task_page/{taskId}"
fun taskPage(taskId: String) = "task_page/$taskId"

data class TaskPageRow(
    val block: TaskPageBlock,
    val label: String,
    val title: String,
    val preview: String,
    val attachmentKind: AttachmentKind? = null,
    val fullBody: String = preview,
    val mediaPath: String? = null,
    val noteBlocks: List<NoteDocBlock>? = null,
)
data class TaskPageState(
    val task: Task? = null,
    val rows: List<TaskPageRow> = emptyList(),
    val destinations: List<Task> = emptyList(),
    val branchSections: List<TaskPageSection> = emptyList(),
    /** Where an item can move: every task of this project (or stream, if it has none). */
    val projectTitle: String = "",
    val projectTasks: List<Task> = emptyList(),
    val projectStreams: List<WorkStream> = emptyList(),
    val loading: Boolean = true,
    val message: String? = null,
)

data class TaskPageSection(val task: Task, val rows: List<TaskPageRow>)

/** The root belongs to the branch; unrelated tasks, even in the same project, do not. */
internal fun taskPageBranchIds(tasks: List<Task>, rootId: String): List<String> =
    listOf(rootId) + TaskHierarchy.descendants(tasks, rootId).map { it.id }

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
        suspend fun rowsFor(ownerId: String): List<TaskPageRow> {
          val ownerBlocks = if (ownerId == taskId) blocks else repository.getTaskPageBlocks(ownerId)
          val steps = repository.getTaskSteps(ownerId)
          return ownerBlocks.filterNot { block ->
            block.typeKey == TaskPageTypeKeys.NOTE && TaskPageTypeKeys.isExtraNoteOwner(block.contentId) &&
                repository.getNoteDoc(block.contentId) == null
          }.map { block ->
            val capture = captures[block.contentId]
            when (block.typeKey) {
                TaskPageTypeKeys.TODO -> {
                    val body = taskPageBody(block.typeKey, ownerId, steps, null)
                    TaskPageRow(block, "To-do", "Checklist", body.lineSequence().first(), fullBody = body)
                }
                TaskPageTypeKeys.NOTE -> {
                    val doc = repository.getNoteDoc(block.contentId)
                    val body = taskPageBody(block.typeKey, ownerId, steps, doc)
                    TaskPageRow(block, "Note", noteCardTitle(doc), body.lineSequence().first(), fullBody = body,
                        noteBlocks = previewableNoteBlocks(doc))
                }
                else -> {
                    val label = TaskPageBlockRegistry.label(block.typeKey)
                    val title = capture?.title?.takeIf { it.isNotBlank() } ?: label
                    val attachment = if (capture?.type == CaptureType.FILE) {
                        repository.getAttachmentByCaptureId(capture.id)
                    } else null
                    val body = when (capture?.type) {
                        CaptureType.LINK -> listOfNotNull(capture.sourceUrl,
                            LinkDocumentCodec.decode(capture.content).note.takeIf { it.isNotBlank() })
                            .joinToString("\n")
                        CaptureType.PROMPT -> repository.getPromptByCaptureId(capture.id)?.let { prompt ->
                            listOfNotNull(prompt.sourceText.takeIf { it.isNotBlank() }
                                ?: NoteBlockText.render(prompt.blocks),
                                prompt.codeText?.takeIf { it.isNotBlank() }?.let { "Code\n$it" },
                                prompt.responseText?.takeIf { it.isNotBlank() }?.let { "Response\n$it" })
                                .joinToString("\n\n")
                        } ?: capture.content
                        CaptureType.NOTE -> repository.getNoteByCaptureId(capture.id)?.blocks
                            ?.let(NoteBlockText::render) ?: capture.content
                        else -> capture?.content.orEmpty()
                    }.ifBlank { attachment?.displayName ?: "Content unavailable" }
                    val typeLabel = when (attachment?.kind) {
                        AttachmentKind.PDF -> "PDF"
                        AttachmentKind.VIDEO -> "Video"
                        AttachmentKind.IMAGE -> "Image"
                        else -> label
                    }
                    TaskPageRow(block, typeLabel, title, body.lineSequence().first(),
                        attachment?.kind, body, attachment?.relativePath)
                }
            }
          }
        }
        val rows = rowsFor(taskId)
        val scopeTasks = repository.tasks.value.filter {
            it.projectId == task.projectId && it.workStreamId == task.workStreamId
        }
        val byId = scopeTasks.associateBy { it.id }
        val branchSections = taskPageBranchIds(scopeTasks, taskId).mapNotNull { id ->
            val owner = if (id == taskId) task else byId[id] ?: return@mapNotNull null
            TaskPageSection(owner, if (id == taskId) rows else rowsFor(id))
        }
        val projectTasks = task.projectId?.let { repository.getTasksByProject(it) }
            ?: repository.tasks.value.filter { it.projectId == null && it.workStreamId == task.workStreamId }
        val destinations = projectTasks.filter { it.id != task.id }
        val streams = repository.streams.value.filter { stream -> projectTasks.any { it.workStreamId == stream.id } }
        val projectTitle = task.projectId?.let { id -> repository.projects.value.firstOrNull { it.id == id }?.title }
            ?: streams.firstOrNull()?.title ?: "Project"
        state = TaskPageState(task, rows, destinations, branchSections, loading = false,
            projectTitle = projectTitle, projectTasks = projectTasks, projectStreams = streams)
    }

    /**
     * Moves one item to [destination]. A note moves with its whole document under a new key;
     * [onNoteMoved] then carries what is kept beside the note (flashcards, page setup) along.
     */
    fun moveTo(row: TaskPageRow, destination: Task, onNoteMoved: suspend (String, String) -> Unit) = viewModelScope.launch {
        val note = row.block.typeKey == TaskPageTypeKeys.NOTE
        var followUpFailed = false
        val ok = if (note) {
            val owner = runCatching { actions.moveNote(row.block.id, destination.id) }.getOrNull()
            if (owner != null) followUpFailed = runCatching { onNoteMoved(row.block.contentId, owner) }.isFailure
            owner != null
        } else runCatching { actions.move(row.block.id, destination.id) }.getOrDefault(false)
        val empty = note && !ok && runCatching { repository.getNoteDoc(row.block.contentId) }.getOrNull() == null
        buildState()
        state = state.copy(message = when {
            ok && followUpFailed -> "Moved to ${destination.title}. Its flashcards could not be moved along."
            ok -> "Moved to ${destination.title}"
            empty -> "This note is still empty. Write something in it first, then move it."
            else -> "This item cannot be moved safely"
        })
    }

    fun moveBy(blockId: String, delta: Int) = viewModelScope.launch {
        val ids = state.rows.map { it.block.id }.toMutableList(); val from = ids.indexOf(blockId)
        val to = (from + delta).coerceIn(0, ids.lastIndex); if (from < 0 || from == to) return@launch
        val item = ids.removeAt(from); ids.add(to, item)
        if (actions.reorder(taskId, ids)) buildState()
    }
    /** Copies the item in place, listed right after the original. */
    fun duplicateHere(blockId: String) = viewModelScope.launch {
        val ok = runCatching { actions.duplicateHere(blockId) }.getOrDefault(false)
        val reason = if (ok) null else state.rows.firstOrNull { it.block.id == blockId }
            ?.takeIf { it.block.typeKey == TaskPageTypeKeys.NOTE }
            ?.let { row ->
                val doc = runCatching { repository.getNoteDoc(row.block.contentId) }.getOrNull()
                when {
                    doc == null -> "This note is still empty. Write something in it first, then duplicate it."
                    doc.loadError != null -> "This note can't be read safely, so it was not copied."
                    else -> null
                }
            }
        buildState()
        if (!ok) state = state.copy(message = reason ?: "This item cannot be duplicated safely")
    }
    /** Runs only after the user typed the item's name in the delete warning. */
    fun delete(blockId: String) = viewModelScope.launch {
        val ok = runCatching { actions.delete(blockId) }.getOrDefault(false)
        buildState()
        if (!ok) state = state.copy(message = "This item cannot be deleted safely")
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
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val s = vm.state
    var organize by remember { mutableStateOf(false) }
    var acrossBranch by rememberSaveable(taskId) { mutableStateOf(false) }
    var moving by remember { mutableStateOf<TaskPageRow?>(null) }
    var deleting by remember { mutableStateOf<TaskPageRow?>(null) }
    val palette = rememberProjectPalette(s.task?.projectId)
    val green = palette?.let { Color(it.secondary) } ?: Color(0xFF07865B)
    val ink = palette?.let { Color(it.text) } ?: Color(0xFF17231E)
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.fillMaxSize()) {
    Scaffold(containerColor = Color(0xFFFCFBF8), topBar = {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(s.task?.title ?: "Page", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ink,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("Page", color = Color.Gray, fontSize = 13.sp, maxLines = 1)
            }
            TextButton({ organize = !organize }) { Text(if (organize) "Done" else "Organize", color = green, fontWeight = FontWeight.SemiBold) }
        }
    }) { padding ->
        when {
            s.loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator(color = green) }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)
                    .background(Color(0xFFE7F4ED), RoundedCornerShape(16.dp)).padding(4.dp)) {
                    listOf("Added here", "Across branch").forEachIndexed { index, title ->
                        val selected = acrossBranch == (index == 1)
                        Box(Modifier.weight(1f)
                            .background(if (selected) Color.White else Color.Transparent, RoundedCornerShape(13.dp))
                            .clickable { acrossBranch = index == 1 }.padding(vertical = 13.dp), Alignment.Center) {
                            Text(title, color = if (selected) green else ink, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                val sections = if (acrossBranch) s.branchSections else s.branchSections.filter { it.task.id == taskId }
                if (sections.all { it.rows.isEmpty() } && (!acrossBranch || sections.size == 1)) Box(Modifier.fillMaxSize().padding(28.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Description, null, tint = green, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(if (acrossBranch) "Nothing across this branch yet" else "This Page is ready",
                            fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(if (acrossBranch) "Items added to this task and its subtasks will appear here."
                             else "Items added from this task's + menu will appear here.", color = Color.Gray)
                    }
                } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                  sections.forEach { section ->
                    item("heading-${section.task.id}") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).then(if (section.task.id == taskId) Modifier
                            else Modifier.clickable { navController.navigate(taskPage(section.task.id)) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (section.task.id == taskId) "Added to ${section.task.title}"
                                 else "Subtask · ${section.task.title}", color = green,
                                 fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                 modifier = Modifier.weight(1f))
                            if (section.task.id != taskId) Icon(Icons.Default.ChevronRight,
                                "Open ${section.task.title} page", tint = green)
                        }
                    }
                    if (section.rows.isEmpty()) item("empty-${section.task.id}") {
                        Text("Nothing added here yet", color = Color.Gray, fontSize = 13.sp)
                    }
                    items(section.rows, key = { it.block.id }) { row ->
                    Column {
                        MasterDocumentItem(
                            MasterDocumentEntry(row.block.id, row.title, row.label, row.fullBody,
                                row.block.contentId, row.mediaPath,
                                row.attachmentKind == AttachmentKind.IMAGE, noteBlocks = row.noteBlocks),
                            ink, green, palette) { openBlock(navController, row, vm::notify) }
                        if (organize && row.block.taskId == taskId) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.End) {
                                IconButton({ vm.moveBy(row.block.id, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                IconButton({ vm.moveBy(row.block.id, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                // The checklist is the task's own steps: it does not move, copy or delete here.
                                val ownsContent = row.block.typeKey == TaskPageTypeKeys.NOTE ||
                                    row.block.typeKey.startsWith("capture.")
                                IconButton({ moving = row }, enabled = ownsContent) { Icon(Icons.Default.DriveFileMove, "Move") }
                                IconButton({ vm.duplicateHere(row.block.id) }, enabled = ownsContent) {
                                    Icon(Icons.Default.ContentCopy, "Duplicate")
                                }
                                IconButton({ deleting = row }, enabled = ownsContent) {
                                    Icon(Icons.Default.DeleteOutline, "Delete",
                                        tint = if (ownsContent) MaterialTheme.colorScheme.error
                                            else LocalContentColor.current)
                                }
                            }
                        }
                    }
                    }
                  }
                }
            }
        }
    }
    moving?.let { row ->
        s.task?.let { task ->
            PageMoveDestinationSheet(
                itemLabel = row.label, projectTitle = s.projectTitle, streams = s.projectStreams,
                tasks = s.projectTasks, currentTaskId = task.id, palette = palette,
                onDismiss = { moving = null },
                onMove = { destination ->
                    moving = null
                    vm.moveTo(row, destination) { from, to ->
                        com.virlin.app.data.flashcard.FlashcardStore.rekey(context, from, to)
                        val pages = com.virlin.app.ui.notes.NotePageSetupStore(context)
                        pages.load(from)?.let { pages.save(to, it); pages.setOn(to, pages.isOn(from)) }
                    }
                })
        }
    }
    }
    deleting?.let { row ->
        val note = row.block.typeKey == TaskPageTypeKeys.NOTE
        val kind = if (row.label.all { it.isUpperCase() }) row.label else row.label.lowercase()
        com.virlin.app.ui.hierarchy.DeleteByNameDialog(row.title, kind,
            warning = if (note) "Permanent · the note and everything in it"
                else "Archived · it can be restored from the Inbox",
            busy = false, error = null,
            onDismiss = { deleting = null },
            onDelete = { deleting = null; vm.delete(row.block.id) })
    }
    s.message?.let { AlertDialog(onDismissRequest = vm::clearMessage, confirmButton = { TextButton(vm::clearMessage) { Text("OK") } }, text = { Text(it) }) }
}

private fun iconFor(key: String) = when (key) { TaskPageTypeKeys.TODO -> Icons.Default.CheckBox; TaskPageTypeKeys.NOTE -> Icons.Default.Description; TaskPageTypeKeys.capture(CaptureType.LINK) -> Icons.Default.Link; TaskPageTypeKeys.capture(CaptureType.PROMPT) -> Icons.Default.Assignment; else -> Icons.Default.InsertDriveFile }

/**
 * Opens a block in its own canonical editor, always by stable `contentId`.
 *
 * `capture.file` is the shared attachment dispatch seam. It selects specialized PDF and Image
 * workspaces by persisted `AttachmentKind`; every other file continues to use the universal file
 * viewer. New file specializations must extend this single kind-based branch rather than infer a
 * destination from filenames or add a parallel Page contract.
 *
 * An unknown `typeKey` now reports through the Page's existing message dialog instead of failing
 * silently, which keeps the open-string type-key contract forward-compatible.
 */
private fun openBlock(nav: NavController, row: TaskPageRow, onUnsupported: (String) -> Unit) {
    when (row.block.typeKey) {
        TaskPageTypeKeys.TODO -> nav.navigate(com.virlin.app.ui.todo.taskTodo(row.block.taskId))
        TaskPageTypeKeys.NOTE -> nav.navigate(com.virlin.app.ui.notes.notesRoute(row.block.contentId, row.title))
        TaskPageTypeKeys.capture(CaptureType.PROMPT) -> nav.navigate(com.virlin.app.ui.prompt.promptEditorRoute(row.block.contentId))
        TaskPageTypeKeys.capture(CaptureType.LINK) -> nav.navigate(com.virlin.app.ui.link.linkEditorRoute(row.block.contentId))
        TaskPageTypeKeys.capture(CaptureType.VOICE) -> nav.navigate(com.virlin.app.ui.voice.voiceEditorRoute(row.block.contentId))
        TaskPageTypeKeys.capture(CaptureType.FILE) -> nav.navigate(
            when (row.attachmentKind) {
                AttachmentKind.PDF -> com.virlin.app.ui.pdf.pdfWorkspaceForCapture(row.block.contentId)
                AttachmentKind.IMAGE -> com.virlin.app.ui.image.imageWorkspaceForCapture(row.block.contentId)
                else -> com.virlin.app.ui.file.fileViewerRoute(row.block.contentId)
            }
        )
        else -> onUnsupported("This build cannot open a ${row.label} block yet.")
    }
}
