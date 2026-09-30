package com.virlin.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.model.TaskPageTypeKeys

/** Navigation route for the Notes page. */
const val NOTES_ROUTE: String = "notes/{ownerKey}?title={title}"

/**
 * Opens the Notes page for a task.
 *
 * The task id only namespaces the document key and supplies the header title. The Notes page
 * reads and writes nothing of the task itself, so the owner can be changed later without
 * touching stored documents.
 */
fun notesRoute(ownerKey: String, title: String): String =
    "notes/" + android.net.Uri.encode(ownerKey) + "?title=" + android.net.Uri.encode(title)

fun notesForTask(taskId: String, title: String): String =
    notesRoute(TaskPageTypeKeys.noteOwner(taskId), title)

fun notesForProject(projectId: String, title: String): String =
    notesRoute("project-$projectId", title)

fun globalNotes(): String = notesRoute("global", "Notes")

private val Ink = Color(0xFF17221D)
private val Green = Color(0xFF087848)
private val Mint = Color(0xFFE2F5EA)
private val Border = Color(0xFFE3E9E5)
private val Muted = Color(0xFF66758A)

/**
 * The Notes page route.
 *
 * Renders the approved [VirlinNotesScreen] shell unchanged. The shell's `footer` slot is left
 * empty on purpose: the shared footer and the Orb come from the app Scaffold for this route, so
 * filling the slot as well would put two footers on the page.
 *
 * [ownerKey] is the opaque key the document is stored under and [title] is display only. Nothing
 * on this page reads or writes a task's note field, a capture item or a `NoteDocument`.
 */
@Composable
fun NotesRoute(
    navController: NavController,
    ownerKey: String,
    title: String,
    vm: NotesViewModel = viewModel(
        key = "notes-$ownerKey",
        factory = NotesViewModel.factory(ownerKey, title),
    ),
) {
    val state by vm.state.collectAsState()

    // The debounce must never eat the last keystrokes when the page goes away.
    DisposableEffect(Unit) { onDispose { vm.flush() } }

    VirlinNotesScreen(
        taskTitle = state.title.ifBlank { title },
        saveLabel = vm.saveLabel(),
        tools = vm.toolState(),
        onClose = { vm.flush(); navController.popBackStack() },
        onTool = vm::onTool,
        onAddBlock = vm::openAddBlock,
        onPreview = vm::openPreview,
        onExport = vm::onExport,
        editor = { NoteBlockEditor(state, vm) },
        footer = {},
    )

    state.dialog?.let { NoteDialogHost(it, state, vm) }
}

@Composable
private fun NoteDialogHost(dialog: NoteDialog, state: NotesUiState, vm: NotesViewModel) {
    when (dialog) {
        is NoteDialog.Paragraph -> PickerDialog("Paragraph style", vm::closeDialog) {
            listOf(
                NoteDocBlockType.PARAGRAPH to "Normal text",
                NoteDocBlockType.HEADING_1 to "Heading 1",
                NoteDocBlockType.HEADING_2 to "Heading 2",
                NoteDocBlockType.HEADING_3 to "Heading 3",
                NoteDocBlockType.QUOTE to "Quote",
                NoteDocBlockType.CODE to "Code",
                NoteDocBlockType.PROMPT to "Prompt",
            ).forEach { (type, label) ->
                PickerRow(label, state.focusedBlock?.type == type) { vm.setBlockType(type) }
            }
        }

        is NoteDialog.Font -> PickerDialog("Font", vm::closeDialog) {
            NotesViewModel.FONTS.forEach { family ->
                PickerRow(family, vm.toolState().fontLabel == family) { vm.setFont(family) }
            }
        }

        is NoteDialog.Size -> PickerDialog("Text size", vm::closeDialog) {
            NotesViewModel.SIZES.forEach { sp ->
                PickerRow("$sp", vm.toolState().sizeLabel == "$sp") { vm.setFontSize(sp) }
            }
        }

        is NoteDialog.TextColor -> PickerDialog("Text colour", vm::closeDialog) {
            NotesViewModel.TEXT_COLORS.forEach { (argb, label) ->
                PickerRow(label, false, swatch = argb) { vm.setTextColor(argb) }
            }
        }

        is NoteDialog.Highlight -> PickerDialog("Highlight", vm::closeDialog) {
            NotesViewModel.HIGHLIGHTS.forEach { (argb, label) ->
                PickerRow(label, false, swatch = argb) { vm.setHighlight(argb) }
            }
        }

        is NoteDialog.LineSpacing -> PickerDialog("Line spacing", vm::closeDialog) {
            NotesViewModel.SPACINGS.forEach { (value, label) ->
                PickerRow(label, state.focusedBlock?.lineSpacing == value) { vm.setLineSpacing(value) }
            }
        }

        is NoteDialog.AddBlock -> PickerDialog("Add a block", vm::closeDialog) {
            listOf(
                NoteDocBlockType.PARAGRAPH to "Paragraph",
                NoteDocBlockType.HEADING_1 to "Heading 1",
                NoteDocBlockType.HEADING_2 to "Heading 2",
                NoteDocBlockType.HEADING_3 to "Heading 3",
                NoteDocBlockType.BULLET to "Bulleted list",
                NoteDocBlockType.NUMBERED to "Numbered list",
                NoteDocBlockType.CHECKLIST to "Checklist",
                NoteDocBlockType.QUOTE to "Quote",
                NoteDocBlockType.PROMPT to "Prompt callout",
                NoteDocBlockType.CODE to "Code",
                NoteDocBlockType.DIVIDER to "Divider",
                NoteDocBlockType.TABLE to "Table",
            ).forEach { (type, label) ->
                PickerRow(label, false) { vm.insertBlock(type) }
            }
            // Image and attachment have no storage in this self-contained feature yet, so they
            // are stated as absent rather than offered and silently doing nothing.
            Spacer(Modifier.height(6.dp))
            Text(
                "Image and attachment blocks are not built yet.",
                color = Muted, fontSize = 11.sp,
            )
        }

        is NoteDialog.Table -> PickerDialog("Insert table", vm::closeDialog) {
            listOf(2 to 2, 3 to 2, 3 to 3, 4 to 3).forEach { (rows, cols) ->
                PickerRow("$rows × $cols", false) {
                    vm.insertBlock(NoteDocBlockType.TABLE, NoteDocTable.blank(rows, cols))
                }
            }
        }

        is NoteDialog.Link -> LinkDialog(dialog, vm)

        is NoteDialog.Preview -> PreviewDialog(state, vm)

        is NoteDialog.Message -> PickerDialog("Note", vm::closeDialog) {
            Text(dialog.text, color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(14.dp))
            PickerRow("OK", false) { vm.closeDialog() }
        }
    }
}

@Composable
private fun PickerDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(
                Modifier.width(300.dp).padding(18.dp)
                    .heightIn(max = 460.dp).verticalScroll(rememberScrollState())
                    .testTag("notes_dialog"),
            ) {
                Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    swatch: Long? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape)
            .background(if (selected) Mint else Color.White)
            .border(1.dp, if (selected) Green.copy(alpha = .35f) else Border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag("notes_pick_$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swatch != null) {
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .background(Color(swatch.toULong().toLong()))
                    .border(1.dp, Border, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            label,
            color = if (selected) Green else Ink,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LinkDialog(dialog: NoteDialog.Link, vm: NotesViewModel) {
    var url by remember { mutableStateOf(dialog.initialUrl) }
    PickerDialog("Link", vm::closeDialog) {
        Text(
            if (dialog.hasSelection) "The selected text becomes the link."
            else "The whole block becomes the link.",
            color = Muted, fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 46.dp)
                .clip(RoundedCornerShape(11.dp))
                .border(1.dp, Border, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                cursorBrush = SolidColor(Green),
                textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (url.isEmpty()) Text("example.com", color = Muted, fontSize = 14.sp)
                    inner()
                },
                modifier = Modifier.fillMaxWidth().testTag("notes_link_field"),
            )
        }
        Spacer(Modifier.height(12.dp))
        PickerRow("Apply link", false) { vm.setLink(url) }
        if (dialog.initialUrl.isNotEmpty()) PickerRow("Remove link", false) { vm.setLink(null) }
        PickerRow("Cancel", false) { vm.closeDialog() }
    }
}

/** Read-only rendering of exactly what is stored. It cannot edit, so it cannot drift. */
@Composable
private fun PreviewDialog(state: NotesUiState, vm: NotesViewModel) {
    Dialog(onDismissRequest = vm::closeDialog) {
        Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
            Column(
                Modifier.width(320.dp).padding(18.dp)
                    .heightIn(max = 520.dp).verticalScroll(rememberScrollState())
                    .testTag("notes_preview"),
            ) {
                Text("Preview", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (state.blocks.all { it.text.isBlank() && it.table == null && it.type != NoteDocBlockType.DIVIDER }) {
                    Text("This note is empty.", color = Muted, fontSize = 13.sp)
                }
                state.blocks.forEachIndexed { i, block ->
                    PreviewBlock(block, state.blocks.take(i))
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(6.dp))
                PickerRow("Close", false) { vm.closeDialog() }
            }
        }
    }
}

@Composable
private fun PreviewBlock(block: NoteDocBlock, before: List<NoteDocBlock>) {
    when (block.type) {
        NoteDocBlockType.DIVIDER ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))

        NoteDocBlockType.TABLE -> block.table?.let { t ->
            Column(Modifier.fillMaxWidth()) {
                repeat(t.rows) { r ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(t.cols) { c ->
                            Box(
                                Modifier.weight(1f).background(Border).padding(1.dp)
                                    .background(Color.White).padding(6.dp),
                            ) { Text(t.cell(r, c), color = Ink, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }

        else -> {
            val prefix = when (block.type) {
                NoteDocBlockType.BULLET -> "•  "
                NoteDocBlockType.NUMBERED -> {
                    val n = before.reversed()
                        .takeWhile { it.type == NoteDocBlockType.NUMBERED && it.indent == block.indent }
                        .size + 1
                    "$n.  "
                }
                NoteDocBlockType.CHECKLIST -> if (block.checked) "✓  " else "▢  "
                NoteDocBlockType.QUOTE -> "│  "
                else -> ""
            }
            val annotated = androidx.compose.ui.text.buildAnnotatedString {
                append(prefix)
                val base = length
                append(block.text)
                block.runs.forEach { r ->
                    val s = base + r.start.coerceIn(0, block.text.length)
                    val e = base + r.end.coerceIn(0, block.text.length)
                    if (e <= s) return@forEach
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(
                            fontWeight = if (NoteDocMark.BOLD in r.marks) FontWeight.Bold else null,
                            fontStyle = if (NoteDocMark.ITALIC in r.marks) {
                                androidx.compose.ui.text.font.FontStyle.Italic
                            } else null,
                            textDecoration = buildList {
                                if (NoteDocMark.UNDERLINE in r.marks || r.linkUrl != null) {
                                    add(TextDecoration.Underline)
                                }
                                if (NoteDocMark.STRIKE in r.marks) add(TextDecoration.LineThrough)
                            }.let { if (it.isEmpty()) null else TextDecoration.combine(it) },
                            color = when {
                                r.linkUrl != null -> Green
                                r.color != null -> Color(r.color.toULong().toLong())
                                else -> Color.Unspecified
                            },
                            background = r.highlight?.let { Color(it.toULong().toLong()) }
                                ?: Color.Unspecified,
                            fontFamily = when {
                                NoteDocMark.CODE in r.marks -> FontFamily.Monospace
                                r.fontFamily == "Serif" -> FontFamily.Serif
                                r.fontFamily == "Mono" -> FontFamily.Monospace
                                r.fontFamily == "Inter" -> FontFamily.SansSerif
                                else -> null
                            },
                            fontSize = r.fontSizeSp?.sp
                                ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                        ),
                        s, e,
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = (block.indent * 14).dp)) {
                block.promptTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    annotated,
                    color = Ink,
                    fontSize = NotesViewModel.defaultSizeFor(block.type).sp,
                    fontWeight = when (block.type) {
                        NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2,
                        NoteDocBlockType.HEADING_3,
                        -> FontWeight.Bold
                        else -> FontWeight.Normal
                    },
                    fontFamily = if (block.type == NoteDocBlockType.CODE) FontFamily.Monospace else null,
                    textAlign = when (block.align) {
                        NoteDocAlign.CENTER -> TextAlign.Center
                        NoteDocAlign.RIGHT -> TextAlign.End
                        NoteDocAlign.LEFT -> TextAlign.Start
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
