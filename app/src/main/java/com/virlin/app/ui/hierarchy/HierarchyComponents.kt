package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.ui.theme.VirlinColors
import java.time.Duration

fun taskRowTag(id: String) = "task_row_$id"
fun taskToggleTag(id: String) = "task_toggle_$id"
fun taskCompleteTag(id: String) = "task_complete_$id"
const val AddTaskButtonTag = "add_task_button"
const val AddTaskTitleTag = "add_task_title"
const val AddTaskConfirmTag = "add_task_confirm"
const val AddNameTitleTag = "add_name_title"
const val AddNameConfirmTag = "add_name_confirm"
const val AddProjectButtonTag = "add_project_button"
const val SwitchFocusTag = "switch_focus_dialog"
const val SwitchFocusConfirmTag = "switch_focus_confirm"
const val SwitchFocusCancelTag = "switch_focus_cancel"
const val AddWorkStreamButtonTag = "add_workstream_button"

private val Hairline = VirlinColors.TextPrimary.copy(alpha = 0.08f)
/** Indentation is capped so deep trees stay usable; hierarchy is still conveyed by order + chevrons. */
private const val MaxIndentDepth = 3
private val IndentStep = 18.dp

@Composable
fun ProgressLine(label: ProgressLabel, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label.text, fontSize = if (large) 34.sp else 12.sp, fontWeight = FontWeight.Black,
                color = if (label.fraction == null) VirlinColors.TextTertiary else VirlinColors.TextPrimary)
            label.hint?.let { Spacer(Modifier.width(8.dp)); Text(it, fontSize = 11.sp, color = VirlinColors.TextTertiary, modifier = Modifier.padding(bottom = if (large) 6.dp else 0.dp)) }
        }
        label.fraction?.let { f ->
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(VirlinColors.TextPrimary.copy(alpha = 0.08f), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(4.dp).background(VirlinColors.Emerald, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/**
 * One task row: status marker → title → (parent progress) → (estimate) → chevron.
 * Tapping the marker completes via the action layer; tapping the row opens Task Detail;
 * the chevron expands/collapses. No toggle-switch semantics.
 */
@Composable
fun TaskTreeRow(
    row: TaskRow,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onComplete: (String) -> Unit
) {
    val indent = IndentStep * minOf(row.depth, MaxIndentDepth)
    val done = row.status == TaskStatus.DONE
    val cancelled = row.status == TaskStatus.CANCELLED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .heightIn(min = 48.dp)
            .background(if (row.isCurrent) VirlinColors.FocusSurface.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(12.dp))
            .testTag(taskRowTag(row.id))
            .clickable(role = Role.Button) { onOpen(row.id) }
            .semantics { contentDescription = row.accessibilityLabel }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusMarker(row, onComplete)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.task.title,
                fontSize = 14.sp,
                fontWeight = if (row.isCurrent) FontWeight.Black else FontWeight.SemiBold,
                color = when { cancelled -> VirlinColors.TextTertiary; done -> VirlinColors.TextSecondary; else -> VirlinColors.TextPrimary },
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                if (cancelled) "Cancelled" else null,
                row.task.estimatedEffort?.let { "Est ${it.toEffortLabel()}" }
            )
            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 11.sp, color = VirlinColors.TextTertiary)
        }
        row.progress?.let {
            Spacer(Modifier.width(8.dp))
            Text(it.text.substringAfterLast("· ").ifBlank { it.text }, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (it.fraction == null) VirlinColors.TextTertiary else VirlinColors.TextSecondary)
        }
        if (row.hasChildren) {
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier.size(40.dp)
                    .testTag(taskToggleTag(row.id))
                    .clickable(interactionSource = MutableInteractionSource(), indication = null, role = Role.Button) { onToggle(row.id) }
                    .semantics { contentDescription = if (row.expanded) "Collapse ${row.task.title}" else "Expand ${row.task.title}" },
                contentAlignment = Alignment.Center
            ) { Text(if (row.expanded) "▼" else "▶", fontSize = 11.sp, color = VirlinColors.TextTertiary) }
        }
    }
}

@Composable
private fun StatusMarker(row: TaskRow, onComplete: (String) -> Unit) {
    val terminal = row.status.isTerminal
    val desc = when (row.status) {
        TaskStatus.DONE -> "${row.task.title}, completed"
        TaskStatus.CANCELLED -> "${row.task.title}, cancelled"
        else -> "Mark ${row.task.title} complete"
    }
    Box(
        modifier = Modifier.size(40.dp)
            .testTag(taskCompleteTag(row.id))
            .clickable(enabled = !terminal, interactionSource = MutableInteractionSource(), indication = null, role = Role.Button) { onComplete(row.id) }
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) {
        when (row.status) {
            TaskStatus.DONE -> Box(Modifier.size(20.dp).background(VirlinColors.Emerald, CircleShape), Alignment.Center) { Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black) }
            TaskStatus.CANCELLED -> Box(Modifier.size(20.dp).border(1.5.dp, VirlinColors.TextTertiary, CircleShape), Alignment.Center) { Text("×", color = VirlinColors.TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Black) }
            else -> Box(Modifier.size(20.dp).border(1.5.dp, if (row.isCurrent) VirlinColors.TextPrimary else VirlinColors.TextTertiary, CircleShape), Alignment.Center) {
                if (row.isCurrent) Box(Modifier.size(9.dp).background(VirlinColors.TextPrimary, CircleShape))
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary)
}

@Composable
fun AddButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tag: String = AddTaskButtonTag) {
    Text(
        label, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = VirlinColors.TextPrimary,
        modifier = modifier
            .background(Color.White, RoundedCornerShape(50))
            .border(1.dp, Hairline, RoundedCornerShape(50))
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** Lightweight creation: title + optional estimate in minutes. Ownership is inherited by the caller. */
/**
 * PHASE 09 — one human focus at a time: name both sides before anything is written. CANCEL leaves
 * the current work exactly as it is; SWITCH closes that session (investment kept, work still
 * incomplete) and starts the new one.
 */
@Composable
fun SwitchFocusDialog(currentTitle: String, nextTitle: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.fillMaxWidth().background(VirlinColors.Background, RoundedCornerShape(20.dp)).padding(20.dp)
                .testTag(SwitchFocusTag)
        ) {
            Text("Switch focus?", fontSize = 16.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Text("CURRENTLY", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = VirlinColors.TextTertiary)
            Text(currentTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            Text("SWITCH TO", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = VirlinColors.TextTertiary)
            Text(nextTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text("Your current work stays incomplete and keeps its invested time.", fontSize = 11.5.sp, color = VirlinColors.TextSecondary)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextSecondary,
                    modifier = Modifier.testTag(SwitchFocusCancelTag).clickable(role = Role.Button, onClick = onCancel).padding(12.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Switch", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White,
                    modifier = Modifier.background(VirlinColors.TextPrimary, RoundedCornerShape(50))
                        .testTag(SwitchFocusConfirmTag).clickable(role = Role.Button, onClick = onConfirm)
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * Fast creation of a named container (Project / WorkStream): ONE field, Cancel / Create.
 * Deliberately not a wizard — creation must take a second.
 */
@Composable
fun AddNameDialog(title: String, placeholder: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().background(VirlinColors.Background, RoundedCornerShape(20.dp)).padding(20.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary)
            Spacer(Modifier.height(14.dp))
            Field(text, { text = it }, placeholder, AddNameTitleTag, VirlinColors.TextPrimary)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextSecondary,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onDismiss).padding(12.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Create", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White,
                    modifier = Modifier
                        .background(VirlinColors.TextPrimary, RoundedCornerShape(50))
                        .testTag(AddNameConfirmTag)
                        .clickable(role = Role.Button) { if (text.isNotBlank()) { onConfirm(text.trim()); onDismiss() } }
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
fun AddTaskDialog(title: String, onDismiss: () -> Unit, onConfirm: (String, Duration?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().background(VirlinColors.Background, RoundedCornerShape(20.dp)).padding(20.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary)
            Spacer(Modifier.height(14.dp))
            Field(text, { text = it }, "Title", AddTaskTitleTag, VirlinColors.TextPrimary)
            Spacer(Modifier.height(10.dp))
            Field(minutes, { minutes = it.filter(Char::isDigit) }, "Estimate (minutes, optional)", "add_task_minutes", VirlinColors.TextPrimary)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextSecondary,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onDismiss).padding(12.dp))
                Spacer(Modifier.width(8.dp))
                val ok = text.isNotBlank()
                Text("ADD", fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White,
                    modifier = Modifier
                        .background(if (ok) VirlinColors.TextPrimary else VirlinColors.TextPrimary.copy(alpha = 0.3f), RoundedCornerShape(50))
                        .testTag(AddTaskConfirmTag)
                        .clickable(enabled = ok, role = Role.Button) { onConfirm(text.trim(), minutes.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofMinutes)) }
                        .padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, tag: String, color: Color) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = color),
        cursorBrush = SolidColor(VirlinColors.Emerald),
        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).border(1.dp, Hairline, RoundedCornerShape(12.dp)).padding(12.dp).testTag(tag),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = VirlinColors.TextTertiary); inner() } }
    )
}
