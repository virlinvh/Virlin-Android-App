package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.model.Project
import com.virlin.app.ui.components.SafeDialog

/** A separate destructive confirmation; opening settings never deletes anything. */
@Composable
internal fun DeleteProjectDialog(project: Project, busy: Boolean, error: String?, onDismiss: () -> Unit, onDelete: () -> Unit) {
    DeleteHierarchyDialog(project.title, "project", busy, error, onDismiss, onDelete)
}

@Composable
internal fun DeleteHierarchyDialog(title: String, kind: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onDelete: () -> Unit) =
    DeleteByNameDialog(title, kind, "Permanent · includes nested content", busy, error, onDismiss, onDelete)

/** The type-the-name delete warning, with its own consequence line. */
@Composable
internal fun DeleteByNameDialog(title: String, kind: String, warning: String, busy: Boolean, error: String?,
    onDismiss: () -> Unit, onDelete: () -> Unit) {
    var name by remember(title, kind) { mutableStateOf("") }
    SafeDialog(onDismissRequest = { if (!busy) onDismiss() }, scrollContent = true) {
        val danger = MaterialTheme.colorScheme.error
        val ink = MaterialTheme.colorScheme.onSurface
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(danger.copy(alpha = .10f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.DeleteOutline, null, tint = danger,
                            modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Delete $kind?", modifier = Modifier.weight(1f), color = ink,
                        fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
                        Text("Selected $kind", color = muted, fontSize = 12.sp)
                        Text(title, color = ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(warning, color = danger, fontSize = 13.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Type “$title” to confirm", color = ink, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(name, { name = it },
                        placeholder = { Text("${kind.replaceFirstChar { it.uppercase() }} name") },
                        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp))
                }
                error?.let { Text(it, color = danger, fontSize = 13.sp) }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 310.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onDelete, enabled = !busy && name == title,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = danger)) {
                                Text(if (busy) "Deleting…" else "Delete $kind")
                            }
                            OutlinedButton(onClick = onDismiss, enabled = !busy,
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                Text("Keep $kind")
                            }
                        }
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onDismiss, enabled = !busy,
                            shape = RoundedCornerShape(12.dp)) { Text("Keep $kind") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onDelete, enabled = !busy && name == title,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = danger)) {
                            Text(if (busy) "Deleting…" else "Delete $kind")
                        }
                    }
                }
            }
        }
    }
}
