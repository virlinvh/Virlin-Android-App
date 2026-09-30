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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.virlin.app.ui.navigation.OrbReservedSpace
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
)

/**
 * Full-page task-owned checklist. The caller supplies the app's real footer/Orb.
 * Drag uses a dedicated grip, stable IDs and a before-ID insertion anchor.
 */
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
) {
    val ordered = remember(items) { items.sortedWith(compareBy<TodoItemUi> { it.order }.thenBy { it.id }) }
    val completed = ordered.count { it.done }
    var arrange by rememberSaveable(taskId) { mutableStateOf(false) }
    var draft by rememberSaveable(taskId) { mutableStateOf("") }
    var editId by rememberSaveable(taskId) { mutableStateOf<String?>(null) }
    var editValue by rememberSaveable(taskId) { mutableStateOf("") }
    var rename by rememberSaveable(taskId) { mutableStateOf(false) }
    var renameValue by rememberSaveable(taskId) { mutableStateOf("") }
    var confirmClear by rememberSaveable(taskId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dropBeforeId by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    val rowBounds = remember(taskId) { mutableStateMapOf<String, Pair<Float, Float>>() }

    fun chooseBefore(y: Float, sourceId: String): String? = ordered
        .asSequence().filter { it.id != sourceId }
        .firstOrNull { item -> y < (rowBounds[item.id]?.let { (top, bottom) -> (top + bottom) / 2f }
            ?: Float.NEGATIVE_INFINITY) }?.id

    BackHandler { if (draggingId != null) draggingId = null else actions.onBack() }
    Column(modifier.fillMaxSize().background(Color.White)) {
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Ink)
            }
            Icon(Icons.Outlined.CheckBox, contentDescription = null, tint = Green)
            Spacer(Modifier.width(10.dp))
            Text("$taskTitle · To-do", Modifier.weight(1f), fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (arrange) {
                Text("Rearranging", color = Green, fontSize = 12.sp,
                    modifier = Modifier.background(Mint, RoundedCornerShape(20.dp))
                        .padding(horizontal = 9.dp, vertical = 6.dp))
                TextButton(onClick = { arrange = false }) { Text("Done", color = Green) }
            } else {
                Text(saveLabel, color = Muted, fontSize = 12.sp)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Checklist options", tint = Ink)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Arrange") }, onClick = {
                            menu = false; arrange = true
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
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listTitle.ifBlank { "To-do list" }, Modifier.weight(1f), fontSize = 25.sp,
                    fontWeight = FontWeight.Bold, color = Ink, maxLines = 2)
                // Rename appears only when the host can actually store a list title. The live
                // `task_steps` schema has no such column, so the heading is derived from the
                // task and this control stays away rather than offering an edit that would be
                // discarded the moment the page is reopened.
                if (canRenameList) IconButton(onClick = { renameValue = listTitle; rename = true }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Rename list", tint = Muted)
                }
            }
            Text("$completed of ${ordered.size} complete", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
                // At large counts a proportional line replaces dozens of tiny segments.
                if (ordered.size <= 12) ordered.forEachIndexed { index, _ ->
                    Box(Modifier.weight(1f).height(5.dp).background(
                        if (index < completed) Green else Line, RoundedCornerShape(5.dp)))
                } else {
                    Box(Modifier.weight(completed.coerceAtLeast(1).toFloat()).height(5.dp)
                        .background(if (completed > 0) Green else Line, RoundedCornerShape(5.dp)))
                    if (completed < ordered.size) Box(
                        Modifier.weight((ordered.size - completed).toFloat()).height(5.dp)
                            .background(Line, RoundedCornerShape(5.dp)))
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { arrange = !arrange }) {
                    Text(if (arrange) "Done arranging" else "↕  Arrange", color = Ink)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = completed > 0) {
                    Text("Clear completed", color = if (completed > 0) Ink else Muted)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            if (ordered.isEmpty()) {
                Text("Add the first item to this task.", color = Muted,
                    modifier = Modifier.padding(vertical = 40.dp))
            }
            ordered.forEach { item ->
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
                        .background(if (draggingId == item.id) Color.White else Color.Transparent)
                        .drawWithContent {
                            drawContent()
                            if (draggingId != null && draggingId != item.id && dropBeforeId == item.id) {
                                drawLine(Green, Offset(0f, 0f), Offset(size.width, 0f),
                                    2.dp.toPx(), cap = StrokeCap.Round)
                                drawCircle(Green, 4.dp.toPx(), Offset(4.dp.toPx(), 0f))
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = item.done, onCheckedChange = { actions.onToggle(item.id, it) },
                        colors = CheckboxDefaults.colors(checkedColor = Green),
                        modifier = Modifier.size(48.dp).testTag("todo_check_${item.id}"))
                    Text(item.text, modifier = Modifier.weight(1f).clickable {
                        editId = item.id; editValue = item.text
                    }.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        fontSize = 16.sp, color = if (item.done) Muted else Ink,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null)
                    Box {
                        var rowMenu by remember(item.id) { mutableStateOf(false) }
                        IconButton(onClick = { rowMenu = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.MoreVert, "Item options", tint = Muted)
                        }
                        DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = {
                                rowMenu = false; editId = item.id; editValue = item.text
                            })
                            val index = ordered.indexOfFirst { it.id == item.id }
                            DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0,
                                onClick = {
                                    rowMenu = false
                                    actions.onMoveBefore(item.id, ordered[index - 1].id)
                                })
                            DropdownMenuItem(text = { Text("Move down") }, enabled = index < ordered.lastIndex,
                                onClick = {
                                    rowMenu = false
                                    val remaining = ordered.filter { it.id != item.id }
                                    actions.onMoveBefore(item.id, remaining.getOrNull(index + 1)?.id)
                                })
                            DropdownMenuItem(text = { Text("Delete item") }, onClick = {
                                rowMenu = false; actions.onDelete(item.id)
                            })
                        }
                    }
                    // Dedicated handle: checkbox/tap cannot accidentally begin a drag.
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
                        Icon(Icons.Outlined.DragIndicator, contentDescription = null, tint = Muted)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            }
            // End slot overlays the fixed-height trailing spacer; it does not shift rows.
            Box(Modifier.fillMaxWidth().height(20.dp).drawWithContent {
                drawContent()
                if (draggingId != null && dropBeforeId == null) {
                    drawLine(Green, Offset.Zero, Offset(size.width, 0f),
                        2.dp.toPx(), cap = StrokeCap.Round)
                }
            })
            Spacer(Modifier.height(20.dp))
        }
        // The Orb floats in the bottom-right of the content area, exactly where the green add
        // button sits, so the composer reserves the Orb's own band plus a gap. While the
        // keyboard is up it rides the IME inset instead: the Orb is behind the keyboard then,
        // and reserving for it would only leave dead space above the letters.
        val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Row(
            Modifier.fillMaxWidth()
                .imePadding()
                .padding(
                    start = 20.dp, end = 20.dp, top = 8.dp,
                    bottom = if (imeUp) 8.dp else OrbReservedSpace + 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Add an item") }, singleLine = true,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.width(10.dp))
            Surface(onClick = {
                val value = draft.trim()
                if (value.isNotEmpty() && actions.onAdd(value)) draft = ""
            }, shape = RoundedCornerShape(50), color = Green,
                modifier = Modifier.size(48.dp).testTag("todo_add")) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, contentDescription = "Add item", tint = Color.White)
                }
            }
        }
        footer()
    }

    if (rename) EditTextDialog("Rename list", renameValue, { renameValue = it },
        onDismiss = { rename = false }, onSave = {
            actions.onRenameList(renameValue.trim()); rename = false
        })
    val editing = editId?.takeIf { id -> ordered.any { it.id == id } }
    if (editing != null) EditTextDialog("Edit item", editValue, { editValue = it },
        onDismiss = { editId = null }, onSave = {
            actions.onEdit(editing, editValue.trim()); editId = null
        })
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear completed items?") },
        text = { Text("Remove $completed completed items from this list. You can undo this change.") },
        confirmButton = { TextButton(onClick = {
            confirmClear = false; actions.onClearCompleted()
        }) { Text("Clear $completed", color = Green) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@Composable private fun EditTextDialog(
    title: String, value: String, onValue: (String) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value, onValue, singleLine = true) },
        confirmButton = { TextButton(onClick = onSave, enabled = value.isNotBlank()) {
            Text("Save", color = Green)
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
