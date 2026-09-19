package com.virlin.app.ui.file

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.ui.theme.VirlinColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

const val FileViewerRoute = "file_viewer"
fun fileViewerRoute(captureId: String?) =
    if (captureId.isNullOrBlank()) "file_viewer" else "file_viewer/$captureId"

const val FileScreenTag = "file_viewer_screen"
const val FileSelectTag = "file_select"
const val FileReplaceTag = "file_replace"
const val FileShareTag = "file_share"
const val FileInboxActionTag = "file_inbox_action"
const val FileSaveStatusTag = "file_save_status"

private val Paper = Color(0xFFFAF8F5)
private val Forest = Color(0xFF162016)
private val Hairline = Color(0x1F162016)
private val Cyan = Color(0xFF0E7490)

@Composable
fun FileViewerScreen(
    navController: NavController,
    captureId: String?,
    vm: FileViewerViewModel = viewModel(
        factory = FileViewerViewModel.factory(captureId, LocalContext.current)
    )
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            vm.importFromUri(uri)
        }
    }

    fun openPicker() {
        picker.launch(
            arrayOf(
                "application/pdf",
                "image/*",
                "video/*",
                "audio/*",
                "text/*",
                "text/csv",
                "application/csv",
                "application/json",
                "application/xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    fun goBack() {
        scope.launch {
            val result = vm.prepareExit()
            if (result.showInboxFeedback) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                delay(550)
                vm.clearInboxBanner()
            }
            if (result.navigate) navController.popBackStack()
        }
    }
    BackHandler { goBack() }

    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(1600)
            vm.clearToast()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag(FileScreenTag)
    ) {
        // Top bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = { goBack() }, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Forest)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("File / Image", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Forest)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (state.saveStatus) {
                            FileSaveStatus.Importing -> "Importing…"
                            FileSaveStatus.Saved -> "Saved ✓"
                            FileSaveStatus.Error -> "Save error"
                            FileSaveStatus.Idle -> if (state.hasMeaningfulContent) "Ready" else "No file"
                        },
                        fontSize = 11.sp,
                        color = if (state.saveStatus == FileSaveStatus.Saved) Cyan else VirlinColors.TextSecondary,
                        modifier = Modifier.testTag(FileSaveStatusTag)
                    )
                    if (state.committedToInbox) {
                        Text("  ·  In Inbox ✓", fontSize = 11.sp, color = Cyan)
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = Forest)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Share") },
                        onClick = {
                            menuOpen = false
                            shareFile(context, state.absolutePath, state.mimeType, state.displayName)
                        },
                        enabled = state.hasMeaningfulContent,
                        modifier = Modifier.testTag(FileShareTag)
                    )
                    DropdownMenuItem(
                        text = { Text("Save to Inbox") },
                        onClick = {
                            menuOpen = false
                            vm.saveToInbox()
                        },
                        modifier = Modifier.testTag(FileInboxActionTag)
                    )
                    DropdownMenuItem(
                        text = { Text("Choose Context") },
                        onClick = {
                            menuOpen = false
                            vm.openContextPicker(true)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = {
                            menuOpen = false
                            vm.removeAttachment { navController.popBackStack() }
                        }
                    )
                }
            }
        }

        // Context line
        Text(
            if (state.contextLabel != null) "Attached to · ${state.contextLabel}" else "Global · Inbox",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (state.contextLabel != null) VirlinColors.Emerald else VirlinColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (state.contextPickerOpen) {
            FileContextPicker(onPick = vm::setContext, onClose = { vm.openContextPicker(false) })
        }

        state.inboxBanner?.let {
            Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Cyan, modifier = Modifier.padding(16.dp, 8.dp))
        }
        state.toast?.let {
            Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.Amber, modifier = Modifier.padding(16.dp, 4.dp))
        }
        state.importError?.let {
            Text(it, fontSize = 12.sp, color = Color(0xFFB45309), modifier = Modifier.padding(16.dp, 4.dp))
        }

        Spacer(Modifier.height(10.dp))

        // Select / Replace
        val selectLabel = if (state.hasMeaningfulContent) "REPLACE FILE" else "SELECT FILE"
        val selectTag = if (state.hasMeaningfulContent) FileReplaceTag else FileSelectTag
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, Hairline, RoundedCornerShape(12.dp))
                .testTag(selectTag)
                .clickable(
                    enabled = state.saveStatus != FileSaveStatus.Importing,
                    onClick = { openPicker() }
                )
                .semantics { contentDescription = selectLabel }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (state.saveStatus == FileSaveStatus.Importing) "IMPORTING…" else selectLabel,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.8.sp,
                color = Cyan
            )
        }

        if (state.hasMeaningfulContent) {
            Spacer(Modifier.height(12.dp))
            Text(
                state.displayName,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = Forest,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text(
                state.metaLine,
                fontSize = 12.sp,
                color = VirlinColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 16.dp)
                    .background(Hairline)
            )
            Spacer(Modifier.height(4.dp))
            UniversalFileViewer(
                absolutePath = state.absolutePath!!,
                kind = state.kind,
                displayName = state.displayName,
                mimeType = state.mimeType,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.loading) "Loading…" else "No file selected.",
                    fontSize = 14.sp,
                    color = VirlinColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun FileContextPicker(onPick: (CaptureContext) -> Unit, onClose: () -> Unit) {
    val streams by VirlinGraph.repository.streams.collectAsState()
    val projects by VirlinGraph.repository.projects.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("Choose context", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Forest)
        Spacer(Modifier.height(8.dp))
        Text(
            "Global (Inbox only)",
            fontSize = 12.sp,
            color = Cyan,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(CaptureContext.None); onClose() }
                .padding(vertical = 6.dp)
        )
        streams.take(8).forEach { ws ->
            Text(
                ws.title,
                fontSize = 12.sp,
                color = Forest,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(CaptureContext(projectId = ws.projectId, workStreamId = ws.id)); onClose() }
                    .padding(vertical = 6.dp)
            )
        }
        projects.take(6).forEach { p ->
            Text(
                "Project · ${p.title}",
                fontSize = 12.sp,
                color = VirlinColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(CaptureContext(projectId = p.id)); onClose() }
                    .padding(vertical = 6.dp)
            )
        }
    }
}

private fun shareFile(context: android.content.Context, absolutePath: String?, mime: String, name: String) {
    val path = absolutePath ?: return
    val file = File(path)
    if (!file.exists()) return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime.ifBlank { "application/octet-stream" }
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share file"))
}
