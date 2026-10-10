package com.virlin.app.ui.hierarchy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.ui.map.MapAddKind
import com.virlin.app.ui.map.MapAddPalette
import kotlinx.coroutines.*

/** Project-owned capture: never silently creates a task or discards the selected project. */
@Composable
internal fun ProjectKnowledgeAdd(projectId: String, title: String, nav: NavController, onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        val owner = Any()
        KnowledgeOrbVisibility.enter(owner)
        onDispose { KnowledgeOrbVisibility.leave(owner) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = rememberProjectPalette(projectId)
    var choice by rememberSaveable(projectId) { mutableStateOf<MapAddKind?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) busy = false
        if (uri != null) scope.launch {
            busy = true
            val id = VirlinGraph.ids.newId("att")
            var saved = false
            try {
                val file = withContext(Dispatchers.IO) {
                    com.virlin.app.data.attachment.AttachmentFileStore.importFromUri(context, uri, id)
                }
                require(file.sizeBytes > 0) { "The file is empty." }
                withContext(NonCancellable) {
                    val result = VirlinGraph.actions.createAttachment(file.displayName, file.mimeType,
                        file.sizeBytes, file.relativePath, file.kind,
                        context = CaptureContext(projectId = projectId), attachmentId = id)
                    if (result !is ActionResult.Success) error("Could not save this file to the project.")
                    saved = true
                }
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Import failed. Please try again." }
            finally {
                if (!saved) withContext(NonCancellable + Dispatchers.IO) {
                    com.virlin.app.data.attachment.AttachmentFileStore.deleteAttachmentTree(context, id)
                }
                busy = false
            }
        }
    }
    CompositionLocalProvider(LocalProjectPalette provides palette) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
            primary = palette?.let { androidx.compose.ui.graphics.Color(it.secondary) } ?: MaterialTheme.colorScheme.primary)) {
        if (choice == null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .36f))) {
            androidx.activity.compose.BackHandler { if (!busy) onDismiss() }
            Box(Modifier.matchParentSize().clickable { if (!busy) onDismiss() })
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = androidx.compose.ui.graphics.Color.White, shadowElevation = 8.dp) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                MapAddPalette(title, knowledgeMode = true, onDismiss = { if (!busy) onDismiss() }, onChoose = { kind ->
                    if (!busy) {
                        error = null
                        when (kind) {
                            MapAddKind.AUDIO -> choice = kind
                            else -> {
                                nav.navigate(projectKnowledgeEditorRoute(kind, projectId))
                                onDismiss()
                            }
                        }
                    }
                })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            }
            }
        }
        choice?.let { kind ->
            if (kind == MapAddKind.AUDIO) {
                AlertDialog(onDismissRequest = { if (!busy) choice = null }, title = { Text("Add audio") },
                    text = { Column {
                        Text("Save audio in $title. Organize or attach it later.")
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    } },
                    confirmButton = { TextButton(enabled = !busy, onClick = {
                        nav.navigate("voice_editor/new/project/${android.net.Uri.encode(projectId)}")
                        onDismiss()
                    }) { Text("Record audio") } },
                    dismissButton = { Row {
                        TextButton(enabled = !busy, onClick = { choice = null }) { Text("Back") }
                        TextButton(enabled = !busy, onClick = {
                            busy = true; error = null
                            try { importer.launch(arrayOf("audio/*")) }
                            catch (failure: Exception) { busy = false; error = "Could not open the file picker." }
                        }) { Text("Import audio") }
                    } })
            }
        }
        }
    }
}

internal fun projectKnowledgeBlocks(body: String, checklist: Boolean): List<NoteBlock> =
    if (checklist) body.lines().filter(String::isNotBlank).map {
        NoteBlock(java.util.UUID.randomUUID().toString(), NoteBlockType.CHECKBOX, plainText = it)
    } else listOf(NoteBlock(java.util.UUID.randomUUID().toString(), NoteBlockType.TEXT, plainText = body))

internal fun projectKnowledgeEditorRoute(kind: MapAddKind, projectId: String): String {
    val owner = android.net.Uri.encode(projectId)
    return when (kind) {
        MapAddKind.NOTE -> "knowledge_note/new/$owner"
        MapAddKind.TODO -> "knowledge_todo/new/$owner"
        MapAddKind.TASK -> "prompt_editor/new/project/$owner"
        MapAddKind.LINK -> "link_editor/new/project/$owner"
        MapAddKind.IMAGE -> "image_workspace/project/$owner"
        MapAddKind.PDF -> "pdf_workspace/new/project/$owner"
        MapAddKind.ATTACHMENT -> "attachment_workspace/project/$owner"
        else -> error("This option uses its file picker or audio editor, not a text route")
    }
}
