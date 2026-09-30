package com.virlin.app.ui.hierarchy

// The tag surfaces: the filter, the per-item editor and the manage sheet. Adapted from the
// supplied reference to the app's own rules:
//  - a tag's id is what everything is keyed by, so a rename never disturbs what carries it,
//  - creating a tag from the editor does not assume the write landed: the list refreshes from
//    the repository and the new tag becomes selectable when it appears,
//  - merge and delete state plainly what they do to the things that carry the tag.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A project's tag, as the UI needs it. The id is the identity; the name is only a label. */
data class KnowledgeTag(val id: String, val name: String)

/** Every selected tag, or any of them. */
enum class TagMatch { ALL, ANY }

private val tagInk = Color(0xFF17202C)
private val tagMuted = Color(0xFF697588)
private val tagLine = Color(0xFFE5E9ED)
private val tagGreen = Color(0xFF68B63B)
private val tagGreenInk = Color(0xFF2C771F)
private val tagWash = Color(0xFFF0F8EC)

/** A restrained chip. Cards show these; they never shout. */
@Composable
fun TagChip(name: String, onRemove: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = tagWash,
        border = BorderStroke(1.dp, tagLine)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("#$name", fontSize = 10.sp, color = tagGreenInk, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (onRemove != null) {
                Spacer(Modifier.width(4.dp))
                Text("×", fontSize = 12.sp, color = tagMuted,
                    modifier = Modifier.clickable(onClickLabel = "Remove $name", onClick = onRemove))
            }
        }
    }
}

@Composable
private fun TagRow(name: String, chosen: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = chosen, onCheckedChange = { onClick() },
            colors = CheckboxDefaults.colors(checkedColor = tagGreenInk))
        Text("#$name", color = tagInk, fontSize = 14.sp)
    }
}

@Composable
private fun TagSearchField(query: String, onQuery: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
        trailingIcon = if (query.isNotEmpty()) ({
            IconButton(onClick = { onQuery("") }) {
                Icon(Icons.Default.Close, "Clear search", Modifier.size(18.dp))
            }
        }) else null,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(56.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
    )
}

/** Filter the gallery by tags: every selected tag, or any of them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTagFilterSheet(
    tags: List<KnowledgeTag>,
    initial: Set<String>,
    initialMatch: TagMatch,
    onDismiss: () -> Unit,
    onApply: (Set<String>, TagMatch) -> Unit,
    onManage: () -> Unit
) {
    var chosen by remember { mutableStateOf(initial) }
    var match by remember { mutableStateOf(initialMatch) }
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tags", Modifier.weight(1f), fontSize = 21.sp,
                    fontWeight = FontWeight.Bold, color = tagInk)
                TextButton(onClick = onManage) { Text("Manage tags", color = tagGreenInk) }
            }
            Spacer(Modifier.height(8.dp))
            TagSearchField(query, { query = it }, "Search tags")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TagMatch.ALL to "Every selected tag", TagMatch.ANY to "Any selected tag")
                    .forEach { (value, label) ->
                        Surface(onClick = { match = value }, shape = RoundedCornerShape(10.dp),
                            color = if (match == value) tagWash else Color.White,
                            border = BorderStroke(1.dp, if (match == value) tagGreen else tagLine)) {
                            Box(Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = 12.dp),
                                contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 12.sp, color = tagInk)
                            }
                        }
                    }
            }
            Spacer(Modifier.height(6.dp))
            val visible = tags.filter { it.name.contains(query.trim(), ignoreCase = true) }
            if (visible.isEmpty()) {
                Text(if (tags.isEmpty()) "This project has no tags yet" else "No tag matches",
                    color = tagMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
            visible.forEach { tag ->
                TagRow(tag.name, tag.id in chosen) {
                    chosen = if (tag.id in chosen) chosen - tag.id else chosen + tag.id
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { chosen = emptySet(); onApply(emptySet(), match) },
                    modifier = Modifier.weight(1f).height(48.dp)) { Text("Clear") }
                Button(onClick = { onApply(chosen, match) },
                    colors = ButtonDefaults.buttonColors(containerColor = tagGreenInk),
                    modifier = Modifier.weight(2f).height(48.dp)) { Text("Apply tags") }
            }
        }
    }
}

/**
 * Apply tags to one item or to a mixed selection. Creating a tag here does not assume the write
 * landed: [onCreate] asks the repository, and the new tag becomes selectable when the observed
 * list contains it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTagEditorSheet(
    title: String,
    tags: List<KnowledgeTag>,
    initiallySelected: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onSave: (Set<String>) -> Unit
) {
    var chosen by remember(initiallySelected) { mutableStateOf(initiallySelected) }
    var query by remember { mutableStateOf("") }
    var pendingName by remember { mutableStateOf<String?>(null) }
    // When the tag the user asked for turns up in the observed list, select it.
    LaunchedEffect(tags, pendingName) {
        val wanted = pendingName?.trim()?.lowercase() ?: return@LaunchedEffect
        tags.firstOrNull { it.name.trim().lowercase() == wanted }?.let {
            chosen = chosen + it.id
            pendingName = null
            query = ""
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 22.dp)) {
            Text("Edit tags", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = tagInk)
            Text(title, fontSize = 13.sp, color = tagMuted, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(10.dp))
            if (chosen.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScrollRow(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chosen.forEach { id ->
                        tags.firstOrNull { it.id == id }?.let { tag ->
                            TagChip(tag.name, onRemove = { chosen = chosen - id })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            TagSearchField(query, { query = it }, "Search or type a new tag")
            val typed = query.trim()
            val exists = tags.any { it.name.trim().equals(typed, ignoreCase = true) }
            if (typed.isNotEmpty() && !exists) {
                TextButton(onClick = { pendingName = typed; onCreate(typed) }) {
                    Text("Create #$typed", color = tagGreenInk)
                }
            }
            tags.filter { it.name.contains(typed, ignoreCase = true) }.forEach { tag ->
                TagRow(tag.name, tag.id in chosen) {
                    chosen = if (tag.id in chosen) chosen - tag.id else chosen + tag.id
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Cancel")
                }
                Button(onClick = { onSave(chosen) },
                    colors = ButtonDefaults.buttonColors(containerColor = tagGreenInk),
                    modifier = Modifier.weight(2f).height(48.dp)) { Text("Save tags") }
            }
        }
    }
}

/** Create, rename, merge and delete this project's tags. Deleting never deletes content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectManageTagsSheet(
    tags: List<KnowledgeTag>,
    message: String?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onMerge: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<KnowledgeTag?>(null) }
    var merging by remember { mutableStateOf<KnowledgeTag?>(null) }
    var deleting by remember { mutableStateOf<KnowledgeTag?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 22.dp)) {
            Text("Manage tags", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = tagInk)
            message?.let {
                Text(it, fontSize = 12.sp, color = tagMuted, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    placeholder = { Text("New tag") }, shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(56.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { onCreate(newName.trim()); newName = "" },
                    enabled = newName.isNotBlank()
                ) { Text("Add", color = tagGreenInk) }
            }
            Spacer(Modifier.height(6.dp))
            if (tags.isEmpty()) Text("No tags in this project yet", color = tagMuted, fontSize = 13.sp)
            tags.forEach { tag ->
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("#${tag.name}", Modifier.weight(1f), color = tagInk, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { renaming = tag }) { Text("Rename", fontSize = 12.sp) }
                    TextButton(onClick = { merging = tag }, enabled = tags.size > 1) {
                        Text("Merge", fontSize = 12.sp)
                    }
                    TextButton(onClick = { deleting = tag }) {
                        Text("Delete", fontSize = 12.sp, color = Color(0xFFB6433D))
                    }
                }
                HorizontalDivider(color = tagLine)
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = tagGreenInk)) { Text("Done") }
        }
    }

    renaming?.let { tag ->
        var text by remember(tag.id) { mutableStateOf(tag.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename #${tag.name}") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                    Text("Everything tagged keeps this tag: the name changes, the tag does not.",
                        fontSize = 12.sp, color = tagMuted, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { onRename(tag.id, text.trim()); renaming = null }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
    merging?.let { tag ->
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Merge #${tag.name} into…") },
            text = {
                Column {
                    Text("Everything tagged #${tag.name} will carry the tag you pick instead.",
                        fontSize = 13.sp, color = tagMuted)
                    Spacer(Modifier.height(8.dp))
                    tags.filter { it.id != tag.id }.forEach { target ->
                        Text("#${target.name}", fontSize = 14.sp, color = tagInk,
                            modifier = Modifier.fillMaxWidth()
                                .defaultMinSize(minHeight = 44.dp)
                                .clickable { onMerge(tag.id, target.id); merging = null }
                                .padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { merging = null }) { Text("Cancel") } }
        )
    }
    deleting?.let { tag ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete #${tag.name}?") },
            text = { Text("The tag is removed from everything that carries it. Nothing it labelled is deleted.") },
            confirmButton = {
                TextButton(onClick = { onDelete(tag.id); deleting = null }) {
                    Text("Delete tag", color = Color(0xFFB6433D))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }
        )
    }
}

private fun Modifier.horizontalScrollRow(): Modifier = this
