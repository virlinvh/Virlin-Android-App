package com.virlin.app.ui.notes

import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder

private data class FanItem(val tool: NoteTool, val icon: ImageVector, val label: String, val angleDeg: Double)

/** Longest pause between taps that still counts as one continuous open · close · open run. */
internal const val ADD_FAN_TAP_RUN_MS = 600L

/**
 * The editor's floating + button. A tap fans three document tools out on a quarter circle
 * (left, up-left, up); the next tap folds them away. Only a third tap in one continuous run
 * (each within [ADD_FAN_TAP_RUN_MS] of the last) opens the Add sheet, so opening and closing
 * the fan and then carrying on never opens the sheet by surprise. Fan buttons dispatch the
 * same [NoteTool]s as the toolbar and close the fan, unless [keepOpenAfter] says the tool is
 * mid-sequence (heading fold steps through its levels with the fan still out). Tapping outside
 * or Back closes the fan. Screen readers get an "Add block" action instead of the triple tap.
 */
@Composable
internal fun NoteAddFan(
    tools: NoteToolState,
    headingFoldLabel: String,
    onTool: (NoteTool) -> Unit,
    onAddBlock: () -> Unit,
    modifier: Modifier = Modifier,
    keepOpenAfter: (NoteTool) -> Boolean = { false },
    now: () -> Long = SystemClock::uptimeMillis,
) {
    var open by remember { mutableStateOf(false) }
    var tapRun by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(Long.MIN_VALUE) }
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else spring(
            dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow,
        ),
        label = "notes_add_fan",
    )
    BackHandler(enabled = open) { open = false; tapRun = 0 }
    val items = listOf(
        FanItem(NoteTool.FLASHCARD, Icons.Outlined.Style, "Create flashcard", 180.0),
        FanItem(NoteTool.SELECT_DOCUMENT, Icons.Outlined.SelectAll, "Select across blocks", 135.0),
        FanItem(NoteTool.HEADING_FOLD, Icons.Outlined.AccountTree, headingFoldLabel, 90.0),
    )
    val radiusPx = with(LocalDensity.current) { 78.dp.toPx() }
    Box(modifier) {
        if (open) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        open = false
                        tapRun = 0
                    }
                    .testTag("notes_add_fan_scrim"),
            )
        }
        if (progress > 0.01f) items.forEach { item ->
            val enabled = item.tool in tools.enabled
            val selected = item.tool in tools.active
            val rad = Math.toRadians(item.angleDeg)
            Surface(
                onClick = {
                    open = keepOpenAfter(item.tool)
                    tapRun = 0
                    onTool(item.tool)
                },
                enabled = open && enabled,
                shape = CircleShape,
                color = if (selected) Mint else Color.White,
                border = BorderStroke(1.dp, if (selected) Green else Border),
                shadowElevation = 3.dp,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 14.dp)
                    .offset {
                        IntOffset(
                            (cos(rad) * radiusPx * progress).roundToInt(),
                            (-sin(rad) * radiusPx * progress).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        val p = progress.coerceIn(0f, 1f)
                        alpha = p
                        scaleX = 0.6f + 0.4f * p
                        scaleY = 0.6f + 0.4f * p
                    }
                    .size(44.dp)
                    .semantics {
                        contentDescription = item.label
                        this.selected = selected
                        if (!enabled) disabled()
                    }
                    .testTag("notes_add_fan_${item.tool.name.lowercase()}"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(item.icon, contentDescription = null, tint = Green,
                        modifier = Modifier.size(22.dp).alpha(if (enabled) 1f else 0.38f))
                }
            }
        }
        Surface(
            onClick = {
                val at = now()
                tapRun = if (lastTapAt != Long.MIN_VALUE && at - lastTapAt in 0..ADD_FAN_TAP_RUN_MS) tapRun + 1 else 1
                lastTapAt = at
                if (tapRun >= 3) {
                    tapRun = 0
                    open = false
                    onAddBlock()
                } else open = !open
            },
            shape = CircleShape, color = Mint,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 12.dp)
                .size(48.dp)
                .semantics {
                    contentDescription = if (open) "Hide quick tools" else "Show quick tools"
                    customActions = listOf(CustomAccessibilityAction("Add block") {
                        open = false; tapRun = 0; onAddBlock(); true
                    })
                }
                .testTag("notes_add_block"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = Green,
                    modifier = Modifier.rotate(45f * progress))
            }
        }
    }
}
