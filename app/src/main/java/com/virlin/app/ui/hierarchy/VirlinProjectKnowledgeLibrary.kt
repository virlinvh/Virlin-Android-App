package com.virlin.app.ui.hierarchy

// Supplied drop-in Knowledge library (two-column gallery for every type, multi-select, bulk
// move). Adapted where the app already owns what is drawn:
//  - items carry the managed-storage path of their media, so thumbnails come from the app's
//    own loader with a real loading and error state, never an empty lambda,
//  - Audio tiles use the app's MediaPlayer transport; Virlin stores no waveform samples, so
//    the tile shows a duration rather than a drawn waveform that is not real,
//  - STEPS and CHECKLIST are the app's own NoteBlock types (NUMBERED_LIST / CHECKBOX) read off
//    the saved document — one canonical capture, classified, never a second copy,
//  - the overflow menu hides Download for an item with no file behind it,
//  - Delete is worded as the app's retention policy behaves (archive, recoverable),
//  - bulk Move goes through one repository transaction and reports failure visibly,
//  - type, grouping, workstream, search, selection and scroll are saved state.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One saved source item. Its placement may change; Activity retains its original timestamp. */
enum class KnowledgeType { PROMPT, IMAGE, AUDIO, DOCUMENT, STEPS, CHECKLIST, TASK }
enum class KnowledgeGroup { TYPE, WORKSTREAM, TASK }

data class KnowledgeItem(
    val id: String,
    val projectId: String,
    val type: KnowledgeType,
    val title: String,
    val snippet: String = "",
    val assetId: String? = null,
    val workstreamId: String? = null,
    val workstreamName: String? = null,
    val taskId: String? = null,
    val taskName: String? = null,
    val updatedAtMillis: Long,
    val sizeBytes: Long? = null,
    val durationSeconds: Int? = null,
    val fileName: String? = null,
    /** Managed-storage path of the image / audio / file this item refers to. */
    val mediaPath: String? = null,
    /** False when nothing on disk backs this item, so Download is not offered. */
    val downloadable: Boolean = false,
    /** Steps and checklists: completed / total, read from the saved document. */
    val completedCount: Int? = null,
    val totalCount: Int? = null,
    /** The project tags carried by this item, by tag id. */
    val tagIds: Set<String> = emptySet()
)

data class KnowledgeWorkstream(val id: String, val title: String)
data class KnowledgeTask(val id: String, val title: String, val workstreamId: String?)

/**
 * The project's own tasks, projected into the library. Knowledge shows the SAME task record —
 * same id, same status, same contribution to progress — and never a copy of it.
 */
data class KnowledgeProjectTask(
    val id: String,
    val projectId: String,
    val title: String,
    val status: String,
    val updatedAtMillis: Long,
    val workstreamId: String? = null,
    val workstreamName: String? = null,
    val tagIds: Set<String> = emptySet(),
    /** The task's own checkbox steps: completed / total. Steps are not tasks. */
    val completedSteps: Int = 0,
    val totalSteps: Int = 0
)

/** Host app implements persistence, playback, document editing and navigation. */
data class KnowledgeActions(
    val onAdd: () -> Unit,
    val onOpen: (KnowledgeItem) -> Unit,
    val onMove: (itemId: String, workstreamId: String?, taskId: String?) -> Unit,
    val onDuplicate: (String) -> Unit,
    val onDownload: (String) -> Unit,
    val onDelete: (String) -> Unit,
    /** Opens the real task page for this task id. */
    val onOpenTask: (String) -> Unit,
    /** Captures new content already attached to that task. */
    val onAddToTask: (String) -> Unit,
    /** Re-places the task itself: a WorkStream of this project, or null for standalone. */
    val onPlaceTask: (taskId: String, workstreamId: String?) -> Unit,
    /** A mixed selection: knowledge placement and task placement in ONE transaction. */
    val onOrganizeMany: (knowledgeIds: Set<String>, taskIds: Set<String>, workstreamId: String?) -> Unit,
    /** Open the tag editor for these items; a mixed selection is allowed. */
    val onEditTags: (knowledgeIds: Set<String>, taskIds: Set<String>) -> Unit = { _, _ -> },
    val onManageTags: () -> Unit = {},
    /** A saved checklist becomes ONE canonical task with ordered steps. Once only. */
    val onConvertChecklistToTask: (String) -> Unit = {},
    /** Persist one transaction for all selected IDs; preserve source IDs and Activity history. */
    val onMoveMany: (itemIds: Set<String>, workstreamId: String?, taskId: String?) -> Unit
)

private object KColor {
    val ink = Color(0xFF17202C)
    val muted = Color(0xFF697588)
    val line = Color(0xFFE5E9ED)
    val green = Color(0xFF68B63B)
    val greenInk = Color(0xFF2C771F)
    val greenWash = Color(0xFFF0F8EC)
    val amber = Color(0xFFAB7108)
    val amberWash = Color(0xFFFFF7E7)
    val grayWash = Color(0xFFF6F7F8)
}
const val KnowledgeListTag = "knowledge_list"
private const val PROJECT_WIDE_FILTER = "__project_wide__"
private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private fun KnowledgeItem.date(zone: ZoneId): String = Instant.ofEpochMilli(updatedAtMillis)
    .atZone(zone).format(dateFormatter)
private fun KnowledgeType.label() = when (this) {
    KnowledgeType.PROMPT -> "Prompts"
    KnowledgeType.IMAGE -> "Images"
    KnowledgeType.AUDIO -> "Audio"
    KnowledgeType.DOCUMENT -> "Docs"
    KnowledgeType.STEPS -> "Steps"
    KnowledgeType.CHECKLIST -> "Checklists"
    KnowledgeType.TASK -> "Tasks"
}
private fun KnowledgeType.symbol() = when (this) {
    KnowledgeType.PROMPT -> "❞"
    KnowledgeType.IMAGE -> "▧"
    KnowledgeType.AUDIO -> "♪"
    KnowledgeType.DOCUMENT -> "▤"
    KnowledgeType.STEPS -> "≡"
    KnowledgeType.CHECKLIST -> "☑"
    KnowledgeType.TASK -> "☐"
}

/**
 * Place in a bounded slot under the shared project header + Overview/Knowledge/Activity tabs.
 * Provide the application's real image loader as [imagePreview]. The caller must render the
 * supplied asset ID as an actual cropped thumbnail, with loading/error states.
 */
@Composable
fun VirlinProjectKnowledgeLibrary(
    projectId: String,
    items: List<KnowledgeItem>,
    projectTasks: List<KnowledgeProjectTask> = emptyList(),
    tags: List<KnowledgeTag> = emptyList(),
    workstreams: List<KnowledgeWorkstream>,
    tasks: List<KnowledgeTask>,
    actions: KnowledgeActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    imagePreview: @Composable (assetId: String, modifier: Modifier) -> Unit
) {
    // Saved content plus the project's own tasks, projected — one list, two kinds of record,
    // each keeping its own identity.
    val projectItems = remember(projectId, items, projectTasks) {
        items.filter { it.projectId == projectId && it.type != KnowledgeType.TASK } +
            projectTasks.filter { it.projectId == projectId }.map { task ->
                KnowledgeItem(
                    id = "task:" + task.id, projectId = task.projectId, type = KnowledgeType.TASK,
                    title = task.title, snippet = task.status, taskId = task.id,
                    taskName = task.title, workstreamId = task.workstreamId,
                    workstreamName = task.workstreamName, updatedAtMillis = task.updatedAtMillis,
                    tagIds = task.tagIds,
                    completedCount = task.totalSteps.takeIf { it > 0 }?.let { task.completedSteps },
                    totalCount = task.totalSteps.takeIf { it > 0 }
                )
            }
    }
    // Saved: opening an item leaves this screen, and the viewer must come back to the same
    // type, grouping, workstream, search, selection and scroll position.
    var typeName by rememberSaveable(projectId) { mutableStateOf("") }
    var groupName by rememberSaveable(projectId) { mutableStateOf(KnowledgeGroup.TYPE.name) }
    var workstream by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(projectId) { mutableStateOf("") }
    var tagCsv by rememberSaveable(projectId) { mutableStateOf("") }
    var tagMatchName by rememberSaveable(projectId) { mutableStateOf(TagMatch.ALL.name) }
    var tagSheet by remember { mutableStateOf(false) }
    val chosenTags = remember(tagCsv) { tagCsv.split(',').filter { it.isNotBlank() }.toSet() }
    val tagMatch = TagMatch.entries.firstOrNull { it.name == tagMatchName } ?: TagMatch.ALL
    val type = KnowledgeType.entries.firstOrNull { it.name == typeName }
    val group = KnowledgeGroup.entries.firstOrNull { it.name == groupName } ?: KnowledgeGroup.TYPE
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var groupMenu by remember { mutableStateOf(false) }
    var workstreamMenu by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<KnowledgeItem?>(null) }
    var placingTask by remember { mutableStateOf<KnowledgeItem?>(null) }
    var deleting by remember { mutableStateOf<KnowledgeItem?>(null) }
    var selecting by rememberSaveable(projectId) { mutableStateOf(false) }
    // Survives process death with the rest of the screen; selection is the user's work too.
    var selectedCsv by rememberSaveable(projectId) { mutableStateOf("") }
    val selected = remember(projectId) { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        if (selected.isEmpty() && selectedCsv.isNotBlank()) {
            selected.addAll(selectedCsv.split(',').filter { it.isNotBlank() })
        }
    }
    LaunchedEffect(selected.size, selected.toList()) { selectedCsv = selected.joinToString(",") }
    var movingMany by remember { mutableStateOf(false) }
    LaunchedEffect(projectItems) { selected.removeAll { id -> projectItems.none { it.id == id } } }

    val filtered = remember(projectItems, type, workstream, query, chosenTags, tagMatch) {
        projectItems.asSequence()
            .filter { type == null || it.type == type }
            .filter { workstream == null || if (workstream == PROJECT_WIDE_FILTER)
                it.workstreamId == null && it.taskId == null else it.workstreamId == workstream }
            // Tags compose with type, workstream and text: each narrows what the others left.
            .filter { item ->
                chosenTags.isEmpty() || when (tagMatch) {
                    TagMatch.ALL -> item.tagIds.containsAll(chosenTags)
                    TagMatch.ANY -> chosenTags.any { it in item.tagIds }
                }
            }
            .filter {
                query.isBlank() || listOf(it.title, it.snippet, it.fileName.orEmpty(),
                    it.workstreamName.orEmpty(), it.taskName.orEmpty())
                    .any { field -> field.contains(query.trim(), ignoreCase = true) }
            }
            .sortedWith(compareByDescending<KnowledgeItem> { it.updatedAtMillis }.thenBy { it.id })
            .toList()
    }
    val tagNames = remember(tags) { tags.associate { it.id to it.name } }
    val sections = remember(filtered, group) {
        filtered.groupBy { item -> when (group) {
            KnowledgeGroup.TYPE -> item.type.label()
            KnowledgeGroup.WORKSTREAM -> item.workstreamName ?: "Project-wide"
            KnowledgeGroup.TASK -> item.taskName ?: if (item.workstreamId == null) "Project-wide" else "Workstream notes"
        } }
    }

    Column(modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (selecting) "${selected.size} selected" else "Project knowledge", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = KColor.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = { selecting = !selecting; selected.clear() }) {
                Text(if (selecting) "Cancel" else "Select", color = KColor.greenInk)
            }
            if (!selecting) TextButton(onClick = actions.onAdd) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Text("Add")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KnowledgeTypeTab("All", type == null, Modifier.width(62.dp)) { typeName = "" }
            KnowledgeType.entries.forEach { kind ->
                KnowledgeTypeTab(
                    kind.label(), type == kind,
                    Modifier.width(if (kind == KnowledgeType.CHECKLIST) 92.dp else 78.dp)
                ) { typeName = kind.name }
            }
        }
        // Scrolls with the chips: with six types the last count was clipped off the edge.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            // Every type the project actually holds, counted from the persisted items.
            KnowledgeType.entries.filter { kind -> projectItems.any { it.type == kind } }.forEach { kind ->
                Text("${kind.symbol()} ${projectItems.count { it.type == kind }}",
                    fontSize = 11.sp, color = KColor.muted)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(if (type == null) "Search this project" else "Search ${type!!.label().lowercase()}") },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, "Clear search", modifier = Modifier.size(18.dp))
                } }) else null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(52.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
            Box {
                KnowledgeOutlineButton("By ${group.name.lowercase()}") { groupMenu = true }
                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false }) {
                    KnowledgeGroup.entries.forEach { option ->
                        DropdownMenuItem(text = { Text("By ${option.name.lowercase()}") },
                            onClick = { groupName = option.name; groupMenu = false })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            KnowledgeOutlineButton(
                if (chosenTags.isEmpty()) "Tags" else "${chosenTags.size} tags · ${
                    if (tagMatch == TagMatch.ALL) "all" else "any"
                }"
            ) { tagSheet = true }
            chosenTags.take(2).forEach { id ->
                tags.firstOrNull { it.id == id }?.let { tag ->
                    TagChip(tag.name, onRemove = {
                        tagCsv = (chosenTags - tag.id).joinToString(",")
                    })
                }
            }
        }
        Box(Modifier.padding(start = 16.dp, top = 6.dp, bottom = 8.dp)) {
            val name = if (workstream == PROJECT_WIDE_FILTER) "Project-wide" else
                workstreams.firstOrNull { it.id == workstream }?.title ?: "All workstreams"
            KnowledgeOutlineButton("$name  ⌄") { workstreamMenu = true }
            DropdownMenu(expanded = workstreamMenu, onDismissRequest = { workstreamMenu = false }) {
                DropdownMenuItem(text = { Text("All workstreams") },
                    onClick = { workstream = null; workstreamMenu = false })
                DropdownMenuItem(text = { Text("Project-wide") },
                    onClick = { workstream = PROJECT_WIDE_FILTER; workstreamMenu = false })
                workstreams.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.title) },
                        onClick = { workstream = choice.id; workstreamMenu = false })
                }
            }
        }

        if (selecting) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val visible = filtered.map { it.id }
                    if (visible.all { it in selected }) selected.removeAll(visible.toSet())
                    else visible.forEach { if (it !in selected) selected.add(it) }
                }) { Text(if (filtered.isNotEmpty() && filtered.all { it.id in selected }) "Clear visible" else "Select visible") }
                TextButton(onClick = {
                    val taskIds = selected.mapNotNull { id ->
                        projectItems.firstOrNull { it.id == id && it.type == KnowledgeType.TASK }?.taskId
                    }.toSet()
                    val knowledgeIds = selected.filter { id ->
                        projectItems.any { it.id == id && it.type != KnowledgeType.TASK }
                    }.toSet()
                    actions.onEditTags(knowledgeIds, taskIds)
                }, enabled = selected.isNotEmpty()) { Text("Edit tags") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { movingMany = true }, enabled = selected.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = KColor.greenInk)) {
                    Text("Move ${selected.size} to…")
                }
            }
        }
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No saved items match", color = KColor.ink, fontWeight = FontWeight.SemiBold)
                    Text("Try another type, workstream, or search.", color = KColor.muted,
                        fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag(KnowledgeListTag), state = listState, contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, bottom = OrbClearance)) {
                sections.forEach { (heading, sectionItems) ->
                    item(key = "heading-$heading") {
                        Text("$heading (${sectionItems.size})", fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, color = KColor.ink,
                            modifier = Modifier.padding(top = 14.dp, bottom = 9.dp))
                    }
                    items(sectionItems.chunked(2), key = { pair -> pair.joinToString("-") { it.id } }) { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { item ->
                                    Box(Modifier.weight(1f)) {
                                        KnowledgeGalleryCard(item, zone, tagNames, imagePreview, actions,
                                            selecting, item.id in selected,
                                            onSelect = { if (item.id in selected) selected.remove(item.id) else selected.add(item.id) },
                                            onStartSelection = { selecting = true; if (item.id !in selected) selected.add(item.id) },
                                            onMove = { moving = item }, onDelete = { deleting = item },
                                            onPlaceTask = { placingTask = item })
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                }
            }
        }
    }

    moving?.let { selected ->
        KnowledgeMoveSheet(selected, workstreams, tasks,
            onDismiss = { moving = null },
            onSave = { workstreamId, taskId ->
                actions.onMove(selected.id, workstreamId, taskId)
                moving = null
            })
    }
    if (tagSheet) {
        ProjectTagFilterSheet(
            tags = tags, initial = chosenTags, initialMatch = tagMatch,
            onDismiss = { tagSheet = false },
            onApply = { ids, match ->
                tagCsv = ids.joinToString(","); tagMatchName = match.name; tagSheet = false
            },
            onManage = { tagSheet = false; actions.onManageTags() }
        )
    }
    placingTask?.let { task ->
        KnowledgeTaskPlacementSheet(task, workstreams,
            onDismiss = { placingTask = null },
            onSave = { streamId ->
                actions.onPlaceTask(requireNotNull(task.taskId), streamId)
                placingTask = null
            })
    }
    if (movingMany && selected.isNotEmpty()) {
        // A task can live in a WorkStream or stand alone, but never inside another task, so a
        // selection containing tasks offers only those destinations.
        val chosenTasks = selected.mapNotNull { id ->
            projectItems.firstOrNull { it.id == id && it.type == KnowledgeType.TASK }?.taskId
        }.toSet()
        val chosenKnowledge = selected.filter { id ->
            projectItems.any { it.id == id && it.type != KnowledgeType.TASK }
        }.toSet()
        KnowledgeMoveSheet(
            "${selected.size} selected", null, null, workstreams,
            if (chosenTasks.isEmpty()) tasks else emptyList(),
            onDismiss = { movingMany = false },
            onSave = { streamId, taskId ->
                if (chosenTasks.isEmpty()) actions.onMoveMany(chosenKnowledge, streamId, taskId)
                else actions.onOrganizeMany(chosenKnowledge, chosenTasks, streamId)
                // The selection is only dropped once the screen reports the write landed.
                movingMany = false
            })
    }
    deleting?.let { selected ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Archive saved item?") },
            text = { Text("${selected.title} is archived, not destroyed: it leaves Knowledge and the Inbox, its file stays on disk, and its original Activity entry is untouched.") },
            confirmButton = { TextButton(onClick = { actions.onDelete(selected.id); deleting = null }) {
                Text("Archive item", color = Color(0xFFB6433D))
            } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun KnowledgeTypeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.defaultMinSize(minHeight = 40.dp),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) KColor.greenWash else KColor.grayWash,
        border = BorderStroke(1.dp, if (selected) KColor.green else Color.Transparent)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 11.sp, color = if (selected) KColor.greenInk else KColor.ink,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1)
        }
    }
}

@Composable
private fun KnowledgeOutlineButton(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = Color.White,
        border = BorderStroke(1.dp, KColor.line)) {
        Box(Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, color = KColor.ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KnowledgeGalleryCard(item: KnowledgeItem, zone: ZoneId,
    tagNames: Map<String, String>,
    imagePreview: @Composable (String, Modifier) -> Unit, actions: KnowledgeActions,
    selecting: Boolean, selected: Boolean, onSelect: () -> Unit, onStartSelection: () -> Unit,
    onMove: () -> Unit, onDelete: () -> Unit, onPlaceTask: () -> Unit) {
    Surface(modifier = Modifier.combinedClickable(
        onClick = {
            when {
                selecting -> onSelect()
                item.type == KnowledgeType.TASK -> actions.onOpenTask(requireNotNull(item.taskId))
                else -> actions.onOpen(item)
            }
        },
        onLongClick = onStartSelection),
        shape = RoundedCornerShape(16.dp), color = Color.White,
        border = BorderStroke(if (selected) 2.dp else 1.dp,
            if (selected) KColor.green else KColor.line)) {
        Column {
            Box(Modifier.fillMaxWidth().height(114.dp)
                .clip(RoundedCornerShape(topStart = 15.dp, topEnd = 15.dp))
                .background(when (item.type) {
                    KnowledgeType.PROMPT -> KColor.amberWash
                    KnowledgeType.STEPS, KnowledgeType.CHECKLIST, KnowledgeType.TASK -> KColor.greenWash
                    KnowledgeType.AUDIO -> Color(0xFFEEF3FC)
                    else -> KColor.grayWash
                })) {
                if (item.type == KnowledgeType.IMAGE && item.mediaPath != null) {
                    imagePreview(item.mediaPath, Modifier.fillMaxSize())
                } else Column(Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(item.type.symbol(), fontSize = 21.sp, color = KColor.greenInk)
                    when {
                        item.type == KnowledgeType.IMAGE -> Text(
                            "Image unavailable", fontSize = 12.sp, color = KColor.muted, maxLines = 2
                        )
                        // No waveform is drawn: Virlin stores no samples, and a decorative one
                        // would be a picture of sound that does not exist.
                        item.type == KnowledgeType.AUDIO -> {
                            Text(item.durationSeconds?.asTime() ?: "Recording",
                                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF3A5891))
                            Text("Tap to play", fontSize = 11.sp, color = KColor.muted)
                        }
                        item.completedCount != null && item.totalCount != null -> {
                            Text("${item.completedCount} of ${item.totalCount} done",
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = KColor.greenInk)
                            Text(item.snippet, fontSize = 11.sp, lineHeight = 15.sp,
                                color = KColor.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        else -> Text(item.snippet.ifBlank { item.title }, fontSize = 12.sp,
                            lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = KColor.ink,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (selecting) Surface(
                    onClick = onSelect,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp)
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription =
                                if (selected) "Deselect ${item.title}" else "Select ${item.title}"
                        },
                    shape = RoundedCornerShape(50),
                    color = if (selected) KColor.green else Color.White.copy(alpha = 0.94f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (selected) "✓" else "○", fontSize = 17.sp,
                            color = if (selected) Color.White else KColor.ink)
                    }
                } else KnowledgeMoreMenu(item, actions, onMove, onDelete, onPlaceTask,
                    Modifier.align(Alignment.TopEnd).padding(5.dp))
            }
            Column(Modifier.fillMaxWidth().padding(9.dp)) {
                if (item.tagIds.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.tagIds.take(2).forEach { id ->
                            tagNames[id]?.let { TagChip(it) }
                        }
                    }
                }
                Text(item.title, color = KColor.ink,
                    fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    maxLines = 2, minLines = 2, lineHeight = 15.sp, overflow = TextOverflow.Ellipsis)
                Text(item.type.label(),
                    color = KColor.greenInk, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                item.fileName?.takeIf { it != item.title }?.let {
                    Text(it, color = KColor.muted, fontSize = 10.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                Text(item.location(), color = KColor.muted, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp))
                Text("${item.date(zone)}${item.sizeBytes?.let { " · ${it.readableSize()}" } ?: ""}",
                    color = KColor.muted, fontSize = 10.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

@Composable
private fun KnowledgeMoreMenu(item: KnowledgeItem, actions: KnowledgeActions,
    onMove: () -> Unit, onDelete: () -> Unit, onPlaceTask: () -> Unit = {},
    modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)
            .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(9.dp))) {
            Icon(Icons.Default.MoreVert, "Options for ${item.title}", tint = KColor.ink)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (item.type == KnowledgeType.TASK) {
                val taskId = requireNotNull(item.taskId)
                DropdownMenuItem(text = { Text("Open task") }, onClick = {
                    expanded = false; actions.onOpenTask(taskId)
                })
                DropdownMenuItem(text = { Text("Move to workstream…") }, onClick = {
                    expanded = false; onPlaceTask()
                })
                // Only meaningful while it belongs to a workstream.
                if (item.workstreamId != null) {
                    DropdownMenuItem(text = { Text("Make standalone") }, onClick = {
                        expanded = false; actions.onPlaceTask(taskId, null)
                    })
                }
                DropdownMenuItem(text = { Text("Add knowledge") }, onClick = {
                    expanded = false; actions.onAddToTask(taskId)
                })
                DropdownMenuItem(text = { Text("Edit tags") }, onClick = {
                    expanded = false; actions.onEditTags(emptySet(), setOf(taskId))
                })
                return@DropdownMenu
            }
            DropdownMenuItem(text = { Text("Move to…") }, onClick = { expanded = false; onMove() })
            DropdownMenuItem(text = { Text("Edit tags") }, onClick = {
                expanded = false; actions.onEditTags(setOf(item.id), emptySet())
            })
            if (item.type == KnowledgeType.CHECKLIST) {
                DropdownMenuItem(text = { Text("Convert to task") }, onClick = {
                    expanded = false; actions.onConvertChecklistToTask(item.id)
                })
            }
            DropdownMenuItem(text = { Text("Duplicate") }, onClick = {
                expanded = false; actions.onDuplicate(item.id)
            })
            // Only offered when a file actually backs the item.
            if (item.downloadable) {
                DropdownMenuItem(text = { Text("Download") }, onClick = {
                    expanded = false; actions.onDownload(item.id)
                })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Archive", color = Color(0xFFB6433D)) },
                onClick = { expanded = false; onDelete() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeMoveSheet(item: KnowledgeItem, workstreams: List<KnowledgeWorkstream>,
    tasks: List<KnowledgeTask>, onDismiss: () -> Unit,
    onSave: (workstreamId: String?, taskId: String?) -> Unit) {
    KnowledgeMoveSheet(item.title, item.workstreamId, item.taskId, workstreams, tasks, onDismiss, onSave)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeMoveSheet(title: String, initialWorkstream: String?, initialTask: String?,
    workstreams: List<KnowledgeWorkstream>, tasks: List<KnowledgeTask>, onDismiss: () -> Unit,
    onSave: (workstreamId: String?, taskId: String?) -> Unit) {
    var destinationWorkstream by remember(title) { mutableStateOf(initialWorkstream) }
    var destinationTask by remember(title) { mutableStateOf(initialTask) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().heightIn(max = 610.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 22.dp)) {
            Text("Move to…", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = KColor.ink)
            Text(title, fontSize = 13.sp, color = KColor.muted,
                modifier = Modifier.padding(top = 3.dp, bottom = 12.dp))
            Text("Project-wide", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = KColor.ink)
            DestinationRow("Save directly to project", destinationWorkstream == null) {
                destinationWorkstream = null; destinationTask = null
            }
            Text("Workstreams", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = KColor.ink, modifier = Modifier.padding(top = 16.dp, bottom = 5.dp))
            workstreams.forEach { stream ->
                DestinationRow(stream.title, destinationWorkstream == stream.id && destinationTask == null) {
                    destinationWorkstream = stream.id; destinationTask = null
                }
                if (destinationWorkstream == stream.id) {
                    val streamTasks = tasks.filter { it.workstreamId == stream.id }
                    if (streamTasks.isNotEmpty()) {
                        // Indented and labelled: a task is INSIDE the workstream above it.
                        Text("Tasks in ${stream.title}", fontSize = 11.sp, color = KColor.muted,
                            modifier = Modifier.padding(start = 30.dp, top = 4.dp))
                        streamTasks.forEach { task ->
                            Box(Modifier.padding(start = 24.dp)) {
                                DestinationRow(task.title, destinationTask == task.id) {
                                    destinationTask = task.id
                                }
                            }
                        }
                    }
                }
            }
            val standalone = tasks.filter { it.workstreamId == null }
            if (standalone.isNotEmpty()) {
                Text("Standalone tasks", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = KColor.ink, modifier = Modifier.padding(top = 16.dp, bottom = 5.dp))
                standalone.forEach { task ->
                    DestinationRow(task.title, destinationTask == task.id) {
                        destinationWorkstream = null; destinationTask = task.id
                    }
                }
            }
            Spacer(Modifier.height(17.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Cancel")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onSave(destinationWorkstream, destinationTask) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KColor.greenInk)) {
                Text("Move to destination")
            }
        }
    }
}

@Composable private fun DestinationRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 46.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = KColor.greenInk))
        Text(label, color = KColor.ink, fontSize = 14.sp)
    }
}

private fun KnowledgeItem.location(): String = if (type == KnowledgeType.TASK)
    (workstreamName ?: "Standalone task") else listOfNotNull(
    workstreamName ?: if (taskId == null) "Project-wide" else null,
    taskName
).joinToString(" › ")
private fun Long.readableSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "%.1f KB".format(Locale.ENGLISH, this / 1024.0)
    else -> "%.1f MB".format(Locale.ENGLISH, this / 1048576.0)
}
private fun Int.asTime(): String = "%d:%02d".format(Locale.ENGLISH, this / 60, this % 60)

/** Where a TASK itself lives: one of this project's WorkStreams, or standalone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeTaskPlacementSheet(
    task: KnowledgeItem,
    workstreams: List<KnowledgeWorkstream>,
    onDismiss: () -> Unit,
    onSave: (workstreamId: String?) -> Unit
) {
    var destination by remember(task.id) { mutableStateOf(task.workstreamId) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().heightIn(max = 610.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 22.dp)) {
            Text("Move task", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = KColor.ink)
            Text(task.title, fontSize = 13.sp, color = KColor.muted,
                modifier = Modifier.padding(top = 3.dp))
            Text("Currently ${task.location()}", fontSize = 12.sp, color = KColor.muted,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            Text("Standalone", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = KColor.ink)
            DestinationRow("Keep in the project, outside any workstream", destination == null) {
                destination = null
            }
            Text("Workstreams", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = KColor.ink, modifier = Modifier.padding(top = 16.dp, bottom = 5.dp))
            workstreams.forEach { stream ->
                DestinationRow(stream.title, destination == stream.id) { destination = stream.id }
            }
            Spacer(Modifier.height(17.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Cancel")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onSave(destination) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KColor.greenInk)) {
                Text("Move task")
            }
        }
    }
}
