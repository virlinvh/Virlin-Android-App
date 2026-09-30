package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlin.math.roundToInt
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.theme.*

/** Test hook: the Streams lazy list (lets tests scroll a row into composition). */
const val StreamsListTag = "streams_list"

/** Horizontal Streams filter rail (All · Projects · Need You · Processing · Ready). */
const val StreamsFilterRailTag = "streams_filter_rail"

private val StreamsFilters = listOf("All", "Projects", "Need You", "Processing", "Ready")

fun streamsFilterTag(label: String) =
    "streams_filter_${label.lowercase().replace(' ', '_')}"


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsScreen(navController: NavController) {
    val hierarchy: com.virlin.app.ui.hierarchy.HierarchyViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel()
    val snapshot by hierarchy.snapshot.collectAsState()
    val now = com.virlin.app.domain.VirlinGraph.clock.now()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterName by rememberSaveable { mutableStateOf(ProjectDirectoryFilter.ALL.name) }
    val filter = ProjectDirectoryFilter.entries.firstOrNull { it.name == filterName }
        ?: ProjectDirectoryFilter.ALL
    var addingProject by rememberSaveable { mutableStateOf(false) }
    var pickingFilter by remember { mutableStateOf(false) }

    if (addingProject) com.virlin.app.ui.hierarchy.AddNameDialog(
        "New project", "Project name", onDismiss = { addingProject = false }
    ) { hierarchy.addProject(it) }

    val summaries = remember(snapshot) { hierarchy.projectSummaries(snapshot) }

    // A project's status is its WorkStreams' status, read from the domain's own projection.
    // Nothing here is inferred from a progress percentage.
    val items = remember(summaries, snapshot, searchQuery, filter, now) {
        summaries.asSequence()
            .filter { summary ->
                searchQuery.isBlank() || summary.project.title.contains(searchQuery, ignoreCase = true)
            }
            .filter { summary ->
                filter == ProjectDirectoryFilter.ALL || snapshot.streams.any { stream ->
                    stream.projectId == summary.project.id && stream.matches(filter, now)
                }
            }
            .map { summary ->
                ProjectDirectoryItem(
                    id = summary.project.id,
                    title = summary.project.title,
                    workstreamCount = summary.streamCount,
                    progressPercent = summary.progress.fraction?.let { (it * 100).roundToInt() },
                    iconTint = Charcoal,
                    iconBackground = Color.Transparent
                )
            }
            .toList()
    }

    VirlinProjectDirectory(
        state = ProjectDirectoryState(
            // The existing source, counting what the heading now names: this project list.
            totalCount = summaries.size,
            searchQuery = searchQuery,
            selectedFilter = filter,
            visibleProjects = items
        ),
        actions = ProjectDirectoryActions(
            onSearchChanged = { searchQuery = it },
            onAdvancedFilter = { pickingFilter = true },
            onFilterChanged = { filterName = it.name },
            onCreateProject = { addingProject = true },
            onOpenProject = { navController.navigate(com.virlin.app.ui.hierarchy.projectDetail(it)) }
        ),
        projectIcon = { item ->
            val project = snapshot.projects.firstOrNull { it.id == item.id }
            if (project != null) {
                com.virlin.app.ui.components.ProjectIcon(project = project, size = 45.dp, decorative = true)
            }
        }
    )

    if (pickingFilter) {
        // The same finite statuses, as a list: the app has no separate advanced filter, and a
        // control that did nothing would be worse than one that offers the same choice plainly.
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickingFilter = false },
            title = { Text("Filter projects") },
            text = {
                Column {
                    ProjectDirectoryFilter.entries.forEach { option ->
                        Text(
                            option.label,
                            modifier = Modifier.fillMaxWidth()
                                .defaultMinSize(minHeight = 44.dp)
                                .clickable { filterName = option.name; pickingFilter = false }
                                .padding(vertical = 10.dp),
                            color = if (option == filter) FocusGreen else Charcoal
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { pickingFilter = false }) { Text("Close") }
            }
        )
    }
}

/**
 * Does this WorkStream put its project under [filter]? The states are the app's own, and a due
 * PROCESSING or SNOOZED stream reads as CHECK exactly as `effectiveAttentionState` says — the
 * same rule Now uses for "Needs You".
 */
private fun com.virlin.app.domain.model.WorkStream.matches(
    filter: ProjectDirectoryFilter,
    now: java.time.Instant
): Boolean {
    val state = com.virlin.app.domain.model.effectiveAttentionState(this, now)
    return when (filter) {
        ProjectDirectoryFilter.ALL -> true
        ProjectDirectoryFilter.NEED_YOU -> state == com.virlin.app.domain.model.WorkStreamState.CHECK
        ProjectDirectoryFilter.PROCESSING -> state == com.virlin.app.domain.model.WorkStreamState.PROCESSING
        ProjectDirectoryFilter.READY -> state == com.virlin.app.domain.model.WorkStreamState.READY
        // The app's own FOCUS state: the one stream consuming human attention.
        ProjectDirectoryFilter.FREE_FOCUS -> state == com.virlin.app.domain.model.WorkStreamState.FOCUS
        ProjectDirectoryFilter.SNOOZED -> state == com.virlin.app.domain.model.WorkStreamState.SNOOZED
        ProjectDirectoryFilter.BLOCKED -> state == com.virlin.app.domain.model.WorkStreamState.BLOCKED
    }
}


/**
 * Streams filter rail. Chips keep intrinsic width (`maxLines = 1`, `softWrap = false`);
 * the row viewport owns horizontal scrolling — never compress labels into the screen width.
 * Projects live INSIDE Streams — never a fourth nav destination.
 */
@Composable
fun StreamsFilterRail(
    filter: String,
    onFilterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(StreamsFilterRailTag)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StreamsFilters.forEach { f ->
            val sel = f == filter
            Text(
                text = f,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp,
                color = if (sel) Color.White else CharcoalMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .background(if (sel) Charcoal else Color.White, RoundedCornerShape(50))
                    .testTag(streamsFilterTag(f))
                    .clickable { onFilterSelected(f) }
                    .semantics { contentDescription = "$f filter"; selected = sel }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun GroupHeader(title: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Black, color = CharcoalMuted)
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.size(18.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(count.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Charcoal)
        }
    }
}

@Composable
fun StreamRow(stream: WorkStream, navController: NavController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .testTag("stream_row_${stream.id}")
            .clickable { navController.navigate(com.virlin.app.ui.hierarchy.workStreamDetail(stream.id)) }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stream.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Charcoal)
            Text(stream.subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted)
        }
        
        when (stream.state) {
            StreamState.FOCUS -> {
                val m = (stream.focusInvestedSec / 60).toString().padStart(2, '0')
                val s = (stream.focusInvestedSec % 60).toString().padStart(2, '0')
                Text("${m}:${s} invested", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Emerald500)
            }
            StreamState.NEEDS_YOU -> {
                Box(modifier = Modifier.background(Charcoal, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("CHECK", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            StreamState.PROCESSING -> {
                val m = (stream.processingElapsedSec / 60).toString().padStart(2, '0')
                val s = (stream.processingElapsedSec % 60).toString().padStart(2, '0')
                Text("${m}:${s} elapsed", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Violet600)
            }
            StreamState.READY -> {
                Text("~5m", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted)
            }
            else -> {}
        }
    }
}
