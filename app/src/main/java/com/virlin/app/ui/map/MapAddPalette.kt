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
 * Link and Note deliberately create no map node. PDF, Attachment and Image open their existing
 * task-owned workspaces. Unsupported decorative placeholders are not offered in this menu.
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
private val green: Color
    @Composable get() = com.virlin.app.ui.hierarchy.LocalProjectPalette.current?.let { Color(it.secondary) }
        ?: Color(0xFF087848)
private val line = Color(0xFFDDE6E1)

/** Put inside the existing map Canvas Box, above the host-owned footer. */
@Composable
fun MapAddPalette(
    selectedTaskTitle: String,
    onDismiss: () -> Unit,
    onChoose: (MapAddKind) -> Unit,
    modifier: Modifier = Modifier,
    /** Centered dialogs have four rounded corners and no sheet drag handle. */
    dialogMode: Boolean = false,
    onAddChild: (() -> Unit)? = null,
    knowledgeMode: Boolean = false,
) {
    androidx.compose.runtime.DisposableEffect(Unit) {
        val owner = Any()
        com.virlin.app.ui.hierarchy.KnowledgeOrbVisibility.enter(owner)
        onDispose { com.virlin.app.ui.hierarchy.KnowledgeOrbVisibility.leave(owner) }
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("mind_map_add_palette"),
        color = Color.White,
        shape = if (dialogMode) RoundedCornerShape(24.dp)
            else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = if (dialogMode) 0.dp else 8.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp,
            top = if (dialogMode) 16.dp else 8.dp, bottom = if (dialogMode) 16.dp else 12.dp)) {
            if (!dialogMode) {
            Box(
                Modifier.align(Alignment.CenterHorizontally)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFFC8CFCA))
            )
            Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!knowledgeMode) { AddHeaderMark(); Spacer(Modifier.size(10.dp)) }
                Column(Modifier.weight(1f)) {
                Text(
                    if (knowledgeMode) "Add to knowledge" else "Add to $selectedTaskTitle",
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = if (knowledgeMode) FontWeight.SemiBold else FontWeight.Normal,
                    color = com.virlin.app.ui.hierarchy.LocalProjectPalette.current?.let { Color(it.muted) }
                        ?: Color(0xFF526171),
                    maxLines = if (dialogMode) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (knowledgeMode) Text("Saved to $selectedTaskTitle", fontSize = 12.sp, color = Color(0xFF63717B))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close Add menu", tint = ink)
                }
            }
            Spacer(Modifier.height(8.dp))
            // The scrolling option area, masked so buttons dissolve at the edge instead of
            onAddChild?.let { addChild ->
                androidx.compose.material3.TextButton(onClick = addChild) {
                    Text("New subtask · Single or Bulk", color = green)
                }
            }
            // being sliced off. The mask covers ONLY this viewport - the title, the close
            // button, the sheet background, the app footer and the Orb are all outside it.
            val scrollState = rememberScrollState()
            Box(
                Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    // The fade is painted with DstIn, which needs the content and the mask in
                    // one offscreen layer; without this it would punch through the sheet.
                    // Centered dialogs do not need a fade layer. Avoid allocating a second
                    // offscreen render target inside the separate Android dialog window.
                    .graphicsLayer { compositingStrategy = if (dialogMode)
                        CompositingStrategy.Auto else CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        if (dialogMode) return@drawWithContent
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
                        AddChoice(left, Modifier.weight(1f), onChoose, knowledgeMode)
                        AddChoice(right, Modifier.weight(1f), onChoose, knowledgeMode)
                    }
                }
            }
            }
            if (knowledgeMode) Text("Store here. Organize or attach to a mind map later.",
                Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.lerp(Color.White, green, .05f)).padding(10.dp),
                fontSize = 11.sp, color = Color(0xFF63717B))
        }
    }
}

@Composable
private fun AddChoice(
    kind: MapAddKind,
    modifier: Modifier,
    onChoose: (MapAddKind) -> Unit,
    knowledgeMode: Boolean = false,
) {
    val supported = kind.isSupported
    val isPrimary = supported && kind == MapAddKind.TODO
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = modifier.heightIn(min = 52.dp)
            .clip(shape)
            .background(
                when {
                    knowledgeMode -> androidx.compose.ui.graphics.lerp(Color.White, green, .05f)
                    !supported -> Color(0xFFF6F7F6)
                    kind == MapAddKind.NOTE -> androidx.compose.ui.graphics.lerp(Color.White, green, .08f)
                    kind == MapAddKind.TODO -> androidx.compose.ui.graphics.lerp(Color.White, green, .04f)
                    else -> Color.White
                }
            )
            .border(1.dp, if (isPrimary) androidx.compose.ui.graphics.lerp(Color.White, green, .25f) else line, shape)
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
        Surface(color = if (knowledgeMode) androidx.compose.ui.graphics.lerp(Color.White, green, .12f) else Color.Transparent,
            shape = RoundedCornerShape(8.dp)) { Icon(
            iconFor(kind), contentDescription = null,
            tint = if (supported) green else Color(0xFFA9B4AE), modifier = Modifier.padding(if (knowledgeMode) 6.dp else 0.dp).size(22.dp)
        ) }
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
