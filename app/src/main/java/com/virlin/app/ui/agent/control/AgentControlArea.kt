package com.virlin.app.ui.agent.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import com.virlin.app.domain.command.VirlinCommand
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.hierarchy.TaskTreeRow
import com.virlin.app.ui.screens.CompleteWorkStreamDialog
import com.virlin.app.ui.screens.NowChooserDialog
import com.virlin.app.ui.theme.VirlinColors

/**
 * CONTROL mode content inside the frozen Agent shell: current focus, attention items,
 * working-for-you, ready — each with its structured controls — plus a compact recursive task
 * picker. Reuses the shared chooser dialog, the whole-WorkStream confirmation and the
 * hierarchy row component. Pure rendering of [AgentControlState]; every tap is a ViewModel
 * intent. No free-text command execution.
 */
private val Hairline = Color(0x1F162016)

const val AgentControlTag = "agent_control"
const val AgentControlFocusTag = "agent_control_focus"
const val AgentControlFeedbackTag = "agent_control_feedback"
const val AgentTaskPickerTag = "agent_task_picker"
fun controlItemTag(id: String) = "control_item_$id"
fun controlActionTag(id: String, a: ControlAction) = "control_${a.name.lowercase()}_$id"
fun quickActionTag(a: QuickAction) = "control_quick_${a.name.lowercase()}"
const val AgentControlQuickActionsTag = "agent_control_quick_actions"
const val AgentControlSuggestedTag = "agent_control_suggested"

// Stitch Control palette (low saturation).
private val Neutral400 = Color(0xFF9CA3AF)
private val Neutral500 = Color(0xFF6B7280)
private val Neutral800 = Color(0xFF1F2937)
private val Neutral900 = Color(0xFF111827)
private val CardBorder = Color(0xD9E5E7EB)

@Composable
fun AgentControlArea(
    vm: AgentControlViewModel,
    modifier: Modifier = Modifier,
    /** Typed command sink for Quick Actions without a target (the existing command panel / clarification). */
    onCommand: (VirlinCommand) -> Unit = {}
) {
    val state by vm.state.collectAsState()
    val chooser by vm.intents.chooser.collectAsState()
    val pendingCompletion by vm.intents.pendingWorkStreamCompletion.collectAsState()
    val feedback by vm.intents.feedback.collectAsState()

    chooser?.let { NowChooserDialog(it, vm.intents) }
    pendingCompletion?.let { id ->
        val title = (listOfNotNull(state.currentFocus) + state.needsAttention + state.processing + state.ready).firstOrNull { it.streamId == id }?.title ?: "this WorkStream"
        CompleteWorkStreamDialog(title, onConfirm = vm.intents::confirmCompleteWorkStream, onDismiss = vm.intents::dismissWorkStreamCompletion)
    }

    Column(modifier = modifier.fillMaxWidth().testTag(AgentControlTag)) {
        feedback?.let {
            Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.Emerald,
                modifier = Modifier.testTag(AgentControlFeedbackTag).padding(bottom = 8.dp))
        }
        if (state.selectedStreamId != null) {
            TaskPicker(state, vm)
            return@Column
        }

        // ---- QUICK ACTIONS (Stitch): four equal tiles over the existing control paths.
        SectionLabel("QUICK ACTIONS")
        Row(modifier = Modifier.fillMaxWidth().testTag(AgentControlQuickActionsTag), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction.values().forEach { a -> QuickActionTile(a, modifier = Modifier.weight(1f)) { vm.quick(a, onCommand) } }
        }
        Spacer(Modifier.height(22.dp))

        // ---- RECENT / SUGGESTED: real Control state — current FOCUS · needs you · working · ready.
        SectionLabel("RECENT / SUGGESTED")
        val rows = state.suggested
        if (rows.isEmpty()) {
            Text("Nothing to control right now", fontSize = 13.sp, color = Neutral500, modifier = Modifier.testTag(AgentControlFocusTag).padding(vertical = 4.dp))
        }
        Column(modifier = Modifier.fillMaxWidth().testTag(AgentControlSuggestedTag), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { item ->
                // The current-focus row keeps its own outer identity for tests (one testTag per node).
                if (item.streamId == state.currentFocus?.streamId) Box(Modifier.fillMaxWidth().testTag(AgentControlFocusTag).semantics { contentDescription = item.description }) { SuggestedRow(item, expanded = item.streamId == state.expandedItemId, vm = vm) }
                else SuggestedRow(item, expanded = item.streamId == state.expandedItemId, vm = vm)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Neutral400, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
}

// ---------------------------------------------------------------- Quick Actions

private data class QuickLook(val top: Color, val bottom: Color, val border: Color, val icon: ImageVector, val tint: Color, val filled: Color?)

private fun lookOf(a: QuickAction) = when (a) {
    QuickAction.FOCUS -> QuickLook(Color(0xFFECFDF5), Color(0x99D1FAE5), Color(0xFFD1FAE5), Icons.Rounded.Description, Color(0xFF047857), null)          // mint / Virlin green
    QuickAction.LEAVE -> QuickLook(Color(0xFFFFFBEB), Color(0x80FEF3C7), Color(0xFFFEF3C7), Icons.Rounded.Bolt, Color.White, Color(0xFFF59E0B))           // soft amber
    QuickAction.HAND_OFF -> QuickLook(Color(0xFFEEF6F3), Color(0x80DDEFE7), Color(0xFFDDEFE7), Icons.Rounded.Groups, Color(0xFF2F7A62), null)            // cool mint neutral (no blue token)
    QuickAction.BLOCK -> QuickLook(Color(0xFFFFF1F2), Color(0x80FFE4E6), Color(0xFFFFE4E6), Icons.Rounded.Close, Color.White, Color(0xFFF43F5E))        // soft rose
}

@Composable
private fun QuickActionTile(a: QuickAction, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val l = lookOf(a)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.testTag(quickActionTag(a)).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "Quick action ${a.label}" }
    ) {
        Box(
            modifier = Modifier.size(58.dp)
                .background(Brush.verticalGradient(listOf(l.top, l.bottom)), RoundedCornerShape(16.dp))
                .border(1.dp, l.border, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (l.filled != null) Box(Modifier.size(24.dp).background(l.filled, CircleShape), contentAlignment = Alignment.Center) {
                Icon(l.icon, contentDescription = null, tint = l.tint, modifier = Modifier.size(14.dp))
            } else Icon(l.icon, contentDescription = null, tint = l.tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(a.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Neutral800, maxLines = 1)
    }
}

// ---------------------------------------------------------------- Recent / Suggested rows

private val ControlItem.description get() = listOfNotNull(projectTitle, title, taskTitle, detail).joinToString(", ")

private data class TileLook(val bg: Color, val border: Color, val icon: ImageVector, val tint: Color)

private fun tileOf(kind: ControlKind) = when (kind) {
    ControlKind.FOCUS_HUMAN, ControlKind.FOCUS_EXTERNAL -> TileLook(Color(0xFFFBF7EE), Color(0xFFFEF3C7), Icons.Rounded.Description, Color(0xFF735A22))
    ControlKind.CHECK_DUE, ControlKind.RESULT_READY_DUE, ControlKind.RESULT_READY_PENDING, ControlKind.PROCESSING ->
        TileLook(Color(0xFFEDF8F4), Color(0xFFD1FAE5), Icons.Rounded.CenterFocusStrong, Color(0xFF059669))
    ControlKind.RETURN_DUE, ControlKind.RETURN_PENDING, ControlKind.READY -> TileLook(Color(0xFFF6F8EC), Color(0xFFE4ECCC), Icons.Rounded.Layers, Color(0xFF67792B))
    ControlKind.BLOCKED -> TileLook(Color(0xFFFFF1F2), Color(0xFFFFE4E6), Icons.Rounded.Block, Color(0xFFBE123C))
}

/** Icon tile · title · concise state · chevron. Tap = reveal this item's existing structured controls. */
@Composable
private fun SuggestedRow(item: ControlItem, expanded: Boolean, vm: AgentControlViewModel, modifier: Modifier = Modifier) {
    val t = tileOf(item.kind)
    Column(
        modifier = modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .border(1.dp, if (expanded) VirlinColors.Emerald.copy(alpha = 0.45f) else CardBorder, RoundedCornerShape(16.dp))
            .testTag(controlItemTag(item.streamId))
            .semantics { contentDescription = item.description; this.selected = expanded }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.toggleItem(item.streamId) }.padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Box(Modifier.size(44.dp).background(t.bg, RoundedCornerShape(12.dp)).border(1.dp, t.border, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(t.icon, contentDescription = null, tint = t.tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(listOfNotNull(item.title, item.projectTitle?.takeIf { it != item.title }).joinToString(" · "), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Neutral900, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(item.taskTitle, item.detail).joinToString(" · "), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Neutral400, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Neutral400, modifier = Modifier.size(18.dp))
        }
        if (expanded) {
            // The item's existing structured controls (identical semantics to Now / the previous cards).
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                item.actions.forEach { a ->
                    val primary = a != ControlAction.TASKS && a != ControlAction.DEFER && a != ControlAction.COMPLETE
                    Text(
                        a.label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp,
                        color = if (primary) Color.White else VirlinColors.TextPrimary,
                        modifier = Modifier.heightIn(min = 36.dp)
                            .background(if (primary) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
                            .border(1.dp, if (primary) Color.Transparent else Hairline, RoundedCornerShape(50))
                            .testTag(controlActionTag(item.streamId, a))
                            .clickable(role = Role.Button) { vm.act(item.streamId, a) }
                            .semantics { contentDescription = "${a.label} ${item.title}" }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskPicker(state: AgentControlState, vm: AgentControlViewModel) {
    Column(modifier = Modifier.fillMaxWidth().testTag(AgentTaskPickerTag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextSecondary,
                modifier = Modifier.testTag("agent_tasks_back").clickable(role = Role.Button) { vm.closeTasks() }.padding(vertical = 6.dp))
            Spacer(Modifier.width(10.dp))
            Text("TASKS · ${state.selectedStreamTitle ?: ""}", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        state.nextCandidate?.let { next ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("Next available: ${next.title}", fontSize = 12.sp, color = VirlinColors.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("SET CURRENT", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                    modifier = Modifier.background(VirlinColors.TextPrimary, RoundedCornerShape(50)).testTag("agent_next_set_current")
                        .clickable(role = Role.Button) { vm.setCurrent(next.id); vm.dismissNextCandidate() }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
        if (state.taskRows.isEmpty()) Text("No tasks yet — this WorkStream is the work item itself.", fontSize = 12.sp, color = VirlinColors.TextSecondary, modifier = Modifier.padding(vertical = 6.dp))
        state.taskRows.forEach { row -> androidx.compose.runtime.key(row.id) {
            val selected = row.id == state.selectedTaskId
            Column(modifier = Modifier.fillMaxWidth().background(if (selected) VirlinColors.FocusSurface.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(12.dp))) {
                TaskTreeRow(row, onOpen = { vm.selectTask(if (selected) null else it) }, onToggle = vm::toggleExpanded, onComplete = vm::completeTask)
                if (selected && !row.status.isTerminal) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                        if (!row.isCurrent) Chip("SET CURRENT", "agent_task_set_current", primary = true) { vm.setCurrent(row.id) }
                        Chip("COMPLETE", "agent_task_complete", primary = false) { vm.completeTask(row.id) }
                        Chip("CANCEL", "agent_task_cancel", primary = false) { vm.requestCancel(row.id) }
                    }
                }
            }
        } }
        state.pendingCancelTaskId?.let { id ->
            val title = state.taskRows.firstOrNull { it.id == id }?.task?.title ?: "this task"
            Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).background(Color.White, RoundedCornerShape(12.dp)).border(1.dp, Hairline, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("Cancel “$title”? It leaves the planned scope; it is not completed.", fontSize = 12.sp, color = VirlinColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("KEEP", "agent_cancel_keep", primary = false) { vm.dismissCancel() }
                    Chip("CANCEL TASK", "agent_cancel_confirm", primary = true) { vm.confirmCancel() }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, tag: String, primary: Boolean, onClick: () -> Unit) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp,
        color = if (primary) Color.White else VirlinColors.TextPrimary,
        modifier = Modifier.heightIn(min = 36.dp)
            .background(if (primary) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
            .border(1.dp, if (primary) Color.Transparent else Hairline, RoundedCornerShape(50))
            .testTag(tag).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp))
}
