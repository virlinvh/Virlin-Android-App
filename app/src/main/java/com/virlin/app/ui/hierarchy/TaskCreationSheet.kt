package com.virlin.app.ui.hierarchy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.BulkHierarchy
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.PhaseImportPrompt
import com.virlin.app.domain.model.ExecutionPreference
import kotlinx.coroutines.launch

/** Task and subtask creation stays within the app viewport, including every choice and preview. */
@Composable
internal fun TaskCreationSheet(
    parentTitle: String,
    defaultExecution: StreamExecutionMode,
    siblings: List<StreamTaskUi>,
    onDismiss: () -> Unit,
    onSave: (String, String?, StreamExecutionMode) -> Unit,
    heading: String,
    bulkDestination: CreateTask?,
    errorMessage: String?
) {
    var bulk by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var after by rememberSaveable { mutableStateOf(siblings.lastOrNull()?.id) }
    var mode by rememberSaveable { mutableStateOf(StreamExecutionMode.INHERIT) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val palette = LocalProjectPalette.current
    val green = palette?.let { Color(it.main) } ?: Color(0xFF007B58)
    val greenInk = palette?.let { Color(it.secondary) } ?: Color(0xFF8B671C)
    val wash = palette?.let { lerp(Color.White, Color(it.main), .14f) } ?: Color(0xFFFAEDCD)
    val ink = Color(0xFF263247)
    val muted = Color(0xFF647089)
    val line = Color(0xFFE0E4EB)
    val dismiss = { if (!busy) onDismiss() }
    val canContinue = if (bulk) input.isNotBlank() else name.isNotBlank()
    BackHandler { if (choosing != null) choosing = null else if (preview != null) preview = null else dismiss() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .32f)).testTag("add_task_overlay")) {
        Box(Modifier.matchParentSize().clickable(onClick = dismiss))
        BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(16.dp),
            contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp).heightIn(max = maxHeight)
                    .testTag("add_task_dialog"),
                shape = RoundedCornerShape(24.dp),
                color = Color.White, shadowElevation = 8.dp
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(wash, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, null, Modifier.size(20.dp), tint = greenInk)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(heading, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = ink)
                            Text("In $parentTitle", fontSize = 12.sp, color = muted,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = dismiss, enabled = !busy,
                            modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Close, "Close task creation", tint = muted,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (bulkDestination != null) {
                        Row(Modifier.fillMaxWidth().background(Color(0xFFF5F6F9), RoundedCornerShape(10.dp))
                            .padding(2.dp)) {
                            listOf(false to "Single", true to "Bulk").forEach { (value, label) ->
                                Surface(onClick = { if (!busy) { bulk = value; choosing = null; preview = null; error = null } },
                                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                                    color = if (bulk == value) wash else Color.Transparent,
                                    border = null) {
                                    Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                                        Text(label, fontSize = 13.sp,
                                            color = if (bulk == value) greenInk else muted)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .testTag("task_creation_form")) {
                        when (choosing) {
                            "placement" -> {
                                Text("Place ${heading.removePrefix("Add ")}", fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold, color = ink)
                                Spacer(Modifier.height(10.dp))
                                TaskChoiceRow("At beginning", after == null, greenInk, wash, line) {
                                    after = null; choosing = null
                                }
                                siblings.forEach { sibling ->
                                    TaskChoiceRow("After ${sibling.title}", after == sibling.id, greenInk, wash, line) {
                                        after = sibling.id; choosing = null
                                    }
                                }
                            }
                            "execution" -> {
                                Text("Execution", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
                                Spacer(Modifier.height(10.dp))
                                StreamExecutionMode.entries.forEach { choice ->
                                    val label = when (choice) {
                                        StreamExecutionMode.INHERIT -> "Inherit · ${defaultExecution.label()}"
                                        StreamExecutionMode.HUMAN -> "Human"
                                        StreamExecutionMode.EXTERNAL -> "External"
                                    }
                                    TaskChoiceRow(label, mode == choice, greenInk, wash, line) {
                                        mode = choice; choosing = null
                                    }
                                }
                            }
                            "help" -> {
                                Text("Bulk import format", fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold, color = ink)
                                Spacer(Modifier.height(8.dp))
                                Text("Paste the same JSON hierarchy supported by bulk creation. Up to ${BulkHierarchy.MAX_ITEMS} items including children.",
                                    color = muted, fontSize = 13.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("{\"version\":2,\"items\":[{\"name\":\"First task\"}]}",
                                    Modifier.fillMaxWidth().background(Color(0xFFF4F6F7), RoundedCornerShape(12.dp))
                                        .padding(14.dp), color = ink, fontSize = 12.sp)
                                TextButton(onClick = { clipboard.setText(AnnotatedString(PhaseImportPrompt.text)) }) {
                                    Text("Copy AI instructions", color = greenInk)
                                }
                            }
                            else -> if (!bulk) {
                                Text(if (heading == "Add subtask") "Subtask name" else "Task name",
                                    color = muted, fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(name, { name = it },
                                    placeholder = { Text("Enter a name", color = muted, fontSize = 14.sp) },
                                    singleLine = true, modifier = Modifier.fillMaxWidth()
                                        .testTag(AddTaskTitleTag),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = greenInk, unfocusedBorderColor = line,
                                        cursorColor = greenInk))
                                Spacer(Modifier.height(12.dp))
                                TaskOptionRow("Placement", siblings.find { it.id == after }?.title?.let { "After $it" }
                                    ?: "At beginning", greenInk, wash, line) { choosing = "placement" }
                                Spacer(Modifier.height(10.dp))
                                TaskOptionRow("Execution", if (mode == StreamExecutionMode.INHERIT)
                                    "Inherit · ${defaultExecution.label()}" else mode.label(), greenInk, wash, line) {
                                    choosing = "execution"
                                }
                            } else if (preview == null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Paste JSON", Modifier.weight(1f), color = muted, fontSize = 13.sp)
                                    IconButton(onClick = { choosing = "help" }) {
                                        Icon(Icons.Outlined.Info, "Import format and AI prompt", tint = muted)
                                    }
                                }
                                OutlinedTextField(input, { input = it; error = null },
                                    placeholder = { Text("Paste your task hierarchy here", color = muted) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                                    minLines = 4, enabled = !busy,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = greenInk, unfocusedBorderColor = line,
                                        cursorColor = greenInk))
                                Spacer(Modifier.height(8.dp))
                                Text("Up to ${BulkHierarchy.MAX_ITEMS} items including children. Preview before adding.",
                                    fontSize = 12.sp, color = muted)
                                Spacer(Modifier.height(12.dp))
                                TaskOptionRow("Execution", if (mode == StreamExecutionMode.INHERIT)
                                    "Inherit · ${defaultExecution.label()}" else mode.label(), greenInk, wash, line) {
                                    choosing = "execution"
                                }
                            } else {
                                Text("Preview · ${preview!!.size} ${if (preview!!.size == 1) "item" else "items"}", fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold, color = ink)
                                Spacer(Modifier.height(10.dp))
                                Text("Execution: ${if (mode == StreamExecutionMode.INHERIT) "Inherit · ${defaultExecution.label()}" else mode.label()}",
                                    color = muted, fontSize = 12.sp)
                                Spacer(Modifier.height(8.dp))
                                preview!!.forEachIndexed { index, item ->
                                    Text("${index + 1}. $item", Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                        color = ink, fontSize = 14.sp)
                                }
                            }
                        }
                        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp, modifier = Modifier.testTag("add_task_error")) }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    }
                    HorizontalDivider(Modifier.padding(top = 14.dp), color = line)
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            when { choosing != null -> choosing = null; preview != null -> preview = null; else -> dismiss() }
                        }, enabled = !busy, modifier = Modifier.testTag("task_creation_cancel")) {
                            Text(if (choosing != null || preview != null) "Back" else "Cancel", color = greenInk)
                        }
                        Spacer(Modifier.width(6.dp))
                        if (choosing == null) Button(onClick = {
                            if (!bulk) onSave(name.trim(), after, mode)
                            else if (preview == null) {
                                try { preview = BulkHierarchy.parse(input).flatMap {
                                    it.preview(rootType = if (bulkDestination?.parentTaskId == null) "tasks" else "subtasks")
                                } } catch (exception: Exception) {
                                    error = exception.message ?: "Invalid JSON."
                                }
                            } else scope.launch {
                                busy = true; error = null
                                val destination = bulkDestination!!.copy(executionPreference = when (mode) {
                                    StreamExecutionMode.INHERIT -> ExecutionPreference.INHERIT
                                    StreamExecutionMode.HUMAN -> ExecutionPreference.HUMAN
                                    StreamExecutionMode.EXTERNAL -> ExecutionPreference.EXTERNAL
                                })
                                when (val result = VirlinGraph.actions.bulkCreateTasks(input, destination)) {
                                    is ActionResult.Success -> onDismiss()
                                    is ActionResult.Failure -> error = result.cause.message ?: "Nothing was added."
                                    else -> error = "Import was refused. Nothing was added."
                                }
                                busy = false
                            }
                        }, enabled = canContinue && !busy,
                            modifier = Modifier.heightIn(min = 44.dp).testTag(AddTaskConfirmTag),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = green,
                                contentColor = readableOn(green))) {
                            Text(when {
                                busy -> "Adding…"
                                !bulk -> heading
                                preview == null -> "Preview"
                                else -> "Add ${preview!!.size} items"
                            }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

private fun StreamExecutionMode.label(): String = name.lowercase().replaceFirstChar(Char::uppercase)

@Composable private fun TaskOptionRow(title: String, value: String, ink: Color, wash: Color,
    line: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Color.White,
        border = BorderStroke(1.dp, line)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(wash, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (title == "Placement") Icons.Default.DateRange else Icons.Default.Link,
                    null, tint = ink,
                    modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFF68778B), fontSize = 11.sp)
                Text(value, color = Color(0xFF17202C), fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0xFF8794A4))
        }
    }
}

@Composable private fun TaskChoiceRow(label: String, selected: Boolean, ink: Color, wash: Color,
    line: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp),
        color = if (selected) wash else Color.White, border = BorderStroke(1.dp, line),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = Color(0xFF17202C),
                fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (selected) Text("✓", color = ink, fontWeight = FontWeight.Bold)
        }
    }
}
