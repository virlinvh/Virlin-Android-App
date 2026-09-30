package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.external.ExternalWorkItem
import com.virlin.app.domain.model.ExternalStage
import com.virlin.app.domain.model.ExternalStageStatus
import com.virlin.app.ui.theme.Charcoal
import com.virlin.app.ui.theme.CharcoalLight
import com.virlin.app.ui.theme.CharcoalMuted
import com.virlin.app.ui.theme.Pearl
import java.time.Instant

/**
 * WORKING FOR YOU detail (Phase 10) — a compact sheet, not a project-management screen.
 * It answers the four questions an external run must answer: who, what, where, when — and
 * shows the planned stages when the run has any. Actions stay the existing attention verbs.
 */
@Composable
fun ExternalWorkDetailSheet(
    item: ExternalWorkItem,
    now: Instant,
    viewModel: NowViewModel,
    onDismiss: () -> Unit
) {
    var stages by remember(item.id) { mutableStateOf<List<ExternalStage>>(emptyList()) }
    LaunchedEffect(item.id) { stages = viewModel.stagesOf(item.id) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Pearl, RoundedCornerShape(24.dp))
                .testTag(ExternalWorkDetailTag)
                .padding(24.dp)
        ) {
            Text(
                item.actorName.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                color = CharcoalLight, letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                item.workItemTitle ?: item.stream.title, fontSize = 18.sp,
                fontWeight = FontWeight.Black, color = Charcoal
            )
            item.instruction?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted)
            }
            listOfNotNull(item.projectTitle, item.stream.title).takeIf { it.isNotEmpty() }?.let { path ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(path.joinToString(" › "), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CharcoalLight)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.countdown(now),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontFeatureSettings = "tnum"),
                    fontSize = 20.sp, fontWeight = FontWeight.Black, color = Charcoal
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (item.checkAt == null) "no check planned" else AttentionTiming.describe(item.checkAt, now).lowercase(),
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CharcoalLight
                )
            }

            if (stages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                stages.forEachIndexed { index, stage ->
                    StageRow(stage, index + 1, stages.size, item, now)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "CLOSE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal.copy(alpha = 0.7f),
                    modifier = Modifier.testTag(ExternalWorkDetailCloseTag).clickable(onClick = onDismiss).padding(12.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "CHECK NOW", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 0.4.sp,
                    modifier = Modifier
                        .background(Charcoal, RoundedCornerShape(50))
                        .testTag(ExternalWorkCheckNowTag)
                        .clickable {
                            // The same due-check path Needs You uses; the chooser decides the outcome.
                            viewModel.checkDue(item.id)
                            viewModel.openCheck(item.id)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun StageRow(stage: ExternalStage, position: Int, total: Int, item: ExternalWorkItem, now: Instant) {
    val running = stage.status == ExternalStageStatus.IN_PROGRESS
    val glyph = when (stage.status) {
        ExternalStageStatus.DONE -> "✓"
        ExternalStageStatus.IN_PROGRESS -> "●"
        ExternalStageStatus.PENDING -> "○"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(externalStageRowTag(stage.id))
            .semantics {
                contentDescription = externalWorkStageDescription(position, total, stage.title, running)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(glyph, fontSize = 12.sp, fontWeight = FontWeight.Black, color = if (running) Charcoal else CharcoalLight)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stage.title, fontSize = 13.sp,
                fontWeight = if (running) FontWeight.ExtraBold else FontWeight.Medium,
                color = if (stage.status == ExternalStageStatus.PENDING) CharcoalMuted else Charcoal
            )
            val caption = when {
                running && item.checkAt != null -> "Check in " + AttentionTiming.format(item.checkAt, now)
                stage.status == ExternalStageStatus.PENDING -> stage.expectedMinutes?.let { "~${it}m" } ?: ""
                else -> "Done"
            }
            if (caption.isNotEmpty()) {
                Text(caption, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = CharcoalLight)
            }
        }
    }
}

const val ExternalWorkDetailTag = "external_work_detail"
const val ExternalWorkDetailCloseTag = "external_work_detail_close"
const val ExternalWorkCheckNowTag = "external_work_check_now"
fun externalStageRowTag(stageId: String) = "external_stage_$stageId"
