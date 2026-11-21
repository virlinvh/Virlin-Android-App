package com.virlin.app.ui.hierarchy

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.project.*
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.project.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

private enum class StorageSection { MENU, USAGE, EXPORT, DUPLICATE }

/** Keep one in-flight operation alive through configuration changes; never replay a copy. */
internal class ProjectStorageOperations : ViewModel() {
    val busy = mutableStateOf(false)
    val message = mutableStateOf<String?>(null)
    val breakdown = mutableStateOf<ProjectStorageBreakdown?>(null)
    val exportPreview = mutableStateOf<VirlinExportPreview?>(null)
}

@Composable
internal fun ProjectStorageSettings(project: Project, repository: com.virlin.app.domain.repository.WorkStreamRepository = VirlinGraph.repository) {
    val context = LocalContext.current
    val operations: ProjectStorageOperations = viewModel(key = "project-storage-${project.id}")
    val scope = operations.viewModelScope
    val tools = remember(context, repository) { ProjectStorageTools(context.applicationContext, repository) }
    var section by rememberSaveable(project.id) { mutableStateOf(StorageSection.MENU) }
    var busy by operations.busy
    var message by operations.message
    var breakdown by operations.breakdown
    var name by rememberSaveable(project.id) { mutableStateOf((project.title.take(53) + " (copy)").take(60)) }
    var mode by rememberSaveable(project.id) { mutableStateOf(ProjectCopyMode.STRUCTURE) }
    var pendingPath by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var pendingSha by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var pendingDone by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var timing by rememberSaveable(project.id) { mutableStateOf(VirlinTiming.KEEP) }
    var exportPreview by operations.exportPreview
    val accent = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF087848)
    fun open(value: StorageSection) { section = value; message = null }
    if (section != StorageSection.MENU) BackHandler { if (!busy) open(StorageSection.MENU) }
    val saveDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = pendingPath?.let(::File)
        val uri = result.data?.data.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        if (file != null) scope.launch {
            busy = true
            try {
                if (uri != null) {
                    // null = this location cannot be read back; false = the saved copy differs.
                    val matches = withContext(Dispatchers.IO) {
                        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot write to this location." }
                        output.use { out -> file.inputStream().use { it.copyTo(out) } }
                        runCatching { context.contentResolver.openInputStream(uri)?.use(VirlinProjectFile::sha256) }.getOrNull()
                            ?.let { it == pendingSha }
                    }
                    message = when (matches) {
                        true -> "Saved and verified. ${pendingDone.orEmpty()}"
                        null -> "Saved. This location could not be read back for a second check. ${pendingDone.orEmpty()}"
                        false -> "The saved copy does not match the export. Please export again to another location."
                    }
                } else message = "Export cancelled. Nothing was saved."
            } catch (error: Exception) {
                message = "Could not finish export. A partial file may remain at the chosen location. Please try again."
            } finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() }; pendingPath = null; pendingSha = null; pendingDone = null; busy = false }
        }
    }
    // Live: re-measured on any project change, on return to the screen, and every few seconds
    // while it is open (files such as note pictures change without a repository event).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumes by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val changes by remember(repository) {
        kotlinx.coroutines.flow.combine(listOf<kotlinx.coroutines.flow.Flow<Any>>(repository.projects, repository.streams,
            repository.tasks, repository.captures, repository.taskPageBlocks, repository.taskSteps,
            repository.tags, repository.tagLinks)) { it.toList() }
    }.collectAsState(initial = null)
    LaunchedEffect(section, project.id, changes, resumes) {
        if (section != StorageSection.EXPORT) return@LaunchedEffect
        try {
            exportPreview = withContext(Dispatchers.IO) {
                val s = repository.projectSnapshot(project.id)
                val b = tools.breakdown(s)
                VirlinExportPreview(s.streams.size, s.tasks.size, s.steps.size, s.richNotes.size + s.notes.size,
                    s.pageBlocks.size, s.captures.size, b.row(ProjectStorageKeys.FLASHCARDS).items, s.previews.size,
                    b.files, b.media, b.missing)
            }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (_: Exception) { }
    }
    LaunchedEffect(section, project.id, changes, resumes) {
        if (section != StorageSection.USAGE) return@LaunchedEffect
        while (true) {
            try { breakdown = withContext(Dispatchers.IO) { tools.breakdown(repository.projectSnapshot(project.id)) } }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { if (breakdown == null) message = error.message ?: "Could not read storage usage." }
            kotlinx.coroutines.delay(4_000)
        }
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("project_storage_settings")) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (section != StorageSection.MENU) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open(StorageSection.MENU) }, enabled = !busy) { Text("Back", color = accent) }
                Text(when (section) { StorageSection.USAGE -> "File and media storage"; StorageSection.EXPORT -> "Export project";
                    StorageSection.DUPLICATE -> "Duplicate project"; else -> "" }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            when (section) {
                StorageSection.MENU -> {
                    StorageOption("File and media storage", "Exact space this project uses, split by kind", !busy) { open(StorageSection.USAGE) }
                    StorageOption("Export project", "One .virlin file with everything, media included", !busy) { open(StorageSection.EXPORT) }
                    StorageOption("Duplicate project", "Structure or full content · fresh activity", !busy) { open(StorageSection.DUPLICATE) }
                }
                StorageSection.USAGE -> {
                    val value = breakdown
                    if (value != null) ProjectStorageUsageView(value, accent)
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent); Text("Measuring…", color = accent)
                    }
                }
                StorageSection.EXPORT -> {
                    ProjectVirlinExportView(exportPreview, timing, { timing = it }, !busy && pendingPath == null, onExport = {
                        scope.launch {
                            busy = true; message = null
                            try {
                                val export = withContext(Dispatchers.IO) { tools.exportVirlin(repository.projectSnapshot(project.id), timing) }
                                val summary = export.summary
                                pendingPath = export.file.path; pendingSha = export.sha256
                                pendingDone = "${summary.mediaFiles} original file${if (summary.mediaFiles == 1) "" else "s"} " +
                                    "(${android.text.format.Formatter.formatFileSize(context, summary.mediaBytes)}) and all project data" +
                                    (if (summary.timing == VirlinTiming.RESET) ", time spent reset" else ", time kept") + ". " +
                                    (if (summary.missing.isNotEmpty()) "${summary.missing.size} already-missing file${if (summary.missing.size == 1) " is" else "s are"} listed in it. " else "") +
                                    "This project is unchanged."
                                saveDocument.launch(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(android.content.Intent.CATEGORY_OPENABLE); type = VirlinProjectFile.SAVE_MIME_TYPE
                                    putExtra(android.content.Intent.EXTRA_TITLE, VirlinProjectFile.fileName(project.title))
                                })
                            } catch (error: Exception) {
                                pendingPath?.let(::File)?.delete(); pendingPath = null; pendingSha = null; pendingDone = null
                                message = error.message ?: "Could not prepare the export. Nothing was saved."
                            }
                            finally { busy = false }
                        }
                    }, accent = accent)
                }
                StorageSection.DUPLICATE -> {
                    OutlinedTextField(name, { name = it.take(60) }, enabled = !busy, label = { Text("New project name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("duplicate_project_name"))
                    ProjectCopyMode.entries.forEach { option ->
                        Row(Modifier.fillMaxWidth().background(if (mode == option) accent.copy(alpha = .08f) else Color.White, RoundedCornerShape(16.dp))
                            .clickable(enabled = !busy) { mode = option }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(mode == option, onClick = { mode = option }, enabled = !busy)
                            Column(Modifier.weight(1f)) {
                                Text(if (option == ProjectCopyMode.STRUCTURE) "Structure only" else "Full content", fontWeight = FontWeight.Bold)
                                Text(if (option == ProjectCopyMode.STRUCTURE) "Project, workstreams and task hierarchy" else "Also copy knowledge, notes, checklists, tags, Page content and managed media", fontSize = 12.sp)
                            }
                        }
                    }
                    Text("Both copies preserve execution preferences, ordering and project identity. All work starts fresh: no completion, running timers, history, due dates or reminders. The original stays unchanged; copied media belongs to the new project.", fontSize = 13.sp, color = Color(0xFF526171))
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = accent); Text("Working…", color = accent)
            }
            message?.let { Text(it, Modifier.testTag("project_storage_message"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (section == StorageSection.DUPLICATE) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { open(StorageSection.MENU) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = {
                    scope.launch {
                        busy = true; message = null
                        try {
                            // Finish independently of navigation; metadata commits atomically.
                            val created = withContext(NonCancellable + Dispatchers.IO) {
                                tools.duplicate(repository.projectSnapshot(project.id), name, mode)
                            }
                            message = "Created ${created.title}. Find it in Projects."; section = StorageSection.MENU
                        } catch (error: Exception) { message = error.message ?: "Could not copy project. Original unchanged." }
                        finally { busy = false }
                    }
                }, enabled = !busy && name.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("confirm_duplicate_project"),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = readableOn(accent))) { Text("Create copy") }
            }
        }
    }
}

@Composable
private fun StorageOption(title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp))
        .border(1.dp, Color(0xFFDDE6E1), RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, fontSize = 12.sp, color = Color(0xFF526171))
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = Color(0xFF526171))
    }
}
