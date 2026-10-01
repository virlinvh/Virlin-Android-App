package com.virlin.app.ui.map.preview

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.mappreview.MapPreviewImageStore
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.mappreview.MapNodePreview
import com.virlin.app.domain.mappreview.MapPreviewImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The host-side state for mind-map node previews.
 *
 * It owns the loaded previews, which node is expanded, and the editor draft. The draft deliberately
 * lives here rather than in the map, so typing a description never recomposes the graph.
 */
class MapPreviewHostState(
    private val scope: CoroutineScope,
    private val actions: com.virlin.app.domain.action.VirlinActions = VirlinGraph.actions,
) {
    var previews by mutableStateOf<Map<String, MapNodePreview>>(emptyMap())
        private set

    /** Only the SELECTED node can show its preview, so at most one id is ever expanded. */
    var expandedId by mutableStateOf<String?>(null)
        private set

    var editing by mutableStateOf<String?>(null)
        private set

    var draft by mutableStateOf<MapPreviewDraft?>(null)

    var confirmingClear by mutableStateOf(false)

    fun refresh() {
        scope.launch { previews = actions.loadAllMapNodePreviews() }
    }

    fun owners(): Set<String> = previews.keys

    /** The caret only reveals or conceals saved content; it never enters editing. */
    fun toggleExpanded(ownerId: String) {
        val has = previews[ownerId]?.isEmpty == false
        expandedId = if (!has) null else if (expandedId == ownerId) null else ownerId
        if (has) scope.launch { actions.setMapNodePreviewExpanded(ownerId, expandedId == ownerId) }
    }

    /** Deselecting a node collapses its preview: a resting node shows its title only. */
    fun onSelectionChanged(selectedId: String?) {
        if (expandedId != null && expandedId != selectedId) expandedId = null
    }

    fun startEditing(ownerId: String) {
        editing = ownerId
        draft = MapPreviewDraft.of(ownerId, previews[ownerId])
    }

    /**
     * Cancel discards the draft and deletes exactly the files imported during this session, so a
     * cancelled edit leaves neither a changed record nor stranded bytes.
     */
    fun cancelEditing(context: android.content.Context) {
        val current = draft
        editing = null
        draft = null
        confirmingClear = false
        if (current == null) return
        scope.launch {
            withContext(Dispatchers.IO) {
                current.addedImages.forEach { MapPreviewImageStore.delete(context, it.relativePath) }
            }
        }
    }

    fun save(context: android.content.Context, onDone: (String?) -> Unit) {
        val current = draft ?: return
        draft = current.copy(saving = true)
        scope.launch {
            val result = actions.saveMapNodePreview(
                ownerId = current.ownerId,
                description = current.description,
                images = current.images,
                expanded = expandedId == current.ownerId,
            )
            when (result) {
                is ActionResult.Success -> {
                    // Only once the record is committed are the superseded bytes removed, so a
                    // failed save can never leave the preview pointing at deleted files.
                    withContext(Dispatchers.IO) {
                        current.removedPaths.forEach { MapPreviewImageStore.delete(context, it) }
                        if (result.value == null) {
                            MapPreviewImageStore.deleteOwner(context, current.ownerId)
                        }
                    }
                    editing = null
                    draft = null
                    confirmingClear = false
                    refresh()
                    if (result.value == null) expandedId = null
                    onDone(null)
                }
                else -> {
                    draft = current.copy(saving = false)
                    onDone("Could not save this preview")
                }
            }
        }
    }

    /** Imports one picked image into managed storage and adds it to the draft. */
    fun addImage(context: android.content.Context, uri: Uri, replace: Boolean, onError: (String) -> Unit) {
        val current = draft ?: return
        if (!replace && !current.canAddImage) return
        scope.launch {
            val imageId = VirlinGraph.ids.newId("mpi")
            val imported = withContext(Dispatchers.IO) {
                MapPreviewImageStore.importFromUri(context, uri, current.ownerId, imageId)
            }
            if (imported == null) {
                onError("That file could not be read as an image")
                return@launch
            }
            val image = MapPreviewImage(
                id = imageId,
                relativePath = imported.relativePath,
                width = imported.width,
                height = imported.height,
                sizeBytes = imported.sizeBytes,
            )
            val base = draft ?: return@launch
            draft = if (replace) base.replaceActive(image) else base.addImage(image)
        }
    }

    /** The extra height an expanded node needs, in the map's world units. */
    fun expandedExtraHeight(): Float {
        val preview = expandedId?.let { previews[it] } ?: return 0f
        var extra = 0f
        if (!preview.description.isBlank) extra += 34f
        if (preview.images.isNotEmpty()) extra += 104f
        return extra
    }
}

@Composable
fun rememberMapPreviewHostState(): MapPreviewHostState {
    val scope = rememberCoroutineScope()
    return remember { MapPreviewHostState(scope).apply { refresh() } }
}

/**
 * Confirmation for the icon-only clear control.
 *
 * It is explicit about what it does NOT do, because "clear" sits near genuinely destructive words:
 * it empties this preview only and never touches the task, its Page or its attachments.
 */
@Composable
fun ClearPreviewDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear this preview?") },
        text = {
            Text(
                "This removes the preview's description and images from this node. " +
                    "The task itself, its Page and its attachments are not affected."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Clear preview") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}

/**
 * The expanded node card, drawn at the rect the map reports.
 *
 * It replaces the Canvas-drawn node for that one node, so the title and the preview share one
 * surface and cannot drift apart.
 */
@Composable
fun ExpandedNodeCard(
    rect: androidx.compose.ui.geometry.Rect,
    title: String,
    preview: MapNodePreview,
    density: androidx.compose.ui.unit.Density,
    onCollapse: () -> Unit,
    onEdit: () -> Unit,
) {
    with(density) {
        Box(
            Modifier
                .padding(start = rect.left.toDp(), top = rect.top.toDp())
                .width(rect.width.toDp())
        ) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF0E7A54)),
                shadowElevation = 3.dp,
            ) {
                Column(Modifier.padding(8.dp)) {
                    // The caret and pencil live IN the card once it is expanded: the Canvas no
                    // longer draws this node, so controls painted there would be invisible and
                    // unhittable. As real composables they also get 48dp targets and semantics.
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .testTag(MapPreviewCollapseTag)
                                .semantics {
                                    contentDescription = "Collapse preview for " + title
                                    stateDescription = "Expanded"
                                }
                                .androidxClickable(onCollapse),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Color(0xFF637283),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            title,
                            color = Color(0xFF192229),
                            fontSize = 12.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .size(32.dp)
                                .testTag(MapPreviewEditFromCardTag)
                                .semantics { contentDescription = "Edit preview for " + title }
                                .androidxClickable(onEdit),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Outlined.Edit,
                                contentDescription = null,
                                tint = Color(0xFF637283).copy(alpha = .65f),
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 2.dp))
                    MapNodeExpandedPreview(preview.description, preview.images)
                }
            }
        }
    }
}

const val MapPreviewCollapseTag = "map_preview_collapse"
const val MapPreviewEditFromCardTag = "map_preview_edit_card"

private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
