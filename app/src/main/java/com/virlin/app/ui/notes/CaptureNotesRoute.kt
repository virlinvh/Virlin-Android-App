package com.virlin.app.ui.notes

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.notedoc.CaptureNoteBridge

fun captureNotesRoute(captureId: String) = "capture_note/${android.net.Uri.encode(captureId)}"

/** Same Notes UI as the mind map, but one independently owned document per Knowledge entry. */
@Composable
fun NewKnowledgeNotesRoute(nav: NavController, projectId: String) {
    val id = rememberSaveable(projectId) { VirlinGraph.ids.newId("cap") }
    NotesRoute(nav, CaptureNoteBridge.draftOwner(projectId, id), "New note")
}

@Composable
fun NewWorkstreamNotesRoute(nav: NavController, projectId: String, workStreamId: String) {
    val id = rememberSaveable(projectId, workStreamId) { VirlinGraph.ids.newId("cap") }
    NotesRoute(nav, CaptureNoteBridge.draftWorkstreamOwner(projectId, workStreamId, id), "New note")
}

@Composable
fun CaptureNotesRoute(nav: NavController, captureId: String) {
    NotesRoute(nav, CaptureNoteBridge.owner(captureId), "Note")
}
