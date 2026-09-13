package com.virlin.app.ui.agent.command

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.virlin.app.domain.command.QueryResult
import com.virlin.app.domain.command.ResolvedCommand
import com.virlin.app.ui.agent.capture.CapturePresentation
import com.virlin.app.ui.theme.VirlinColors

/**
 * Compact command surface above the CONTROL / CREATE content (Pass 12): the current question
 * (clarification candidates), confirmation, create preview, query answer or feedback — one at a
 * time, no transcript. Reuses the Agent chip language; every tap is a ViewModel intent.
 */
private val Hairline = Color(0x1F162016)

const val CommandPanelTag = "agent_command_panel"
const val CommandFeedbackTag = "agent_command_feedback"
const val CommandClarifyTag = "agent_command_clarify"
const val CommandConfirmTag = "agent_command_confirm"
const val CommandPreviewTag = "agent_command_preview"
const val CommandAnswerTag = "agent_command_answer"
const val CommandConfirmYesTag = "agent_command_confirm_yes"
const val CommandConfirmNoTag = "agent_command_confirm_no"
const val CommandPreviewCreateTag = "agent_command_preview_create"
const val CommandPreviewCancelTag = "agent_command_preview_cancel"
const val CommandDismissTag = "agent_command_dismiss"
fun commandCandidateTag(value: String) = "agent_command_candidate_$value"

@Composable
fun AgentCommandPanel(vm: AgentCommandViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    if (state is CommandPanelState.Idle) return
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 12.dp).testTag(CommandPanelTag)) {
        when (val s = state) {
            is CommandPanelState.Idle -> Unit
            is CommandPanelState.Clarify -> Card(CommandClarifyTag, emphasized = true, description = s.clarification.question) {
                Title(s.clarification.question)
                Spacer(Modifier.height(8.dp))
                if (s.clarification.canChoose) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Control 1: the entity kind is shown only when the choices span kinds (Project / WorkStream / Task);
                    // same-kind duplicates keep the plain title + ancestry row.
                    val mixedKinds = s.clarification.candidates.mapNotNull { it.kind }.distinct().size > 1
                    s.clarification.candidates.forEach { c ->
                        PickRow(c.title, listOfNotNull(c.kind.takeIf { mixedKinds }, c.subtitle).joinToString(" · ").ifEmpty { null }, commandCandidateTag(c.value)) { vm.choose(s.clarification, c.value) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Chip("CANCEL", CommandDismissTag, primary = false) { vm.dismiss() }
            }
            is CommandPanelState.Confirm -> Card(CommandConfirmTag, emphasized = true, description = s.confirmation.question) {
                Title(s.confirmation.question)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(confirmLabel(s.confirmation.command), CommandConfirmYesTag, primary = true) { vm.confirm(s.confirmation) }
                    Chip("CANCEL", CommandConfirmNoTag, primary = false) { vm.dismiss() }
                }
            }
            is CommandPanelState.Preview -> Card(CommandPreviewTag, emphasized = false, description = s.command.preview.title) {
                Title(s.command.preview.title)
                s.command.preview.fields.forEach { f -> FieldRow(f.label, f.value) }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(if (s.command is ResolvedCommand.Create) "CREATE" else "CONFIRM", CommandPreviewCreateTag, primary = true) { vm.accept(s.command) }
                    Chip("CANCEL", CommandPreviewCancelTag, primary = false) { vm.dismiss() }
                }
            }
            is CommandPanelState.Answer -> Card(CommandAnswerTag, emphasized = false, description = answerSummary(s.result)) {
                Answer(s.result)
                Spacer(Modifier.height(8.dp))
                Chip("OK", CommandDismissTag, primary = false) { vm.dismiss() }
            }
            is CommandPanelState.Feedback -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(s.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (s.isError) VirlinColors.Amber else VirlinColors.Emerald,
                        modifier = Modifier.testTag(CommandFeedbackTag))
                    if (s.examples.isNotEmpty()) Text("Try: " + s.examples.joinToString(" · "), fontSize = 11.sp, color = VirlinColors.TextSecondary)
                }
                Text("×", fontSize = 16.sp, color = VirlinColors.TextTertiary, modifier = Modifier.testTag(CommandDismissTag)
                    .clickable(role = Role.Button) { vm.dismiss() }.semantics { contentDescription = "Dismiss" }.padding(8.dp))
            }
        }
    }
}

private fun confirmLabel(c: ResolvedCommand) = when (c) { is ResolvedCommand.CompleteStream -> "COMPLETE"; is ResolvedCommand.CancelTask -> "CANCEL TASK"; else -> "CONFIRM" }

@Composable
private fun Answer(r: QueryResult) {
    when (r) {
        is QueryResult.CurrentFocus -> { Title("Current Focus"); r.project?.let { FieldRow("Project", it.title) }; FieldRow("WorkStream", r.workStream.title); r.activeTask?.let { FieldRow("Task", it.title) } ?: FieldRow("Task", "none") }
        QueryResult.NoCurrentFocus -> { Title("Current Focus"); Text("Your attention is free.", fontSize = 12.sp, color = VirlinColors.TextSecondary) }
        is QueryResult.NeedsAttention -> { Title("Needs You · ${r.items.size}"); r.items.forEach { FieldRow(it.workStream.title, when (it.kind) { QueryResult.AttentionKind.HUMAN_RETURN -> "Return due"; QueryResult.AttentionKind.EXTERNAL_CHECK -> "Check due"; QueryResult.AttentionKind.EXTERNAL_RESULT_READY -> "Result ready" }) } }
        is QueryResult.Processing -> { Title("Working For You · ${r.items.size}"); r.items.forEach { FieldRow(it.workStream.title, it.checkAt?.let { "check scheduled" } ?: "no check") } }
        is QueryResult.Ready -> { Title("Ready · ${r.workStreams.size}"); r.workStreams.take(8).forEach { Line(it.title) } }
        is QueryResult.Projects -> { Title("Projects · ${r.projects.size}"); r.projects.forEach { Line(it.title) } }
        is QueryResult.WorkStreams -> { Title("WorkStreams · ${r.workStreams.size}"); r.workStreams.take(10).forEach { Line(it.title) } }
        is QueryResult.Tasks -> { Title("Tasks · ${r.workStream.title}"); r.tasks.take(10).forEach { Line((if (it.id == r.activeTaskId) "● " else "") + it.title) } }
        is QueryResult.CaptureInbox -> { Title("Inbox · ${r.items.size}"); r.items.take(6).forEach { Line("${CapturePresentation.typeLabel(it.type)} · ${CapturePresentation.preview(it)}") } }
    }
}

private fun answerSummary(r: QueryResult): String = when (r) {
    is QueryResult.CurrentFocus -> "Current Focus ${r.workStream.title}" + (r.activeTask?.let { " ${it.title}" } ?: "")
    QueryResult.NoCurrentFocus -> "No current focus"
    is QueryResult.NeedsAttention -> "Needs You ${r.items.size} items"
    is QueryResult.Processing -> "Working for you ${r.items.size} items"
    is QueryResult.Ready -> "Ready ${r.workStreams.size} items"
    is QueryResult.Projects -> "Projects ${r.projects.size}"
    is QueryResult.WorkStreams -> "WorkStreams ${r.workStreams.size}"
    is QueryResult.Tasks -> "Tasks ${r.tasks.size}"
    is QueryResult.CaptureInbox -> "Inbox ${r.items.size} items"
}

@Composable
private fun Card(tag: String, emphasized: Boolean, description: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()
        .background(if (emphasized) VirlinColors.FocusSurface else Color.White, RoundedCornerShape(14.dp))
        .border(1.dp, if (emphasized) VirlinColors.Emerald.copy(alpha = 0.5f) else Hairline, RoundedCornerShape(14.dp))
        .testTag(tag).semantics { contentDescription = description }.padding(horizontal = 12.dp, vertical = 10.dp)) { content() }
}
@Composable private fun Title(t: String) { Text(t, fontSize = 13.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary) }
@Composable private fun Line(t: String) { Text(t, fontSize = 12.sp, color = VirlinColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
@Composable private fun FieldRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, color = VirlinColors.TextTertiary, modifier = Modifier.padding(end = 8.dp).heightIn(min = 18.dp))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
@Composable
private fun PickRow(title: String, subtitle: String?, tag: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Hairline, RoundedCornerShape(10.dp))
        .testTag(tag).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = listOfNotNull(title, subtitle).joinToString(" · ") }
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, fontSize = 10.sp, color = VirlinColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
