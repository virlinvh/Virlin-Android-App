package com.virlin.app.ui.hierarchy

// Supplied drop-in page. Adapted only where the app already owns the thing being drawn:
//  - the project's real ProjectIcon and per-row icon slots replace the placeholder glyphs,
//  - the app's existing test tags (project detail, stream rows, task rows) are carried over,
//  - "Archive project" is wired to the existing COMPLETE PROJECT action and labelled as such,
//    because the domain has no archive state (see the integration notes in ProjectDetailScreen).
// It still owns no data: every action is a callback the host screen fills in.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// UI models only. Adapt your existing entities into these models in the ViewModel.
data class ProjectPageUi(
    val id: String,
    val title: String,
    val description: String,
    /** The project's real icon, drawn by the host with the existing ProjectIcon component. */
    val icon: (@Composable () -> Unit)? = null,
    /** Opens the existing project-icon editor. */
    val onEditIcon: () -> Unit = {},
    val workstreams: List<WorkstreamUi>,
    val standaloneTasks: List<ProjectTaskUi>,
    val documents: List<ProjectDocumentUi>,
    /** The Activity tab's own content, given a bounded height by this page. */
    val activityContent: @Composable () -> Unit = {},
    /** The Knowledge tab's own content, given a bounded height by this page. */
    val knowledgeContent: @Composable () -> Unit = {},
    val executionDefaultLabel: String = "Human",
    /** The app has no archive state; the menu offers the existing completion action instead. */
    val archiveLabel: String = "Complete project"
) {
    val totalTasks: Int get() = workstreams.sumOf { it.totalTasks } + standaloneTasks.size
    val completedTasks: Int get() = workstreams.sumOf { it.completedTasks } + standaloneTasks.count { it.completed }
    val progress: Float get() = if (totalTasks == 0) 0f else completedTasks.toFloat() / totalTasks
}

data class WorkstreamUi(
    val id: String,
    val title: String,
    val completedTasks: Int,
    val totalTasks: Int,
    val kind: WorkstreamKind = WorkstreamKind.GENERAL,
    val state: WorkstreamState = WorkstreamState.NORMAL,
    /** The app's own state wording ("Processing", "Blocked", …) shown under the title. */
    val stateLabel: String = "",
    /** True when the row has no task structure at all, so it reads "No tasks yet", never "0%". */
    val unstructured: Boolean = false
)

enum class WorkstreamKind { DESIGN, ASSISTANT, BUILD, CLOUD, GENERAL }
enum class WorkstreamState { NORMAL, IN_PROGRESS, BLOCKED }
data class ProjectTaskUi(val id: String, val title: String, val completed: Boolean)
data class ProjectDocumentUi(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: (@Composable () -> Unit)? = null
)
private enum class ProjectTab { OVERVIEW, KNOWLEDGE, ACTIVITY }

// Keep navigation and mutations in the host app. This component never writes data itself.
data class ProjectPageActions(
    val onBack: () -> Unit,
    val onSearch: () -> Unit,
    val onProgress: () -> Unit,
    val onOpenWorkstream: (String) -> Unit,
    val onAddWorkstream: () -> Unit,
    /** Opens the project's mind map; the Overview stays in the back stack. */
    val onOpenMap: () -> Unit = {},
    val onOpenTask: (String) -> Unit,
    val onToggleTask: (String) -> Unit,
    val onTaskMenu: (String) -> Unit,
    val onAddTask: () -> Unit,
    val onOpenDocument: (String) -> Unit,
    val onAddDocument: () -> Unit,
    val onProjectSettings: () -> Unit,
    val onEditProject: () -> Unit,
    val onManageWorkstreams: () -> Unit,
    val onArchiveProject: () -> Unit
)

private object P {
    val ink = Color(0xFF17202C)
    val secondary = Color(0xFF626D80)
    val line = Color(0xFFE4E8EF)
    val leaf = Color(0xFF79BF39)
    val leafDark = Color(0xFF367C19) // Only for small readable action text.
    val leafTrack = Color(0xFFE6EFDF)
    val amber = Color(0xFFB46A00)
    val amberLine = Color(0xFFF2D69A)
    val amberWash = Color(0xFFFFFCF3)
    val coral = Color(0xFFD34C3C)
    val ivory = Color(0xFFFAF8F4)
}

@Composable
fun VirlinProjectPage(ui: ProjectPageUi, actions: ProjectPageActions, modifier: Modifier = Modifier) {
    // Saved, not just remembered: opening a capture from Activity and coming back must return
    // to the tab the viewer was on, not to Overview.
    var tabName by androidx.compose.runtime.saveable.rememberSaveable(ui.id) {
        mutableStateOf(ProjectTab.OVERVIEW.name)
    }
    val tab = ProjectTab.entries.firstOrNull { it.name == tabName } ?: ProjectTab.OVERVIEW
    var menuOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(Color.White).testTag(ProjectDetailTag)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects", tint = P.ink)
            }
            Text("Projects", style = TextStyle(fontSize = 15.sp, color = P.ink))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = actions.onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search this project", tint = P.ink)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Project menu", tint = P.ink)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Column {
                            Text("Project settings")
                            Text("Execution default: ${ui.executionDefaultLabel}", fontSize = 12.sp, color = P.secondary)
                        } },
                        onClick = { menuOpen = false; actions.onProjectSettings() }
                    )
                    DropdownMenuItem(text = { Text("Edit project") }, onClick = {
                        menuOpen = false; actions.onEditProject()
                    })
                    DropdownMenuItem(text = { Text("Search this project") }, onClick = {
                        menuOpen = false; actions.onSearch()
                    })
                    DropdownMenuItem(text = { Text("Manage workstreams") }, onClick = {
                        menuOpen = false; actions.onManageWorkstreams()
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(ui.archiveLabel) }, onClick = {
                        menuOpen = false; actions.onArchiveProject()
                    })
                }
            }
        }

        // Knowledge and Activity both own a lazy list, so both get a bounded height.
        val bounded = tab == ProjectTab.ACTIVITY || tab == ProjectTab.KNOWLEDGE
        Column(
            if (bounded) Modifier.weight(1f) else Modifier.verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.icon != null) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClickLabel = "Change ${ui.title} icon", onClick = ui.onEditIcon)
                            .testTag(ProjectDetailIconTag),
                        contentAlignment = Alignment.Center
                    ) { ui.icon.invoke() }
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        ui.title,
                        style = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold, color = P.ink),
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    if (ui.description.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            ui.description, style = TextStyle(fontSize = 15.sp, color = P.secondary),
                            maxLines = 3, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            // Borderless and background-free, as in the approved mockup.
            Row(
                Modifier.fillMaxWidth().clickable(onClick = actions.onProgress)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProgressRing(ui.progress, Modifier.size(74.dp))
                Spacer(Modifier.width(22.dp))
                Box(Modifier.height(44.dp).width(1.dp).background(P.line))
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    if (ui.totalTasks == 0) {
                        Text("No tasks yet", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = P.ink)
                        Text("Add a workstream or a task", fontSize = 14.sp, color = P.secondary)
                    } else {
                        Text("${ui.completedTasks} of ${ui.totalTasks} tasks", fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, color = P.ink, maxLines = 1)
                        Text("${ui.totalTasks - ui.completedTasks} remaining", fontSize = 14.sp, color = P.secondary)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = P.ink)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                ProjectTab.entries.forEach { item ->
                    // Each tab takes an equal share; the underline fills ITS share, not the row
                    // (without the weight the first tab's fillMaxWidth ate the other two).
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(role = Role.Tab) { tabName = item.name }
                            .padding(horizontal = 8.dp)
                            .semantics { selected = tab == item }
                    ) {
                        Text(
                            item.name.lowercase().replaceFirstChar { it.uppercase() },
                            fontSize = 16.sp,
                            color = if (tab == item) P.leafDark else P.secondary,
                            fontWeight = if (tab == item) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == item) P.leaf else Color.Transparent))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = P.line)
            when (tab) {
                ProjectTab.OVERVIEW -> Column(Modifier.padding(horizontal = 20.dp)) {
                    Spacer(Modifier.height(19.dp))
                    OverviewBody(ui, actions)
                    Spacer(Modifier.height(28.dp))
                }
                // Bounded height, no outer padding: the library brings its own layout.
                ProjectTab.KNOWLEDGE -> Box(Modifier.weight(1f)) { ui.knowledgeContent() }
                // Bounded height, no outer padding: Activity brings its own layout.
                ProjectTab.ACTIVITY -> Box(Modifier.weight(1f)) { ui.activityContent() }
            }
        }
    }
}

@Composable
private fun ProgressRing(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 7.dp.toPx()
            val inset = width / 2
            val diameter = size.minDimension - width
            drawArc(P.leafTrack, -90f, 360f, false, Offset(inset, inset),
                androidx.compose.ui.geometry.Size(diameter, diameter), style = Stroke(width, cap = StrokeCap.Round))
            if (progress > 0f) drawArc(P.leaf, -90f, 360f * progress.coerceIn(0f, 1f), false,
                Offset(inset, inset), androidx.compose.ui.geometry.Size(diameter, diameter),
                style = Stroke(width, cap = StrokeCap.Round))
        }
        Text("${(progress * 100).roundToInt()}%", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = P.ink)
    }
}

@Composable
private fun SectionHeading(title: String, action: String, tag: String? = null, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = P.ink, modifier = Modifier.weight(1f))
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .clickable(onClick = onAction)
                .defaultMinSize(minHeight = 44.dp)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = P.leafDark, modifier = Modifier.size(19.dp))
            Text(action, color = P.leafDark, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun OverviewBody(ui: ProjectPageUi, actions: ProjectPageActions) {
    // The app's existing tag, so the hierarchy journey still finds this control.
    SectionHeading("Workstreams", "New", AddWorkStreamButtonTag, actions.onAddWorkstream)
    Spacer(Modifier.height(7.dp))
    if (ui.workstreams.isEmpty()) {
        Text("No workstreams yet", fontSize = 13.sp, color = P.secondary)
        Spacer(Modifier.height(4.dp))
    }
    ui.workstreams.forEach { workstream ->
        WorkstreamRow(workstream, onClick = { actions.onOpenWorkstream(workstream.id) })
        Spacer(Modifier.height(8.dp))
    }
    Spacer(Modifier.height(13.dp))
    SectionHeading("Standalone tasks", "Add", AddTaskButtonTag, actions.onAddTask)
    Spacer(Modifier.height(4.dp))
    if (ui.standaloneTasks.isEmpty()) {
        Text("None", fontSize = 13.sp, color = P.secondary)
    } else Surface(shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, P.line), color = Color.White) {
        Column {
            ui.standaloneTasks.forEachIndexed { index, task ->
                Row(
                    Modifier.fillMaxWidth().height(48.dp).testTag(taskRowTag(task.id)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { actions.onToggleTask(task.id) }, modifier = Modifier.testTag(taskCompleteTag(task.id))) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape)
                                .background(if (task.completed) P.leaf else Color.White)
                                .then(if (task.completed) Modifier else Modifier.background(Color.White)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (task.completed) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(17.dp))
                            else Canvas(Modifier.fillMaxSize()) {
                                drawCircle(P.secondary, style = Stroke(1.4.dp.toPx()))
                            }
                        }
                    }
                    Text(
                        task.title, modifier = Modifier.weight(1f).clickable { actions.onOpenTask(task.id) },
                        fontSize = 15.sp, color = if (task.completed) P.secondary else P.ink,
                        textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = { actions.onTaskMenu(task.id) }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options for ${task.title}", tint = P.secondary)
                    }
                }
                if (index != ui.standaloneTasks.lastIndex) HorizontalDivider(Modifier.padding(start = 47.dp), color = P.line)
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    KnowledgeBody(ui, actions, showHeading = true)
}

@Composable
private fun WorkstreamRow(item: WorkstreamUi, onClick: () -> Unit) {
    val active = item.state == WorkstreamState.IN_PROGRESS
    val blocked = item.state == WorkstreamState.BLOCKED
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("stream_row_${item.id}"),
        shape = RoundedCornerShape(12.dp),
        color = if (active) P.amberWash else Color.White,
        border = BorderStroke(1.dp, if (active) P.amberLine else P.line)
    ) {
        Row(Modifier.height(66.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(P.ivory),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when (item.kind) {
                        WorkstreamKind.DESIGN -> "✎"
                        WorkstreamKind.ASSISTANT -> "▢"
                        WorkstreamKind.BUILD -> "◇"
                        WorkstreamKind.CLOUD -> "☁"
                        WorkstreamKind.GENERAL -> "▣"
                    }, fontSize = 25.sp, color = P.ink
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = P.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.unstructured) "No tasks yet"
                        else "${item.completedTasks} of ${item.totalTasks} tasks complete",
                        fontSize = 12.sp, color = P.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (blocked) Text(" · Blocked", fontSize = 12.sp, color = P.coral)
                    else if (item.stateLabel.isNotBlank() && !active) {
                        Text(" · ${item.stateLabel}", fontSize = 12.sp, color = P.secondary, maxLines = 1)
                    }
                }
            }
            if (active) {
                Text("In progress", color = P.amber, fontSize = 11.sp,
                    modifier = Modifier.background(Color(0xFFFFF2D7), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 4.dp))
                Spacer(Modifier.width(7.dp))
            }
            if (!item.unstructured) {
                val ratio = if (item.totalTasks == 0) 0 else (100f * item.completedTasks / item.totalTasks).roundToInt()
                Text("$ratio%", fontSize = 13.sp, color = P.ink, fontWeight = FontWeight.Medium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = P.ink, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun KnowledgeBody(ui: ProjectPageUi, actions: ProjectPageActions, showHeading: Boolean) {
    SectionHeading("Project knowledge", "Add", onAction = actions.onAddDocument)
    if (!showHeading) {
        Spacer(Modifier.height(2.dp))
        Text("Documents, prompts, images and audio for this project", color = P.secondary, fontSize = 13.sp)
    }
    Spacer(Modifier.height(5.dp))
    if (ui.documents.isEmpty()) {
        Text("Nothing saved to this project yet", fontSize = 13.sp, color = P.secondary)
        Spacer(Modifier.height(4.dp))
    }
    ui.documents.forEach { doc ->
        Surface(
            onClick = { actions.onOpenDocument(doc.id) }, modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
            shape = RoundedCornerShape(11.dp), color = Color.White,
            border = BorderStroke(1.dp, P.line)
        ) {
            Row(Modifier.height(53.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(Color(0xFFFFF8E6), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    doc.icon?.invoke() ?: Text("▤", color = P.ink, fontSize = 22.sp)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1)
                    Text(doc.subtitle, fontSize = 11.sp, color = P.secondary, maxLines = 1)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = P.secondary)
            }
        }
    }
}

