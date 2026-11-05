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
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.action.BulkHierarchy
import com.virlin.app.ui.components.SafeDialog
import com.virlin.app.ui.hierarchy.ProjectPalette

internal object TodoBulkImport {
    const val sample = """Review requirements
- Check the first version
3. Verify the final result"""
    private val marker = Regex("""^(?:[-*•]\s+|(?:\d+[.)])\s+)""")
    private val checkbox = Regex("^(?:\\[(?: |x|X)]|[☐☑✓])\\s+")

    fun parse(text: String): List<String> {
        require(!text.trimStart().startsWith("{")) { "Paste one to-do per line, not JSON." }
        val names = text.lineSequence().map { raw ->
            raw.trim().replaceFirst(marker, "").replaceFirst(checkbox, "").trim()
        }.filter { it.isNotEmpty() }.toList()
        require(names.isNotEmpty()) { "Paste at least one to-do." }
        require(names.size <= BulkHierarchy.MAX_ITEMS) { "You can add at most ${BulkHierarchy.MAX_ITEMS} to-dos at once." }
        require(names.all { it.length <= 200 }) { "Each to-do must be 200 characters or fewer." }
        return names
    }
}

/** Plain-text checklist import; all rows are previewed before one atomic bulk write. */
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
                        Text("Paste one to-do per line. Bullets, numbers and checkboxes are optional; each line becomes one item in this task's list.")
                        OutlinedTextField(input, { input = it; error = null },
                            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                            enabled = !busy, label = { Text("Paste to-dos") }, minLines = 4)
                        Text("Example", style = MaterialTheme.typography.labelMedium, color = accent)
                        Text(TodoBulkImport.sample, style = MaterialTheme.typography.bodySmall)
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
                            catch (e: Exception) { error = e.message ?: "Could not read that list." }
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
