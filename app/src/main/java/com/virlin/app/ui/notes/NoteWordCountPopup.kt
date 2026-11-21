package com.virlin.app.ui.notes

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
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteWordCount
import com.virlin.app.ui.hierarchy.readableOn
import com.virlin.app.ui.components.SafeDialog as Dialog
import java.text.NumberFormat

/** The word-count card: words and characters for the whole note, read once when opened. */
@Composable
internal fun NoteWordCountPopup(stats: NoteWordCount.Stats, onDismiss: () -> Unit) {
    val format = remember { NumberFormat.getIntegerInstance() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(26.dp), color = Color.White, shadowElevation = 8.dp) {
            Column(Modifier.width(304.dp).padding(22.dp).testTag("notes_word_count")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(NotesWash),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Numbers, null, tint = NotesAccent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Word count", color = NotesInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Whole note", color = NotesMuted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CountTile(format.format(stats.words), "Words",
                        if (stats.words == 1) "1 word" else "${format.format(stats.words)} words",
                        Modifier.weight(1f).testTag("notes_word_count_words"))
                    CountTile(format.format(stats.characters), "Characters",
                        if (stats.characters == 1) "1 character" else "${format.format(stats.characters)} characters",
                        Modifier.weight(1f).testTag("notes_word_count_characters"))
                }
                Spacer(Modifier.height(10.dp))
                val rowShape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().clip(rowShape).border(1.dp, NotesBorder, rowShape)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .clearAndSetSemantics {
                            contentDescription = "${format.format(stats.charactersWithoutSpaces)} characters without spaces"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Characters without spaces", color = NotesMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(format.format(stats.charactersWithoutSpaces), color = NotesInk, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(18.dp))
                val buttonShape = RoundedCornerShape(16.dp)
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(buttonShape).background(NotesAccent)
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .testTag("notes_word_count_done"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Done", color = readableOn(NotesAccent), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CountTile(value: String, label: String, spoken: String, modifier: Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape).background(NotesWash).border(1.dp, NotesAccent.copy(alpha = .16f), shape)
            .padding(horizontal = 14.dp, vertical = 16.dp)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(value, color = NotesAccent, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label.uppercase(), color = NotesMuted, fontSize = 11.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Medium)
    }
}
