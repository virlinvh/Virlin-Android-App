package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.theme.Charcoal
import com.virlin.app.ui.theme.CharcoalMuted

/*
 * Needs You QUEUE POSITION (Phase 2 of priority ranking).
 *
 * Position = attention ORDER ("what do I handle first?"). It is NOT urgency — urgency is the
 * timer/palette system and keeps its colours. The position control therefore speaks through
 * shape, weight and typography only: a compact `#n` pill, filled Charcoal for #1 (the next item),
 * quiet outlined for every other position. Tapping opens the selector; choosing a position calls
 * the ONE Phase 1 action (`VirlinActions.reorderNeedsYou`) — nothing here orders anything.
 */

fun needsYouRankTag(streamId: String) = "needs_you_rank_$streamId"
fun needsYouPositionTag(position: Int) = "needs_you_position_$position"
const val NeedsYouPositionSheetTag = "needs_you_position_sheet"

/** Spoken label for a position ("first" … "tenth", then "position 11"). */
fun positionWord(position: Int): String = when (position) {
    1 -> "First"; 2 -> "Second"; 3 -> "Third"; 4 -> "Fourth"; 5 -> "Fifth"
    6 -> "Sixth"; 7 -> "Seventh"; 8 -> "Eighth"; 9 -> "Ninth"; 10 -> "Tenth"
    else -> "Position $position"
}

/**
 * `#n` queue badge with a `▾` affordance and a muted `of N`. Visible pill ≈ 26dp tall; the touch
 * target is 44dp. Semantics: "Attention position n of N. Double tap to change."
 */
@Composable
fun NeedsYouPositionBadge(
    streamId: String,
    position: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val first = position == 1
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .height(44.dp)
            .testTag(needsYouRankTag(streamId))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Attention position $position of $total. Double tap to change." },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .background(if (first) Charcoal else Color.White.copy(alpha = 0.55f), shape)
                .border(1.dp, if (first) Charcoal else Charcoal.copy(alpha = 0.22f), shape)
                .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "#$position", fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.2.sp,
                color = if (first) Color.White else Charcoal, maxLines = 1, softWrap = false
            )
            Spacer(Modifier.width(3.dp))
            Text("▾", fontSize = 10.sp, color = if (first) Color.White.copy(alpha = 0.8f) else Charcoal.copy(alpha = 0.55f))
        }
        if (total > 1) Text(
            "of $total", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = CharcoalMuted,
            maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/**
 * Position selector: one row per currently valid position (exactly `streams.size` rows), each
 * showing the number, its ordinal and the task currently at that position. The current position
 * is highlighted and `selected`; tapping another applies immediately (no Save).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsYouPositionSheet(
    streamId: String,
    streams: List<WorkStream>,
    onSelect: (position: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val current = streams.indexOfFirst { it.id == streamId } + 1
    val subject = streams.firstOrNull { it.id == streamId }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = Color.White, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag(NeedsYouPositionSheetTag)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("Move to position", fontSize = 17.sp, fontWeight = FontWeight.Black, color = Charcoal)
            if (subject != null) Text(
                subject.subtitle.ifBlank { subject.title }, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(12.dp))
            streams.forEachIndexed { i, s ->
                val pos = i + 1
                val isCurrent = pos == current
                val rowShape = RoundedCornerShape(14.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .heightIn(min = 52.dp)
                        .background(if (isCurrent) Charcoal.copy(alpha = 0.06f) else Color.Transparent, rowShape)
                        .border(1.dp, if (isCurrent) Charcoal.copy(alpha = 0.35f) else Charcoal.copy(alpha = 0.08f), rowShape)
                        .testTag(needsYouPositionTag(pos))
                        .clickable(role = Role.Button) { if (!isCurrent) onSelect(pos) else onDismiss() }
                        .semantics {
                            contentDescription = if (isCurrent) "Current position $pos, ${positionWord(pos)}" else "Move to position $pos, ${positionWord(pos)}"
                            selected = isCurrent
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(30.dp)
                            .background(if (pos == 1) Charcoal else Color.White, CircleShape)
                            .border(1.dp, if (pos == 1) Charcoal else Charcoal.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$pos", fontSize = 12.sp, fontWeight = FontWeight.Black, color = if (pos == 1) Color.White else Charcoal)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(positionWord(pos), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Charcoal)
                        Text(
                            if (isCurrent) "Current position" else s.subtitle.ifBlank { s.title },
                            fontSize = 11.5.sp, color = CharcoalMuted, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (isCurrent) Text("✓", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Charcoal, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
