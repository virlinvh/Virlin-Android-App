package com.virlin.app.ui.screens

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.theme.Charcoal
import com.virlin.app.ui.theme.CharcoalMuted
import java.time.Instant

/**
 * THE NEEDS YOU CONTROL SHEET — one surface for everything a waiting item can do.
 *
 * Both halves of the card's `[ #n | CHECK → ]` pill open THIS sheet; they differ only in which tab
 * is selected on entry. Inside, the user moves freely between:
 *
 *  - **Priority** — the queue positions, applied immediately through the existing
 *    `reorderNeedsYou` path. The sheet deliberately stays open afterwards so a priority change and
 *    a check decision are one visit. Durable policies (Phase 04/07) remain available through the
 *    existing priority editor, one row below the positions.
 *  - **Check** — ONLY the transitions the domain already allows from this item's state. The sheet
 *    invents nothing: every row maps to an existing `VirlinActions` verb.
 *
 * The sheet holds no domain logic and no timer of its own: the header's waiting time is the same
 * `dueAt − now` the card renders, from the same shared ticker. Opening, switching tabs and
 * dismissing write nothing.
 */
enum class NeedsYouControlTab { PRIORITY, CHECK }

/** What the Check tab can offer. Each one maps to an existing action — see `NowScreen` wiring. */
sealed interface NeedsYouControlAction {
    /** CHECK → FOCUS: the result is ready (or the return is due) and the human looks now. */
    data object FocusNow : NeedsYouControlAction
    /** CHECK → PROCESSING: the external process is still running; look again in [minutes]. */
    data class StillRunning(val minutes: Long) : NeedsYouControlAction
    /** CHECK → SNOOZED: the work is finished/waiting for me, but later. */
    data class RemindLater(val minutes: Long) : NeedsYouControlAction
    /** CHECK → READY: keep it, just not now. */
    data object NotNow : NeedsYouControlAction
    /** CHECK → BLOCKED: it cannot proceed. */
    data object Blocked : NeedsYouControlAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsYouControlSheet(
    stream: WorkStream?,
    project: com.virlin.app.domain.model.Project? = null,
    kind: AttentionKind? = null,
    /** Live effective rank; null when the item has left Needs You while the sheet was open. */
    position: Int?,
    total: Int,
    /** The SAME temporal truth the card uses (`WorkStream.checkAt`), rendered with the same formatter. */
    dueAt: Instant? = null,
    now: State<Instant>? = null,
    initialTab: NeedsYouControlTab,
    /** Immediate move through the existing queue path; the sheet stays open. */
    onMoveToPosition: (Int) -> Unit,
    /** Opens the existing priority editor for durable policies (Always / this term / until…). */
    onOpenPriorityPolicy: () -> Unit = {},
    onAction: (NeedsYouControlAction) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Transient UI state: which tab is showing. Survives recomposition, not a new open.
    var tab by rememberSaveable(stream?.id, initialTab) { mutableStateOf(initialTab) }
    val v = NeedsYouPriority.visualsFor(position)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag(NeedsYouControlSheetTag)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        ) {
            if (stream == null) {
                Text("This activity is no longer waiting", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Charcoal)
                Spacer(Modifier.height(6.dp))
                Text("There is nothing left to control here.", fontSize = 12.5.sp, color = CharcoalMuted)
                Spacer(Modifier.height(16.dp))
                SheetTextButton("CLOSE", NeedsYouControlCloseTag, onDismiss)
                return@Column
            }

            // ── header: which item is being controlled, and how long it has been waiting
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(34.dp).background(v.container, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    com.virlin.app.ui.components.ProjectIcon(
                        projectId = project?.id ?: stream.id,
                        name = project?.title ?: stream.title,
                        iconPath = project?.iconPath,
                        iconId = project?.iconId,
                        size = 24.dp,
                        decorative = true
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stream.subtitle.ifBlank { stream.title }, fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                        color = Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(stream.title, fontSize = 11.5.sp, color = CharcoalMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    AttentionTiming.format(dueAt, now?.value ?: dueAt ?: Instant.EPOCH, adaptive = true),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum"),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = NeedsYouPriority.readableInk(v), maxLines = 1, softWrap = false,
                    modifier = Modifier
                        .testTag(NeedsYouControlTimerTag)
                        .semantics { contentDescription = AttentionTiming.describe(dueAt, now?.value ?: dueAt ?: Instant.EPOCH) }
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "✕", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag(NeedsYouControlCloseTag)
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .semantics { contentDescription = "Close task controls" }
                        .padding(horizontal = 6.dp, vertical = 13.dp)
                )
            }

            // ── the two tabs. Switching is presentation only: nothing is written.
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF4F5F7), RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                SheetTab("Priority", NeedsYouControlPriorityTabTag, tab == NeedsYouControlTab.PRIORITY, v, Modifier.weight(1f)) {
                    tab = NeedsYouControlTab.PRIORITY
                }
                SheetTab("Check", NeedsYouControlCheckTabTag, tab == NeedsYouControlTab.CHECK, v, Modifier.weight(1f)) {
                    tab = NeedsYouControlTab.CHECK
                }
            }

            Spacer(Modifier.height(14.dp))
            Crossfade(targetState = tab, label = "ny_control_tab") { current ->
                when (current) {
                    NeedsYouControlTab.PRIORITY -> PriorityTab(position, total, v, onMoveToPosition, onOpenPriorityPolicy)
                    NeedsYouControlTab.CHECK -> CheckTab(kind, onAction)
                }
            }
        }
    }
}

/** POSITION list: every live Needs You position, applied immediately through the existing path. */
@Composable
private fun PriorityTab(
    position: Int?,
    total: Int,
    v: PriorityVisuals,
    onMoveToPosition: (Int) -> Unit,
    onOpenPriorityPolicy: () -> Unit
) {
    Column {
        Text("Choose the position for this task.", fontSize = 12.5.sp, color = CharcoalMuted)
        Spacer(Modifier.height(10.dp))
        val count = total.coerceAtLeast(position ?: 1)
        (1..count).forEach { p ->
            val current = p == position
            val rank = NeedsYouPriority.visualsFor(p)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 3.dp)
                    .background(if (current) v.surface else Color.White, RoundedCornerShape(12.dp))
                    .border(1.dp, if (current) v.border else Color(0xFFECEEF1), RoundedCornerShape(12.dp))
                    .testTag(priorityPositionTag(p))
                    .clickable(role = Role.RadioButton, enabled = !current) { onMoveToPosition(p) }
                    .semantics {
                        selected = current
                        contentDescription =
                            if (current) "Current position $p, ${ordinal(p)}." else "Move to position $p, ${ordinal(p)}."
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(if (rank.neutral) Color.White else rank.accent, CircleShape)
                        .border(1.dp, if (rank.neutral) Charcoal.copy(alpha = 0.22f) else rank.accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$p", fontSize = if (p >= 10) 11.sp else 12.5.sp, fontWeight = FontWeight.Black,
                        color = if (rank.neutral) Charcoal else rank.onAccent
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ordinal(p), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Charcoal)
                    // Never colour alone: the state is written out.
                    Text(
                        if (current) "Currently at this position" else "Move to position $p",
                        fontSize = 11.5.sp, color = CharcoalMuted
                    )
                }
                if (current) {
                    Text("✓", fontSize = 15.sp, fontWeight = FontWeight.Black, color = v.accent)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Remember this priority…", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .testTag(NeedsYouControlPolicyTag)
                .clickable(role = Role.Button, onClick = onOpenPriorityPolicy)
                .semantics { contentDescription = "Remember this priority. Choose how long it applies." }
                .padding(vertical = 13.dp)
        )
    }
}

/**
 * CHECK: only the transitions the domain already allows out of this item's state
 * (`WorkStreamTransitions`: CHECK → FOCUS · PROCESSING · SNOOZED · READY · BLOCKED).
 * There is deliberately no "Mark as done" here — CHECK → DONE is not a legal transition; the work
 * is completed from Current Focus after FOCUS NOW.
 */
@Composable
private fun CheckTab(kind: AttentionKind?, onAction: (NeedsYouControlAction) -> Unit) {
    var timed by remember { mutableStateOf<NeedsYouControlAction?>(null) }
    val external = kind == AttentionKind.CHECK_DUE || kind == null
    Column {
        Text(
            if (external) "What happened with this work?" else "What do you want to do?",
            fontSize = 12.5.sp, color = CharcoalMuted
        )
        Spacer(Modifier.height(10.dp))

        ActionRow(
            tag = NeedsYouControlFocusTag,
            icon = "→",
            title = if (external) "Result ready" else "Focus now",
            subtitle = if (external) "Look at what came back" else "Work on this now",
            chevron = false
        ) { onAction(NeedsYouControlAction.FocusNow) }

        if (external) {
            ActionRow(
                tag = NeedsYouControlStillRunningTag,
                icon = "◷",
                title = "Still running",
                subtitle = "Check again later",
                chevron = true,
                expanded = timed is NeedsYouControlAction.StillRunning
            ) { timed = if (timed is NeedsYouControlAction.StillRunning) null else NeedsYouControlAction.StillRunning(0) }
            if (timed is NeedsYouControlAction.StillRunning) {
                MinutePresets(NeedsYouControlStillRunningTag) { onAction(NeedsYouControlAction.StillRunning(it)) }
            }
        } else {
            ActionRow(
                tag = NeedsYouControlRemindTag,
                icon = "◷",
                title = "Remind me later",
                subtitle = "Keep it waiting for me",
                chevron = true,
                expanded = timed is NeedsYouControlAction.RemindLater
            ) { timed = if (timed is NeedsYouControlAction.RemindLater) null else NeedsYouControlAction.RemindLater(0) }
            if (timed is NeedsYouControlAction.RemindLater) {
                MinutePresets(NeedsYouControlRemindTag) { onAction(NeedsYouControlAction.RemindLater(it)) }
            }
        }

        ActionRow(
            tag = NeedsYouControlNotNowTag,
            icon = "Ⅱ",
            title = "Not now",
            subtitle = "Keep it available, stop asking",
            chevron = false
        ) { onAction(NeedsYouControlAction.NotNow) }

        ActionRow(
            tag = NeedsYouControlBlockTag,
            icon = "⊘",
            title = "Blocked",
            subtitle = "It can't go further right now",
            chevron = false
        ) { onAction(NeedsYouControlAction.Blocked) }
    }
}

/** The existing CHECK AGAIN presets (3 · 5 · 10 minutes) — one source, no second snooze system. */
@Composable
private fun MinutePresets(parentTag: String, onPick: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 46.dp, top = 2.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AttentionTiming.checkAgainPresets.forEach { m ->
            Text(
                "${m}m", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal,
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .background(Color.White, RoundedCornerShape(50))
                    .border(1.dp, Charcoal.copy(alpha = 0.18f), RoundedCornerShape(50))
                    .testTag("${parentTag}_$m")
                    .clickable(role = Role.Button) { onPick(m) }
                    .semantics { contentDescription = "In $m minutes" }
                    .padding(horizontal = 14.dp, vertical = 13.dp)
            )
        }
    }
}

@Composable
private fun ActionRow(
    tag: String,
    icon: String,
    title: String,
    subtitle: String,
    chevron: Boolean,
    expanded: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 3.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, if (expanded) Charcoal.copy(alpha = 0.22f) else Color(0xFFECEEF1), RoundedCornerShape(12.dp))
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$title. $subtitle." }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(Color(0xFFF4F5F7), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 13.sp, fontWeight = FontWeight.Black, color = Charcoal) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Charcoal)
            Text(subtitle, fontSize = 11.5.sp, color = CharcoalMuted)
        }
        if (chevron) Text(if (expanded) "⌄" else "›", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted)
    }
}

@Composable
private fun SheetTab(
    label: String,
    tag: String,
    selectedTab: Boolean,
    v: PriorityVisuals,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (selectedTab) FontWeight.Black else FontWeight.SemiBold,
        color = if (selectedTab) NeedsYouPriority.readableInk(v) else CharcoalMuted,
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(if (selectedTab) v.surface else Color.White, RoundedCornerShape(10.dp))
            .border(1.dp, if (selectedTab) v.border else Color(0xFFECEEF1), RoundedCornerShape(10.dp))
            .testTag(tag)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { selected = selectedTab; contentDescription = "$label tab" }
            .padding(vertical = 13.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun SheetTextButton(label: String, tag: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 12.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 0.4.sp,
        modifier = Modifier
            .heightIn(min = 44.dp)
            .background(Charcoal, RoundedCornerShape(50))
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp)
    )
}

/** "First", "Second", … then plain numbers — the existing product language for queue positions. */
private fun ordinal(position: Int): String = when (position) {
    1 -> "First"; 2 -> "Second"; 3 -> "Third"; 4 -> "Fourth"; 5 -> "Fifth"
    6 -> "Sixth"; 7 -> "Seventh"; 8 -> "Eighth"; 9 -> "Ninth"; 10 -> "Tenth"
    else -> "Position $position"
}

const val NeedsYouControlSheetTag = "needs_you_control_sheet"
const val NeedsYouControlPriorityTabTag = "needs_you_control_tab_priority"
const val NeedsYouControlCheckTabTag = "needs_you_control_tab_check"
const val NeedsYouControlTimerTag = "needs_you_control_timer"
const val NeedsYouControlCloseTag = "needs_you_control_close"
const val NeedsYouControlPolicyTag = "needs_you_control_policy"
const val NeedsYouControlFocusTag = "needs_you_control_focus"
const val NeedsYouControlStillRunningTag = "needs_you_control_still_running"
const val NeedsYouControlRemindTag = "needs_you_control_remind"
const val NeedsYouControlNotNowTag = "needs_you_control_not_now"
const val NeedsYouControlBlockTag = "needs_you_control_block"
