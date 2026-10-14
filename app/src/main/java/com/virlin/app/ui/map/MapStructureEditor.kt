package com.virlin.app.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.structure.HierarchyNode
import com.virlin.app.domain.structure.NodeKind
import kotlinx.coroutines.delay

const val MapStructureBarTag = "map_structure_bar"
const val MapMoveSheetTag = "map_move_sheet"
fun mapDestinationTag(id: String) = "map_destination_$id"

// The map surface's palette. Package-visible so every map overlay - the structure bar, the
// selected-task toolbar - reads the same three values instead of repeating the literals.
internal val MapInk = Color(0xFF17202C)
internal val MapMuted = Color(0xFF647087)
internal val MapGreen = Color(0xFF16A34A)
internal val MapHairline = Color(0xFFE1E8E2)

/** What the host is currently doing with a selected branch. */
enum class StructureIntent { MOVE, COPY }

/**
 * An icon-only overlay for the selected node. Its 44dp touch targets and accessibility labels
 * remain present even though the visible border, selected title, and helper copy are gone.
 */
@Composable
fun MapStructureBar(
    selected: MapTopic?,
    /** The selected node plus its descendants. */
    affectedCount: Int,
    clipboard: MapTopic?,
    organizing: Boolean,
    /**
     * True while the floating selected-task toolbar is on screen. That toolbar owns Open Task and
     * Move, so this bar drops both rather than offering the same action twice in two places.
     */
    taskToolbarPresent: Boolean = false,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onAddBranch: () -> Unit = {},
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDuplicate: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    if (selected == null) return
    val openLabel = when (selected.kind) {
        MapKind.TASK -> "Open Task"
        MapKind.WORKSTREAM -> "Open Workstream"
        else -> "Open Project"
    }
    val actions = buildList {
        if (organizing) {
            add(MapToolbarAction(Icons.Default.Edit, "Edit", onEdit))
            add(MapToolbarAction(Icons.Default.AccountTree, "Add branch", onAddBranch))
            if (selected.kind == MapKind.TASK) {
                add(MapToolbarAction(Icons.Default.ContentCopy, "Copy", onCopy))
                add(MapToolbarAction(Icons.Default.FileCopy, "Duplicate", onDuplicate))
            } else if (!taskToolbarPresent) {
                add(MapToolbarAction(Icons.Default.OpenInNew, openLabel, onOpen))
                add(MapToolbarAction(Icons.Default.DriveFileMove, "Move", onMove))
            }
            if (clipboard != null) add(MapToolbarAction(Icons.Default.ContentPaste, "Paste into", onPaste))
        } else if (!taskToolbarPresent) {
            add(MapToolbarAction(Icons.Default.OpenInNew, openLabel, onOpen))
        }
        add(MapToolbarAction(Icons.Default.Close, "Clear selection", onClear))
    }
    Row(
        Modifier.testTag(MapStructureBarTag).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, action ->
            key(selected.id, action.label) {
                MapIconAction(action.icon, action.label,
                    entranceDelayMs = (actions.lastIndex - index) * 40L, action.onClick)
            }
        }
    }
}

private data class MapToolbarAction(
    val icon: ImageVector, val label: String, val onClick: () -> Unit,
)

@Composable
private fun MapIconAction(icon: ImageVector, label: String, entranceDelayMs: Long, onClick: () -> Unit) {
    val entrance = remember { Animatable(0f) }
    val tint = com.virlin.app.ui.hierarchy.LocalProjectPalette.current?.let { Color(it.secondary) } ?: MapGreen
    LaunchedEffect(Unit) {
        delay(entranceDelayMs)
        entrance.animateTo(1f, tween(180))
    }
    Box(
        Modifier.size(44.dp)
            .graphicsLayer {
                alpha = entrance.value
                translationX = (1f - entrance.value) * 10.dp.toPx()
            }
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/**
 * Choosing where a branch goes. Every legal destination in this project is listed with the
 * breadcrumb that says where it is, so a cross-workstream move is never a guess.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapDestinationSheet(
    intent: StructureIntent,
    source: MapTopic,
    affectedCount: Int,
    destinations: List<MapDestination>,
    onDismiss: () -> Unit,
    onChoose: (MapDestination) -> Unit,
) {
    val palette = com.virlin.app.ui.hierarchy.LocalProjectPalette.current
    val ink = palette?.let { Color(it.text) } ?: MapInk
    val muted = palette?.let { Color(it.muted) } ?: MapMuted
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text(
                if (intent == StructureIntent.MOVE) "Move \"${source.title}\""
                else "Copy \"${source.title}\"",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink
            )
            Text(
                if (affectedCount == 1) "1 item will move." else "$affectedCount items will move.",
                fontSize = 12.sp, color = muted
            )
            Spacer(Modifier.height(10.dp))
        }
        if (destinations.isEmpty()) {
            Text(
                "There is nowhere else this branch can go.",
                Modifier.padding(18.dp), fontSize = 13.sp, color = muted
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag(MapMoveSheetTag)) {
            items(destinations, key = { it.node.id }) { destination ->
                Column(
                    Modifier.fillMaxWidth()
                        .testTag(mapDestinationTag(destination.node.id))
                        .clickable(role = Role.Button) { onChoose(destination) }
                        .semantics {
                            contentDescription = destination.title + ", " + destination.breadcrumb
                        }
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    Text(destination.title, fontSize = 15.sp, color = ink, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(destination.breadcrumb, fontSize = 12.sp, color = muted, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** One legal landing place, with the path that identifies it. */
data class MapDestination(
    val node: HierarchyNode,
    val title: String,
    val breadcrumb: String,
)

/**
 * The destinations a branch may legally land in, each with its breadcrumb. The list is built
 * from the same node projection the validator uses, so the sheet can never offer something the
 * command would refuse.
 */
fun destinationsFor(
    nodes: List<HierarchyNode>,
    titles: Map<String, String>,
    sourceId: String,
    projectTitle: String,
): List<MapDestination> {
    val byId = nodes.associateBy { it.id }
    val source = byId[sourceId] ?: return emptyList()
    // The branch itself and everything under it are never destinations.
    val children = nodes.groupBy { it.parentId }
    val inBranch = HashSet<String>()
    val stack = ArrayDeque<String>()
    stack.addLast(sourceId)
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (!inBranch.add(id)) continue
        children[id].orEmpty().forEach { stack.addLast(it.id) }
    }

    fun breadcrumb(node: HierarchyNode): String {
        val path = ArrayList<String>()
        var current: HierarchyNode? = node
        while (current != null) {
            path += if (current.kind == NodeKind.PROJECT) projectTitle
            else titles[current.id] ?: current.id
            current = current.parentId?.let(byId::get)
        }
        return path.asReversed().joinToString(" · ")
    }

    return nodes.asSequence()
        .filter { it.id !in inBranch }
        .filter { candidate ->
            when (source.kind) {
                NodeKind.WORKSTREAM -> candidate.kind == NodeKind.PROJECT
                NodeKind.TASK -> true
                NodeKind.PROJECT -> false
            }
        }
        // Dropping back on the current parent with no reordering is a no-op the command refuses.
        .filter { it.id != source.parentId }
        .map { candidate ->
            MapDestination(
                node = candidate,
                title = when (candidate.kind) {
                    NodeKind.PROJECT -> "$projectTitle (standalone)"
                    else -> titles[candidate.id] ?: candidate.id
                },
                breadcrumb = breadcrumb(candidate)
            )
        }
        .sortedBy { it.breadcrumb.lowercase() }
        .toList()
}

/** A refusal, shown with the validator's own words. */
@Composable
fun PlacementRefusedDialog(reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("That move is not allowed") },
        text = { Text(reason, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Renaming a node in place, saved through the app's own update actions. */
@Composable
fun MapRenameDialog(
    current: String,
    label: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit name") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text(label) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(MapRenameFieldTag)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != current,
                modifier = Modifier.testTag(MapRenameSaveTag)
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * A new child under [parentTitle]. Only the title is asked for; everything else about the new
 * task - its project, its workstream, its position among its siblings - is decided by the
 * canonical `addSubtask` action from the parent, so this dialog cannot disagree with the domain.
 */
@Composable
fun MapAddSubtaskDialog(
    parentTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val muted = com.virlin.app.ui.hierarchy.LocalProjectPalette.current?.let { Color(it.muted) } ?: MapMuted
    com.virlin.app.ui.components.SafeDialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), color = Color.White) {
            com.virlin.app.ui.hierarchy.TaskCreationCard(parentTitle, "Add subtask", onDismiss, onSave,
                titleTag = MapAddSubtaskFieldTag, confirmTag = MapAddSubtaskSaveTag) {
                Text("Use parent · At end", fontSize = 12.sp, color = muted)
            }
        }
    }
}

/** One short piece of text confirmed before anything is written. */
@Composable
fun MapAddTextDialog(
    title: String,
    subtitle: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    initial: String = "",
    singleLine: Boolean = true,
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }
    val muted = com.virlin.app.ui.hierarchy.LocalProjectPalette.current?.let { Color(it.muted) } ?: MapMuted
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(subtitle, fontSize = 13.sp, color = muted)
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text(label) }, singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3,
                    modifier = Modifier.fillMaxWidth().testTag(MapAddTextFieldTag)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(MapAddTextSaveTag)
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

const val MapAddTextFieldTag = "map_add_text_field"
const val MapAddTextSaveTag = "map_add_text_save"

const val MapAddSubtaskFieldTag = "map_add_subtask_field"
const val MapAddSubtaskSaveTag = "map_add_subtask_save"

const val MapRenameFieldTag = "map_rename_field"
const val MapRenameSaveTag = "map_rename_save"


/** A plain message that is not about a placement. */
@Composable
fun MapNoticeDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
