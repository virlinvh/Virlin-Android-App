package com.virlin.app.ui.prompt

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
import androidx.compose.material.icons.rounded.Close
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
import com.virlin.app.ui.note.ClipboardNoteReader
import com.virlin.app.ui.theme.VirlinColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val PromptRoute = "prompt_editor"
fun promptEditorRoute(captureId: String?) =
    if (captureId.isNullOrBlank()) "prompt_editor" else "prompt_editor/$captureId"

const val PromptScreenTag = "prompt_editor_screen"
const val PromptTitleTag = "prompt_title"
const val PromptBodyCopyTag = "prompt_body_copy"
const val PromptInboxActionTag = "prompt_inbox_action"
const val PromptSaveStatusTag = "prompt_save_status"
const val PromptPasteTag = "prompt_paste"

private val Paper = Color(0xFFFAF8F5)
private val DocWhite = Color(0xFFFFFFFF)
private val Forest = Color(0xFF162016)
private val Hairline = Color(0x1F162016)
private val Mint = Color(0xFF0D9488)
private val Lavender = Color(0xFF7E57C2)
private val LavenderSoft = Color(0xFFF3EBFC)
private val LavenderBorder = Color(0x337E57C2)
private val CodeTint = Color(0xFFF5F3FA)
private val QuoteRule = Color(0xFFC4B5D8)

@Composable
fun PromptEditorScreen(
    navController: NavController,
    captureId: String?,
    vm: PromptEditorViewModel = viewModel(factory = PromptEditorViewModel.factory(captureId))
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
            .testTag(PromptScreenTag)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { goBack() }, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Forest)
            }
            Column(Modifier.weight(1f)) {
                Text("Prompt", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Forest)
                val statusLine = when {
                    state.inboxBanner != null -> state.inboxBanner!!
                    state.committedToInbox && state.saveStatus == PromptSaveStatus.Saved -> "In Inbox ✓ · Saved ✓"
                    state.committedToInbox -> when (state.saveStatus) {
                        PromptSaveStatus.Saving -> "In Inbox · Saving…"
                        PromptSaveStatus.Error -> "In Inbox · Save issue"
                        else -> "In Inbox ✓"
                    }
                    state.saveStatus == PromptSaveStatus.Saving -> "Saving…"
                    state.saveStatus == PromptSaveStatus.Saved -> "Saved ✓"
                    state.saveStatus == PromptSaveStatus.Error -> "Save issue"
                    else -> "Draft"
                }
                Text(
                    statusLine,
                    fontSize = 11.sp,
                    color = when {
                        state.inboxBanner != null || state.committedToInbox -> Mint
                        state.saveStatus == PromptSaveStatus.Saved -> Mint
                        else -> VirlinColors.TextTertiary
                    },
                    modifier = Modifier.testTag(PromptSaveStatusTag)
                )
            }
            if (!state.committedToInbox) {
                Text(
                    "Save to Inbox",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Lavender,
                    modifier = Modifier
                        .testTag(PromptInboxActionTag)
                        .semantics { contentDescription = "Save to Inbox" }
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.saveToInbox()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "Prompt menu" }) {
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
                        modifier = Modifier.testTag(PromptPasteTag)
                    )
                    DropdownMenuItem(
                        text = { Text("Copy prompt") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(vm.copyPromptPlainText()))
                        }
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

        state.inboxBanner?.let {
            Text(it, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Mint,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        Text(
            state.contextLabel ?: "Global · Inbox",
            fontSize = 11.sp,
            color = if (state.contextLabel != null) Lavender else VirlinColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        if (state.contextPickerOpen) {
            PromptContextPicker(vm)
        }

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                BasicTextField(
                    value = state.title,
                    onValueChange = vm::onTitleChange,
                    textStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Forest),
                    cursorBrush = SolidColor(Lavender),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag(PromptTitleTag)
                        .semantics { contentDescription = "Prompt title" },
                    decorationBox = { inner ->
                        Box {
                            if (state.title.isEmpty()) {
                                Text("Untitled prompt", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9CA3AF))
                            }
                            inner()
                        }
                    }
                )
            }
            item {
                BasicTextField(
                    value = state.description,
                    onValueChange = vm::onDescriptionChange,
                    textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF4B5563), lineHeight = 20.sp),
                    cursorBrush = SolidColor(Lavender),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Prompt description" },
                    decorationBox = { inner ->
                        Box {
                            if (state.description.isEmpty()) {
                                Text("Add description…", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                            }
                            inner()
                        }
                    }
                )
            }
            item { TagRow(state, vm) }
            item {
                PromptBodyCard(state, vm, clipboard)
            }
            item { Spacer(Modifier.height(48.dp)) }
        }

        state.toast?.let {
            Text(
                it, color = Mint, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.padding(12.dp).align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun TagRow(state: PromptUiState, vm: PromptEditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        state.tags.forEach { tag ->
            Row(
                Modifier
                    .background(LavenderSoft, RoundedCornerShape(20.dp))
                    .border(1.dp, LavenderBorder, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tag, fontSize = 12.sp, color = Lavender, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Rounded.Close, contentDescription = "Remove $tag",
                    tint = Lavender, modifier = Modifier.size(14.dp).clickable { vm.removeTag(tag) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = state.tagDraft,
                onValueChange = vm::onTagDraftChange,
                textStyle = TextStyle(fontSize = 12.sp, color = Forest),
                cursorBrush = SolidColor(Lavender),
                singleLine = true,
                modifier = Modifier
                    .width(100.dp)
                    .background(Color.White, RoundedCornerShape(20.dp))
                    .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                            vm.addTagFromDraft(); true
                        } else false
                    },
                decorationBox = { inner ->
                    Box {
                        if (state.tagDraft.isEmpty()) Text("+ Tag", fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        inner()
                    }
                }
            )
            Text(
                "Add",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Lavender,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clickable { vm.addTagFromDraft() }
            )
        }
    }
}

@Composable
private fun PromptBodyCard(
    state: PromptUiState,
    vm: PromptEditorViewModel,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(DocWhite, RoundedCornerShape(16.dp))
            .border(1.dp, LavenderBorder, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Prompt", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Lavender)
            Spacer(Modifier.weight(1f))
            Text(
                "COPY ⧉",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = Lavender,
                modifier = Modifier
                    .testTag(PromptBodyCopyTag)
                    .semantics { contentDescription = "Copy prompt" }
                    .clickable { clipboard.setText(AnnotatedString(vm.copyPromptPlainText())) }
                    .padding(4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        state.blocks.forEach { block ->
            PromptBlockRow(block, state, vm)
        }
    }
}

@Composable
private fun PromptBlockRow(block: NoteBlock, state: PromptUiState, vm: PromptEditorViewModel) {
    when (block.type) {
        NoteBlockType.DIVIDER -> HorizontalDivider(
            Modifier.padding(vertical = 10.dp), color = Hairline, thickness = 1.dp
        )
        else -> {
            val prefix = when (block.type) {
                NoteBlockType.BULLETED_LIST -> "•"
                NoteBlockType.NUMBERED_LIST -> "1."
                NoteBlockType.CHECKBOX -> if (block.checked) "✓" else "☐"
                else -> null
            }
            val style = when (block.type) {
                NoteBlockType.HEADING_1 -> TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Forest)
                NoteBlockType.HEADING_2 -> TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Forest)
                NoteBlockType.HEADING_3 -> TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Forest)
                NoteBlockType.CODE -> TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Forest)
                NoteBlockType.QUOTE -> TextStyle(fontSize = 14.sp, color = Color(0xFF3F4A3F))
                else -> TextStyle(fontSize = 14.sp, color = Forest, lineHeight = 21.sp)
            }
            val boxMod = when (block.type) {
                NoteBlockType.CODE -> Modifier
                    .fillMaxWidth()
                    .background(CodeTint, RoundedCornerShape(8.dp))
                    .padding(10.dp)
                NoteBlockType.CALLOUT -> Modifier
                    .fillMaxWidth()
                    .background(LavenderSoft, RoundedCornerShape(8.dp))
                    .padding(10.dp)
                NoteBlockType.QUOTE -> Modifier.fillMaxWidth().padding(start = 4.dp)
                else -> Modifier.fillMaxWidth()
            }
            Row(Modifier.padding(vertical = 2.dp).then(boxMod), verticalAlignment = Alignment.Top) {
                if (block.type == NoteBlockType.QUOTE) {
                    Box(
                        Modifier
                            .padding(end = 8.dp, top = 2.dp)
                            .width(3.dp)
                            .height(22.dp)
                            .background(QuoteRule, RoundedCornerShape(2.dp))
                    )
                }
                if (prefix != null) {
                    Text(prefix, fontSize = 14.sp, color = Forest, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                }
                PromptBlockField(block, state, vm, style, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PromptBlockField(
    block: NoteBlock,
    state: PromptUiState,
    vm: PromptEditorViewModel,
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
        val selectionLen = (value.selection.max - value.selection.min).coerceAtLeast(0)
        val grewBy = new.length - old.length + selectionLen
        if (grewBy >= 3 && !softBreak) {
            val clip = ClipboardNoteReader.read(context)
            val plain = clip.plain
            val related = plain.isNotBlank() && (
                new == plain || new.endsWith(plain) ||
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
        cursorBrush = SolidColor(Lavender),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    e.key == Key.Enter && e.isShiftPressed -> {
                        val cur = value.selection.start
                        val next = NoteEnterSemantics.insertSoftBreak(value.text, cur)
                        applyText(TextFieldValue(next, TextRange(cur + 1)), softBreak = true)
                        true
                    }
                    e.key == Key.Backspace && value.text.isEmpty() && value.selection.collapsed ->
                        vm.handleBackspaceOnEmpty(block.id)
                    (e.key == Key.V && e.isCtrlPressed) || (e.key == Key.V && e.isMetaPressed) -> {
                        pasteFromClipboard(); true
                    }
                    else -> false
                }
            }
            .semantics { contentDescription = "Prompt block ${block.type.name}" },
        decorationBox = { inner ->
            Box(Modifier.padding(vertical = 2.dp)) {
                if (value.text.isEmpty() && focused) {
                    Text(
                        "Paste or type your prompt…",
                        style = style.copy(color = Color(0xFF9CA3AF))
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun PromptContextPicker(vm: PromptEditorViewModel) {
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
        listOf("Global · Inbox" to CaptureContext.None).forEach { (label, ctx) ->
            Text(label, fontSize = 13.sp, color = Forest,
                modifier = Modifier.fillMaxWidth().clickable { vm.setContext(ctx) }.padding(10.dp, 8.dp))
        }
        projects.forEach { p ->
            Text(p.title, fontSize = 13.sp, color = Forest,
                modifier = Modifier.fillMaxWidth().clickable {
                    vm.setContext(CaptureContext(projectId = p.id))
                }.padding(10.dp, 8.dp))
        }
        streams.forEach { s ->
            Text(s.title, fontSize = 13.sp, color = Forest,
                modifier = Modifier.fillMaxWidth().clickable {
                    vm.setContext(CaptureContext(projectId = s.projectId, workStreamId = s.id))
                }.padding(10.dp, 8.dp))
        }
    }
}
