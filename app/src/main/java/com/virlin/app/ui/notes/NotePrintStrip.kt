package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.hierarchy.readableOn

class NotePrintStripState(
    /** The note's page formatting; null when the note has not been formatted yet. */
    val settings: NotePrintSettings?,
    /** Opens page formatting, where paper, margins, page numbers and the title are chosen. */
    val onFormat: () -> Unit,
    /** Pages the note fills with [settings]; null while counting. */
    val pageCount: Int?,
    /** A PDF is being written. */
    val busy: Boolean,
    val onPrint: () -> Unit,
    val onSavePdf: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * The print strip under the formatting rail: Print (the system print dialog) and Save PDF. The
 * pages follow the note's page formatting; the summary opens it.
 */
@Composable
internal fun NotePrintStrip(state: NotePrintStripState, modifier: Modifier = Modifier) {
    BackHandler { state.onClose() }
    Box(modifier.fillMaxWidth().background(Color.White).testTag("notes_print_strip")) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(58.dp).padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(NotesWash), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Print, contentDescription = null, tint = NotesAccent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = state.onFormat)
                        .semantics { contentDescription = "Page formatting" }
                        .padding(vertical = 4.dp),
                ) {
                    Text("Print", color = NotesInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    val s = state.settings
                    val pages = state.pageCount?.let { if (it == 1) "1 page" else "$it pages" } ?: "Counting pages…"
                    Text(if (s == null) "Not formatted · Format pages" else "$pages · ${s.summary}",
                        color = if (s == null) NotesAccent else NotesMuted, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("notes_print_pages"))
                }
                val enabled = !state.busy
                Row(
                    Modifier.height(38.dp).clip(RoundedCornerShape(12.dp))
                        .border(1.dp, NotesAccent.copy(alpha = .55f), RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled, role = Role.Button, onClick = state.onSavePdf)
                        .semantics { contentDescription = "Save as PDF"; if (!enabled) disabled() }
                        .testTag("notes_print_save_pdf")
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(16.dp), color = NotesAccent, strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = NotesAccent,
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("PDF", color = NotesAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.height(38.dp).clip(RoundedCornerShape(12.dp)).background(NotesAccent)
                        .clickable(enabled = enabled, role = Role.Button, onClick = state.onPrint)
                        .semantics { contentDescription = "Print note"; if (!enabled) disabled() }
                        .testTag("notes_print_button")
                        .padding(horizontal = 14.dp)
                        .alpha(if (enabled) 1f else .6f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Print", color = readableOn(NotesAccent), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = state.onClose)
                        .semantics { contentDescription = "Close print" }
                        .testTag("notes_print_close"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = NotesMuted, modifier = Modifier.size(20.dp)) }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(NotesBorder))
    }
}
