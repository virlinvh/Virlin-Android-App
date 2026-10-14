package com.virlin.app.ui.hierarchy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
internal fun ProjectKnowledgeAdd(projectId: String, title: String, nav: NavController,
    workStreamId: String? = null, onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        val owner = Any()
        KnowledgeOrbVisibility.enter(owner)
        onDispose { KnowledgeOrbVisibility.leave(owner) }
    }
    val palette = rememberProjectPalette(projectId)
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sheetVisible by remember { mutableStateOf(true) }
    val entrance = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { entrance.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
    val dismissSheet = { if (!busy) sheetVisible = false }
    LaunchedEffect(sheetVisible) {
        if (!sheetVisible) {
            kotlinx.coroutines.delay(230)
            onDismiss()
        }
    }
    CompositionLocalProvider(LocalProjectPalette provides palette) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
            primary = palette?.let { androidx.compose.ui.graphics.Color(it.secondary) } ?: MaterialTheme.colorScheme.primary)) {
        Box(Modifier.fillMaxSize()) {
            val scrimAlpha by androidx.compose.animation.core.animateFloatAsState(
                if (sheetVisible) .36f else 0f, animationSpec = tween(230), label = "knowledge_scrim")
            androidx.activity.compose.BackHandler { dismissSheet() }
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = scrimAlpha))
                .clickable { dismissSheet() })
            AnimatedVisibility(visible = sheetVisible,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .graphicsLayer { translationY = (1f - entrance.value) * size.height },
                enter = fadeIn(tween(220)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) + fadeOut(tween(180))) {
            Column {
                MapAddPalette(title, knowledgeMode = true, onDismiss = dismissSheet,
                    allowedKinds = if (workStreamId == null) MapAddKind.entries.toSet()
                        else setOf(MapAddKind.TODO, MapAddKind.NOTE, MapAddKind.TASK, MapAddKind.LINK, MapAddKind.AUDIO),
                    onChoose = { kind ->
                    if (!busy) {
                        error = null
                        when (kind) {
                            MapAddKind.AUDIO -> {
                                nav.navigate(if (workStreamId == null) "voice_editor/new/project/${android.net.Uri.encode(projectId)}"
                                    else "voice_editor/new/workstream/${android.net.Uri.encode(workStreamId)}")
                                onDismiss()
                            }
                            else -> {
                                nav.navigate(if (workStreamId == null) projectKnowledgeEditorRoute(kind, projectId)
                                    else workstreamKnowledgeEditorRoute(kind, workStreamId))
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

internal fun workstreamKnowledgeEditorRoute(kind: MapAddKind, workStreamId: String): String {
    val owner = android.net.Uri.encode(workStreamId)
    return when (kind) {
        MapAddKind.NOTE -> "knowledge_note/new/workstream/$owner"
        MapAddKind.TODO -> "knowledge_todo/new/workstream/$owner"
        MapAddKind.TASK -> "prompt_editor/new/workstream/$owner"
        MapAddKind.LINK -> "link_editor/new/workstream/$owner"
        MapAddKind.AUDIO -> "voice_editor/new/workstream/$owner"
        else -> error("This option is not available for a workstream")
    }
}
