package com.virlin.app.ui.hierarchy

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.virlin.app.domain.action.NewExternalStage
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.model.ExternalActor
import com.virlin.app.ui.theme.VirlinColors

/**
 * DELEGATE (Phase 10) — the minimum creation path for external work: who, what, when to check,
 * and optionally the planned stages. Deliberately small: three decisions and an optional list,
 * never a project-management form.
 *
 * Stage titles are plain text; the first stage starts with the run, and when the user gives it an
 * expected duration that duration becomes the check time.
 */
@Composable
fun DelegateDialog(
    workItemTitle: String,
    onDismiss: () -> Unit,
    onDelegate: (actor: ExternalActor, instruction: String, checkInMinutes: Long?, stages: List<NewExternalStage>) -> Unit
) {
    var actor by remember { mutableStateOf(ExternalActor.CLAUDE_CODE) }
    var instruction by remember { mutableStateOf(workItemTitle) }
    var minutes by remember { mutableStateOf<Long?>(5L) }
    var customMinutes by remember { mutableStateOf("") }
    var stageText by remember { mutableStateOf("") }
    var stages by remember { mutableStateOf(listOf<NewExternalStage>()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(VirlinColors.Background, RoundedCornerShape(24.dp))
                .testTag(DelegateDialogTag)
                .padding(22.dp)
        ) {
            Text("Working for you", fontSize = 17.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary)
            Spacer(Modifier.height(14.dp))

            Text("WHO", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = VirlinColors.TextTertiary, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExternalActor.catalogue.forEach { a ->
                    Chip(a.displayName, selected = a.id == actor.id, tag = delegateActorTag(a.id)) { actor = a }
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it },
                label = { Text("What is it doing?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(DelegateInstructionTag)
            )

            Spacer(Modifier.height(14.dp))
            Text("CHECK IN", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = VirlinColors.TextTertiary, letterSpacing = 0.8.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AttentionTiming.checkAgainPresets.forEach { m ->
                    Chip("${m}m", selected = minutes == m, tag = delegateCheckInTag(m)) { minutes = m; customMinutes = "" }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedTextField(
                    value = customMinutes,
                    onValueChange = { v ->
                        customMinutes = v.filter { it.isDigit() }.take(4)
                        minutes = customMinutes.toLongOrNull()
                    },
                    label = { Text("min") },
                    singleLine = true,
                    modifier = Modifier.width(96.dp).testTag(DelegateCustomTag)
                )
            }

            Spacer(Modifier.height(14.dp))
            Text("STAGES (OPTIONAL)", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = VirlinColors.TextTertiary, letterSpacing = 0.8.sp)
            stages.forEachIndexed { i, st ->
                Text(
                    "${i + 1}. ${st.title}" + (st.expectedMinutes?.let { " · ~${it}m" } ?: ""),
                    fontSize = 12.sp, color = VirlinColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp).testTag(delegateStageTag(i))
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = stageText,
                    onValueChange = { stageText = it },
                    label = { Text("Add a stage") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag(DelegateStageInputTag)
                )
                Spacer(Modifier.width(8.dp))
                Chip("+ ADD", selected = false, tag = DelegateAddStageTag) {
                    val t = stageText.trim()
                    if (t.isNotEmpty()) {
                        stages = stages + NewExternalStage(t, minutes)
                        stageText = ""
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Cancel", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextSecondary,
                    modifier = Modifier.testTag(DelegateCancelTag).clickable(onClick = onDismiss).padding(12.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "DELEGATE", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 0.4.sp,
                    modifier = Modifier
                        .background(VirlinColors.TextPrimary, RoundedCornerShape(50))
                        .testTag(DelegateConfirmTag)
                        .clickable { onDelegate(actor, instruction.trim(), minutes, stages) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
        color = if (selected) Color.White else VirlinColors.TextSecondary,
        modifier = Modifier
            .background(
                if (selected) VirlinColors.TextPrimary else VirlinColors.TextPrimary.copy(alpha = 0.06f),
                RoundedCornerShape(50)
            )
            .testTag(tag)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

const val DelegateDialogTag = "delegate_dialog"
const val DelegateInstructionTag = "delegate_instruction"
const val DelegateCustomTag = "delegate_custom_minutes"
const val DelegateStageInputTag = "delegate_stage_input"
const val DelegateAddStageTag = "delegate_add_stage"
const val DelegateConfirmTag = "delegate_confirm"
const val DelegateCancelTag = "delegate_cancel"
const val DelegateButtonTag = "delegate_button"
fun delegateActorTag(actorId: String) = "delegate_actor_$actorId"
fun delegateCheckInTag(minutes: Long) = "delegate_check_in_$minutes"
fun delegateStageTag(index: Int) = "delegate_stage_$index"
