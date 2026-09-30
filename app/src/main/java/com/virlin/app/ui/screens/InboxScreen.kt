package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.ui.agent.capture.AgentCaptureViewModel
import com.virlin.app.ui.agent.capture.CaptureContextChoice
import com.virlin.app.domain.action.CaptureTaskTarget
import com.virlin.app.ui.agent.capture.CaptureFilter
import com.virlin.app.ui.agent.capture.CaptureInbox
import com.virlin.app.ui.agent.capture.ContextPicker

const val InboxScreenTag = "inbox_screen"

/**
 * Root INBOX destination: view and manage previously captured items.
 *
 * The presentation is the approved [VirlinInboxScreen]; every operation behind it is the one the
 * app already had. Rows, counts, filters and ordering come from [AgentCaptureViewModel] — this
 * screen keeps no copy of them — and opening an item still hands off to the same per-type editor
 * routes. When an item is selected, the existing [CaptureInbox] detail is shown unchanged, so
 * organize, attach, convert, archive and restore keep working exactly as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    vm: AgentCaptureViewModel,
    onOpenTextNote: ((captureId: String?) -> Unit)? = null,
    onOpenPrompt: ((captureId: String?) -> Unit)? = null,
    onOpenLink: ((captureId: String?) -> Unit)? = null,
    onOpenFile: ((captureId: String?) -> Unit)? = null,
    onOpenVoice: ((captureId: String?) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    // The Inbox is a pure-white surface, and the window's own status-bar colour is the app's
    // warm off-white. Own it for this screen's lifetime only and put it back on the way out,
    // the same way the fullscreen focus clock does.
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        @Suppress("DEPRECATION")
        val previous = window?.statusBarColor
        @Suppress("DEPRECATION")
        window?.statusBarColor = android.graphics.Color.WHITE
        onDispose {
            @Suppress("DEPRECATION")
            if (previous != null) window.statusBarColor = previous
        }
    }

    val state by vm.state.collectAsState()
    val filter = state.form.filter

    // An item is open: the existing detail owns the screen, with its own organize/archive UI.
    if (state.selected != null) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.White)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).testTag(InboxScreenTag)
        ) {
            Spacer(Modifier.height(16.dp))
            CaptureInbox(
                vm, onOpenTextNote = onOpenTextNote, onOpenPrompt = onOpenPrompt,
                onOpenLink = onOpenLink, onOpenFile = onOpenFile, onOpenVoice = onOpenVoice
            )
            Spacer(Modifier.height(96.dp))
        }
        return
    }

    val tab = when (filter) {
        CaptureFilter.INBOX -> InboxTab.INBOX
        CaptureFilter.ORGANIZED -> InboxTab.ORGANIZED
        CaptureFilter.ARCHIVED -> InboxTab.ARCHIVED
    }
    val items = remember(state.rows, tab) {
        state.rows.map { row ->
            InboxUiItem(
                id = row.item.id,
                title = row.preview,
                kind = com.virlin.app.ui.agent.capture.CapturePresentation.typeLabel(row.item.type),
                timeLabel = row.age,
                tab = tab,
                icon = captureTypeIcon(row.item.type),
                contextLabel = row.contextLabel,
            )
        }
    }

    var menuFor by remember { mutableStateOf<String?>(null) }
    var bulkFor by remember { mutableStateOf<Set<String>?>(null) }

    /** The same per-type routing the capture list has always used. */
    fun open(id: String) {
        val item = state.rows.firstOrNull { it.item.id == id }?.item ?: return
        when {
            item.type == CaptureType.NOTE && onOpenTextNote != null -> onOpenTextNote(item.id)
            item.type == CaptureType.PROMPT && onOpenPrompt != null -> onOpenPrompt(item.id)
            item.type == CaptureType.LINK && onOpenLink != null -> onOpenLink(item.id)
            item.type == CaptureType.FILE && onOpenFile != null -> onOpenFile(item.id)
            item.type == CaptureType.VOICE && onOpenVoice != null -> onOpenVoice(item.id)
            else -> vm.select(item.id)
        }
    }

    VirlinInboxScreen(
        items = items,
        inboxCount = state.inboxCount,
        tab = tab,
        onTabChange = {
            vm.setFilter(
                when (it) {
                    InboxTab.INBOX -> CaptureFilter.INBOX
                    InboxTab.ORGANIZED -> CaptureFilter.ORGANIZED
                    InboxTab.ARCHIVED -> CaptureFilter.ARCHIVED
                }
            )
        },
        onBack = { onBack?.invoke() },
        onOpen = ::open,
        // Organize opens the item's existing organize panel rather than a new one.
        onOrganize = { id -> vm.select(id); vm.toggleOrganize() },
        onMenu = { menuFor = it },
        // Capture goes to the app's own editors: a new note, or the voice recorder.
        onCaptureText = { onOpenTextNote?.invoke(null) },
        onCaptureVoice = onOpenVoice?.let { open -> { open(null) } },
        onBulkOrganize = { bulkFor = it },
    )

    menuFor?.let { id ->
        val row = state.rows.firstOrNull { it.item.id == id }
        if (row == null) { menuFor = null; return@let }
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(row.preview, maxLines = 2) },
            text = {
                Column {
                    MenuRow("Open") { menuFor = null; open(id) }
                    if (row.item.status == CaptureStatus.INBOX) {
                        MenuRow("Organize") { menuFor = null; vm.select(id); vm.toggleOrganize() }
                        MenuRow("Archive") { menuFor = null; vm.archive(id) }
                    }
                    if (row.item.status == CaptureStatus.ARCHIVED) {
                        MenuRow("Restore to Inbox") { menuFor = null; vm.restore(id) }
                    }
                    // Virlin archives rather than deletes; there is no delete action to offer.
                    Text(
                        "Virlin archives rather than deletes, so nothing here is destroyed.",
                        fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } }
        )
    }

    // Bulk organize reuses the SAME context picker the single-item organize panel uses, then
    // applies the existing attach action to each selected capture.
    bulkFor?.let { ids ->
        ModalBottomSheet(onDismissRequest = { bulkFor = null }) {
            // The sheet holds two full pickers, so it scrolls on its own; the page's scroll is
            // not behind it.
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
            ) {
                Text("Organize ${ids.size} ${if (ids.size == 1) "item" else "items"}", fontSize = 18.sp)
                // The same two operations, and the same distinction, as the single-item panel:
                // attaching records where a capture belongs; only converting organizes it.
                Spacer(Modifier.height(14.dp))
                Text("ATTACH TO · stays a capture", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                ContextPicker(
                    state = state,
                    streamTag = { "bulk_attach_stream_$it" }, projectTag = { "bulk_attach_project_$it" },
                    onStream = { ws ->
                        ids.forEach {
                            vm.attach(it, CaptureContextChoice(projectId = ws.projectId, workStreamId = ws.id))
                        }
                        bulkFor = null
                    },
                    onProject = { p ->
                        ids.forEach { vm.attach(it, CaptureContextChoice(projectId = p.id)) }
                        bulkFor = null
                    }
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "CONVERT TO TASK · becomes a real task, capture organized",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                ContextPicker(
                    state = state,
                    streamTag = { "bulk_convert_stream_$it" }, projectTag = { "bulk_convert_project_$it" },
                    onStream = { ws ->
                        ids.forEach { vm.convertToTask(it, CaptureTaskTarget(workStreamId = ws.id)) }
                        bulkFor = null
                    },
                    onProject = { p ->
                        ids.forEach { vm.convertToTask(it, CaptureTaskTarget(projectId = p.id)) }
                        bulkFor = null
                    },
                    projectsHeader = "STANDALONE IN PROJECT"
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    )
}
