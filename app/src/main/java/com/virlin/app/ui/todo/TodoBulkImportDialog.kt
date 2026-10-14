package com.virlin.app.ui.todo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.action.BulkHierarchy
import com.virlin.app.ui.components.SafeDialog
import com.virlin.app.ui.hierarchy.ProjectPalette

internal object TodoBulkImport {
    const val sample = """{"version":2,"items":[{"name":"Review requirements"},{"name":"Check final result"}]}"""

    fun parse(json: String): List<String> {
        val nodes = BulkHierarchy.parse(json)
        require(nodes.all { it.children.isEmpty() }) {
            "To-dos are a flat list. Remove children from the JSON."
        }
        return nodes.map { it.name }
    }
}

/** The same bounded JSON/preview flow used by hierarchy import, adapted for flat checkboxes. */
@Composable
internal fun TodoBulkImportDialog(
    palette: ProjectPalette?,
    onDismiss: () -> Unit,
    onImport: (List<String>, (Boolean) -> Unit) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val accent = palette?.let { Color(it.secondary) } ?: Color(0xFF087848)
    SafeDialog(onDismissRequest = { if (!busy) onDismiss() }, scrollContent = false) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("todo_bulk_import_dialog"),
            shape = RoundedCornerShape(24.dp), color = Color.White) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bulk import to-dos", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (preview == null) {
                        Text("Paste the same JSON format used by Bulk Import. Each item becomes a to-do.")
                        OutlinedTextField(input, { input = it; error = null },
                            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                            enabled = !busy, label = { Text("JSON") }, minLines = 4,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        Text("Sample JSON", style = MaterialTheme.typography.labelMedium, color = accent)
                        Text(TodoBulkImport.sample, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        Text("Up to ${BulkHierarchy.MAX_ITEMS} items. Preview before adding.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("${preview!!.size} new to-dos · existing items stay unchanged")
                        preview!!.forEachIndexed { index, name ->
                            Text("${index + 1}. $name", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { if (preview != null) preview = null else onDismiss() }, enabled = !busy) {
                        Text(if (preview == null) "Cancel" else "Edit input", color = accent)
                    }
                    Button(onClick = {
                        if (preview == null) {
                            try { preview = TodoBulkImport.parse(input); error = null }
                            catch (e: Exception) { error = e.message ?: "Invalid JSON." }
                        } else {
                            busy = true
                            onImport(preview!!) { success ->
                                busy = false
                                if (success) onDismiss() else error = "Import failed. Nothing was added."
                            }
                        }
                    }, enabled = !busy && (preview != null || input.isNotBlank()),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                        Text(if (busy) "Adding…" else if (preview == null) "Preview" else "Add ${preview!!.size} items")
                    }
                }
            }
        }
    }
}
