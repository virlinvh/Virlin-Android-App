package com.virlin.app.ui.hierarchy

// Supplied drop-in project Task Index. Adapted to the app's own rules:
//  - the rows ARE the units `ProgressCalculator.ofProject` counted (see ProjectTaskUnits), so a
//    filter can never show a set that disagrees with the ratio above it,
//  - a WorkStream that holds no tasks is itself one counted unit and is listed as such,
//  - navigation carries stable ids (workstream + ancestor chain + task), never titles,
//  - the app-wide bottom bar is supplied by the host; this screen draws no second one.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The project repository decides which records contribute to project progress. */
data class ProjectTaskIndexState(
    val projectId: String,
    val projectTitle: String,
    val completedCount: Int,
    val totalCount: Int,
    val tasks: List<ProjectTaskIndexEntry>,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ProjectTaskIndexEntry(
    val taskId: String,
    val projectId: String,
    val workstreamId: String?,
    val workstreamTitle: String?,
    val parentTaskId: String?,
    /** Ordered ancestor IDs from the stream root to the direct parent. */
    val ancestorTaskIds: List<String>,
    /** Display path only; navigation uses IDs, never names. */
    val ancestorTitles: List<String>,
    val title: String,
    val completed: Boolean,
    val current: Boolean,
    val order: Int,
    /**
     * True when this row IS a WorkStream that holds no tasks. Project progress counts such a
     * stream as one unit, so the index lists it rather than quietly dropping it.
     */
    val isEmptyWorkStream: Boolean = false,
)

enum class ProjectTaskIndexFilter { REMAINING, ALL, DONE }

/** Host with the app's existing NavHost and bottom navigation. */
interface ProjectTaskIndexActions {
    fun backToProject(projectId: String)
    fun openTaskInHierarchy(
        projectId: String,
        workstreamId: String?,
        ancestorTaskIds: List<String>,
        /** Null opens the WorkStream's own root level. */
        taskId: String?
    )
    fun searchProjectTasks(projectId: String)
    fun showOptions(projectId: String)
    fun retry(projectId: String)
}

private val Ink = Color(0xFF17202C)
private val Muted = Color(0xFF66758A)
private val Line = Color(0xFFE4E9ED)
private val Green = Color(0xFF65BB31)
private val DeepGreen = Color(0xFF176B45)
private val Mint = Color(0xFFEAF7EC)
private val Amber = Color(0xFFE5A000)
private val AmberWash = Color(0xFFFFF5DB)
private val Neutral = Color(0xFFF5F7F7)

/** Place inside the current project Overview progress block; the whole area is tappable. */
@Composable
fun ProjectProgressEntry(
    completedCount: Int,
    totalCount: Int,
    onOpenProjectTasks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = (totalCount - completedCount).coerceAtLeast(0)
    val percent = if (totalCount > 0) (completedCount * 100f / totalCount).toInt() else 0
    Row(
        modifier.fillMaxWidth().background(Mint, RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenProjectTasks).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(66.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxSize(), color = Green, trackColor = Color(0xFFE0EDDB), strokeWidth = 5.dp)
            Text("$percent%", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("$completedCount of $totalCount tasks", color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("$remaining remaining", color = Muted, fontSize = 14.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "View project tasks", tint = DeepGreen)
    }
}

/** This is a dedicated project route. It does not navigate to Pulse. */
@Composable
fun VirlinProjectTaskIndex(
    state: ProjectTaskIndexState,
    actions: ProjectTaskIndexActions,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filterName by rememberSaveable(state.projectId) { mutableStateOf(ProjectTaskIndexFilter.REMAINING.name) }
    // Saved with the filter: returning from a task lands where the viewer left off.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val filter = ProjectTaskIndexFilter.entries.firstOrNull { it.name == filterName } ?: ProjectTaskIndexFilter.REMAINING
    val validTasks = remember(state.tasks, state.projectId) { state.tasks.filter { it.projectId == state.projectId } }
    val visible = remember(validTasks, filter) {
        validTasks.filter { when (filter) {
            ProjectTaskIndexFilter.REMAINING -> !it.completed
            ProjectTaskIndexFilter.ALL -> true
            ProjectTaskIndexFilter.DONE -> it.completed
        } }.sortedWith(compareBy<ProjectTaskIndexEntry>({ it.workstreamTitle ?: "" }, { it.order }, { it.title }))
    }
    val groups = remember(visible) { visible.groupBy { it.workstreamId } }
    Scaffold(
        modifier = modifier,
        containerColor = Color.White,
        bottomBar = footer,
        topBar = {
            Row(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.backToProject(state.projectId) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to project", tint = Ink) }
                Column(Modifier.weight(1f)) {
                    Text("${state.projectTitle} tasks", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${state.projectTitle} project", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { actions.searchProjectTasks(state.projectId) }) { Icon(Icons.Default.Search, "Search project tasks", tint = Ink) }
                IconButton(onClick = { actions.showOptions(state.projectId) }) { Icon(Icons.Default.MoreVert, "Options", tint = Ink) }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            LazyColumn(
                Modifier.fillMaxSize().testTag(ProjectTaskIndexTag),
                state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = OrbClearance),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "progress") { ProjectTaskIndexProgress(state.completedCount, state.totalCount) }
                item(key = "filters") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        ProjectTaskIndexFilter.entries.forEach { value ->
                            val count = when (value) {
                                ProjectTaskIndexFilter.REMAINING -> (state.totalCount - state.completedCount).coerceAtLeast(0)
                                ProjectTaskIndexFilter.ALL -> state.totalCount
                                ProjectTaskIndexFilter.DONE -> state.completedCount
                            }
                            IndexFilterChip(value.name.lowercase().replaceFirstChar(Char::uppercase) + " $count", value == filter, Modifier.weight(1f)) { filterName = value.name }
                        }
                    }
                }
                if (state.loading) item(key = "loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Green) } }
                else if (state.error != null) item(key = "error") {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(state.error, color = Ink)
                        TextButton(onClick = { actions.retry(state.projectId) }) { Text("Retry", color = DeepGreen) }
                    }
                } else if (visible.isEmpty()) item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(top = 55.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (filter == ProjectTaskIndexFilter.REMAINING) "Nothing remaining" else "No tasks here yet", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text(if (filter == ProjectTaskIndexFilter.REMAINING) "Tasks to finish will appear here." else "Try another view.", color = Muted)
                    }
                } else {
                    groups.forEach { (workstreamId, entries) ->
                        val label = entries.first().workstreamTitle ?: "Standalone tasks"
                        item(key = "group:$workstreamId") {
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("${entries.size}", color = Muted, fontSize = 13.sp)
                            }
                        }
                        items(entries, key = { it.taskId }) { entry ->
                            ProjectIndexTaskRow(entry) {
                                actions.openTaskInHierarchy(
                                    state.projectId, entry.workstreamId, entry.ancestorTaskIds,
                                    // An empty WorkStream opens at its own root, where its first
                                    // task would be added; it has no task id to open.
                                    if (entry.isEmptyWorkStream) null else entry.taskId
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ProjectTaskIndexProgress(completed: Int, total: Int) {
    val remaining = (total - completed).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp)) {
        Text("$completed of $total tasks complete", color = Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("$remaining remaining", color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(18.dp))
        if (total in 1..30) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(total) { index -> Box(Modifier.weight(1f).height(9.dp).background(if (index < completed) Green else Line, RoundedCornerShape(5.dp))) }
            }
        } else {
            LinearProgressIndicator(progress = { if (total > 0) completed.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Green, trackColor = Line)
        }
    }
}

@Composable private fun IndexFilterChip(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.background(if (selected) Mint else Neutral, RoundedCornerShape(13.dp)).clickable(onClick = onClick).padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (selected) DeepGreen else Muted, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable private fun ProjectIndexTaskRow(entry: ProjectTaskIndexEntry, onClick: () -> Unit) {
    val bg = if (entry.current && !entry.completed) AmberWash else Neutral
    Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(if (entry.completed) Green else if (entry.current) Amber else Color(0xFFE9EDEF), CircleShape), contentAlignment = Alignment.Center) {
            if (entry.completed) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
            else if (entry.current) Box(Modifier.size(15.dp).background(Color.White, CircleShape))
            else Box(Modifier.size(15.dp).background(Color.White, CircleShape))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (entry.current && !entry.completed) {
                    Spacer(Modifier.width(8.dp))
                    Text("Current", color = Color(0xFF805A00), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            val path = if (entry.isEmptyWorkStream) "Workstream · no tasks yet"
            else (listOfNotNull(entry.workstreamTitle) + entry.ancestorTitles).joinToString(" › ")
            if (path.isNotEmpty()) Text(path, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open ${entry.title} in hierarchy", tint = Ink)
    }
}

/** Test tag for the project Task Index list. */
const val ProjectTaskIndexTag = "project_task_index"
