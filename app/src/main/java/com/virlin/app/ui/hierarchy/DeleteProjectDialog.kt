package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.model.Project
import com.virlin.app.ui.components.SafeDialog

/** A separate destructive confirmation; opening settings never deletes anything. */
@Composable
internal fun DeleteProjectDialog(project: Project, busy: Boolean, error: String?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    DeleteHierarchyDialog(project.title, "project", busy, error, onDismiss, onDelete)
}

@Composable
internal fun DeleteHierarchyDialog(title: String, kind: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    var name by remember(title, kind) { mutableStateOf("") }
    SafeDialog(onDismissRequest = { if (!busy) onDismiss() }, scrollContent = true) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Permanently delete $kind?", style = MaterialTheme.typography.titleLarge)
                Text("$title\n\nThis permanently removes this $kind and all its descendants at every nesting level, including owned knowledge, notes, recordings, attachments, activity and recorded time. Active timers and reminders are removed too.\n\nThis cannot be undone. Parents, siblings, other projects and original files outside Virlin are not deleted.")
                Text("Type the $kind name to confirm.")
                OutlinedTextField(name, { name = it }, label = { Text("${kind.replaceFirstChar { it.uppercase() }} name") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    Button(onClick = onDelete, enabled = !busy && name == title,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text(if (busy) "Deleting…" else "Delete permanently")
                    }
                }
            }
        }
    }
}
