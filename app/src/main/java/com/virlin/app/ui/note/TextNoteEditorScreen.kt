package com.virlin.app.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.note.NoteClipboardImporter
import com.virlin.app.domain.note.NoteEnterSemantics
import com.virlin.app.ui.theme.VirlinColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val TextNoteRoute = "text_note"
fun textNoteRoute(captureId: String?) =
    if (captureId.isNullOrBlank()) "text_note" else "text_note/$captureId"

const val TextNoteScreenTag = "text_note_screen"
const val TextNoteTitleTag = "text_note_title"
const val TextNoteSaveStatusTag = "text_note_save_status"
const val TextNoteCopyAllTag = "text_note_copy_all"
const val TextNoteExportPdfTag = "text_note_export_pdf"
const val TextNoteInboxActionTag = "text_note_inbox_action"
const val TextNoteLineBreakTag = "text_note_line_break"
const val TextNoteInboxBannerTag = "text_note_inbox_banner"

private val Paper = Color(0xFFFAF8F5)
private val DocWhite = Color(0xFFFFFFFF)
private val Forest = Color(0xFF162016)
private val Hairline = Color(0x1F162016)
private val Mint = Color(0xFF0D9488)
private val CalloutTint = Color(0xFFF0FAF6)
private val CodeTint = Color(0xFFF5F5F2)
private val QuoteRule = Color(0xFFCBD5C8)

const val TextNotePasteTag = "text_note_paste"

@Composable
fun TextNoteEditorScreen(
    navController: NavController,
    captureId: String?,
    vm: TextNoteViewModel = viewModel(factory = TextNoteViewModel.factory(captureId))
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

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
        Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag(TextNoteScreenTag)
    ) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { goBack() }, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Forest)
            }
            Column(Modifier.weight(1f)) {
                Text("Text Note", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Forest)
                val statusLine = when {
                    state.inboxBanner != null -> state.inboxBanner!!
                    state.committedToInbox && state.saveStatus == NoteSaveStatus.Saved -> "In Inbox ✓ · Saved ✓"
                    state.committedToInbox -> when (state.saveStatus) {
                        NoteSaveStatus.Saving -> "In Inbox · Saving…"
                        NoteSaveStatus.Error -> "In Inbox · Save issue"
                        else -> "In Inbox ✓"
                    }
                    state.saveStatus == NoteSaveStatus.Saving -> "Saving…"
                    state.saveStatus == NoteSaveStatus.Saved -> "Saved ✓"
                    state.saveStatus == NoteSaveStatus.Error -> "Save issue"
                    else -> "Draft"
                }
                Text(
                    statusLine,
                    fontSize = 11.sp,
                    color = when {
                        state.inboxBanner != null || state.committedToInbox -> Mint
                        state.saveStatus == NoteSaveStatus.Saved -> Mint
                        else -> VirlinColors.TextTertiary
                    },
                    modifier = Modifier.testTag(TextNoteSaveStatusTag)
                )
            }
            if (!state.committedToInbox) {
                Text(
                    "Inbox ↑",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Mint,
                    modifier = Modifier
                        .testTag(TextNoteInboxActionTag)
                        .semantics { contentDescription = "Save to Inbox" }
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.saveToInbox()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "Note menu" }) {
                    Icon(Icons.Rounded.MoreVert, null, tint = Forest)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Paste") },
                        onClick = {
                            menuOpen = false
                            val clip = ClipboardNoteReader.read(context)
                            val target = state.focusedBlockId ?: state.blocks.firstOrNull()?.id
                            if (target != null && (clip.plain.isNotBlank() || !clip.html.isNullOrBlank())) {
                                vm.pasteStructured(target, clip.html, clip.plain)
                            }
                        },
                        modifier = Modifier.testTag(TextNotePasteTag)
                    )
                    DropdownMenuItem(
                        text = { Text("Copy all") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(vm.copyAllPlainText()))
                        },
                        modifier = Modifier.testTag(TextNoteCopyAllTag)
                    )
                    DropdownMenuItem(
                        text = { Text("Export as PDF") },
                        onClick = {
                            menuOpen = false
                            scope.launch {
                                vm.prepareExit()
                                NotePdfExporter.exportAndShare(context, vm.currentDocument())
                            }
                        },
                        modifier = Modifier.testTag(TextNoteExportPdfTag)
                    )
                    DropdownMenuItem(text = { Text("Choose context") }, onClick = {
                        menuOpen = false; vm.openContextPicker(true)
                    })
                    DropdownMenuItem(text = { Text("Archive") }, onClick = {
                        menuOpen = false
                        vm.archiveAndExit { navController.popBackStack() }
                    })
                }
            }
        }

        state.inboxBanner?.let { banner ->
            Text(
                banner,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Mint,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .testTag(TextNoteInboxBannerTag)
            )
        }

        Text(
            state.contextLabel?.let { it } ?: "Global · Inbox",
            fontSize = 11.sp,
            color = if (state.contextLabel != null) Mint else VirlinColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        if (state.contextPickerOpen) {
            ContextPickerSheet(vm)
        }

        // Title + document canvas (one continuous white surface)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .background(DocWhite, RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = state.title,
                onValueChange = vm::onTitleChange,
                textStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Forest),
                cursorBrush = SolidColor(Mint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(TextNoteTitleTag)
                    .semantics { contentDescription = "Note title" },
                decorationBox = { inner ->
                    Box {
                        if (state.title.isEmpty()) {
                            Text("Untitled note", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9CA3AF))
                        }
                        inner()
                    }
                }
            )

            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(state.blocks, key = { it.id }) { block ->
                    BlockRow(block, state, vm)
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }

        // Toolbar
        NoteToolbar(vm, state)

        state.toast?.let {
            Text(
                it,
                color = Mint,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(12.dp).align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun BlockRow(block: NoteBlock, state: TextNoteUiState, vm: TextNoteViewModel) {
    val focused = state.focusedBlockId == block.id
    val handleVisible = focused || state.handleMenuBlockId == block.id
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .width(20.dp)
                    .padding(top = when (block.type) {
                        NoteBlockType.HEADING_1 -> 10.dp
                        NoteBlockType.HEADING_2 -> 8.dp
                        else -> 6.dp
                    }),
                contentAlignment = Alignment.Center
            ) {
                if (handleVisible) {
                    Text(
                        "⋮⋮",
                        fontSize = 11.sp,
                        color = Color(0x66162016),
                        modifier = Modifier
                            .clickable { vm.openHandle(block.id) }
                            .semantics { contentDescription = "Block handle" }
                    )
                }
            }

            when (block.type) {
                NoteBlockType.DIVIDER -> HorizontalDivider(
                    Modifier.padding(vertical = 16.dp).weight(1f),
                    color = Hairline,
                    thickness = 1.dp
                )
                NoteBlockType.CHECKBOX -> {
                    Icon(
                        if (block.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        contentDescription = if (block.checked) "Checked" else "Unchecked",
                        tint = if (block.checked) Mint else Forest,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(22.dp)
                            .clickable { vm.toggleCheckbox(block.id) }
                    )
                    Spacer(Modifier.width(8.dp))
                    BlockTextField(
                        block, state, vm,
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = if (block.checked) VirlinColors.TextTertiary else Forest,
                            textDecoration = if (block.checked) TextDecoration.LineThrough else TextDecoration.None
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                NoteBlockType.TOGGLE -> {
                    Icon(
                        if (block.collapsed) Icons.Rounded.KeyboardArrowRight else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = if (block.collapsed) "Collapsed" else "Expanded",
                        tint = Forest,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(22.dp)
                            .clickable { vm.toggleCollapsed(block.id) }
                    )
                    Column(Modifier.weight(1f)) {
                        BlockTextField(
                            block, state, vm,
                            TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Forest)
                        )
                        if (!block.collapsed) {
                            block.children.forEach { child ->
                                BlockTextField(
                                    block = child,
                                    state = state,
                                    vm = vm,
                                    style = TextStyle(fontSize = 15.sp, color = Forest),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 8.dp, top = 4.dp)
                                )
                            }
                            Text(
                                "+ Add inside",
                                fontSize = 12.sp,
                                color = Mint,
                                modifier = Modifier
                                    .padding(start = 8.dp, top = 6.dp)
                                    .clickable { vm.addChildToToggle(block.id) }
                            )
                        }
                    }
                }
                else -> {
                    val prefix = when (block.type) {
                        NoteBlockType.BULLETED_LIST -> "•"
                        NoteBlockType.NUMBERED_LIST -> "1."
                        else -> null
                    }
                    if (prefix != null) {
                        Text(
                            prefix,
                            fontSize = 16.sp,
                            color = Forest,
                            modifier = Modifier.padding(top = 8.dp, end = 8.dp)
                        )
                    }
                    val style = when (block.type) {
                        NoteBlockType.HEADING_1 -> TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Forest)
                        NoteBlockType.HEADING_2 -> TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Forest)
                        NoteBlockType.HEADING_3 -> TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Forest)
                        NoteBlockType.CODE -> TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Forest)
                        NoteBlockType.QUOTE -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF3F4A3F))
                        else -> TextStyle(fontSize = 16.sp, color = Forest, lineHeight = 24.sp)
                    }
                    val specialized = when (block.type) {
                        NoteBlockType.CALLOUT -> Modifier
                            .background(CalloutTint, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                        NoteBlockType.CODE -> Modifier
                            .background(CodeTint, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                        NoteBlockType.QUOTE -> Modifier
                            .border(width = 0.dp, color = Color.Transparent)
                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                        else -> Modifier
                    }
                    Row(Modifier.weight(1f).then(specialized), verticalAlignment = Alignment.Top) {
                        if (block.type == NoteBlockType.QUOTE) {
                            Box(
                                Modifier
                                    .padding(end = 10.dp, top = 4.dp)
                                    .width(3.dp)
                                    .heightIn(min = 22.dp)
                                    .background(QuoteRule, RoundedCornerShape(2.dp))
                                    .height(28.dp)
                            )
                        }
                        BlockTextField(block, state, vm, style, Modifier.weight(1f))
                    }
                }
            }
        }

        if (state.handleMenuBlockId == block.id) {
            HandleMenu(block, vm)
        }
        if (state.turnIntoOpen && state.handleMenuBlockId == block.id) {
            TurnIntoMenu(block.id, vm)
        }
    }
}

@Composable
private fun BlockTextField(
    block: NoteBlock,
    state: TextNoteUiState,
    vm: TextNoteViewModel,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focused = state.focusedBlockId == block.id
    var value by remember(block.id) { mutableStateOf(TextFieldValue(block.plainText)) }
    LaunchedEffect(block.plainText) {
        if (block.plainText != value.text) {
            value = TextFieldValue(block.plainText, TextRange(block.plainText.length))
        }
    }
    LaunchedEffect(state.cursorAtEndBlockId, block.id, block.plainText) {
        if (state.cursorAtEndBlockId == block.id) {
            value = TextFieldValue(block.plainText, TextRange(block.plainText.length))
            vm.clearCursorAtEnd()
        }
    }

    fun pasteFromClipboard() {
        val clip = ClipboardNoteReader.read(context)
        if (clip.plain.isNotBlank() || !clip.html.isNullOrBlank()) {
            vm.pasteStructured(block.id, clip.html, clip.plain)
        }
    }

    fun applyText(newValue: TextFieldValue, softBreak: Boolean) {
        vm.focusBlock(block.id)
        val old = value.text
        val new = newValue.text
        if (old.isEmpty() && new == "/") {
            vm.openBlockMenu(true)
            value = TextFieldValue("")
            return
        }
        // Multi-char paste via IME/system: prefer structured import when clipboard matches.
        val selectionLen = (value.selection.max - value.selection.min).coerceAtLeast(0)
        val grewBy = new.length - old.length + selectionLen
        if (grewBy >= 3 && !softBreak) {
            val clip = ClipboardNoteReader.read(context)
            val plain = clip.plain
            val related = plain.isNotBlank() && (
                new == plain ||
                    new.endsWith(plain) ||
                    (old.isEmpty() && (plain == new || plain.startsWith(new) || new.startsWith(plain.take(32))))
                )
            val wantsStructure = !clip.html.isNullOrBlank() ||
                NoteClipboardImporter.looksLikeMarkdown(plain) ||
                plain.contains('\n')
            if (related && wantsStructure) {
                vm.pasteStructured(block.id, clip.html, plain)
                return
            }
        }
        val nlAt = NoteEnterSemantics.newlineInsertion(old, new)
        if (nlAt != null && !softBreak && block.type != NoteBlockType.CODE) {
            val before = new.substring(0, nlAt)
            val after = new.substring(nlAt + 1)
            value = TextFieldValue(before, TextRange(before.length))
            vm.handleEnter(block.id, before, after)
            return
        }
        value = newValue
        vm.onBlockTextChange(block.id, new)
    }

    BasicTextField(
        value = value,
        onValueChange = { applyText(it, softBreak = false) },
        textStyle = style,
        cursorBrush = SolidColor(Mint),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Enter && e.isShiftPressed -> {
                        val cur = value.selection.start
                        val next = NoteEnterSemantics.insertSoftBreak(value.text, cur)
                        val pos = cur + 1
                        applyText(TextFieldValue(next, TextRange(pos)), softBreak = true)
                        true
                    }
                    e.key == Key.Backspace && value.text.isEmpty() &&
                        value.selection.collapsed -> {
                        vm.handleBackspaceOnEmpty(block.id)
                    }
                    (e.key == Key.V && e.isCtrlPressed) || (e.key == Key.V && e.isMetaPressed) -> {
                        pasteFromClipboard()
                        true
                    }
                    else -> false
                }
            }
            .semantics { contentDescription = "Block ${block.type.name}" },
        decorationBox = { inner ->
            Box(Modifier.padding(vertical = 4.dp)) {
                if (value.text.isEmpty() && focused) {
                    Text(
                        when (block.type) {
                            NoteBlockType.HEADING_1 -> "Heading 1"
                            NoteBlockType.HEADING_2 -> "Heading 2"
                            NoteBlockType.HEADING_3 -> "Heading 3"
                            NoteBlockType.CODE -> "Code"
                            else -> "Type something, or / for blocks"
                        },
                        style = style.copy(color = Color(0xFF9CA3AF))
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun HandleMenu(block: NoteBlock, vm: TextNoteViewModel) {
    Column(
        Modifier
            .padding(start = 20.dp, bottom = 6.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .padding(6.dp)
    ) {
        MenuLine("Line break") { vm.insertSoftLineBreak(block.id); vm.openHandle(null) }
        MenuLine("Move up") { vm.moveBlock(block.id, -1) }
        MenuLine("Move down") { vm.moveBlock(block.id, 1) }
        MenuLine("Duplicate") { vm.duplicateBlock(block.id) }
        MenuLine("Turn into…") { vm.openTurnInto(true) }
        MenuLine("Delete") { vm.deleteBlock(block.id) }
    }
}

@Composable
private fun TurnIntoMenu(blockId: String, vm: TextNoteViewModel) {
    val types = listOf(
        NoteBlockType.TEXT, NoteBlockType.HEADING_1, NoteBlockType.HEADING_2, NoteBlockType.HEADING_3,
        NoteBlockType.BULLETED_LIST, NoteBlockType.NUMBERED_LIST, NoteBlockType.CHECKBOX,
        NoteBlockType.QUOTE, NoteBlockType.CALLOUT, NoteBlockType.CODE, NoteBlockType.TOGGLE
    )
    Column(
        Modifier
            .padding(start = 20.dp, bottom = 6.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .padding(6.dp)
    ) {
        types.forEach { t -> MenuLine(t.name.lowercase().replace('_', ' ')) { vm.transformBlock(blockId, t) } }
    }
}

@Composable
private fun MenuLine(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        color = Forest,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

@Composable
private fun NoteToolbar(vm: TextNoteViewModel, state: TextNoteUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .border(width = 1.dp, color = Hairline, shape = RoundedCornerShape(0.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (state.blockMenuOpen) BlockPicker(vm)
        if (state.styleMenuOpen) StylePicker(vm)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ToolChip("+") { vm.openBlockMenu(!state.blockMenuOpen) }
            ToolChip("Aa") { vm.openStyleMenu(!state.styleMenuOpen) }
            ToolChip("↵", tag = TextNoteLineBreakTag) {
                val id = state.focusedBlockId ?: return@ToolChip
                vm.insertSoftLineBreak(id)
            }
            ToolChip("B") { vm.toggleInlineOnFocused(com.virlin.app.domain.model.InlineStyle.BOLD) }
            ToolChip("I") { vm.toggleInlineOnFocused(com.virlin.app.domain.model.InlineStyle.ITALIC) }
            ToolChip("U") { vm.toggleInlineOnFocused(com.virlin.app.domain.model.InlineStyle.UNDERLINE) }
            ToolChip("S") { vm.toggleInlineOnFocused(com.virlin.app.domain.model.InlineStyle.STRIKETHROUGH) }
            ToolChip("Link") { vm.toggleInlineOnFocused(com.virlin.app.domain.model.InlineStyle.LINK) }
        }
    }
}

@Composable
private fun ToolChip(label: String, tag: String? = null, onClick: () -> Unit) {
    Text(
        label,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = Forest,
        modifier = Modifier
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .background(Paper, RoundedCornerShape(10.dp))
            .border(1.dp, Hairline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics {
                contentDescription = when (label) {
                    "↵" -> "Line break"
                    else -> "Toolbar $label"
                }
            }
    )
}

@Composable
private fun BlockPicker(vm: TextNoteViewModel) {
    Column(Modifier.padding(8.dp)) {
        PickerGroup("BASIC", listOf(
            "Text" to NoteBlockType.TEXT,
            "Heading 1" to NoteBlockType.HEADING_1,
            "Heading 2" to NoteBlockType.HEADING_2,
            "Heading 3" to NoteBlockType.HEADING_3
        ), vm)
        PickerGroup("LISTS", listOf(
            "Bulleted list" to NoteBlockType.BULLETED_LIST,
            "Numbered list" to NoteBlockType.NUMBERED_LIST,
            "Checkbox" to NoteBlockType.CHECKBOX
        ), vm)
        PickerGroup("STRUCTURE", listOf(
            "Toggle" to NoteBlockType.TOGGLE,
            "Divider" to NoteBlockType.DIVIDER,
            "Quote" to NoteBlockType.QUOTE,
            "Callout" to NoteBlockType.CALLOUT,
            "Code" to NoteBlockType.CODE
        ), vm)
    }
}

@Composable
private fun StylePicker(vm: TextNoteViewModel) {
    Column(Modifier.padding(8.dp)) {
        listOf(
            "Text" to NoteBlockType.TEXT,
            "Heading 1" to NoteBlockType.HEADING_1,
            "Heading 2" to NoteBlockType.HEADING_2,
            "Heading 3" to NoteBlockType.HEADING_3,
            "Quote" to NoteBlockType.QUOTE,
            "Code" to NoteBlockType.CODE
        ).forEach { (label, type) ->
            MenuLine(label) { vm.transformFocused(type) }
        }
    }
}

@Composable
private fun PickerGroup(title: String, items: List<Pair<String, NoteBlockType>>, vm: TextNoteViewModel) {
    Text(title, fontSize = 10.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextTertiary, letterSpacing = 1.sp)
    items.forEach { (label, type) ->
        MenuLine(label) {
            if (type == NoteBlockType.DIVIDER) vm.insertBlockAfter(type)
            else {
                val focused = vm.state.value.focusedBlockId
                if (focused != null) vm.transformBlock(focused, type) else vm.insertBlockAfter(type)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun ContextPickerSheet(vm: TextNoteViewModel) {
    val projects = VirlinGraph.repository.projects.value
    val streams = VirlinGraph.repository.streams.value
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        MenuLine("Global · Inbox") { vm.setContext(CaptureContext.None) }
        projects.forEach { p ->
            MenuLine(p.title) { vm.setContext(CaptureContext(projectId = p.id)) }
        }
        streams.forEach { s ->
            MenuLine(s.title) { vm.setContext(CaptureContext(projectId = s.projectId, workStreamId = s.id)) }
        }
    }
}
