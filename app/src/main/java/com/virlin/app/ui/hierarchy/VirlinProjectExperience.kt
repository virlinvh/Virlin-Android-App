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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import kotlin.math.roundToInt
import com.virlin.app.ui.components.avoidOrbCircle
import com.virlin.app.ui.components.circularOrbCutout

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
private val experienceInk: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.text) } ?: Color(0xFF17202C)
private val experienceMuted: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.muted) } ?: Color(0xFF647087)
private val experienceLine: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.tertiary) } ?: Color(0xFFE3E8EE)
private val experienceGreen: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.main) } ?: Color(0xFF72BE36)
private val experienceGreenInk: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF337B1E)

/** The app owns the bottom navigation outside this screen. */
@Composable
fun VirlinProjectExperience(
    data: ProjectExperienceData,
    actions: ProjectExperienceActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    controlsOpen: Boolean = false,
    projectIcon: @Composable () -> Unit,
    imagePreview: @Composable (mediaPath: String, modifier: Modifier) -> Unit =
        { path, m -> ActivityImage(path, m) }
) {
    val project = data.project
    val palette = rememberProjectPalette(project.id)
    CompositionLocalProvider(LocalProjectPalette provides palette) {
    var searchOpen by rememberSaveable(project.id) { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = searchOpen) { searchOpen = false }
    if (searchOpen) {
        HideProjectOrb()
        Surface(color = palette?.let { Color(it.surface) } ?: Color.White) {
            ProjectSearchScreen(project, actions.project) { searchOpen = false }
        }
    } else {
    var tabName by rememberSaveable(project.id) { mutableStateOf(ExperienceTab.OVERVIEW.name) }
    val tab = ExperienceTab.entries.firstOrNull { it.name == tabName } ?: ExperienceTab.OVERVIEW
    if (tab != ExperienceTab.OVERVIEW) HideProjectOrb()
    var detailsHeight by remember(project.id) { mutableFloatStateOf(0f) }
    var detailsOffset by remember(project.id) { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(tab) { if (tab != ExperienceTab.OVERVIEW) detailsOffset = 0f }
    val scrollConnection = remember(project.id, detailsHeight, tab) {
        ProjectHeaderScrollConnection(
            enabled = { tab == ExperienceTab.OVERVIEW }, height = { detailsHeight },
            offset = { detailsOffset }, updateOffset = { detailsOffset = it })
    }

    // Keep the modal backdrop on the standard window dim path. RenderEffect blur can
    // freeze/black out dialog surfaces on emulator and some device GPU backends.
    Column(modifier.fillMaxSize()
        .background(palette?.let { Color(it.surface) } ?: Color.White).nestedScroll(scrollConnection).testTag(ProjectDetailTag)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.project.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to projects", tint = experienceInk)
            }
            // Overview carries the large title below, so the bar says where Back goes; the other
            // tabs drop the big header and name the project here instead.
            if (tab != ExperienceTab.OVERVIEW) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { projectIcon() }
                Spacer(Modifier.width(8.dp))
            }
            Text(if (tab == ExperienceTab.OVERVIEW) "Projects" else project.title,
                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = experienceInk,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { searchOpen = true }) {
                Icon(Icons.Default.Search, "Search project", tint = experienceInk)
            }
            actions.project.onToggleTimers?.let { toggle ->
                com.virlin.app.ui.screens.MasterTimerButton(project.timersRunning, project.timerControlBusy, toggle,
                    runningColor = palette?.let { Color(it.primary) } ?: Color(0xFFEBB700),
                    scopeLabel = project.title, tag = "project_timer_button")
            }
            // This toolbar has only one settings destination. Open its normal page
            // directly instead of creating a separate popup window (black on some GPUs).
            IconButton(onClick = actions.project.onProjectSettings) {
                Icon(Icons.Default.Settings, "Project settings", tint = experienceInk)
            }
        }

        project.timerControlMessage?.let { message ->
            Text(message, fontSize = 12.sp, color = experienceMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("project_timer_message"))
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
        }
        Box(Modifier.fillMaxWidth().height(with(density) { (detailsHeight + detailsOffset).coerceAtLeast(0f).toDp() })
            .clipToBounds().testTag("active_project_collapsible_details")) {
        Column(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true, align = Alignment.Top)
            .onSizeChanged { detailsHeight = it.height.toFloat() }
            .offset { androidx.compose.ui.unit.IntOffset(0, detailsOffset.roundToInt()) }) {
        if (tab == ExperienceTab.OVERVIEW) {
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
            if (project.externalSeconds > 0 || project.focusSeconds > 0) Text(
                "External ${com.virlin.app.domain.model.ExecutionTiming.formatClock(project.externalSeconds)} · Focused ${com.virlin.app.domain.model.ExecutionTiming.formatClock(project.focusSeconds)}",
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                fontSize = 12.sp, color = experienceMuted)
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
        if (tab == ExperienceTab.OVERVIEW) Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            ExecutionLegend(project.humanActiveCount, project.externalActiveCount,
                project.workstreams.count { it.state == WorkstreamState.MULTI_RUN },
                project.workstreams.count { it.state == WorkstreamState.NEEDS_ATTENTION }, project.id)
            Spacer(Modifier.height(18.dp))
        }
        }
        if (tab == ExperienceTab.OVERVIEW && detailsOffset < 0f && detailsHeight + detailsOffset > 0f) {
            Box(Modifier.fillMaxWidth().height(14.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(LocalProjectPalette.current?.let { Color(it.surface) } ?: Color.White, Color.Transparent))))
        }
        }
        if (tab == ExperienceTab.OVERVIEW) Column(Modifier.padding(horizontal = 20.dp)) {
            ExperienceHeading("Workstreams", "+ New", actions.project.onAddWorkstream,
                AddWorkStreamButtonTag, secondaryLabel = "Mind map",
                onSecondary = actions.project.onOpenMap, secondaryTag = ProjectMapButtonTag)
            Spacer(Modifier.height(10.dp))
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
                workstreams = project.workstreams.map { KnowledgeWorkstream(it.id, it.title, "${it.completedTasks} of ${it.totalTasks} complete") },
                tasks = data.tasks.map { KnowledgeTask(it.id, it.title, it.workstreamId, it.parentId) },
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
    var standaloneExpanded by rememberSaveable(project.id) { mutableStateOf(false) }
    var knowledgeExpanded by rememberSaveable(project.id) { mutableStateOf(false) }
    val (blocks, _) = rememberWorkstreamBlocks(project.id)
    val listState = rememberLazyListState()
    var dragging by remember(project.id) { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var beforeId by remember { mutableStateOf<String?>(null) }
    val accent = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF087848)
    val handleBounds = remember(project.id) { mutableMapOf<String, androidx.compose.ui.geometry.Rect>() }
    var listCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    fun updateTarget() {
        beforeId = listState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            val id = info.key.toString().removePrefix("stream:")
            info.key.toString().startsWith("stream:") && id != dragging && info.offset + info.size / 2f > dragY
        }?.key?.toString()?.removePrefix("stream:")
            ?: project.workstreams.getOrNull((listState.layoutInfo.visibleItemsInfo.lastOrNull {
                it.key.toString().startsWith("stream:")
            }?.index ?: project.workstreams.lastIndex) + 1)?.id
    }
    LaunchedEffect(blocks, project.workstreams.map { it.id }) { dragging = null }
    LaunchedEffect(dragging) {
        while (dragging != null) {
            val layout = listState.layoutInfo
            val edge = 80f
            val scroll = when {
                dragY < layout.viewportStartOffset + edge -> -12f
                dragY > layout.viewportEndOffset - edge -> 12f
                else -> 0f
            }
            if (scroll != 0f) { listState.scrollBy(scroll); updateTarget() }
            delay(16)
        }
    }
    Box(modifier.fillMaxWidth().clipToBounds()) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()
        .padding(horizontal = 20.dp).testTag("active_project_overview_scroll")
        .pointerInput(blocks, project.workstreams.map { it.id }) {
            if (blocks) detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val rootPosition = listCoordinates?.takeIf { it.isAttached }?.localToRoot(position)
                    val source = handleBounds.entries.firstOrNull { entry -> rootPosition != null && entry.value.contains(rootPosition) &&
                        listState.layoutInfo.visibleItemsInfo.any { it.key == "stream:${entry.key}" } }?.key
                    listState.layoutInfo.visibleItemsInfo.firstOrNull {
                        source != null && it.key == "stream:$source"
                    }?.let { row ->
                        dragging = row.key.toString().removePrefix("stream:")
                        dragY = row.offset + row.size / 2f
                        beforeId = project.workstreams.getOrNull(row.index + 1)?.id
                    }
                },
                onDrag = { change, amount -> if (dragging != null) { change.consume(); dragY += amount.y; updateTarget() } },
                onDragCancel = { dragging = null },
                onDragEnd = {
                    dragging?.let { source ->
                        val index = project.workstreams.indexOfFirst { it.id == source }
                        if (beforeId != project.workstreams.getOrNull(index + 1)?.id)
                            actions.project.onReorderWorkstream(source, beforeId)
                    }
                    dragging = null
                })
        }.onGloballyPositioned { listCoordinates = it }, state = listState,
        userScrollEnabled = dragging == null) {
        if (project.workstreams.isEmpty()) {
            item { Text("No workstreams yet", fontSize = 14.sp, color = experienceMuted) }
        }
        items(project.workstreams.size, key = { "stream:${project.workstreams[it].id}" }) { index ->
            val stream = project.workstreams[index]
            val isDragging = dragging == stream.id
            if (dragging != null && !isDragging && beforeId == stream.id) HorizontalDivider(color = accent, thickness = 2.dp)
            Surface(onClick = { actions.project.onOpenWorkstream(stream.id) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).circularOrbCutout()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { alpha = if (isDragging) .3f else 1f }
                    .testTag("stream_row_${stream.id}"),
                shape = RoundedCornerShape(12.dp), color = Color.White,
                shadowElevation = if (isDragging) 8.dp else 0.dp,
                border = BorderStroke(if (isDragging) 2.dp else 1.dp, if (isDragging) accent else experienceLine)) {
                Row(Modifier.fillMaxWidth().avoidOrbCircle().heightIn(min = 76.dp).padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (blocks) {
                        Box(Modifier.size(40.dp, 48.dp).testTag("workstream_drag_${stream.id}")
                            .onGloballyPositioned { handleBounds[stream.id] = it.boundsInRoot() }
                            .semantics {
                                contentDescription = "Reorder ${stream.title}"
                                customActions = listOfNotNull(
                                    if (index > 0) CustomAccessibilityAction("Move up") {
                                        actions.project.onReorderWorkstream(stream.id, project.workstreams[index - 1].id); true
                                    } else null,
                                    if (index < project.workstreams.lastIndex) CustomAccessibilityAction("Move down") {
                                        actions.project.onReorderWorkstream(stream.id, project.workstreams.getOrNull(index + 2)?.id); true
                                    } else null)
                            }, contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(12.dp, 20.dp)) {
                                for (x in 0..1) for (y in 0..2) drawCircle(if (isDragging) accent else Color(0xFF9AA5AC),
                                    radius = 1.6.dp.toPx(), center = Offset(size.width * (.25f + .5f * x), size.height * (.2f + .3f * y)))
                            }
                        }
                    }
                    ProjectWorkstreamCardContent(stream, actions.project)
                }
            }
            if (dragging != null && beforeId == null && index == project.workstreams.lastIndex) HorizontalDivider(color = accent, thickness = 2.dp)
        }

        item {
        Spacer(Modifier.height(19.dp))
        ProjectSectionDisclosure("Standalone tasks", Icons.AutoMirrored.Filled.List, standaloneExpanded,
            "standalone_section_toggle", { standaloneExpanded = !standaloneExpanded }, actions.project.onAddTask, AddTaskButtonTag)
        if (standaloneExpanded) {
            Spacer(Modifier.height(8.dp))
            if (project.standaloneTasks.isEmpty()) Text("None", modifier = Modifier.fillMaxWidth().avoidOrbCircle(), fontSize = 14.sp, color = experienceMuted)
        }
        }
        if (standaloneExpanded) items(project.standaloneTasks.size, key = { "task:${project.standaloneTasks[it].id}" }) { index ->
            val task = project.standaloneTasks[index]
            ProjectStandaloneTaskCard(task, index, actions.project,
                Modifier.fillMaxWidth().padding(bottom = 10.dp))
        }

        item {
        Spacer(Modifier.height(20.dp))
        ProjectSectionDisclosure("Project knowledge", Icons.Outlined.Description, knowledgeExpanded,
            "knowledge_section_toggle", { knowledgeExpanded = !knowledgeExpanded }, actions.knowledge.onAdd)
        if (knowledgeExpanded) {
        Spacer(Modifier.height(8.dp))
        // Overview shows only the most recent saved item — the library lives in its own tab.
        val latest = remember(data.knowledge, project.id) {
            data.knowledge.asSequence().filter { it.projectId == project.id }
                .maxWithOrNull(compareBy<KnowledgeItem> { it.updatedAtMillis }.thenBy { it.id })
        }
        if (latest == null) {
            Text("Nothing saved to this project yet", modifier = Modifier.fillMaxWidth().avoidOrbCircle(), fontSize = 14.sp, color = experienceMuted)
        } else {
            Surface(onClick = { actions.knowledge.onOpen(latest) },
                modifier = Modifier.fillMaxWidth().circularOrbCutout(), shape = RoundedCornerShape(11.dp),
                color = Color.White, border = BorderStroke(1.dp, experienceLine)) {
                Row(Modifier.fillMaxWidth().avoidOrbCircle().heightIn(min = 54.dp).padding(horizontal = 12.dp),
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
        Row(Modifier.fillMaxWidth().avoidOrbCircle(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = openKnowledge) { Text("View all →", color = experienceGreenInk) }
        }
        }
        }
    }
    project.workstreams.firstOrNull { it.id == dragging }?.let { stream ->
        Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .graphicsLayer { translationY = dragY - 38.dp.toPx() }.testTag("dragged_workstream_preview"),
            shape = RoundedCornerShape(12.dp), color = Color.White, shadowElevation = 8.dp,
            border = BorderStroke(2.dp, accent)) {
            Row(Modifier.heightIn(min = 76.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("⠿", color = accent, fontSize = 24.sp)
                Spacer(Modifier.width(12.dp))
                ExecutionDial(stream.state, Modifier.size(40.dp), stream.activeRunCount)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stream.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Release to place here", fontSize = 12.sp, color = accent)
                }
            }
        }
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
    val accent = experienceGreen
    val track = LocalProjectPalette.current?.let { Color(it.tertiary) } ?: Color(0xFFE7F0E1)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 7.dp.toPx()
            val inset = width / 2
            val diameter = size.minDimension - width
            drawArc(track, -90f, 360f, false, Offset(inset, inset),
                Size(diameter, diameter), style = Stroke(width, cap = StrokeCap.Round))
            if (progress > 0f) drawArc(accent, -90f, 360f * progress.coerceIn(0f, 1f), false,
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

@Composable
internal fun androidx.compose.foundation.layout.RowScope.ProjectWorkstreamCardContent(stream: WorkstreamUi, actions: ProjectPageActions) {
if (stream.state == WorkstreamState.COMPLETE) {
    Box(Modifier.size(48.dp).background(Color(0xFFE7F7F0), RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center) {
        Text("✓", color = Color(0xFF009B72), fontSize = 28.sp,
            modifier = Modifier.semantics { contentDescription = "Completed workstream" })
    }
} else ExecutionDial(stream.state, Modifier.size(48.dp), stream.activeRunCount)
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
    Spacer(Modifier.height(5.dp))
    ExecutionBars(stream.state)
}
if (stream.state != WorkstreamState.COMPLETE) {
    Box(Modifier.width(82.dp).heightIn(min = 48.dp)
        .clickable(role = Role.Button, onClickLabel = "Workstream controls for ${stream.title}") {
            actions.onWorkstreamControls(stream.id)
        }.testTag("workstream_controls_${stream.id}"), contentAlignment = Alignment.Center) {
        if (stream.state == WorkstreamState.READY) Text("READY", fontSize = 10.sp, color = experienceMuted)
        else StatePill(stream.state)
    }
}
Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = experienceInk)
}

@Composable
internal fun ProjectSearchWorkstreamCard(stream: WorkstreamUi, actions: ProjectPageActions) {
    Surface(onClick = { actions.onOpenWorkstream(stream.id) },
        modifier = Modifier.fillMaxWidth().circularOrbCutout(),
        shape = RoundedCornerShape(12.dp), color = Color.White,
        border = BorderStroke(1.dp, experienceLine)) {
        Row(Modifier.fillMaxWidth().avoidOrbCircle().heightIn(min = 76.dp).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            ProjectWorkstreamCardContent(stream, actions)
        }
    }
}
