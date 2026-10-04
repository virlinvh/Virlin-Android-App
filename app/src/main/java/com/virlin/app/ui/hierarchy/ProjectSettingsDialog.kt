package com.virlin.app.ui.hierarchy

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virlin.app.domain.model.EffectiveExecutionMode
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.ui.components.ProjectIcon
import com.virlin.app.ui.theme.VirlinColors

const val ProjectSettingsTestTag = "project_settings_dialog"

private enum class SettingsCategory(
    val title: String,
    val summary: String,
    val icon: ImageVector,
) {
    IDENTITY("Identity & status", "Name, icon, color and project status", Icons.Outlined.Badge),
    DEFAULTS("Defaults & workflow", "Execution, task state, completion and opening tab", Icons.Outlined.Tune),
    ORGANIZATION("Organization", "WorkStream icons, ordering and progress", Icons.Outlined.AccountTree),
    ATTENTION("Attention & reminders", "Alerts, overdue work and external checks", Icons.Outlined.NotificationsNone),
    TIMERS("Timers & display", "Focused / External timer rotation across this project", Icons.Outlined.Timer),
    CONTENT("Content & appearance", "Completed work, Mind map and Page defaults", Icons.Outlined.ViewQuilt),
    STORAGE("Storage & export", "Media usage, export and duplicate project", Icons.Outlined.Storage),
    MANAGEMENT("Project management", "Complete, archive and permanent deletion", Icons.Outlined.AdminPanelSettings),
}

private sealed interface SettingsPage {
    data object Categories : SettingsPage
    data class Category(val value: SettingsCategory) : SettingsPage
    data object Rename : SettingsPage
    data object Execution : SettingsPage
    data object Completed : SettingsPage
    data object Colors : SettingsPage
}

@Composable
fun ProjectSettingsDialog(
    project: Project,
    projectTasks: List<Task>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onEditIcon: () -> Unit,
    onExecutionDefault: (EffectiveExecutionMode) -> Unit,
    onRestoreTask: (String) -> Unit,
    onCompleteProject: () -> Unit,
) {
    // Navigation is modal-local UI state. Keeping it out of saveable state avoids attempting to
    val palette = rememberProjectPalette(project.id)
    CompositionLocalProvider(LocalProjectPalette provides palette) {
    // serialize this sealed navigation contract during Activity recreation.
    var page: SettingsPage by remember(project.id) { mutableStateOf(SettingsPage.Categories) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by animateFloatAsState(if (entered) 1f else .96f, tween(180), label = "settings-scale")
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(150), label = "settings-alpha")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 22.dp, vertical = 42.dp)
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .heightIn(min = 420.dp, max = 680.dp)
                .scale(scale).alpha(alpha)
                .testTag(ProjectSettingsTestTag),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFFFBFDFB),
            shadowElevation = 18.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                SettingsHeader(project, page, onBack = {
                    page = when (page) {
                        is SettingsPage.Category, SettingsPage.Rename, SettingsPage.Execution,
                        SettingsPage.Completed, SettingsPage.Colors -> SettingsPage.Categories
                        SettingsPage.Categories -> SettingsPage.Categories
                    }
                }, onDismiss = onDismiss)
                HorizontalDivider(color = Color(0xFFE5ECE8))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (val current = page) {
                        SettingsPage.Categories -> CategoryList { page = SettingsPage.Category(it) }
                        is SettingsPage.Category -> CategoryPage(
                            category = current.value,
                            project = project,
                            completedCount = projectTasks.count { it.status.isCompleted },
                            onRename = { page = SettingsPage.Rename },
                            onEditIcon = onEditIcon,
                            onExecution = { page = SettingsPage.Execution },
                            onCompleted = { page = SettingsPage.Completed },
                            onCompleteProject = onCompleteProject,
                            onColors = { page = SettingsPage.Colors },
                        )
                        SettingsPage.Rename -> RenamePage(project.title) { title -> onRename(title); page = SettingsPage.Categories }
                        SettingsPage.Execution -> ExecutionPage(project.defaultExecutionMode) { mode ->
                            onExecutionDefault(mode); page = SettingsPage.Categories
                        }
                        SettingsPage.Completed -> CompletedTasksPage(projectTasks.filter { it.status.isCompleted }, onRestoreTask)
                        SettingsPage.Colors -> ProjectColorsEditor(project.id) { page = SettingsPage.Categories }
                    }
                    ScrollFade(Alignment.TopCenter)
                    ScrollFade(Alignment.BottomCenter)
                }
                HorizontalDivider(color = Color(0xFFE5ECE8))
                if (page != SettingsPage.Colors) Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = settingsAccent),
                    ) { Text("Save changes") }
                }
            }
        }
    }
}

}

private val settingsAccent: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF087848)
private val settingsTint: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.tertiary) } ?: Color(0xFFEAF8F1)

@Composable
private fun SettingsHeader(project: Project, page: SettingsPage, onBack: () -> Unit, onDismiss: () -> Unit) {
    val title = when (page) {
        SettingsPage.Categories -> "Project settings"
        is SettingsPage.Category -> page.value.title
        SettingsPage.Rename -> "Project name"
        SettingsPage.Execution -> "Default execution"
        SettingsPage.Completed -> "Completed tasks"
        SettingsPage.Colors -> "Project colors"
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (page == SettingsPage.Categories) ProjectIcon(project = project, size = 42.dp, decorative = true)
        else IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "Back to project settings")
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary)
            Text(project.title, fontSize = 12.sp, color = VirlinColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Close project settings") }
    }
}

@Composable
private fun CategoryList(onOpen: (SettingsCategory) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(SettingsCategory.entries) { category ->
            SettingsCard(category.icon, category.title, category.summary, onClick = { onOpen(category) })
        }
    }
}

@Composable
private fun CategoryPage(
    category: SettingsCategory,
    project: Project,
    completedCount: Int,
    onRename: () -> Unit,
    onEditIcon: () -> Unit,
    onExecution: () -> Unit,
    onCompleted: () -> Unit,
    onCompleteProject: () -> Unit,
    onColors: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (category) {
            SettingsCategory.IDENTITY -> {
                item { SettingRow("Project name", project.title, onRename) }
                item { SettingRow("Project icon", "Choose default or custom", onEditIcon) }
                item { SettingRow("Project colors", "24 colors, shades and custom palette", onColors) }
                item { SettingRow("Project status", project.status.name.lowercase().replaceFirstChar { it.uppercase() }, null) }
            }
            SettingsCategory.DEFAULTS -> {
                item { SettingRow("Default execution", if (project.defaultExecutionMode == EffectiveExecutionMode.EXTERNAL) "External" else "Internal", onExecution) }
                item { PlannedRow("Default task execution", "Inherit project") }
                item { PlannedRow("Default task state", "To do") }
                item { PlannedRow("Completion behavior", "Stay on current page") }
                item { PlannedRow("Opening tab", "Overview · Page shown when this project opens") }
            }
            SettingsCategory.ORGANIZATION -> {
                item { PlannedRow("WorkStream icons", "Automatic") }
                item { PlannedRow("WorkStream ordering", "Manual") }
                item { PlannedRow("Task ordering", "Manual") }
                item { PlannedRow("Progress calculation", "Task count") }
            }
            SettingsCategory.ATTENTION -> {
                item { PlannedRow("Project reminders", "Standard") }
                item { PlannedRow("Overdue alerts", "On") }
                item { PlannedRow("External-work checks", "On") }
                item { PlannedRow("Notification intensity", "Standard") }
            }
            SettingsCategory.TIMERS -> {
                item {
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimerDisplaySettings("project:${project.id}")
                        Text("Applies to all workstreams, tasks and subtasks in this project. Only the display changes; running work and recorded time stay unchanged. Changes are saved immediately.",
                            fontSize = 12.sp, color = VirlinColors.TextSecondary)
                    }
                }
            }
            SettingsCategory.CONTENT -> {
                item { SettingRow("Project colors", "Palette, text and surfaces", onColors) }
                item { SettingRow("Completed tasks", "$completedCount available to restore", onCompleted) }
                item { PlannedRow("Completed work visibility", "Shown") }
                item { PlannedRow("Completed-work retention", "Keep indefinitely") }
                item { PlannedRow("Mind-map appearance", "Automatic") }
                item { PlannedRow("Page defaults", "Standard") }
            }
            SettingsCategory.STORAGE -> {
                item { PlannedRow("File and media storage", "Review usage") }
                item { PlannedRow("Export project", "PDF, Markdown or backup") }
                item { PlannedRow("Duplicate project", "Structure or full copy") }
            }
            SettingsCategory.MANAGEMENT -> {
                item { SettingRow("Complete project", "Mark this project done", onCompleteProject) }
                item { PlannedRow("Archive project", "Hide without deleting") }
                item { PlannedRow("Delete project", "Permanent · confirmation required", danger = true) }
            }
        }
    }
}

@Composable
private fun RenamePage(current: String, onApply: (String) -> Unit) {
    var value by rememberSaveable(current) { mutableStateOf(current) }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= 60) value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") },
            supportingText = { Text("${value.length} / 60") },
            singleLine = true,
        )
        Text("This name appears across the project, WorkStreams, tasks and Page.", fontSize = 12.sp, color = VirlinColors.TextSecondary)
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { onApply(value.trim()) },
            enabled = value.isNotBlank() && value.trim() != current,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = settingsAccent),
        ) { Text("Apply name") }
    }
}

@Composable
private fun ExecutionPage(current: EffectiveExecutionMode, onApply: (EffectiveExecutionMode) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(current) }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Text("Choose how new WorkStreams and tasks begin. They can still override this individually.", color = VirlinColors.TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        SelectCard("Internal", "Work completed by you or your team", selected == EffectiveExecutionMode.HUMAN) { selected = EffectiveExecutionMode.HUMAN }
        Spacer(Modifier.height(10.dp))
        SelectCard("External", "Work handled by an outside person or process", selected == EffectiveExecutionMode.EXTERNAL) { selected = EffectiveExecutionMode.EXTERNAL }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onApply(selected) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = settingsAccent),
        ) { Text("Apply default") }
    }
}

@Composable
private fun CompletedTasksPage(tasks: List<Task>, onRestore: (String) -> Unit) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (tasks.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No completed tasks to restore", color = VirlinColors.TextSecondary)
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                val checked = task.id in selected
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) {
                        selected = if (checked) selected - task.id else selected + task.id
                    }.background(Color.White, RoundedCornerShape(14.dp))
                        .border(1.dp, Color(0xFFDDE6E1), RoundedCornerShape(14.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(task.title, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary)
                        Text("Completed", fontSize = 11.sp, color = VirlinColors.TextSecondary)
                    }
                }
            }
        }
        Text("Select tasks to restore them to To do.", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = VirlinColors.TextSecondary)
        Button(
            onClick = { confirming = true }, enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = settingsAccent),
        ) { Text("Restore selected") }
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Restore ${selected.size} ${if (selected.size == 1) "task" else "tasks"}?") },
        text = { Text("They will return to To do. Nothing else will change.") },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {
            selected.forEach(onRestore); selected = emptySet(); confirming = false
        }) { Text(if (selected.size == 1) "Restore task" else "Restore tasks") } },
    )
}

@Composable
private fun SettingsCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFDDE6E1), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).background(settingsTint, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = settingsAccent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary)
            Text(subtitle, fontSize = 11.5.sp, color = VirlinColors.TextSecondary, lineHeight = 15.sp)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = VirlinColors.TextSecondary)
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)?) {
    val modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp))
        .border(1.dp, Color(0xFFDDE6E1), RoundedCornerShape(14.dp))
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(14.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary)
            Text(value, fontSize = 11.5.sp, color = VirlinColors.TextSecondary)
        }
        if (onClick != null) Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = VirlinColors.TextSecondary)
    }
}

@Composable
private fun PlannedRow(title: String, value: String, danger: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFF7F9F8), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFE2E8E5), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (danger) Color(0xFF9B3C38) else VirlinColors.TextPrimary)
            Text(value, fontSize = 11.5.sp, color = VirlinColors.TextSecondary)
        }
        Text("Planned", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextTertiary)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = VirlinColors.TextTertiary)
    }
}

@Composable
private fun SelectCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) settingsTint else Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, if (selected) settingsAccent else Color(0xFFDDE6E1), RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(10.dp))
        Column { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, fontSize = 12.sp, color = VirlinColors.TextSecondary) }
    }
}

@Composable
private fun BoxScope.ScrollFade(alignment: Alignment) {
    Box(
        Modifier.align(alignment).fillMaxWidth().height(12.dp)
            .background(if (alignment == Alignment.TopCenter) Color(0xFFFBFDFB).copy(alpha = .86f) else Color(0xFFFBFDFB).copy(alpha = .86f))
    )
}
