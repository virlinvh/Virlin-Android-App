package com.virlin.app.ui.agent.control

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.command.VirlinCommand
import com.virlin.app.ui.hierarchy.TaskTreeRow
import com.virlin.app.ui.screens.CompleteWorkStreamDialog
import com.virlin.app.ui.screens.NowChooserDialog
import com.virlin.app.ui.screens.scrollEdgeFade
import com.virlin.app.ui.theme.VirlinColors
import kotlinx.coroutines.launch

/**
 * CONTROL mode content inside the frozen Agent shell.
 *
 * Layout contract: FIXED top (feedback · QUICK ACTIONS horizontal rail · RECENT / SUGGESTED
 * heading) · SCROLLABLE middle (suggested targets / task picker) · shell-pinned composer.
 *
 * Quick Actions: ACTION + TARGET selection. Selection alone never mutates domain state;
 * a valid pair executes through [AgentControlViewModel] → AttentionIntentController / VirlinActions.
 */
private val Hairline = Color(0x1F162016)

/** Bottom inset inside a scrollable middle region so the last card clears the composer. */
private val ControlListBottomInset = 72.dp

const val AgentControlTag = "agent_control"
const val AgentControlFocusTag = "agent_control_focus"
const val AgentControlFeedbackTag = "agent_control_feedback"
const val AgentTaskPickerTag = "agent_task_picker"
fun controlItemTag(id: String) = "control_item_$id"
fun controlActionTag(id: String, a: ControlAction) = "control_${a.name.lowercase()}_$id"
fun quickActionTag(a: QuickAction) = "control_quick_${a.name.lowercase()}"
const val AgentControlQuickActionsTag = "agent_control_quick_actions"
const val AgentControlSuggestedTag = "agent_control_suggested"

private val Neutral400 = Color(0xFF9CA3AF)
private val Neutral500 = Color(0xFF6B7280)
private val Neutral800 = Color(0xFF1F2937)
private val Neutral900 = Color(0xFF111827)
private val CardBorder = Color(0xD9E5E7EB)

@Composable
fun AgentControlArea(
    vm: AgentControlViewModel,
    modifier: Modifier = Modifier,
    /** Typed command sink for Quick Actions without a target (existing command panel / clarification). */
    onCommand: (VirlinCommand) -> Unit = {}
) {
    val state by vm.state.collectAsState()
    val chooser by vm.intents.chooser.collectAsState()
    val pendingCompletion by vm.intents.pendingWorkStreamCompletion.collectAsState()
    val feedback by vm.intents.feedback.collectAsState()

    chooser?.let { NowChooserDialog(it, vm.intents) }
    pendingCompletion?.let { id ->
        val title = state.suggested.firstOrNull { it.streamId == id }?.title ?: "this WorkStream"
        CompleteWorkStreamDialog(title, onConfirm = vm.intents::confirmCompleteWorkStream, onDismiss = vm.intents::dismissWorkStreamCompletion)
    }

    Column(modifier = modifier.fillMaxWidth().testTag(AgentControlTag)) {
        feedback?.let {
            Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.Emerald,
                modifier = Modifier.testTag(AgentControlFeedbackTag).padding(bottom = 8.dp))
        }
        if (state.selectedStreamId != null) {
            val pickerScroll = rememberScrollState()
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .scrollEdgeFade(pickerScroll, VirlinColors.Background)
                    .verticalScroll(pickerScroll)
            ) {
                TaskPicker(state, vm)
                Spacer(Modifier.height(ControlListBottomInset))
            }
            return@Column
        }

        // ---- QUICK ACTIONS: FIXED horizontal rail
        SectionLabel("QUICK ACTIONS")
        QuickActionRail(
            selected = state.selectedQuickAction,
            enabledFor = { vm.isQuickEnabled(it) },
            onSelect = { vm.selectQuick(it, onCommand) }
        )
        Spacer(Modifier.height(22.dp))

        // ---- RECENT / SUGGESTED heading: FIXED
        SectionLabel("RECENT / SUGGESTED")

        val listScroll = rememberScrollState()
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .scrollEdgeFade(listScroll, VirlinColors.Background)
                .verticalScroll(listScroll)
        ) {
            val rows = state.suggested
            if (rows.isEmpty()) {
                Text("Nothing to control right now", fontSize = 13.sp, color = Neutral500,
                    modifier = Modifier.testTag(AgentControlFocusTag).padding(vertical = 4.dp))
            }
            Column(modifier = Modifier.fillMaxWidth().testTag(AgentControlSuggestedTag), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { item ->
                    val selected = item.streamId == state.selectedTargetId
                    val row = @Composable {
                        SuggestedRow(item, selected = selected, vm = vm)
                    }
                    if (item.streamId == state.currentFocus?.streamId) {
                        Box(Modifier.fillMaxWidth().testTag(AgentControlFocusTag).semantics { contentDescription = item.description }) { row() }
                    } else row()
                }
            }
            Spacer(Modifier.height(ControlListBottomInset))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Neutral400,
        modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
}

// ---------------------------------------------------------------- Quick Actions rail

@Composable
private fun QuickActionRail(
    selected: QuickAction?,
    enabledFor: (QuickAction) -> Boolean,
    onSelect: (QuickAction) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    LaunchedEffect(selected) {
        val idx = selected?.let { QuickAction.rail.indexOf(it) } ?: return@LaunchedEffect
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AgentControlQuickActionsTag)
            .clipToBounds()
            .drawWithContent {
                drawContent()
                // Soft trailing fade — cue that more actions exist horizontally.
                val fade = 28.dp.toPx()
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to VirlinColors.Background,
                        startX = size.width - fade,
                        endX = size.width
                    )
                )
            }
    ) {
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(QuickAction.rail, key = { it.name }) { a ->
                val enabled = enabledFor(a)
                QuickActionTile(
                    a = a,
                    selected = a == selected,
                    enabled = enabled,
                    onClick = {
                        if (!enabled) return@QuickActionTile
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(a)
                        scope.launch {
                            val i = QuickAction.rail.indexOf(a)
                            if (i >= 0) listState.animateScrollToItem(i)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun QuickActionTile(
    a: QuickAction,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val l = ControlQuickRegistry.look(a)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.94f
            selected -> 1.06f
            else -> 1f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
        label = "quickScale"
    )
    val alpha by animateFloatAsState(if (enabled) 1f else 0.42f, tween(160), label = "quickAlpha")
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) l.selectedBorder else l.border,
        animationSpec = tween(180),
        label = "quickBorder"
    )
    val topColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) l.selectedTop else l.containerTop,
        animationSpec = tween(180),
        label = "quickTop"
    )
    val bottomColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) l.selectedBottom else l.containerBottom,
        animationSpec = tween(180),
        label = "quickBottom"
    )
    val iconFg = if (l.badgeFill != null) l.badgeIconColor else l.iconColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
            .testTag(quickActionTag(a))
            .semantics {
                contentDescription = buildString {
                    append("Quick action ${a.label}")
                    if (selected) append(", selected")
                    if (!enabled) append(", unavailable for selected target")
                }
                this.selected = selected
                if (!enabled) disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { onClick() }
                )
            }
    ) {
        Box(
            modifier = Modifier.size(58.dp)
                .background(Brush.verticalGradient(listOf(topColor, bottomColor)), RoundedCornerShape(16.dp))
                .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (l.badgeFill != null) Box(
                Modifier.size(24.dp).background(l.badgeFill, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(l.icon, contentDescription = null, tint = iconFg, modifier = Modifier.size(14.dp))
            } else Icon(l.icon, contentDescription = null, tint = l.iconColor, modifier = Modifier.size(24.dp))
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp)
                        .background(l.selectedBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            a.label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = Neutral800,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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

@Composable
private fun SuggestedRow(item: ControlItem, selected: Boolean, vm: AgentControlViewModel, modifier: Modifier = Modifier) {
    val t = tileOf(item.kind)
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (selected) 1.01f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "targetScale")
    val bg = if (selected) Color(0xFFF3FAF6) else Color.White
    val border = if (selected) VirlinColors.Emerald.copy(alpha = 0.55f) else CardBorder

    Column(
        modifier = modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(bg, RoundedCornerShape(16.dp))
            .border(if (selected) 1.5.dp else 1.dp, border, RoundedCornerShape(16.dp))
            .testTag(controlItemTag(item.streamId))
            .semantics {
                contentDescription = item.description + if (selected) ", selected target" else ""
                this.selected = selected
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clickable(role = Role.Button) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    vm.selectTarget(item.streamId)
                }
                .padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Box(Modifier.size(44.dp).background(t.bg, RoundedCornerShape(12.dp)).border(1.dp, t.border, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(t.icon, contentDescription = null, tint = t.tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(listOfNotNull(item.title, item.projectTitle?.takeIf { it != item.title }).joinToString(" · "),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Neutral900, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(item.taskTitle, item.detail).joinToString(" · "),
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Neutral400, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) {
                Box(Modifier.size(22.dp).background(VirlinColors.Emerald.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = VirlinColors.Emerald, modifier = Modifier.size(14.dp))
                }
            } else {
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Neutral400, modifier = Modifier.size(18.dp))
            }
        }
        if (selected) {
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
