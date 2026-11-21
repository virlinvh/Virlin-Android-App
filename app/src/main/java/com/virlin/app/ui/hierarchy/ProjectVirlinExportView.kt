package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.project.VirlinProjectFile
import com.virlin.app.data.project.VirlinTiming
import com.virlin.app.ui.theme.VirlinColors

private val ExportBorder = Color(0xFFDDE6E1)
private val ExportHairline = Color(0xFFEDF1EF)

/** What the file will hold, counted from the live project before anything is written. */
internal data class VirlinExportPreview(
    val streams: Int, val tasks: Int, val steps: Int, val notes: Int, val pageBlocks: Int,
    val captures: Int, val flashcards: Int, val mapPreviews: Int,
    val mediaFiles: Int, val mediaBytes: Long, val missing: Int,
)

private fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"

@Composable
internal fun ProjectVirlinExportView(
    preview: VirlinExportPreview?,
    timing: VirlinTiming,
    onTiming: (VirlinTiming) -> Unit,
    enabled: Boolean,
    onExport: () -> Unit,
    accent: Color,
) {
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatFileSize(context, bytes)
    val text = VirlinColors.TextPrimary
    val muted = VirlinColors.TextSecondary
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp))
                .border(1.dp, ExportBorder, RoundedCornerShape(20.dp)).padding(18.dp).testTag("virlin_export_card")
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(accent.copy(alpha = .12f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Inventory2, null, tint = accent, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Virlin project file", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = text)
                    Text("Everything in one file, ready to import", fontSize = 12.5.sp, color = muted)
                }
                Text(".${VirlinProjectFile.EXTENSION}", Modifier.background(accent.copy(alpha = .10f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp), color = accent, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            }
            Spacer(Modifier.padding(top = 10.dp))
            Text("The exact project as it is now: its work, notes, Pages, mind map and the original media files. " +
                "Import it here or on another device later and carry on where you left off.",
                fontSize = 13.sp, color = muted, lineHeight = 18.sp)
        }

        Text("WHAT GOES IN", Modifier.padding(start = 4.dp, top = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = muted)
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).border(1.dp, ExportBorder, RoundedCornerShape(16.dp))) {
            val rows = listOf(
                Triple(Icons.Outlined.AccountTree, "Workstreams, tasks & checklists",
                    preview?.let { "${plural(it.streams, "workstream")} · ${plural(it.tasks, "task")} · ${plural(it.steps, "step")}" }),
                Triple(Icons.Outlined.TextSnippet, "Notes, Pages & captures",
                    preview?.let { "${plural(it.notes, "note")} · ${plural(it.pageBlocks, "Page block")} · ${plural(it.captures, "capture")}" }),
                Triple(Icons.Outlined.Hub, "Mind map", preview?.let { "Layout, colours and ${plural(it.mapPreviews, "node preview")}" }),
                Triple(Icons.Outlined.Style, "Flashcards", preview?.let { "${plural(it.flashcards, "card")} with their review progress" }),
                Triple(Icons.Outlined.PhotoLibrary, "Media, byte for byte",
                    preview?.let { "${plural(it.mediaFiles, "original file")} · ${size(it.mediaBytes)}" }),
                Triple(Icons.Outlined.History, "History & settings",
                    if (timing == VirlinTiming.KEEP) "Activity, focus sessions, time spent and project settings"
                    else "Activity and project settings · time spent starts at zero"),
            )
            rows.forEachIndexed { index, (icon, label, detail) ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 62.dp), color = ExportHairline)
                ExportRow(icon, label, detail ?: "Counting…", accent, text, muted)
            }
        }
        if ((preview?.missing ?: 0) > 0) Text(
            "${plural(preview!!.missing, "file")} this project points to ${if (preview.missing == 1) "is" else "are"} already missing on this device. " +
                "Everything else is exported, and the file lists what was missing.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("virlin_export_missing"))

        Text("TIME SPENT", Modifier.padding(start = 4.dp, top = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = muted)
        TimingOption(VirlinTiming.KEEP, timing, "Keep time exactly",
            "Focus invested, external run time and running clocks stay as they are.", accent, enabled, onTiming)
        TimingOption(VirlinTiming.RESET, timing, "Start time from zero",
            "Work, notes and history stay. Only the time spent is cleared in the file — this project is not changed.", accent, enabled, onTiming)

        Button(onClick = onExport, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("export_virlin"),
            shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = readableOn(accent))) {
            Icon(Icons.Outlined.FileDownload, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text("Export .${VirlinProjectFile.EXTENSION} file", fontWeight = FontWeight.Bold)
        }
        Text("Every file is checked against its fingerprint while exporting, and the saved copy is checked again. " +
            "Nothing in this project changes. The file may contain private information; keep it somewhere you trust.",
            fontSize = 12.5.sp, color = muted, lineHeight = 17.sp)
    }
}

@Composable
private fun ExportRow(icon: ImageVector, label: String, detail: String, accent: Color, text: Color, muted: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(accent.copy(alpha = .10f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = text)
            Text(detail, fontSize = 11.5.sp, color = muted)
        }
    }
}

@Composable
private fun TimingOption(value: VirlinTiming, selected: VirlinTiming, title: String, description: String,
                         accent: Color, enabled: Boolean, onTiming: (VirlinTiming) -> Unit) {
    val on = value == selected
    Row(
        Modifier.fillMaxWidth().background(if (on) accent.copy(alpha = .06f) else Color.White, RoundedCornerShape(16.dp))
            .border(if (on) 1.5.dp else 1.dp, if (on) accent else ExportBorder, RoundedCornerShape(16.dp))
            .selectable(on, enabled = enabled, role = Role.RadioButton) { onTiming(value) }
            .padding(horizontal = 8.dp, vertical = 10.dp).testTag("virlin_timing_${value.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(on, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = accent))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f).padding(end = 6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = VirlinColors.TextPrimary)
            Text(description, fontSize = 12.sp, color = VirlinColors.TextSecondary, lineHeight = 16.sp)
        }
    }
}
