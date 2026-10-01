package com.virlin.app.ui.map.preview

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LayersClear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.mappreview.FormatMark
import com.virlin.app.domain.mappreview.MapPreviewImage

const val MapPreviewPopupTag = "map_preview_popup"
const val MapPreviewScrimTag = "map_preview_scrim"
const val MapPreviewDescriptionTag = "map_preview_description"
const val MapPreviewSaveTag = "map_preview_save"
const val MapPreviewCancelTag = "map_preview_cancel"
const val MapPreviewClearTag = "map_preview_clear"
const val MapPreviewCloseTag = "map_preview_close"
const val MapPreviewAddImageTag = "map_preview_add_image"
const val MapPreviewReplaceTag = "map_preview_replace"
const val MapPreviewDeleteImageTag = "map_preview_delete_image"
fun mapPreviewMarkTag(mark: FormatMark) = "map_preview_mark_${mark.name.lowercase()}"
fun mapPreviewThumbTag(index: Int) = "map_preview_thumb_$index"

private val Emerald = Color(0xFF0E7A54)
private val PaleMint = Color(0xFFDCF0E6)
private val Ink = Color(0xFF192229)
private val Muted = Color(0xFF637283)
private val MutedRed = Color(0xFFB4504C)

/**
 * The node preview editor.
 *
 * It is a compact card over the map rather than a page or a bottom sheet, because the point is to
 * keep the node it belongs to in view. The background is dimmed by a full-surface scrim that also
 * swallows touches, so the map, the action bar, the zoom controls, the Orb and the bottom navigation
 * are all visibly and functionally behind it.
 *
 * **Blur:** `RenderEffect` blur needs API 31+ and costs a full-surface render pass every frame on a
 * map that may be mid-animation. The approximation used here is a strong scrim instead of a true
 * blur, which is honest about the platform floor and costs nothing per frame. This is a documented
 * deliberate deviation from the mockup's frosted background.
 */
@Composable
fun MapNodePreviewPopup(
    nodeTitle: String,
    draft: MapPreviewDraft,
    onDraftChange: (MapPreviewDraft) -> Unit,
    onPickImage: () -> Unit,
    onReplaceImage: () -> Unit,
    onRequestClear: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    imageLoader: @Composable (MapPreviewImage, Modifier) -> Unit,
) {
    BackHandler(onBack = onCancel)
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "preview-popup-appear",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = .46f * appear))
            .testTag(MapPreviewScrimTag)
            // Swallows every touch so nothing behind the popup can be operated through it.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag(MapPreviewPopupTag)
                // Consumes clicks so a tap inside the card never reaches the dismissing scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Header(nodeTitle, draft.hasContent, onRequestClear, onCancel)
                Spacer(Modifier.height(14.dp))
                DescriptionEditor(draft, onDraftChange)
                Spacer(Modifier.height(16.dp))
                ImageEditor(draft, onDraftChange, onPickImage, onReplaceImage, imageLoader)
                Spacer(Modifier.height(18.dp))
                Footer(draft.saving, onCancel, onSave)
            }
        }
    }
}

@Composable
private fun Header(
    nodeTitle: String,
    hasContent: Boolean,
    onRequestClear: () -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Preview details", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            // The node stays identified, so the card can never be mistaken for a global setting.
            Text(nodeTitle, fontSize = 12.sp, color = Muted, maxLines = 1)
        }
        if (hasContent) {
            IconOnly(
                // Deliberately NOT another X: clearing the preview and closing the editor are
                // different actions and must not share a glyph.
                icon = Icons.Outlined.LayersClear,
                // Distinct from "Close" and from deleting the task: it empties the preview only.
                description = "Clear this preview's description and images",
                tag = MapPreviewClearTag,
                tint = Muted,
                background = Color(0xFFF1F3F2),
                onClick = onRequestClear,
            )
            Spacer(Modifier.width(8.dp))
        }
        IconOnly(
            icon = Icons.Outlined.Close,
            description = "Close preview editor without saving",
            tag = MapPreviewCloseTag,
            tint = Ink,
            background = Color(0xFFF1F3F2),
            onClick = onClose,
        )
    }
}

@Composable
private fun DescriptionEditor(draft: MapPreviewDraft, onChange: (MapPreviewDraft) -> Unit) {
    // The field owns only the caret; the formatted text itself lives in the draft, so a
    // recomposition can never resurrect a stale selection over new content.
    var selection by remember { mutableStateOf(TextRange(draft.description.text.length)) }
    val value = TextFieldValue(
        annotatedDescription(draft.description),
        selection = TextRange(
            selection.start.coerceIn(0, draft.description.text.length),
            selection.end.coerceIn(0, draft.description.text.length),
        ),
    )
    Text("Short description", fontSize = 13.sp, color = Emerald, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF7F9F8),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    selection = next.selection
                    if (next.text != draft.description.text) onChange(draft.withText(next.text))
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink, fontSize = 16.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    // Roughly two lines at rest, growing to about five before it scrolls itself.
                    .heightIn(min = 48.dp, max = 120.dp)
                    .testTag(MapPreviewDescriptionTag),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarkToggle(FormatMark.BOLD, "Bold", draft, selection, onChange)
                Spacer(Modifier.width(8.dp))
                MarkToggle(FormatMark.ITALIC, "Italic", draft, selection, onChange)
                Spacer(Modifier.weight(1f))
                Text(draft.characterCounter, fontSize = 12.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun MarkToggle(
    mark: FormatMark,
    label: String,
    draft: MapPreviewDraft,
    selection: TextRange,
    onChange: (MapPreviewDraft) -> Unit,
) {
    val active = draft.isActive(mark, selection.min, selection.max)
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (active) PaleMint else Color.Transparent,
        modifier = Modifier
            .size(40.dp)
            .testTag(mapPreviewMarkTag(mark))
            .semantics {
                contentDescription = label
                // A screen reader hears whether the mark is on, not just its name.
                toggleableState = if (active) ToggleableState.On else ToggleableState.Off
                stateDescription = if (active) "On" else "Off"
                role = Role.Switch
            }
            .clickable { onChange(draft.toggleMark(mark, selection.min, selection.max)) },
    ) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                if (mark == FormatMark.BOLD) "B" else "I",
                fontSize = 16.sp,
                color = if (active) Emerald else Muted.copy(alpha = .75f),
                fontWeight = if (mark == FormatMark.BOLD) FontWeight.Bold else FontWeight.Medium,
                fontStyle = if (mark == FormatMark.ITALIC) FontStyle.Italic else FontStyle.Normal,
            )
        }
    }
}

@Composable
private fun ImageEditor(
    draft: MapPreviewDraft,
    onChange: (MapPreviewDraft) -> Unit,
    onPickImage: () -> Unit,
    onReplaceImage: () -> Unit,
    imageLoader: @Composable (MapPreviewImage, Modifier) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Image", fontSize = 13.sp, color = Emerald, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Text(draft.counter, fontSize = 12.sp, color = Muted)
    }
    Spacer(Modifier.height(8.dp))

    val active = draft.active
    if (active != null) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFEFF2F0)),
        ) {
            imageLoader(active, Modifier.fillMaxSize())
            // Frosted icon-only overlays: no visible "Replace image" text, no separate delete row.
            FrostedAction(
                icon = Icons.Outlined.Image,
                description = "Replace this image",
                tag = MapPreviewReplaceTag,
                tint = Ink,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                onClick = onReplaceImage,
            )
            FrostedAction(
                icon = Icons.Filled.Delete,
                description = "Delete this image",
                tag = MapPreviewDeleteImageTag,
                tint = MutedRed,
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                onClick = { onChange(draft.deleteActive()) },
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        draft.images.forEachIndexed { index, image ->
            val selected = index == draft.activeImage
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0xFFEFF2F0))
                    .testTag(mapPreviewThumbTag(index))
                    .semantics { contentDescription = "Image ${index + 1} of ${draft.images.size}" }
                    .clickable { onChange(draft.select(index)) },
            ) {
                imageLoader(image, Modifier.fillMaxSize())
                if (selected) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(Emerald.copy(alpha = .18f))
                            .clip(RoundedCornerShape(11.dp)),
                    )
                }
            }
        }
        if (draft.canAddImage) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0xFFF4F6F5))
                    .testTag(MapPreviewAddImageTag)
                    .semantics { contentDescription = "Add an image, ${draft.counter}" }
                    .clickable(onClick = onPickImage),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = Muted)
            }
        }
    }
}

@Composable
private fun Footer(saving: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f).height(48.dp).testTag(MapPreviewCancelTag),
        ) { Text("Cancel", color = Ink) }
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.weight(1f).height(48.dp).testTag(MapPreviewSaveTag),
            colors = ButtonDefaults.buttonColors(containerColor = Emerald),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Save") }
    }
}

@Composable
private fun IconOnly(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    tint: Color,
    background: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            // The glyph is small; the touch target is not.
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .testTag(tag)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FrostedAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    tint: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = .82f))
            .testTag(tag)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}
