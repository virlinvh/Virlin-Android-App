package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.progress.ProjectTaskUnits
import com.virlin.app.mock.MockData
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.ProgressResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxSize
import com.virlin.app.domain.activity.ActivityEntryKind
import com.virlin.app.domain.activity.NoteBlockShape
import com.virlin.app.domain.activity.ProjectActivityEntry
import com.virlin.app.domain.activity.NoteBlockText
import kotlinx.coroutines.launch
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.Project
import com.virlin.app.ui.theme.VirlinColors

const val ProjectDetailRoute = "project_detail/{id}"
const val WorkStreamDetailRoute = "workstream_detail/{id}?path={path}"
/** Project-scoped list of the very work items the project's progress is counted from. */
const val ProjectTaskIndexRoute = "project_task_index/{id}"
const val TaskDetailRoute = "task_detail/{id}"
fun projectDetail(id: String) = "project_detail/$id"
fun workStreamDetail(id: String) = "workstream_detail/$id"

/**
 * Opens a WorkStream already drilled down to [path] — the ancestor task ids from the stream
 * root to the task to show, by id. Navigation never resolves a task by title or by position.
 */
fun workStreamDetailAt(id: String, path: List<String>) =
    if (path.isEmpty()) workStreamDetail(id)
    else "workstream_detail/$id?path=" + path.joinToString(",")

fun projectTaskIndex(id: String) = "project_task_index/$id"
fun taskDetail(id: String) = "task_detail/$id"

internal fun taskHierarchyDestination(
    taskId: String?,
    tasks: List<Task>,
    streams: List<WorkStream>
): String? {
    val task = tasks.firstOrNull { it.id == taskId } ?: return null
    val byId = tasks.associateBy { it.id }
    val reversedLineage = mutableListOf<Task>()
    var cursor: Task? = task
    val visited = mutableSetOf<String>()
    while (cursor != null && visited.add(cursor.id)) {
        reversedLineage += cursor
        cursor = cursor.parentTaskId?.let(byId::get)
    }
    val lineage = reversedLineage.asReversed()
    val workStreamId = reversedLineage.firstNotNullOfOrNull { it.workStreamId }
    val projectId = reversedLineage.firstNotNullOfOrNull { it.projectId }
        ?: workStreamId?.let { id -> streams.firstOrNull { it.id == id }?.projectId }
    return when {
        workStreamId != null -> workStreamDetailAt(workStreamId, lineage.map { it.id })
        projectId != null -> projectTaskIndex(projectId)
        else -> null
    }
}

/**
 * Stable compatibility entry point for opening a task. The route name is retained so existing
 * callers, notifications and restored back stacks remain valid, but it no longer owns a second
 * task UI. WorkStream tasks open at their exact level in the green hierarchy; standalone tasks
 * open in their project's green task index.
 */
@Composable
fun TaskHierarchyRedirectScreen(
    taskId: String?,
    navController: NavController,
    vm: HierarchyViewModel = viewModel()
) {
    val snapshot by vm.snapshot.collectAsState()
    val task = snapshot.tasks.firstOrNull { it.id == taskId }
    val destination = remember(taskId, snapshot.tasks, snapshot.streams) {
        taskHierarchyDestination(taskId, snapshot.tasks, snapshot.streams)
    }
    LaunchedEffect(destination) {
        destination?.let { route ->
            navController.navigate(route) {
                popUpTo(TaskDetailRoute) { inclusive = true }
                launchSingleTop = true
            }
        }
    }
    if (task == null) Missing("Task")
    else if (destination == null) Missing("Task location")
    else Box(Modifier.fillMaxSize().background(Color.White))
}

const val ProjectDetailTag = "project_detail"
const val ProjectDetailIconTag = "project_detail_icon"
const val WorkStreamDetailTag = "workstream_detail"
const val StartNextTag = "start_next_task"

private val Hairline = VirlinColors.TextPrimary.copy(alpha = 0.08f)

// =====================================================================================
// PROJECT DETAIL
// =====================================================================================

@Composable
fun ProjectDetailScreen(projectId: String?, navController: NavController, vm: HierarchyViewModel = viewModel()) {
    val s by vm.snapshot.collectAsState()
    val captures by vm.captures.collectAsState()
    val project = s.projects.firstOrNull { it.id == projectId }
    if (project == null) { Missing("Project"); return }

    val streams = s.streams.filter { it.projectId == project.id }
    // Every task counted ONCE: a workstream's own tasks plus this project's standalone tasks.
    // Checklists and documents are not tasks and are not counted.
    val standalone = s.tasks.filter { it.projectId == project.id && it.workStreamId == null }
    val knowledge = captures.filter { it.projectId == project.id && it.status != CaptureStatus.ARCHIVED }

    var adding by remember { mutableStateOf(false) }
    var addingStream by remember { mutableStateOf(false) }
    var editingIcon by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var confirmComplete by remember { mutableStateOf(false) }
    var taskMenu by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (editingIcon) {
        com.virlin.app.ui.components.ProjectIconEditorSheet(
            project = project,
            onSelectBuiltIn = { vm.selectBuiltInIcon(context, project.id, it) },
            onCustomImported = { vm.setCustomIcon(project.id, it) },
            onRemoveCustom = { vm.removeCustomIcon(context, project.id) },
            onUseAuto = { vm.useAutoIcon(context, project.id) },
            onDismiss = { editingIcon = false }
        )
    }

    val ui = ProjectPageUi(
        id = project.id,
        title = project.title,
        description = project.description.orEmpty(),
        icon = { com.virlin.app.ui.components.ProjectIcon(project = project, size = 44.dp, decorative = true) },
        onEditIcon = { editingIcon = true },
        workstreams = streams.map { ws ->
            val progress = ProgressCalculator.ofWorkStream(s.tasks, ws.id)
            val structured = progress as? ProgressResult.Structured
            WorkstreamUi(
                id = ws.id,
                title = ws.title,
                completedTasks = structured?.completedLeaves ?: 0,
                totalTasks = structured?.totalLeaves ?: 0,
                // The domain has no "workstream kind"; EXTERNAL execution is the one distinction
                // the model actually makes, so it is the only one drawn.
                kind = if (ExecutionModeResolver.resolveWorkStream(ws, s.projects) == EffectiveExecutionMode.EXTERNAL)
                    WorkstreamKind.ASSISTANT else WorkstreamKind.GENERAL,
                state = when (ws.state) {
                    WorkStreamState.FOCUS, WorkStreamState.PROCESSING -> WorkstreamState.IN_PROGRESS
                    WorkStreamState.BLOCKED -> WorkstreamState.BLOCKED
                    else -> WorkstreamState.NORMAL
                },
                stateLabel = ws.state.label(),
                unstructured = structured == null
            )
        },
        standaloneTasks = standalone.map { ProjectTaskUi(it.id, it.title, it.status.isCompleted) },
        documents = knowledge.map { capture ->
            ProjectDocumentUi(
                id = capture.id,
                title = capture.title?.takeIf { it.isNotBlank() } ?: capture.content.lineSequence().first().take(80),
                subtitle = capture.type.name.lowercase().replaceFirstChar { it.uppercase() }
            )
        },
        executionDefaultLabel = if (project.defaultExecutionMode == EffectiveExecutionMode.EXTERNAL) "External" else "Human",
        archiveLabel = "Complete project"
    )

    val actions = ProjectPageActions(
        onBack = { navController.popBackStack() },
        // No project-scoped search exists yet; this opens the existing Streams search.
        onSearch = { navController.navigate(com.virlin.app.ui.navigation.RootDestination.STREAMS.route) },
        onProgress = { navController.navigate(projectTaskIndex(project.id)) },
        onOpenWorkstream = { navController.navigate(workStreamDetail(it)) },
        onAddWorkstream = { addingStream = true },
        onOpenMap = { navController.navigate(com.virlin.app.ui.map.projectMap(project.id)) },
        onOpenTask = { navController.navigate(taskDetail(it)) },
        onToggleTask = { vm.completeTask(it) },
        onTaskMenu = { taskMenu = it },
        onAddTask = { adding = true },
        onOpenDocument = { id ->
            knowledge.firstOrNull { it.id == id }?.let { capture ->
                navController.navigate(
                    when (capture.type) {
                        CaptureType.PROMPT -> "prompt_editor/$id"
                        CaptureType.LINK -> "link_editor/$id"
                        CaptureType.FILE -> "file_viewer/$id"
                        CaptureType.VOICE -> "voice_editor/$id"
                        else -> "text_note/$id"
                    }
                )
            }
        },
        onAddDocument = {
            navController.navigate(com.virlin.app.ui.notes.notesForProject(project.id, "${project.title} Notes"))
        },
        onProjectSettings = { settingsOpen = true },
        onEditProject = { editingIcon = true },
        onManageWorkstreams = { addingStream = true },
        onArchiveProject = { confirmComplete = true }
    )

    // Only THIS project's tasks: a list built from every task in the app would show, and offer
    // to re-file, another project's work.
    val projectTasks = remember(s.tasks, streams, project.id) {
        s.tasks.filter { task ->
            task.parentTaskId == null &&
                (task.workStreamId?.let { id -> streams.any { it.id == id } }
                    ?: (task.projectId == project.id))
        }
    }
    ProjectExperienceHost(project.id, streams, projectTasks, ui, actions, vm, navController)

    if (adding) AddTaskDialog("New task in ${project.title}", onDismiss = { adding = false }) { t, e -> vm.addStandaloneTask(project.id, t, e); adding = false }
    if (addingStream) AddNameDialog("New WorkStream in ${project.title}", "WorkStream name", onDismiss = { addingStream = false }) { vm.addWorkStream(project.id, it) }
    taskMenu?.let { id ->
        val task = standalone.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { taskMenu = null },
            title = { Text(task?.title ?: "Task") },
            text = { Text("Open it, or cancel it. Completion is the round control on the row.") },
            confirmButton = {
                TextButton(onClick = { taskMenu = null; navController.navigate(taskDetail(id)) }) { Text("OPEN") }
            },
            dismissButton = {
                TextButton(onClick = { taskMenu = null; vm.cancelTask(id) }) { Text("CANCEL TASK") }
            }
        )
    }
    if (settingsOpen) {
        // Project settings keeps the EXISTING execution-default control; the main page no longer
        // carries a Human/External selector.
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Project settings") },
            text = {
                Column {
                    SectionLabel("DEFAULT EXECUTION")
                    Spacer(Modifier.height(6.dp))
                    ExecutionChoiceRow(
                        selectedKey = project.defaultExecutionMode.name,
                        options = listOf(
                            Triple("Human", EffectiveExecutionMode.HUMAN.name) { vm.setProjectExecutionDefault(project.id, EffectiveExecutionMode.HUMAN) },
                            Triple("External", EffectiveExecutionMode.EXTERNAL.name) { vm.setProjectExecutionDefault(project.id, EffectiveExecutionMode.EXTERNAL) }
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "WorkStreams and tasks inherit this unless they set their own.",
                        fontSize = 12.sp, color = VirlinColors.TextSecondary
                    )
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("DONE") } }
        )
    }
    if (confirmComplete) {
        AlertDialog(
            onDismissRequest = { confirmComplete = false },
            title = { Text("Complete ${project.title}?") },
            text = { Text("Virlin has no archive state yet; completing the project is the existing action.") },
            confirmButton = {
                TextButton(onClick = { confirmComplete = false; vm.completeProject(project.id); navController.popBackStack() }) { Text("COMPLETE") }
            },
            dismissButton = { TextButton(onClick = { confirmComplete = false }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun StreamSummaryRow(sum: HierarchyViewModel.StreamSummary, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(Color.White, RoundedCornerShape(14.dp)).border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .testTag("stream_row_${sum.stream.id}")
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${sum.stream.title}, ${sum.progress.text}" }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sum.stream.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary, modifier = Modifier.weight(1f))
            Text(sum.progress.text, fontSize = 13.sp, fontWeight = FontWeight.Black,
                color = if (sum.progress.fraction == null) VirlinColors.TextTertiary else VirlinColors.TextPrimary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sum.stream.state.label(), fontSize = 11.sp, color = VirlinColors.TextTertiary)
            sum.currentTask?.let { Text("   ●  ${it.title}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextSecondary) }
        }
    }
}

// =====================================================================================
// WORKSTREAM DETAIL — the core Pass 2 screen
// =====================================================================================

@Composable
fun WorkStreamDetailScreen(
    streamId: String?,
    navController: NavController,
    initialPath: List<String> = emptyList(),
    vm: HierarchyViewModel = viewModel()
) {
    val s by vm.snapshot.collectAsState()
    val stream = s.streams.firstOrNull { it.id == streamId }
    if (stream == null) { Missing("WorkStream"); return }
    val project = stream.projectId?.let { id -> s.projects.firstOrNull { it.id == id } }
    val message by vm.knowledgeMessage.collectAsState()
    val pendingSwitch by vm.pendingSwitch.collectAsState()
    var delegatingTaskId by rememberSaveable(stream.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (message != null) { kotlinx.coroutines.delay(3_500); vm.clearKnowledgeMessage() }
    }

    val tasksById = remember(s.tasks) { s.tasks.associateBy { it.id } }
    val projectDefault = ExecutionModeResolver.projectDefault(project)
    val ui = remember(s.tasks, stream, project) {
        val mine = s.tasks.filter { it.workStreamId == stream.id }
        val childCounts = mine.groupingBy { it.parentTaskId }.eachCount()
        StreamTasksUi(
            streamId = stream.id,
            streamName = stream.title,
            projectName = project?.title ?: "No project",
            streamPercent = (ProgressCalculator.ofWorkStream(s.tasks, stream.id)
                as? ProgressResult.Structured)?.let { percentOf(it) },
            streamExecution = stream.executionPreference.asStreamMode(),
            streamEffectiveExecution =
                ExecutionModeResolver.resolveWorkStream(stream, projectDefault).asStreamMode(),
            currentTaskId = stream.activeTaskId,
            tasks = mine.map { task ->
                val children = childCounts[task.id] ?: 0
                // Partial parent progress is the app's own rule: executable leaves under it.
                val progress = if (children == 0) null
                else (ProgressCalculator.ofTask(s.tasks, task.id) as? ProgressResult.Structured)
                    ?.let { percentOf(it) }
                StreamTaskUi(
                    id = task.id,
                    parentId = task.parentTaskId,
                    title = task.title,
                    order = task.order,
                    // Only a truly completed task shows a green check; cancelled is not complete.
                    completed = task.status.isCompleted,
                    terminal = task.status.isTerminal,
                    isCurrent = stream.activeTaskId == task.id,
                    execution = task.executionPreference.asStreamMode(),
                    effectiveExecution = ExecutionModeResolver
                        .resolveTask(task, tasksById, stream, projectDefault).asStreamMode(),
                    inheritedFrom = executionSource(task, tasksById, stream, project),
                    childCount = children,
                    progressPercent = progress
                )
            }
        )
    }

    Column(Modifier.fillMaxSize().testTag(WorkStreamDetailTag)) {
        message?.let { text ->
            Box(Modifier.fillMaxWidth().background(VirlinColors.Background)) {
                Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    fontSize = 13.sp, color = VirlinColors.TextSecondary)
            }
        }
        VirlinStreamTasks(
            ui = ui,
            initialPath = initialPath,
            modifier = Modifier.weight(1f),
            actions = object : StreamTaskActions {
                override fun back() { navController.popBackStack() }
                override fun add(parentId: String?, title: String, afterId: String?, execution: StreamExecutionMode) =
                    vm.addStreamTask(stream.id, parentId, title, afterId, execution.asPreference())
                override fun rename(taskId: String, title: String) = vm.renameTask(taskId, title)
                override fun toggleComplete(taskId: String) = vm.completeTask(taskId)
                override fun setCurrent(taskId: String) = vm.setActiveTask(stream.id, taskId)
                override fun setExecution(taskId: String, mode: StreamExecutionMode) {
                    if (mode == StreamExecutionMode.INHERIT) vm.resetTaskExecutionPreference(taskId)
                    else vm.setTaskExecutionPreference(taskId, mode.asPreference())
                }
                override fun setStreamExecution(mode: StreamExecutionMode) {
                    if (mode == StreamExecutionMode.INHERIT) vm.resetWorkStreamExecutionPreference(stream.id)
                    else vm.setWorkStreamExecutionPreference(stream.id, mode.asPreference())
                }
                override fun move(taskIds: List<String>, destinationParentId: String?, afterId: String?) =
                    vm.moveTasks(taskIds, destinationParentId, afterId, stream.id)
                override fun duplicate(taskId: String) = vm.duplicateTask(taskId)
                override fun delete(taskIds: List<String>) = taskIds.forEach { vm.cancelTask(it) }
                override fun focus(taskId: String) { vm.focusWorkItem(taskId) }
                override fun delegate(taskId: String) { delegatingTaskId = taskId }
                override fun openStreamWorkItem(streamId: String) { navController.popBackStack() }
            }
        )
    }
    pendingSwitch?.let { pending ->
        SwitchFocusDialog(
            currentTitle = pending.currentTitle,
            nextTitle = pending.target.workItem.title,
            onCancel = vm::cancelSwitch,
            onConfirm = vm::confirmSwitch
        )
    }
    delegatingTaskId?.let { taskId ->
        val task = s.tasks.firstOrNull { it.id == taskId }
        if (task == null || task.status.isTerminal) {
            LaunchedEffect(taskId) { delegatingTaskId = null }
        } else {
            DelegateDialog(task.title, onDismiss = { delegatingTaskId = null }) { actor, instruction, minutes, stages ->
                vm.delegate(stream.id, task.id, actor, instruction, minutes, stages)
                delegatingTaskId = null
            }
        }
    }
}

private fun percentOf(p: ProgressResult.Structured): Int =
    if (p.total == 0L) 0 else Math.round((p.completed * 100f) / p.total)

/** The app's preference vocabulary, in the screen's terms. */
private fun ExecutionPreference.asStreamMode(): StreamExecutionMode = when (this) {
    ExecutionPreference.HUMAN -> StreamExecutionMode.HUMAN
    ExecutionPreference.EXTERNAL -> StreamExecutionMode.EXTERNAL
    else -> StreamExecutionMode.INHERIT
}

private fun EffectiveExecutionMode.asStreamMode(): StreamExecutionMode = when (this) {
    EffectiveExecutionMode.EXTERNAL -> StreamExecutionMode.EXTERNAL
    else -> StreamExecutionMode.HUMAN
}

private fun StreamExecutionMode.asPreference(): ExecutionPreference = when (this) {
    StreamExecutionMode.HUMAN -> ExecutionPreference.HUMAN
    StreamExecutionMode.EXTERNAL -> ExecutionPreference.EXTERNAL
    StreamExecutionMode.INHERIT -> ExecutionPreference.INHERIT
}

/**
 * Which ancestor the effective mode actually came from. The resolver decides the value; this
 * walks the same chain only to name its source, so the sheet can say where it came from.
 */
private fun executionSource(
    task: Task,
    tasksById: Map<String, Task>,
    stream: WorkStream,
    project: Project?
): String? {
    if (task.executionPreference != ExecutionPreference.INHERIT) return null
    var cursor = task.parentTaskId?.let { tasksById[it] }
    val seen = HashSet<String>()
    while (cursor != null && seen.add(cursor.id)) {
        if (cursor.executionPreference != ExecutionPreference.INHERIT) return cursor.title
        cursor = cursor.parentTaskId?.let { tasksById[it] }
    }
    if (stream.executionPreference != ExecutionPreference.INHERIT) return stream.title
    return project?.title
}


@Composable
private fun ExecutionChoiceRow(
    selectedKey: String,
    options: List<Triple<String, String, () -> Unit>>
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, key, onClick) ->
            val selected = selectedKey == key
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else VirlinColors.TextSecondary,
                modifier = Modifier
                    .background(if (selected) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
                    .border(1.dp, Hairline, RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("execution_choice_${key.lowercase()}")
            )
        }
    }
}

@Composable
private fun BackRow(navController: NavController) {
    Text("‹ Back", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextSecondary,
        modifier = Modifier.clickable(role = Role.Button) { navController.popBackStack() }.semantics { contentDescription = "Back" }.padding(vertical = 8.dp))
}

@Composable
private fun Missing(what: String) {
    Box(Modifier.fillMaxSize().background(VirlinColors.Background), Alignment.Center) { Text("$what not found", color = VirlinColors.TextTertiary) }
}

fun WorkStreamState.label() = when (this) {
    WorkStreamState.FOCUS -> "Focus"; WorkStreamState.PROCESSING -> "Processing"; WorkStreamState.CHECK -> "Check due"
    WorkStreamState.READY -> "Ready"; WorkStreamState.SNOOZED -> "Snoozed"; WorkStreamState.BLOCKED -> "Blocked"
    WorkStreamState.PAUSED -> "Paused"; WorkStreamState.DONE -> "Done"
}

private fun stateSurface(s: WorkStreamState): Color = when (s) {
    WorkStreamState.FOCUS -> VirlinColors.FocusSurface; WorkStreamState.PROCESSING -> VirlinColors.ProcessingSurface
    WorkStreamState.CHECK -> VirlinColors.NeedsYouDue; WorkStreamState.READY -> VirlinColors.ReadySurface
    WorkStreamState.BLOCKED -> VirlinColors.NeedsYouOverdue; else -> Color.White
}


/**
 * The project's Activity record, mapped to the timeline's UI model. The entries come from the
 * domain projection over the WorkStreams' append-only history; nothing is copied here.
 */
fun activityRecords(entries: List<ProjectActivityEntry>): List<ActivityRecord> =
    entries.map { entry ->
            ActivityRecord(
                id = entry.id,
                projectId = entry.projectId,
                occurredAtMillis = entry.occurredAt.toEpochMilli(),
                kind = when (entry.kind) {
                    ActivityEntryKind.PROMPT -> ActivityKind.PROMPT
                    ActivityEntryKind.NOTE -> ActivityKind.NOTE
                    ActivityEntryKind.LINK -> ActivityKind.LINK
                    ActivityEntryKind.IMAGE -> ActivityKind.IMAGE
                    ActivityEntryKind.AUDIO -> ActivityKind.AUDIO
                    ActivityEntryKind.FILE -> ActivityKind.FILE
                    ActivityEntryKind.TASK -> ActivityKind.TASK
                    ActivityEntryKind.CHECKLIST -> ActivityKind.CHECKLIST
                    ActivityEntryKind.EVENT -> ActivityKind.EVENT
                },
                title = entry.title,
                description = entry.description,
                workstreamId = entry.workStreamId,
                workstreamName = entry.workStreamName,
                taskId = entry.taskId,
                taskName = entry.taskName,
                content = entry.content,
                knowledgeId = entry.captureId,
                assetId = entry.captureId,
                captureId = entry.captureId,
                fileName = entry.fileName,
                audioDurationSeconds = entry.audioDurationMillis?.let { (it / 1000).toInt() },
                completedSteps = entry.completedSteps,
                totalSteps = entry.totalSteps,
                oldStatus = entry.fromStatus,
                newStatus = entry.toStatus,
                sourceLabel = entry.sourceLabel,
                mediaPath = entry.mediaPath,
                hasSnapshot = entry.hasSnapshot
            )
    }

/** Captures open in the editor/viewer that already exists for their type. */
private fun captureRoute(captureId: String, vm: HierarchyViewModel): String {
    val capture = vm.captures.value.firstOrNull { it.id == captureId }
    return when (capture?.type) {
        CaptureType.PROMPT -> "prompt_editor/$captureId"
        CaptureType.LINK -> "link_editor/$captureId"
        CaptureType.FILE -> "file_viewer/$captureId"
        CaptureType.VOICE -> "voice_editor/$captureId"
        else -> "text_note/$captureId"
    }
}


/** Captures and their documents, mapped to the library's UI model, scoped to one project. */
fun knowledgeItems(
    projectId: String,
    entries: List<HierarchyViewModel.KnowledgeEntry>,
    streams: List<WorkStream>,
    tasks: List<Task>,
    links: List<com.virlin.app.domain.model.TagLink> = emptyList()
): List<KnowledgeItem> =
    entries.map { entry ->
            val capture = entry.capture
            val stream = streams.firstOrNull { it.id == capture.workStreamId }
            val task = tasks.firstOrNull { it.id == capture.taskId }
            val clip = entry.voice?.sortedClips()?.firstOrNull()
            // The same renderer Activity and Copy use, so a checklist excerpt shows its state
            // and a prompt excerpt keeps its steps instead of collapsing into bare lines.
            val blocks = entry.prompt?.blocks ?: entry.note?.blocks ?: emptyList()
            val checklist = NoteBlockShape.checklistProgress(blocks)
            val steps = if (checklist == null) NoteBlockShape.stepProgress(blocks) else null
            val body = entry.prompt?.blocks?.let { NoteBlockText.render(it) }
                ?: entry.note?.blocks?.let { NoteBlockText.render(it) }
                ?: capture.content
            KnowledgeItem(
                id = capture.id,
                projectId = projectId,
                // Classified from the blocks that were actually saved, so a note of checkboxes
                // is a checklist and a note of ordered items is a set of steps — one capture,
                // named for what it holds, never a second copy of it.
                type = when {
                    capture.type == CaptureType.VOICE -> KnowledgeType.AUDIO
                    entry.attachment?.kind == AttachmentKind.IMAGE -> KnowledgeType.IMAGE
                    entry.attachment != null -> KnowledgeType.DOCUMENT
                    // A prompt is a prompt even when its body happens to be numbered; block
                    // shape classifies NOTES, which is where Virlin keeps steps and checklists.
                    capture.type == CaptureType.PROMPT -> KnowledgeType.PROMPT
                    checklist != null -> KnowledgeType.CHECKLIST
                    steps != null -> KnowledgeType.STEPS
                    else -> KnowledgeType.DOCUMENT
                },
                title = capture.title?.takeIf { it.isNotBlank() }
                    ?: entry.attachment?.displayName
                    ?: capture.sourceUrl
                    ?: body.lineSequence().firstOrNull { it.isNotBlank() }?.take(60)
                    ?: capture.type.name.lowercase().replaceFirstChar { it.uppercase() },
                snippet = capture.sourceUrl ?: body.take(240),
                assetId = capture.id,
                workstreamId = stream?.id,
                workstreamName = stream?.title,
                taskId = task?.id,
                taskName = task?.title,
                updatedAtMillis = capture.updatedAt.toEpochMilli(),
                sizeBytes = entry.attachment?.sizeBytes ?: clip?.sizeBytes,
                durationSeconds = clip?.let { (it.durationMs / 1000).toInt() },
                fileName = entry.attachment?.displayName ?: clip?.displayName,
                mediaPath = entry.attachment?.relativePath ?: clip?.relativePath,
                downloadable = entry.attachment != null || clip != null,
                completedCount = (checklist ?: steps)?.completed,
                totalCount = (checklist ?: steps)?.total,
                tagIds = links.filter {
                    it.targetType == com.virlin.app.domain.model.TagTargetType.CAPTURE &&
                        it.targetId == capture.id
                }.map { it.tagId }.toSet()
            )
    }

/** Shares the stored file through the app's existing FileProvider. */
private fun shareKnowledgeFile(
    context: android.content.Context,
    item: KnowledgeItem,
    relativePath: String
) {
    val voice = com.virlin.app.data.voice.VoiceFileStore.resolve(context, relativePath)
    val file = if (voice.exists()) voice
    else com.virlin.app.data.attachment.AttachmentFileStore.resolve(context, relativePath)
    if (!file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = if (voice.exists()) "audio/*" else "application/octet-stream"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, item.fileName ?: item.title)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share"))
}


/**
 * Feeds the one project screen. Overview, Knowledge and Activity are tabs of the SAME screen,
 * so their data is gathered here and every action points at the repository the rest of the app
 * already uses.
 */
@Composable
private fun ProjectExperienceHost(
    projectId: String,
    streams: List<WorkStream>,
    projectTasks: List<Task>,
    ui: ProjectPageUi,
    projectActions: ProjectPageActions,
    vm: HierarchyViewModel,
    navController: NavController
) {
    val entries by vm.knowledge.collectAsState()
    val activity by vm.activity.collectAsState()
    val message by vm.knowledgeMessage.collectAsState()
    val allTags by vm.tags.collectAsState()
    val links by vm.tagLinks.collectAsState()
    val steps by vm.taskSteps.collectAsState()
    val tagSaved by vm.tagSaveSucceeded.collectAsState()
    // Only this project's vocabulary reaches this screen.
    val projectTags = remember(allTags, projectId) {
        allTags.filter { it.projectId == projectId }.map { KnowledgeTag(it.id, it.name) }
    }
    var editingTagsFor by remember { mutableStateOf<Pair<Set<String>, Set<String>>?>(null) }
    var managingTags by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(projectId) { vm.openKnowledge(projectId); vm.openActivity(projectId) }
    LaunchedEffect(message) {
        if (message != null) { kotlinx.coroutines.delay(3_500); vm.clearKnowledgeMessage() }
    }

    val knowledgeItems = remember(entries, streams, projectTasks, links) {
        knowledgeItems(projectId, entries, streams, projectTasks, links)
    }
    val taskProjection = remember(projectTasks, streams, links, steps) {
        projectTasks.map { task ->
            val own = steps.filter { it.taskId == task.id }
            KnowledgeProjectTask(
                id = task.id, projectId = projectId, title = task.title,
                status = task.status.name.lowercase().replaceFirstChar { it.uppercase() }
                    .replace('_', ' '),
                updatedAtMillis = task.updatedAt.toEpochMilli(),
                workstreamId = task.workStreamId,
                workstreamName = streams.firstOrNull { it.id == task.workStreamId }?.title,
                tagIds = links.filter {
                    it.targetType == com.virlin.app.domain.model.TagTargetType.TASK &&
                        it.targetId == task.id
                }.map { it.tagId }.toSet(),
                completedSteps = own.count { it.done },
                totalSteps = own.size
            )
        }
    }
    val records = remember(activity.entries) { activityRecords(activity.entries) }

    VirlinProjectExperience(
        data = ProjectExperienceData(
            project = ui,
            knowledge = knowledgeItems,
            tasks = taskProjection,
            tags = projectTags,
            activity = records,
            activityLoading = activity.loading,
            activityError = activity.error,
            activityHasMore = activity.hasMore,
            onLoadMoreActivity = { vm.loadMoreActivity() },
            message = message
        ),
        actions = ProjectExperienceActions(
            project = projectActions,
            knowledge = KnowledgeActions(
                onAdd = {
                    navController.navigate(com.virlin.app.ui.notes.notesForProject(projectId, "${ui.title} Notes"))
                },
                onOpen = { navController.navigate(captureRoute(it.id, vm)) },
                onMove = { id, workStreamId, taskId -> vm.moveCapture(id, projectId, workStreamId, taskId) },
                onMoveMany = { ids, workStreamId, taskId ->
                    vm.moveCaptures(ids, projectId, workStreamId, taskId)
                },
                onDuplicate = { vm.duplicateCapture(context, it) },
                onDownload = { id ->
                    val item = knowledgeItems.firstOrNull { it.id == id }
                    val path = item?.mediaPath
                    if (path != null) shareKnowledgeFile(context, item, path)
                },
                onDelete = { vm.archiveCapture(it) },
                onOpenTask = { navController.navigate(taskDetail(it)) },
                // New content captured already attached to that task.
                onAddToTask = { taskId ->
                    val title = taskProjection.firstOrNull { it.id == taskId }?.title ?: "Notes"
                    navController.navigate(com.virlin.app.ui.notes.notesForTask(taskId, title))
                },
                onPlaceTask = { taskId, workStreamId -> vm.placeTask(taskId, workStreamId) },
                onOrganizeMany = { knowledgeIds, taskIds, workStreamId ->
                    vm.organize(projectId, knowledgeIds, taskIds, workStreamId)
                },
                onEditTags = { knowledgeIds, taskIds -> editingTagsFor = knowledgeIds to taskIds },
                onManageTags = { managingTags = true },
                onConvertChecklistToTask = { captureId ->
                    val capture = vm.captures.value.firstOrNull { it.id == captureId }
                    vm.convertChecklistToTask(projectId, captureId, capture?.workStreamId)
                }
            ),
            activity = ActivityActions(
                onBack = { navController.popBackStack() },
                onOpenTask = { navController.navigate(taskDetail(it)) },
                onOpenKnowledge = { navController.navigate(captureRoute(it, vm)) },
                onOpenAsset = { navController.navigate(captureRoute(it, vm)) },
                onCopyPrompt = { text ->
                    scope.launch {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    }
                }
            )
        ),
        projectIcon = { ui.icon?.invoke() }
    )

    editingTagsFor?.let { (knowledgeIds, taskIds) ->
        // The tags already on every selected item; a mixed selection starts from what they share.
        val current = remember(knowledgeIds, taskIds, links) {
            val sets = knowledgeIds.map { id ->
                links.filter {
                    it.targetType == com.virlin.app.domain.model.TagTargetType.CAPTURE && it.targetId == id
                }.map { it.tagId }.toSet()
            } + taskIds.map { id ->
                links.filter {
                    it.targetType == com.virlin.app.domain.model.TagTargetType.TASK && it.targetId == id
                }.map { it.tagId }.toSet()
            }
            if (sets.isEmpty()) emptySet() else sets.reduce { a, b -> a intersect b }
        }
        val count = knowledgeIds.size + taskIds.size
        ProjectTagEditorSheet(
            title = if (count == 1) "1 item" else "$count items",
            tags = projectTags,
            initiallySelected = current,
            onDismiss = { editingTagsFor = null },
            onCreate = { vm.createTag(projectId, it) },
            onSave = { chosen -> vm.setTags(projectId, chosen, knowledgeIds, taskIds) }
        )
    }
    // The sheet closes only once the write has landed, so a failure keeps the selection.
    LaunchedEffect(tagSaved) {
        if (tagSaved) { editingTagsFor = null; vm.clearTagSaveFlag() }
    }
    if (managingTags) {
        ProjectManageTagsSheet(
            tags = projectTags,
            message = message,
            onDismiss = { managingTags = false },
            onCreate = { vm.createTag(projectId, it) },
            onRename = { id, name -> vm.renameTag(id, name) },
            onMerge = { from, into -> vm.mergeTags(from, into) },
            onDelete = { vm.deleteTag(it) }
        )
    }
}


/**
 * PROJECT TASK INDEX — the project's work items, listed from the same units its progress is
 * counted from, so the ratio at the top and the rows beneath it can never disagree.
 *
 * It is read-only navigation: a row opens that task at its real place in its WorkStream, where
 * the task's own actions already live.
 */
@Composable
fun ProjectTaskIndexScreen(
    projectId: String?,
    navController: NavController,
    vm: HierarchyViewModel = viewModel()
) {
    val s by vm.snapshot.collectAsState()
    val project = s.projects.firstOrNull { it.id == projectId }
    if (project == null) { Missing("Project"); return }

    val state = remember(s.tasks, s.streams, project.id) {
        val units = ProjectTaskUnits.of(s.tasks, s.streams, project.id)
        val byId = s.tasks.associateBy { it.id }
        val currentIds = s.streams.filter { it.projectId == project.id }.mapNotNull { it.activeTaskId }.toSet()
        ProjectTaskIndexState(
            projectId = project.id,
            projectTitle = project.title,
            completedCount = units.count { it.completed },
            totalCount = units.size,
            tasks = units.mapIndexed { index, unit ->
                when (unit) {
                    is ProjectTaskUnits.Unit.EmptyWorkStream -> ProjectTaskIndexEntry(
                        taskId = "stream:" + unit.workStream.id,
                        projectId = project.id,
                        workstreamId = unit.workStream.id,
                        workstreamTitle = unit.workStream.title,
                        parentTaskId = null,
                        ancestorTaskIds = emptyList(),
                        ancestorTitles = emptyList(),
                        title = unit.workStream.title,
                        completed = unit.completed,
                        current = false,
                        order = index,
                        isEmptyWorkStream = true
                    )
                    is ProjectTaskUnits.Unit.TaskUnit -> {
                        // Ancestors from the stream root down to the direct parent, by id.
                        val ancestors = generateSequence(unit.task.parentTaskId) { byId[it]?.parentTaskId }
                            .mapNotNull { byId[it] }
                            .toList()
                            .reversed()
                        ProjectTaskIndexEntry(
                            taskId = unit.task.id,
                            projectId = project.id,
                            workstreamId = unit.workStream?.id,
                            workstreamTitle = unit.workStream?.title,
                            parentTaskId = unit.task.parentTaskId,
                            ancestorTaskIds = ancestors.map { it.id },
                            ancestorTitles = ancestors.map { it.title },
                            title = unit.task.title,
                            completed = unit.completed,
                            current = unit.task.id in currentIds,
                            order = index
                        )
                    }
                }
            }
        )
    }

    VirlinProjectTaskIndex(
        state = state,
        // The app-wide bar is drawn by the host scaffold; a second one here would be a duplicate.
        footer = {},
        actions = object : ProjectTaskIndexActions {
            override fun backToProject(projectId: String) { navController.popBackStack() }
            override fun openTaskInHierarchy(
                projectId: String,
                workstreamId: String?,
                ancestorTaskIds: List<String>,
                taskId: String?
            ) {
                // A standalone task has no WorkStream page; its own detail route is its home.
                if (workstreamId == null) {
                    taskId?.let { navController.navigate(taskDetail(it)) }
                    return
                }
                navController.navigate(
                    workStreamDetailAt(workstreamId, ancestorTaskIds + listOfNotNull(taskId))
                )
            }
            override fun searchProjectTasks(projectId: String) {
                navController.navigate(com.virlin.app.ui.navigation.RootDestination.STREAMS.route)
            }
            override fun showOptions(projectId: String) { navController.popBackStack() }
            override fun retry(projectId: String) { /* observed state; nothing to re-fetch */ }
        }
    )
}
