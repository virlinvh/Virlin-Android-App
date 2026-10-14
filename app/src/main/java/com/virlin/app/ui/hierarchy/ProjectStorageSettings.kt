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
    val usage = mutableStateOf<ProjectStorageUsage?>(null)
}

@Composable
internal fun ProjectStorageSettings(project: Project) {
    val context = LocalContext.current
    val operations: ProjectStorageOperations = viewModel(key = "project-storage-${project.id}")
    val scope = operations.viewModelScope
    val tools = remember(context) { ProjectStorageTools(context.applicationContext, VirlinGraph.repository) }
    var section by rememberSaveable(project.id) { mutableStateOf(StorageSection.MENU) }
    var busy by operations.busy
    var message by operations.message
    var usage by operations.usage
    var name by rememberSaveable(project.id) { mutableStateOf((project.title.take(53) + " (copy)").take(60)) }
    var mode by rememberSaveable(project.id) { mutableStateOf(ProjectCopyMode.STRUCTURE) }
    var pendingPath by rememberSaveable(project.id) { mutableStateOf<String?>(null) }
    var pendingFormat by rememberSaveable(project.id) { mutableStateOf<ProjectExportFormat?>(null) }
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
                    withContext(Dispatchers.IO) {
                        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot write to this location." }
                        output.use { out -> file.inputStream().use { it.copyTo(out) } }
                    }
                    message = "Export saved. Original project is unchanged."
                } else message = "Export cancelled."
            } catch (error: Exception) {
                message = "Could not finish export. A partial file may remain at the chosen location. Please try again."
            } finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() }; pendingPath = null; pendingFormat = null; busy = false }
        }
    }
    LaunchedEffect(section, project.id) {
        if (section == StorageSection.USAGE && !busy) {
            busy = true; usage = null
            try { usage = withContext(Dispatchers.IO) { tools.usage(VirlinGraph.repository.projectSnapshot(project.id)) } }
            catch (error: Exception) { message = error.message ?: "Could not read storage usage." }
            finally { busy = false }
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
                    StorageOption("File and media storage", "Review this project's managed media", !busy) { open(StorageSection.USAGE) }
                    StorageOption("Export project", "PDF, Markdown or ZIP archive", !busy) { open(StorageSection.EXPORT) }
                    StorageOption("Duplicate project", "Structure or full content · fresh activity", !busy) { open(StorageSection.DUPLICATE) }
                }
                StorageSection.USAGE -> {
                    usage?.let { value ->
                        Text(android.text.format.Formatter.formatFileSize(context, value.bytes), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = accent)
                        Text("${value.files} managed files", fontSize = 14.sp)
                        listOf("attachments" to "Files & attachment sidecars", "voices" to "Voice clips", "previews" to "Mind-map images", "icons" to "Project icon").forEach { (key, title) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(title, Modifier.weight(1f))
                                Text(android.text.format.Formatter.formatFileSize(context, value.categories[key] ?: 0L), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (value.missing > 0) Text("${value.missing} referenced files are missing. Backup/full copy will report missing media rather than silently omit it.", color = MaterialTheme.colorScheme.error)
                    }
                    Text("Read-only review of referenced media and attachment sidecars. Text, database overhead, cache and abandoned drafts are not included. No files are removed.", fontSize = 13.sp, color = Color(0xFF526171))
                }
                StorageSection.EXPORT -> {
                    Text("Choose a format, then choose where to save it. Nothing changes in your project.", color = Color(0xFF526171))
                    ProjectExportFormat.entries.forEach { format ->
                        StorageOption(when (format) { ProjectExportFormat.PDF -> "PDF"; ProjectExportFormat.MARKDOWN -> "Markdown"; else -> "Backup archive (.zip)" },
                            when (format) { ProjectExportFormat.PDF -> "Readable text summary, paginated"; ProjectExportFormat.MARKDOWN -> "Editable text, hierarchy and knowledge";
                                else -> "Structured data, recorded history and original media · export only" }, !busy && pendingPath == null) {
                            scope.launch {
                                busy = true; message = null
                                try {
                                    val file = withContext(Dispatchers.IO) { tools.export(VirlinGraph.repository.projectSnapshot(project.id), format) }
                                    pendingPath = file.path; pendingFormat = format
                                    val safeName = project.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(60).ifBlank { "Project" }
                                    saveDocument.launch(android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                                        addCategory(android.content.Intent.CATEGORY_OPENABLE); type = format.mime
                                        putExtra(android.content.Intent.EXTRA_TITLE, "$safeName.${format.extension}")
                                    })
                                } catch (error: Exception) { pendingPath?.let(::File)?.delete(); pendingPath = null; pendingFormat = null; message = error.message ?: "Could not prepare export." }
                                finally { busy = false }
                            }
                        }
                    }
                    Text("PDF and Markdown are text summaries, not media bundles. ZIP includes managed files and a versioned manifest. Restore/import is not available. Files may contain private information; save them somewhere trusted.", fontSize = 13.sp, color = Color(0xFF526171))
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
                                tools.duplicate(VirlinGraph.repository.projectSnapshot(project.id), name, mode)
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
