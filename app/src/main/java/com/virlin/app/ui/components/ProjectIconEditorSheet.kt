package com.virlin.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProjectIconSelection
import com.virlin.app.ui.theme.VirlinColors

const val ProjectIconEditorTestTag = "project_icon_editor"
const val ProjectIconEditorPreviewTestTag = "project_icon_editor_preview"
const val ProjectIconEditorAutoTestTag = "project_icon_editor_auto"
const val ProjectIconEditorChooseImageTestTag = "project_icon_editor_choose_image"
const val ProjectIconEditorRemoveImageTestTag = "project_icon_editor_remove_image"
fun projectIconChoiceTag(id: String) = "project_icon_choice_$id"

/**
 * Project icon editor (Phase 3): a compact bottom sheet — large preview · name · the built-in
 * icon grid · CUSTOM (Choose image / Remove custom image) · Use automatic icon. Every tap
 * applies immediately through the callbacks (no Save / Cancel); the sheet stays open so the
 * preview reflects the change. All state comes from [project] (the repository), never copied.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectIconEditorSheet(
    project: Project,
    onSelectBuiltIn: (String) -> Unit,
    onCustomImported: (String) -> Unit,
    onRemoveCustom: () -> Unit,
    onUseAuto: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberProjectImagePicker(project.id, onImported = { error = null; onCustomImported(it) }, onError = { error = it })
    val selection = ProjectIconSelection.of(project)
    val hairline = VirlinColors.TextPrimary.copy(alpha = 0.08f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VirlinColors.Background,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 2.dp).width(36.dp).height(4.dp).background(VirlinColors.TextPrimary.copy(alpha = 0.12f), RoundedCornerShape(2.dp))) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .testTag(ProjectIconEditorTestTag)
        ) {
            Text("Project icon", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ProjectIcon(project = project, size = 72.dp, modifier = Modifier.testTag(ProjectIconEditorPreviewTestTag))
            }
            Spacer(Modifier.height(8.dp))
            Text(project.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text(
                when (selection) {
                    is ProjectIconSelection.Custom -> "Custom image"
                    is ProjectIconSelection.BuiltIn -> BuiltInProjectIcons.lookOf(selection.id)?.label ?: "Built-in icon"
                    is ProjectIconSelection.Auto -> "Automatic · " + (BuiltInProjectIcons.lookOf(selection.id)?.label ?: "")
                },
                fontSize = 11.5.sp, color = VirlinColors.TextSecondary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(18.dp))
            Text("CHOOSE AN ICON", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary)
            Spacer(Modifier.height(10.dp))
            val chosenBuiltIn = (selection as? ProjectIconSelection.BuiltIn)?.id
            // Responsive grid: fixed 58dp tiles wrap to the available width (4 per row on a normal phone).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 6) {
                BuiltInProjectIcons.all.forEach { look ->
                    val chosen = look.id == chosenBuiltIn
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(look.surface, RoundedCornerShape(16.dp))
                            .border(if (chosen) 2.dp else 1.dp, if (chosen) VirlinColors.Emerald else hairline, RoundedCornerShape(16.dp))
                            .testTag(projectIconChoiceTag(look.id))
                            .clickable(role = Role.RadioButton) { onSelectBuiltIn(look.id) }
                            .semantics { contentDescription = "${look.label} icon"; selected = chosen },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(look.icon, contentDescription = null, tint = look.symbol, modifier = Modifier.size(26.dp))
                        if (chosen) Box(
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp).background(VirlinColors.Emerald, CircleShape),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp)) }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("CUSTOM", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                EditorChip(
                    label = if (selection is ProjectIconSelection.Custom) "Change image" else "Choose image",
                    tag = ProjectIconEditorChooseImageTestTag, primary = true, description = "Choose custom project icon"
                ) { picker.choose() }
                if (selection is ProjectIconSelection.Custom) EditorChip(
                    label = "Remove custom image", tag = ProjectIconEditorRemoveImageTestTag, primary = false, description = "Remove custom project icon"
                ) { onRemoveCustom() }
            }
            error?.let { Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.Amber, modifier = Modifier.padding(top = 8.dp)) }

            Spacer(Modifier.height(14.dp))
            val isAuto = selection is ProjectIconSelection.Auto
            Text(
                if (isAuto) "Using the automatic icon" else "Use automatic icon",
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (isAuto) VirlinColors.TextTertiary else VirlinColors.Emerald,
                modifier = Modifier
                    .testTag(ProjectIconEditorAutoTestTag)
                    .clickable(enabled = !isAuto, role = Role.Button) { onUseAuto() }
                    .semantics { contentDescription = "Use automatic project icon"; selected = isAuto }
                    .padding(vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun EditorChip(label: String, tag: String, primary: Boolean, description: String, onClick: () -> Unit) {
    val hairline = VirlinColors.TextPrimary.copy(alpha = 0.08f)
    Text(
        label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp,
        color = if (primary) Color.White else VirlinColors.TextPrimary,
        modifier = Modifier.heightIn(min = 36.dp)
            .background(if (primary) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
            .border(1.dp, if (primary) Color.Transparent else hairline, RoundedCornerShape(50))
            .testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    )
}
