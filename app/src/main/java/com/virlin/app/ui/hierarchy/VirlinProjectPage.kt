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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val externalSeconds: Long = 0,
    val focusSeconds: Long = 0,
    val timersRunning: Boolean = false,
    val timerControlBusy: Boolean = false,
    val timerControlMessage: String? = null,
    val searchItems: List<ProjectSearchItem> = emptyList(),
    val searchTasks: List<StreamTaskUi> = emptyList()
) {
    val totalTasks: Int get() = workstreams.sumOf { it.totalTasks } + standaloneTasks.sumOf { it.leafCount }
    val completedTasks: Int get() = workstreams.sumOf { it.completedTasks } + standaloneTasks.sumOf { it.completedLeafCount }
    val progress: Float get() = if (totalTasks == 0) 0f else completedTasks.toFloat() / totalTasks
    val humanActiveCount: Int get() = workstreams.sumOf { it.humanRunCount ?: if (it.state == WorkstreamState.HUMAN_FOCUS) 1 else 0 }
    val externalActiveCount: Int get() = workstreams.sumOf { it.externalRunCount ?: if (it.state == WorkstreamState.EXTERNAL_RUNNING) 1 else 0 }
}

data class WorkstreamUi(
    val id: String,
    val title: String,
    val completedTasks: Int,
    val totalTasks: Int,
    val kind: WorkstreamKind = WorkstreamKind.GENERAL,
    val state: WorkstreamState = WorkstreamState.READY,
    /** The app's own state wording ("Processing", "Blocked", …) shown under the title. */
    val stateLabel: String = "",
    /** Exact concurrent item count when [state] is [WorkstreamState.MULTI_RUN]. */
    val activeRunCount: Int = 0,
    val humanRunCount: Int? = null,
    val externalRunCount: Int? = null,
    /** True when the row has no task structure at all, so it reads "No tasks yet", never "0%". */
    val unstructured: Boolean = false
)

enum class WorkstreamKind { DESIGN, ASSISTANT, BUILD, CLOUD, GENERAL }
/** Read-only rendering states. Domain state remains authoritative in HierarchyScreens. */
enum class WorkstreamState {
    HUMAN_FOCUS, EXTERNAL_RUNNING, MULTI_RUN, NEEDS_ATTENTION, READY, WAITING, BLOCKED, PAUSED, COMPLETE
}
data class ProjectTaskUi(val id: String, val title: String, val completed: Boolean,
    val row: StreamTaskUi? = null, val leafCount: Int = 1, val completedLeafCount: Int = if (completed) 1 else 0)
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
    val onWorkstreamControls: (String) -> Unit = {},
    val onReorderWorkstream: (String, String?) -> Unit = { _, _ -> },
    val onToggleTimers: (() -> Unit)? = null
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
    val focus = Color(0xFF1FA676)
    val external = Color(0xFF2F6BDE)
    val externalLight = Color(0xFF49A9FF)
    val parallel = Color(0xFF1AA6B7)
    val attention = Color(0xFFE6A400)
    val inactive = Color(0xFFBCC6C2)
    val executionWash = Color(0xFFF3F8F5)
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
                    DropdownMenuItem(text = { Text("Search this project") }, onClick = {
                        menuOpen = false; actions.onSearch()
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
    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ui.standaloneTasks.forEachIndexed { index, task ->
                ProjectStandaloneTaskCard(task, index, actions)
            }
    }
    Spacer(Modifier.height(16.dp))
    KnowledgeBody(ui, actions, showHeading = true)
}

@Composable
private fun WorkstreamRow(item: WorkstreamUi, onClick: () -> Unit) {
    val blocked = item.state == WorkstreamState.BLOCKED
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("stream_row_${item.id}"),
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(1.dp, P.line)
    ) {
        Row(Modifier.defaultMinSize(minHeight = 76.dp).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            ExecutionDial(item.state, Modifier.size(48.dp), item.activeRunCount)
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
                    else if (item.stateLabel.isNotBlank()) {
                        Text(" · ${item.stateLabel}", fontSize = 12.sp, color = P.secondary, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(5.dp))
                ExecutionBars(item.state)
            }
            StatePill(item.state)
            if (item.state != WorkstreamState.READY) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExecutionLegend(human: Int, external: Int, multiRun: Int = 0, review: Int = 0, projectId: String = "") {
    var expanded by rememberSaveable(projectId) { mutableStateOf(false) }
    val palette = LocalProjectPalette.current
    val accent = palette?.let { Color(it.secondary) } ?: Color(0xFF007B58)
    Surface(shape = RoundedCornerShape(14.dp), color = palette?.let { Color(it.tertiary) } ?: P.executionWash) {
        Column(Modifier.fillMaxWidth().testTag("execution_summary")) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(palette?.let { Color(it.tertiary) } ?: Color(0xFFDDF3EA), CircleShape)
                    .testTag("execution_pulse_icon"), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(18.dp)) {
                        val pulse = Path().apply {
                            moveTo(0f, size.height * 0.52f)
                            lineTo(size.width * 0.22f, size.height * 0.52f)
                            lineTo(size.width * 0.34f, size.height * 0.18f)
                            lineTo(size.width * 0.49f, size.height * 0.82f)
                            lineTo(size.width * 0.64f, size.height * 0.36f)
                            lineTo(size.width * 0.76f, size.height * 0.52f)
                            lineTo(size.width, size.height * 0.52f)
                        }
                        drawPath(pulse, accent, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("Execution", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = palette?.let { Color(it.text) } ?: P.ink,
                    modifier = Modifier.weight(1f))
                Text("${human + external} active", fontSize = 11.sp, color = palette?.let { Color(it.muted) } ?: P.secondary)
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(44.dp)
                    .testTag("execution_disclosure").semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (expanded) "Hide execution details" else "Show execution details",
                        tint = palette?.let { Color(it.text) } ?: P.ink, modifier = Modifier.size(22.dp))
                }
            }
            if (expanded) FlowRow(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                .testTag("execution_details"), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CountChip("Focus", human, Color(0xFF009F73), Color(0xFFE7F7F0))
                CountChip("Running", external, Color(0xFF2864F0), Color(0xFFE8F0FF))
                CountChip("Multi-run", multiRun, Color(0xFF7452E8), Color(0xFFF0ECFF))
                CountChip("Review", review, Color(0xFFAA7500), Color(0xFFFFF3D5))
            }
        }
    }
}

@Composable
private fun CountChip(label: String, count: Int, color: Color, background: Color) {
    Row(Modifier.background(background, RoundedCornerShape(50))
        .semantics(mergeDescendants = true) { contentDescription = "$label $count" }
        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Box(Modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .background(color.copy(alpha = 0.10f), CircleShape).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center) {
            Text("$count", color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Smooth viewport-edge envelope for each copy of the travelling wave dot. */
internal fun flowingWaveHeadOpacity(progress: Float): Float {
    if (progress <= 0f || progress >= 1f) return 0f
        val edge = .14f
    fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    return smooth(progress / edge) * smooth((1f - progress) / edge)
}

@Composable
internal fun ExecutionDial(state: WorkstreamState, modifier: Modifier = Modifier, activeRunCount: Int = 0) {
    if (state == WorkstreamState.PAUSED) {
        PausedWorkIcon(modifier)
        return
    }
    if (state == WorkstreamState.WAITING || state == WorkstreamState.NEEDS_ATTENTION) {
        WaitingReviewIcon(state == WorkstreamState.NEEDS_ATTENTION, modifier)
        return
    }
    if (state == WorkstreamState.READY) {
        ReadyWorkIcon(modifier, P.inactive)
        return
    }
    val animated = state == WorkstreamState.HUMAN_FOCUS ||
        state == WorkstreamState.EXTERNAL_RUNNING ||
        state == WorkstreamState.MULTI_RUN ||
        state == WorkstreamState.NEEDS_ATTENTION ||
        state == WorkstreamState.BLOCKED
    val transition = rememberInfiniteTransition(label = "execution-dial")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = if (animated) 360f else 0f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "dial-rotation"
    )
    val shimmer by transition.animateFloat(
        initialValue = 0.25f, targetValue = if (animated) 1f else 0.25f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dial-shimmer"
    )
    val color = when (state) {
        WorkstreamState.HUMAN_FOCUS -> P.focus
        WorkstreamState.EXTERNAL_RUNNING -> P.external
        WorkstreamState.MULTI_RUN -> P.parallel
        WorkstreamState.NEEDS_ATTENTION -> P.attention
        else -> P.inactive
    }
    val dialDescription = when (state) {
        WorkstreamState.MULTI_RUN -> "${activeRunCount.coerceAtLeast(2)} active tasks, multi run"
        else -> state.name.lowercase().replace('_', ' ')
    }
    Box(modifier.semantics { contentDescription = dialDescription }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 3.dp.toPx()
            val diameter = size.minDimension - inset * 2
            val bounds = androidx.compose.ui.geometry.Size(diameter, diameter)
            when (state) {
                WorkstreamState.HUMAN_FOCUS -> {
                    // Four separate aperture corners breathe inward around the focused item.
                    // It reads as attention rather than a circular loading indicator.
                    val travel = (1f - shimmer) * 2.5.dp.toPx()
                    val left = size.width * .17f + travel
                    val right = size.width * .83f - travel
                    val top = size.height * .17f + travel
                    val bottom = size.height * .83f - travel
                    val arm = size.minDimension * .18f
                    val cornerStroke = Stroke(4.4.dp.toPx(), cap = StrokeCap.Round)
                    val cornerColors = listOf(
                        P.focus.copy(alpha = .72f + shimmer * .28f),
                        Color(0xFF32B894).copy(alpha = .66f + shimmer * .34f),
                        Color(0xFF42C49D).copy(alpha = .62f + shimmer * .38f),
                        P.focus.copy(alpha = .72f + shimmer * .28f)
                    )
                    drawLine(cornerColors[0], Offset(left, top + arm), Offset(left, top), strokeWidth = cornerStroke.width, cap = StrokeCap.Round)
                    drawLine(cornerColors[0], Offset(left, top), Offset(left + arm, top), strokeWidth = cornerStroke.width, cap = StrokeCap.Round)
                    drawLine(cornerColors[1], Offset(right - arm, top), Offset(right, top), strokeWidth = cornerStroke.width, cap = StrokeCap.Round)
                    drawLine(cornerColors[1], Offset(right, top), Offset(right, top + arm), strokeWidth = cornerStroke.width, cap = StrokeCap.Round)
                    drawLine(cornerColors[2], Offset(right, bottom - arm), Offset(right, bottom), strokeWidth = cornerStroke.width, cap = StrokeCap.Round)
                    drawLine(cornerColors[2], Offset(right, bottom), Offset(right - arm, bottom), strokeWidth = cornerStroke.width, cap = StrokeCap.Round)
                    drawLine(cornerColors[3], Offset(left + arm, bottom), Offset(left, bottom), strokeWidth = cornerStroke.width, cap = StrokeCap.Round)
                    drawLine(cornerColors[3], Offset(left, bottom), Offset(left, bottom - arm), strokeWidth = cornerStroke.width, cap = StrokeCap.Round)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val diamondRadius = (3.1f + shimmer * .8f).dp.toPx()
                    val diamond = Path().apply {
                        moveTo(center.x, center.y - diamondRadius)
                        lineTo(center.x + diamondRadius, center.y)
                        lineTo(center.x, center.y + diamondRadius)
                        lineTo(center.x - diamondRadius, center.y)
                        close()
                    }
                    drawCircle(P.focus.copy(alpha = .07f + shimmer * .09f), 10.dp.toPx(), center)
                    drawPath(diamond, P.parallel.copy(alpha = .55f + shimmer * .45f))
                    drawCircle(Color.White.copy(alpha = .72f), .8.dp.toPx(), center)
                }
                WorkstreamState.EXTERNAL_RUNNING -> {
                    // Three offset waves flow horizontally; the bright head travels with them.
                    val phase = angle / 360f
                    repeat(3) { lane ->
                        val path = Path()
                        val baseline = size.height * (.36f + lane * .14f)
                        val amplitude = size.height * .10f
                        for (step in 0..24) {
                            val x = size.width * step / 24f
                            val wave = sin((step / 24f * Math.PI * 2.0 + phase * Math.PI * 2.0 + lane * .75)).toFloat()
                            val y = baseline + wave * amplitude
                            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, if (lane == 1) P.external else P.externalLight.copy(alpha = .42f + lane * .18f),
                            style = Stroke((3.8f - lane * .65f).dp.toPx(), cap = StrokeCap.Round))
                    }
                    // Periodic copies cross opposite edges together. Each head feathers out
                    // before leaving and the next feathers in, so the restart is invisible.
                    for (copy in -1..2) {
                        val progress = phase + copy
                        val position = progress * .86f
                        val opacity = flowingWaveHeadOpacity(position)
                        if (opacity <= 0f) continue
                        val head = Offset(
                            size.width * position,
                            size.height * .50f +
                                sin((progress * Math.PI * 4.0)).toFloat() * size.height * .10f,
                        )
                        drawCircle(P.externalLight.copy(alpha = .20f * opacity), 6.dp.toPx(), head)
                        drawCircle(P.externalLight.copy(alpha = opacity), 2.6.dp.toPx(), head)
                    }
                }
                WorkstreamState.MULTI_RUN -> {
                    // Independent lanes orbit one shared center. Each ribbon uses the established
                    // execution palette and its own speed/direction, so this never reads as a
                    // single spinner recolored four times.
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val ribbonBounds = androidx.compose.ui.geometry.Size(diameter * .82f, diameter * .72f)
                    val ribbonTopLeft = Offset((size.width - ribbonBounds.width) / 2f, (size.height - ribbonBounds.height) / 2f)
                    val ribbons = listOf(
                        Triple(P.focus, 1f, 4.8f),
                        Triple(P.externalLight, -2f, 4.4f),
                        Triple(P.attention, 3f, 4.0f),
                        Triple(Color(0xFF7757E8), -1f, 4.6f)
                    )
                    ribbons.forEachIndexed { index, (ribbonColor, speed, widthDp) ->
                        val orbit = angle * speed + index * 90f
                        rotate(orbit, center) {
                            drawArc(
                                color = ribbonColor.copy(alpha = .82f + shimmer * .18f),
                                startAngle = -72f,
                                sweepAngle = 104f,
                                useCenter = false,
                                topLeft = ribbonTopLeft,
                                size = ribbonBounds,
                                style = Stroke(widthDp.dp.toPx(), cap = StrokeCap.Round)
                            )
                            val glowAngle = Math.toRadians((-72f + 104f).toDouble())
                            val glow = Offset(
                                center.x + kotlin.math.cos(glowAngle).toFloat() * ribbonBounds.width * .42f,
                                center.y + kotlin.math.sin(glowAngle).toFloat() * ribbonBounds.height * .36f
                            )
                            drawCircle(ribbonColor.copy(alpha = .10f + shimmer * .08f), 5.dp.toPx(), glow)
                        }
                    }
                    drawCircle(Color.White.copy(alpha = .94f), size.minDimension * .19f, center)
                }
                WorkstreamState.NEEDS_ATTENTION -> {
                    // An abstract inspection eye: open ribbons frame the result while a diamond
                    // scans calmly from side to side. It signals review, never an error/loading loop.
                    val upper = Path().apply {
                        moveTo(size.width * .10f, size.height * .43f)
                        quadraticBezierTo(size.width * .43f, size.height * .13f, size.width * .72f, size.height * .34f)
                    }
                    val lower = Path().apply {
                        moveTo(size.width * .28f, size.height * .66f)
                        quadraticBezierTo(size.width * .57f, size.height * .87f, size.width * .90f, size.height * .55f)
                    }
                    drawPath(upper, Color(0xFFFFC85A).copy(alpha = .56f + shimmer * .32f),
                        style = Stroke(4.4.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(lower, P.attention.copy(alpha = .62f + shimmer * .34f),
                        style = Stroke(4.4.dp.toPx(), cap = StrokeCap.Round))
                    val scan = .5f - .5f * kotlin.math.cos((angle / 360f * Math.PI * 2.0)).toFloat()
                    val bead = Offset(size.width * (.32f + scan * .36f), size.height * .50f)
                    val beadRadius = 3.5.dp.toPx()
                    val diamond = Path().apply {
                        moveTo(bead.x, bead.y - beadRadius)
                        lineTo(bead.x + beadRadius, bead.y)
                        lineTo(bead.x, bead.y + beadRadius)
                        lineTo(bead.x - beadRadius, bead.y)
                        close()
                    }
                    drawCircle(Color(0xFFFFC64A).copy(alpha = .10f + shimmer * .10f), 9.dp.toPx(), bead)
                    drawPath(diamond, Color(0xFFF2A900))
                    drawLine(Color.White.copy(alpha = .68f),
                        Offset(bead.x - 1.4.dp.toPx(), bead.y), Offset(bead.x + 1.4.dp.toPx(), bead.y),
                        strokeWidth = .8.dp.toPx(), cap = StrokeCap.Round)
                }
                WorkstreamState.BLOCKED -> {
                    // Segments keep trying to move horizontally, but the coral lock holds the
                    // center gap. Motion makes the blocked work visible without implying progress.
                    val barY = size.height * .46f
                    val phase = angle / 360f
                    val blockWidth = size.width * .19f
                    val blockHeight = size.height * .16f
                    val laneWidth = size.width * .38f
                    repeat(2) { index ->
                        val local = (phase + index * .55f) % 1f
                        val leftX = -blockWidth + local * laneWidth
                        val rightX = size.width + blockWidth - local * laneWidth - blockWidth
                        val alpha = .48f + .42f * shimmer
                        drawRoundRect(P.inactive.copy(alpha = alpha), Offset(leftX, barY),
                            androidx.compose.ui.geometry.Size(blockWidth, blockHeight),
                            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                        drawRoundRect(P.inactive.copy(alpha = alpha), Offset(rightX, barY),
                            androidx.compose.ui.geometry.Size(blockWidth, blockHeight),
                            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                    }
                    drawRect(P.coral, Offset(size.width * .43f, barY + size.height * .02f),
                        androidx.compose.ui.geometry.Size(size.width * .14f, size.height * .14f))
                    drawArc(P.coral, 180f, 180f, false, Offset(size.width * .44f, size.height * .24f),
                        androidx.compose.ui.geometry.Size(size.width * .12f, size.height * .28f),
                        style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
                WorkstreamState.WAITING, WorkstreamState.PAUSED -> {
                    val float = (shimmer - .5f) * 2.dp.toPx()
                    drawArc(P.inactive, 20f, 140f, false, Offset(inset, inset + float), bounds, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(P.inactive.copy(alpha = .72f), 200f, 140f, false, Offset(inset, inset - float), bounds, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                    drawCircle(P.inactive, 2.dp.toPx(), Offset(size.width / 2f, size.height / 2f))
                }
                WorkstreamState.COMPLETE ->
                    drawArc(P.focus.copy(alpha = .65f), -90f, 360f, false, Offset(inset, inset), bounds, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                WorkstreamState.READY ->
                    drawArc(P.inactive, -90f, 105f, false, Offset(inset, inset), bounds, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
            if (animated) {
                drawCircle(color.copy(alpha = 0.06f * shimmer), radius = size.minDimension * 0.48f)
                if (state == WorkstreamState.EXTERNAL_RUNNING) {
                    drawCircle(P.externalLight.copy(alpha = .08f * shimmer), radius = size.minDimension * .43f)
                }
            }
        }
        if (state == WorkstreamState.MULTI_RUN) {
            Text(activeRunCount.coerceAtLeast(2).toString(), fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = P.ink)
        } else if (state == WorkstreamState.COMPLETE || state == WorkstreamState.READY) {
            Text(if (state == WorkstreamState.COMPLETE) "✓" else "0", fontSize = 11.sp,
                fontWeight = FontWeight.Bold, color = P.ink)
        }
    }
}

@Composable
internal fun ExecutionBars(state: WorkstreamState) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val colors = when (state) {
            WorkstreamState.HUMAN_FOCUS -> listOf(P.focus, P.inactive, P.inactive)
            WorkstreamState.EXTERNAL_RUNNING -> listOf(P.external, P.inactive, P.inactive)
            WorkstreamState.MULTI_RUN -> listOf(P.focus, P.external, P.attention, Color(0xFF7757E8))
            WorkstreamState.NEEDS_ATTENTION -> listOf(P.attention, P.inactive, P.inactive)
            else -> listOf(P.inactive)
        }
        colors.forEach { color -> Box(Modifier.width(22.dp).height(4.dp).clip(RoundedCornerShape(50)).background(color)) }
    }
}

@Composable
internal fun StatePill(state: WorkstreamState) {
    val label = when (state) {
        WorkstreamState.HUMAN_FOCUS -> "FOCUS"
        WorkstreamState.EXTERNAL_RUNNING -> "RUNNING"
        WorkstreamState.MULTI_RUN -> "MULTI RUN"
        WorkstreamState.NEEDS_ATTENTION -> "REVIEW"
        WorkstreamState.WAITING -> "WAITING"
        WorkstreamState.BLOCKED -> "BLOCKED"
        WorkstreamState.PAUSED -> "PAUSED"
        WorkstreamState.COMPLETE -> "DONE"
        WorkstreamState.READY -> return
    }
    val (foreground, background) = when (state) {
        WorkstreamState.HUMAN_FOCUS -> P.focus to Color(0xFFE7F7F0)
        WorkstreamState.EXTERNAL_RUNNING -> P.external to Color(0xFFEAF2FF)
        WorkstreamState.MULTI_RUN -> Color(0xFF315FE8) to Color(0xFFF0EDFF)
        WorkstreamState.NEEDS_ATTENTION -> P.attention to Color(0xFFFFF3D3)
        WorkstreamState.WAITING -> WaitingStatusInk to WaitingStatusSurface
        WorkstreamState.PAUSED -> PausedStatusInk to PausedStatusSurface
        WorkstreamState.BLOCKED -> P.coral to Color(0xFFFFECE9)
        WorkstreamState.COMPLETE -> P.focus to Color(0xFFE7F7F0)
        else -> P.secondary to Color(0xFFF1F3F4)
    }
    Text(label, color = foreground, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        maxLines = 1, softWrap = false,
        modifier = Modifier.background(background, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 6.dp))
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

