package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.attention.PriorityScope
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.theme.Charcoal
import com.virlin.app.ui.theme.CharcoalMuted
import java.time.Duration
import java.time.Instant

/*
 * Needs You PRIORITY EDITOR (Phase 04).
 *
 * Entry point: the card's rank badge. The sheet is pure PREVIEW state — the queue is mutated only
 * by SAVE, through the Phase 03 engine (`VirlinActions.setNeedsYouPriority` → `reorderNeedsYou`).
 * Cancel, back and swipe-dismiss never write anything. It shows what is being changed, how many
 * items are waiting, the valid positions (1..N) and how long the choice should apply.
 */

const val PriorityEditorTag = "priority_editor"
const val PriorityEditorSaveTag = "priority_editor_save"
const val PriorityEditorCancelTag = "priority_editor_cancel"
const val PriorityEditorCountTag = "priority_editor_count"
const val PriorityEditorOtherTag = "priority_editor_other"
fun priorityPositionTag(position: Int) = "priority_position_$position"
fun priorityScopeTag(scope: PriorityScope) = "priority_scope_" + when (scope) {
    PriorityScope.OneTime -> "one_time"
    PriorityScope.Always -> "always"
    PriorityScope.CurrentTerm -> "current_term"
    is PriorityScope.Until -> "custom"
}
fun priorityCustomTag(hours: Long) = "priority_custom_${hours}h"

/** "1 activity waiting" / "12 activities waiting". */
fun waitingCountLabel(count: Int): String = if (count == 1) "1 activity waiting" else "$count activities waiting"

/** The scope options, in order; `Until` is represented by the Custom option. */
private val scopeOptions = listOf(
    Triple(PriorityScope.OneTime as PriorityScope, "This time", "Only this occurrence"),
    Triple(PriorityScope.Always as PriorityScope, "Always prioritize here", "Use this position whenever it returns"),
    Triple(PriorityScope.CurrentTerm as PriorityScope, "This term", "While the current work term lasts"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsYouPriorityEditor(
    /** The item being edited, resolved by stable id — never by list index. */
    stream: WorkStream?,
    /** Its current effective rank, or null when it has left Needs You while the sheet was open. */
    currentPosition: Int?,
    /** Live queue size; the sheet re-validates the preview against it. */
    queueSize: Int,
    /** Existing stored preference for this item, if any. */
    existing: com.virlin.app.domain.attention.PriorityPreference? = null,
    now: Instant = Instant.now(),
    onSave: (position: Int, scope: PriorityScope) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = Color.White, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag(PriorityEditorTag)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 26.dp)) {
            if (stream == null || currentPosition == null) {
                // Defensive: the item left Needs You while the editor was open.
                Text("This activity is no longer waiting", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Charcoal)
                Spacer(Modifier.height(6.dp))
                Text("Its priority can't be changed any more.", fontSize = 12.5.sp, color = CharcoalMuted)
                Spacer(Modifier.height(16.dp))
                EditorButton("CLOSE", PriorityEditorCancelTag, primary = true, onClick = onDismiss)
                return@Column
            }

            // Preview state only — nothing below writes to the queue until SAVE.
            var selected by remember(stream.id, currentPosition) { mutableStateOf(currentPosition) }
            var scope by remember(stream.id) { mutableStateOf(existing?.scope ?: PriorityScope.OneTime) }
            var customOpen by remember(stream.id) { mutableStateOf(existing?.scope is PriorityScope.Until) }
            // A queue that shrank while the sheet was open can never produce an invalid target.
            val maxPosition = queueSize.coerceAtLeast(1)
            val safeSelected = selected.coerceIn(1, maxPosition)

            Text("CHANGE PRIORITY", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp, color = CharcoalMuted)
            Spacer(Modifier.height(12.dp))

            // ── what is being changed (compact: badge + title + one context line)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val v = NeedsYouPriority.visualsFor(currentPosition)
                Box(
                    Modifier.size(30.dp).background(if (v.neutral) Color.White else v.accent, CircleShape)
                        .border(1.dp, if (v.neutral) Charcoal.copy(alpha = 0.22f) else v.accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("$currentPosition", fontSize = 13.sp, fontWeight = FontWeight.Black, color = if (v.neutral) Charcoal else v.onAccent) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stream.subtitle.ifBlank { stream.title }, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stream.title, fontSize = 11.5.sp, color = CharcoalMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                waitingCountLabel(queueSize), fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted,
                modifier = Modifier.testTag(PriorityEditorCountTag).padding(top = 6.dp)
            )

            // ── POSITION: 1..min(10, N) as a grid; anything beyond 10 through a compact row.
            Spacer(Modifier.height(14.dp))
            SectionLabel("POSITION")
            val direct = minOf(maxPosition, NeedsYouPriority.coloredRanks)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (0 until direct step 5).forEach { start ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (start + 1..minOf(start + 5, direct)).forEach { pos ->
                            PositionChip(pos, selected = pos == safeSelected) { selected = pos }
                        }
                    }
                }
            }
            if (maxPosition > NeedsYouPriority.coloredRanks) {
                Spacer(Modifier.height(10.dp))
                SectionLabel("OTHER POSITION")
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(PriorityEditorOtherTag),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (NeedsYouPriority.coloredRanks + 1..maxPosition).forEach { pos ->
                        PositionChip(pos, selected = pos == safeSelected) { selected = pos }
                    }
                }
            }
            Text(
                "Current: $currentPosition", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CharcoalMuted,
                modifier = Modifier.padding(top = 8.dp)
            )

            // ── APPLY (scope)
            Spacer(Modifier.height(14.dp))
            SectionLabel("APPLY")
            Spacer(Modifier.height(6.dp))
            scopeOptions.forEach { (option, label, hint) ->
                ScopeRow(label, hint, selected = scope == option, tag = priorityScopeTag(option)) { scope = option; customOpen = false }
            }
            ScopeRow(
                "Custom", if (scope is PriorityScope.Until) untilLabel(scope as PriorityScope.Until, now) else "Keep this priority until…",
                selected = scope is PriorityScope.Until, tag = priorityScopeTag(PriorityScope.Until(now))
            ) { customOpen = true; if (scope !is PriorityScope.Until) scope = PriorityScope.Until(now.plus(Duration.ofHours(24))) }
            if (customOpen) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
                    listOf(4L to "Today", 24L to "Tomorrow", 24L * 7 to "Next week").forEach { (hours, label) ->
                        val active = (scope as? PriorityScope.Until)?.expiresAt == now.plus(Duration.ofHours(hours))
                        Text(
                            label, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            color = if (active) Color.White else Charcoal,
                            modifier = Modifier
                                .background(if (active) Charcoal else Color.White, RoundedCornerShape(50))
                                .border(1.dp, if (active) Charcoal else Charcoal.copy(alpha = 0.18f), RoundedCornerShape(50))
                                .testTag(priorityCustomTag(hours))
                                .clickable(role = Role.RadioButton) { scope = PriorityScope.Until(now.plus(Duration.ofHours(hours))) }
                                .semantics { contentDescription = "Keep this priority until $label"; this.selected = active }
                                .padding(horizontal = 12.dp, vertical = 9.dp)
                        )
                    }
                }
            }

            // ── actions
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { EditorButton("CANCEL", PriorityEditorCancelTag, primary = false, onClick = onDismiss) }
                Box(Modifier.weight(1f)) { EditorButton("SAVE", PriorityEditorSaveTag, primary = true) { onSave(safeSelected, scope) } }
            }
        }
    }
}

private fun untilLabel(until: PriorityScope.Until, now: Instant): String {
    val hours = Duration.between(now, until.expiresAt).toHours()
    return when {
        hours <= 0 -> "Expired"
        hours <= 6 -> "Until later today"
        hours <= 36 -> "Until tomorrow"
        else -> "Until next week"
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp, color = CharcoalMuted)

/** A position choice: the rank's Phase 02 identity, but selection is a ring + weight, never colour alone. */
@Composable
private fun PositionChip(position: Int, selected: Boolean, onClick: () -> Unit) {
    val v = NeedsYouPriority.visualsFor(position)
    Box(
        modifier = Modifier
            .size(48.dp)
            .testTag(priorityPositionTag(position))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Priority position $position"; this.selected = selected },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) 40.dp else 36.dp)
                .background(if (v.neutral) Color.White else v.accent, CircleShape)
                .border(if (selected) 2.5.dp else 1.dp, if (selected) Charcoal else Charcoal.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$position", fontSize = if (position >= 10) 12.sp else 13.sp,
                fontWeight = if (selected) FontWeight.Black else FontWeight.Bold,
                color = if (v.neutral) Charcoal else v.onAccent
            )
        }
    }
}

@Composable
private fun ScopeRow(label: String, hint: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(18.dp).background(Color.White, CircleShape)
                .border(if (selected) 5.dp else 1.5.dp, if (selected) Charcoal else Charcoal.copy(alpha = 0.3f), CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = Charcoal)
            Text(hint, fontSize = 11.sp, color = CharcoalMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EditorButton(label: String, tag: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(if (primary) Charcoal else Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, if (primary) Charcoal else Charcoal.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (primary && label == "SAVE") "Save priority" else label.lowercase().replaceFirstChar { it.uppercase() } },
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, color = if (primary) Color.White else Charcoal)
    }
}
