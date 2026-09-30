package com.virlin.app.ui.hierarchy

// Supplied drop-in project screen: one toolbar, three tabs, and a bounded slot for whichever
// tab is showing. Adapted where the app already owns what is drawn:
//  - the real ProjectIcon is passed in through `projectIcon`, never an emoji stand-in,
//  - Knowledge and Activity are the app's own libraries, given the remaining height,
//  - Overview shows ONE most recent saved item with + Add and View all, not the whole library,
//  - the tab choice is saved state, so it survives recomposition and process death,
//  - the app's Now / Streams / Pulse / Inbox bar stays outside this screen.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import kotlin.math.roundToInt

/** Screen contract: the host ViewModel adapts the real repository entities into this. */
data class ProjectExperienceData(
    val project: ProjectPageUi,
    val knowledge: List<KnowledgeItem>,
    val tasks: List<KnowledgeProjectTask>,
    val tags: List<KnowledgeTag> = emptyList(),
    val activity: List<ActivityRecord>,
    val activityLoading: Boolean = false,
    val activityError: String? = null,
    val activityHasMore: Boolean = false,
    val onLoadMoreActivity: () -> Unit = {},
    /** One-shot feedback from a write, shown above the tabs' content. */
    val message: String? = null
)

data class ProjectExperienceActions(
    val project: ProjectPageActions,
    val knowledge: KnowledgeActions,
    val activity: ActivityActions
)

private enum class ExperienceTab { OVERVIEW, KNOWLEDGE, ACTIVITY }
private val experienceInk = Color(0xFF17202C)
private val experienceMuted = Color(0xFF647087)
private val experienceLine = Color(0xFFE3E8EE)
private val experienceGreen = Color(0xFF72BE36)
private val experienceGreenInk = Color(0xFF337B1E)

/** The app owns the bottom navigation outside this screen. */
@Composable
fun VirlinProjectExperience(
    data: ProjectExperienceData,
    actions: ProjectExperienceActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    projectIcon: @Composable () -> Unit,
    imagePreview: @Composable (mediaPath: String, modifier: Modifier) -> Unit =
        { path, m -> ActivityImage(path, m) }
) {
    val project = data.project
    var tabName by rememberSaveable(project.id) { mutableStateOf(ExperienceTab.OVERVIEW.name) }
    val tab = ExperienceTab.entries.firstOrNull { it.name == tabName } ?: ExperienceTab.OVERVIEW
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(Color.White).testTag(ProjectDetailTag)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.project.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to projects", tint = experienceInk)
            }
            // Overview carries the large title below, so the bar says where Back goes; the other
            // tabs drop the big header and name the project here instead.
            Text(if (tab == ExperienceTab.OVERVIEW) "Projects" else project.title,
                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = experienceInk,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            IconButton(onClick = actions.project.onSearch) {
                Icon(Icons.Default.Search, "Search project", tint = experienceInk)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, "Project options", tint = experienceInk)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Project settings") }, onClick = {
                        menuOpen = false; actions.project.onProjectSettings()
                    })
                    DropdownMenuItem(text = { Text("Edit project") }, onClick = {
                        menuOpen = false; actions.project.onEditProject()
                    })
                    DropdownMenuItem(text = { Text("Manage workstreams") }, onClick = {
                        menuOpen = false; actions.project.onManageWorkstreams()
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(project.archiveLabel) }, onClick = {
                        menuOpen = false; actions.project.onArchiveProject()
                    })
                }
            }
        }

        if (tab == ExperienceTab.OVERVIEW) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape)
                        .clickable(onClickLabel = "Change ${project.title} icon", onClick = project.onEditIcon)
                        .testTag(ProjectDetailIconTag),
                    contentAlignment = Alignment.Center
                ) { projectIcon() }
                Spacer(Modifier.width(12.dp))
                Text(project.title, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = experienceInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)
                // The whole block opens the project's own task list — not Pulse, which has no
                // way back to this project and is reachable from its footer item anyway.
                .clickable(onClickLabel = "View project tasks", onClick = actions.project.onProgress)
                .testTag(ProjectProgressBlockTag),
                verticalAlignment = Alignment.CenterVertically) {
                ExperienceRing(project.progress, Modifier.size(74.dp))
                Spacer(Modifier.width(20.dp))
                Box(Modifier.size(width = 1.dp, height = 42.dp).background(experienceLine))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    if (project.totalTasks == 0) {
                        Text("No tasks yet", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = experienceInk)
                        Text("Add a workstream or a task", fontSize = 14.sp, color = experienceMuted)
                    } else {
                        Text("${project.completedTasks} of ${project.totalTasks} tasks", fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, color = experienceInk)
                        Text("${project.totalTasks - project.completedTasks} remaining",
                            fontSize = 14.sp, color = experienceMuted)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = experienceInk)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            ExperienceTab.entries.forEach { candidate ->
                Column(
                    Modifier.weight(1f)
                        .clickable(role = Role.Tab) { tabName = candidate.name }
                        .semantics { selected = candidate == tab },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(10.dp))
                    Text(candidate.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = 15.sp,
                        fontWeight = if (candidate == tab) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (candidate == tab) experienceGreenInk else experienceMuted)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(
                        if (candidate == tab) experienceGreen else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = experienceLine)

        data.message?.let { text ->
            Box(Modifier.fillMaxWidth().background(Color(0xFFF6F7F8))) {
                Text(text, Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    fontSize = 13.sp, color = experienceMuted)
            }
        }

        // Each tab gets the whole remaining height; Knowledge and Activity own lazy lists and
        // must never sit inside a scrolling parent.
        when (tab) {
            ExperienceTab.OVERVIEW -> ProjectExperienceOverview(
                data, actions, Modifier.weight(1f)
            ) { tabName = ExperienceTab.KNOWLEDGE.name }
            ExperienceTab.KNOWLEDGE -> VirlinProjectKnowledgeLibrary(
                projectId = project.id, items = data.knowledge, projectTasks = data.tasks,
                tags = data.tags,
                workstreams = project.workstreams.map { KnowledgeWorkstream(it.id, it.title) },
                tasks = data.tasks.map { KnowledgeTask(it.id, it.title, it.workstreamId) },
                actions = actions.knowledge, modifier = Modifier.weight(1f), zone = zone,
                imagePreview = imagePreview
            )
            ExperienceTab.ACTIVITY -> VirlinProjectActivity(
                projectId = project.id, records = data.activity,
                workstreams = project.workstreams.map { ActivityWorkstream(it.id, it.title) },
                actions = actions.activity, modifier = Modifier.weight(1f), zone = zone,
                loading = data.activityLoading, errorMessage = data.activityError,
                hasMore = data.activityHasMore, onLoadMore = data.onLoadMoreActivity,
                imagePreview = imagePreview
            )
        }
    }
}

@Composable
private fun ProjectExperienceOverview(
    data: ProjectExperienceData,
    actions: ProjectExperienceActions,
    modifier: Modifier,
    openKnowledge: () -> Unit
) {
    val project = data.project
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 20.dp)) {
        ExperienceHeading("Workstreams", "+ New", actions.project.onAddWorkstream,
            AddWorkStreamButtonTag, secondaryLabel = "Mind map",
            onSecondary = actions.project.onOpenMap, secondaryTag = ProjectMapButtonTag)
        Spacer(Modifier.height(10.dp))
        if (project.workstreams.isEmpty()) {
            Text("No workstreams yet", fontSize = 14.sp, color = experienceMuted)
        }
        project.workstreams.forEach { stream ->
            Surface(onClick = { actions.project.onOpenWorkstream(stream.id) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .testTag("stream_row_${stream.id}"),
                shape = RoundedCornerShape(12.dp), color = Color.White,
                border = BorderStroke(1.dp, experienceLine)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Color(0xFFFAF8F4), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center) {
                        Text(if (stream.kind == WorkstreamKind.ASSISTANT) "✦" else "▣", color = experienceInk)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stream.title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                            color = experienceInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val progress = if (stream.unstructured) "No tasks yet"
                        else "${stream.completedTasks} of ${stream.totalTasks} tasks complete"
                        Text(
                            if (stream.stateLabel.isBlank()) progress else "$progress · ${stream.stateLabel}",
                            fontSize = 12.sp,
                            color = if (stream.state == WorkstreamState.BLOCKED) Color(0xFFD84B4B) else experienceMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!stream.unstructured) {
                        val percent = if (stream.totalTasks == 0) 0 else
                            (100f * stream.completedTasks / stream.totalTasks).roundToInt()
                        Text("$percent%", color = experienceInk, fontSize = 13.sp)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = experienceInk)
                }
            }
        }

        Spacer(Modifier.height(19.dp))
        ExperienceHeading("Standalone tasks", "+ Add", actions.project.onAddTask, AddTaskButtonTag)
        Spacer(Modifier.height(8.dp))
        if (project.standaloneTasks.isEmpty()) Text("None", fontSize = 14.sp, color = experienceMuted)
        project.standaloneTasks.forEach { task ->
            Surface(onClick = { actions.project.onOpenTask(task.id) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag(taskRowTag(task.id)),
                shape = RoundedCornerShape(11.dp), color = Color.White,
                border = BorderStroke(1.dp, experienceLine)) {
                Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { actions.project.onToggleTask(task.id) },
                        modifier = Modifier.testTag(taskCompleteTag(task.id))) {
                        Text(if (task.completed) "☑" else "☐", color = experienceGreenInk, fontSize = 18.sp)
                    }
                    Text(task.title, Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = experienceInk)
                    IconButton(onClick = { actions.project.onTaskMenu(task.id) }) {
                        Icon(Icons.Default.MoreVert, "Options for ${task.title}", tint = experienceMuted)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        ExperienceHeading("Project knowledge", "+ Add", actions.knowledge.onAdd)
        Spacer(Modifier.height(8.dp))
        // Overview shows only the most recent saved item — the library lives in its own tab.
        val latest = remember(data.knowledge, project.id) {
            data.knowledge.asSequence().filter { it.projectId == project.id }
                .maxWithOrNull(compareBy<KnowledgeItem> { it.updatedAtMillis }.thenBy { it.id })
        }
        if (latest == null) {
            Text("Nothing saved to this project yet", fontSize = 14.sp, color = experienceMuted)
        } else {
            Surface(onClick = { actions.knowledge.onOpen(latest) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp),
                color = Color.White, border = BorderStroke(1.dp, experienceLine)) {
                Row(Modifier.heightIn(min = 54.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Color(0xFFFFF8E8), RoundedCornerShape(9.dp)),
                        contentAlignment = Alignment.Center) {
                        Text(latest.type.symbolForOverview(), color = experienceInk)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(latest.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = experienceInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Most recent · ${latest.type.name.lowercase().replaceFirstChar { it.uppercase() }}",
                            fontSize = 11.sp, color = experienceMuted)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = experienceMuted)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = openKnowledge) { Text("View all →", color = experienceGreenInk) }
        }
    }
}

@Composable
private fun ExperienceHeading(
    label: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionTag: String? = null,
    /** An optional second, quieter action on the same row — used for "Mind map". */
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryTag: String? = null
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold,
            color = experienceInk, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (secondaryLabel != null && onSecondary != null) TextButton(
            onClick = onSecondary,
            modifier = if (secondaryTag != null) Modifier.testTag(secondaryTag) else Modifier
        ) { Text(secondaryLabel, color = experienceMuted) }
        TextButton(
            onClick = onAction,
            modifier = if (actionTag != null) Modifier.testTag(actionTag) else Modifier
        ) { Text(actionLabel, color = experienceGreenInk) }
    }
}

@Composable
private fun ExperienceRing(progress: Float, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 7.dp.toPx()
            val inset = width / 2
            val diameter = size.minDimension - width
            drawArc(Color(0xFFE7F0E1), -90f, 360f, false, Offset(inset, inset),
                Size(diameter, diameter), style = Stroke(width, cap = StrokeCap.Round))
            if (progress > 0f) drawArc(experienceGreen, -90f, 360f * progress.coerceIn(0f, 1f), false,
                Offset(inset, inset), Size(diameter, diameter),
                style = Stroke(width, cap = StrokeCap.Round))
        }
        Text("${(progress * 100).roundToInt()}%", fontSize = 18.sp,
            fontWeight = FontWeight.Bold, color = experienceInk)
    }
}

private fun KnowledgeType.symbolForOverview(): String = when (this) {
    KnowledgeType.PROMPT -> "❞"
    KnowledgeType.IMAGE -> "▧"
    KnowledgeType.AUDIO -> "♪"
    KnowledgeType.STEPS -> "≡"
    KnowledgeType.CHECKLIST -> "☑"
    KnowledgeType.TASK -> "☐"
    KnowledgeType.DOCUMENT -> "▤"
}

/** Test tag for Overview's tappable progress block. */
const val ProjectMapButtonTag = "project_map_button"
const val ProjectProgressBlockTag = "project_progress_block"

/**
 * Bottom room a scrolling list leaves for the floating Orb, which sits above the shared footer
 * in the bottom-right corner. Without it the last row would sit under the Orb and its actions
 * would be unreachable.
 */
val OrbClearance = 78.dp
