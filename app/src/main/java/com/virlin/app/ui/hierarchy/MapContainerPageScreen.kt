package com.virlin.app.ui.hierarchy

import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskPageBlock
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.activity.NoteBlockText
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import com.virlin.app.domain.model.TaskStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import com.virlin.app.domain.VirlinGraph

/** A map Page is a content view, not the project's Overview/Knowledge/Activity tab screen. */
@Composable
fun MapContainerPageScreen(
    projectId: String?, scope: String?, nav: NavController, vm: HierarchyViewModel = viewModel()
) {
    val snapshot by vm.snapshot.collectAsState()
    val entries by vm.knowledge.collectAsState()
    val links by vm.tagLinks.collectAsState()
    val taskPages by VirlinGraph.repository.taskPageBlocks.collectAsState()
    val taskSteps by VirlinGraph.repository.taskSteps.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val noteDocs = remember { mutableStateMapOf<String, VirlinNoteDoc?>() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeVersion by remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeVersion++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val project = snapshot.projects.firstOrNull { it.id == projectId }
    val stream = snapshot.streams.firstOrNull { it.id == scope && it.projectId == projectId }
    val projectPage = scope == "project"
    if (project == null || (!projectPage && stream == null)) {
        Text("This page is no longer available", Modifier.padding(24.dp))
        return
    }
    LaunchedEffect(project.id) { vm.openKnowledge(project.id) }
    val palette = rememberProjectPalette(project.id)
    val accent = palette?.let { Color(it.secondary) } ?: Color(0xFF087848)
    val wash = palette?.let { Color(it.tertiary) } ?: Color(0xFFE9F5ED)
    val ink = palette?.let { Color(it.text) } ?: Color(0xFF17202C)
    val muted = palette?.let { Color(it.muted) } ?: Color(0xFF526171)
    val paper = palette?.let { Color(it.surface) } ?: Color.White
    val title = stream?.title ?: project.title
    val allItems = remember(project.id, entries, snapshot.streams, snapshot.tasks, links) {
        knowledgeItems(project.id, entries, snapshot.streams, snapshot.tasks, links)
    }
    var acrossMap by rememberSaveable(project.id, scope) { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val sections = remember(allItems, snapshot.streams, snapshot.tasks, scope, acrossMap) {
        mapPageSections(allItems, snapshot.streams, snapshot.tasks,
            workStreamId = stream?.id, acrossMap = acrossMap)
    }
    val pageBlocks = remember(taskPages, snapshot.tasks, project.id, scope, acrossMap) {
        if (acrossMap) mapTaskPageEntries(taskPages, snapshot.tasks, project.id, stream?.id)
        else emptyList()
    }
    val allSections = remember(allItems, snapshot.streams, snapshot.tasks, scope) {
        mapPageSections(allItems, snapshot.streams, snapshot.tasks, stream?.id, acrossMap = true)
    }
    val allPageBlocks = remember(taskPages, snapshot.tasks, project.id, scope) {
        mapTaskPageEntries(taskPages, snapshot.tasks, project.id, stream?.id)
    }
    LaunchedEffect(allPageBlocks, resumeVersion) {
        allPageBlocks.filter { it.block.typeKey == TaskPageTypeKeys.NOTE }.forEach { entry ->
            noteDocs[entry.block.taskId] = VirlinGraph.repository.getNoteDoc(
                TaskPageTypeKeys.noteOwner(entry.block.taskId))
        }
    }
    val captureEntries = remember(entries) { entries.associateBy { it.capture.id } }
    val documentSections = remember(sections, pageBlocks, captureEntries, taskSteps, noteDocs.toMap()) {
        masterSections(sections, pageBlocks, captureEntries, taskSteps, noteDocs, vm)
    }
    val exportSections = remember(allSections, allPageBlocks, captureEntries, taskSteps, noteDocs.toMap()) {
        masterSections(allSections, allPageBlocks, captureEntries, taskSteps, noteDocs, vm)
    }
    val expanded = remember(project.id, scope) { mutableStateMapOf<String, Boolean>() }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) coroutineScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    MasterDocumentPdf.write(context, "${project.title} · ${if (projectPage) "Project master document" else "Workstream document"}",
                        exportSections, output)
                } ?: error("Could not open export destination")
            } }.onSuccess { Toast.makeText(context, "PDF exported", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "PDF export failed", Toast.LENGTH_LONG).show() }
        }
    }
    CompositionLocalProvider(LocalProjectPalette provides palette) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = accent,
            onPrimary = readableOn(accent), surface = paper, onSurface = ink)) {
            Column(Modifier.fillMaxSize().background(paper).testTag("map_container_page")) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = ink)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = ink, fontWeight = FontWeight.Bold, fontSize = 19.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (projectPage) "Project master document" else "Workstream document",
                            color = muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { exportPdf.launch("${title.replace(Regex("[^A-Za-z0-9 _-]"), "")}.pdf") },
                        modifier = Modifier.testTag("map_page_export_pdf")) {
                        Icon(Icons.Outlined.PictureAsPdf, "Export complete PDF", tint = accent)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                    .background(wash, RoundedCornerShape(16.dp)).padding(4.dp)) {
                    listOf("Added here", if (projectPage) "Across map" else "Across branch").forEachIndexed { index, label ->
                        val chosen = acrossMap == (index == 1)
                        Box(Modifier.weight(1f)
                            .background(if (chosen) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { acrossMap = index == 1 }
                            .testTag(if (index == 0) "map_page_added_here" else "map_page_across")
                            .padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
                            Text(label, color = if (chosen) accent else muted,
                                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 14.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (acrossMap) "${if (projectPage) "Project" else "Workstream"} Master Document" else
                            if (projectPage) "Project Knowledge" else "Workstream Knowledge",
                            color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(if (acrossMap) "Read everything here. Tap an entry to edit it."
                            else if (projectPage) "Your project's home for notes, files, and ideas."
                            else "A home for this workstream's notes and resources.",
                            color = muted, fontSize = 12.sp, lineHeight = 16.sp)
                        Text("${sections.sumOf { it.items.size } + pageBlocks.size} items", color = muted, fontSize = 13.sp)
                    }
                    Surface(onClick = { adding = true }, color = wash,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("map_page_add")) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Add, null, tint = accent, modifier = Modifier.size(18.dp))
                            Text("Add", color = accent,
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
                if (documentSections.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (acrossMap) "Nothing has been added across this map yet."
                        else "Nothing has been added here yet.", color = muted)
                } else LazyColumn(Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    documentSections.forEachIndexed { index, section ->
                        val key = "$index-${section.heading}"
                        item(key = "heading-$key") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                .background(wash, RoundedCornerShape(14.dp))
                                .clickable { expanded[key] = expanded[key] == false }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(section.heading, Modifier.weight(1f), color = accent,
                                    fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("${section.entries.size}  ${if (expanded[key] == false) "▸" else "▾"}",
                                    color = accent, fontSize = 13.sp)
                            }
                        }
                        if (expanded[key] != false) items(section.entries, key = { "document-${it.id}" }) { entry ->
                            MasterDocumentItem(entry, ink, accent) { nav.navigate(entry.route) }
                        }
                    }
                    item { Spacer(Modifier.size(20.dp)) }
                }
            }
            if (adding) ProjectKnowledgeAdd(project.id, title, nav, workStreamId = stream?.id) {
                adding = false
            }
        }
    }
}

@Composable
internal fun MasterDocumentItem(entry: MasterDocumentEntry, ink: Color,
    accent: Color, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)
        .testTag("map_page_item_${entry.id}")
        .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.title, Modifier.weight(1f), color = ink,
                fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp)
            Text("${entry.kind}  ↗", color = accent, fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.size(9.dp))
        MasterEntryBody(entry, ink)
    }
}

@Composable
private fun MasterEntryBody(entry: MasterDocumentEntry, ink: Color) {
    val context = LocalContext.current
    var preview by remember(entry.mediaPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(entry.mediaPath, entry.isImage) {
        preview = if (entry.isImage && entry.mediaPath != null) withContext(Dispatchers.IO) {
            val file = com.virlin.app.data.attachment.AttachmentFileStore.resolve(context, entry.mediaPath)
            if (!file.isFile) null else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 900).coerceAtLeast(1)
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } else null
    }
    preview?.let { image ->
        Image(image.asImageBitmap(), entry.title,
            Modifier.fillMaxWidth().padding(bottom = 10.dp)
                .aspectRatio(image.width.toFloat() / image.height), contentScale = ContentScale.Fit)
    }
    val body = entry.body.takeUnless { entry.isImage && (it == entry.title || it == "Attached media") }
        .orEmpty()
    body.takeIf(String::isNotBlank)?.lines()?.forEach { raw ->
        val line = raw.trimEnd()
        val depth = when {
            line.startsWith("### ") -> 3
            line.startsWith("## ") -> 2
            line.startsWith("# ") -> 1
            else -> 0
        }
        if (line.isBlank()) Spacer(Modifier.size(8.dp))
        else Text(if (depth > 0) line.drop(depth + 1) else line,
            color = if (depth > 0) ink else ink.copy(alpha = .84f),
            fontSize = when (depth) { 1 -> 20.sp; 2 -> 17.sp; 3 -> 15.sp; else -> 15.sp },
            fontWeight = if (depth > 0) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 23.sp)
    }
}

internal data class MapPageSection(val key: String, val title: String, val items: List<KnowledgeItem>)
internal data class MapTaskPageEntry(val block: TaskPageBlock, val label: String,
    val taskPath: String, val route: String)

private fun masterSections(sections: List<MapPageSection>, pageBlocks: List<MapTaskPageEntry>,
    captureEntries: Map<String, HierarchyViewModel.KnowledgeEntry>, steps: List<TaskStep>,
    notes: Map<String, VirlinNoteDoc?>, vm: HierarchyViewModel): List<MasterDocumentSection> = buildList {
    sections.forEach { section ->
        add(MasterDocumentSection(section.title, section.items.map { item ->
            val source = captureEntries[item.id]
            val richBody = source?.richNote?.let { doc ->
                taskPageBody(TaskPageTypeKeys.NOTE, "", emptyList(), doc)
            }
            val body = richBody ?: source?.prompt?.blocks?.let(NoteBlockText::render)
                ?: source?.note?.blocks?.let(NoteBlockText::render)
                ?: source?.capture?.content.orEmpty()
            MasterDocumentEntry(item.id, item.title, item.kindLabel ?: "Note",
                body.ifBlank { item.snippet.ifBlank { item.fileName ?: "Attached media" } },
                captureRoute(item.id, vm), item.mediaPath,
                item.type == KnowledgeType.IMAGE)
        }))
    }
    pageBlocks.groupBy { it.taskPath }.forEach { (path, blocks) ->
        add(MasterDocumentSection("Task · $path", blocks.map { entry ->
            MasterDocumentEntry(entry.block.id, entry.label, entry.label,
                taskPageBody(entry.block.typeKey, entry.block.taskId, steps,
                    notes[entry.block.taskId]), entry.route)
        }))
    }
}

internal fun mapTaskPageEntries(blocks: List<TaskPageBlock>, tasks: List<Task>, projectId: String,
    workStreamId: String?): List<MapTaskPageEntry> {
    val byId = tasks.associateBy { it.id }
    return blocks.filter { it.typeKey == TaskPageTypeKeys.TODO || it.typeKey == TaskPageTypeKeys.NOTE }
        .mapNotNull { block ->
            val task = byId[block.taskId] ?: return@mapNotNull null
            if (task.projectId != projectId) return@mapNotNull null
            if (workStreamId != null && task.workStreamId != workStreamId) return@mapNotNull null
            val names = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            var node: Task? = task
            while (node != null && seen.add(node.id)) {
                names += node.title
                node = node.parentTaskId?.let(byId::get)
            }
            MapTaskPageEntry(block,
                if (block.typeKey == TaskPageTypeKeys.TODO) "To-do" else "Note",
                names.asReversed().joinToString(" › "),
                if (block.typeKey == TaskPageTypeKeys.TODO) com.virlin.app.ui.todo.taskTodo(task.id)
                else com.virlin.app.ui.notes.notesForTask(task.id, task.title))
        }.sortedWith(compareBy({ it.taskPath }, { it.block.order }, { it.block.id }))
}

/** Exact-owner view first; the second tab gathers every direct project/workstream/task capture. */
internal fun mapPageSections(items: List<KnowledgeItem>, streams: List<WorkStream>, tasks: List<Task>,
    workStreamId: String?, acrossMap: Boolean): List<MapPageSection> {
    val visible = items.filter { item -> workStreamId == null || item.workstreamId == workStreamId }
    fun section(key: String, title: String, rows: List<KnowledgeItem>) =
        rows.takeIf { it.isNotEmpty() }?.let { MapPageSection(key, title,
            it.sortedWith(compareByDescending<KnowledgeItem> { row -> row.updatedAtMillis }.thenBy { row -> row.id })) }
    val direct = visible.filter { it.taskId == null && it.workstreamId == workStreamId }
    if (!acrossMap) return listOfNotNull(section("direct", if (workStreamId == null) "In this library" else "Added to workstream", direct))
    val result = mutableListOf<MapPageSection>()
    if (workStreamId == null) section("general", "Project Knowledge", direct)?.let(result::add)
    val orderedStreams = streams.filter { workStreamId == null || it.id == workStreamId }
        .sortedWith(compareBy({ it.sortOrder }, { it.title }))
    orderedStreams.forEach { stream ->
        section("stream-${stream.id}", "Workstream · ${stream.title}",
            visible.filter { it.workstreamId == stream.id && it.taskId == null })?.let(result::add)
    }
    val tasksById = tasks.associateBy { it.id }
    fun taskPath(task: Task): String {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current: Task? = task
        while (current != null && seen.add(current.id)) {
            names.add(current.title)
            current = current.parentTaskId?.let(tasksById::get)
        }
        return names.asReversed().joinToString(" › ")
    }
    val orderedTasks = tasks.filter { task ->
        (workStreamId == null || task.workStreamId == workStreamId) &&
            visible.any { it.taskId == task.id }
    }.sortedWith(compareBy<Task>({ it.workStreamId?.let { id -> orderedStreams.indexOfFirst { stream -> stream.id == id } } ?: -1 },
        { taskPath(it) }, { it.order }))
    orderedTasks.forEach { task ->
        section("task-${task.id}", "${if (task.parentTaskId == null) "Task" else "Subtask"} · ${taskPath(task)}",
            visible.filter { it.taskId == task.id })?.let(result::add)
    }
    val knownTasks = orderedTasks.map { it.id }.toSet()
    section("other", "Other knowledge", visible.filter { it.taskId != null && it.taskId !in knownTasks })?.let(result::add)
    return result
}
