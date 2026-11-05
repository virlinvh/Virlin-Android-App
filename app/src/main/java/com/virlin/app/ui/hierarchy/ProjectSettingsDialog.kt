package com.virlin.app.ui.hierarchy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    DEFAULTS("Defaults & workflow", "Project default execution and inheritance", Icons.Outlined.Tune),
    ORGANIZATION("Organization", "Organize workstreams as blocks", Icons.Outlined.AccountTree),
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
    data object Icon : SettingsPage
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
    onDeleteProject: (() -> Unit)? = null,
    iconEditor: (@Composable (onClose: () -> Unit) -> Unit)? = null,
) {
    // Section navigation belongs to this page, not a separate modal window.
    val palette = rememberProjectPalette(project.id)
    CompositionLocalProvider(LocalProjectPalette provides palette) {
    // serialize this sealed navigation contract during Activity recreation.
    var page: SettingsPage by remember(project.id) { mutableStateOf(SettingsPage.Categories) }
    fun back() {
        page = when (page) {
            SettingsPage.Rename, SettingsPage.Colors, SettingsPage.Icon -> SettingsPage.Category(SettingsCategory.IDENTITY)
            SettingsPage.Execution -> SettingsPage.Category(SettingsCategory.DEFAULTS)
            SettingsPage.Completed -> SettingsPage.Category(SettingsCategory.CONTENT)
            is SettingsPage.Category -> SettingsPage.Categories
            SettingsPage.Categories -> { onDismiss(); SettingsPage.Categories }
        }
    }

    Box(Modifier.fillMaxSize()) {
        BackHandler { back() }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag(ProjectSettingsTestTag),
            color = Color(0xFFFBFDFB),
        ) {
            Column(Modifier.fillMaxSize()) {
                SettingsHeader(project, page, onBack = { back() })
                HorizontalDivider(color = Color(0xFFE5ECE8))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    androidx.compose.animation.AnimatedContent(targetState = page,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 10 }) togetherWith fadeOut(tween(100))
                        }, label = "settings_section") { current ->
                    when (current) {
                        SettingsPage.Categories -> CategoryList { page = SettingsPage.Category(it) }
                        is SettingsPage.Category -> if (current.value == SettingsCategory.STORAGE) ProjectStorageSettings(project) else CategoryPage(
                            category = current.value,
                            project = project,
                            completedCount = projectTasks.count { it.status.isCompleted },
                            onRename = { page = SettingsPage.Rename },
                            onEditIcon = { if (iconEditor != null) page = SettingsPage.Icon else onEditIcon() },
                            onExecution = { page = SettingsPage.Execution },
                            onCompleted = { page = SettingsPage.Completed },
                            onCompleteProject = onCompleteProject,
                            onColors = { page = SettingsPage.Colors },
                            onDeleteProject = onDeleteProject,
                        )
                        SettingsPage.Rename -> RenamePage(project.title) { title -> onRename(title); page = SettingsPage.Categories }
                        SettingsPage.Execution -> ExecutionPage(project.defaultExecutionMode) { mode ->
                            onExecutionDefault(mode)
                        }
                        SettingsPage.Completed -> CompletedTasksPage(projectTasks.filter { it.status.isCompleted }, onRestoreTask)
                        SettingsPage.Colors -> ProjectColorsEditor(project.id) { page = SettingsPage.Category(SettingsCategory.IDENTITY) }
                        SettingsPage.Icon -> iconEditor?.invoke { page = SettingsPage.Category(SettingsCategory.IDENTITY) }
                    }
                    }
                    ScrollFade(Alignment.TopCenter)
                    ScrollFade(Alignment.BottomCenter)
                }
            }
        }
    }
}

}

private val settingsAccent: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF087848)
private val settingsText: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.text) } ?: VirlinColors.TextPrimary
private val settingsMuted: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.muted) } ?: VirlinColors.TextSecondary

/** Draft-only editing; repository writes happen exclusively through Save. */
@Composable
internal fun ProjectIconSettingsEditor(
    project: Project,
    onSelectBuiltIn: (String) -> Unit,
    onCustomImported: (String) -> Unit,
    onUseAuto: () -> Unit,
    onClose: () -> Unit,
) {
    var iconId by rememberSaveable(project.id) { mutableStateOf(project.iconId) }
    var iconPath by rememberSaveable(project.id) { mutableStateOf(project.iconPath) }
    val changed = iconId != project.iconId || iconPath != project.iconPath
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            com.virlin.app.ui.components.ProjectIconEditorSheet(
                project.copy(iconId = iconId, iconPath = iconPath),
                onSelectBuiltIn = { iconId = it; iconPath = null },
                onCustomImported = { iconPath = it },
                onRemoveCustom = { iconPath = null },
                onUseAuto = { iconId = null; iconPath = null },
                onDismiss = onClose, inline = true)
        }
        HorizontalDivider(color = Color(0xFFE5ECE8))
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            SettingsInlineActions(onCancel = onClose, enabled = changed, onSave = {
                when {
                    iconPath != null -> onCustomImported(iconPath!!)
                    iconId != null -> onSelectBuiltIn(iconId!!)
                    else -> onUseAuto()
                }
                onClose()
            })
        }
    }
}
private val settingsTint: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.tertiary) } ?: Color(0xFFEAF8F1)

@Composable
private fun SettingsHeader(project: Project, page: SettingsPage, onBack: () -> Unit) {
    val title = when (page) {
        SettingsPage.Categories -> "Project settings"
        is SettingsPage.Category -> page.value.title
        SettingsPage.Rename -> "Project name"
        SettingsPage.Execution -> "Default execution"
        SettingsPage.Completed -> "Completed tasks"
        SettingsPage.Colors -> "Project colors"
        SettingsPage.Icon -> "Project icon"
    }
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = if (page == SettingsPage.Categories) "Back to project" else "Back to project settings", tint = settingsAccent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = settingsText)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectIcon(project = project, size = 28.dp, decorative = true)
                Text(project.title, modifier = Modifier.weight(1f), fontSize = 12.sp, lineHeight = 18.sp,
                    color = settingsMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
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
    onDeleteProject: (() -> Unit)?,
) {
    var completing by rememberSaveable(project.id, category) { mutableStateOf(false) }
    if (completing) BackHandler { completing = false }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (category) {
            SettingsCategory.IDENTITY -> {
                item { SettingRow("Project name", project.title, onRename, leading = Icons.Outlined.Edit) }
                item { SettingRow("Project icon", "Built-in, automatic or your own image", onEditIcon, leading = Icons.Outlined.Image) }
                item { SettingRow("Project colors", "Presets, custom shades and live preview", onColors, leading = Icons.Outlined.Palette) }
                item { SettingRow("Project status", project.status.name.lowercase().replaceFirstChar { it.uppercase() }, null, leading = Icons.Outlined.CheckCircle) }
            }
            SettingsCategory.DEFAULTS -> {
                item { SettingRow("Default execution", if (project.defaultExecutionMode == EffectiveExecutionMode.EXTERNAL) "External" else "Internal", onExecution) }
            }
            SettingsCategory.ORGANIZATION -> {
                item {
                    val (enabled, setEnabled) = rememberWorkstreamBlocks(project.id)
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Organize workstreams as blocks", fontWeight = FontWeight.SemiBold)
                            }
                            Switch(enabled, setEnabled, modifier = Modifier.testTag("workstream_blocks_switch"),
                                colors = SwitchDefaults.colors(checkedTrackColor = settingsAccent, checkedThumbColor = readableOn(settingsAccent)))
                        }
                    }
                }
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
                            fontSize = 12.sp, color = settingsMuted)
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
                // Live storage tools are hosted directly in the settings content viewport.
            }
            SettingsCategory.MANAGEMENT -> {
                item {
                    SettingRow("Complete project", "Mark this project done", { completing = !completing })
                }
                if (completing) item {
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp))
                        .testTag("complete_project_inline_confirmation").padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Complete ${project.title}?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Mark this project as done using the existing completion action. This is not archive or deletion.",
                            color = settingsMuted, fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { completing = false }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Keep project") }
                            Button(onClick = { completing = false; onCompleteProject() },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("confirm_complete_project"),
                                colors = ButtonDefaults.buttonColors(containerColor = settingsAccent, contentColor = readableOn(settingsAccent))) { Text("Complete") }
                        }
                    }
                }
                item { PlannedRow("Archive project", "Hide without deleting") }
                item {
                    if (onDeleteProject != null) SettingRow("Delete project", "Permanently remove project and all its content", onDeleteProject, danger = true)
                    else PlannedRow("Delete project", "Permanent · confirmation required", danger = true)
                }
            }
        }
    }
}

@Composable
private fun SettingsInlineActions(onCancel: () -> Unit, enabled: Boolean = true, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("settings_inline_actions"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = settingsAccent)) { Text("Cancel") }
        Button(onClick = onSave, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = settingsAccent, contentColor = readableOn(settingsAccent))) { Text("Save changes") }
    }
}

@Composable
private fun RenamePage(current: String, onApply: (String) -> Unit) {
    var value by rememberSaveable(current) { mutableStateOf(current) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length <= 60) value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") },
            supportingText = { Text("${value.length} / 60") },
            singleLine = true,
        )
        Text("This name appears across the project, WorkStreams, tasks and Page.", fontSize = 12.sp, color = settingsMuted)
        if (value != current) {
            Spacer(Modifier.height(18.dp))
            SettingsInlineActions(onCancel = { value = current }, enabled = value.isNotBlank() && value.trim() != current,
                onSave = { onApply(value.trim()) })
        }
    }
}

@Composable
private fun ExecutionPage(current: EffectiveExecutionMode, onApply: (EffectiveExecutionMode) -> Unit) {
    var selected by rememberSaveable(current) { mutableStateOf(current) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Applies to existing and new workstreams, tasks and subtasks set to Inherit. An explicit choice on an item or its nearest parent takes priority and stays unchanged.", color = settingsMuted, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        SelectCard("Internal", "Work completed by you or your team", selected == EffectiveExecutionMode.HUMAN) { selected = EffectiveExecutionMode.HUMAN }
        Spacer(Modifier.height(10.dp))
        SelectCard("External", "Work handled by an outside person or process", selected == EffectiveExecutionMode.EXTERNAL) { selected = EffectiveExecutionMode.EXTERNAL }
        Spacer(Modifier.height(12.dp))
        Text("Saving changes responsibility only. It does not start external work automatically. Pause inherited external tracking before switching it to Internal.", color = settingsMuted, fontSize = 12.sp)
        if (selected != current) {
            Spacer(Modifier.height(14.dp))
            SettingsInlineActions(onCancel = { selected = current }, onSave = { onApply(selected) })
        }
    }
}

@Composable
private fun CompletedTasksPage(tasks: List<Task>, onRestore: (String) -> Unit) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (tasks.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No completed tasks to restore", color = settingsMuted)
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
                        Text(task.title, fontWeight = FontWeight.SemiBold, color = settingsText)
                        Text("Completed", fontSize = 11.sp, color = settingsMuted)
                    }
                }
            }
        }
        Text("Select tasks to restore them to To do.", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp, color = settingsMuted)
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
            Text(title, fontWeight = FontWeight.Bold, color = settingsText)
            Text(subtitle, fontSize = 11.5.sp, color = settingsMuted, lineHeight = 15.sp)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = settingsMuted)
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)?, danger: Boolean = false, leading: ImageVector? = null) {
    val modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(14.dp))
        .border(1.dp, Color(0xFFDDE6E1), RoundedCornerShape(14.dp))
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(14.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        leading?.let {
            Box(Modifier.padding(end = 12.dp).size(40.dp).background(settingsTint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(it, contentDescription = null, tint = settingsAccent, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = if (danger) Color(0xFF9B3C38) else settingsText)
            Text(value, fontSize = 11.5.sp, color = settingsMuted)
        }
        if (onClick != null) Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = settingsMuted)
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
            Text(title, fontWeight = FontWeight.SemiBold, color = if (danger) Color(0xFF9B3C38) else settingsText)
            Text(value, fontSize = 11.5.sp, color = settingsMuted)
        }
        Text("Planned", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = settingsMuted)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = settingsMuted)
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
        Column { Text(title, fontWeight = FontWeight.Bold, color = settingsText); Text(subtitle, fontSize = 12.sp, color = settingsMuted) }
    }
}

@Composable
private fun BoxScope.ScrollFade(alignment: Alignment) {
    Box(
        Modifier.align(alignment).fillMaxWidth().height(12.dp)
            .background(if (alignment == Alignment.TopCenter) Color(0xFFFBFDFB).copy(alpha = .86f) else Color(0xFFFBFDFB).copy(alpha = .86f))
    )
}
