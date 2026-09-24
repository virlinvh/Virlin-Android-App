package com.virlin.app.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.data.projecticon.ProjectIconStore
import com.virlin.app.domain.model.ProjectIdentity
import com.virlin.app.ui.theme.VirlinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ProjectIconPickerTestTag = "project_icon_picker"
const val ProjectIconChooseTestTag = "project_icon_choose"
const val ProjectIconRemoveTestTag = "project_icon_remove"
const val ProjectIconErrorTestTag = "project_icon_error"

/**
 * Shared Photo Picker launcher: validates + imports the picked image into the managed store
 * off the main thread and reports the relative path (or a user-facing error). No permissions.
 */
class ProjectImagePicker internal constructor(private val launch: () -> Unit) {
    fun choose() = launch()
}

@Composable
fun rememberProjectImagePicker(projectId: String, onImported: (String) -> Unit, onError: (String) -> Unit): ProjectImagePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ProjectIconStore.importFromUri(context, projectId, uri) } }
            result.onSuccess(onImported)
                .onFailure { onError(if (it is ProjectIconStore.UnsupportedImage) "Choose a PNG, JPEG or WebP image." else "Couldn't use that image.") }
        }
    }
    return remember(projectId) { ProjectImagePicker { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
}

/**
 * Reusable project icon editor (compact inline form; the Project Detail editor sheet is `ProjectIconEditorSheet`):
 * current preview (custom image or the fallback avatar) · Choose / Change image · Remove.
 *
 * Uses the Android Photo Picker (`PickVisualMedia`, images only) — no storage or media
 * permission is requested. A picked image is validated (PNG / JPEG / WebP, decodable), copied
 * into Virlin's managed store, and its relative path handed to [onIconChanged]; the caller
 * persists it through `VirlinActions.updateProject(iconPath = Field.Set/Clear)`. Removing
 * deletes the managed file and reports `null` (→ fallback everywhere the project appears).
 */
@Composable
fun ProjectIconPicker(
    projectId: String,
    name: String,
    iconPath: String?,
    onIconChanged: (String?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val busy = false
    val picker = rememberProjectImagePicker(projectId, onImported = { error = null; onIconChanged(it) }, onError = { error = it })
    val hasCustom = ProjectIdentity.hasCustomIcon(iconPath)

    Column(modifier = modifier.fillMaxWidth().testTag(ProjectIconPickerTestTag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectIcon(projectId = projectId, name = name, iconPath = iconPath, size = 56.dp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(if (hasCustom) "Custom icon" else "Default icon", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary)
                Text(if (hasCustom) "Shown wherever this project's work appears." else "Initials on the project colour.", fontSize = 11.5.sp, color = VirlinColors.TextSecondary)
            }
        }
        Spacer(Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(
                label = if (hasCustom) "Change image" else "Choose image", tag = ProjectIconChooseTestTag, primary = true,
                enabled = enabled && !busy, description = "Choose an image for $name"
            ) { picker.choose() }
            if (hasCustom) ActionChip(
                label = "Remove", tag = ProjectIconRemoveTestTag, primary = false, enabled = enabled && !busy, description = "Remove the custom icon of $name"
            ) {
                scope.launch { withContext(Dispatchers.IO) { ProjectIconStore.delete(context, projectId) }; onIconChanged(null) }
            }
        }
        error?.let {
            Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.Amber, modifier = Modifier.testTag(ProjectIconErrorTestTag).padding(top = 8.dp))
        }
    }
}

@Composable
private fun ActionChip(label: String, tag: String, primary: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val hairline = VirlinColors.TextPrimary.copy(alpha = 0.08f)
    Text(
        label, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp,
        color = if (primary) Color.White else VirlinColors.TextPrimary,
        modifier = Modifier.heightIn(min = 36.dp)
            .background(if (primary) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
            .border(1.dp, if (primary) Color.Transparent else hairline, RoundedCornerShape(50))
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    )
}
