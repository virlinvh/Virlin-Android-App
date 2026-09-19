package com.virlin.app.ui.link

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Link
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.ui.theme.VirlinColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val LinkEditorRoute = "link_editor"
fun linkEditorRoute(captureId: String?) =
    if (captureId.isNullOrBlank()) "link_editor" else "link_editor/$captureId"

const val LinkScreenTag = "link_editor_screen"
const val LinkUrlFieldTag = "link_url_field"
const val LinkCardTag = "link_card"
const val LinkOpenTag = "link_open"
const val LinkCopyTag = "link_copy"
const val LinkShareTag = "link_share"
const val LinkTitleTag = "link_title"
const val LinkNoteTag = "link_note"
const val LinkInboxActionTag = "link_inbox_action"
const val LinkSaveStatusTag = "link_save_status"

private val Paper = Color(0xFFFAF8F5)
private val Forest = Color(0xFF162016)
private val Hairline = Color(0x1F162016)
private val Mint = Color(0xFF0D9488)
private val Teal = Color(0xFF107C6F)
private val AquaCard = Color(0xFFE6F7F3)
private val AquaBorder = Color(0x33107C6F)

@Composable
fun LinkEditorScreen(
    navController: NavController,
    captureId: String?,
    vm: LinkEditorViewModel = viewModel(factory = LinkEditorViewModel.factory(captureId))
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

    fun openLink() {
        val url = state.canonicalUrl ?: return
        val intent = LinkIntents.viewIntent(url) ?: run {
            vm.markOpenFailed(); return
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            vm.markOpenFailed()
        }
    }

    fun shareLink() {
        val url = state.canonicalUrl ?: return
        val intent = LinkIntents.shareIntent(url)?.let {
            android.content.Intent.createChooser(it, "Share link")
        } ?: return
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            vm.markOpenFailed()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag(LinkScreenTag)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { goBack() }, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = Forest)
            }
            Column(Modifier.weight(1f)) {
                Text("Link", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Forest)
                val statusLine = when {
                    state.inboxBanner != null -> state.inboxBanner!!
                    state.committedToInbox && state.saveStatus == LinkSaveStatus.Saved -> "In Inbox ✓ · Saved ✓"
                    state.committedToInbox -> when (state.saveStatus) {
                        LinkSaveStatus.Saving -> "In Inbox · Saving…"
                        LinkSaveStatus.Error -> "In Inbox · Save issue"
                        else -> "In Inbox ✓"
                    }
                    state.saveStatus == LinkSaveStatus.Saving -> "Saving…"
                    state.saveStatus == LinkSaveStatus.Saved -> "Saved ✓"
                    state.saveStatus == LinkSaveStatus.Error -> "Save issue"
                    else -> "Draft"
                }
                Text(
                    statusLine,
                    fontSize = 11.sp,
                    color = when {
                        state.inboxBanner != null || state.committedToInbox -> Mint
                        state.saveStatus == LinkSaveStatus.Saved -> Mint
                        else -> VirlinColors.TextTertiary
                    },
                    modifier = Modifier.testTag(LinkSaveStatusTag)
                )
            }
            if (!state.committedToInbox) {
                Text(
                    "Save to Inbox",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Teal,
                    modifier = Modifier
                        .testTag(LinkInboxActionTag)
                        .semantics { contentDescription = "Save to Inbox" }
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            vm.saveToInbox()
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.semantics { contentDescription = "Link menu" }) {
                    Icon(Icons.Rounded.MoreVert, null, tint = Forest)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (state.canonicalUrl != null) {
                        DropdownMenuItem(
                            text = { Text("Edit link") },
                            onClick = { menuOpen = false; vm.startEditUrl() }
                        )
                        DropdownMenuItem(
                            text = { Text("Copy URL") },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(state.canonicalUrl!!))
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                vm.copyUrlFeedback()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            onClick = { menuOpen = false; shareLink() }
                        )
                    }
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
            color = if (state.contextLabel != null) Teal else VirlinColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        if (state.contextPickerOpen) {
            LinkContextPicker(vm)
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!state.showCard) {
                UrlInputField(state.urlInput, vm::onUrlInputChange)
                if (state.urlInput.isNotBlank() && state.canonicalUrl == null) {
                    Text(
                        "Enter a full http:// or https:// link",
                        fontSize = 12.sp,
                        color = VirlinColors.Amber,
                        modifier = Modifier.semantics { contentDescription = "Invalid link" }
                    )
                }
            } else {
                LinkCard(
                    title = state.displayTitle,
                    displayUrl = state.displayUrl,
                    onOpen = { openLink() },
                    onCopy = {
                        clipboard.setText(AnnotatedString(state.canonicalUrl!!))
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.copyUrlFeedback()
                    },
                    onShare = { shareLink() }
                )
            }

            if (state.canonicalUrl != null) {
                BasicTextField(
                    value = state.title,
                    onValueChange = vm::onTitleChange,
                    textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Forest),
                    cursorBrush = SolidColor(Teal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(LinkTitleTag)
                        .semantics { contentDescription = "Link title" },
                    decorationBox = { inner ->
                        Box {
                            if (state.title.isEmpty()) {
                                Text("Custom title (optional)", fontSize = 16.sp, color = Color(0xFF9CA3AF))
                            }
                            inner()
                        }
                    }
                )
                BasicTextField(
                    value = state.note,
                    onValueChange = vm::onNoteChange,
                    textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF4B5563), lineHeight = 20.sp),
                    cursorBrush = SolidColor(Teal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(LinkNoteTag)
                        .semantics { contentDescription = "Link note" },
                    decorationBox = { inner ->
                        Box {
                            if (state.note.isEmpty()) {
                                Text("Add a note…", fontSize = 14.sp, color = Color(0xFF9CA3AF))
                            }
                            inner()
                        }
                    }
                )
            }

            Spacer(Modifier.height(40.dp))
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
private fun UrlInputField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(fontSize = 16.sp, color = Forest),
        cursorBrush = SolidColor(Teal),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag(LinkUrlFieldTag)
            .semantics { contentDescription = "Paste a link" },
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text("Paste a link…", fontSize = 16.sp, color = Color(0xFF9CA3AF))
                }
                inner()
            }
        }
    )
}

@Composable
private fun LinkCard(
    title: String,
    displayUrl: String,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AquaCard, RoundedCornerShape(18.dp))
            .border(1.dp, AquaBorder, RoundedCornerShape(18.dp))
            .testTag(LinkCardTag)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Link, contentDescription = null, tint = Teal, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Forest,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    displayUrl,
                    fontSize = 13.sp,
                    color = Teal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "OPEN LINK ↗",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier
                    .background(Teal, RoundedCornerShape(12.dp))
                    .testTag(LinkOpenTag)
                    .semantics { contentDescription = "Open link in browser" }
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
            Text(
                "Copy",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Teal,
                modifier = Modifier
                    .border(1.dp, AquaBorder, RoundedCornerShape(12.dp))
                    .testTag(LinkCopyTag)
                    .semantics { contentDescription = "Copy URL" }
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Text(
                "Share",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Teal,
                modifier = Modifier
                    .border(1.dp, AquaBorder, RoundedCornerShape(12.dp))
                    .testTag(LinkShareTag)
                    .semantics { contentDescription = "Share URL" }
                    .clickable(onClick = onShare)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun LinkContextPicker(vm: LinkEditorViewModel) {
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
        Text("Global · Inbox", fontSize = 13.sp, color = Forest,
            modifier = Modifier.fillMaxWidth().clickable { vm.setContext(CaptureContext.None) }.padding(10.dp, 8.dp))
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
