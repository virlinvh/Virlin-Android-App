package com.virlin.app.ui.notes

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.hierarchy.readableOn
import com.virlin.app.ui.components.SafeDialog as Dialog

/** A short notice from the editor, in the same card and button as the word-count popup. */
@Composable
internal fun NoteMessagePopup(text: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(26.dp), color = Color.White, shadowElevation = 8.dp) {
            Column(Modifier.width(304.dp).padding(22.dp).testTag("notes_message")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(NotesWash),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Info, null, tint = NotesAccent, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Note", color = NotesInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(14.dp))
                Text(text, color = NotesInk, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.testTag("notes_message_text"))
                Spacer(Modifier.height(20.dp))
                val buttonShape = RoundedCornerShape(16.dp)
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(buttonShape).background(NotesAccent)
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .testTag("notes_message_ok"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("OK", color = readableOn(NotesAccent), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
