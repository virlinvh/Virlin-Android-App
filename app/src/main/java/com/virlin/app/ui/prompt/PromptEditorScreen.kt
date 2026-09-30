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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.model.PromptContentMode
import com.virlin.app.domain.prompt.PromptContentParser
import com.virlin.app.domain.prompt.ResponseSegment
import com.virlin.app.ui.note.ClipboardNoteReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val PromptRoute = "prompt_editor"
fun promptEditorRoute(captureId: String?) =
    if (captureId.isNullOrBlank()) "prompt_editor" else "prompt_editor/$captureId"
fun promptEditorForTask(taskId: String) = "prompt_editor/new/$taskId"

const val PromptScreenTag = "prompt_editor_screen"
const val PromptTitleTag = "prompt_title"
const val PromptBodyCopyTag = "prompt_body_copy"
const val PromptInboxActionTag = "prompt_inbox_action"
const val PromptSaveStatusTag = "prompt_save_status"
const val PromptPasteTag = "prompt_paste"
const val PromptModeTag = "prompt_mode_prompt"
const val CodeModeTag = "prompt_mode_code"
const val PromptSourceTag = "prompt_source_editor"
const val PromptAddResponseTag = "prompt_add_response"
const val PromptResponseEditorTag = "prompt_response_editor"

private val Paper = Color(0xFFFAF9F6)
private val White = Color.White
private val Ink = Color(0xFF142019)
private val Muted = Color(0xFF66736B)
private val Green = Color(0xFF078653)
private val Mint = Color(0xFFE7F7EF)
private val Line = Color(0xFFD9E3DD)
private val CodeSurface = Color(0xFF202A27)
private val CodeInk = Color(0xFFF1F7F3)
private val languages = listOf("Kotlin", "Java", "TypeScript", "JavaScript", "Python", "SQL", "JSON", "HTML", "CSS", "Shell", "Plain text")

@Composable
fun PromptEditorScreen(
    navController: NavController,
    captureId: String?,
    initialContext: CaptureContext = CaptureContext.None,
    vm: PromptEditorViewModel = viewModel(factory = PromptEditorViewModel.factory(captureId, initialContext))
) {
    val state by vm.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }

    fun goBack() {
        scope.launch { if (vm.prepareExit().navigate) navController.popBackStack() }
    }
    fun pasteSource() {
        val clip = ClipboardNoteReader.read(context).plain
        if (clip.isNotEmpty()) vm.onSourceTextChange(clip)
    }
    fun pasteResponse() {
        val clip = ClipboardNoteReader.read(context).plain
        if (clip.isNotEmpty()) vm.onResponseTextChange(clip)
    }

    BackHandler { goBack() }
    LaunchedEffect(state.toast) {
        if (state.toast != null) { delay(1400); vm.clearToast() }
    }

    Column(
        Modifier.fillMaxSize().background(Paper).statusBarsPadding().navigationBarsPadding().imePadding()
            .testTag(PromptScreenTag)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Ink) }
            Column(Modifier.weight(1f)) {
                Text("Prompt workspace", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Ink,
                    modifier = Modifier.testTag(PromptTitleTag))
                Text(
                    if (!state.committedToInbox) "Draft" else when (state.saveStatus) {
                        PromptSaveStatus.Saving -> "Saving…"
                        PromptSaveStatus.Error -> "Save issue"
                        PromptSaveStatus.Saved -> "Saved ✓"
                        else -> if (state.committedToInbox) "In Inbox" else "Draft"
                    },
                    fontSize = 11.sp,
                    color = if (state.saveStatus == PromptSaveStatus.Error) Color(0xFFB42318) else Green,
                    modifier = Modifier.testTag(PromptSaveStatusTag)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "Prompt menu", tint = Ink) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Paste source") }, onClick = { menuOpen = false; pasteSource() }, modifier = Modifier.testTag(PromptPasteTag))
                    DropdownMenuItem(text = { Text("Copy source") }, onClick = {
                        menuOpen = false; clipboard.setText(AnnotatedString(vm.copyPromptPlainText()))
                    })
                    DropdownMenuItem(text = { Text("Choose context") }, onClick = { menuOpen = false; vm.openContextPicker(true) })
                    DropdownMenuItem(text = { Text("Archive") }, onClick = {
                        menuOpen = false; vm.archiveAndExit { navController.popBackStack() }
                    })
                }
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModeSwitch(state.mode, vm::setMode)
            ContextChip(state.contextLabel ?: "Global · Inbox") { vm.openContextPicker(true) }
            if (state.contextPickerOpen) PromptContextPicker(vm)

            if (state.mode == PromptContentMode.CODE) {
                LanguagePicker(
                    selected = state.language ?: PromptContentParser.detectLanguage(state.sourceText),
                    detected = state.language == null,
                    onSelect = vm::setLanguage
                )
            }

            SourceEditor(state, vm, onPaste = ::pasteSource, onCopy = {
                clipboard.setText(AnnotatedString(vm.copyPromptPlainText()))
            })

            if (state.responseText.isBlank() && !state.responseEditorOpen) {
                OutlineAction(Icons.Rounded.Add, "Add response", Modifier.testTag(PromptAddResponseTag)) {
                    vm.openResponseEditor()
                }
            }

            if (state.responseEditorOpen) {
                ResponsePasteEditor(state.responseText, vm::onResponseTextChange, onPaste = ::pasteResponse) {
                    vm.openResponseEditor(false)
                }
            } else if (state.responseText.isNotBlank()) {
                ResponseViewer(
                    response = state.responseText,
                    copyAll = { clipboard.setText(AnnotatedString(vm.copyResponseText())) },
                    copyBlock = { clipboard.setText(AnnotatedString(it)) },
                    replace = { vm.openResponseEditor(true) }
                )
            }

            InfoStrip(if (state.mode == PromptContentMode.CODE) "Formatting never changes your source" else "Saved exactly as pasted")
            Spacer(Modifier.height(6.dp))
        }

        state.toast?.let {
            Text(it, color = Green, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(4.dp))
        }
        Text(
            if (state.responseText.isNotBlank()) "Save response" else "Save",
            color = White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                .background(Green, RoundedCornerShape(14.dp)).clickable { vm.saveToInbox() }
                .padding(vertical = 15.dp).testTag(PromptInboxActionTag),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun ModeSwitch(mode: PromptContentMode, onMode: (PromptContentMode) -> Unit) {
    Row(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(14.dp)).padding(2.dp)) {
        ModeOption(Icons.Rounded.Description, "Prompt", mode == PromptContentMode.PROMPT, Modifier.weight(1f).testTag(PromptModeTag)) { onMode(PromptContentMode.PROMPT) }
        ModeOption(Icons.Rounded.Code, "Code", mode == PromptContentMode.CODE, Modifier.weight(1f).testTag(CodeModeTag)) { onMode(PromptContentMode.CODE) }
    }
}

@Composable
private fun ModeOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.background(if (selected) Mint else Color.Transparent, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (selected) Green else Muted, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Green else Ink)
    }
}

@Composable
private fun ContextChip(label: String, onClick: () -> Unit) {
    Row(Modifier.background(Mint, RoundedCornerShape(30.dp)).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Folder, null, tint = Green, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(label, color = Ink, fontSize = 13.sp)
    }
}

@Composable
private fun LanguagePicker(selected: String, detected: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().background(White, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp)).clickable { open = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Code, null, tint = Green); Spacer(Modifier.width(10.dp)); Text(selected, color = Ink, fontWeight = FontWeight.SemiBold)
            if (detected) Text("Detected", color = Green, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp).background(Mint, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f)); Text("⌄", color = Muted, fontSize = 18.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            languages.forEach { language -> DropdownMenuItem(text = { Text(language) }, onClick = { open = false; onSelect(language) }) }
        }
    }
}

@Composable
private fun SourceEditor(state: PromptUiState, vm: PromptEditorViewModel, onPaste: () -> Unit, onCopy: () -> Unit) {
    var wrap by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))) {
        BasicTextField(
            value = state.sourceText,
            onValueChange = vm::onSourceTextChange,
            textStyle = TextStyle(color = Ink, fontSize = 14.sp, lineHeight = 21.sp, fontFamily = if (state.mode == PromptContentMode.CODE) FontFamily.Monospace else FontFamily.Default),
            cursorBrush = SolidColor(Green),
            visualTransformation = if (state.mode == PromptContentMode.CODE) CodeHighlightTransformation else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(16.dp).testTag(PromptSourceTag).semantics { contentDescription = "Prompt source editor" },
            decorationBox = { inner -> Box { if (state.sourceText.isEmpty()) Text(if (state.mode == PromptContentMode.CODE) "Paste or write your code…" else "Paste or write your prompt…", color = Color(0xFF8A9690), fontSize = 14.sp); inner() } }
        )
        Row(Modifier.fillMaxWidth().border(1.dp, Line).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Utility(Icons.Rounded.ContentPaste, "Paste", onPaste); Utility(Icons.Rounded.ContentCopy, "Copy", onCopy, Modifier.testTag(PromptBodyCopyTag))
            Spacer(Modifier.weight(1f))
            if (state.mode == PromptContentMode.CODE) {
                Text("Wrap lines", color = Muted, fontSize = 12.sp); Switch(checked = wrap, onCheckedChange = { wrap = it }, modifier = Modifier.height(30.dp))
            } else Text("${state.sourceText.length} characters", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Utility(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clickable(onClick = onClick).padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = Muted, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text(label, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ResponsePasteEditor(value: String, onChange: (String) -> Unit, onPaste: () -> Unit, done: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Response", color = Ink, fontWeight = FontWeight.Bold, fontSize = 19.sp); Text("Pasted by you", color = Green, fontSize = 11.sp, modifier = Modifier.padding(start = 9.dp).background(Mint, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp)) }
        Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp))) {
            BasicTextField(value, onChange, textStyle = TextStyle(color = Ink, fontSize = 14.sp, lineHeight = 21.sp), cursorBrush = SolidColor(Green),
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(16.dp).testTag(PromptResponseEditorTag),
                decorationBox = { inner -> Box { if (value.isEmpty()) Text("Paste the response you received…", color = Color(0xFF8A9690)); inner() } })
            Row(Modifier.fillMaxWidth().border(1.dp, Line).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Utility(Icons.Rounded.ContentPaste, "Paste", onPaste); Spacer(Modifier.weight(1f)); Text("Done", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = done).padding(8.dp))
            }
        }
    }
}

@Composable
private fun ResponseViewer(response: String, copyAll: () -> Unit, copyBlock: (String) -> Unit, replace: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Response", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Pasted", color = Green, fontSize = 11.sp, modifier = Modifier.padding(start = 9.dp).background(Mint, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 4.dp))
            Spacer(Modifier.weight(1f)); Utility(Icons.Rounded.ContentCopy, "Copy all", copyAll)
        }
        PromptContentParser.responseSegments(response).forEach { segment ->
            when (segment) {
                is ResponseSegment.Prose -> Text(segment.text, color = Ink, fontSize = 14.sp, lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth().background(White, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(14.dp))
                is ResponseSegment.Code -> CodeBlock(segment, copyBlock)
            }
        }
        OutlineAction(Icons.Rounded.Refresh, "Replace response", onClick = replace)
    }
}

@Composable
private fun CodeBlock(segment: ResponseSegment.Code, copyBlock: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(CodeSurface, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(segment.language ?: "Code", color = CodeInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp); Spacer(Modifier.weight(1f))
            Row(Modifier.clickable { copyBlock(segment.text) }, verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.ContentCopy, "Copy code", tint = CodeInk, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("Copy", color = CodeInk, fontSize = 12.sp) }
        }
        Text(segment.text, color = CodeInk, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 14.dp).horizontalScroll(rememberScrollState()))
    }
}

@Composable
private fun OutlineAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().border(1.dp, Green, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Green, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(label, color = Green, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoStrip(text: String) {
    Row(Modifier.fillMaxWidth().background(Color(0xFFF0F4F1), RoundedCornerShape(14.dp)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Info, null, tint = Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(text, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PromptContextPicker(vm: PromptEditorViewModel) {
    val projects = VirlinGraph.repository.projects.value
    val streams = VirlinGraph.repository.streams.value
    Column(Modifier.fillMaxWidth().background(White, RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(8.dp)) {
        Text("Global · Inbox", color = Ink, modifier = Modifier.fillMaxWidth().clickable { vm.setContext(CaptureContext.None) }.padding(10.dp))
        projects.forEach { p -> Text(p.title, color = Ink, modifier = Modifier.fillMaxWidth().clickable { vm.setContext(CaptureContext(projectId = p.id)) }.padding(10.dp)) }
        streams.forEach { s -> Text(s.title, color = Ink, modifier = Modifier.fillMaxWidth().clickable { vm.setContext(CaptureContext(projectId = s.projectId, workStreamId = s.id)) }.padding(10.dp)) }
    }
}

private object CodeHighlightTransformation : VisualTransformation {
    private val keywords = Regex("\\b(fun|val|var|class|object|interface|return|if|else|when|for|while|import|package|const|let|def|public|private|data|null|true|false)\\b")
    private val strings = Regex("\"(?:\\\\.|[^\"\\\\])*\"")
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            keywords.findAll(text.text).forEach { addStyle(SpanStyle(color = Color(0xFF6D4CC3), fontWeight = FontWeight.SemiBold), it.range.first, it.range.last + 1) }
            strings.findAll(text.text).forEach { addStyle(SpanStyle(color = Color(0xFF087A65)), it.range.first, it.range.last + 1) }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
