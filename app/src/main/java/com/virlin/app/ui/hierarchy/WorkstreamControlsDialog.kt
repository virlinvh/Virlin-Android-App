package com.virlin.app.ui.hierarchy

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.components.SafeDialog as Dialog
import com.virlin.app.domain.model.*
import com.virlin.app.ui.screens.AttentionIntentController
import com.virlin.app.ui.screens.NowViewModel.Chooser
import com.virlin.app.ui.screens.NowViewModel.TimedIntent

private val ControlInk: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.text) } ?: Color(0xFF17202C)
private val ControlMuted: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.muted) } ?: Color(0xFF65748B)
private val ControlGreen: Color @Composable get() = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF008D68)
private val ControlWash = Color(0xFFF4F8F7)

/** One modal window. Secondary steps swap its body, never open another dialog. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun WorkstreamControlsDialog(
    stream: WorkStream, projectDefault: EffectiveExecutionMode, timingSummary: String,
    onDismiss: () -> Unit, onMode: (ExecutionPreference) -> Unit, onFocus: () -> Unit,
    intents: AttentionIntentController,
    currentExecution: EffectiveExecutionMode = ExecutionModeResolver.resolveWorkStream(stream, projectDefault),
    pendingSwitch: HierarchyViewModel.PendingSwitch? = null,
    onConfirmSwitch: () -> Unit = {}, onCancelSwitch: () -> Unit = {},
    taskId: String? = null, onCompleteTask: (() -> Unit)? = null, allowSharedActivityActions: Boolean = true,
    externalStartConflict: String? = null, onOpenConflictingWork: () -> Unit = {},
    onRename: ((String) -> Unit)? = null, onAddSubtask: ((String) -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null, onCancelTask: (() -> Unit)? = null,
    moveDestinations: List<Pair<String?, String>> = emptyList(), onMove: ((String?) -> Unit)? = null,
    childActivities: List<Pair<String, String>> = emptyList(), onOpenActivity: (String) -> Unit = {},
    executionAllowed: Boolean = true,
    aggregateState: WorkstreamState? = null,
    responsibilitySource: String? = null,
    descendantCount: Int = 0,
    onDelete: (() -> Unit)? = null,
) {
    var expanded by remember(stream.id, taskId) { mutableStateOf(false) }
    var managing by remember(stream.id, taskId) { mutableStateOf<String?>(null) }
    var editText by remember(stream.id, taskId) { mutableStateOf("") }
    var completing by remember(stream.id, taskId) { mutableStateOf(false) }
    var startingExternal by remember(stream.id, taskId) { mutableStateOf(false) }
    var schedulingResume by remember(stream.id) { mutableStateOf(false) }
    var pausingExternal by remember(stream.id) { mutableStateOf(false) }
    var editingReason by remember(stream.id) { mutableStateOf(false) }
    var confirmingResult by remember(stream.id) { mutableStateOf(false) }
    var reasonText by remember(stream.id, stream.blockerReason) { mutableStateOf(stream.blockerReason.orEmpty()) }
    val chooser by intents.chooser.collectAsState()
    LaunchedEffect(chooser) {
        if (chooser == null) { startingExternal = false; schedulingResume = false; pausingExternal = false }
    }
    val feedback by intents.feedback.collectAsState()
    val terminal = stream.state.isTerminal
    val compactWorkstream = taskId == null && !executionAllowed
    val canComplete = !terminal && (onCompleteTask != null || WorkStreamTransitions.canTransition(stream.state, WorkStreamState.DONE))
    val effective = currentExecution
    val preferenceMode = if (taskId != null) effective else ExecutionModeResolver.resolveWorkStream(stream, projectDefault)
    val mode = if (preferenceMode == EffectiveExecutionMode.HUMAN) "Human" else "External"
    val page = when { managing != null -> managing!!; pendingSwitch != null -> "switch"; completing -> "complete"; confirmingResult -> "result"; editingReason -> "reason"; chooser != null -> chooser!!::class.simpleName.orEmpty(); else -> "main" }
    val back = { managing = null; completing = false; confirmingResult = false; editingReason = false; startingExternal = false; schedulingResume = false; intents.dismissChooser(); onCancelSwitch() }
    val palette = rememberProjectPalette(stream.projectId)
    CompositionLocalProvider(LocalProjectPalette provides palette) {
    Dialog(onDismissRequest = { if (page != "main") back() else onDismiss() }) {
        BackHandler { if (page != "main") back() else onDismiss() }
        Surface(shape = RoundedCornerShape(24.dp), color = palette?.let { Color(it.surface) } ?: Color.White) {
            Column(Modifier.fillMaxWidth().padding(20.dp).testTag("workstream_control_dialog")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (page != "main") IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ControlInk)
                    }
                    Text(if (taskId == null) "Workstream controls" else "Work controls", Modifier.weight(1f), fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, color = ControlInk)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, "Close controls", tint = ControlMuted)
                    }
                }
                Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stream.title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ControlInk)
                    if (!terminal) StatePill(aggregateState ?: when (stream.state) {
                        WorkStreamState.FOCUS -> WorkstreamState.HUMAN_FOCUS
                        WorkStreamState.PROCESSING -> WorkstreamState.EXTERNAL_RUNNING
                        WorkStreamState.CHECK -> WorkstreamState.NEEDS_ATTENTION
                        WorkStreamState.SNOOZED -> WorkstreamState.WAITING
                        WorkStreamState.BLOCKED -> WorkstreamState.BLOCKED
                        WorkStreamState.PAUSED -> WorkstreamState.PAUSED
                        else -> WorkstreamState.READY
                    })
                }
                if (!compactWorkstream) Text(timingSummary, color = ControlMuted, fontSize = 12.sp)
                HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0xFFE7ECEF))
                val stepContent: @Composable (String) -> Unit = { shownPage ->
                    // Stable viewport prevents text-input/lookahead remeasurement loops and
                    // keeps the popup/header in the same position when changing steps.
                    Column(Modifier.fillMaxWidth().then(if (compactWorkstream) Modifier.heightIn(max = 360.dp) else Modifier.height(360.dp)).then(
                        if (shownPage == "rename" || shownPage == "add") Modifier
                        else Modifier.verticalScroll(rememberScrollState())),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (shownPage) {
                            "timer" -> {
                                StepTitle("Timer display")
                                Hint("This section and its descendants only. Display settings never start or stop work.")
                                TimerDisplaySettings(stream.id)
                            }
                            "add" -> {
                                TaskCreationCard(stream.title, "Add subtask", back, {
                                    onAddSubtask?.invoke(it); managing = null
                                }, titleTag = "work_name_input") {
                                    Text("Use parent · At end", color = ControlMuted, fontSize = 12.sp)
                                }
                            }
                            "rename" -> {
                                StepTitle(if (shownPage == "rename") "Edit name" else "Add subtask")
                                Hint("Name")
                                OutlinedTextField(editText, { editText = it }, singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = ControlInk, fontSize = 16.sp),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("work_name_input"))
                                PrimaryAction("Save", enabled = editText.isNotBlank()) {
                                    if (shownPage == "rename") onRename?.invoke(editText.trim()) else onAddSubtask?.invoke(editText.trim())
                                    managing = null
                                }
                            }
                            "move" -> {
                                StepTitle("Move to…")
                                moveDestinations.forEach { (id, title) -> ActionRow(title) { onMove?.invoke(id); managing = null } }
                            }
                            "cancelTask" -> {
                                StepTitle("Cancel task?")
                                Hint(if (descendantCount > 0) "Cancel this task and its $descendantCount remaining descendants. Their timers stop; recorded time remains saved. Other tasks stay unchanged." else "This cancels this task, not the other tasks in its workstream. Recorded time remains saved.")
                                PrimaryAction("Confirm cancellation") { onCancelTask?.invoke(); managing = null }
                                ActionRow("Keep task", onClick = back)
                            }
                            "result" -> {
                                StepTitle("Result ready?")
                                Hint("Confirm the external work has finished. Tracking ends; the workstream stays available for review, not completed.")
                                PrimaryAction("Confirm result ready") { confirmingResult = false; intents.confirmExternalResult(stream.id) }
                                ActionRow("Cancel", onClick = back)
                            }
                            "reason" -> {
                                StepTitle("Block reason")
                                OutlinedTextField(value = reasonText, onValueChange = { reasonText = it },
                                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
                                PrimaryAction("Save reason", enabled = reasonText.isNotBlank()) {
                                    editingReason = false; intents.editBlockReason(stream.id, reasonText)
                                }
                            }
                            "complete" -> {
                                StepTitle(if (taskId == null) "Complete workstream?" else "Complete task?")
                                Hint(if (compactWorkstream) "Complete all remaining tasks and subtasks in this workstream and stop their timers. Recorded time is preserved. Other workstreams stay unchanged." else if (descendantCount > 0) "Complete this task and its $descendantCount remaining descendants and stop their timers. Recorded time is preserved. Other tasks stay unchanged." else "End this item's tracking and save its time. Other tasks stay unchanged. Nothing else starts automatically.")
                                PrimaryAction(if (taskId == null) "Complete workstream" else "Complete task", enabled = canComplete) {
                                    completing = false; if (onCompleteTask != null) onCompleteTask() else intents.completeStream(stream.id)
                                }
                                ActionRow("Cancel", onClick = back)
                            }
                            "switch" -> {
                                StepTitle("Switch human focus?")
                                Hint("Stop focus on ${pendingSwitch?.currentTitle.orEmpty()} and focus ${pendingSwitch?.nextTitle.orEmpty()}. Its recorded time stays saved.")
                                PrimaryAction("Switch focus", onClick = onConfirmSwitch)
                                ActionRow("Cancel", onClick = back)
                            }
                            "main" -> {
                                if (compactWorkstream) {
                                    onRename?.let { ActionRow("Edit name") { editText = stream.title; managing = "rename" } }
                                    ActionRow(if (terminal) "Completed" else "Mark as complete",
                                        if (terminal) "Time and history preserved" else "Complete this workstream and its remaining tasks",
                                        enabled = canComplete, tag = "workstream_complete") { completing = true }
                                    onDelete?.let { TextButton(onClick = it) { Text("Delete workstream", color = MaterialTheme.colorScheme.error) } }
                                } else {
                                if (executionAllowed) ActionRow("Who does this?", "$mode" + if (stream.executionPreference == ExecutionPreference.INHERIT) " · inherited" else "",
                                    enabled = !terminal, tag = "workstream_mode_expand", onClick = { expanded = !expanded })
                                // Disclosure changes content, not the dialog window's animated size.
                                if (expanded && executionAllowed) {
                                    Column(Modifier.background(ControlWash, RoundedCornerShape(12.dp)).padding(8.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            listOf(ExecutionPreference.INHERIT, ExecutionPreference.HUMAN, ExecutionPreference.EXTERNAL).forEach { value ->
                                                val selected = stream.executionPreference == value
                                                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                                    .background(if (selected) ControlGreen else Color.White, RoundedCornerShape(9.dp))
                                                    .clickable(role = Role.RadioButton) { onMode(value) }, contentAlignment = Alignment.Center) {
                                                    Text(when(value) { ExecutionPreference.INHERIT -> "Use parent"; ExecutionPreference.HUMAN -> "Human"; else -> "External" },
                                                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                                        color = if (selected) Color.White else ControlInk)
                                                }
                                            }
                                        }
                                        Hint("Resolved responsibility: $mode. Use parent follows the nearest inherited default. Changing it does not start work automatically.")
                                        responsibilitySource?.let { Hint("Inherited from $it") }
                                        if (stream.processingStartedAt != null)
                                            Hint("Pause external tracking before changing running work to Human. Changing the mode never starts focus automatically.")
                                    }
                                }
                                if (!terminal) {
                                    if (executionAllowed) ActionRow("Timer display", "Focused / External · every ${timerRotationSeconds(stream.id)} seconds") { managing = "timer" }
                                    if (executionAllowed) {
                                    Hint("Current work: ${if(effective == EffectiveExecutionMode.HUMAN) "Human" else "External"}" +
                                        if (stream.activeTaskId != null) " · follows the current task" else " · follows workstream settings")
                                    if (stream.externalTrackingPaused) {
                                        Hint("External tracking is paused. The external process is not controlled by Virlin.")
                                        Hint("${com.virlin.app.domain.model.FocusInvestment.formatCompact(stream.externalTrackedSeconds)} tracked · paused time excluded")
                                        PrimaryAction("Resume external tracking") { intents.resumeExternal(stream.id) }
                                        ActionRow("Change return reminder", "Choose a time or remain paused indefinitely") { pausingExternal = true; intents.openLeave(stream.id) }
                                    } else when (stream.state) {
                                        WorkStreamState.FOCUS -> {
                                            PrimaryAction("Pause focus") { intents.openLeave(stream.id) }
                                            if (effective == EffectiveExecutionMode.EXTERNAL) ActionRow("Hand off", "Start external tracking") { intents.openHandOff(stream.id) }
                                        }
                                        WorkStreamState.PROCESSING -> {
                                            ActionRow("Check progress", "Still running, ready or blocked") { intents.openCheck(stream.id) }
                                            ActionRow("Pause tracking", "Save tracked time · choose when to return") { pausingExternal = true; intents.openLeave(stream.id) }
                                        }
                                        WorkStreamState.READY -> if(effective == EffectiveExecutionMode.EXTERNAL) {
                                            if (externalStartConflict != null) {
                                                Hint("$externalStartConflict is already active in this workstream. No timer has been set for this task; starting it would replace that activity.")
                                                ActionRow("View active work", "Resolve that activity before starting another", onClick = onOpenConflictingWork)
                                            } else PrimaryAction("Start external run") { intents.clearFeedback(); startingExternal = true; intents.openHandOff(stream.id) }
                                        } else PrimaryAction("Focus now", onClick = onFocus)
                                        WorkStreamState.PAUSED -> {
                                            if (effective == EffectiveExecutionMode.EXTERNAL) PrimaryAction("Start external run") { intents.clearFeedback(); startingExternal = true; intents.openHandOff(stream.id) }
                                            else {
                                                PrimaryAction("Resume focus", onClick = onFocus)
                                                ActionRow("Resume later", "Choose a return reminder") { schedulingResume = true; intents.openLeave(stream.id) }
                                            }
                                        }
                                        WorkStreamState.BLOCKED -> {
                                            Hint(stream.blockerReason?.takeIf { it.isNotBlank() } ?: "No block reason recorded")
                                            PrimaryAction("Unblock", onClick = { intents.unblockWorkstream(stream.id) })
                                            ActionRow("Edit reason") { editingReason = true }
                                        }
                                        WorkStreamState.CHECK -> when(stream.snoozeReason) {
                                            SnoozeReason.EXTERNAL_RESULT_READY -> { PrimaryAction("Review now", onClick = onFocus); ActionRow("Remind later") { intents.openDefer(stream.id) } }
                                            SnoozeReason.HUMAN_RETURN -> { PrimaryAction("Resume focus", onClick = onFocus); ActionRow("Remind later") { intents.openDefer(stream.id) } }
                                            null -> {
                                                if (effective == EffectiveExecutionMode.HUMAN && stream.processingStartedAt == null) {
                                                    PrimaryAction("Focus now", onClick = onFocus)
                                                    ActionRow("Remind later") { intents.openDefer(stream.id) }
                                                } else {
                                                    Hint("Check due · result not yet confirmed")
                                                    ActionRow("Check progress") { intents.openCheck(stream.id) }
                                                    if (stream.processingStartedAt != null) ActionRow("Pause tracking") { pausingExternal = true; intents.openLeave(stream.id) }
                                                }
                                            }
                                        }
                                        WorkStreamState.SNOOZED -> {
                                            PrimaryAction(if(stream.snoozeReason == SnoozeReason.EXTERNAL_RESULT_READY) "Review now" else "Resume focus", onClick = onFocus)
                                            ActionRow("Change reminder") { intents.openDefer(stream.id) }
                                        }
                                        else -> PrimaryAction(if(stream.state == WorkStreamState.PAUSED) "Resume focus" else "Focus now", onClick = onFocus)
                                    }
                                    val progressOutcomesAvailable = stream.state == WorkStreamState.PROCESSING ||
                                        (stream.state == WorkStreamState.CHECK && stream.snoozeReason == null && effective == EffectiveExecutionMode.EXTERNAL)
                                    if (allowSharedActivityActions && !progressOutcomesAvailable && WorkStreamTransitions.canTransition(stream.state, WorkStreamState.BLOCKED))
                                        ActionRow(if (taskId == null) "Block workstream" else "Block task", "An obstacle prevents progress") { intents.block(stream.id) }
                                    if (stream.state != WorkStreamState.READY && WorkStreamTransitions.canTransition(stream.state, WorkStreamState.READY))
                                        ActionRow("End activity", "Save time and clear reminders before adding children") { intents.markReady(stream.id) }
                                    }
                                }
                                HorizontalDivider(color = Color(0xFFE7ECEF))
                                ActionRow(if(terminal) "Completed" else "Mark as complete",
                                    if(terminal) "Time and history preserved" else "Confirmation required",
                                    enabled = canComplete, tag = "workstream_complete") { completing = true }
                                if (executionAllowed && !terminal && !canComplete) ActionRow("End reminder", "Make ready before completion") { intents.markReady(stream.id) }
                                if (onRename != null || onAddSubtask != null) {
                                    HorizontalDivider(color = Color(0xFFE7ECEF))
                                    StepTitle("Manage")
                                    onRename?.let { ActionRow("Edit name") { editText = stream.title; managing = "rename" } }
                                    onAddSubtask?.let { ActionRow("Add subtask") { editText = ""; managing = "add" } }
                                    onMove?.let { ActionRow("Move to…") { managing = "move" } }
                                    onDuplicate?.let { ActionRow("Duplicate", onClick = it) }
                                    if (!terminal) onCancelTask?.let { ActionRow("Cancel task") { managing = "cancelTask" } }
                                }
                                onDelete?.let { TextButton(onClick = it) { Text(if (taskId == null) "Delete workstream" else "Delete task", color = MaterialTheme.colorScheme.error) } }
                                }
                            }
                            else -> chooser?.let { InlineTimingStep(it, intents, onFocus, startingExternal, schedulingResume, pausingExternal, taskId,
                                onResultReady = { intents.dismissChooser(); confirmingResult = true }) }
                        }
                    }
                }
                // Exactly one keyed viewport. Retaining an outgoing scroll body while a text
                // editor is inserted can cause perpetual remeasurement on narrow dialogs.
                key(page) { stepContent(page) }
                feedback?.let { text ->
                    Spacer(Modifier.height(10.dp))
                    Text(text, Modifier.background(ControlWash, RoundedCornerShape(10.dp)).padding(10.dp),
                        fontSize = 12.sp, color = ControlInk)
                }
            }
        }
    }
}

}

@Composable private fun StepTitle(text: String) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = ControlInk) }
@Composable private fun Hint(text: String) { Text(text, fontSize = 12.sp, color = ControlMuted, modifier = Modifier.padding(vertical = 4.dp)) }
@Composable private fun ActionRow(text: String, subtitle: String? = null, enabled: Boolean = true, tag: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(ControlWash, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ControlInk); subtitle?.let { Hint(it) } }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = ControlMuted, modifier = Modifier.size(18.dp))
    }
}
@Composable private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ControlGreen, contentColor = Color.White),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

/** Same controller, durations and permission contract as Now, only a different presentation. */
@Composable private fun InlineTimingStep(chooser: Chooser, intents: AttentionIntentController, onFocus: () -> Unit,
    startingExternal: Boolean = false, schedulingResume: Boolean = false, pausingExternal: Boolean = false,
    taskId: String? = null, onResultReady: () -> Unit = {}) {
    val id = chooser.streamId
    if (chooser is Chooser.CheckOutcome) {
        StepTitle("How is it progressing?")
        ActionRow("Still running", "Keep tracking · set next check") { intents.openStillRunning(id) }
        ActionRow("Result ready", "Finish external time · review next", onClick = onResultReady)
        ActionRow("Blocked", "An obstacle prevents progress") { intents.block(id) }
        return
    }
    val intent = when(chooser) {
        is Chooser.Leave -> TimedIntent.LEAVE
        is Chooser.HandOff -> TimedIntent.HAND_OFF
        is Chooser.StillRunning -> TimedIntent.STILL_RUNNING
        is Chooser.ResultReady -> TimedIntent.RESULT_READY_LATER
        is Chooser.Defer -> TimedIntent.DEFER_RETURN
        is Chooser.Custom -> chooser.intent
        else -> return
    }
    var selection by remember(chooser) { mutableStateOf<Long?>(null) }
    var custom by remember(chooser) { mutableStateOf(false) }
    var input by remember(chooser) { mutableStateOf("") }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val title = when { pausingExternal -> "Pause external tracking"; startingExternal -> "Start external run"; schedulingResume -> "Resume later"; else -> when(intent) { TimedIntent.LEAVE -> "Pause focus"; TimedIntent.HAND_OFF -> "Hand off to external work"; TimedIntent.STILL_RUNNING -> "Next progress check"; else -> "Review reminder" } }
    StepTitle(title)
    if (pausingExternal) Hint("This pauses Virlin's timer, not the external process. A reminder never resumes tracking automatically.")
    if (chooser is Chooser.ResultReady) { PrimaryAction("Review now") { intents.dismissChooser(); onFocus() }; Hint("Or choose a reminder below") }
    Hint(if(intent == TimedIntent.LEAVE) "When would you like to return?" else "Choose minutes from now")
    val minutes = when(intent) {
        TimedIntent.HAND_OFF -> listOf(1, 2, 5, 10, 15)
        TimedIntent.STILL_RUNNING -> com.virlin.app.domain.attention.AttentionTiming.checkAgainPresets.map { it.toInt() }
        else -> listOf(3, 5, 10, 15)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        minutes.forEach { minute ->
            val selected = selection == minute.toLong() && !custom
            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                .background(if(selected) ControlGreen else ControlWash, RoundedCornerShape(10.dp))
                .clickable { selection = minute.toLong(); custom = false }, contentAlignment = Alignment.Center) {
                Text("${minute}m", color = if(selected) Color.White else ControlInk, fontSize = 12.sp)
            }
        }
    }
    ActionRow("Custom time") { custom = !custom }
    if (custom) {
        Hint("Minutes")
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxWidth().height(48.dp).background(ControlWash, RoundedCornerShape(10.dp))
                .testTag("workstream_custom_minutes"),
            factory = { context -> android.widget.EditText(context).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setSingleLine(true)
                textSize = 16f
                setTextColor(android.graphics.Color.rgb(23, 32, 44))
                background = null
                setPadding(24, 0, 24, 0)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { input = s.toString().filter(Char::isDigit) }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            } },
            update = { field -> if (field.text.toString() != input) field.setText(input) }
        )
    }
    val chosen = if(custom) input.toLongOrNull() else selection
    PrimaryAction(if(intent == TimedIntent.LEAVE) "Confirm pause" else "Confirm reminder", enabled = chosen != null && chosen > 0) {
        if (chosen != null) {
            when {
                pausingExternal -> { intents.dismissChooser(); intents.pauseExternal(id, chosen) }
                startingExternal -> { intents.dismissChooser(); intents.startExternalRun(id, chosen, taskId) }
                schedulingResume -> { intents.dismissChooser(); intents.resumeLater(id, chosen) }
                else -> intents.customMinutes(id, intent, chosen)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 && !com.virlin.app.platform.AttentionNotifications.canPost(context)) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    if(intent == TimedIntent.LEAVE && !schedulingResume) ActionRow("Pause without reminder", "Return whenever you are ready") {
        intents.dismissChooser(); if (pausingExternal) intents.pauseExternal(id, null) else intents.pauseWorkstream(id)
    }
    if(intent == TimedIntent.HAND_OFF) ActionRow("No check reminder", "External tracking continues") {
        if(startingExternal) { intents.dismissChooser(); intents.startExternalRun(id, null, taskId) } else intents.handOff(id, null)
    }
}
