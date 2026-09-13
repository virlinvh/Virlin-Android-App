package com.virlin.app.ui.agent.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.action.CaptureTaskTarget
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.ui.theme.VirlinColors

/**
 * CAPTURE mode content inside the frozen Agent shell (Pass 10). The pinned composer IS the
 * capture field (its "SAVE TO INBOX" action persists the text); this area holds the type
 * chips, the explicit optional context, the Inbox (preview rows, newest first) and a
 * lightweight detail (full content · copy · organize · archive). Pure rendering of
 * [AgentCaptureState]; every tap is a ViewModel intent. No parsing, no execution.
 */
private val Hairline = Color(0x1F162016)

const val AgentCaptureTag = "agent_capture"
const val CaptureFeedbackTag = "capture_feedback"
const val CaptureErrorTag = "capture_error"
const val CaptureContextLineTag = "capture_context_line"
const val CaptureAttachCurrentTag = "capture_attach_current"
const val CaptureChooseContextTag = "capture_choose_context"
const val CaptureClearContextTag = "capture_clear_context"
const val CaptureLinkNoteTag = "capture_link_note"
const val CaptureInboxTag = "capture_inbox"
const val CaptureDetailTag = "capture_detail"
const val CaptureDetailContentTag = "capture_detail_content"
const val CaptureCopyTag = "capture_copy"
const val CaptureOrganizeTag = "capture_organize"
const val CaptureArchiveTag = "capture_archive"
const val CaptureRestoreTag = "capture_restore"
const val CaptureCloseTag = "capture_close"
const val CaptureConvertTag = "capture_convert"
fun captureTypeTag(t: CaptureType) = "capture_type_${t.name.lowercase()}"
fun captureFilterTag(f: CaptureFilter) = "capture_filter_${f.name.lowercase()}"
fun captureRowTag(id: String) = "capture_row_$id"
fun captureContextStreamTag(id: String) = "capture_ctx_stream_$id"
fun captureContextProjectTag(id: String) = "capture_ctx_project_$id"
fun captureConvertStreamTag(id: String) = "capture_convert_stream_$id"
fun captureConvertProjectTag(id: String) = "capture_convert_project_$id"

@Composable
fun AgentCaptureArea(vm: AgentCaptureViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val f = state.form
    Column(modifier = modifier.fillMaxWidth().testTag(AgentCaptureTag)) {
        state.selected?.let { CaptureInbox(vm); return@Column }

        // ---- Stitch Capture UI: five cards in the reference order. The first three ARE the existing CaptureType
        // selector (NOTE default). File / Image and Voice are shown as designed but have NO backend yet (no picker,
        // no recorder, no storage): they are disabled and never mutate anything — no fake captures.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CaptureType.values().forEach { t -> CaptureCard(lookOf(t), tag = captureTypeTag(t), selected = f.type == t, enabled = true) { vm.setType(t) } }
            CaptureCard(FileImageLook, tag = CaptureFileImageTag, selected = false, enabled = false) {}
            CaptureCard(VoiceLook, tag = CaptureVoiceTag, selected = false, enabled = false) {}
        }
        Text(
            when (f.type) {
                CaptureType.NOTE -> "Type below, then SAVE TO INBOX. Nothing to file."
                CaptureType.PROMPT -> "Paste the prompt below. Stored exactly as written — never run."
                CaptureType.LINK -> "Paste the URL below. Stored, never fetched."
            }, fontSize = 11.sp, color = VirlinColors.TextSecondary, modifier = Modifier.padding(top = 6.dp)
        )
        if (f.type == CaptureType.LINK) {
            Spacer(Modifier.height(8.dp))
            Field(f.linkNote, vm::setLinkNote, "Optional note for this link", CaptureLinkNoteTag)
        }

        // ---- explicit optional context (always visible: the user knows if a capture is global)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.contextLabel != null) "Attached to · ${state.contextLabel}" else "Global · Inbox",
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (state.contextLabel != null) VirlinColors.Emerald else VirlinColors.TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag(CaptureContextLineTag)
            )
            if (state.contextLabel != null) { Chip("CLEAR", CaptureClearContextTag, primary = false) { vm.clearContext() }; Spacer(Modifier.width(6.dp)) }
            Chip(if (f.contextPickerOpen) "HIDE" else "CHOOSE CONTEXT", CaptureChooseContextTag, primary = false) { vm.toggleContextPicker() }
        }
        state.currentFocus?.let { ws ->
            if (f.context.workStreamId != ws.id) {
                Spacer(Modifier.height(6.dp))
                Chip("ATTACH TO CURRENT · ${ws.title.uppercase()}", CaptureAttachCurrentTag, primary = false) { vm.attachToCurrentFocus() }
            }
        }
        if (f.contextPickerOpen) {
            Spacer(Modifier.height(8.dp))
            ContextPicker(state, streamTag = ::captureContextStreamTag, projectTag = ::captureContextProjectTag,
                onStream = { ws -> vm.setContext(CaptureContextChoice(projectId = ws.projectId, workStreamId = ws.id)) },
                onProject = { p -> vm.setContext(CaptureContextChoice(projectId = p.id)) })
        }
        f.feedback?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.Emerald, modifier = Modifier.testTag(CaptureFeedbackTag).padding(top = 8.dp)) }
        f.error?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.Amber, modifier = Modifier.testTag(CaptureErrorTag).padding(top = 8.dp)) }

        // ---- inbox (shared with the root Inbox tab)
        Spacer(Modifier.height(14.dp))
        CaptureInbox(vm)
    }
}

// ---------------------------------------------------------------- Capture cards (Stitch)

private data class CardLook(val title: String, val subtitle: String, val tile: Color, val icon: ImageVector, val tint: Color)

private fun lookOf(t: CaptureType) = when (t) {
    CaptureType.NOTE -> CardLook("Text Note", "Quick thought or idea", Color(0xFFE8F8F5), Icons.Rounded.Description, Color(0xFF0D9488))   // mint / teal
    CaptureType.PROMPT -> CardLook("Prompt", "Save a prompt", Color(0xFFF3EBFC), Icons.Rounded.AutoAwesome, Color(0xFF7E57C2))          // soft lavender (restrained)
    CaptureType.LINK -> CardLook("Link", "Save a web link", Color(0xFFE6F7F3), Icons.Rounded.Link, Color(0xFF107C6F))                 // seafoam
}

/** Designed but not yet backed: no file/image picker or storage and no audio recorder exist in the app. */
private val FileImageLook = CardLook("File / Image", "Attach a file or photo", Color(0xFFE8F6F8), Icons.Rounded.AttachFile, Color(0xFF0E7490))
private val VoiceLook = CardLook("Voice", "Record a voice note", Color(0xFFE9F2FE), Icons.Rounded.Mic, Color(0xFF1D63ED))
const val CaptureFileImageTag = "capture_type_file_image"
const val CaptureVoiceTag = "capture_type_voice"

private val CardBorder = Color(0xFFF1F1F1)
private val Neutral400 = Color(0xFF9CA3AF)
private val Neutral900 = Color(0xFF111827)

@Composable
private fun CaptureCard(l: CardLook, tag: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .scale(if (pressed) 0.985f else 1f)
            .background(Color.White, RoundedCornerShape(18.dp))
            .border(1.dp, if (selected) VirlinColors.Emerald.copy(alpha = 0.45f) else CardBorder, RoundedCornerShape(18.dp))
            .testTag(tag)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = "${l.title}, ${l.subtitle}" + (if (selected) ", selected" else "")
                this.selected = selected
                if (!enabled) { disabled(); stateDescription = "Not available yet" }
            }
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
    ) {
        Box(Modifier.size(48.dp).background(l.tile, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(l.icon, contentDescription = null, tint = l.tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Neutral900)
            Text(l.subtitle, fontSize = 13.sp, color = VirlinColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Neutral400, modifier = Modifier.size(16.dp))
    }
}

/**
 * The capture Inbox itself: filter chips, rows, and the detail (organize / archive / restore /
 * convert) once a row is selected. ONE implementation, used by the Agent CAPTURE area and by the
 * root Inbox destination; both observe the same repository through [AgentCaptureViewModel].
 */
@Composable
fun CaptureInbox(vm: AgentCaptureViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val f = state.form
    Column(modifier = modifier.fillMaxWidth()) {
        state.selected?.let { Detail(state, vm); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaptureFilter.values().forEach { flt ->
                val label = when (flt) { CaptureFilter.INBOX -> "INBOX · ${state.inboxCount}"; CaptureFilter.ORGANIZED -> "ORGANIZED"; CaptureFilter.ARCHIVED -> "ARCHIVED" }
                Choice(label, captureFilterTag(flt), selected = f.filter == flt, small = true) { vm.setFilter(flt) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.fillMaxWidth().testTag(CaptureInboxTag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.rows.isEmpty()) Text(
                when (f.filter) { CaptureFilter.INBOX -> "Inbox is empty. Anything you save lands here."; CaptureFilter.ORGANIZED -> "Nothing organized yet."; CaptureFilter.ARCHIVED -> "Nothing archived." },
                fontSize = 12.sp, color = VirlinColors.TextSecondary
            )
            state.rows.forEach { row -> InboxRow(row) { vm.select(row.item.id) } }
        }
    }
}

@Composable
private fun InboxRow(row: CaptureRow, onOpen: () -> Unit) {
    val kind = CapturePresentation.typeLabel(row.item.type)
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp)).border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .testTag(captureRowTag(row.item.id))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = listOfNotNull(kind, row.preview, row.contextLabel, row.age).joinToString(", ") }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, color = VirlinColors.TextTertiary, modifier = Modifier.weight(1f))
            Text(row.age, fontSize = 10.sp, color = VirlinColors.TextTertiary)
        }
        Text(row.preview, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        row.contextLabel?.let { Text(it, fontSize = 10.sp, color = VirlinColors.Emerald, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Detail(state: AgentCaptureState, vm: AgentCaptureViewModel) {
    val item = state.selected ?: return
    val f = state.form
    val clipboard = LocalClipboardManager.current
    val copyText = if (item.type == CaptureType.LINK) item.sourceUrl ?: item.content else item.content
    Column(modifier = Modifier.fillMaxWidth().testTag(CaptureDetailTag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextSecondary,
                modifier = Modifier.testTag(CaptureCloseTag).clickable(role = Role.Button) { vm.select(null) }.padding(vertical = 6.dp))
            Spacer(Modifier.width(10.dp))
            Text("${CapturePresentation.typeLabel(item.type).uppercase()} · ${CapturePresentation.age(item.createdAt, java.time.Instant.now())}", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary)
        }
        Column(modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).border(1.dp, Hairline, RoundedCornerShape(12.dp)).padding(12.dp)) {
            item.title?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary) }
            item.sourceUrl?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.Emerald) }
            // Full content, verbatim (line breaks preserved). Only the detail renders it all.
            if (item.content.isNotBlank()) Text(item.content, fontSize = 13.sp, color = VirlinColors.TextPrimary, lineHeight = 19.sp, modifier = Modifier.testTag(CaptureDetailContentTag))
            state.selectedContextLabel?.let { Spacer(Modifier.height(6.dp)); Text("Attached to · $it", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VirlinColors.Emerald) }
            if (item.status == CaptureStatus.ORGANIZED) Text("Organized · turned into a task", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextTertiary)
            if (item.status == CaptureStatus.ARCHIVED) Text("Archived", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextTertiary)
        }
        f.feedback?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.Emerald, modifier = Modifier.testTag(CaptureFeedbackTag).padding(top = 8.dp)) }
        f.error?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.Amber, modifier = Modifier.testTag(CaptureErrorTag).padding(top = 8.dp)) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("COPY", CaptureCopyTag, primary = false) { clipboard.setText(AnnotatedString(copyText)); vm.dismissFeedback() }
            when (item.status) {
                CaptureStatus.INBOX -> {
                    Chip(if (f.organizeOpen) "HIDE" else "ORGANIZE", CaptureOrganizeTag, primary = true) { vm.toggleOrganize() }
                    Chip("ARCHIVE", CaptureArchiveTag, primary = false) { vm.archive(item.id) }
                }
                CaptureStatus.ARCHIVED -> Chip("RESTORE", CaptureRestoreTag, primary = false) { vm.restore(item.id) }
                CaptureStatus.ORGANIZED -> Unit
            }
        }
        if (f.organizeOpen && item.status == CaptureStatus.INBOX) {
            Spacer(Modifier.height(10.dp))
            SectionLabel("ATTACH TO · stays a capture")
            ContextPicker(state, streamTag = ::captureContextStreamTag, projectTag = ::captureContextProjectTag,
                onStream = { ws -> vm.attach(item.id, CaptureContextChoice(projectId = ws.projectId, workStreamId = ws.id)) },
                onProject = { p -> vm.attach(item.id, CaptureContextChoice(projectId = p.id)) })
            if (item.hasContext) { Spacer(Modifier.height(4.dp)); Chip("CLEAR CONTEXT", CaptureClearContextTag, primary = false) { vm.attach(item.id, CaptureContextChoice()) } }
            Spacer(Modifier.height(10.dp))
            SectionLabel("CONVERT TO TASK · becomes a real task, capture organized")
            ContextPicker(state, streamTag = ::captureConvertStreamTag, projectTag = ::captureConvertProjectTag,
                onStream = { ws -> vm.convertToTask(item.id, CaptureTaskTarget(workStreamId = ws.id)) },
                onProject = { p -> vm.convertToTask(item.id, CaptureTaskTarget(projectId = p.id)) },
                projectsHeader = "STANDALONE IN PROJECT")
        }
    }
}

/** WorkStreams (with their project) then Projects. Explicit rows; nothing pre-selected. */
@Composable
private fun ContextPicker(
    state: AgentCaptureState,
    streamTag: (String) -> String, projectTag: (String) -> String,
    onStream: (com.virlin.app.domain.model.WorkStream) -> Unit, onProject: (com.virlin.app.domain.model.Project) -> Unit,
    projectsHeader: String = "PROJECT ONLY"
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("WORKSTREAM")
        state.workStreams.forEach { ws ->
            val project = ws.projectId?.let { id -> state.projects.firstOrNull { it.id == id }?.title }
            PickRow(if (project != null) "$project · ${ws.title}" else ws.title, streamTag(ws.id)) { onStream(ws) }
        }
        if (state.projects.isNotEmpty()) {
            Spacer(Modifier.height(4.dp)); SectionLabel(projectsHeader)
            state.projects.forEach { p -> PickRow(p.title, projectTag(p.id)) { onProject(p) } }
        }
    }
}

@Composable
private fun PickRow(label: String, tag: String, onClick: () -> Unit) {
    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = VirlinColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Hairline, RoundedCornerShape(10.dp))
            .testTag(tag).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }.padding(horizontal = 12.dp, vertical = 11.dp))
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, tag: String) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = VirlinColors.TextPrimary), cursorBrush = SolidColor(VirlinColors.Emerald),
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Hairline, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (value.isEmpty()) Text(placeholder, fontSize = 13.sp, color = VirlinColors.TextTertiary)
                inner()
            }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary, modifier = Modifier.padding(bottom = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Choice(label: String, tag: String, selected: Boolean, small: Boolean = false, onClick: () -> Unit) {
    Text(label, fontSize = if (small) 10.sp else 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp,
        color = if (selected) Color.White else VirlinColors.TextPrimary,
        modifier = Modifier.heightIn(min = 36.dp)
            .background(if (selected) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
            .border(1.dp, if (selected) Color.Transparent else Hairline, RoundedCornerShape(50))
            .testTag(tag).clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (selected) "$label, selected" else label }
            .padding(horizontal = 12.dp, vertical = 9.dp), maxLines = 1)
}

@Composable
private fun Chip(label: String, tag: String, primary: Boolean, onClick: () -> Unit) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp,
        color = if (primary) Color.White else VirlinColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.heightIn(min = 36.dp)
            .background(if (primary) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
            .border(1.dp, if (primary) Color.Transparent else Hairline, RoundedCornerShape(50))
            .testTag(tag).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp))
}
