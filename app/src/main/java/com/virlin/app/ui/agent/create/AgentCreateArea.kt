package com.virlin.app.ui.agent.create

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.model.WorkStreamMode
import com.virlin.app.ui.hierarchy.TaskTreeRow
import com.virlin.app.ui.theme.VirlinColors

/**
 * CREATE mode content inside the frozen Agent shell (Pass 9): deterministic, structured
 * creation of a Project, WorkStream or Task. Every choice is an explicit control (type chip,
 * picker row, mode chip); nothing is inferred from text. Pure rendering of
 * [AgentCreateState]; every tap is a ViewModel intent that ends in `VirlinActions`.
 */
private val Hairline = Color(0x1F162016)

const val AgentCreateTag = "agent_create"
const val AgentCreateTitleTag = "agent_create_title"
const val AgentCreateEstimateTag = "agent_create_estimate"
const val AgentCreateSubmitTag = "agent_create_submit"
const val AgentCreateErrorTag = "agent_create_error"
const val AgentCreateSuccessTag = "agent_create_success"
const val AgentCreateParentPickerTag = "agent_create_parent_picker"
fun createKindTag(k: CreateKind) = "create_kind_${k.name.lowercase()}"
fun createModeTag(m: WorkStreamMode) = "create_mode_${m.name.lowercase()}"
fun createOwnerTag(o: TaskOwnerKind) = "create_owner_${o.name.lowercase()}"
fun createProjectTag(id: String?) = "create_project_${id ?: "none"}"
fun createStreamTag(id: String) = "create_stream_$id"
const val CreateNextAddWorkStream = "create_next_add_workstream"
const val CreateNextAddTask = "create_next_add_task"
const val CreateNextAddSubtask = "create_next_add_subtask"
const val CreateNextSetCurrent = "create_next_set_current"
const val CreateNextFocusNow = "create_next_focus_now"
const val CreateNextDone = "create_next_done"

@Composable
fun AgentCreateArea(vm: AgentCreateViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val form = state.form
    Column(modifier = modifier.fillMaxWidth().testTag(AgentCreateTag)) {
        form.created?.let { AfterCreate(it, vm); return@Column }

        // Stitch Create UI: three large cards choose the kind (the old PROJECT | WORKSTREAM | TASK chips are gone);
        // once a kind is chosen the existing Create V1 form continues below without the cards.
        val kind = form.kind ?: run {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CreateKind.values().forEach { k -> CreateCard(k) { vm.choose(k) } }
            }
            return@Column
        }
        SectionLabel("CREATE · ${kind.label}")
        Spacer(Modifier.height(6.dp))
        TitleField(form.title, vm::setTitle, placeholder = when (kind) {
            CreateKind.PROJECT -> "Project name"
            CreateKind.WORKSTREAM -> "WorkStream name"
            CreateKind.TASK -> "Task name"
        })
        when (kind) {
            CreateKind.PROJECT -> Unit
            CreateKind.WORKSTREAM -> {
                Spacer(Modifier.height(10.dp))
                SectionLabel("PROJECT · OPTIONAL")
                ProjectPicker(state, vm)
                Spacer(Modifier.height(10.dp))
                SectionLabel("MODE")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice("I do the work", createModeTag(WorkStreamMode.HUMAN), selected = form.mode == WorkStreamMode.HUMAN) { vm.setMode(WorkStreamMode.HUMAN) }
                    Choice("It can continue without me", createModeTag(WorkStreamMode.EXTERNAL), selected = form.mode == WorkStreamMode.EXTERNAL) { vm.setMode(WorkStreamMode.EXTERNAL) }
                }
            }
            CreateKind.TASK -> {
                Spacer(Modifier.height(10.dp))
                SectionLabel("BELONGS TO")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice("WorkStream", createOwnerTag(TaskOwnerKind.WORKSTREAM), selected = form.ownerKind == TaskOwnerKind.WORKSTREAM) { vm.setOwnerKind(TaskOwnerKind.WORKSTREAM) }
                    Choice("Project (standalone)", createOwnerTag(TaskOwnerKind.PROJECT), selected = form.ownerKind == TaskOwnerKind.PROJECT) { vm.setOwnerKind(TaskOwnerKind.PROJECT) }
                }
                Spacer(Modifier.height(8.dp))
                when (form.ownerKind) {
                    TaskOwnerKind.WORKSTREAM -> StreamPicker(state, vm)
                    TaskOwnerKind.PROJECT -> ProjectPicker(state, vm, allowNone = false)
                }
                if (state.parentRows.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SectionLabel(if (form.parentTaskId != null) "UNDER · ${state.selectedParentTitle}" else "UNDER · top level (tap a task to nest)")
                    Column(modifier = Modifier.fillMaxWidth().testTag(AgentCreateParentPickerTag)) {
                        state.parentRows.forEach { row ->
                            val selected = row.id == form.parentTaskId
                            Box(Modifier.fillMaxWidth().background(if (selected) VirlinColors.FocusSurface.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(12.dp))) {
                                TaskTreeRow(row, onOpen = { vm.setParent(it) }, onToggle = vm::toggleExpanded, onComplete = {})
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                SectionLabel("ESTIMATE · MINUTES · OPTIONAL")
                EstimateField(form.estimateMinutes, vm::setEstimate)
            }
        }
        form.error?.let {
            Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.Amber,
                modifier = Modifier.testTag(AgentCreateErrorTag).padding(top = 10.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("CREATE", AgentCreateSubmitTag, primary = true) { vm.create() }
            Chip("CANCEL", "agent_create_cancel", primary = false) { vm.reset() }
        }
    }
}

// ---------------------------------------------------------------- Create cards (Stitch)

private data class CardLook(val subtitle: String, val tile: Color, val icon: ImageVector, val tint: Color, val filled: Color?)

private fun lookOf(k: CreateKind) = when (k) {
    CreateKind.PROJECT -> CardLook("A larger outcome", Color(0xFFFFF6E6), Icons.Rounded.Folder, Color(0xFFEAA023), null)          // warm cream / amber
    CreateKind.WORKSTREAM -> CardLook("A focused area of work", Color(0xFFEEF6F3), Icons.Rounded.AccountTree, Color(0xFF2F7A62), null) // restrained mint / cool
    CreateKind.TASK -> CardLook("An actionable step", Color(0xFFE8F8EE), Icons.Rounded.Check, Color.White, Color(0xFF10B981))       // mint green + check
}

private val CardBorder = Color(0xFFEFEFEF)
private val Zinc400 = Color(0xFFA1A1AA)
private val Zinc900 = Color(0xFF18181B)

@Composable
private fun CreateCard(k: CreateKind, onClick: () -> Unit) {
    val l = lookOf(k)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .scale(if (pressed) 0.985f else 1f)
            .background(if (pressed) Color(0xFFFAFAFA) else Color.White, RoundedCornerShape(22.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(22.dp))
            .testTag(createKindTag(k))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Create ${k.title}, ${l.subtitle}" }
            .padding(16.dp)
    ) {
        Box(Modifier.size(52.dp).background(l.tile, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            if (l.filled != null) Box(Modifier.size(28.dp).background(l.filled, CircleShape), contentAlignment = Alignment.Center) {
                Icon(l.icon, contentDescription = null, tint = l.tint, modifier = Modifier.size(16.dp))
            } else Icon(l.icon, contentDescription = null, tint = l.tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(k.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Zinc900)
            Text(l.subtitle, fontSize = 14.sp, color = Zinc400, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Zinc400, modifier = Modifier.size(20.dp))
    }
}

private val CreateKind.title: String get() = when (this) { CreateKind.PROJECT -> "Project"; CreateKind.WORKSTREAM -> "WorkStream"; CreateKind.TASK -> "Task" }

@Composable
private fun AfterCreate(created: Created, vm: AgentCreateViewModel) {
    val (line, subject) = when (created) {
        is Created.ProjectCreated -> "Created project · ${created.project.title}" to created.project.title
        is Created.WorkStreamCreated -> "Created WorkStream · ${created.stream.title}" to created.stream.title
        is Created.TaskCreated -> "Created task · ${created.task.title}" to created.task.title
    }
    Column(modifier = Modifier.fillMaxWidth().background(VirlinColors.FocusSurface, RoundedCornerShape(14.dp))
        .border(1.dp, VirlinColors.Emerald.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
        .testTag(AgentCreateSuccessTag).semantics { contentDescription = line }.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(line, fontSize = 13.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Saved. What next for $subject?", fontSize = 11.sp, color = VirlinColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (created) {
                is Created.ProjectCreated -> {
                    Chip("ADD WORKSTREAM", CreateNextAddWorkStream, primary = true) { vm.addWorkStreamTo(created.project.id) }
                    Chip("ADD TASK", CreateNextAddTask, primary = false) { vm.addStandaloneTaskTo(created.project.id) }
                }
                is Created.WorkStreamCreated -> {
                    Chip("ADD TASK", CreateNextAddTask, primary = true) { vm.addTaskTo(created.stream.id) }
                    Chip("FOCUS NOW", CreateNextFocusNow, primary = false) { vm.focusNow(created.stream.id); vm.reset() }
                }
                is Created.TaskCreated -> {
                    Chip("ADD SUBTASK", CreateNextAddSubtask, primary = true) { vm.addSubtaskTo(created.task) }
                    if (created.task.workStreamId != null) Chip("SET CURRENT", CreateNextSetCurrent, primary = false) { vm.setCurrent(created.task); vm.reset() }
                }
            }
            Chip("DONE", CreateNextDone, primary = false) { vm.reset() }
        }
    }
}

@Composable
private fun ProjectPicker(state: AgentCreateState, vm: AgentCreateViewModel, allowNone: Boolean = true) {
    val f = state.form
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (allowNone) PickRow("No Project", createProjectTag(null), selected = f.projectId == null) { vm.setProject(null) }
        if (!allowNone && state.projects.isEmpty()) Text("No projects yet — create one first.", fontSize = 12.sp, color = VirlinColors.TextSecondary)
        state.projects.forEach { p -> PickRow(p.title, createProjectTag(p.id), selected = f.projectId == p.id) { vm.setProject(p.id) } }
    }
}

@Composable
private fun StreamPicker(state: AgentCreateState, vm: AgentCreateViewModel) {
    val f = state.form
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.workStreams.isEmpty()) Text("No WorkStreams yet — create one first.", fontSize = 12.sp, color = VirlinColors.TextSecondary)
        state.workStreams.forEach { s ->
            val project = s.projectId?.let { id -> state.projects.firstOrNull { it.id == id }?.title }
            PickRow(if (project != null) "$project · ${s.title}" else s.title, createStreamTag(s.id), selected = f.workStreamId == s.id) { vm.setWorkStream(s.id) }
        }
    }
}

@Composable
private fun PickRow(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
        .background(if (selected) VirlinColors.FocusSurface else Color.White, RoundedCornerShape(10.dp))
        .border(1.dp, if (selected) VirlinColors.Emerald.copy(alpha = 0.5f) else Hairline, RoundedCornerShape(10.dp))
        .testTag(tag).clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = if (selected) "$label, selected" else label }
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = VirlinColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) { Spacer(Modifier.width(8.dp)); Text("●", fontSize = 10.sp, color = VirlinColors.Emerald) }
    }
}

@Composable
private fun TitleField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Field(value, onChange, placeholder, AgentCreateTitleTag, KeyboardType.Text)
}

@Composable
private fun EstimateField(value: String, onChange: (String) -> Unit) {
    Field(value, onChange, "e.g. 25", AgentCreateEstimateTag, KeyboardType.Number)
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, tag: String, type: KeyboardType) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        textStyle = TextStyle(fontSize = 14.sp, color = VirlinColors.TextPrimary),
        cursorBrush = SolidColor(VirlinColors.Emerald),
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).border(1.dp, Hairline, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (value.isEmpty()) Text(placeholder, fontSize = 14.sp, color = VirlinColors.TextTertiary)
                inner()
            }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary, modifier = Modifier.padding(bottom = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Choice(label: String, tag: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp,
        color = if (selected) Color.White else VirlinColors.TextPrimary,
        modifier = Modifier.heightIn(min = 36.dp)
            .background(if (selected) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
            .border(1.dp, if (selected) Color.Transparent else Hairline, RoundedCornerShape(50))
            .testTag(tag).clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (selected) "$label, selected" else label }
            .padding(horizontal = 12.dp, vertical = 9.dp), maxLines = 1)
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

private val CreateKind.label: String get() = when (this) { CreateKind.PROJECT -> "PROJECT"; CreateKind.WORKSTREAM -> "WORKSTREAM"; CreateKind.TASK -> "TASK" }
