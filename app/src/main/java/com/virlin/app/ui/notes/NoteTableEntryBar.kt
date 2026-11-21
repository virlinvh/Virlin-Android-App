package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocTable

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/** Callbacks for the table-size strip shown under the formatting rail. */
class NoteTableEntry(
    val onInsert: (rows: Int, cols: Int) -> Unit,
    val onCancel: () -> Unit,
)

/**
 * "Rows × Columns" strip under the formatting rail, like the other style strips. Values are
 * clamped to [NoteDocTable.MAX_ROWS] / [NoteDocTable.MAX_COLS] as they are typed; Create stays
 * disabled until both are at least 1. Creating hands over to the table tools strip.
 */
@Composable
internal fun NoteTableEntryBar(entry: NoteTableEntry, modifier: Modifier = Modifier) {
    var rows by remember { mutableStateOf(TextFieldValue("3", TextRange(0, 1))) }
    var cols by remember { mutableStateOf(TextFieldValue("3", TextRange(0, 1))) }
    val rowsFocus = remember { FocusRequester() }
    val colsFocus = remember { FocusRequester() }
    val r = rows.text.toIntOrNull() ?: 0
    val c = cols.text.toIntOrNull() ?: 0
    val canInsert = r >= 1 && c >= 1
    val insert = { if (canInsert) entry.onInsert(r, c) }
    BackHandler { entry.onCancel() }
    LaunchedEffect(Unit) { runCatching { rowsFocus.requestFocus() } }

    Box(modifier.fillMaxWidth().height(60.dp).background(Color.White)) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("notes_table_entry"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▦", color = Green, fontSize = 18.sp)
            CountField("Rows", rows, NoteDocTable.MAX_ROWS, rowsFocus, ImeAction.Next,
                onAction = { runCatching { colsFocus.requestFocus() } },
                onChange = { rows = it }, tag = "notes_table_rows")
            Text("×", color = Muted, fontSize = 16.sp)
            CountField("Columns", cols, NoteDocTable.MAX_COLS, colsFocus, ImeAction.Done,
                onAction = insert, onChange = { cols = it }, tag = "notes_table_cols")
            Spacer(Modifier.weight(1f))
            val pill = RoundedCornerShape(12.dp)
            Row(
                Modifier.height(40.dp).clip(pill)
                    .background(if (canInsert) Green else Border)
                    .clickable(enabled = canInsert, role = Role.Button, onClick = insert)
                    .semantics { contentDescription = "Create table, $r rows by $c columns" }
                    .testTag("notes_table_insert")
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text("Create", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier.size(40.dp).clip(pill)
                    .clickable(role = Role.Button, onClick = entry.onCancel)
                    .semantics { contentDescription = "Cancel table" }
                    .testTag("notes_table_cancel"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Border))
    }
}

@Composable
private fun CountField(
    label: String,
    value: TextFieldValue,
    max: Int,
    focus: FocusRequester,
    imeAction: ImeAction,
    onAction: () -> Unit,
    onChange: (TextFieldValue) -> Unit,
    tag: String,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(58.dp).height(44.dp).clip(shape)
            .background(if (focused) Mint else Color.White)
            .border(1.dp, if (focused) Green.copy(alpha = .6f) else Border, shape)
            .clickable(role = Role.Button) { runCatching { focus.requestFocus() } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label.uppercase(), color = Muted, fontSize = 8.sp, lineHeight = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = { next ->
                if (next.text.length <= 2 && next.text.all(Char::isDigit)) {
                    val n = next.text.toIntOrNull()
                    onChange(if (n != null && n > max) TextFieldValue("$max", TextRange("$max".length)) else next)
                }
            },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Green),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
            modifier = Modifier.width(44.dp).focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "$label, up to $max" }
                .testTag(tag),
        )
    }
}
