package com.virlin.app.ui.todo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.hierarchy.ProjectPalette

private val Ink = Color(0xFF17221D)
private val Muted = Color(0xFF677384)
private val Green = Color(0xFF087848)
private val Mint = Color(0xFFE4F6EB)
private val Line = Color(0xFFE0E8E3)

/** Adapt the current task_steps entities to this immutable UI projection. */
data class TodoItemUi(val id: String, val text: String, val done: Boolean, val order: Int)

/** The host routes these commands to canonical task-step actions; no map-only records. */
data class TodoActions(
    val onBack: () -> Unit,
    val onRenameList: (String) -> Unit,
    /** Return true only when the command is accepted; keep the draft on failure. */
    val onAdd: (String) -> Boolean,
    val onEdit: (String, String) -> Unit,
    val onToggle: (String, Boolean) -> Unit,
    val onMoveBefore: (sourceId: String, beforeId: String?) -> Unit,
    val onDelete: (String) -> Unit,
    val onClearCompleted: () -> Unit,
    val onUndo: () -> Unit,
    /** Optional non-blocking persistence; completion reports whether the draft can be cleared. */
    val onAddAsync: ((String, (Boolean) -> Unit) -> Unit)? = null,
    val onBulkImport: ((List<String>, (Boolean) -> Unit) -> Unit)? = null,
)

/**
 * Full-page task-owned checklist. The caller supplies the app's real footer/Orb.
 * Drag uses a dedicated grip, stable IDs and a before-ID insertion anchor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirlinTodoScreen(
    taskId: String,
    taskTitle: String,
    listTitle: String,
    saveLabel: String,
    items: List<TodoItemUi>,
    actions: TodoActions,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** True only when the host has somewhere to persist a list title. */
    canRenameList: Boolean = false,
    palette: ProjectPalette? = null,
) {
    val ordered = remember(items) { items.sortedWith(compareBy<TodoItemUi> { it.order }.thenBy { it.id }) }
    val completed = ordered.count { it.done }
    val ink = palette?.let { Color(it.text) } ?: Ink
    val muted = palette?.let { Color(it.muted) } ?: Muted
    val accent = palette?.let { Color(it.secondary) } ?: Green
    val wash = palette?.let { Color(it.tertiary) } ?: Mint
    val surface = palette?.let { Color(it.surface) } ?: Color.White
    var arrange by rememberSaveable(taskId) { mutableStateOf(false) }
    var filter by rememberSaveable(taskId) { mutableStateOf("all") }
    var activeFirst by rememberSaveable(taskId) { mutableStateOf(false) }
    val displayed = remember(ordered, filter, activeFirst, arrange) {
        val filtered = if (arrange) ordered else ordered.filter {
            filter == "all" || (filter == "done") == it.done
        }
        if (activeFirst && !arrange) filtered.sortedWith(compareBy<TodoItemUi> { it.done }.thenBy { it.order })
        else filtered
    }
    var draft by rememberSaveable(taskId) { mutableStateOf("") }
    var adding by remember(taskId) { mutableStateOf(false) }
    var editId by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var editValue by rememberSaveable(taskId) { mutableStateOf("") }
    var rename by rememberSaveable(taskId) { mutableStateOf(false) }
    var renameValue by rememberSaveable(taskId) { mutableStateOf("") }
    var confirmClear by rememberSaveable(taskId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var bulkImport by remember(taskId) { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dropBeforeId by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    val rowBounds = remember(taskId) { mutableStateMapOf<String, Pair<Float, Float>>() }

    fun chooseBefore(y: Float, sourceId: String): String? = ordered
        .asSequence().filter { it.id != sourceId }
        .firstOrNull { item -> y < (rowBounds[item.id]?.let { (top, bottom) -> (top + bottom) / 2f }
            ?: Float.NEGATIVE_INFINITY) }?.id

    BackHandler { if (draggingId != null) draggingId = null else actions.onBack() }
    Column(modifier.fillMaxSize().background(surface)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = ink)
            }
            Text(taskTitle, Modifier.weight(1f), fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (arrange) {
                Text("Rearranging", color = accent, fontSize = 12.sp,
                    modifier = Modifier.background(wash, RoundedCornerShape(20.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp))
                TextButton(onClick = { arrange = false }) { Text("Done", color = accent) }
            } else {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Checklist options", tint = ink)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (actions.onBulkImport != null) DropdownMenuItem(text = { Text("Bulk Import") }, onClick = {
                            menu = false; bulkImport = true
                        })
                        DropdownMenuItem(text = { Text("Arrange") }, onClick = {
                            menu = false; filter = "all"; activeFirst = false; arrange = true
                        })
                        DropdownMenuItem(text = { Text("Clear completed") },
                            enabled = completed > 0, onClick = {
                                menu = false; confirmClear = true
                            })
                        DropdownMenuItem(text = { Text("Undo last change") }, onClick = {
                            menu = false; actions.onUndo()
                        })
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("TASK LIST", color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (canRenameList) listTitle.ifBlank { "To-do" } else "To-do",
                        fontSize = 29.sp, fontWeight = FontWeight.SemiBold, color = ink, maxLines = 2)
                    Text(if (canRenameList) taskTitle else listTitle.ifBlank { taskTitle },
                        color = muted, fontSize = 14.sp, maxLines = 2)
                }
                // Rename appears only when the host can actually store a list title. The live
                // `task_steps` schema has no such column, so the heading is derived from the
                // task and this control stays away rather than offering an edit that would be
                // discarded the moment the page is reopened.
                if (canRenameList) IconButton(onClick = { renameValue = listTitle; rename = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename list", tint = muted)
                }
                Column(Modifier.size(56.dp).background(wash, RoundedCornerShape(50)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Text("$completed/${ordered.size}", color = accent, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold)
                    Text("done", color = accent, fontSize = 10.sp)
                }
            }
            if (saveLabel != "No changes") Text(saveLabel, color = muted, fontSize = 12.sp)
            Spacer(Modifier.height(19.dp))
            LinearProgressIndicator(
                progress = { if (ordered.isEmpty()) 0f else completed.toFloat() / ordered.size },
                modifier = Modifier.fillMaxWidth().height(5.dp), color = accent, trackColor = wash,
            )
            Spacer(Modifier.height(18.dp))
            if (!arrange) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("all" to "All ${ordered.size}", "active" to "To do ${ordered.size - completed}",
                    "done" to "Done $completed").forEach { (key, label) ->
                    FilterChip(selected = filter == key, onClick = { filter = key },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.weight(1f).testTag("todo_filter_$key"),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = surface,
                            labelColor = ink,
                            selectedContainerColor = wash,
                            selectedLabelColor = accent,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = filter == key,
                            borderColor = accent.copy(alpha = .45f),
                            selectedBorderColor = accent,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (arrange) "Arrange to-dos" else when (filter) {
                    "done" -> "Completed"; "active" -> "To do"; else -> "Your to-dos"
                }, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (arrange) arrange = false else activeFirst = !activeFirst
                }) {
                    Icon(if (arrange) Icons.Outlined.DragIndicator else Icons.Outlined.SwapVert,
                        contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (arrange) "Done" else if (activeFirst) "Active first" else "Manual order",
                        color = accent, fontSize = 12.sp)
                }
            }
            if (displayed.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(54.dp).background(wash, RoundedCornerShape(17.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.ListAlt, contentDescription = null, tint = accent)
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(if (ordered.isEmpty()) "Your list starts here" else "Nothing here yet",
                        color = ink, fontWeight = FontWeight.SemiBold)
                    Text(if (ordered.isEmpty()) "Add the first step for $taskTitle."
                        else if (filter == "done") "Completed to-dos will appear here."
                        else "Everything is checked off.", color = muted, fontSize = 14.sp,
                        textAlign = TextAlign.Center)
                }
            }
            displayed.forEach { item ->
                // The insertion stroke is painted, never inserted into layout: no mid-drag reflow.
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .onGloballyPositioned { coordinates ->
                            if (draggingId == null) {
                                val rect = coordinates.boundsInRoot()
                                rowBounds[item.id] = rect.top to rect.bottom
                            }
                        }
                        .zIndex(if (draggingId == item.id) 2f else 0f)
                        .graphicsLayer {
                            if (draggingId == item.id) {
                                val original = rowBounds[item.id]
                                translationY = dragY - ((original?.first ?: 0f) +
                                    (original?.second ?: 0f)) / 2f
                                shadowElevation = 12.dp.toPx()
                                shape = RoundedCornerShape(12.dp)
                                clip = true
                            }
                        }
                        .background(if (draggingId == item.id) surface else Color.Transparent)
                        .drawWithContent {
                            drawContent()
                            if (draggingId != null && draggingId != item.id && dropBeforeId == item.id) {
                                drawLine(accent, Offset(0f, 0f), Offset(size.width, 0f),
                                    2.dp.toPx(), cap = StrokeCap.Round)
                                drawCircle(accent, 4.dp.toPx(), Offset(4.dp.toPx(), 0f))
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = item.done, onCheckedChange = { actions.onToggle(item.id, it) },
                        colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = accent),
                        modifier = Modifier.size(48.dp).testTag("todo_check_${item.id}"))
                    Text(item.text, modifier = Modifier.weight(1f).clickable {
                        editId = item.id; editValue = item.text
                    }.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        fontSize = 16.sp, color = if (item.done) muted else ink,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null)
                    if (!arrange) IconButton(onClick = { editId = item.id; editValue = item.text },
                        modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.ChevronRight, "Open ${item.text}", tint = muted)
                    }
                    // Dedicated handle: checkbox/tap cannot accidentally begin a drag.
                    if (arrange) {
                    var gripRootY by remember(item.id) { mutableStateOf(0f) }
                    Box(
                        Modifier.size(48.dp)
                            .onGloballyPositioned { gripRootY = it.boundsInRoot().top }
                            .pointerInput(item.id, ordered.map { it.id }) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { local ->
                                        draggingId = item.id
                                        dragY = gripRootY + local.y
                                        dropBeforeId = chooseBefore(dragY, item.id)
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragY += amount.y
                                        dropBeforeId = chooseBefore(dragY, item.id)
                                    },
                                    onDragEnd = {
                                        val before = chooseBefore(dragY, item.id)
                                        val remaining = ordered.filter { it.id != item.id }.map { it.id }
                                        val result = remaining.toMutableList().apply {
                                            add(if (before == null) size else indexOf(before), item.id)
                                        }
                                        if (result != ordered.map { it.id }) actions.onMoveBefore(item.id, before)
                                        draggingId = null; dropBeforeId = null
                                    },
                                    onDragCancel = { draggingId = null; dropBeforeId = null },
                                )
                            }
                            .semantics { contentDescription = "Drag to reorder ${item.text}" }
                            .testTag("todo_grip_${item.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.DragIndicator, contentDescription = null, tint = muted)
                    }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            }
            // End slot overlays the fixed-height trailing spacer; it does not shift rows.
            Box(Modifier.fillMaxWidth().height(20.dp).drawWithContent {
                drawContent()
                if (draggingId != null && dropBeforeId == null) {
                    drawLine(accent, Offset.Zero, Offset(size.width, 0f),
                        2.dp.toPx(), cap = StrokeCap.Round)
                }
            })
            Spacer(Modifier.height(20.dp))
        }
        // Keep the quick-add field directly above the shared navigation, and lift it with the
        // keyboard. To-do routes do not display an Orb, so no floating-button gap is needed.
        val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Row(
            Modifier.fillMaxWidth()
                .imePadding()
                .padding(
                    start = 20.dp, end = 20.dp, top = 8.dp,
                    bottom = if (imeUp) 8.dp else 16.dp,
                ),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, enabled = !adding, modifier = Modifier.weight(1f),
                placeholder = { Text("Add a to-do") }, singleLine = true,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(10.dp))
            Surface(onClick = {
                val value = draft.trim()
                if (value.isNotEmpty() && !adding) {
                    val async = actions.onAddAsync
                    if (async == null) {
                        if (actions.onAdd(value)) draft = ""
                    } else {
                        adding = true
                        async(value) { accepted ->
                            if (accepted) draft = ""
                            adding = false
                        }
                    }
                }
            }, shape = RoundedCornerShape(14.dp), color = accent,
                modifier = Modifier.size(48.dp).testTag("todo_add")) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add item", tint = Color.White)
                }
            }
        }
        footer()
    }

    if (rename) EditTextDialog("Rename list", renameValue, { renameValue = it }, accent,
        onDismiss = { rename = false }, onSave = {
            actions.onRenameList(renameValue.trim()); rename = false
        })
    val editing = ordered.firstOrNull { it.id == editId }
    if (editing != null) ModalBottomSheet(onDismissRequest = { editId = null }, containerColor = surface) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("To-do details", color = ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = editValue, onValueChange = { editValue = it },
                label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = { actions.onToggle(editing.id, !editing.done); editId = null }) {
                Text(if (editing.done) "Mark as to do" else "Mark complete", color = accent)
            }
            val index = ordered.indexOfFirst { it.id == editing.id }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    actions.onMoveBefore(editing.id, ordered[index - 1].id); editId = null
                }, enabled = index > 0) { Text("Move up", color = if (index > 0) accent else muted) }
                TextButton(onClick = {
                    val remaining = ordered.filter { it.id != editing.id }
                    actions.onMoveBefore(editing.id, remaining.getOrNull(index + 1)?.id)
                    editId = null
                }, enabled = index < ordered.lastIndex) {
                    Text("Move down", color = if (index < ordered.lastIndex) accent else muted)
                }
            }
            TextButton(onClick = { actions.onDelete(editing.id); editId = null }) {
                Text("Delete to-do", color = Color(0xFFAF3434))
            }
            Spacer(Modifier.height(10.dp))
            Surface(onClick = {
                val value = editValue.trim()
                if (value.isNotEmpty()) { if (value != editing.text) actions.onEdit(editing.id, value); editId = null }
            }, modifier = Modifier.fillMaxWidth().height(48.dp), color = accent,
                shape = RoundedCornerShape(14.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("Save changes", color = Color.White) }
            }
        }
    }
    if (bulkImport && actions.onBulkImport != null) TodoBulkImportDialog(
        palette = palette, onDismiss = { bulkImport = false }, onImport = actions.onBulkImport,
    )
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear completed items?") },
        text = { Text("Remove $completed completed items from this list. You can undo this change.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false; actions.onClearCompleted()
        }) { Text("Clear $completed", color = accent) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable private fun EditTextDialog(
    title: String, value: String, onValue: (String) -> Unit, accent: Color,
    onDismiss: () -> Unit, onSave: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, onValue, singleLine = true) },
        confirmButton = { TextButton(onClick = onSave, enabled = value.isNotBlank()) {
            Text("Save", color = accent)
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
