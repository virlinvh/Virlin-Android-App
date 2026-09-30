package com.virlin.app.ui.screens

// Supplied drop-in projects directory. Adapted where the app already decides the meaning:
//  - a project matches a status when one of ITS WORKSTREAMS is in that state, read from the
//    domain's own `effectiveAttentionState` — nothing is inferred from a progress percentage,
//  - progress may be absent (a project with no tasks is "Unstructured", not 0%), so the ring
//    shows a dash rather than claiming nothing has been done,
//  - the trailing filter control opens the same finite status list as a list, because the app
//    has no separate advanced-filter behaviour to call,
//  - the app's real ProjectIcon is passed in; the footer and Orb stay outside, drawn by the shell.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI only. The existing repository owns filtering, ordering, status rules,
 * project navigation, project creation, footer, and ORB.
 */
enum class ProjectDirectoryFilter(val label: String) {
    ALL("All"), NEED_YOU("Need You"), PROCESSING("Processing"), READY("Ready"),
    FREE_FOCUS("Free Focus"), SNOOZED("Snoozed"), BLOCKED("Blocked")
}

data class ProjectDirectoryItem(
    val id: String,
    val title: String,
    val workstreamCount: Int,
    /** Null when the project has no executable work yet — not the same thing as 0%. */
    val progressPercent: Int?,
    val iconTint: Color,
    val iconBackground: Color
)

data class ProjectDirectoryState(
    val totalCount: Int,
    val searchQuery: String,
    val selectedFilter: ProjectDirectoryFilter,
    /** Already filtered by the host's existing classification and search logic. */
    val visibleProjects: List<ProjectDirectoryItem>
)

data class ProjectDirectoryActions(
    val onSearchChanged: (String) -> Unit,
    /** Opens the same statuses as a list — the app has no other filtering behaviour to call. */
    val onAdvancedFilter: () -> Unit,
    val onFilterChanged: (ProjectDirectoryFilter) -> Unit,
    val onCreateProject: () -> Unit,
    val onOpenProject: (String) -> Unit
)

private val directoryBackground = Color(0xFFF8F7F5)
private val directoryInk = Color(0xFF172119)
private val directoryMuted = Color(0xFF63717B)
private val directoryActive = Color(0xFF13251A)
private val directoryMint = Color(0xFFDFF4EB)
private val directoryMintInk = Color(0xFF007B58)
private val directoryProgress = Color(0xFF0BBB87)
private val directoryRingTrack = Color(0xFFE6E9ED)

/** Place inside the app's existing Scaffold content; do not draw another bottom bar. */
@Composable
fun VirlinProjectDirectory(
    state: ProjectDirectoryState,
    actions: ProjectDirectoryActions,
    modifier: Modifier = Modifier,
    projectIcon: @Composable (ProjectDirectoryItem) -> Unit = { item ->
        Text(item.title.take(1), fontWeight = FontWeight.Bold, fontSize = 19.sp, color = item.iconTint)
    }
) {
    Column(modifier.fillMaxSize().background(directoryBackground)) {
        ProjectDirectoryHeader(state.totalCount, actions.onCreateProject)
        DirectorySearch(state.searchQuery, actions.onSearchChanged, actions.onAdvancedFilter)
        Spacer(Modifier.height(14.dp))
        CyclicStatusRail(state.selectedFilter, actions.onFilterChanged)
        Spacer(Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(StreamsListTag),
            // The Orb floats above the footer in the bottom-right; the last card clears it.
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 78.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.visibleProjects.isEmpty()) {
                item {
                    DirectoryEmptyState(state.searchQuery, state.selectedFilter)
                }
            } else {
                items(state.visibleProjects, key = { it.id }) { project ->
                    ProjectDirectoryCard(project, actions.onOpenProject, projectIcon)
                }
            }
        }
    }
}

@Composable
private fun ProjectDirectoryHeader(totalCount: Int, onCreate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The heading yields space; the action never does. At large font scales the title
        // ellipsizes and the count drops rather than the button clipping to "+ PROJE".
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("Project", color = directoryInk, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(9.dp))
            // The heading itself is measured first; the count gives way before the word does.
            Text("$totalCount total", color = directoryMuted, fontSize = 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.defaultMinSize(minHeight = 44.dp)
                .clip(CircleShape).background(directoryMint)
                .clickable(role = Role.Button, onClick = onCreate)
                .testTag(com.virlin.app.ui.hierarchy.AddProjectButtonTag)
                .padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = directoryMintInk,
                modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(3.dp))
            Text("PROJECT", color = directoryMintInk, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun DirectorySearch(query: String, onQuery: (String) -> Unit, onAdvancedFilter: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .height(58.dp).clip(RoundedCornerShape(18.dp)).background(Color.White)
            .padding(start = 17.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = directoryInk,
            modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(13.dp))
        BasicTextField(
            value = query, onValueChange = onQuery,
            modifier = Modifier.weight(1f).semantics { contentDescription = "Search projects" },
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = directoryInk),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Search...", color = directoryMuted, fontSize = 16.sp)
                    inner()
                }
            }
        )
        Box(Modifier.width(1.dp).height(28.dp).background(Color(0xFFE4E9E8)))
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onAdvancedFilter)
                .semantics { contentDescription = "More filters" },
            contentAlignment = Alignment.Center
        ) {
            DirectoryFilterGlyph()
        }
    }
}

@Composable
private fun DirectoryFilterGlyph() {
    Canvas(Modifier.size(23.dp)) {
        val stroke = 1.8.dp.toPx()
        val ys = listOf(size.height * .22f, size.height * .50f, size.height * .78f)
        val knobs = listOf(.34f, .70f, .45f)
        ys.forEachIndexed { index, y ->
            drawLine(directoryMintInk, Offset(0f, y), Offset(size.width, y),
                strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(Color.White, radius = 3.4.dp.toPx(), center = Offset(size.width * knobs[index], y))
            drawCircle(directoryMintInk, radius = 3.4.dp.toPx(), center = Offset(size.width * knobs[index], y),
                style = Stroke(width = stroke))
        }
    }
}

/**
 * All is fixed. A large virtual LazyRow repeats the finite status list, so
 * swiping can continue in either direction without a visible terminal edge.
 * The status value itself is unique and filtering is performed only once.
 */
@Composable
private fun CyclicStatusRail(selected: ProjectDirectoryFilter, onSelected: (ProjectDirectoryFilter) -> Unit) {
    val filters = remember { ProjectDirectoryFilter.values().filterNot { it == ProjectDirectoryFilter.ALL } }
    val middle = remember(filters.size) { 1_000_000 - (1_000_000 % filters.size) }
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = middle)
    Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusChip(ProjectDirectoryFilter.ALL, selected == ProjectDirectoryFilter.ALL, onSelected)
        Spacer(Modifier.width(9.dp))
        Box(Modifier.width(1.dp).height(25.dp).background(Color(0xFFD3DAD7)))
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 25.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(Int.MAX_VALUE) { index ->
                    val filter = filters[index % filters.size]
                    StatusChip(filter, selected == filter, onSelected)
                }
            }
            // Edge fades signal scrollability, without consuming touch input.
            Box(Modifier.align(Alignment.CenterStart).width(14.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(directoryBackground, directoryBackground.copy(alpha = 0f)))))
            Box(Modifier.align(Alignment.CenterEnd).width(26.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(directoryBackground.copy(alpha = 0f), directoryBackground))))
        }
    }
}

@Composable
private fun StatusChip(filter: ProjectDirectoryFilter, selected: Boolean,
                       onSelected: (ProjectDirectoryFilter) -> Unit) {
    Box(
        Modifier.defaultMinSize(minHeight = 44.dp).clip(CircleShape)
            .background(if (selected) directoryActive else Color.White)
            .clickable(role = Role.Tab) { onSelected(filter) }
            .semantics {
                this.selected = selected
                contentDescription = filter.label + " filter"
            }
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(filter.label, color = if (selected) Color.White else Color(0xFF4F5D58),
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ProjectDirectoryCard(
    item: ProjectDirectoryItem,
    onOpen: (String) -> Unit,
    projectIcon: @Composable (ProjectDirectoryItem) -> Unit
) {
    val percent = item.progressPercent?.coerceIn(0, 100)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 82.dp)
            .clip(RoundedCornerShape(19.dp)).background(Color.White)
            .clickable(role = Role.Button) { onOpen(item.id) }
            .testTag("project_row_" + item.id)
            .semantics {
                contentDescription = item.title + ", " + item.workstreamCount + " WorkStreams" +
                    (item.progressPercent?.let { ", $it percent complete" } ?: ", no tasks yet")
            }
            .padding(start = 13.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(45.dp).background(item.iconBackground, CircleShape),
            contentAlignment = Alignment.Center) { projectIcon(item) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = directoryInk, fontSize = 16.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text("${item.workstreamCount} WorkStream${if (item.workstreamCount == 1) "" else "s"}",
                color = directoryMuted, fontSize = 13.sp, maxLines = 1,
                // Without this a squeezed row clips the word away and leaves a bare number.
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        ProgressRing(percent)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = Color(0xFF596276), modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun ProgressRing(percent: Int?) {
    Box(
        Modifier.size(51.dp).semantics {
            contentDescription = if (percent == null) "No tasks yet" else "$percent percent complete"
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val width = 5.dp.toPx()
            drawArc(directoryRingTrack, 0f, 360f, false, style = Stroke(width, cap = StrokeCap.Round))
            if (percent != null && percent > 0) drawArc(directoryProgress, -90f, percent * 3.6f, false,
                style = Stroke(width, cap = StrokeCap.Round))
        }
        // A project with no tasks has no percentage; showing 0% would claim work not started
        // where there is no work to start.
        Text(if (percent == null) "\u2013" else "$percent%",
            color = if (percent == null) directoryMuted else directoryInk,
            fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DirectoryEmptyState(query: String, selected: ProjectDirectoryFilter) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No projects found", color = directoryInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(if (query.isNotBlank()) "Try another search" else "No projects in ${selected.label} yet",
            color = directoryMuted, fontSize = 14.sp)
    }
}
