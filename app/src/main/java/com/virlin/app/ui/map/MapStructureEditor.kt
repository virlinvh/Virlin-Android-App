package com.virlin.app.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * The bar for the selected node.
 *
 * It appears in BOTH modes. While viewing it is a compact strip that names the node and offers
 * to open it; while [organizing] it also carries the placement actions, every one of which ends
 * in the same `placeBranch` command. Opening is always an explicit button - a tap on the canvas
 * only ever selects - so selection never navigates by surprise.
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
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().testTag(MapStructureBarTag)
            .background(Color(0xFFF1F7F2)).padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (selected == null) {
            // Nothing selected and not organizing: there is nothing to say, so no bar.
            if (!organizing) return@Column
            Text(
                if (clipboard == null)
                    "Organize: tap a task to select it, then drag its green handle onto " +
                        "another task or workstream."
                else "Copied \"${clipboard.title}\". Tap where it should go, then Paste.",
                fontSize = 13.sp, color = MapMuted
            )
            return@Column
        }
        // The action says what it will open, so it is never a guess from a generic "Open".
        val openLabel = when (selected.kind) {
            MapKind.TASK -> "Open Task"
            MapKind.WORKSTREAM -> "Open Workstream"
            else -> "Open Project"
        }
        Text(
            selected.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MapInk,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        // The branch size matters only when something is about to move.
        if (organizing) Text(
            if (affectedCount == 1) "This branch is 1 item"
            else "This branch is $affectedCount items",
            fontSize = 12.sp, color = MapMuted
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!taskToolbarPresent) BarAction(openLabel, onOpen)
            if (organizing) {
                BarAction("Edit", onEdit)
                if (!taskToolbarPresent) BarAction("Move here…", onMove)
                if (selected.kind == MapKind.TASK) BarAction("Copy", onCopy)
                if (clipboard != null) BarAction("Paste into", onPaste)
            }
            BarAction("Clear", onClear)
        }
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MapGreen,
        modifier = Modifier.defaultMinSize(minHeight = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
    )
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text(
                if (intent == StructureIntent.MOVE) "Move \"${source.title}\""
                else "Copy \"${source.title}\"",
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MapInk
            )
            Text(
                if (affectedCount == 1) "1 item will move." else "$affectedCount items will move.",
                fontSize = 12.sp, color = MapMuted
            )
            Spacer(Modifier.height(10.dp))
        }
        if (destinations.isEmpty()) {
            Text(
                "There is nowhere else this branch can go.",
                Modifier.padding(18.dp), fontSize = 13.sp, color = MapMuted
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
                    Text(destination.title, fontSize = 15.sp, color = MapInk, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(destination.breadcrumb, fontSize = 12.sp, color = MapMuted, maxLines = 1,
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
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add subtask") },
        text = {
            Column {
                Text("Under \"" + parentTitle + "\"", fontSize = 13.sp, color = MapMuted)
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Subtask name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(MapAddSubtaskFieldTag)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text.trim()) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(MapAddSubtaskSaveTag)
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(subtitle, fontSize = 13.sp, color = MapMuted)
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
