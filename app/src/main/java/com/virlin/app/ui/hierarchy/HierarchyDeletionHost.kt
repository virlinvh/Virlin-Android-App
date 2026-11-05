package com.virlin.app.ui.hierarchy

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult

internal data class DeletionTarget(val id: String, val title: String, val kind: String)

@Composable
internal fun HierarchyDeletionHost(target: DeletionTarget, onDismiss: () -> Unit, onRemoved: () -> Unit) {
    var busy by remember(target.id) { mutableStateOf(false) }
    var error by remember(target.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    DeleteHierarchyDialog(target.title, target.kind, busy, error, onDismiss) {
        if (!busy) {
            busy = true
            VirlinGraph.deleteHierarchy(target.id, target.kind) { result ->
                busy = false
                val absent = if (target.kind == "task") VirlinGraph.repository.tasks.value.none { it.id == target.id }
                    else VirlinGraph.repository.streams.value.none { it.id == target.id }
                if (result is ActionResult.Success || absent) {
                    if (result !is ActionResult.Success) android.widget.Toast.makeText(context, "Item removed. Remaining cleanup is queued for retry.", android.widget.Toast.LENGTH_LONG).show()
                    onRemoved()
                } else error = (result as? ActionResult.Failure)?.cause?.message ?: "Deletion failed. Nothing was deleted."
            }
        }
    }
}
