package com.virlin.app.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.ui.hierarchy.ProjectPalette
import com.virlin.app.ui.hierarchy.readableOn

const val PageMoveSheetTag = "page_move_destination_sheet"

/** Where the picker is browsing: the project, one of its workstreams, or inside a task. */
private sealed interface MoveSpot {
    data object Project : MoveSpot
    data class Stream(val id: String) : MoveSpot
    data class InTask(val id: String) : MoveSpot
}

/**
 * The guided destination sheet of the task and map move, for one Page item. Items always live on
 * a task, so workstreams and the project are browsed through and only tasks can be chosen; the
 * item's own task is shown but cannot be chosen. It never writes data.
 */
@Composable
fun PageMoveDestinationSheet(
    itemLabel: String,
    projectTitle: String,
    streams: List<WorkStream>,
    tasks: List<Task>,
    currentTaskId: String,
    palette: ProjectPalette?,
    onDismiss: () -> Unit,
    onMove: (Task) -> Unit,
) {
    val byId = remember(tasks) { tasks.associateBy { it.id } }
    val children = remember(tasks) {
        tasks.filter { it.parentTaskId != null }.groupBy { it.parentTaskId }
            .mapValues { (_, list) -> list.sortedWith(compareBy<Task> { it.order }.thenBy { it.title }) }
    }
    val roots = remember(tasks) {
        tasks.filter { it.parentTaskId == null }.groupBy { it.workStreamId }
            .mapValues { (_, list) -> list.sortedWith(compareBy<Task> { it.order }.thenBy { it.title }) }
    }
    // Every workstream that holds tasks, in the project's own order; none is ever unreachable.
    val usedStreams = remember(streams, roots) {
        val known = streams.filter { roots[it.id].orEmpty().isNotEmpty() }
        known.map { it.id to it.title } + roots.keys.filterNotNull()
            .filter { id -> known.none { it.id == id } }.map { it to "Workstream" }
    }
    // Opens beside the item's own task, where its neighbours are.
    var spot by remember(currentTaskId) {
        val current = byId[currentTaskId]
        mutableStateOf<MoveSpot>(current?.parentTaskId?.takeIf { it in byId }?.let { MoveSpot.InTask(it) }
            ?: current?.workStreamId?.let { MoveSpot.Stream(it) } ?: MoveSpot.Project)
    }
    var selectedId by remember(currentTaskId) { mutableStateOf<String?>(null) }
    var committing by remember(currentTaskId) { mutableStateOf(false) }

    val accent = palette?.let { Color(it.main) } ?: Color(0xFF07865B)
    val ink = palette?.let { Color(it.text) } ?: Color(0xFF17231E)
    val muted = palette?.let { Color(it.muted) } ?: Color(0xFF66736D)
    val wash = Color(androidx.core.graphics.ColorUtils.blendARGB(
        android.graphics.Color.WHITE, accent.toArgb(), .10f))
    val line = accent.copy(alpha = .25f)

    fun parentOf(s: MoveSpot): MoveSpot? = when (s) {
        MoveSpot.Project -> null
        is MoveSpot.Stream -> MoveSpot.Project
        is MoveSpot.InTask -> byId[s.id]?.let { task ->
            task.parentTaskId?.let { MoveSpot.InTask(it) }
                ?: task.workStreamId?.let { MoveSpot.Stream(it) }
        } ?: MoveSpot.Project
    }
    fun spotName(s: MoveSpot): String = when (s) {
        MoveSpot.Project -> projectTitle
        is MoveSpot.Stream -> usedStreams.firstOrNull { it.first == s.id }?.second.orEmpty()
        is MoveSpot.InTask -> byId[s.id]?.title.orEmpty()
    }
    val path = generateSequence(spot) { parentOf(it) }.toList().asReversed().joinToString("  ›  ") { spotName(it) }
    val choices: List<Task> = when (val s = spot) {
        MoveSpot.Project -> roots[null].orEmpty()
        is MoveSpot.Stream -> roots[s.id].orEmpty()
        is MoveSpot.InTask -> children[s.id].orEmpty()
    }
    val selected = selectedId?.let(byId::get)

    BackHandler { parentOf(spot)?.let { spot = it } ?: onDismiss() }
    Box(Modifier.fillMaxSize().testTag(PageMoveSheetTag)) {
        Box(Modifier.fillMaxSize().background(Color(0x660F241B)).clickable(onClick = onDismiss))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.79f)
            .background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 15.dp)
                .size(width = 36.dp, height = 4.dp).background(line, RoundedCornerShape(4.dp)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("MOVE 1 ${itemLabel.uppercase()}", color = accent, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Text("Choose a destination", color = ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("page_move_close")) {
                    Icon(Icons.Default.Close, contentDescription = "Close move destinations", tint = muted)
                }
            }
            Text(path, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                    .background(wash, RoundedCornerShape(12.dp)).padding(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                parentOf(spot)?.let { up ->
                    TextButton(onClick = { spot = up }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp), tint = accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Back one level", color = accent)
                    }
                }
                (spot as? MoveSpot.InTask)?.let { here ->
                    val task = byId[here.id]
                    if (task != null) PageMoveRow(task.title,
                        if (task.id == currentTaskId) "Already here" else "Place inside this task",
                        selected = selectedId == task.id, enabled = task.id != currentTaskId,
                        accent, ink, muted, wash, onSelect = { selectedId = task.id },
                        modifier = Modifier.testTag("page_move_current"))
                }
                if (spot == MoveSpot.Project) usedStreams.forEach { (id, title) ->
                    val count = roots[id].orEmpty().size
                    PageMoveRow(title, "Workstream · $count ${if (count == 1) "task" else "tasks"} · Tap to explore",
                        selected = false, enabled = true, accent, ink, muted, wash,
                        onSelect = { spot = MoveSpot.Stream(id) },
                        onExplore = { spot = MoveSpot.Stream(id) },
                        modifier = Modifier.testTag("page_move_stream_$id"))
                }
                choices.forEach { task ->
                    val count = children[task.id].orEmpty().size
                    val here = task.id == currentTaskId
                    PageMoveRow(task.title,
                        when {
                            here -> "Already here" + if (count > 0) " · Tap to explore" else ""
                            count > 0 -> "$count ${if (count == 1) "subtask" else "subtasks"} · Tap to explore"
                            else -> "No subtasks"
                        },
                        selected = selectedId == task.id, enabled = !here, accent, ink, muted, wash,
                        onSelect = { selectedId = task.id },
                        onExplore = if (count > 0) ({ spot = MoveSpot.InTask(task.id) }) else null,
                        modifier = Modifier.testTag("page_move_task_${task.id}"))
                }
                if (choices.isEmpty() && (spot != MoveSpot.Project || usedStreams.isEmpty()) && spot !is MoveSpot.InTask)
                    Text("There are no tasks here yet.", color = muted, modifier = Modifier.padding(12.dp))
            }
            Row(Modifier.fillMaxWidth().border(1.dp, line).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DESTINATION", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    Text(selected?.title ?: "Choose a task", color = ink, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = { if (!committing) selected?.let { committing = true; onMove(it) } },
                    enabled = selected != null && !committing,
                    modifier = Modifier.testTag("page_move_confirm"),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = readableOn(accent)),
                    shape = RoundedCornerShape(12.dp)) { Text("Move here") }
            }
        }
    }
}

@Composable
private fun PageMoveRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    ink: Color,
    muted: Color,
    wash: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    onExplore: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(bottom = 5.dp)
        .background(if (selected) wash else Color.White, RoundedCornerShape(13.dp))
        .border(if (selected) 1.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(13.dp))
        .clickable(enabled = enabled || onExplore != null, onClick = if (enabled) onSelect else onExplore ?: {})
        .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Folder, null, tint = if (enabled) accent else muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) ink else muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected destination",
            tint = accent, modifier = Modifier.size(20.dp))
        if (onExplore != null) IconButton(onClick = onExplore, modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Explore $title", tint = accent)
        }
    }
}
