package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.hierarchy.readableOn

class NoteRenameStripState(
    /** The name the note shows now. */
    val current: String,
    /** Saves the new name; false when it can't be used (blank). */
    val onRename: (String) -> Boolean,
    val onClose: () -> Unit,
)

/**
 * The rename strip under the formatting rail: the note's name, ready to edit, with Save and close.
 * It renames this note only, never the task or mind-map item the note belongs to.
 */
@Composable
internal fun NoteRenameStrip(state: NoteRenameStripState, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    var value by remember(state.current) {
        mutableStateOf(TextFieldValue(state.current, TextRange(0, state.current.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    val close = { keyboard?.hide(); state.onClose() }
    val canSave = value.text.isNotBlank()
    val save = { if (canSave && state.onRename(value.text)) keyboard?.hide() }
    BackHandler { close() }

    Box(modifier.fillMaxWidth().height(60.dp).background(Color.White).testTag("notes_rename_strip")) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(NotesWash), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null, tint = NotesAccent,
                    modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            val fieldShape = RoundedCornerShape(12.dp)
            Box(
                Modifier.weight(1f).height(40.dp).clip(fieldShape)
                    .border(1.dp, NotesAccent.copy(alpha = .45f), fieldShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) Text("Note name", color = NotesMuted, fontSize = 15.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = value,
                    onValueChange = { next ->
                        value = if (next.text.length <= NotesViewModel.MAX_TITLE) next
                        else next.copy(text = next.text.take(NotesViewModel.MAX_TITLE),
                            selection = TextRange(minOf(next.selection.start, NotesViewModel.MAX_TITLE),
                                minOf(next.selection.end, NotesViewModel.MAX_TITLE)))
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = NotesInk, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(NotesAccent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .semantics { contentDescription = "Note name" }
                        .testTag("notes_rename_field"),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.height(40.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (canSave) NotesAccent else NotesBorder)
                    .clickable(enabled = canSave, role = Role.Button) { save() }
                    .semantics { contentDescription = "Save note name"; if (!canSave) disabled() }
                    .testTag("notes_rename_save")
                    .padding(horizontal = 16.dp)
                    .alpha(if (canSave) 1f else .7f),
                contentAlignment = Alignment.Center,
            ) {
                Text("Save", color = if (canSave) readableOn(NotesAccent) else NotesMuted, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button) { close() }
                    .semantics { contentDescription = "Close rename" }
                    .testTag("notes_rename_close"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = NotesMuted, modifier = Modifier.size(20.dp)) }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(NotesBorder))
    }
}
