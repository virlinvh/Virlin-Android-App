package com.virlin.app.ui.hierarchy

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.project.ProjectStorageBreakdown
import com.virlin.app.data.project.ProjectStorageKeys
import com.virlin.app.data.project.StorageGroup
import com.virlin.app.data.project.StorageRow
import com.virlin.app.ui.theme.VirlinColors

private val CardBorder = Color(0xFFDDE6E1)
private val Hairline = Color(0xFFEDF1EF)

/** Distinct, calm hues so each slice of the bar matches its row. */
private fun rowColor(key: String, accent: Color): Color = when (key) {
    ProjectStorageKeys.NOTE_PICTURES -> Color(0xFF7C3AED)
    ProjectStorageKeys.NOTE_RECORDINGS -> Color(0xFFDB2777)
    ProjectStorageKeys.FLASHCARD_PICTURES -> Color(0xFFF59E0B)
    ProjectStorageKeys.IMAGES -> Color(0xFF0EA5E9)
    ProjectStorageKeys.PDFS -> Color(0xFFDC2626)
    ProjectStorageKeys.FILES -> Color(0xFF64748B)
    ProjectStorageKeys.VOICE -> Color(0xFF10B981)
    ProjectStorageKeys.MAP_IMAGES -> Color(0xFF6366F1)
    ProjectStorageKeys.ICON -> Color(0xFFF97316)
    ProjectStorageKeys.NOTES -> accent
    ProjectStorageKeys.CAPTURES -> Color(0xFFEAB308)
    ProjectStorageKeys.STRUCTURE -> Color(0xFF14B8A6)
    else -> Color(0xFFA855F7)
}

private fun rowIcon(key: String): ImageVector = when (key) {
    ProjectStorageKeys.NOTE_PICTURES -> Icons.Outlined.PhotoLibrary
    ProjectStorageKeys.NOTE_RECORDINGS -> Icons.Outlined.GraphicEq
    ProjectStorageKeys.FLASHCARD_PICTURES -> Icons.Outlined.Collections
    ProjectStorageKeys.IMAGES -> Icons.Outlined.Image
    ProjectStorageKeys.PDFS -> Icons.Outlined.PictureAsPdf
    ProjectStorageKeys.FILES -> Icons.Outlined.Description
    ProjectStorageKeys.VOICE -> Icons.Outlined.Mic
    ProjectStorageKeys.MAP_IMAGES -> Icons.Outlined.Hub
    ProjectStorageKeys.ICON -> Icons.Outlined.Category
    ProjectStorageKeys.NOTES -> Icons.Outlined.TextSnippet
    ProjectStorageKeys.CAPTURES -> Icons.Outlined.Lightbulb
    ProjectStorageKeys.STRUCTURE -> Icons.Outlined.AccountTree
    else -> Icons.Outlined.Style
}

private fun itemsLabel(row: StorageRow): String {
    if (row.items == 0) return "Nothing yet"
    val noun = when {
        row.group == StorageGroup.MEDIA -> "file"
        row.key == ProjectStorageKeys.NOTES -> "note"
        row.key == ProjectStorageKeys.CAPTURES -> "capture"
        row.key == ProjectStorageKeys.FLASHCARDS -> "card"
        else -> "item"
    }
    return "${row.items} $noun${if (row.items == 1) "" else "s"}"
}

/** The live split of one project's storage: a total, one proportional bar, then each kind. */
@Composable
internal fun ProjectStorageUsageView(value: ProjectStorageBreakdown, accent: Color) {
    val context = LocalContext.current
    fun size(bytes: Long) = android.text.format.Formatter.formatFileSize(context, bytes)
    val text = VirlinColors.TextPrimary
    val muted = VirlinColors.TextSecondary
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(20.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(20.dp)).padding(18.dp)
                .testTag("project_storage_total")
        ) {
            Text("USED ON THIS DEVICE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = muted)
            Text(size(value.total), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = accent,
                modifier = Modifier.semantics { contentDescription = "Total ${size(value.total)}" })
            Text("Media ${size(value.media)}  ·  Project data ${size(value.data)}  ·  ${value.files} file${if (value.files == 1) "" else "s"}",
                fontSize = 12.5.sp, color = muted)
            Spacer(Modifier.height(14.dp))
            StorageBar(value, accent)
        }
        StorageGroupCard("Media", value.media, value.rows.filter { it.group == StorageGroup.MEDIA }, value.total, accent, ::size, text, muted)
        StorageGroupCard("Project data", value.data, value.rows.filter { it.group == StorageGroup.DATA }, value.total, accent, ::size, text, muted)
        if (value.missing > 0) Text(
            "${value.missing} referenced file${if (value.missing == 1) " is" else "s are"} missing on this device and not counted. Export and full copy report missing media rather than silently omit it.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("project_storage_missing"))
        Text("Updates live as you add or remove content. Media is measured from the files on this device; project data is the size of this project's saved notes, captures, tasks and flashcards. App cache, database indexes and other projects are not included. No files are removed.",
            fontSize = 12.5.sp, color = muted, lineHeight = 17.sp)
    }
}

@Composable
private fun StorageBar(value: ProjectStorageBreakdown, accent: Color) {
    val slices = value.rows.filter { it.bytes > 0 }
    Row(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Color(0xFFEFF3F1))
            .clearAndSetSemantics { }.testTag("project_storage_bar"),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Tiny kinds still get a visible sliver; the numbers below stay exact.
        val floor = value.total * 0.015
        slices.forEach { row ->
            Box(Modifier.weight(maxOf(row.bytes.toDouble(), floor).toFloat()).fillMaxHeight().background(rowColor(row.key, accent)))
        }
    }
}

@Composable
private fun StorageGroupCard(title: String, total: Long, rows: List<StorageRow>, overall: Long, accent: Color,
    size: (Long) -> String, text: Color, muted: Color) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = text)
            Text(size(total), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = muted)
        }
        Column(
            Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp))
                .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
        ) {
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = Hairline)
                StorageRowItem(row, overall, accent, size, text, muted)
            }
        }
    }
}

@Composable
private fun StorageRowItem(row: StorageRow, overall: Long, accent: Color, size: (Long) -> String, text: Color, muted: Color) {
    val color = rowColor(row.key, accent)
    val empty = row.bytes == 0L
    val share = if (overall > 0 && !empty) " · ${shareLabel(row.bytes, overall)}" else ""
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { }
            .testTag("project_storage_row_${row.key}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(38.dp).background(color.copy(alpha = if (empty) .07f else .13f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Icon(rowIcon(row.key), null, tint = if (empty) color.copy(alpha = .45f) else color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (empty) muted else text)
            Text(itemsLabel(row) + share, fontSize = 11.5.sp, color = muted)
        }
        if (!empty) Box(Modifier.padding(end = 8.dp).size(8.dp).background(color, CircleShape))
        Text(size(row.bytes), fontWeight = if (empty) FontWeight.Medium else FontWeight.Bold, fontSize = 13.5.sp,
            color = if (empty) muted else text)
    }
}

private fun shareLabel(bytes: Long, overall: Long): String {
    val percent = bytes * 100.0 / overall
    return when {
        percent >= 99.5 && bytes < overall -> ">99%"
        percent >= 1 -> "${Math.round(percent)}%"
        else -> "<1%"
    }
}
