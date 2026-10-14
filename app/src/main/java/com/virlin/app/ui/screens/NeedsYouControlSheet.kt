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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
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
import com.virlin.app.domain.attention.PriorityPreference
import com.virlin.app.domain.attention.PriorityScope
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
 *  - **Priority** — available queue positions apply immediately through the existing
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
    data object Complete : NeedsYouControlAction
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
    /** Confirmed move through the existing queue path. */
    onMoveToPosition: (Int) -> Unit,
    existingPreference: PriorityPreference? = null,
    onSavePreference: (Int, PriorityScope, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onRemovePreference: (() -> Unit)? = null,
    onAction: (NeedsYouControlAction) -> Unit,
    onDismiss: () -> Unit
) {
    // Transient UI state: which tab is showing. Survives recomposition, not a new open.
    var tab by rememberSaveable(stream?.id, initialTab) { mutableStateOf(initialTab) }
    var pendingPosition by remember { mutableStateOf<Int?>(null) }
    var saved by remember { mutableStateOf(false) }
    var moveFailed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(position, total, pendingPosition) {
        if (pendingPosition != null && position == pendingPosition?.coerceAtMost(total.coerceAtLeast(1))) {
            pendingPosition = null; moveFailed = false; saved = true
        }
    }
    androidx.compose.runtime.LaunchedEffect(saved) {
        if (saved) { kotlinx.coroutines.delay(1600); saved = false }
    }
    androidx.compose.runtime.LaunchedEffect(pendingPosition) {
        // Re-enable retry if the queue rejects a stale request; never fabricate success.
        if (pendingPosition != null) {
            kotlinx.coroutines.delay(5000)
            if (pendingPosition != null) { pendingPosition = null; moveFailed = true }
        }
    }
    val v = NeedsYouPriority.visualsFor(position)
    val optionsScroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(tab) { optionsScroll.scrollTo(0) }
    val entrance = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        entrance.animateTo(1f, androidx.compose.animation.core.tween(280,
            easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val owner = Any()
        com.virlin.app.ui.hierarchy.KnowledgeOrbVisibility.enter(owner)
        onDispose { com.virlin.app.ui.hierarchy.KnowledgeOrbVisibility.leave(owner) }
    }

    BackHandler(onBack = onDismiss)
    // Draw within the app's content slot: the shell already reserves footer and IME space.
    // A separate ModalBottomSheet window would cover that real navigation footer.
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f * entrance.value))
        .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }, contentAlignment = Alignment.BottomCenter) {
    Surface(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 560.dp).fillMaxWidth()
        // One responsive shell for both tabs; only the contents change, never its bounds.
        .height(minOf(520.dp, (maxHeight - 16.dp).coerceAtLeast(48.dp)))
        .then(Modifier.graphicsLayer {
            alpha = entrance.value
            translationY = (1f - entrance.value) * 24.dp.toPx()
        }).testTag(NeedsYouControlSheetTag)
        .pointerInput(Unit) { detectTapGestures { } },
        color = Color.White, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 10.dp)
                .testTag(NeedsYouControlHeaderTag)
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

            Spacer(Modifier.height(8.dp))
                Text(
                    AttentionTiming.format(dueAt, now?.value ?: dueAt ?: Instant.EPOCH, adaptive = true),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum"),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = NeedsYouPriority.readableInk(v), maxLines = 1, softWrap = false,
                    modifier = Modifier
                        .testTag(NeedsYouControlTimerTag)
                        .semantics { contentDescription = AttentionTiming.describe(dueAt, now?.value ?: dueAt ?: Instant.EPOCH) }
                )

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

        }
        // Only the choices scroll. White edge gradients soften both clipping boundaries.
        if (stream != null) Box(Modifier.fillMaxWidth().weight(1f).testTag(NeedsYouControlOptionsTag)) {
            Column(Modifier.fillMaxSize().verticalScroll(optionsScroll)
                .padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 24.dp)) {
            Crossfade(targetState = tab, animationSpec = androidx.compose.animation.core.tween(180), label = "ny_control_tab") { current ->
                when (current) {
                    NeedsYouControlTab.PRIORITY -> Column {
                        PriorityTab(position, total, v, pendingPosition != null, { requested ->
                            if (position != null && pendingPosition == null) {
                                val target = requested.coerceIn(1, total.coerceAtLeast(1))
                                if (target != position) {
                                    saved = false; moveFailed = false; pendingPosition = target
                                    onMoveToPosition(target)
                                }
                            }
                        }, existingPreference, onSavePreference, onRemovePreference)
                        if (pendingPosition != null) Text("Updating position…", color = CharcoalMuted,
                            fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).testTag("needs_you_position_pending"))
                        if (moveFailed) Text("Could not update the position. Tap a position to try again.",
                            color = Color(0xFFB54335), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp).testTag("needs_you_position_error"))
                        androidx.compose.animation.AnimatedVisibility(saved, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                            Text("✓ Position updated", color = Color(0xFF07533E), fontSize = 12.sp,
                                modifier = Modifier.padding(top = 8.dp).background(Color(0xFFE6F7EE), RoundedCornerShape(50))
                                    .padding(horizontal = 14.dp, vertical = 8.dp).testTag("needs_you_position_saved"))
                        }
                    }
                    NeedsYouControlTab.CHECK -> CheckTab(kind, onAction)
                }
            }
            }
            Box(Modifier.fillMaxWidth().height(16.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 0f)))))
            Box(Modifier.fillMaxWidth().height(24.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White))))
        }
        }
        }
    }
}

/** Valid queue positions apply on tap; custom positions require a valid number and one Apply. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriorityTab(
    position: Int?,
    total: Int,
    v: PriorityVisuals,
    updating: Boolean,
    onMoveToPosition: (Int) -> Unit,
    existingPreference: PriorityPreference?,
    onSavePreference: (Int, PriorityScope, (Boolean) -> Unit) -> Unit,
    onRemovePreference: (() -> Unit)?
) {
    var custom by rememberSaveable { mutableStateOf((position ?: 1) > 8) }
    var customText by rememberSaveable { mutableStateOf(if ((position ?: 1) > 8) position.toString() else "") }
    var policy by rememberSaveable { mutableStateOf("none") }
    var returnsText by rememberSaveable { mutableStateOf("10") }
    var policyStatus by remember { mutableStateOf("") }
    var policySaving by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(existingPreference) {
        policy = when (val scope = existingPreference?.scope) {
            PriorityScope.Always -> "always"
            is PriorityScope.NextReturns -> {
                returnsText = scope.remaining.toString()
                if (scope.remaining == 10) "ten" else "returns"
            }
            else -> "none"
        }
    }
    Column {
        Text("Choose position", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Charcoal)
        Text("Tap a position to move this task in Needs You.", fontSize = 12.5.sp, color = CharcoalMuted)
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tileWidth = (maxWidth - 24.dp) / 4 - 1.dp
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..7).forEach { p ->
            val current = p == position && !custom
            val available = p <= total
            val rank = NeedsYouPriority.visualsFor(p)
            Column(
                modifier = Modifier
                    .width(tileWidth)
                    .height(48.dp)
                    .background(if (current) v.surface else if (available) Color.White else Color(0xFFF5F6F7), RoundedCornerShape(12.dp))
                    .border(1.dp, if (current) v.border else Color(0xFFECEEF1), RoundedCornerShape(12.dp))
                    .testTag(priorityPositionTag(p))
                    .clickable(enabled = available && !updating, role = Role.RadioButton) {
                        custom = false; onMoveToPosition(p)
                    }
                    .semantics {
                        selected = current
                        contentDescription =
                            if (current) "Current position $p, ${ordinal(p)}."
                            else if (!available) "Position $p unavailable; $total items in Needs You."
                            else "Move to position $p, ${ordinal(p)}."
                    }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
                , verticalArrangement = Arrangement.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(17.dp).background(if (available) rank.accent else Color(0xFFD5D9DC), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text("$p", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            color = if (available) rank.onAccent else CharcoalMuted)
                    }
                    Text(ordinal(p), fontSize = 10.sp,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Medium,
                        color = if (available) Charcoal else CharcoalMuted, maxLines = 1)
                }

            }
        }
        Column(Modifier.width(tileWidth).height(48.dp)
            .background(if (custom) v.surface else Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, if (custom) v.border else Color(0xFFECEEF1), RoundedCornerShape(12.dp))
            .testTag("needs_you_custom_position").clickable(enabled = !updating, role = Role.RadioButton) {
                custom = true
            }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (!custom) Text("Custom", fontSize = 12.sp, color = Charcoal)
            else Text("Custom ✎", fontSize = 12.sp, color = Charcoal)
        }
        }
        if (custom) {
            Text("Custom position · 1 to $total", fontSize = 11.sp, color = CharcoalMuted,
                modifier = Modifier.padding(top = 10.dp))
            BasicTextField(customText, { customText = it.filter(Char::isDigit).take(4) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = Charcoal, fontSize = 17.sp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                .background(Color(0xFFF5F6F7), RoundedCornerShape(12.dp))
                .padding(14.dp).testTag("needs_you_custom_position_input")
                .semantics { contentDescription = "Custom position, 1 to $total" })
        }
        }
        if (custom && customText.isNotEmpty() && validPriorityPosition(customText) == null) {
            Text("Enter a valid positive whole number.",
                fontSize = 11.sp, color = CharcoalMuted, modifier = Modifier.padding(top = 8.dp))
        }
        if (custom) {
            val target = validPriorityPosition(customText)?.takeIf { it in 1..total }
            Text(
                if (target == null) "Enter a position from 1 to $total" else "Move to $target",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (target == null) CharcoalMuted else Color(0xFF07533E),
                modifier = Modifier.padding(top = 8.dp).heightIn(min = 44.dp)
                    .testTag("needs_you_apply_position")
                    .clickable(enabled = target != null && target != position && !updating, role = Role.Button) {
                        if (target != null) onMoveToPosition(target)
                    }
                    .padding(vertical = 12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.height(16.dp))
        Text("Keep this position", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Charcoal)
        Text("Choose what happens when this same task returns to Needs You.", fontSize = 12.sp, color = CharcoalMuted)
        listOf(
            Triple("once", "This time only", "Move now; do not remember it"),
            Triple("always", "Always for this task", "Place it here whenever it returns"),
            Triple("ten", "Next 10 returns", "Use this position for the next 10 returns"),
            Triple("returns", "Custom number of returns", "Choose how many times to reuse it")
        ).forEach { (choice, label, hint) ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)
                .background(if (policy == choice) v.surface else Color(0xFFF8F9F9), RoundedCornerShape(12.dp))
                .border(1.dp, if (policy == choice) v.border else Color(0xFFE7EAEC), RoundedCornerShape(12.dp))
                .testTag("needs_you_policy_$choice")
                .clickable(role = Role.RadioButton) { policy = choice; policyStatus = "Not saved" }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (policy == choice) "◉" else "○", color = v.accent, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column { Text(label, color = Charcoal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(hint, color = CharcoalMuted, fontSize = 11.sp) }
            }
        }
        if (policy == "returns") {
            Text("Number of future returns · 1 to 9999", fontSize = 11.sp, color = CharcoalMuted,
                modifier = Modifier.padding(top = 8.dp))
            BasicTextField(returnsText,
            { returnsText = it.filter(Char::isDigit).take(4); policyStatus = "Not saved" }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(color = Charcoal, fontSize = 16.sp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                .background(Color(0xFFF5F6F7), RoundedCornerShape(12.dp))
                .padding(14.dp).testTag("needs_you_policy_returns_input"))
        }
        val scope = when (policy) {
            "once" -> PriorityScope.OneTime
            "always" -> PriorityScope.Always
            "ten" -> PriorityScope.NextReturns(10)
            "returns" -> returnsText.toIntOrNull()?.takeIf { it in 1..9999 }?.let(PriorityScope::NextReturns)
            else -> null
        }
        if (policy != "none" || existingPreference != null) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (policySaving) "Saving…" else if (policyStatus.isNotEmpty()) policyStatus
                    else if (existingPreference != null) "Saved for this task" else "Not saved",
                    modifier = Modifier.weight(1f).testTag("needs_you_policy_status"), color = CharcoalMuted, fontSize = 12.sp)
                if (policyStatus == "Not saved") Text("Don't save", color = CharcoalMuted,
                    fontSize = 12.sp, modifier = Modifier.testTag("needs_you_policy_discard")
                        .clickable(role = Role.Button) {
                            policy = when (val savedScope = existingPreference?.scope) {
                                PriorityScope.Always -> "always"
                                is PriorityScope.NextReturns -> {
                                    returnsText = savedScope.remaining.toString()
                                    if (savedScope.remaining == 10) "ten" else "returns"
                                }
                                else -> "none"
                            }
                            policyStatus = ""
                        }.padding(12.dp))
                Text("Save", color = if (scope != null && !policySaving) v.accent else CharcoalMuted,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("needs_you_policy_save")
                        .clickable(enabled = scope != null && !policySaving && position != null, role = Role.Button) {
                            if (scope != null && position != null) {
                                policySaving = true
                                onSavePreference(position, scope) { ok ->
                                    policySaving = false
                                    policyStatus = if (!ok) "Could not save. Try again."
                                        else if (scope == PriorityScope.OneTime) "✓ Applied once" else "✓ Saved"
                                }
                            }
                        }.padding(12.dp))
            }
            if (existingPreference != null && onRemovePreference != null) Text("Remove saved rule",
                color = CharcoalMuted, fontSize = 12.sp,
                modifier = Modifier.testTag("needs_you_policy_remove")
                    .clickable(role = Role.Button) { onRemovePreference(); policy = "none"; policyStatus = "Rule removed" }
                    .padding(vertical = 12.dp))
        }
    }
}

/**
 * CHECK: only the transitions the domain already allows out of this item's state
 * (`WorkStreamTransitions`: CHECK → FOCUS · PROCESSING · SNOOZED · READY · BLOCKED).
 * Completion uses the existing task completion intent, without starting human focus.
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
                MinutePresets(NeedsYouControlStillRunningTag, allowCustom = true) { onAction(NeedsYouControlAction.StillRunning(it)) }
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

        if (external) ActionRow(
            tag = "needs_you_control_complete",
            icon = "✓",
            title = "Completed",
            subtitle = "Mark this work as done",
            chevron = false
        ) { onAction(NeedsYouControlAction.Complete) }

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
internal fun MinutePresets(parentTag: String, allowCustom: Boolean = false, onPick: (Long) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column {
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
        if (allowCustom) Text("Custom", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal,
            modifier = Modifier.heightIn(min = 44.dp)
                .background(Color.White, RoundedCornerShape(50))
                .border(1.dp, Charcoal.copy(alpha = 0.18f), RoundedCornerShape(50))
                .testTag("${parentTag}_custom")
                .clickable(role = Role.Button) { custom = !custom }
                .padding(horizontal = 12.dp, vertical = 13.dp))
    }
    if (custom) Row(Modifier.fillMaxWidth().padding(start = 46.dp, top = 2.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(value = text, onValueChange = { text = it.filter(Char::isDigit).take(8) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TextStyle(fontSize = 16.sp, color = Charcoal),
            modifier = Modifier.width(68.dp).heightIn(min = 44.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .border(1.dp, Charcoal.copy(alpha = .18f), RoundedCornerShape(12.dp))
                .semantics { contentDescription = "Minutes from now" }
                .testTag("${parentTag}_custom_minutes").padding(12.dp))
        Text("min", color = CharcoalMuted, fontSize = 12.sp)
        val minutes = text.toLongOrNull()?.takeIf { it > 0 }
        Text("Save", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Charcoal.copy(alpha = if (minutes != null) 1f else .35f), RoundedCornerShape(12.dp))
                .testTag("${parentTag}_custom_save")
                .clickable(enabled = minutes != null, role = Role.Button) { minutes?.let(onPick) }
                .padding(horizontal = 18.dp, vertical = 14.dp))
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

internal fun validPriorityPosition(input: String): Int? =
    input.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }

const val NeedsYouControlSheetTag = "needs_you_control_sheet"
const val NeedsYouControlHeaderTag = "needs_you_control_header"
const val NeedsYouControlOptionsTag = "needs_you_control_options"
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
