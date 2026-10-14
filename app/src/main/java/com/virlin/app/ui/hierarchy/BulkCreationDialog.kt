package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.*
import kotlinx.coroutines.launch

/** V1: project directory -> projects; selected project -> workstreams. */
@Composable
internal fun BulkCreationDialog(title: String, placeholder: String, projectId: String?,
    onDismiss: () -> Unit, onSingle: (String) -> Unit,
    taskDestination: CreateTask? = null, startBulk: Boolean = false, embedded: Boolean = false,
    onBusyChanged: (Boolean) -> Unit = {}) {
    HideProjectOrb()
    var bulk by rememberSaveable { mutableStateOf(startBulk) }
    var input by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var help by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val storedPalette = rememberProjectPalette(projectId)
    val palette = if (embedded) LocalProjectPalette.current ?: storedPalette else storedPalette
    val itemType = if (taskDestination != null) {
        if (taskDestination.parentTaskId != null) "subtasks" else "tasks"
    } else if (projectId == null) "projects" else "workstreams"
    val formatExample = """{"version":2,"items":[{"name":"PHASE 0 Architecture & Baseline"},{"name":"PHASE 1 Application Shell"}]}"""
    val aiPrompt = PhaseImportPrompt.text
    val accent = if (projectId == null) Color(0xFF007B58)
        else palette?.let { Color(it.secondary) } ?: Color(0xFF8B671C)
    val wash = if (projectId == null) Color(0xFFDFF4EB)
        else palette?.let { lerp(Color.White, Color(it.main), .14f) } ?: Color(0xFFFAEDCD)
    // Use the configured main color for filled actions, with contrast-safe text.
    // Secondary ink and a main-color wash keep the smaller controls readable.
    val actionColor = if (projectId != null && palette != null) Color(palette.main) else accent
    val actionInk = readableOn(actionColor)
    val ink = palette?.let { Color(it.text) } ?: Color(0xFF263247)
    val muted = palette?.let { Color(it.muted) } ?: Color(0xFF647089)
    val line = Color(0xFFE0E4EB)
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent,
        unfocusedBorderColor = if (embedded) Color.Transparent else line, focusedTextColor = ink, unfocusedTextColor = ink,
        cursorColor = accent, focusedContainerColor = if (embedded) Color(0xFFF3F4F5) else Color.White,
        unfocusedContainerColor = if (embedded) Color(0xFFF3F4F5) else Color.White)
    val dialogScheme = remember(accent, wash) { lightColorScheme(primary = accent,
        surface = Color.White, onSurface = ink, onSurfaceVariant = muted,
        primaryContainer = wash, onPrimary = readableOn(accent)) }
    val content: @Composable () -> Unit = {
            Surface(modifier = if (embedded) Modifier else Modifier.fillMaxWidth().heightIn(max = 560.dp)
                .testTag("bulk_creation_dialog"),
                shape = RoundedCornerShape(24.dp), color = Color.White) {
                Column(if (embedded) Modifier else Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!embedded) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = wash, shape = RoundedCornerShape(50)) {
                            Icon(Icons.Outlined.Add, null, tint = accent, modifier = Modifier.padding(8.dp).size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Add ${if (bulk) itemType else itemType.removeSuffix("s")}",
                                fontSize = 15.sp, fontWeight = FontWeight.Normal, color = ink)
                            Text(if (taskDestination != null) "In $title" else if (projectId == null) "In Projects" else "In ${title.substringAfter(" in ", title)}",
                                fontSize = 12.sp, color = muted)
                        }
                        IconButton(onClick = { if (!busy) onDismiss() }, enabled = !busy) {
                            Icon(Icons.Outlined.Close, "Close creation popup", tint = muted, modifier = Modifier.size(20.dp))
                        }
                    }
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFF5F6F9), border = BorderStroke(1.dp, line)) {
                        Row(Modifier.fillMaxWidth().padding(2.dp)) {
                            listOf(false to "Single", true to "Bulk").forEach { (mode, label) ->
                                Surface(onClick = { if (!busy) { bulk = mode; preview = null; error = null } },
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                                    color = if (bulk == mode) wash else Color.Transparent) {
                                    Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                                        Text(label, fontSize = 13.sp, color = if (bulk == mode) accent else muted)
                                    }
                                }
                            }
                        }
                    }
                    }
                    // The form alone scrolls; actions stay attached to the card above the IME.
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .testTag("bulk_creation_form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!bulk) {
                        Text(placeholder, fontSize = 12.sp, color = muted)
                        OutlinedTextField(name, { name = it }, placeholder = { Text("Enter a name", fontSize = 14.sp) },
                            singleLine = true, shape = RoundedCornerShape(12.dp), colors = fieldColors,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), modifier = Modifier.fillMaxWidth())
                    }
                    else if (preview == null) {
                        Text("Paste JSON", fontSize = 12.sp, color = muted)
                        OutlinedTextField(input, { input = it; error = null }, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            placeholder = { Text(formatExample, fontSize = 12.sp, color = muted) },
                            shape = RoundedCornerShape(12.dp), colors = fieldColors,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace), minLines = 4,
                            trailingIcon = { IconButton(onClick = { help = true },
                                modifier = Modifier.semantics { contentDescription = "Import format and AI prompt" }) {
                                    Icon(Icons.Outlined.Info, null, tint = muted, modifier = Modifier.size(20.dp))
                                } }, enabled = !busy)
                        Text("Up to ${BulkHierarchy.MAX_ITEMS} total items including children. Preview before adding.", fontSize = 12.sp, color = muted)
                    } else {
                        // Lazy content can finish disposing after Edit input clears preview.
                        // Capture this composition's list instead of reading nullable state later.
                        val previewItems = preview.orEmpty()
                        Text("Preview", fontSize = 15.sp, color = ink)
                        Text("${preview!!.size} new items · hierarchy shown below", fontSize = 12.sp, color = muted)
                        Surface(shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, line)) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                            itemsIndexed(previewItems) { index, item ->
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${index + 1}.", fontSize = 12.sp, color = muted)
                                    Spacer(Modifier.width(10.dp))
                                    Text(item, Modifier.weight(1f), fontSize = 14.sp, color = ink)
                                }
                            }
                        }
                        }
                        Text(if (taskDestination != null) "Appends at end · Execution: ${taskDestination.executionPreference.name.lowercase()}\nExisting items stay unchanged."
                            else "Creates new items only. Existing items stay unchanged.", fontSize = 12.sp, color = muted)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    HorizontalDivider(color = line)
                    Row(Modifier.fillMaxWidth().testTag("bulk_creation_actions"),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = !busy, onClick = { if (preview != null) preview = null else onDismiss() }) { Text(if (preview != null) "Edit input" else "Cancel") }
                        Button(shape = if (embedded) RoundedCornerShape(50) else RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = actionColor, contentColor = actionInk),
                            enabled = !busy && (if (bulk) input.isNotBlank() else name.isNotBlank()), onClick = {
                            if (!bulk) { onSingle(name.trim()); onDismiss() }
                            else if (preview == null) {
                                try { preview = BulkHierarchy.parse(input).flatMap { it.preview(rootType = itemType) }
                                } catch (e: Exception) { error = e.message ?: "Invalid JSON." }
                            } else scope.launch {
                                busy = true; onBusyChanged(true); error = null
                                when (val result = if (taskDestination != null) VirlinGraph.actions.bulkCreateTasks(input, taskDestination)
                                    else VirlinGraph.actions.bulkCreateNames(input, projectId)) {
                                    is ActionResult.Success -> onDismiss()
                                    is ActionResult.Failure -> error = result.cause.message ?: "Import failed. Nothing was added."
                                    else -> error = "Import was refused. Nothing was added."
                                }
                                busy = false; onBusyChanged(false)
                            }
                        }) { Text(if (busy) "Adding…" else if (!bulk) "Create" else if (preview == null) "Preview" else "Add ${preview!!.size} items") }
                    }
                }
            }
    }
    MaterialTheme(colorScheme = dialogScheme) {
        if (embedded) content() else com.virlin.app.ui.components.SafeDialog(
            onDismissRequest = { if (!busy) onDismiss() }, scrollContent = false) { content() }
        if (help) com.virlin.app.ui.components.SafeDialog(onDismissRequest = { help = false }) {
            Surface(shape = RoundedCornerShape(24.dp), color = Color.White) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Import format", Modifier.weight(1f), fontSize = 15.sp, color = ink)
                        IconButton(onClick = { help = false }) { Icon(Icons.Outlined.Close, "Close format help", tint = muted) }
                    }
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Ask your AI tool to convert your information using this format.", fontSize = 13.sp, color = muted)
                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF5F6F9), border = BorderStroke(1.dp, line)) {
                            Text(formatExample, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = ink)
                        }
                        Text("AI instructions", fontSize = 13.sp, color = ink)
                        Text(aiPrompt, fontSize = 12.sp, lineHeight = 18.sp, color = muted)
                    }
                    HorizontalDivider(color = line)
                    Button(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = actionColor, contentColor = actionInk), onClick = {
                clipboard.setText(AnnotatedString(aiPrompt))
                help = false
                    }) { Text("Copy AI prompt") }
                }
            }
        }
    }
}
