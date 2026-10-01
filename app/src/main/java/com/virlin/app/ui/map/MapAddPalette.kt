package com.virlin.app.ui.map

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** UI intent, not a new persisted entity type. The host decides which are supported. */
enum class MapAddKind(val label: String) {
    TODO("To-do"), NOTE("Note"),
    TASK("Prompt"), LINK("Link"),
    PDF("PDF"), ATTACHMENT("Attachment"),
    IMAGE("Image"), AUDIO("Audio"),
}

/**
 * Whether this app can actually store this kind against a task today.
 *
 * The enabled choices have an honest live destination: a To-do is a `TaskStep`, Prompt creates
 * a task-context capture, Link opens its full-page Capture workspace with project context, and
 * Note opens the full-page Notes workspace backed by its own `virlin_notes` document, and Audio
 * opens the existing Capture Voice recorder with the selected task as its owner (Audio v1 is
 * recorded voice; imported audio stays with `AttachmentKind.AUDIO` and is not offered here yet).
 * Link and Note deliberately create no map node; that relationship is deferred to the future
 * design. The attachment and voice DOCUMENT tables key on `captureItemId`, so they belong to the
 * Capture Inbox, not to a task - routing a file there would file an inbox item rather than
 * attach anything to this task.
 */
val MapAddKind.isSupported: Boolean
    get() = this == MapAddKind.TODO || this == MapAddKind.TASK ||
        this == MapAddKind.LINK || this == MapAddKind.NOTE ||
        this == MapAddKind.AUDIO || this == MapAddKind.PDF ||
        this == MapAddKind.ATTACHMENT || this == MapAddKind.IMAGE

private val rows = listOf(
    MapAddKind.TODO to MapAddKind.NOTE,
    MapAddKind.TASK to MapAddKind.LINK,
    MapAddKind.PDF to MapAddKind.ATTACHMENT,
    MapAddKind.IMAGE to MapAddKind.AUDIO,
)

private val ink = Color(0xFF19231E)
private val green = Color(0xFF087848)
private val line = Color(0xFFDDE6E1)

/** Put inside the existing map Canvas Box, above the host-owned footer. */
@Composable
fun MapAddPalette(
    selectedTaskTitle: String,
    onDismiss: () -> Unit,
    onChoose: (MapAddKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("mind_map_add_palette"),
        color = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Box(
                Modifier.align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFC8CFCA))
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AddHeaderMark()
                Spacer(Modifier.size(10.dp))
                Text(
                    "Add to $selectedTaskTitle",
                    modifier = Modifier.weight(1f),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close Add menu", tint = ink)
                }
            }
            Spacer(Modifier.height(8.dp))
            // The scrolling option area, masked so buttons dissolve at the edge instead of
            // being sliced off. The mask covers ONLY this viewport - the title, the close
            // button, the sheet background, the app footer and the Orb are all outside it.
            val scrollState = rememberScrollState()
            Box(
                Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    // The fade is painted with DstIn, which needs the content and the mask in
                    // one offscreen layer; without this it would punch through the sheet.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // An edge only fades when there is something past it to scroll to, so a
                        // list that already fits is drawn crisply top and bottom.
                        val topAlpha = if (scrollState.value > 0) 0f else 1f
                        val bottomAlpha = if (scrollState.value < scrollState.maxValue) 0f else 1f
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = topAlpha),
                                0.06f to Color.Black,
                                0.94f to Color.Black,
                                1f to Color.Black.copy(alpha = bottomAlpha),
                                startY = 0f,
                                endY = size.height,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
            ) {
            Column(
                modifier = Modifier.verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rows.forEach { (left, right) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AddChoice(left, Modifier.weight(1f), onChoose)
                        AddChoice(right, Modifier.weight(1f), onChoose)
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun AddChoice(
    kind: MapAddKind,
    modifier: Modifier,
    onChoose: (MapAddKind) -> Unit,
) {
    val supported = kind.isSupported
    val isPrimary = supported && kind == MapAddKind.TODO
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = modifier.heightIn(min = 52.dp)
            .clip(shape)
            .background(
                when {
                    !supported -> Color(0xFFF6F7F6)
                    kind == MapAddKind.NOTE -> Color(0xFFE9F8EF)
                    kind == MapAddKind.TODO -> Color(0xFFF2FAF5)
                    else -> Color.White
                }
            )
            .border(1.dp, if (isPrimary) Color(0xFFC5E9D1) else line, shape)
            // An unsupported kind is not clickable at all, so there is no path from this button
            // to a success message for something that was never stored.
            .then(
                if (supported) Modifier.clickable(role = Role.Button) { onChoose(kind) }
                else Modifier.semantics {
                    disabled()
                    contentDescription = kind.label + ", not available yet"
                }
            )
            .testTag("mind_map_add_${kind.name.lowercase()}")
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            iconFor(kind), contentDescription = null,
            tint = if (supported) green else Color(0xFFA9B4AE), modifier = Modifier.size(22.dp)
        )
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                kind.label, color = if (supported) ink else Color(0xFF8A958F), fontSize = 14.sp,
                fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (!supported) Text(
                "Not yet", color = Color(0xFFA9B4AE), fontSize = 10.sp, maxLines = 1,
            )
        }
    }
}

private fun iconFor(kind: MapAddKind): ImageVector = when (kind) {
    MapAddKind.TODO -> Icons.Outlined.CheckBox
    MapAddKind.NOTE -> Icons.Outlined.Description
    MapAddKind.TASK -> Icons.Outlined.Assignment
    MapAddKind.LINK -> Icons.Outlined.Link
    MapAddKind.PDF -> Icons.Outlined.PictureAsPdf
    MapAddKind.ATTACHMENT -> Icons.Outlined.AttachFile
    MapAddKind.IMAGE -> Icons.Outlined.Image
    MapAddKind.AUDIO -> Icons.Outlined.MicNone
}

/**
 * A plain white plus on a small rounded green square.
 *
 * This is the POPUP's header mark only. The node icons on the map are drawn by the canvas and
 * are untouched by it.
 */
@Composable
private fun AddHeaderMark() {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(green)
            .semantics { contentDescription = "Add to selected task" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Add, contentDescription = null,
            tint = Color.White, modifier = Modifier.size(22.dp),
        )
    }
}
