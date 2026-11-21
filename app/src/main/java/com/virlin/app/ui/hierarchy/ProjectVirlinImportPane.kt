package com.virlin.app.ui.hierarchy

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.project.VirlinImportPreview
import com.virlin.app.data.project.VirlinProjectFile
import com.virlin.app.data.project.VirlinProjectImporter
import com.virlin.app.data.project.VirlinTiming
import com.virlin.app.domain.VirlinGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

private fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"
private fun nothingAdded(message: String) = if ("Nothing was" in message) message else "$message Nothing was added."

/**
 * The Add projects dialog's Import mode: choose a `.virlin` file, see it checked and counted,
 * then add it as a new project. Nothing is written until Import project is pressed.
 */
@Composable
internal fun ColumnScope.ProjectVirlinImportPane(
    accent: Color, wash: Color, ink: Color, muted: Color, line: Color,
    actionColor: Color, actionInk: Color, fieldColors: TextFieldColors,
    busy: Boolean, onBusyChanged: (Boolean) -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = VirlinGraph.repository
    val importer = remember(repository) { VirlinProjectImporter(context.applicationContext, repository) }
    var file by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf<VirlinImportPreview?>(null) }
    var name by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun size(bytes: Long) = android.text.format.Formatter.formatFileSize(context, bytes)
    DisposableEffect(Unit) { onDispose { file?.delete() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            checking = true; error = null; preview = null
            file?.delete(); file = null
            var copy: File? = null
            try {
                copy = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "project-imports").apply { check(isDirectory || mkdirs()) }
                    dir.listFiles()?.forEach { it.delete() }
                    File(dir, "${UUID.randomUUID()}.${VirlinProjectFile.EXTENSION}").also { target ->
                        val input = context.contentResolver.openInputStream(uri) ?: error("This file could not be opened.")
                        input.use { source -> target.outputStream().use { source.copyTo(it) } }
                    }
                }
                val checked = importer.inspect(copy)
                val taken = repository.projects.value.map { it.title.trim().lowercase() }.toSet()
                val title = checked.summary.projectTitle.ifBlank { "Imported project" }
                name = (if (title.trim().lowercase() in taken) "$title (imported)" else title).take(60)
                file = copy; preview = checked
            } catch (e: CancellationException) { copy?.delete(); throw e
            } catch (e: Exception) {
                copy?.delete()
                error = nothingAdded(e.message?.takeIf { it.isNotBlank() } ?: "This file could not be read.")
            }
            checking = false
        }
    }
    fun choose() { if (!busy && !checking) picker.launch(arrayOf("*/*")) }

    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("project_import_form"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val checked = preview
        when {
            checking -> Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Checking every part of the file…", fontSize = 13.sp, color = muted)
            }
            checked == null -> {
                Text("Bring in a whole project from a .virlin file exported by Virlin. The file is checked before anything is added.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = muted)
                Surface(onClick = ::choose, enabled = !busy, shape = RoundedCornerShape(14.dp), color = wash,
                    border = BorderStroke(1.dp, accent.copy(alpha = .35f)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("project_import_choose")) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.UploadFile, null, tint = accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Choose a .virlin file", fontSize = 14.sp, color = ink)
                            Text("From Project settings › Storage & export", fontSize = 12.sp, color = muted)
                        }
                    }
                }
                error?.let { message ->
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFFDECEC), modifier = Modifier.fillMaxWidth().testTag("project_import_error")) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.ErrorOutline, null, tint = Color(0xFFB3261E), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(message, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFF8C1D18))
                        }
                    }
                }
            }
            else -> {
                Surface(shape = RoundedCornerShape(14.dp), color = wash, modifier = Modifier.fillMaxWidth().testTag("project_import_preview")) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Inventory2, null, tint = accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(checked.summary.projectTitle.ifBlank { "Project" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Verified, null, tint = accent, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Checked · every part is intact", fontSize = 12.sp, color = muted)
                            }
                        }
                        Surface(shape = RoundedCornerShape(50), color = Color.White) {
                            Text(".virlin", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp, color = accent)
                        }
                    }
                }
                Text("Project name", fontSize = 12.sp, color = muted)
                OutlinedTextField(name, { name = it.take(60) }, singleLine = true, shape = RoundedCornerShape(12.dp),
                    colors = fieldColors, enabled = !busy, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth().testTag("project_import_name"))
                Text("WHAT COMES IN", fontSize = 11.sp, letterSpacing = 1.sp, color = muted)
                Surface(shape = RoundedCornerShape(12.dp), color = Color.White, border = BorderStroke(1.dp, line)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        val rows = listOf(
                            "Workstreams" to checked.workstreams.toString(),
                            "Tasks" to checked.tasks.toString(),
                            "Steps" to checked.steps.toString(),
                            "Notes" to checked.notes.toString(),
                            "Captures" to checked.captures.toString(),
                            "Flashcards" to checked.flashcards.toString(),
                            "Mind-map previews" to checked.mapPreviews.toString(),
                            "Media files" to "${checked.summary.mediaFiles} · ${size(checked.summary.mediaBytes)}",
                            "Focus sessions" to checked.focusSessions.toString(),
                            "Activity events" to checked.events.toString(),
                        ).filterIndexed { index, (_, value) -> index < 2 || value != "0" && !value.startsWith("0 ·") }
                        rows.forEachIndexed { index, (label, value) ->
                            if (index > 0) HorizontalDivider(color = line.copy(alpha = .6f))
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Text(label, Modifier.weight(1f), fontSize = 13.sp, color = ink)
                                Text(value, fontSize = 13.sp, color = muted)
                            }
                        }
                    }
                }
                Text("TIME SPENT", fontSize = 11.sp, letterSpacing = 1.sp, color = muted)
                Text(if (checked.summary.timing == VirlinTiming.KEEP)
                        "Kept as recorded. Running clocks continue from the time they showed when exported."
                    else "Starts from zero. This file was exported without time spent.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = ink)
                checked.exportedAt?.let { at ->
                    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .format(at.atZone(ZoneId.systemDefault()))
                    Text("Exported $date${checked.appVersion?.let { " · Virlin $it" } ?: ""}", fontSize = 12.sp, color = muted)
                }
                if (checked.leavesFocus > 0) Text(
                    "${plural(checked.leavesFocus, "item")} ${if (checked.leavesFocus == 1) "was" else "were"} in Focus. " +
                        "${if (checked.leavesFocus == 1) "It arrives" else "They arrive"} as Ready, so your current Focus stays as it is.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = muted)
                if (checked.summary.missing.isNotEmpty()) Text(
                    "${plural(checked.summary.missing.size, "file")} ${if (checked.summary.missing.size == 1) "was" else "were"} already missing " +
                        "when this was exported, so ${if (checked.summary.missing.size == 1) "it stays" else "they stay"} missing.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFF8C5A00), modifier = Modifier.testTag("project_import_missing"))
                Text("Adds a new project. Your existing projects stay unchanged.", fontSize = 12.sp, color = muted)
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("project_import_error")) }
            }
        }
    }
    HorizontalDivider(color = line)
    Row(Modifier.fillMaxWidth().testTag("bulk_creation_actions"),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (preview != null) TextButton(enabled = !busy, onClick = ::choose) { Text("Choose another") }
        else TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        Button(shape = RoundedCornerShape(10.dp), modifier = Modifier.testTag("project_import_confirm"),
            colors = ButtonDefaults.buttonColors(containerColor = actionColor, contentColor = actionInk),
            enabled = !busy && !checking && (preview == null || name.isNotBlank()), onClick = {
                val source = file
                if (preview == null || source == null) choose()
                else scope.launch {
                    onBusyChanged(true); error = null
                    try {
                        val result = importer.import(source, name)
                        source.delete(); file = null
                        Toast.makeText(context, (listOf("Imported “${result.project.title}”.") + result.warnings).joinToString(" "),
                            Toast.LENGTH_LONG).show()
                        onBusyChanged(false)
                        onDismiss()
                    } catch (e: CancellationException) { onBusyChanged(false); throw e
                    } catch (e: Exception) {
                        error = nothingAdded(e.message?.takeIf { it.isNotBlank() } ?: "Import failed.")
                        onBusyChanged(false)
                    }
                }
            }) { Text(when { busy -> "Importing…"; preview == null -> "Choose file"; else -> "Import project" }) }
    }
}
