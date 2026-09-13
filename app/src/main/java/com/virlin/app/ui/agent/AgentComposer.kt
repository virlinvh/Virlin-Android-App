package com.virlin.app.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.model.CaptureType
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.theme.VirlinColors

// Stable selectors (§44).
const val AgentComposerTestTag = "agent_composer"
const val AgentAttachmentButtonTestTag = "agent_attachment_button"
const val AgentSubmitTestTag = "agent_submit"
const val CaptureSaveInboxTestTag = "capture_save_inbox"
const val AgentAttachmentMenuTestTag = "agent_attachment_menu"
fun attachmentMenuItemTag(type: CaptureType) = "agent_attach_${type.name.lowercase()}"
fun inputObjectTag(id: String) = "agent_input_object_$id"

private val Surface = Color.White
private val Hairline = VirlinColors.TextPrimary.copy(alpha = 0.08f)
private val Soft = VirlinColors.Background

/**
 * The ONE universal composer shared by CONTROL, CREATE and CAPTURE.
 *
 * Structure: [attachment objects] / [natural-language text area] / [+] [context line] [action].
 * Text, voice-derived text, paste, prompt, link, file and image all arrive through the same
 * state path; only the visual treatment of attached objects differs by mode (compact chips
 * here, the Capture Tray in CAPTURE mode — see [CaptureTray]).
 */
@Composable
fun UniversalComposer(
    mode: AgentMode,
    text: String,
    enabled: Boolean,
    attachments: List<InputObject>,
    showAttachmentsInline: Boolean,
    contextLine: String,
    menuOpen: Boolean,
    onTextChanged: (String) -> Unit,
    onToggleMenu: () -> Unit,
    onAddAttachment: (CaptureType) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Overrides the per-mode hint (the entry step reads "Ask anything…"). */
    placeholder: String? = null
) {
    val canSubmit = enabled && (text.isNotBlank() || attachments.isNotEmpty())

    Column(modifier = modifier.fillMaxWidth()) {
        if (showAttachmentsInline && attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { InputObjectChip(it, onRemove = { onRemoveAttachment(it.id) }) }
            }
        }

        if (menuOpen) {
            AttachmentMenu(onPick = onAddAttachment)
            Spacer(Modifier.height(8.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface, RoundedCornerShape(18.dp))
                .border(1.dp, Hairline, RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            val placeholder = placeholder ?: when (mode) {
                AgentMode.CONTROL -> "Tell Virlin what to do…"
                AgentMode.CREATE -> "Describe what you want to create…"
                AgentMode.CAPTURE -> "What would you like to capture…"
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChanged,
                enabled = enabled,
                minLines = 2,
                maxLines = 5,
                textStyle = TextStyle(fontSize = 15.sp, color = VirlinColors.TextPrimary, lineHeight = 21.sp),
                cursorBrush = SolidColor(VirlinColors.Emerald),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AgentComposerTestTag)
                    .semantics { contentDescription = "Message to Virlin" },
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(placeholder, fontSize = 15.sp, color = VirlinColors.TextTertiary, lineHeight = 21.sp)
                        }
                        inner()
                    }
                }
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // ONE compact add control → progressive disclosure.
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(if (menuOpen) VirlinColors.TextPrimary else Soft, CircleShape)
                        .testTag(AgentAttachmentButtonTestTag)
                        .clickable(enabled = enabled, role = Role.Button, onClick = onToggleMenu)
                        .semantics { contentDescription = "Add attachment" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null,
                        tint = if (menuOpen) Color.White else VirlinColors.TextSecondary,
                        modifier = Modifier.size(20.dp))
                }

                Spacer(Modifier.width(10.dp))

                Text(
                    contextLine,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    color = VirlinColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(Modifier.width(10.dp))

                if (mode == AgentMode.CAPTURE) {
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .background(if (canSubmit) VirlinColors.TextPrimary else VirlinColors.TextPrimary.copy(alpha = 0.25f), RoundedCornerShape(50))
                            .testTag(CaptureSaveInboxTestTag)
                            .clickable(enabled = canSubmit, role = Role.Button, onClick = onSubmit)
                            .semantics { contentDescription = "Save to Inbox" }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("SAVE TO INBOX", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(if (canSubmit) VirlinColors.TextPrimary else VirlinColors.TextPrimary.copy(alpha = 0.25f), CircleShape)
                            .testTag(AgentSubmitTestTag)
                            .clickable(enabled = canSubmit, role = Role.Button, onClick = onSubmit)
                            .semantics { contentDescription = "Send" },
                        contentAlignment = Alignment.Center
                    ) { Text("→", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
            }
        }
    }
}

/** Progressive-disclosure input menu. UI only — each item adds a demo object. */
@Composable
private fun AttachmentMenu(onPick: (CaptureType) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(AgentAttachmentMenuTestTag),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            CaptureType.VOICE to "Voice",
            CaptureType.PROMPT to "Prompt",
            CaptureType.LINK to "Link",
            CaptureType.FILE to "File",
            CaptureType.IMAGE to "Image"
        ).forEach { (type, label) ->
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = VirlinColors.TextPrimary,
                modifier = Modifier
                    .background(Surface, RoundedCornerShape(50))
                    .border(1.dp, Hairline, RoundedCornerShape(50))
                    .testTag(attachmentMenuItemTag(type))
                    .clickable(role = Role.Button) { onPick(type) }
                    .semantics { contentDescription = "Add $label" }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

/** Compact representation for CONTROL / CREATE. Remove control is a full 40dp target. */
@Composable
fun InputObjectChip(obj: InputObject, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .background(Surface, RoundedCornerShape(12.dp))
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .testTag(inputObjectTag(obj.id))
            .semantics { contentDescription = obj.accessibilityLabel }
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KindMark(obj.type)
        Spacer(Modifier.width(6.dp))
        Text(
            when (obj) {
                is InputObject.Prompt -> "Prompt · ${obj.title}"
                is InputObject.Link -> obj.domain
                is InputObject.File -> obj.name
                is InputObject.Image -> obj.fileName
                is InputObject.Voice -> "Voice · ${formatDuration(obj.durationSec)}"
            },
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)
        )
        RemoveControl(onRemove)
    }
}

/**
 * The Capture Tray: objects shown in their actual conceptual form so a Prompt reads as
 * structured text, a Link as a source, a Voice note as an audio object. All objects belong
 * to ONE bundle. Demo shells only.
 */
@Composable
fun CaptureTray(attachments: List<InputObject>, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text("CAPTURE TRAY", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary)
        Spacer(Modifier.height(8.dp))
        if (attachments.isEmpty()) {
            Text(
                "Add voice, a prompt, a link, a file or an image — or just type. Everything saves together.",
                fontSize = 12.sp, color = VirlinColors.TextTertiary, lineHeight = 17.sp
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { InputObjectCard(it, onRemove = { onRemove(it.id) }) }
            }
        }
    }
}

@Composable
private fun InputObjectCard(obj: InputObject, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .testTag(inputObjectTag(obj.id))
            .semantics { contentDescription = obj.accessibilityLabel }
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (obj) {
            is InputObject.Voice -> {
                Box(
                    modifier = Modifier.size(32.dp).background(VirlinColors.FocusSurface, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = VirlinColors.TextPrimary, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // Waveform-free simple progress bar (audio object, not a transcript).
                    Box(Modifier.fillMaxWidth().height(4.dp).background(VirlinColors.TextPrimary.copy(alpha = 0.10f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(0.0f).height(4.dp).background(VirlinColors.Emerald, RoundedCornerShape(2.dp)))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Voice · ${formatDuration(obj.durationSec)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary)
                }
            }
            is InputObject.Prompt -> {
                KindMark(obj.type)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Prompt · ${obj.title}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    // Structured text: preserved line breaks, monospace preview, line count.
                    Text(
                        obj.preview, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                        color = VirlinColors.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(8.dp)).padding(8.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text("${obj.lineCount} lines · structure preserved", fontSize = 10.sp, color = VirlinColors.TextTertiary)
                }
            }
            is InputObject.Link -> {
                Box(Modifier.size(28.dp).background(Soft, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    Text(obj.domain.first().uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextSecondary)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(obj.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(obj.domain, fontSize = 11.sp, color = VirlinColors.TextTertiary)
                }
            }
            is InputObject.File -> {
                KindMark(obj.type)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(obj.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${obj.kind} · ${obj.sizeLabel}", fontSize = 11.sp, color = VirlinColors.TextTertiary)
                }
            }
            is InputObject.Image -> {
                // Thumbnail placeholder.
                Box(Modifier.size(36.dp).background(VirlinColors.ProcessingSurface, RoundedCornerShape(8.dp)).border(1.dp, Hairline, RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(obj.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary)
                    Text(obj.fileName, fontSize = 11.sp, color = VirlinColors.TextTertiary)
                }
            }
        }
        RemoveControl(onRemove)
    }
}

@Composable
private fun KindMark(type: CaptureType) {
    val label = when (type) {
        CaptureType.PROMPT -> "P"; CaptureType.LINK -> "L"; CaptureType.FILE -> "F"
        CaptureType.IMAGE -> "I"; CaptureType.VOICE -> "V"; CaptureType.TEXT -> "T"; CaptureType.BUNDLE -> "B"
    }
    Box(
        modifier = Modifier.size(22.dp).background(VirlinColors.FocusSurface, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 10.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary) }
}

@Composable
private fun RemoveControl(onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(interactionSource = MutableInteractionSource(), indication = null, role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = "Remove" },
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Rounded.Close, contentDescription = null, tint = VirlinColors.TextTertiary, modifier = Modifier.size(14.dp)) }
}

private fun formatDuration(sec: Int) = "%02d:%02d".format(sec / 60, sec % 60)
