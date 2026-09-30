package com.virlin.app.ui.link

import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.capture.LinkDocument
import com.virlin.app.domain.capture.LinkPresentation
import com.virlin.app.domain.capture.LinkProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

const val LinkEditorRoute = "link_editor"
fun linkEditorRoute(captureId: String?) = if (captureId.isNullOrBlank()) LinkEditorRoute else "$LinkEditorRoute/$captureId"
fun linkEditorForProject(projectId: String) = "$LinkEditorRoute/new/project/$projectId"
fun linkEditorForTask(taskId: String) = "$LinkEditorRoute/new/task/$taskId"

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
const val LinkInlinePlayTag = "link_inline_play"
const val LinkInlinePlayerTag = "link_inline_player"

private val Paper = Color(0xFFFAF9F7)
private val Ink = Color(0xFF17221D)
private val Green = Color(0xFF087F55)
private val Mint = Color(0xFFEAF7F1)
private val Line = Color(0xFFD8E4DE)
private val Muted = Color(0xFF66756E)
private val Danger = Color(0xFFB3261E)

@Composable
fun LinkEditorScreen(
    navController: NavController,
    captureId: String?,
    initialContext: CaptureContext = CaptureContext.None,
    vm: LinkEditorViewModel = viewModel(factory = LinkEditorViewModel.factory(captureId, initialContext)),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    fun openLink() {
        val url = state.canonicalUrl ?: return
        val target = LinkPresentation.playableUrl(url, state.document)
        val intent = LinkIntents.viewIntent(target) ?: return vm.markOpenFailed()
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { vm.markOpenFailed() }
    }
    fun goBack() {
        if (state.editing && state.committedToInbox) vm.cancelEdit() else navController.popBackStack()
    }
    BackHandler { goBack() }
    LaunchedEffect(state.toast) { if (state.toast != null) { delay(1800); vm.clearToast() } }

    Column(
        Modifier.fillMaxSize().background(Paper).statusBarsPadding().navigationBarsPadding()
            .imePadding().testTag(LinkScreenTag)
    ) {
        LinkHeader(
            title = if (state.editing && state.committedToInbox) "Edit link" else "Link",
            subtitle = if (!state.editing && state.committedToInbox) "Saved" else null,
            editing = state.editing,
            committed = state.committedToInbox,
            onBack = ::goBack,
            onEdit = vm::edit,
            onCancel = vm::cancelEdit,
        )
        state.contextLabel?.let { ContextStrip(it) }

        if (state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Green)
        } else if (state.editing) {
            LinkEditPage(state, vm, Modifier.weight(1f))
            EditActions(
                canSave = state.canSave,
                existing = state.committedToInbox,
                saving = state.saveStatus == LinkSaveStatus.Saving,
                onDelete = { vm.archive { navController.popBackStack() } },
                onSave = vm::save,
            )
        } else {
            LinkDetailPage(state, Modifier.weight(1f))
            DetailActions(
                onCopy = {
                    state.canonicalUrl?.let { clipboard.setText(AnnotatedString(it)); vm.copyUrlFeedback() }
                },
                onOpen = ::openLink,
            )
        }
        state.toast?.let { ToastBar(it) }
    }
}

@Composable private fun LinkHeader(
    title: String, subtitle: String?, editing: Boolean, committed: Boolean,
    onBack: () -> Unit, onEdit: () -> Unit, onCancel: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Ink) }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
            subtitle?.let { Text(it, fontSize = 12.sp, color = Muted, modifier = Modifier.testTag(LinkSaveStatusTag)) }
        }
        if (editing && committed) TextButton(onClick = onCancel) { Text("Cancel", color = Green, fontWeight = FontWeight.SemiBold) }
        if (!editing && committed) IconButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = "Edit link" }) {
            Icon(Icons.Outlined.Edit, null, tint = Green, modifier = Modifier.size(21.dp))
        }
        IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, "More options", tint = Ink) }
    }
}

@Composable private fun ContextStrip(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp)
            .background(Color(0xFFF0F5F2), RoundedCornerShape(14.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Link, null, tint = Green, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp)); Text(label, color = Ink, fontSize = 13.sp)
    }
}

@Composable private fun LinkEditPage(state: LinkUiState, vm: LinkEditorViewModel, modifier: Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard {
            Text("Paste a link", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            OutlinedTextField(
                value = state.urlInput, onValueChange = vm::onUrlInputChange,
                modifier = Modifier.fillMaxWidth().testTag(LinkUrlFieldTag), singleLine = true,
                placeholder = { Text("https://…") }, trailingIcon = {
                    if (state.urlInput.isNotEmpty()) IconButton(onClick = { vm.onUrlInputChange("") }) { Icon(Icons.Outlined.Close, "Clear") }
                }, colors = fieldColors(), shape = RoundedCornerShape(10.dp),
            )
            if (state.urlInput.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.canonicalUrl != null) Icons.Rounded.CheckCircle else Icons.Outlined.ErrorOutline,
                    null, tint = if (state.canonicalUrl != null) Green else Danger, modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(7.dp)); Text(
                    if (state.canonicalUrl != null) "Valid ${providerName(state.preview?.provider)} link" else "Enter a valid http:// or https:// link",
                    color = if (state.canonicalUrl != null) Green else Danger, fontSize = 12.sp,
                )
            }
        }

        SettingCard("Show preview", "Show a thumbnail or cover from the link", state.showPreview, vm::setShowPreview)
        if (state.canonicalUrl != null && state.showPreview) PreviewCard(state)

        if (state.canonicalUrl != null) {
            OutlinedTextField(state.title, vm::onTitleChange, Modifier.fillMaxWidth().testTag(LinkTitleTag),
                label = { Text("Title (optional)") }, colors = fieldColors(), shape = RoundedCornerShape(12.dp))
            OutlinedTextField(state.note, vm::onNoteChange, Modifier.fillMaxWidth().testTag(LinkNoteTag),
                label = { Text("Note (optional)") }, minLines = 2, colors = fieldColors(), shape = RoundedCornerShape(12.dp))
        }

        if (state.supportsPlayback) {
            SectionCard {
                SettingRow("Playback segment", "Open only the part you need", state.playbackEnabled, vm::setPlaybackEnabled)
                if (state.playbackEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeField("Start at", state.startInput, vm::onStartChange, Modifier.weight(1f))
                        TimeField("End / exit at", state.endInput, vm::onEndChange, Modifier.weight(1f))
                    }
                    Text(
                        if (state.preview?.provider == LinkProvider.YOUTUBE)
                            "The start time is added to YouTube. End behavior depends on YouTube."
                        else "Timing support depends on the destination video player.",
                        fontSize = 11.sp, color = Muted,
                    )
                    if (!state.isTimeValid) Text("End time must be later than the start time.", fontSize = 12.sp, color = Danger)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun LinkDetailPage(state: LinkUiState, modifier: Modifier) {
    val youtubeId = state.canonicalUrl?.let(LinkPresentation::youtubeVideoId)
    var inlinePlaying by remember(state.canonicalUrl) { mutableStateOf(false) }
    var inlineFailed by remember(state.canonicalUrl) { mutableStateOf(false) }
    var inlineReady by remember(state.canonicalUrl) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        if (state.showPreview && youtubeId != null && inlinePlaying && !inlineFailed) {
            YouTubeInlinePlayer(
                videoId = youtubeId,
                startSeconds = state.document.startSeconds ?: 0,
                endSeconds = state.document.endSeconds,
                onReady = { inlineReady = true },
                onFailed = { inlineFailed = true; inlinePlaying = false; inlineReady = false },
                modifier = Modifier.testTag(LinkInlinePlayerTag),
            )
            Text(
                if (!inlineReady) "Loading YouTube player…"
                else if (state.document.playbackEnabled) "Saved segment ready in Virlin"
                else "Ready to play in Virlin",
                color = Muted, fontSize = 12.sp,
            )
        } else if (state.showPreview) PreviewCard(
            state = state,
            canPlayInline = youtubeId != null && !inlineFailed,
            onPlayInline = { inlinePlaying = true },
        )
        if (inlineFailed) Text(
            "This video cannot be embedded. Use Open link to watch it in YouTube.",
            color = Muted, fontSize = 12.sp,
        )
        Text(state.displayTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(providerName(state.preview?.provider), fontSize = 13.sp, color = Muted)
        if (state.note.isNotBlank()) Text(state.note, fontSize = 14.sp, color = Muted, lineHeight = 20.sp)
        if (state.document.playbackEnabled) {
            Row(Modifier.fillMaxWidth().background(Mint, RoundedCornerShape(12.dp)).padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PlayCircle, null, tint = Green)
                Spacer(Modifier.width(9.dp)); Text(
                    "Plays ${LinkPresentation.formatTime(state.document.startSeconds)}–${state.document.endSeconds?.let(LinkPresentation::formatTime) ?: "end"}",
                    fontWeight = FontWeight.SemiBold, color = Ink,
                )
            }
        }
        Row(Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Link, null, tint = Green); Spacer(Modifier.width(10.dp))
            Text(state.canonicalUrl.orEmpty(), Modifier.weight(1f), fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule, null, tint = Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Saved", fontSize = 12.sp, color = Muted)
        }
    }
}

@Composable private fun PreviewCard(
    state: LinkUiState,
    canPlayInline: Boolean = false,
    onPlayInline: () -> Unit = {},
) {
    val preview = state.preview ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, Line, RoundedCornerShape(16.dp)).testTag(LinkCardTag)) {
        val thumb = preview.thumbnailUrl
        if (thumb != null) Box(
            Modifier.fillMaxWidth().height(190.dp)
                .then(if (canPlayInline) Modifier.clickable(onClick = onPlayInline) else Modifier)
        ) {
            RemoteImage(thumb, Modifier.matchParentSize())
            if (canPlayInline) Box(
                Modifier.align(Alignment.Center).size(62.dp).clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.94f)).testTag(LinkInlinePlayTag)
                    .semantics { contentDescription = "Play video here" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PlayArrow, null, tint = Green, modifier = Modifier.size(38.dp))
            }
        }
        else Box(Modifier.fillMaxWidth().height(130.dp).background(Mint), contentAlignment = Alignment.Center) {
            Icon(if (preview.provider == LinkProvider.WEBSITE) Icons.Outlined.Language else Icons.Outlined.Videocam,
                null, tint = Green, modifier = Modifier.size(44.dp))
        }
        Column(Modifier.padding(14.dp)) {
            Text(state.displayTitle, color = Ink, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(preview.host, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable private fun RemoteImage(url: String, modifier: Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000; connection.readTimeout = 7_000
                connection.instanceFollowRedirects = true; connection.setRequestProperty("User-Agent", "Virlin/1.0")
                connection.inputStream.use(BitmapFactory::decodeStream).also { connection.disconnect() }
            }.getOrNull()
        }
    }
    Box(modifier.background(Mint), contentAlignment = Alignment.Center) {
        if (bitmap == null) CircularProgressIndicator(color = Green, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        else Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable private fun SettingCard(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) = SectionCard {
    SettingRow(title, subtitle, checked, onChecked)
}

@Composable private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = Ink, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, fontSize = 12.sp) }
        Switch(checked, onChecked, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Green))
    }
}

@Composable private fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, { if (it.length <= 6) onChange(it) }, modifier, label = { Text(label) }, placeholder = { Text("00:00") },
        singleLine = true, leadingIcon = { Icon(Icons.Outlined.Schedule, null) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = fieldColors(), shape = RoundedCornerShape(11.dp))
}

@Composable private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(15.dp)).border(1.dp, Line, RoundedCornerShape(15.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable private fun EditActions(canSave: Boolean, existing: Boolean, saving: Boolean, onDelete: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (existing) OutlinedButton(onDelete, Modifier.weight(1f).height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) {
            Icon(Icons.Outlined.Delete, null); Spacer(Modifier.width(6.dp)); Text("Delete link")
        }
        Button(onSave, Modifier.weight(1f).height(52.dp).testTag(LinkInboxActionTag), enabled = canSave && !saving,
            colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(13.dp)) {
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Text(if (existing) "Save changes" else "Save link", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun DetailActions(onCopy: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onCopy, Modifier.weight(1f).height(52.dp).testTag(LinkCopyTag), shape = RoundedCornerShape(13.dp)) {
            Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(7.dp)); Text("Copy link")
        }
        Button(onOpen, Modifier.weight(1f).height(52.dp).testTag(LinkOpenTag), colors = ButtonDefaults.buttonColors(containerColor = Green), shape = RoundedCornerShape(13.dp)) {
            Text("Open link", fontWeight = FontWeight.Bold); Spacer(Modifier.width(7.dp)); Icon(Icons.Outlined.OpenInNew, null)
        }
    }
}

@Composable private fun ToastBar(message: String) {
    Row(Modifier.fillMaxWidth().background(Color(0xFFDDF5E9)).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, null, tint = Green); Spacer(Modifier.width(9.dp)); Text(message, color = Ink, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Green, unfocusedBorderColor = Line, focusedLabelColor = Green,
    cursorColor = Green, focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
)

private fun providerName(provider: LinkProvider?): String = when (provider) {
    LinkProvider.YOUTUBE -> "YouTube"
    LinkProvider.INSTAGRAM -> "Instagram"
    LinkProvider.VIDEO -> "Video"
    LinkProvider.WEBSITE -> "Website"
    null -> "Web"
}
