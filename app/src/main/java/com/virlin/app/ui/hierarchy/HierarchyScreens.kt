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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.mock.MockData
import com.virlin.app.ui.theme.VirlinColors
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val ProjectDetailRoute = "project_detail/{id}"
const val WorkStreamDetailRoute = "workstream_detail/{id}"
const val TaskDetailRoute = "task_detail/{id}"
fun projectDetail(id: String) = "project_detail/$id"
fun workStreamDetail(id: String) = "workstream_detail/$id"
fun taskDetail(id: String) = "task_detail/$id"

const val ProjectDetailTag = "project_detail"
const val ProjectDetailIconTag = "project_detail_icon"
const val WorkStreamDetailTag = "workstream_detail"
const val TaskDetailTag = "task_detail"
const val TaskCancelTag = "task_cancel"
const val TaskCancelConfirmTag = "task_cancel_confirm"
const val StartNextTag = "start_next_task"

private val Hairline = VirlinColors.TextPrimary.copy(alpha = 0.08f)
private val DueFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d · h:mm a")

// =====================================================================================
// PROJECT DETAIL
// =====================================================================================

@Composable
fun ProjectDetailScreen(projectId: String?, navController: NavController, vm: HierarchyViewModel = viewModel()) {
    val s by vm.snapshot.collectAsState()
    val project = s.projects.firstOrNull { it.id == projectId }
    if (project == null) { Missing("Project"); return }
    val streams = s.streams.filter { it.projectId == project.id }
    val standalone = HierarchyPresentation.rows(s.tasks.filter { it.projectId == project.id && it.workStreamId == null }, null, null, s.expanded)
    val progress = remember(s) { ProgressCalculator.ofProject(s.tasks, s.streams, project.id).toLabel() }
    var adding by remember { mutableStateOf(false) }
    var addingStream by remember { mutableStateOf(false) }
    var editingIcon by remember { mutableStateOf(false) }
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

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(VirlinColors.Background).testTag(ProjectDetailTag),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp)
    ) {
        item { BackRow(navController) }
        item {
            Spacer(Modifier.height(8.dp))
            // Project identity (Phase 3): the icon sits in the heading; tapping it (or its ✎) opens the editor.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .testTag(ProjectDetailIconTag)
                        .clickable(role = Role.Button) { editingIcon = true }
                        .semantics { contentDescription = "Change ${project.title} icon" },
                    contentAlignment = Alignment.Center
                ) {
                    com.virlin.app.ui.components.ProjectIcon(project = project, size = 52.dp, decorative = true)
                    Box(
                        modifier = Modifier.align(Alignment.BottomEnd).size(20.dp)
                            .background(VirlinColors.Background, CircleShape)
                            .padding(2.dp)
                            .background(VirlinColors.TextPrimary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Rounded.Edit, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(10.dp)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(project.title.uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, color = VirlinColors.TextPrimary, lineHeight = 26.sp)
                    project.description?.let { Text(it, fontSize = 13.sp, color = VirlinColors.TextSecondary) }
                }
            }
            Spacer(Modifier.height(14.dp))
            ProgressLine(progress, large = true)
            val facts = listOfNotNull(
                project.estimatedEffort?.let { "Estimated ${it.toEffortLabel()}" },
                project.dueAt?.let { "Due ${DueFmt.format(it.atZone(ZoneId.systemDefault()))}" }
            )
            if (facts.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(facts.joinToString("   ·   "), fontSize = 12.sp, color = VirlinColors.TextSecondary) }
            Spacer(Modifier.height(12.dp))
            SectionLabel("DEFAULT EXECUTION")
            Spacer(Modifier.height(6.dp))
            ExecutionChoiceRow(
                selectedKey = project.defaultExecutionMode.name,
                options = listOf(
                    Triple("Human", EffectiveExecutionMode.HUMAN.name) { vm.setProjectExecutionDefault(project.id, EffectiveExecutionMode.HUMAN) },
                    Triple("External", EffectiveExecutionMode.EXTERNAL.name) { vm.setProjectExecutionDefault(project.id, EffectiveExecutionMode.EXTERNAL) }
                )
            )
            Spacer(Modifier.height(24.dp))
            SectionLabel("WORKSTREAMS")
            Spacer(Modifier.height(8.dp))
        }
        if (streams.isEmpty()) item { Text("No WorkStreams yet", fontSize = 13.sp, color = VirlinColors.TextTertiary) }
        items(streams, key = { it.id }) { ws ->
            val sum = vm.streamSummary(s, ws)
            StreamSummaryRow(sum) { navController.navigate(workStreamDetail(ws.id)) }
        }
        item {
            Spacer(Modifier.height(10.dp))
            AddButton("+ WORKSTREAM", onClick = { addingStream = true }, tag = AddWorkStreamButtonTag)
        }
        item {
            Spacer(Modifier.height(20.dp)); SectionLabel("STANDALONE TASKS"); Spacer(Modifier.height(6.dp))
            if (standalone.isEmpty()) Text("None", fontSize = 13.sp, color = VirlinColors.TextTertiary)
        }
        items(standalone, key = { it.id }) { row ->
            TaskTreeRow(row, onOpen = { navController.navigate(taskDetail(it)) }, onToggle = vm::toggleExpanded, onComplete = vm::completeTask)
        }
        item { Spacer(Modifier.height(16.dp)); AddButton("+ ADD TASK", onClick = { adding = true }) }
    }
    if (adding) AddTaskDialog("New task in ${project.title}", onDismiss = { adding = false }) { t, e -> vm.addStandaloneTask(project.id, t, e); adding = false }
    if (addingStream) AddNameDialog("New WorkStream in ${project.title}", "WorkStream name", onDismiss = { addingStream = false }) { vm.addWorkStream(project.id, it) }
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
fun WorkStreamDetailScreen(streamId: String?, navController: NavController, vm: HierarchyViewModel = viewModel()) {
    val s by vm.snapshot.collectAsState()
    val stream = s.streams.firstOrNull { it.id == streamId }
    if (stream == null) { Missing("WorkStream"); return }
    val project = stream.projectId?.let { id -> s.projects.firstOrNull { it.id == id } }
    val sum = vm.streamSummary(s, stream)
    val rows = remember(s, stream.id) { vm.streamRows(s, stream) }
    val display = MockData.streams.collectAsState().value.firstOrNull { it.id == stream.id }
    var adding by remember { mutableStateOf(false) }
    var next by remember(s.tasks, stream.activeTaskId) { mutableStateOf<Task?>(null) }
    LaunchedEffect(stream.activeTaskId, s.tasks) { vm.expandPathTo(stream.activeTaskId); next = if (stream.activeTaskId == null) vm.nextCandidate(stream.id) else null }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(VirlinColors.Background).testTag(WorkStreamDetailTag),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp)
    ) {
        item { BackRow(navController) }
        item {
            Spacer(Modifier.height(8.dp))
            Text(stream.title.uppercase(), fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, color = VirlinColors.TextPrimary)
            // No empty Project label for projectless streams.
            project?.let { Text(it.title, fontSize = 13.sp, color = VirlinColors.TextSecondary,
                modifier = Modifier.clickable(role = Role.Button) { navController.navigate(projectDetail(it.id)) }) }
            Spacer(Modifier.height(14.dp))
            ProgressLine(sum.progress.let { if (it.fraction != null) it.copy(text = "${it.text} COMPLETE") else it }, large = true)
            Spacer(Modifier.height(14.dp))
            // Runtime attention state — distinct from planning progress.
            Row(verticalAlignment = Alignment.CenterVertically) {
                stream.tool?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary); Spacer(Modifier.width(8.dp)) }
                Text(stream.state.label().uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = VirlinColors.TextSecondary,
                    modifier = Modifier.background(stateSurface(stream.state), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp))
                display?.checkInRemainingSec?.takeIf { stream.state == WorkStreamState.PROCESSING }?.let {
                    Spacer(Modifier.width(10.dp)); Text("Check in %02d:%02d".format(maxOf(it, 0) / 60, maxOf(it, 0) % 60), fontSize = 11.sp, color = VirlinColors.TextTertiary)
                }
            }
            Spacer(Modifier.height(12.dp))
            val effectiveWs = ExecutionModeResolver.resolveCurrent(stream, s.projects, s.tasks)
            SectionLabel("EXECUTION · ${effectiveWs.name}")
            Spacer(Modifier.height(6.dp))
            ExecutionPreferenceRow(
                preference = stream.executionPreference,
                effective = effectiveWs,
                allowInherit = project != null,
                onSelect = { vm.setWorkStreamExecutionPreference(stream.id, it) },
                onReset = { vm.resetWorkStreamExecutionPreference(stream.id) }
            )
            Spacer(Modifier.height(16.dp))
            SectionLabel("CURRENT TASK"); Spacer(Modifier.height(4.dp))
            if (sum.currentTask != null) {
                Text(sum.currentTask.title, fontSize = 15.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary,
                    modifier = Modifier.clickable(role = Role.Button) { navController.navigate(taskDetail(sum.currentTask.id)) }.testTag("current_task"))
            } else {
                Text("None", fontSize = 13.sp, color = VirlinColors.TextTertiary)
                next?.let { n ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Next: ${n.title}", fontSize = 13.sp, color = VirlinColors.TextSecondary, modifier = Modifier.weight(1f))
                        Text("START", fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White,
                            modifier = Modifier.background(VirlinColors.TextPrimary, RoundedCornerShape(50)).testTag(StartNextTag)
                                .clickable(role = Role.Button) { vm.setActiveTask(stream.id, n.id) }.semantics { contentDescription = "Start ${n.title}" }
                                .padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(22.dp)); SectionLabel("TASKS"); Spacer(Modifier.height(6.dp))
            if (rows.isEmpty()) Text("No tasks yet — this WorkStream is the work item itself.", fontSize = 13.sp, color = VirlinColors.TextTertiary)
        }
        items(rows, key = { it.id }) { row ->
            TaskTreeRow(row, onOpen = { navController.navigate(taskDetail(it)) }, onToggle = vm::toggleExpanded, onComplete = vm::completeTask)
        }
        item { Spacer(Modifier.height(12.dp)); AddButton("+ TASK", onClick = { adding = true }) }
        item {
            Spacer(Modifier.height(24.dp)); SectionLabel("CONTEXT"); Spacer(Modifier.height(6.dp))
            val ctx = listOfNotNull(
                stream.lastHumanAction?.let { "Last · $it" }, stream.waitingFor?.let { "Waiting for · $it" },
                stream.nextHumanAction?.let { "Next · $it" }, stream.blockerReason?.let { "Blocked · $it" }
            )
            if (ctx.isEmpty()) Text("Nothing recorded yet", fontSize = 13.sp, color = VirlinColors.TextTertiary)
            else ctx.forEach { Text(it, fontSize = 13.sp, color = VirlinColors.TextSecondary) }
        }
    }
    if (adding) AddTaskDialog("New task in ${stream.title}", onDismiss = { adding = false }) { t, e -> vm.addTask(stream.id, t, e); adding = false }
}

// =====================================================================================
// TASK DETAIL — reusable at any depth
// =====================================================================================

@Composable
fun TaskDetailScreen(taskId: String?, navController: NavController, vm: HierarchyViewModel = viewModel()) {
    val s by vm.snapshot.collectAsState()
    val task = s.tasks.firstOrNull { it.id == taskId }
    if (task == null) { Missing("Task"); return }
    val crumb = remember(s, task.id) { vm.breadcrumb(s, task) }
    val stream = task.workStreamId?.let { id -> s.streams.firstOrNull { it.id == id } }
    val isCurrent = stream?.activeTaskId == task.id
    val rows = remember(s, task.id) { vm.subtaskRows(s, task) }
    val progress = remember(s, task.id) { ProgressCalculator.ofTask(s.tasks, task.id).toLabel() }
    var focused by remember(task.id) { mutableStateOf<Duration?>(null) }
    LaunchedEffect(task.id, s.streams) { focused = vm.focusedOn(task) }
    var adding by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(VirlinColors.Background).testTag(TaskDetailTag),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp)
    ) {
        item { BackRow(navController) }
        item {
            Spacer(Modifier.height(4.dp))
            // Derived breadcrumb: project › stream › ancestors.
            val trail = listOfNotNull(crumb.project, crumb.workStream) + crumb.ancestors.map { it.title }
            if (trail.isNotEmpty()) Text(trail.joinToString("  ›  "), fontSize = 11.sp, color = VirlinColors.TextTertiary, modifier = Modifier.testTag("task_breadcrumb"))
            Spacer(Modifier.height(6.dp))
            Text(task.title.uppercase(), fontSize = 20.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp,
                color = if (task.status == TaskStatus.CANCELLED) VirlinColors.TextTertiary else VirlinColors.TextPrimary,
                textDecoration = if (task.status == TaskStatus.CANCELLED) TextDecoration.LineThrough else null)
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    when { isCurrent -> "CURRENT"; else -> task.status.label() }, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp,
                    color = VirlinColors.TextSecondary,
                    modifier = Modifier.background(if (isCurrent) VirlinColors.FocusSurface else Color.White, RoundedCornerShape(50)).border(1.dp, Hairline, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            task.description?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, color = VirlinColors.TextSecondary) }
            Spacer(Modifier.height(12.dp))
            val effectiveTask = ExecutionModeResolver.resolveTask(task, s.projects, s.streams, s.tasks)
            SectionLabel("EXECUTION · ${effectiveTask.name}")
            Spacer(Modifier.height(6.dp))
            ExecutionPreferenceRow(
                preference = task.executionPreference,
                effective = effectiveTask,
                allowInherit = true,
                onSelect = { vm.setTaskExecutionPreference(task.id, it) },
                onReset = { vm.resetTaskExecutionPreference(task.id) }
            )
            Spacer(Modifier.height(16.dp))
            if (rows.isNotEmpty()) { SectionLabel("PROGRESS"); Spacer(Modifier.height(4.dp)); ProgressLine(progress); Spacer(Modifier.height(14.dp)) }
            // Distinct time concepts — never merged. Omitted when unavailable.
            val facts = listOfNotNull(
                task.estimatedEffort?.let { "Estimated" to it.toEffortLabel() },
                focused?.let { "Focused" to it.toEffortLabel() },
                task.dueAt?.let { "Due" to DueFmt.format(it.atZone(ZoneId.systemDefault())) },
                task.reminderAt?.let { "Reminder" to DueFmt.format(it.atZone(ZoneId.systemDefault())) },
                ("Priority" to task.priority.name.lowercase().replaceFirstChar(Char::uppercase)).takeIf { task.priority != com.virlin.app.domain.model.Priority.NORMAL }
            )
            facts.forEach { (k, v) -> Row { Text(k, fontSize = 12.sp, color = VirlinColors.TextTertiary, modifier = Modifier.width(90.dp)); Text(v, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary) } }
            Spacer(Modifier.height(18.dp)); SectionLabel("SUBTASKS"); Spacer(Modifier.height(6.dp))
            if (rows.isEmpty()) Text("No subtasks", fontSize = 13.sp, color = VirlinColors.TextTertiary)
        }
        items(rows, key = { it.id }) { row ->
            TaskTreeRow(row, onOpen = { navController.navigate(taskDetail(it)) }, onToggle = vm::toggleExpanded, onComplete = vm::completeTask)
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row {
                if (!task.status.isTerminal) AddButton("+ SUBTASK", onClick = { adding = true })
                Spacer(Modifier.width(10.dp))
                if (!task.status.isTerminal && stream != null && !isCurrent)
                    AddButton("SET CURRENT", onClick = { vm.setActiveTask(stream.id, task.id) }, modifier = Modifier.testTag("set_current"))
            }
            task.notes?.let { Spacer(Modifier.height(22.dp)); SectionLabel("NOTES"); Spacer(Modifier.height(4.dp)); Text(it, fontSize = 13.sp, color = VirlinColors.TextSecondary) }
            if (!task.status.isTerminal) {
                Spacer(Modifier.height(28.dp))
                // Secondary, low-salience destructive action. Confirmation required when descendants exist.
                Text("Cancel task", fontSize = 12.sp, color = VirlinColors.TextTertiary,
                    modifier = Modifier.testTag(TaskCancelTag).clickable(role = Role.Button) { if (rows.isNotEmpty()) confirmCancel = true else vm.cancelTask(task.id) }.padding(vertical = 8.dp))
            }
        }
    }
    if (adding) AddTaskDialog("New subtask under ${task.title}", onDismiss = { adding = false }) { t, e -> vm.addSubtask(task.id, t, e); adding = false }
    if (confirmCancel) Dialog(onDismissRequest = { confirmCancel = false }) {
        Column(Modifier.background(VirlinColors.Background, RoundedCornerShape(20.dp)).padding(20.dp)) {
            Text("Cancel “${task.title}”?", fontSize = 15.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary)
            Spacer(Modifier.height(6.dp)); Text("It has subtasks. They stay as they are; only this task is cancelled and leaves the planned scope.", fontSize = 12.sp, color = VirlinColors.TextSecondary)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text("Keep", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextSecondary, modifier = Modifier.clickable(role = Role.Button) { confirmCancel = false }.padding(12.dp))
                Text("CANCEL TASK", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White,
                    modifier = Modifier.background(VirlinColors.TextPrimary, RoundedCornerShape(50)).testTag(TaskCancelConfirmTag).clickable(role = Role.Button) { vm.cancelTask(task.id); confirmCancel = false }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

// =====================================================================================

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
private fun ExecutionPreferenceRow(
    preference: ExecutionPreference,
    effective: EffectiveExecutionMode,
    allowInherit: Boolean,
    onSelect: (ExecutionPreference) -> Unit,
    onReset: () -> Unit
) {
    val inheritLabel = "Inherit — ${effective.name.lowercase().replaceFirstChar(Char::uppercase)}"
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        if (allowInherit) {
            val selected = preference == ExecutionPreference.INHERIT
            Text(
                inheritLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else VirlinColors.TextSecondary,
                modifier = Modifier
                    .background(if (selected) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
                    .border(1.dp, Hairline, RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onReset() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("execution_choice_inherit")
            )
        }
        listOf(
            "Human" to ExecutionPreference.HUMAN,
            "External" to ExecutionPreference.EXTERNAL
        ).forEach { (label, pref) ->
            val selected = preference == pref
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) Color.White else VirlinColors.TextSecondary,
                modifier = Modifier
                    .background(if (selected) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
                    .border(1.dp, Hairline, RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onSelect(pref) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("execution_choice_${pref.name.lowercase()}")
            )
        }
    }
    if (preference != ExecutionPreference.INHERIT && allowInherit) {
        Spacer(Modifier.height(4.dp))
        Text("Overrides parent · effective ${effective.name.lowercase()}", fontSize = 11.sp, color = VirlinColors.TextTertiary)
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

fun TaskStatus.label() = when (this) {
    TaskStatus.TODO -> "TO DO"; TaskStatus.IN_PROGRESS -> "IN PROGRESS"; TaskStatus.DONE -> "COMPLETED"; TaskStatus.CANCELLED -> "CANCELLED"
}

private fun stateSurface(s: WorkStreamState): Color = when (s) {
    WorkStreamState.FOCUS -> VirlinColors.FocusSurface; WorkStreamState.PROCESSING -> VirlinColors.ProcessingSurface
    WorkStreamState.CHECK -> VirlinColors.NeedsYouDue; WorkStreamState.READY -> VirlinColors.ReadySurface
    WorkStreamState.BLOCKED -> VirlinColors.NeedsYouOverdue; else -> Color.White
}
