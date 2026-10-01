package com.virlin.app.ui.attachment

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.domain.attachment.AttachmentKindResolver
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.ui.file.UniversalFileViewer
import java.io.File

private val Ink = Color(0xFF17221D)
private val Green = Color(0xFF087848)
private val Mint = Color(0xFFE2F5EA)
private val Border = Color(0xFFE3E9E5)
private val Muted = Color(0xFF66758A)
private val Canvas = Color(0xFFFCFBF8)

/**
 * The task's universal file space.
 *
 * Every row is one managed attachment. Which action a row offers, and which viewer it opens, comes
 * from the centrally resolved [AttachmentKind] - never from the filename.
 */
@Composable
fun AttachmentWorkspaceScreen(
    navController: NavController,
    taskId: String,
    vm: AttachmentWorkspaceViewModel = viewModel(
        key = "attachments-$taskId",
        factory = AttachmentWorkspaceViewModel.factory(taskId, LocalContext.current),
    ),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.importAll(uris.orEmpty()) }

    fun choose() = runCatching { picker.launch(arrayOf("*/*")) }

    state.openDetail?.let { row ->
        AttachmentDetail(row, vm, navController, onClose = vm::closeDetail)
        return
    }

    Scaffold(
        containerColor = Canvas,
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton({ navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Ink)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Attachments", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                        if (state.taskTitle.isNotBlank()) {
                            Text(
                                "Attached to  ·  ${state.taskTitle}",
                                fontSize = 12.sp, color = Muted, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (state.rows.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().background(Canvas).padding(16.dp)) {
                    Button(
                        onClick = { choose() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                    ) { Text("Add files", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).testTag(AttachmentWorkspaceTag)) {
            ImportCard(importing = state.importing, done = state.importedCount, total = state.importTotal) { choose() }

            if (state.failures.isNotEmpty()) {
                FailureNotice(state.failures.map { "${it.displayName}: ${it.reason}" }, vm::clearFailures)
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Green)
                }
                state.rows.isEmpty() -> EmptyLibrary()
                else -> {
                    Text(
                        "Saved files  ·  ${state.rows.size}",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted,
                        modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 8.dp),
                    )
                    LazyColumn(
                        Modifier.weight(1f).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.rows, key = { it.captureId }) { row ->
                            AttachmentRowItem(row) { vm.open(row) }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }

    state.message?.let {
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            confirmButton = { TextButton(vm::clearMessage) { Text("OK") } },
            text = { Text(it) },
        )
    }
    state.pendingRemoval?.let { row ->
        AlertDialog(
            onDismissRequest = vm::cancelRemove,
            title = { Text("Remove this file?") },
            text = {
                Text("“${row.document.displayName}” will be removed from this task and its stored copy deleted. This cannot be undone.")
            },
            confirmButton = { TextButton(vm::confirmRemove) { Text("Remove", color = Color(0xFFB3261E)) } },
            dismissButton = { TextButton(vm::cancelRemove) { Text("Cancel") } },
        )
    }
    state.renaming?.let { row -> RenameDialog(row, vm) }
}

@Composable
private fun ImportCard(importing: Boolean, done: Int, total: Int, onChoose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color.White)
            .border(1.dp, Border, RoundedCornerShape(16.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Mint),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AttachFile, null, tint = Green, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Add any file", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(
                    "Documents, text, media, archives and more",
                    fontSize = 12.sp, color = Muted,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        if (importing) {
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Green,
            )
            Spacer(Modifier.height(8.dp))
            Text("Importing $done of $total…", fontSize = 12.sp, color = Muted)
        } else {
            Button(
                onClick = onChoose,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag(AttachmentImportButtonTag),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
            ) { Text("Choose files", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun FailureNotice(lines: List<String>, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF4E5)).padding(12.dp),
    ) {
        Text("Some files could not be added", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8A5A00))
        lines.forEach { Text(it, fontSize = 12.sp, color = Color(0xFF8A5A00)) }
        TextButton(onDismiss) { Text("Dismiss", color = Color(0xFF8A5A00)) }
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AttachFile, null, tint = Green, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("No files yet", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Anything you add stays on this device, attached to this task.",
            fontSize = 13.sp, color = Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun AttachmentRowItem(row: AttachmentRow, onOpen: () -> Unit) {
    val action = when (row.kind) {
        AttachmentKind.AUDIO, AttachmentKind.VIDEO -> "Play"
        AttachmentKind.ARCHIVE, AttachmentKind.UNSUPPORTED -> "Details"
        else -> "View"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)
            .border(1.dp, Border, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen).padding(12.dp)
            .testTag(AttachmentRowTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(badgeColor(row.kind)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badgeText(row.document.displayName, row.kind),
                fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.document.displayName,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${AttachmentKindResolver.formatSize(row.document.sizeBytes)}  ·  Saved",
                fontSize = 12.sp, color = Muted,
            )
        }
        OutlinedButton(
            onClick = onOpen,
            shape = RoundedCornerShape(10.dp),
            border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp),
        ) { Text(action, fontSize = 13.sp, color = Green) }
    }
}

private fun badgeText(name: String, kind: AttachmentKind): String {
    val ext = name.substringAfterLast('.', "").uppercase()
    return if (ext.isNotEmpty() && ext.length <= 4) ext else AttachmentKindResolver.formatLabel(kind).take(4).uppercase()
}

private fun badgeColor(kind: AttachmentKind): Color = when (kind) {
    AttachmentKind.PDF -> Color(0xFFD64545)
    AttachmentKind.IMAGE -> Color(0xFF2E7D32)
    AttachmentKind.VIDEO -> Color(0xFF7E57C2)
    AttachmentKind.AUDIO -> Color(0xFFE05252)
    AttachmentKind.ARCHIVE -> Color(0xFFE0A52E)
    AttachmentKind.DOCX, AttachmentKind.XLSX, AttachmentKind.PPTX -> Color(0xFF2B6CB0)
    else -> Color(0xFF6B7A72)
}

/** Full-screen view of one attachment, plus its action sheet. */
@Composable
private fun AttachmentDetail(
    row: AttachmentRow,
    vm: AttachmentWorkspaceViewModel,
    navController: NavController,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var sheetOpen by remember { mutableStateOf(false) }
    val file = remember(row.document.relativePath) {
        AttachmentFileStore.resolve(context, row.document.relativePath)
    }
    val missing = remember(file.absolutePath) { !file.exists() || file.length() == 0L }

    // A PDF belongs to the dedicated workspace; hand off by capture id, never by filename.
    LaunchedEffect(row.captureId, row.kind) {
        if (row.kind == AttachmentKind.PDF && !missing) {
            onClose()
            navController.navigate(com.virlin.app.ui.pdf.pdfWorkspaceForCapture(row.captureId))
        }
    }

    Scaffold(
        containerColor = Canvas,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Ink) }
                Column(Modifier.weight(1f)) {
                    Text(
                        row.document.displayName,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${AttachmentKindResolver.formatLabel(row.kind)}  ·  ${AttachmentKindResolver.formatSize(row.document.sizeBytes)}",
                        fontSize = 12.sp, color = Muted,
                    )
                }
                IconButton({ sheetOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, "File actions", tint = Ink)
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).testTag(AttachmentDetailTag)) {
            when {
                missing -> MissingFile(row)
                else -> UniversalFileViewer(
                    absolutePath = file.absolutePath,
                    kind = row.kind,
                    displayName = row.document.displayName,
                    mimeType = row.document.mimeType,
                )
            }
        }
    }

    if (sheetOpen) {
        AttachmentActionSheet(
            row = row,
            fileExists = !missing,
            onDismiss = { sheetOpen = false },
            onRename = { sheetOpen = false; vm.startRename(row) },
            onShare = { sheetOpen = false; shareFile(context, row) },
            onOpenWith = { sheetOpen = false; openWith(context, row) },
            onRemove = { sheetOpen = false; vm.askRemove(row) },
        )
    }
}

@Composable
private fun MissingFile(row: AttachmentRow) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("This file is missing", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Its stored copy is no longer on this device. The record is kept so you can remove it deliberately.",
            fontSize = 13.sp, color = Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(row.document.displayName, fontSize = 11.sp, color = Color(0xFF8A918A))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachmentActionSheet(
    row: AttachmentRow,
    fileExists: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
    onRemove: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(bottom = 24.dp)) {
            SheetLine("Rename", enabled = true, onClick = onRename)
            SheetLine("Share", enabled = fileExists, onClick = onShare)
            SheetLine("Open with", enabled = fileExists, onClick = onOpenWith)
            SheetLine("Remove", enabled = true, danger = true, onClick = onRemove)
        }
    }
}

@Composable
private fun SheetLine(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> Color(0xFFB9C2BC)
        danger -> Color(0xFFB3261E)
        else -> Ink
    }
    Text(
        label,
        fontSize = 15.sp,
        color = tint,
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

@Composable
private fun RenameDialog(row: AttachmentRow, vm: AttachmentWorkspaceViewModel) {
    var text by remember(row.captureId) { mutableStateOf(row.document.displayName) }
    AlertDialog(
        onDismissRequest = { vm.startRename(null) },
        title = { Text("Rename file") },
        text = {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Border, RoundedCornerShape(10.dp)).padding(12.dp),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    cursorBrush = SolidColor(Green),
                    textStyle = TextStyle(color = Ink, fontSize = 15.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton({ vm.rename(row, text) }) { Text("Save") } },
        dismissButton = { TextButton({ vm.startRename(null) }) { Text("Cancel") } },
    )
}

/** Shares through the app FileProvider. A private filesystem path is never exposed. */
private fun shareFile(context: android.content.Context, row: AttachmentRow) {
    runCatching {
        val file = AttachmentFileStore.resolve(context, row.document.relativePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = row.document.mimeType.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${row.document.displayName}"))
    }
}

/** Hands the file to a compatible app. Virlin never executes imported content itself. */
private fun openWith(context: android.content.Context, row: AttachmentRow) {
    runCatching {
        val file = AttachmentFileStore.resolve(context, row.document.relativePath)
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, row.document.mimeType.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open ${row.document.displayName}"))
    }
}
