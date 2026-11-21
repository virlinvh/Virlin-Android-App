package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.hierarchy.readableOn

class NoteExportStripState(
    val settings: NoteExportSettings,
    val onChange: (NoteExportSettings) -> Unit,
    /** A file is being written. */
    val busy: Boolean,
    /** What the last export produced, e.g. "Saved · 184 KB"; null before one. */
    val result: String?,
    val onExport: () -> Unit,
    val onClose: () -> Unit,
    /** The note's page formatting, which shapes PDF, Word and image pages; null when not formatted. */
    val pageSetup: NotePrintSettings? = null,
    val onFormat: () -> Unit = {},
)

private val FORMAT_ICONS: Map<NoteExportFormat, ImageVector> = mapOf(
    NoteExportFormat.PDF to Icons.Outlined.PictureAsPdf,
    NoteExportFormat.WORD to Icons.Outlined.Description,
    NoteExportFormat.MARKDOWN to Icons.Outlined.Code,
    NoteExportFormat.IMAGE to Icons.Outlined.Image,
)

/**
 * The export strip under the formatting rail: pick PDF, Word, Markdown or Image, and that
 * format's own options open in the same strip. Export asks where to save the file.
 */
@Composable
internal fun NoteExportStrip(state: NoteExportStripState, modifier: Modifier = Modifier) {
    BackHandler { state.onClose() }
    val s = state.settings
    Box(modifier.fillMaxWidth().background(Color.White).testTag("notes_export_strip")) {
        Column(Modifier.fillMaxWidth().animateContentSize(tween(220)).padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(NotesWash), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.IosShare, contentDescription = null, tint = NotesAccent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Export", color = NotesInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    val subtitle = when {
                        state.busy -> "Exporting…"
                        state.result != null -> state.result
                        s.format == null -> "Choose a format"
                        else -> s.format.caption
                    }
                    AnimatedContent(subtitle, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        label = "exportSubtitle") { text ->
                        Text(text, color = NotesMuted, fontSize = 11.sp, maxLines = 1,
                            modifier = Modifier.testTag("notes_export_status"))
                    }
                }
                val enabled = s.format != null && !state.busy
                Row(
                    Modifier.height(38.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (s.format != null) NotesAccent else NotesBorder)
                        .clickable(enabled = enabled, role = Role.Button, onClick = state.onExport)
                        .semantics { contentDescription = "Export note"; if (!enabled) disabled() }
                        .testTag("notes_export_button")
                        .padding(horizontal = 14.dp)
                        .alpha(if (enabled) 1f else .6f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val onAccent = readableOn(NotesAccent)
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(15.dp), color = onAccent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Export", color = if (s.format != null) onAccent else NotesMuted, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = state.onClose)
                        .semantics { contentDescription = "Close export" }
                        .testTag("notes_export_close"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = NotesMuted, modifier = Modifier.size(20.dp)) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoteExportFormat.entries.forEach { format ->
                    FormatTile(format, selected = s.format == format, Modifier.weight(1f)) {
                        state.onChange(s.copy(format = format))
                    }
                }
            }
            AnimatedContent(
                targetState = s.format,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 60)) + expandVertically(tween(220)))
                        .togetherWith(fadeOut(tween(120)) + shrinkVertically(tween(200)))
                },
                label = "exportOptions",
            ) { format ->
                if (format != null) Column(Modifier.fillMaxWidth().padding(top = 8.dp)) { Options(format, state) }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(NotesBorder))
    }
}

@Composable
private fun FormatTile(format: NoteExportFormat, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val fill by animateColorAsState(if (selected) NotesAccent.copy(alpha = .12f) else Color.White, tween(200), label = "tileFill")
    val edge by animateColorAsState(if (selected) NotesAccent else NotesBorder, tween(200), label = "tileEdge")
    val tint by animateColorAsState(if (selected) NotesAccent else NotesInk, tween(200), label = "tileTint")
    val iconScale by animateFloatAsState(if (selected) 1.12f else 1f, tween(220), label = "tileIcon")
    Column(
        modifier.height(60.dp).clip(shape).background(fill).border(if (selected) 1.5.dp else 1.dp, edge, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Export as ${format.label}"; this.selected = selected; role = Role.RadioButton }
            .testTag("notes_export_format_${format.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(FORMAT_ICONS.getValue(format), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp).scale(iconScale))
        Spacer(Modifier.height(4.dp))
        Text(format.label, color = tint, fontSize = 12.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun Options(format: NoteExportFormat, state: NoteExportStripState) {
    val s = state.settings
    val onChange = state.onChange
    NoteFeatheredRow(Modifier.fillMaxWidth().height(44.dp), peek = true) {
        if (s.usesPages) PagesChip(state.pageSetup, state.onFormat)
        when (format) {
            NoteExportFormat.PDF, NoteExportFormat.WORD ->
                NoteStripChoice("Images", NoteImageQuality.entries, s.imageQuality, { it.label }, "notes_export", last = true) {
                    onChange(s.copy(imageQuality = it))
                }
            NoteExportFormat.MARKDOWN ->
                NoteStripChoice("Images", listOf(false, true), s.markdownImages, { if (it) "Include (.zip)" else "Leave out" },
                    "notes_export", last = true) { onChange(s.copy(markdownImages = it)) }
            NoteExportFormat.IMAGE -> {
                NoteStripChoice("Output", NoteImageOutput.entries, s.imageOutput, { it.label }, "notes_export") {
                    onChange(s.copy(imageOutput = it))
                }
                NoteStripChoice("Type", NoteImageType.entries, s.imageType, { it.label }, "notes_export") {
                    onChange(s.copy(imageType = it))
                }
                NoteStripChoice("Sharpness", listOf(false, true), s.sharpImage, { if (it) "High" else "Standard" },
                    "notes_export", last = true) { onChange(s.copy(sharpImage = it)) }
            }
        }
    }
}

/** The note's page setup at a glance; tapping it opens page formatting. */
@Composable
private fun PagesChip(setup: NotePrintSettings?, onFormat: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.height(34.dp).clip(shape)
            .background(if (setup == null) NotesAccent.copy(alpha = .10f) else NotesWash)
            .border(1.dp, if (setup == null) NotesAccent.copy(alpha = .45f) else NotesBorder, shape)
            .clickable(role = Role.Button, onClick = onFormat)
            .semantics { contentDescription = if (setup == null) "Format pages" else "Pages ${setup.summary}, change formatting" }
            .testTag("notes_export_pages")
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Article, contentDescription = null, tint = NotesAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(setup?.summary ?: "Format pages", color = if (setup == null) NotesAccent else NotesInk, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, maxLines = 1)
        Text("  ›", color = NotesMuted, fontSize = 13.sp, maxLines = 1)
    }
    Spacer(Modifier.width(14.dp))
}
