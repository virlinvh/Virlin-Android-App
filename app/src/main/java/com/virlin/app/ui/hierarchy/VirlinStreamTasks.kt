package com.virlin.app.ui.hierarchy
import com.virlin.app.ui.components.avoidOrbCircle
import com.virlin.app.ui.components.circularOrbCutout

// Supplied drop-in stream task page. Adapted where the app's own rules differ from the
// reference's assumptions:
//  - completion is one-way in this domain (DONE is terminal; there is no reopen action), so a
//    finished task's mark is not a toggle and says so instead of silently reopening,
//  - Delete is the app's real policy: a task is CANCELLED — terminal, kept in history, excluded
//    from progress — and the dialog states that plainly, including what happens to its subtree,
//  - Execution uses the app's ExecutionPreference / ExecutionModeResolver chain, and the sheet
//    names the ancestor the effective value actually came from,
//  - reordering is the accessible up/down control plus the destination sheet; no drag library
//    is present in this project, so drag is NOT claimed,
//  - every write goes through VirlinActions and the screen re-reads the observed repository.


import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
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

/** UI contract. Adapt repository entities by stable ID; never create duplicate task records. */
enum class StreamExecutionMode { INHERIT, HUMAN, EXTERNAL }
enum class TaskFilter { ALL, ACTIVE, DONE }
data class StreamTaskUi(
    val id: String,
    val parentId: String?,
    val title: String,
    val order: Int,
    val completed: Boolean = false,
    /** Completed and cancelled work cannot be focused or delegated. */
    val terminal: Boolean = false,
    val isCurrent: Boolean = false,
    val execution: StreamExecutionMode = StreamExecutionMode.INHERIT,
    val effectiveExecution: StreamExecutionMode = StreamExecutionMode.HUMAN,
    val inheritedFrom: String? = null,
    val childCount: Int = 0,
    val progressPercent: Int? = null,
    /** Exact leaf time, or descendant roll-up for a container. */
    val focusedLabel: String? = null,
    val externalLabel: String? = null,
    val activityState: WorkstreamState = WorkstreamState.READY,
)
data class StreamTasksUi(
    val streamId: String,
    val streamName: String,
    val projectName: String,
    val streamPercent: Int?,
    val streamExecution: StreamExecutionMode,
    val streamEffectiveExecution: StreamExecutionMode,
    val tasks: List<StreamTaskUi>,
    val currentTaskId: String? = null,
    /** Read-only execution state shared with the Project dial. */
    val visualState: WorkstreamState = WorkstreamState.READY,
    val stateSummary: String = "Ready",
    val totalFocusedLabel: String? = null,
    val elapsedSeconds: Long? = null,
    val sectionFocusSeconds: Map<String, Long> = emptyMap(),
    val externalSeconds: Long? = null,
    val externalTaskId: String? = null,
    val sectionExternalSeconds: Map<String, Long> = emptyMap(),
    val projectId: String? = null,
    val standalone: Boolean = false,
)
/** Implement these against the existing ViewModel/repository, including loading and error states. */
interface StreamTaskActions {
    fun back()
    fun add(parentId: String?, title: String, afterId: String?, execution: StreamExecutionMode)
    fun addWithResult(parentId: String?, title: String, afterId: String?, execution: StreamExecutionMode, onResult: (String?) -> Unit) {
        add(parentId, title, afterId, execution)
        onResult(null)
    }
    fun rename(taskId: String, title: String)
    fun toggleComplete(taskId: String)
    fun setCurrent(taskId: String)
    fun setExecution(taskId: String, mode: StreamExecutionMode)
    fun setStreamExecution(mode: StreamExecutionMode)
    fun move(taskIds: List<String>, destinationParentId: String?, afterId: String?)
    fun duplicate(taskId: String)
    fun delete(taskIds: List<String>)
    fun focus(taskId: String)
    fun workControls(taskId: String) {}
    fun streamWorkControls() {}
    fun delegate(taskId: String)
    fun openStreamWorkItem(streamId: String)
}
private val Ink: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.text) } ?: Color(0xFF17202C)
private val Muted: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.muted) } ?: Color(0xFF66758A)
private val Line: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.tertiary) } ?: Color(0xFFE4E9ED)
private val Green: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.primary) } ?: Color(0xFF65BB31)
private val DeepGreen: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF176B45)
private val Mint = Color(0xFFEAF7EC)
private val Amber = Color(0xFFE5A000)
private val AmberWash = Color(0xFFFFF5DB)
private val Danger = Color(0xFFC73728)
private val CardShape = RoundedCornerShape(18.dp)

/** Full-width title: actions never steal wrapping space from long ancestor names. */
@Composable
internal fun HierarchySectionHeading(
    title: String,
    progress: String,
    onManage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Text(title, Modifier.fillMaxWidth(), fontSize = 23.sp, lineHeight = 30.sp,
            fontWeight = FontWeight.Bold, color = Ink)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(progress, Modifier.weight(1f), color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            TextButton(onClick = onManage) { Text("Manage", color = DeepGreen) }
        }
    }
}

internal fun hierarchyElapsed(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return if (safe < 3600) "%d:%02d".format(safe / 60, safe % 60)
    else "%d:%02d:%02d".format(safe / 3600, safe / 60 % 60, safe % 60)
}

/** Root list: each sheet displays one level, so arbitrary depth does not crowd the viewport. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirlinStreamTasks(
    ui: StreamTasksUi,
    actions: StreamTaskActions,
    modifier: Modifier = Modifier,
    /** Ancestor task ids to open at, by id. Survives process death with the rest of the screen. */
    initialPath: List<String> = emptyList()
) {
    // Saved as plain ids: a rebuilt process reopens the same level without a live breadcrumb.
    val palette = rememberProjectPalette(ui.projectId)
    CompositionLocalProvider(LocalProjectPalette provides palette) {
    var pathCsv by rememberSaveable(ui.streamId) { mutableStateOf(initialPath.joinToString(",")) }
    val path = remember(pathCsv) { pathCsv.split(',').filter { it.isNotBlank() } }
    var filter by remember(ui.streamId) { mutableStateOf(TaskFilter.ALL) }
    var addFor by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var organize by remember { mutableStateOf(false) }
    var executionFor by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var moveFor by remember { mutableStateOf<List<String>?>(null) }
    var deleteFor by remember { mutableStateOf<List<String>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var streamMenu by remember { mutableStateOf(false) }
    if (showAdd || organize || executionFor != null || menuFor != null ||
        renameFor != null || moveFor != null || deleteFor != null || streamMenu) HideProjectOrb()
    val tasksById = remember(ui.tasks) { ui.tasks.associateBy { it.id } }
    LaunchedEffect(ui.tasks, path) { pathCsv = path.takeWhile { it in tasksById }.joinToString(",") }
    val parentId = path.lastOrNull()
    val siblings = ui.tasks.filter { it.parentId == parentId }.sortedWith(compareBy({ it.order }, { it.id }))
    val visible = siblings.filter {
        when (filter) { TaskFilter.ALL -> true; TaskFilter.ACTIVE -> !it.completed; TaskFilter.DONE -> it.completed }
    }
    val current = ui.currentTaskId?.let(tasksById::get)
    val ancestorIds = remember(ui.tasks, current?.id) {
        buildSet {
            var ancestor = current?.parentId
            while (ancestor != null && add(ancestor)) ancestor = tasksById[ancestor]?.parentId
        }
    }
    val parent = parentId?.let(tasksById::get)
    val rootListTitle = if (ui.standalone) "Standalone tasks" else "Tasks"
    // Each task/subtask level has its own viewport. Keep it in saveable state so navigating
    // down a level, returning with Back, or recreating this route restores the same position.
    var viewports by rememberSaveable(ui.streamId) {
        mutableStateOf<Map<String, List<Int>>>(emptyMap())
    }
    val savedViewport = viewports[pathCsv]
    val listState = remember(pathCsv) {
        LazyListState(savedViewport?.getOrNull(0) ?: 0, savedViewport?.getOrNull(1) ?: 0)
    }
    var headerHeight by remember { mutableFloatStateOf(0f) }
    var headerOffset by remember(pathCsv) {
        mutableFloatStateOf((savedViewport?.getOrNull(2) ?: 0).toFloat())
    }
    LaunchedEffect(pathCsv, listState) {
        snapshotFlow {
            listOf(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset,
                headerOffset.toInt())
        }.collect { viewport -> viewports = viewports + (pathCsv to viewport) }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scrollConnection = remember(pathCsv, headerHeight) {
        ProjectHeaderScrollConnection(enabled = { true }, height = { headerHeight },
            offset = { headerOffset }, updateOffset = { headerOffset = it })
    }
    // Forward gestures started in the fixed chrome to the same list used below. The surrounding
    // nested-scroll connection still consumes header motion first, preserving collapse/reveal.
    val chromeScrollState = rememberScrollableState { delta -> -listState.dispatchRawDelta(-delta) }
    val chromeScroll = Modifier.scrollable(chromeScrollState, Orientation.Vertical)
    Box(modifier.fillMaxSize()) {
    Scaffold(modifier = Modifier.testTag(StreamTasksTag).nestedScroll(scrollConnection), containerColor = palette?.let { Color(it.surface) } ?: Color.White,
        // The app's root Scaffold already places this route above the footer and system bars.
        // A second bottom inset here lifts the shared task/subtask FAB and leaves a blank band.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(Modifier.fillMaxWidth().height(76.dp).then(chromeScroll).testTag("hierarchy_top_bar")
                .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (path.isEmpty()) actions.back() else pathCsv = path.dropLast(1).joinToString(",") }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Ink)
                }
                Text(ui.streamName, Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val externalInSection = parentId == null || ui.externalTaskId == parentId ||
                    generateSequence(ui.externalTaskId) { tasksById[it]?.parentId }
                        .take(tasksById.size + 1).any { it == parentId }
                RotatingHierarchyTimer(
                    if (parentId == null) ui.elapsedSeconds ?: 0L else ui.sectionFocusSeconds[parentId] ?: 0L,
                    if (parentId == null) ui.externalSeconds else ui.sectionExternalSeconds[parentId], parentId ?: ui.streamId, ui.projectId)
            }
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(with(density) { (headerHeight + headerOffset).coerceAtLeast(0f).toDp() })
                    .then(chromeScroll).clipToBounds().testTag("hierarchy_collapsible_details")) {
                    Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).onSizeChanged { headerHeight = it.height.toFloat() }
                        .graphicsLayer { translationY = headerOffset }.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            Text(ui.streamName, color = Muted, fontSize = 12.sp, modifier = Modifier.clickable { pathCsv = "" })
                            path.forEachIndexed { index, id ->
                                Text("  ›  ", color = Muted)
                                Text(tasksById[id]?.title.orEmpty(), color = DeepGreen, fontSize = 12.sp,
                                    modifier = Modifier.clickable { pathCsv = path.take(index + 1).joinToString(",") })
                            }
                        }
                        val counted = siblings.filterNot { it.terminal && !it.completed }
                        val done = counted.count { it.completed }
                        val percent = if (counted.isEmpty()) 0 else done * 100 / counted.size
                        HierarchySectionHeading(
                            title = parent?.title ?: rootListTitle,
                            progress = "$percent% · $done/${counted.size} complete",
                            onManage = { if (parent != null) actions.workControls(parent.id) else actions.streamWorkControls() },
                            modifier = Modifier.padding(top = 14.dp)
                        )
                        Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TaskFilter.entries.forEach { item -> FilterPill(item.name.lowercase().replaceFirstChar(Char::uppercase), filter == item, Modifier.weight(1f)) { filter = item } }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (parentId == null) rootListTitle else "Subtasks", Modifier.weight(1f),
                                color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            TextButton(onClick = { organize = !organize; selected = emptySet() }) { Text(if (organize) "Done" else "↕ Organize", color = DeepGreen) }
                        }
                    }
                    // Feather only the moving details, never the breadcrumbs at rest or list cards.
                    if (headerOffset < 0f && headerHeight + headerOffset > 0f) {
                        Box(Modifier.fillMaxWidth().height(14.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(LocalProjectPalette.current?.let { Color(it.surface) } ?: Color.White, Color.Transparent))))
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("hierarchy_task_list"), state = listState, contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 84.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (visible.isEmpty()) item { Text(if (siblings.isEmpty()) "No tasks here yet" else "No matching tasks", color = Muted, modifier = Modifier.padding(20.dp)) }
                    itemsIndexed(visible, key = { _, task -> task.id }) { index, task ->
                        TaskRow(task, index + 1, task.id in ancestorIds, task.id in selected, organize, ui.visualState,
                            modifier = Modifier.testTag(taskRowTag(task.id)),
                            onOpen = { if (organize) selected = if (task.id in selected) selected - task.id else selected + task.id else pathCsv = (path + task.id).joinToString(",") },
                            onComplete = { actions.toggleComplete(task.id) }, onWorkControls = { actions.workControls(task.id) }, onMenu = {},
                            onReorder = { direction ->
                                val from = siblings.indexOfFirst { it.id == task.id }; val to = from + direction
                                if (to in siblings.indices) actions.move(listOf(task.id), parentId, siblings.filterNot { it.id == task.id }.getOrNull(to - if (to > from) 0 else 1)?.id)
                            })
                    }
                }
            }
        }
    }
    // Anchor the overlay to the route's full viewport, as on the project overview.
    // The nested Scaffold's content slot can reserve space independently of the root footer.
    Box(Modifier.align(Alignment.BottomCenter)) {
        BottomListFeather(palette?.let { Color(it.surface) } ?: Color.White)
    }
    Row(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.End) {
        if (organize && selected.isNotEmpty()) {
            TextButton(onClick = { moveFor = selected.toList() }) { Text("Move") }
            TextButton(onClick = { deleteFor = selected.toList() }) { Text("Cancel tasks") }
        } else FloatingActionButton(
            onClick = { addFor = parentId; showAdd = true },
            modifier = Modifier.size(56.dp).testTag(AddTaskButtonTag),
            shape = RoundedCornerShape(20.dp),
            containerColor = LocalProjectPalette.current?.let { Color(it.main) } ?: Green,
            contentColor = LocalProjectPalette.current?.let { readableOn(Color(it.main)) } ?: Color.White
        ) {
            Icon(Icons.Default.Add, if (parentId == null) "Add task" else "Add subtask", modifier = Modifier.size(28.dp))
        }
    }
    if (showAdd) {
        var addError by remember { mutableStateOf<String?>(null) }
        var adding by remember { mutableStateOf(false) }
        AddTaskSheet(parentTitle = addFor?.let { tasksById[it]?.title } ?: ui.streamName,
            defaultExecution = addFor?.let { tasksById[it]?.effectiveExecution } ?: ui.streamEffectiveExecution,
            siblings = ui.tasks.filter { it.parentId == addFor }.sortedBy { it.order },
            onDismiss = { if (!adding) showAdd = false }, onSave = { title, after, mode ->
                if (!adding) {
                    adding = true; addError = null
                    actions.addWithResult(addFor, title, after, mode) { error ->
                        adding = false; addError = error
                        if (error == null) showAdd = false
                    }
                }
            }, errorMessage = addError,
            heading = if (addFor == null) "Add task" else "Add subtask",
            bulkDestination = com.virlin.app.domain.action.CreateTask("",
                projectId = ui.projectId.takeIf { ui.standalone && addFor == null },
                workStreamId = ui.streamId.takeIf { !ui.standalone && addFor == null }, parentTaskId = addFor),
            bulkProjectId = ui.projectId)
    }
    }
    if (executionFor != null) {
        val task = executionFor?.let(tasksById::get)
        ExecutionSheet(title = task?.title ?: ui.streamName,
            value = task?.execution ?: ui.streamExecution,
            effective = task?.effectiveExecution ?: ui.streamEffectiveExecution,
            source = task?.inheritedFrom ?: if (task == null) ui.projectName else ui.streamName,
            onDismiss = { executionFor = null }, onChange = { mode ->
                if (executionFor == STREAM_KEY) actions.setStreamExecution(mode) else task?.let { actions.setExecution(it.id, mode) }
                executionFor = null
            })
    }
    renameFor?.let { id -> RenameDialog(tasksById[id]?.title.orEmpty(), { renameFor = null }) { actions.rename(id, it); renameFor = null } }
    moveFor?.let { ids -> MoveSheet(ui, ids, { moveFor = null; selected = emptySet() }) { destination -> actions.move(ids, destination, null); moveFor = null; selected = emptySet() } }
    deleteFor?.let { ids ->
        val subtree = remember(ids, ui.tasks) {
            val byParent = ui.tasks.groupBy { it.parentId }
            var frontier = ids.toSet(); var total = 0
            while (frontier.isNotEmpty()) {
                val next = frontier.flatMap { byParent[it].orEmpty() }.map { it.id }.toSet()
                total += next.size; frontier = next
            }
            total
        }
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Cancel ${if (ids.size == 1) "this task" else "${ids.size} tasks"}?") },
            text = {
                Text(
                    "Virlin cancels rather than erases: " +
                        (if (ids.size == 1) "the task" else "the tasks") +
                        (if (subtree > 0) " and $subtree nested subtask${if (subtree == 1) "" else "s"}" else "") +
                        " leave active work and stop counting towards progress, and their history stays."
                )
            },
            confirmButton = {
                TextButton(onClick = { actions.delete(ids); deleteFor = null; selected = emptySet() }) {
                    Text("Cancel tasks", color = Danger)
                }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Keep") } }
        )
    }
}
}
private const val STREAM_KEY = "__stream__"
private fun pathLabel(task: StreamTaskUi, byId: Map<String, StreamTaskUi>): String {
    val names = mutableListOf<String>(); var cursor = task.parentId
    while (cursor != null && cursor in byId) { names += byId.getValue(cursor).title; cursor = byId[cursor]?.parentId }
    return names.reversed().joinToString(" / ")
}
@Composable private fun FilterPill(label: String, chosen: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.background(if (chosen) Green else Color(0xFFF4F5F6), RoundedCornerShape(15.dp)).clickable(onClick = onClick).padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (chosen) { if(LocalProjectPalette.current==null) Color.White else readableOn(Green) } else Muted, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium)
    }
}
@Composable private fun StatusMark(done: Boolean = false, current: Boolean = false) {
    Box(Modifier.size(30.dp).background(if (done) Green else if (current) AmberWash else Color.White, CircleShape).border(2.dp, if (done) Green else if (current) Amber else Line, CircleShape), contentAlignment = Alignment.Center) {
        if (done) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
        if (current) Box(Modifier.size(15.dp).background(Amber, CircleShape))
    }
}
private fun executionWash(state: WorkstreamState): Color = when (state) {
    WorkstreamState.HUMAN_FOCUS -> Color(0xFFEAF8F1)
    WorkstreamState.EXTERNAL_RUNNING -> Color(0xFFF1EDFF)
    WorkstreamState.MULTI_RUN -> Color(0xFFF0EDFF)
    WorkstreamState.NEEDS_ATTENTION -> AmberWash
    WorkstreamState.BLOCKED -> Color(0xFFFFECE9)
    else -> Color(0xFFF4F6F5)
}
@Composable internal fun TaskRow(task: StreamTaskUi, number: Int, containsCurrent: Boolean, selected: Boolean, organize: Boolean, executionState: WorkstreamState, modifier: Modifier = Modifier, onOpen: () -> Unit, onComplete: () -> Unit, onWorkControls: () -> Unit, onMenu: () -> Unit, onReorder: (Int) -> Unit) {
    val rowState = task.activityState.takeIf { it != WorkstreamState.READY } ?: if (task.isCurrent) executionState else WorkstreamState.READY
    val tint = if (rowState != WorkstreamState.READY) executionWash(rowState) else if (selected || containsCurrent) Mint else Color.White
    Row(modifier.fillMaxWidth().circularOrbCutout().background(tint, CardShape).border(1.dp, if (selected) Green else Line, CardShape).clickable(onClick = onOpen).avoidOrbCircle().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (organize) {
            TextButton(onClick = onOpen) { Text(if (selected) "Selected" else "Select", color = DeepGreen, fontSize = 10.sp) }
            // No drag library is present in this project, so ordering is done with explicit,
            // accessible controls rather than a gesture that is not really implemented.
            Column {
                IconButton(onClick = { onReorder(-1) }, modifier = Modifier.size(32.dp)) {
                    Text("▲", color = DeepGreen)
                }
                IconButton(onClick = { onReorder(1) }, modifier = Modifier.size(32.dp)) {
                    Text("▼", color = DeepGreen)
                }
            }
        } else Box(Modifier.clickable(onClick = onWorkControls)) {
            ExecutionDial(if (task.completed) WorkstreamState.COMPLETE else rowState, Modifier.size(38.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = buildList {
                if (task.childCount > 0) add("${task.progressPercent?.let { "$it% · " }.orEmpty()}${task.childCount} subtasks")
                task.focusedLabel?.let { add("$it focused") }
                task.externalLabel?.let { add("$it external") }
            }.joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 12.sp)
        }
        if (!organize) {
            if (!task.completed) Box(Modifier.width(82.dp).clickable(onClickLabel = "Work controls for ${task.title}", onClick = onWorkControls), contentAlignment = Alignment.Center) {
                if (rowState != WorkstreamState.READY) StatePill(rowState)
                else Text("WORK", color = Color(0xFF176B45), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Mint, RoundedCornerShape(50)).padding(12.dp))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = Ink, modifier = Modifier.size(22.dp).testTag("task_navigation_arrow_${task.id}"))
        }
    }
}
@Composable private fun TaskMenu(
    allowWorkActions: Boolean,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        ((if (allowWorkActions) listOf("Work controls") else emptyList()) +
            listOf("Edit name", "Add subtask", "Move to…", "Duplicate", "Cancel task"))
            .forEach { label ->
            DropdownMenuItem(text = { Text(label, color = if (label == "Cancel task") Danger else Ink) }, onClick = { onAction(label) })
        }
    }
}
@Composable private fun EmptyStream(ui: StreamTasksUi, parentId: String?, onAdd: () -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(112.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) { Text("☷", color = Green, fontSize = 42.sp) }
        Spacer(Modifier.height(22.dp)); Text(if (parentId == null) "No tasks yet" else "No subtasks yet", color = Ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(if (parentId == null && ui.standalone) "Add a task directly to this project."
            else if (parentId == null) "Break this stream into tasks, or keep it as a work item."
            else "Add a subtask here. Every subtask can have its own subtasks.",
            color = Muted, modifier = Modifier.padding(18.dp), lineHeight = 21.sp)
        // An empty level shows this instead of the bottom bar's "+ Task"; only one is ever present.
        val addColor = LocalProjectPalette.current?.let { Color(it.main) } ?: Green
        Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = addColor, contentColor = if(LocalProjectPalette.current==null) Color.White else readableOn(addColor)), modifier = Modifier.fillMaxWidth().testTag(AddTaskButtonTag).padding(horizontal = 20.dp)) { Icon(Icons.Default.Add, null); Text(if (parentId == null) "Add first task" else "Add subtask") }
        if (parentId == null && !ui.standalone) TextButton(onClick = onKeep) { Text("Keep stream as work item", color = DeepGreen) }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun AddTaskSheet(parentTitle: String, defaultExecution: StreamExecutionMode, siblings: List<StreamTaskUi>, onDismiss: () -> Unit, onSave: (String, String?, StreamExecutionMode) -> Unit, heading: String = "Add subtask",
    bulkDestination: com.virlin.app.domain.action.CreateTask? = null, bulkProjectId: String? = null, errorMessage: String? = null) {
    TaskCreationSheet(parentTitle, defaultExecution, siblings, onDismiss, onSave, heading,
        bulkDestination, errorMessage)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ExecutionSheet(title: String, value: StreamExecutionMode, effective: StreamExecutionMode, source: String, onDismiss: () -> Unit, onChange: (StreamExecutionMode) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
            Text("Execution", color = Ink, fontWeight = FontWeight.Bold, fontSize = 23.sp)
            Text(title, color = Muted, modifier = Modifier.padding(bottom = 12.dp))
            listOf(StreamExecutionMode.INHERIT to "Use the nearest ancestor's execution", StreamExecutionMode.HUMAN to "Do it yourself", StreamExecutionMode.EXTERNAL to "Performed outside").forEach { (mode, hint) ->
                Row(Modifier.fillMaxWidth().clickable { onChange(mode) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(value == mode, onClick = { onChange(mode) }, colors = RadioButtonDefaults.colors(selectedColor = Green))
                    Column { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase), color = Ink, fontWeight = FontWeight.SemiBold); Text(hint, color = Muted, fontSize = 12.sp) }
                }
            }
            Text("Effective: ${effective.name.lowercase().replaceFirstChar(Char::uppercase)} · from $source", modifier = Modifier.fillMaxWidth().background(Mint, CardShape).padding(14.dp), color = DeepGreen)
            Spacer(Modifier.height(24.dp))
        }
    }
}
@Composable private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit task name") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save", color = DeepGreen) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MoveSheet(ui: StreamTasksUi, ids: List<String>, onDismiss: () -> Unit, onMove: (String?) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp)) {
            Text("Move ${ids.size} ${if (ids.size == 1) "task" else "tasks"}", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Ink)
            Text("Choose a parent in this stream", color = Muted)
            TextButton(onClick = { onMove(null) }) { Text("Stream root", color = DeepGreen) }
            val excluded = buildSet { addAll(ids); var changed: Boolean; do { val sizeBefore = size; ui.tasks.filter { it.parentId in this }.forEach { add(it.id) }; changed = size != sizeBefore } while (changed) }
            ui.tasks.filterNot { it.id in excluded }.sortedBy { it.order }.forEach { target ->
                TextButton(onClick = { onMove(target.id) }) { Text("${pathLabel(target, ui.tasks.associateBy { it.id })} / ${target.title}", color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Test tag for the stream task page root. */
const val StreamTasksTag = "stream_tasks"
