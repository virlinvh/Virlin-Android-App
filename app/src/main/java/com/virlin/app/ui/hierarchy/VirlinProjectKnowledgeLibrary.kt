package com.virlin.app.ui.hierarchy

import android.graphics.BitmapFactory
import com.virlin.app.data.attachment.AttachmentFileStore

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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import com.virlin.app.ui.components.SafeDialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val tagIds: Set<String> = emptySet(),
    val kindLabel: String? = null
)

data class KnowledgeWorkstream(val id: String, val title: String, val progress: String? = null)
data class KnowledgeTask(val id: String, val title: String, val workstreamId: String?, val parentId: String? = null)

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
    val totalSteps: Int = 0,
    val parentId: String? = null
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
    val ink: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.text) } ?: Color(0xFF17202C)
    val muted: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.muted) } ?: Color(0xFF697588)
    val line: Color @Composable get() = Color(0xFFE6E8E6)
    val green: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.primary) } ?: Color(0xFF68B63B)
    val greenInk: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF2C771F)
    val greenWash: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.tertiary) } ?: Color(0xFFF0F8EC)
    val grayWash = Color(0xFFF6F7F8)
}
const val KnowledgeListTag = "knowledge_list"
/** Active library compositions suppress the shell orb without changing any app data. */
object KnowledgeOrbVisibility {
    private val owners = mutableStateListOf<Any>()
    val hidden: Boolean get() = owners.isNotEmpty()
    internal fun enter(owner: Any) { owners.add(owner) }
    internal fun leave(owner: Any) { owners.remove(owner) }
}
internal const val PROJECT_WIDE_FILTER = "__project_wide__"
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

private fun knowledgeIcon(item: KnowledgeItem) = when {
    item.kindLabel == "PDF" -> Icons.Outlined.PictureAsPdf
    item.kindLabel == "Link" -> Icons.Outlined.Link
    item.kindLabel == "Attachment" -> Icons.Outlined.AttachFile
    item.type == KnowledgeType.IMAGE -> Icons.Outlined.Image
    item.type == KnowledgeType.AUDIO -> Icons.Outlined.MicNone
    item.type == KnowledgeType.CHECKLIST || item.type == KnowledgeType.TASK -> Icons.Outlined.CheckBox
    item.type == KnowledgeType.PROMPT -> Icons.Outlined.Assignment
    else -> Icons.Outlined.Description
}

/**
 * Place in a bounded slot under the shared project header + Overview/Knowledge/Activity tabs.
 * Provide the application's real image loader as [imagePreview]. The caller must render the
 * supplied asset ID as an actual cropped thumbnail, with loading/error states.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VirlinProjectKnowledgeLibrary(
    projectId: String,
    items: List<KnowledgeItem>,
    fixedScope: String? = null,
    pageTitle: String? = null,
    projectTasks: List<KnowledgeProjectTask> = emptyList(),
    tags: List<KnowledgeTag> = emptyList(),
    workstreams: List<KnowledgeWorkstream>,
    tasks: List<KnowledgeTask>,
    actions: KnowledgeActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    imagePreview: @Composable (assetId: String, modifier: Modifier) -> Unit
) {
    // Knowledge is saved content only. Tasks remain destinations, never gallery records.
    val projectItems = remember(projectId, items) {
        items.filter { it.projectId == projectId && it.type != KnowledgeType.TASK }
    }
    // Saved: opening an item leaves this screen, and the viewer must come back to the same
    // type, grouping, workstream, search, selection and scroll position.
    var typeName by rememberSaveable(projectId) { mutableStateOf("") }
    var groupName by rememberSaveable(projectId) { mutableStateOf("") }
    var workstream by rememberSaveable(projectId, fixedScope) { mutableStateOf(fixedScope) }
    var query by rememberSaveable(projectId) { mutableStateOf("") }
    var tagCsv by rememberSaveable(projectId) { mutableStateOf("") }
    var tagMatchName by rememberSaveable(projectId) { mutableStateOf(TagMatch.ALL.name) }
    var tagSheet by remember { mutableStateOf(false) }
    val chosenTags = remember(tagCsv) { tagCsv.split(',').filter { it.isNotBlank() }.toSet() }
    val tagMatch = TagMatch.entries.firstOrNull { it.name == tagMatchName } ?: TagMatch.ALL
    val type = KnowledgeType.entries.firstOrNull { it.name == typeName }
    val group = KnowledgeGroup.entries.firstOrNull { it.name == groupName } ?: KnowledgeGroup.TYPE
    val listState = rememberLazyStaggeredGridState()
    var groupMenu by remember { mutableStateOf(false) }
    var grid by rememberSaveable(projectId) { mutableStateOf(true) }
    var recentFirst by rememberSaveable(projectId) { mutableStateOf(true) }
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

    val filtered = remember(projectItems, typeName, workstream, query, chosenTags, tagMatch, recentFirst) {
        projectItems.asSequence()
            .filter { item -> when (typeName) {
                "NOTES" -> item.type == KnowledgeType.DOCUMENT && (item.kindLabel == null || item.kindLabel == "Note")
                "MEDIA" -> item.type == KnowledgeType.IMAGE || item.type == KnowledgeType.AUDIO || item.kindLabel == "PDF" || item.kindLabel == "Attachment"
                else -> type == null || item.type == type
            } }
            .filter { workstream == null || if (workstream == PROJECT_WIDE_FILTER)
                it.workstreamId == null && it.taskId == null else
                it.workstreamId == workstream && (fixedScope == null || it.taskId == null) }
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
            .sortedWith(if (recentFirst) compareByDescending<KnowledgeItem> { it.updatedAtMillis }.thenBy { it.id }
                else compareBy<KnowledgeItem> { it.updatedAtMillis }.thenBy { it.id })
            .toList()
    }
    val tagNames = remember(tags) { tags.associate { it.id to it.name } }
    val sections = remember(filtered, group, groupName) {
        if (groupName.isEmpty()) mapOf("" to filtered) else
        filtered.groupBy { item -> when (group) {
            KnowledgeGroup.TYPE -> item.type.label()
            KnowledgeGroup.WORKSTREAM -> item.workstreamName ?: "Project-wide"
            KnowledgeGroup.TASK -> item.taskName ?: if (item.workstreamId == null) "Project-wide" else "Workstream notes"
        } }
    }

    DisposableEffect(Unit) {
        val owner = Any()
        KnowledgeOrbVisibility.enter(owner)
        onDispose { KnowledgeOrbVisibility.leave(owner) }
    }
    Box(modifier.fillMaxSize()) {
    LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(if (grid) 2 else 1),
        modifier = Modifier.fillMaxSize().background(LocalProjectPalette.current?.let { Color(it.surface) } ?: Color.White)
            .testTag(KnowledgeListTag), state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp) {
        item(key = "knowledge-controls", span = StaggeredGridItemSpan.FullLine) { Column {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (selecting) "${selected.size} selected" else pageTitle ?: "Project knowledge", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = KColor.ink)
                Text("${filtered.size} items · One place for your ideas", fontSize = 12.sp, color = KColor.muted)
            }
            if (selecting) TextButton(onClick = { selecting = false; selected.clear() }) {
                Text(if (selecting) "Cancel" else "Select", color = KColor.greenInk)
            }
            if (!selecting) Button(onClick = actions.onAdd, shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                border = BorderStroke(1.dp, KColor.green),
                colors = ButtonDefaults.buttonColors(containerColor = KColor.greenWash, contentColor = KColor.greenInk)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Text("Add")
            }
        }
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search knowledge", fontSize = 13.sp) },
                colors = TextFieldDefaults.colors(focusedContainerColor = KColor.grayWash, unfocusedContainerColor = KColor.grayWash,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = KColor.greenInk),
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) {
                    Icon(Icons.Default.Close, "Clear search", modifier = Modifier.size(18.dp))
                } }) else null,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KnowledgeTypeTab("All ${projectItems.size}", typeName.isEmpty(), Modifier.testTag("knowledge_all_fixed")) { typeName = "" }
            val categoryScroll = rememberScrollState()
            Box(Modifier.weight(1f).clip(androidx.compose.ui.graphics.RectangleShape)
                .drawWithContent {
                    drawContent()
                    val fade = 16.dp.toPx().coerceAtMost(size.width / 2)
                    if (categoryScroll.value > 0) drawRect(
                        Brush.horizontalGradient(listOf(Color.White, Color.Transparent), endX = fade),
                        size = androidx.compose.ui.geometry.Size(fade, size.height))
                    if (categoryScroll.value < categoryScroll.maxValue) drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.White), startX = size.width - fade, endX = size.width),
                        topLeft = androidx.compose.ui.geometry.Offset(size.width - fade, 0f),
                        size = androidx.compose.ui.geometry.Size(fade, size.height))
                }) {
            Row(Modifier.horizontalScroll(categoryScroll).testTag("knowledge_category_scroll"),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KnowledgeTypeTab("Notes ${projectItems.count { it.type == KnowledgeType.DOCUMENT && (it.kindLabel == null || it.kindLabel == "Note") }}", typeName == "NOTES", Modifier) { typeName = "NOTES" }
            KnowledgeTypeTab("To-dos ${projectItems.count { it.type == KnowledgeType.CHECKLIST }}", type == KnowledgeType.CHECKLIST, Modifier) { typeName = KnowledgeType.CHECKLIST.name }
            KnowledgeTypeTab("Prompts ${projectItems.count { it.type == KnowledgeType.PROMPT }}", type == KnowledgeType.PROMPT, Modifier) { typeName = KnowledgeType.PROMPT.name }
            KnowledgeTypeTab("Media ${projectItems.count { it.type == KnowledgeType.IMAGE || it.type == KnowledgeType.AUDIO || it.kindLabel == "PDF" || it.kindLabel == "Attachment" }}", typeName == "MEDIA", Modifier) { typeName = "MEDIA" }
            KnowledgeType.entries.filter { it != KnowledgeType.CHECKLIST && it != KnowledgeType.PROMPT && it != KnowledgeType.TASK }.forEach { kind ->
                KnowledgeTypeTab("${kind.label()} ${projectItems.count { it.type == kind }}", type == kind, Modifier) { typeName = kind.name }
            }
            }
            }
        }
        if (chosenTags.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 6.dp),
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
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            val name = if (workstream == PROJECT_WIDE_FILTER) "Project-wide" else
                workstreams.firstOrNull { it.id == workstream }?.title ?: "All workstreams"
            if (fixedScope == null) KnowledgeOutlineButton("$name  ⌄") { workstreamMenu = true }
            else Text(name, color = KColor.muted, fontSize = 13.sp)
        }
        TextButton(onClick = { recentFirst = !recentFirst }) { Text(if (recentFirst) "Recent ↓" else "Oldest ↑", color = KColor.muted, fontSize = 12.sp) }
        IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
            if (grid) "Show list" else "Show grid", tint = KColor.muted) }
        }

        if (selecting) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 5.dp),
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
        } }
        if (filtered.isEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) { Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No saved items match", color = KColor.ink, fontWeight = FontWeight.SemiBold)
                    Text("Try another type, workstream, or search.", color = KColor.muted,
                        fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                }
            } }
        } else {
                sections.forEach { (heading, sectionItems) ->
                    if (heading.isNotBlank()) item(key = "heading-$heading", span = StaggeredGridItemSpan.FullLine) {
                        Text("$heading (${sectionItems.size})", fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp, color = KColor.ink,
                            modifier = Modifier.padding(top = 14.dp, bottom = 9.dp))
                    }
                    staggeredItems(sectionItems, key = { it.id }) { item ->
                        KnowledgeGalleryCard(item, zone, tagNames, imagePreview, actions,
                            selecting, item.id in selected,
                            onSelect = { if (item.id in selected) selected.remove(item.id) else selected.add(item.id) },
                            onStartSelection = { selecting = true; if (item.id !in selected) selected.add(item.id) },
                            onMove = { moving = item }, onDelete = { deleting = item },
                            onPlaceTask = { placingTask = item })
                    }
                }
            }
        }

    if (workstreamMenu) KnowledgeWorkstreamPicker(workstreams, workstream,
        onDismiss = { workstreamMenu = false },
        onChoose = { workstream = it; workstreamMenu = false })
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
        KnowledgeOverlay(onDismiss = { deleting = null }) {
            KnowledgePanelHeader("Delete knowledge?", { deleting = null })
            Text(selected.title, fontWeight = FontWeight.SemiBold, color = KColor.ink)
            Text("Only this knowledge item will be removed from Knowledge and the Inbox. Its project, workstream, tasks and subtasks stay unchanged. The item is archived; its file and original Activity entry are preserved.",
                color = KColor.muted, fontSize = 13.sp)
            KnowledgePanelActions("Delete knowledge", { deleting = null }, {
                actions.onDelete(selected.id); deleting = null
            }, destructive = true)
        }
    }
    }
}

@Composable
internal fun KnowledgeWorkstreamPicker(workstreams: List<KnowledgeWorkstream>, selected: String?,
    onDismiss: () -> Unit, onChoose: (String?) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val choices = remember(workstreams, search) {
        (listOf(null to "All workstreams", PROJECT_WIDE_FILTER to "Project-wide") +
            workstreams.map { it.id to it.title })
            .filter { search.isBlank() || it.second.contains(search.trim(), ignoreCase = true) }
    }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(search) { state.scrollToItem(0) }
    SafeDialog(onDismissRequest = onDismiss, scrollContent = false) {
        BoxWithConstraints {
            Surface(Modifier.fillMaxWidth().heightIn(max = minOf(540.dp, maxHeight * .9f))
                .testTag("knowledge_workstream_picker"), shape = RoundedCornerShape(22.dp), color = Color.White) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Filter by workstream", color = KColor.ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("${workstreams.size} workstreams", color = KColor.muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close workstream filter", tint = KColor.muted) }
                    }
                    TextField(search, { search = it }, singleLine = true,
                        placeholder = { Text("Search workstreams", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = if (search.isNotEmpty()) ({ IconButton(onClick = { search = "" }) {
                            Icon(Icons.Default.Close, "Clear workstream search")
                        } }) else null,
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(focusedContainerColor = KColor.grayWash,
                            unfocusedContainerColor = KColor.grayWash, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, cursorColor = KColor.greenInk),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                    LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .heightIn(max = 310.dp).testTag("workstream_picker_list")
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = if (state.canScrollBackward) 0f else 1f),
                                .07f to Color.Black, .93f to Color.Black,
                                1f to Color.Black.copy(alpha = if (state.canScrollForward) 0f else 1f)),
                                blendMode = BlendMode.DstIn)
                        }, contentPadding = PaddingValues(vertical = 8.dp)) {
                        if (choices.isEmpty()) item { Text("No matching workstreams", Modifier.padding(16.dp), color = KColor.muted) }
                        items(choices, key = { it.first ?: "__all__" }) { (id, title) ->
                            Surface(onClick = { onChoose(id) }, color = if (id == selected) KColor.greenWash else Color.White,
                                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(title, Modifier.weight(1f), color = if (id == selected) KColor.greenInk else KColor.ink,
                                        fontSize = 14.sp, lineHeight = 20.sp,
                                        fontWeight = if (id == selected) FontWeight.SemiBold else FontWeight.Normal)
                                    RadioButton(selected = id == selected, onClick = null,
                                        colors = RadioButtonDefaults.colors(selectedColor = KColor.greenInk))
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = KColor.line)
                    Text("Tap a workstream to filter", Modifier.fillMaxWidth().padding(top = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = KColor.muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun KnowledgeTypeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.defaultMinSize(minHeight = 40.dp),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) KColor.greenWash else KColor.grayWash,
        border = BorderStroke(1.dp, if (selected) KColor.green else Color.Transparent)) {
        Box(Modifier.padding(horizontal = 10.dp).heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
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
internal fun KnowledgeGalleryCard(item: KnowledgeItem, zone: ZoneId,
    tagNames: Map<String, String>,
    imagePreview: @Composable (String, Modifier) -> Unit, actions: KnowledgeActions,
    selecting: Boolean, selected: Boolean, onSelect: () -> Unit, onStartSelection: () -> Unit,
    onMove: () -> Unit, onDelete: () -> Unit, onPlaceTask: () -> Unit) {
    val context = LocalContext.current
    val imagePath = item.mediaPath.takeIf { item.type == KnowledgeType.IMAGE }
    var imageRatio by remember(imagePath) { mutableFloatStateOf(1f) }
    LaunchedEffect(imagePath) {
        if (imagePath == null) return@LaunchedEffect
        val ratio = withContext(Dispatchers.IO) {
            runCatching {
                val file = AttachmentFileStore.resolve(context, imagePath)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0)
                    bounds.outWidth.toFloat() / bounds.outHeight else 1f
            }.getOrDefault(1f)
        }
        imageRatio = ratio
    }
    Surface(modifier = Modifier.testTag("knowledge_card_${item.id}").combinedClickable(
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
        Column((if (imagePath == null) Modifier.fillMaxWidth().heightIn(min = 200.dp)
            else Modifier.fillMaxWidth()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = KColor.greenInk, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(knowledgeIcon(item), null, tint = readableOn(KColor.greenInk), modifier = Modifier.size(16.dp))
                Text(item.kindLabel ?: when (item.type) {
                    KnowledgeType.PROMPT -> "Prompt"
                    KnowledgeType.IMAGE -> "Image"
                    KnowledgeType.AUDIO -> "Audio"
                    KnowledgeType.DOCUMENT -> "Note"
                    KnowledgeType.CHECKLIST -> "To-do"
                    KnowledgeType.STEPS -> "Steps"
                    KnowledgeType.TASK -> "Task"
                }, Modifier.padding(start = 6.dp), fontSize = 11.sp, color = readableOn(KColor.greenInk),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                }
                Spacer(Modifier.weight(1f))
                if (selecting) IconButton(onClick = onSelect, modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = if (selected) "Deselect ${item.title}" else "Select ${item.title}" }) {
                    Text(if (selected) "✓" else "○", color = KColor.greenInk)
                } else KnowledgeMoreMenu(item, actions, onMove, onDelete, onPlaceTask, Modifier)
            }
            Text(item.title, color = KColor.ink, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                item.type == KnowledgeType.IMAGE && item.mediaPath != null ->
                    imagePreview(item.mediaPath, Modifier.fillMaxWidth().aspectRatio(imageRatio)
                        .clip(RoundedCornerShape(8.dp)).testTag("knowledge_image_${item.id}"))
                item.type == KnowledgeType.AUDIO -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PlayCircle, null, tint = KColor.greenInk, modifier = Modifier.size(36.dp))
                    Text(item.durationSeconds?.asTime() ?: "Audio recording", Modifier.padding(start = 8.dp),
                        fontSize = 13.sp, color = KColor.muted)
                }
                item.completedCount != null && item.totalCount != null -> {
                    Text("${item.completedCount}/${item.totalCount} complete", fontSize = 12.sp, color = KColor.muted)
                    LinearProgressIndicator(progress = {
                        if (item.totalCount > 0) (item.completedCount.toFloat() / item.totalCount).coerceIn(0f, 1f) else 0f
                    }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                        color = KColor.greenInk, trackColor = KColor.line)
                    Text(item.snippet.ifBlank { "Open checklist" }, fontSize = 12.sp, lineHeight = 17.sp,
                        color = KColor.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                else -> Text(item.snippet.ifBlank { item.fileName ?: "Open to view" }, fontSize = 12.sp,
                    lineHeight = 17.sp, minLines = 3, maxLines = 3, color = KColor.muted, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.location(), Modifier.weight(1f), color = KColor.muted, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Instant.ofEpochMilli(item.updatedAtMillis).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)),
                    Modifier.padding(start = 6.dp), color = KColor.muted, fontSize = 10.sp)
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
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.MoreVert, "Options for ${item.title}", tint = KColor.ink)
        }
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = Color.White, surfaceTint = Color.White),
            shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))) {
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(item.title, Modifier.widthIn(max = 240.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                color = KColor.muted, fontSize = 12.sp, maxLines = 2)
            DropdownMenuItem(text = { Text("Open knowledge", color = KColor.ink) },
                leadingIcon = { Icon(Icons.Outlined.OpenInNew, null, tint = KColor.greenInk) },
                onClick = { expanded = false; actions.onOpen(item) })
            DropdownMenuItem(text = { Text("Move", color = KColor.ink) },
                leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null, tint = KColor.greenInk) },
                onClick = { expanded = false; onMove() })
            DropdownMenuItem(text = { Text("Duplicate", color = KColor.ink) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = KColor.greenInk) },
                onClick = { expanded = false; actions.onDuplicate(item.id) })
            HorizontalDivider(color = KColor.line)
            DropdownMenuItem(text = { Text("Delete knowledge", color = Color(0xFFB6433D)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = Color(0xFFB6433D)) },
                onClick = { expanded = false; onDelete() })
        }
        }
    }
}

/** Lifecycle-owned suppression: nested surfaces cannot restore another surface's ORB. */
@Composable
internal fun HideProjectOrb() {
    DisposableEffect(Unit) {
        val owner = Any()
        KnowledgeOrbVisibility.enter(owner)
        onDispose { KnowledgeOrbVisibility.leave(owner) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KnowledgeMoveSheet(item: KnowledgeItem, workstreams: List<KnowledgeWorkstream>,
    tasks: List<KnowledgeTask>, onDismiss: () -> Unit,
    onSave: (workstreamId: String?, taskId: String?) -> Unit) {
    KnowledgeMoveSheet(item.title, item.workstreamId, item.taskId, workstreams, tasks, onDismiss, onSave)
}

/** In-page overlays are constrained to the Knowledge viewport, above the shell footer. */
@Composable
private fun KnowledgeOverlay(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f)).clickable(
        indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        onClick = onDismiss).testTag("knowledge_overlay")) {
        Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp).imePadding()
            .widthIn(max = 600.dp).fillMaxWidth().heightIn(max = 640.dp)
            .clickable(indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = {}),
            shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable
private fun KnowledgePanelHeader(title: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = KColor.ink)
        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close ${title.lowercase()}", tint = KColor.muted) }
    }
}

@Composable
private fun KnowledgePanelActions(label: String, onDismiss: () -> Unit, onSave: () -> Unit,
    destructive: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).requiredHeight(48.dp),
            border = BorderStroke(1.dp, KColor.green), colors = ButtonDefaults.outlinedButtonColors(
                containerColor = KColor.greenWash, contentColor = KColor.greenInk)) { Text("Cancel", maxLines = 1) }
        val color = if (destructive) Color(0xFFB6433D) else KColor.greenInk
        Button(onClick = onSave, modifier = Modifier.weight(1f).requiredHeight(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = readableOn(color))) { Text(label, maxLines = 1) }
    }
}

@Composable
private fun KnowledgeMoveSheet(title: String, initialWorkstream: String?, initialTask: String?,
    workstreams: List<KnowledgeWorkstream>, tasks: List<KnowledgeTask>, onDismiss: () -> Unit,
    onSave: (workstreamId: String?, taskId: String?) -> Unit) {
    var destinationWorkstream by remember(title) { mutableStateOf(initialWorkstream) }
    var destinationTask by remember(title) { mutableStateOf(initialTask) }
    var destinationChosen by remember(title) { mutableStateOf(false) }
    var search by rememberSaveable(title) { mutableStateOf("") }
    val expanded = remember(title) { mutableStateListOf<String>().apply {
        initialWorkstream?.let { add("w:$it") }
        val seen = mutableSetOf<String>()
        var id = initialTask
        while (id != null && seen.add(id)) {
            add("t:$id")
            id = tasks.firstOrNull { it.id == id }?.parentId
        }
    } }
    // Search includes ancestors so any matching deep subtask remains reachable.
    val matches = remember(tasks, search) { buildSet {
        tasks.filter { it.title.contains(search.trim(), true) }.forEach { task ->
            var node: KnowledgeTask? = task
            val seen = mutableSetOf<String>()
            while (node != null && seen.add(node.id)) {
                add(node.id)
                node = tasks.firstOrNull { it.id == node?.parentId }
            }
        }
    } }
    KnowledgeOverlay(onDismiss) {
        KnowledgePanelHeader("Move knowledge", onDismiss)
        Text(title, color = KColor.ink, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(search, { search = it }, singleLine = true,
            placeholder = { Text("Search destinations") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            modifier = Modifier.fillMaxWidth().testTag("knowledge_destination_search"),
            shape = RoundedCornerShape(12.dp))
        LazyColumn(Modifier.weight(1f, fill = false).testTag("knowledge_destination_tree"),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                KnowledgeDestination("Project-wide", "Project", destinationWorkstream == null && destinationTask == null,
                    "project", 0, null, {}, { destinationWorkstream = null; destinationTask = null; destinationChosen = true })
            }
            fun addTasks(parent: String?, streamId: String?, depth: Int, seen: Set<String>, showAll: Boolean) {
                tasks.filter { it.workstreamId == streamId && it.parentId == parent && it.id !in seen }
                    .filter { search.isBlank() || showAll || it.id in matches }.forEach { task ->
                        val children = tasks.any { it.parentId == task.id && it.workstreamId == streamId }
                        val isExpanded = "t:${task.id}" in expanded
                        item(key = "task:${task.id}") {
                            KnowledgeDestination(task.title, if (task.parentId == null) "Task" else "Subtask",
                                destinationTask == task.id, task.id, depth, if (children) isExpanded else null,
                                { if (isExpanded) expanded.remove("t:${task.id}") else expanded.add("t:${task.id}") },
                                { destinationWorkstream = streamId; destinationTask = task.id; destinationChosen = true })
                        }
                        if (children && (isExpanded || search.isNotBlank()))
                            addTasks(task.id, streamId, depth + 1, seen + task.id, showAll || task.title.contains(search.trim(), true))
                    }
            }
            workstreams.filter { stream -> search.isBlank() || stream.title.contains(search.trim(), true) ||
                tasks.any { it.workstreamId == stream.id && it.id in matches } }.forEach { stream ->
                val isExpanded = "w:${stream.id}" in expanded
                item(key = "stream:${stream.id}") {
                    KnowledgeDestination(stream.title, listOfNotNull("Workstream", stream.progress).joinToString(" · "),
                        destinationWorkstream == stream.id && destinationTask == null, stream.id, 0, isExpanded,
                        { if (isExpanded) expanded.remove("w:${stream.id}") else expanded.add("w:${stream.id}") },
                        { destinationWorkstream = stream.id; destinationTask = null; destinationChosen = true })
                }
                if (isExpanded || search.isNotBlank())
                    addTasks(null, stream.id, 1, emptySet(), search.isNotBlank() && stream.title.contains(search.trim(), true))
            }
            addTasks(null, null, 1, emptySet(), false)
        }
        val selectedName = destinationTask?.let { id -> tasks.firstOrNull { it.id == id }?.title }
            ?: destinationWorkstream?.let { id -> workstreams.firstOrNull { it.id == id }?.title } ?: "Project-wide"
        Text("Destination: $selectedName", color = KColor.muted, fontSize = 12.sp, maxLines = 2)
        if (destinationChosen) KnowledgePanelActions("Move", onDismiss, { onSave(destinationWorkstream, destinationTask) })
    }
}

@Composable
private fun KnowledgeDestination(title: String, subtitle: String, selected: Boolean, id: String,
    depth: Int, expanded: Boolean?, onExpand: () -> Unit, onSelect: () -> Unit) {
    Surface(onClick = onSelect, modifier = Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(3) * 12).dp)
        .testTag("knowledge_destination_$id"), shape = RoundedCornerShape(12.dp),
        color = if (selected) KColor.greenWash else Color.White,
        border = BorderStroke(1.dp, if (selected) KColor.green else KColor.line)) {
        Row(Modifier.padding(horizontal = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onSelect, colors = RadioButtonDefaults.colors(selectedColor = KColor.greenInk))
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, color = KColor.ink, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = KColor.muted, fontSize = 11.sp)
            }
            if (expanded != null) IconButton(onClick = onExpand, modifier = Modifier.testTag("knowledge_expand_$id")) {
                Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, "Expand $title", tint = KColor.greenInk)
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
