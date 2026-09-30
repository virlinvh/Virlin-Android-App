package com.virlin.app.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.external.ExternalWorkItem
import com.virlin.app.ui.theme.Charcoal
import com.virlin.app.ui.theme.CharcoalLight
import com.virlin.app.ui.theme.CharcoalMuted
import java.time.Instant

/**
 * WORKING FOR YOU row (Phase 10) — the approved compact processing row, now carrying real
 * execution data instead of demo counters:
 *
 * ```
 * [●●●]  Claude Code                       00:04:32
 *        Orb Interaction · Implement listening
 *        Stage 2 · Implement fix
 * ```
 *
 * The countdown is `checkAt − now` (`AttentionTiming`): one shared clock value is passed in, so
 * twenty-five rows still cost one ticker and zero writes. The row never owns time of its own.
 */
@Composable
fun ExternalWorkRow(
    item: ExternalWorkItem,
    now: Instant,
    index: Int,
    onOpen: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WorkingRowBg, RoundedCornerShape(12.dp))
            .border(1.dp, WorkingRowBorder, RoundedCornerShape(12.dp))
            .testTag(externalWorkRowTag(item.id))
            .clickable { onOpen(item.id) }
            .semantics { contentDescription = item.describe(now) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProcessingPulseTile(index)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.actorName, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val context = listOfNotNull(item.workItemTitle ?: item.stream.title, item.instruction)
                .joinToString(" · ")
            Text(
                context, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            item.stageLabel()?.let { stage ->
                Text(
                    stage, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = CharcoalLight,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(externalWorkStageTag(item.id))
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            if (item.checkAt != null) {
                Text(
                    item.countdown(now),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum"),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal,
                    modifier = Modifier.testTag(externalWorkTimerTag(item.id))
                )
                Text("Check in", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = CharcoalLight)
            } else {
                Text(
                    "No check", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted,
                    modifier = Modifier
                        .background(Color(0xFFF1F5F9), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** The approved three-capsule processing pulse, unchanged; extracted so the row stays readable. */
@Composable
private fun ProcessingPulseTile(index: Int) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color(0xFFF5F3FF), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFFEDE9FE), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            val heights = when (index % 3) {
                0 -> listOf(12.dp, 16.dp, 8.dp)
                1 -> listOf(10.dp, 16.dp, 12.dp)
                else -> listOf(14.dp, 8.dp, 16.dp)
            }
            heights.forEachIndexed { i, h ->
                if (i > 0) Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier.width(4.dp).height(h)
                        .background(Color(0xFF7C3AED).copy(alpha = 0.55f), CircleShape)
                )
            }
        }
    }
}

/** Spoken/described form reused by the detail surface. */
fun externalWorkStageDescription(position: Int, total: Int, title: String, running: Boolean): String =
    "Stage $position of $total. $title. " + if (running) "In progress." else "Planned."

fun externalWorkRowTag(streamId: String) = "external_work_$streamId"
fun externalWorkTimerTag(streamId: String) = "external_work_timer_$streamId"
fun externalWorkStageTag(streamId: String) = "external_work_stage_$streamId"

/** The countdown a row would show; kept next to the row so tests read one source. */
fun externalWorkCountdown(checkAt: Instant?, now: Instant): String = AttentionTiming.format(checkAt, now)
