package com.virlin.app.ui.map

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.roundToInt

const val MindMapCanvasTag = "mind_map_canvas"
const val MindMapOutlineTag = "mind_map_outline"
const val MindMapStructureToggleTag = "mind_map_structure_toggle"

/** Virlin's own presets. No other product's themes or assets are used. */
private val mapPalettes = listOf(
    listOf(0xFF24A46B, 0xFF618EE1, 0xFFD97867, 0xFF9A70CD, 0xFFE0A73A, 0xFF48A9AD),
    listOf(0xFF70A985, 0xFF698BBD, 0xFFB78FA9, 0xFFD9A57E, 0xFF8D9E76, 0xFF8B80B6),
    listOf(0xFF238B78, 0xFF6689A8, 0xFFD47882, 0xFFA885B8, 0xFFBB9957, 0xFF658F77),
    listOf(0xFF78A85D, 0xFF658FCE, 0xFFDF8774, 0xFF846FBD, 0xFFD9B45C, 0xFF52A7A5),
)
private val customColors = listOf(
    0xFF52A887, 0xFF95BC7C, 0xFF93C9C0, 0xFF438FAD,
    0xFF82A9DE, 0xFF7582D1, 0xFFAC85D3, 0xFFD28CC0,
    0xFFE58DA6, 0xFFD88474, 0xFFEDAD82, 0xFFE6C26C,
    0xFF9B9EA7, 0xFF739B91, 0xFFB59A82, 0xFF81A8B8,
)

private const val MIN_ZOOM = .18f
private const val MAX_ZOOM = 3f

internal data class Frame(val id: String, val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(px: Float, py: Float) = px in x..(x + w) && py in y..(y + h)
}

internal data class MapGeometry(
    val topics: List<MapTopic>, val frames: Map<String, Frame>,
    val parent: Map<String, String?>, val branch: Map<String, Int>,
    val depth: Map<String, Int>,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
)

/**
 * Iterative traversal and bottom-up placement: no arbitrary subtask depth limit, and the whole
 * layout is computed once per hierarchy/template change — never on a pan or zoom frame.
 */
internal fun arrange(topics: List<MapTopic>, layout: MapLayout): MapGeometry {
    require(topics.map { it.id }.distinct().size == topics.size) { "Map IDs must be unique" }
    val root = topics.singleOrNull { it.kind == MapKind.PROJECT && it.parentId == null }
        ?: error("Map requires one project root")
    val children = topics.groupBy { it.parentId }.mapValues { (_, v) ->
        v.sortedWith(compareBy<MapTopic> { it.order }.thenBy { it.id })
    }
    val ordered = mutableListOf<MapTopic>()
    val depth = mutableMapOf(root.id to 0)
    val branch = mutableMapOf(root.id to 0)
    val seen = mutableSetOf<String>()
    val stack = ArrayDeque<MapTopic>()
    stack.add(root)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        if (!seen.add(node.id)) continue // guards accidental cycles / duplicate edges
        ordered += node
        val kids = children[node.id].orEmpty()
        for (i in kids.indices.reversed()) {
            val child = kids[i]
            depth[child.id] = depth.getValue(node.id) + 1
            branch[child.id] = if (node.id == root.id) i else branch.getValue(node.id)
            stack.add(child)
        }
    }
    require(ordered.size == topics.size) { "Map contains disconnected nodes or cycles" }
    val y = mutableMapOf<String, Float>()
    var leaf = 0f
    var previousBranch = -1
    // Leaf rows are handed out in CANONICAL reading order, so sibling 0 sits at the top and the
    // group runs downwards. This pass used to walk `ordered.asReversed()`, which gave the LAST
    // sibling the smallest y and drew every group upside down: screen order was the mirror of
    // stored order. Nothing in the tree revealed it - a mirrored group looks like a perfectly
    // ordinary one - but it meant an insertion aimed at a gap on screen committed to the
    // mirrored slot, so a branch landed at the far end of the group from the line that promised
    // it. `ordered` is DFS pre-order with children already in canonical order, so walking it
    // forward is exactly the order a reader's eye takes.
    for (node in ordered) {
        if (children[node.id].orEmpty().none { it.id in seen }) {
            val b = branch.getValue(node.id)
            if (layout == MapLayout.BRANCH_LANES && previousBranch != -1 && previousBranch != b) {
                leaf += 80f
            }
            y[node.id] = leaf
            leaf += if (layout == MapLayout.COMPACT_OUTLINE) 70f else 112f
            previousBranch = b
        }
    }
    // Parents centre on their children, so they must be placed AFTER them: children-before-
    // parents is what `asReversed()` gives on a pre-order list.
    for (node in ordered.asReversed()) {
        val kids = children[node.id].orEmpty().filter { it.id in seen }
        if (kids.isNotEmpty()) {
            y[node.id] = (y.getValue(kids.first().id) + y.getValue(kids.last().id)) / 2f
        }
    }
    val maxY = max(0f, leaf - 112f)
    val frames = linkedMapOf<String, Frame>()
    val outlineIndex = ordered.withIndex().associate { it.value.id to it.index }
    ordered.forEach { node ->
        val d = depth.getValue(node.id)
        val b = branch.getValue(node.id)
        val sign = if (layout == MapLayout.BALANCED && b % 2 == 0 && d > 0) -1 else 1
        val w = when (node.kind) {
            MapKind.PROJECT -> 228f
            MapKind.WORKSTREAM -> 208f
            MapKind.TASK -> 190f
        }
        val h = if (node.kind == MapKind.PROJECT) 82f else 68f
        val row = y.getValue(node.id)
        val x: Float
        val cy: Float
        when (layout) {
            MapLayout.RIGHT_TREE, MapLayout.BRANCH_LANES -> {
                x = d * 268f; cy = row
            }
            MapLayout.BALANCED -> {
                x = if (d == 0) 0f else sign * d * 268f - if (sign < 0) w else 0f
                cy = row
            }
            MapLayout.TOP_DOWN -> {
                x = row * 1.65f; cy = d * 152f
            }
            MapLayout.RADIAL -> {
                val angle = if (maxY == 0f) 0f else (row / maxY) * 2f * PI.toFloat()
                x = cos(angle) * d * 275f - w / 2f
                cy = sin(angle) * d * 215f
            }
            MapLayout.COMPACT_OUTLINE -> {
                x = d * 42f; cy = outlineIndex.getValue(node.id) * 76f
            }
        }
        frames[node.id] = Frame(node.id, x, cy - h / 2f, w, h)
    }
    val values = frames.values
    return MapGeometry(
        ordered, frames, ordered.associate { it.id to it.parentId }, branch, depth,
        values.minOf { it.x }, values.minOf { it.y },
        values.maxOf { it.x + it.w }, values.maxOf { it.y + it.h }
    )
}


/**
 * The node under a touch, in world coordinates.
 *
 * A direct hit always wins. Otherwise the NEAREST box within [slopWorld] is taken, because at
 * Fit zoom a task box measures about 51 x 18 dp on a Pixel 8 - its height is barely a third of
 * Android's 48 dp minimum touch target, while a fingertip's contact patch is around 45 dp. An
 * exact-containment test therefore makes small nodes practically untappable with a finger even
 * though they look perfectly hittable, which an injected pixel-perfect tap never reveals.
 *
 * Nearest-box resolution is what keeps neighbours distinct: siblings sit only ~12 dp apart at
 * this zoom, so an inflated rectangle would overlap them, but the closest box always wins and a
 * near miss can never select something further away. Nothing about the drawn size changes.
 */
internal fun MapGeometry.frameNear(world: Offset, slopWorld: Float): Frame? {
    // Topmost exact hit first: an unambiguous touch is never second-guessed.
    frames.values.toList().asReversed().firstOrNull { it.contains(world.x, world.y) }?.let { return it }
    var best: Frame? = null
    var bestDistance = Float.MAX_VALUE
    frames.values.forEach { f ->
        val dx = max(0f, max(f.x - world.x, world.x - (f.x + f.w)))
        val dy = max(0f, max(f.y - world.y, world.y - (f.y + f.h)))
        val distance = kotlin.math.hypot(dx, dy)
        if (distance <= slopWorld && distance < bestDistance) {
            bestDistance = distance
            best = f
        }
    }
    return best
}

/** Touch slop expressed in WORLD units: a fixed number of dp however far the map is zoomed out. */
internal fun touchSlopWorld(zoom: Float) = 12f / zoom

/**
 * The map surface. The canvas has NO finite scroll extent: pan is an unbounded world offset in
 * both axes and is never clamped to the content's bounds, which exist only so Fit can frame
 * them. Everything is drawn into one Canvas with offscreen nodes and connectors culled, so a
 * map with hundreds of tasks costs no composables.
 *
 * The host scaffold keeps the app's bottom navigation and Orb; nothing here replaces them.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun VirlinProjectMindMap(
    projectTitle: String,
    topics: List<MapTopic>,
    appearance: MapAppearance,
    onAppearanceChange: (MapAppearance) -> Unit,
    onNodeOpen: (MapTopic) -> Unit,
    onBack: () -> Unit,
    onExportXmind: () -> Unit,
    onExportVirlin: () -> Unit,
    onOpenVirlinMap: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Edit structure mode. While it is on, a tap SELECTS a node instead of opening it, so a
     * branch can be chosen and then moved or copied. Opening, colouring, pinch, Fit and pan are
     * unchanged in both modes.
     */
    structureMode: Boolean = false,
    onToggleStructureMode: (() -> Unit)? = null,
    /**
     * THE selected node, owned by the host. The map used to keep its own copy, which could
     * disagree with the host's - a drag handle could float over an empty action bar. There is
     * now one id: the map reports every change through [onStructureSelect] and renders this.
     */
    selectedId: String? = null,
    onStructureSelect: (MapTopic?) -> Unit = {},
    /** Rendered under the toolbar while a branch is selected: the host's action bar. */
    structureBar: (@Composable () -> Unit)? = null,
    /**
     * May this branch legally land on that node? Answered with the same rules the command
     * validates with, so a target lights up only when the drop would really be accepted.
     */
    isValidTarget: (sourceId: String, targetId: String) -> Boolean = { _, _ -> false },
    /** How many records travel with this branch - shown on the lifted preview. */
    branchSizeOf: (String) -> Int = { 1 },
    /**
     * May this branch be inserted NEXT TO that node - that is, does its parent accept the
     * branch, and does the node sit outside the branch being moved? Separate from
     * [isValidTarget] because "inside X" and "beside X" are different questions.
     */
    canInsertBeside: (sourceId: String, siblingId: String) -> Boolean = { _, _ -> false },
    /**
     * A release on a planned destination. The plan says INTO a node or BEFORE/AFTER a sibling;
     * the host turns the screen slot into a stable anchor and runs the placement command.
     */
    onBranchDrop: (sourceId: String, plan: MapDropPlan) -> Unit = { _, _ -> },
    /** A release over a node that would refuse it, so the host can explain why. */
    onInvalidDrop: (sourceId: String, targetId: String) -> Unit = { _, _ -> },
    /**
     * A choice made in the Add palette. The palette itself writes nothing; the host runs the one
     * real flow that choice names.
     */
    onAddChoice: (MapTopic, MapAddKind) -> Unit = { _, _ -> },
    /** Open this task's Page, or say plainly that there is not one yet. */
    onOpenTaskPage: (MapTopic) -> Unit = {},
    /** Open the host's destination picker for this task. Nothing is written until it confirms. */
    onMoveTask: (MapTopic) -> Unit = {},
) {
    var current by remember(appearance) { mutableStateOf(appearance) }
    val geometry = remember(topics, current.layout) { arrange(topics, current.layout) }
    val byId = remember(geometry) { geometry.topics.associateBy { it.id } }
    val density = LocalDensity.current.density
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // Pan/zoom survive a rotation or a process restart; a colour change must not disturb them.
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var offsetY by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var showLayouts by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var selectingColor by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var pickedColor by remember { mutableStateOf<Long?>(null) }
    // Branch drag. `dragId` is the lifted branch's root; `dragPointer` is the finger, in screen
    // pixels; `hoverId` is the node under it.
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    // Where the branch would land right now. INTO keeps the old node highlight; BEFORE/AFTER
    // draws an insertion line instead. Null means "nowhere" - a release here is a cancel.
    var dropPlan by remember { mutableStateOf<MapDropPlan?>(null) }
    // The boxes in screen pixels, captured when the drag starts. The canvas transform is frozen
    // for the whole gesture, so recomputing this per move event would only cost allocations.
    var dropNodes by remember { mutableStateOf<List<MapDropNode>>(emptyList()) }
    val hoverId = dropPlan?.takeIf { it.kind == MapDropKind.INTO }?.targetId
    // Feedback fades; the finger does not. The preview's POSITION is never animated - easing it
    // would put the branch where the finger was a moment ago and make precise dropping guesswork.
    val indicatorAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dropPlan?.indicatorStart != null) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 130),
        label = "drop-indicator",
    )
    val liftScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dragId != null) 1f else .88f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 140),
        label = "drop-lift",
    )
    /** The drag handle's own position in this Box, kept as state so gestures read it live. */
    var handleOrigin by remember { mutableStateOf(Offset.Zero) }
    // The Add palette remembers only a stable id. Holding the MapTopic would keep a snapshot
    // that a move, an undo or a delete could leave pointing at something that no longer exists.
    var addPaletteTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val paletteTask = addPaletteTaskId?.let(byId::get)?.takeIf { it.kind == MapKind.TASK }
    LaunchedEffect(addPaletteTaskId, paletteTask) {
        // The task it was opened for has gone (moved out of this project, or removed).
        if (addPaletteTaskId != null && paletteTask == null) addPaletteTaskId = null
    }
    val offset = Offset(if (offsetX.isNaN()) 0f else offsetX, if (offsetY.isNaN()) 0f else offsetY)

    fun fit() {
        if (viewport == IntSize.Zero) return
        val margin = 72f * density
        val width = (geometry.right - geometry.left) * density
        val height = (geometry.bottom - geometry.top) * density
        zoom = min(
            1f,
            min((viewport.width - margin) / max(1f, width), (viewport.height - margin) / max(1f, height))
        ).coerceIn(MIN_ZOOM, MAX_ZOOM)
        offsetX = viewport.width / 2f - (geometry.left + geometry.right) / 2f * density * zoom
        offsetY = viewport.height / 2f - (geometry.top + geometry.bottom) / 2f * density * zoom
    }
    // Fit ONCE, when the map first has a viewport to fit into. The old condition was true
    // whenever the viewport was non-zero, so any change to the topic count - every move, every
    // task edited elsewhere - recentred the canvas underneath the user. Recentring is now
    // either the Fit button or an explicit template change.
    LaunchedEffect(viewport) {
        if (
            viewport != IntSize.Zero &&
            (offsetX.isNaN() || offsetY.isNaN())
        ) {
            fit()
        }
    }

    Column(modifier.fillMaxSize().background(Color(0xFFFBFAF8))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to project")
            }
            Text(
                projectTitle, Modifier.weight(1f), fontSize = 18.sp,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { showOutline = true }) {
                Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Map outline")
            }
            IconButton(onClick = { selectingColor = true; showColors = true }) {
                Icon(Icons.Default.Palette, "Map colours")
            }
            IconButton(onClick = { showLayouts = true }) {
                Icon(Icons.Default.Dashboard, "Map layout")
            }
            // A labelled control, not a second tree glyph beside the layout button: it says what
            // it does, and while it is on it is a filled green pill rather than a tint.
            if (onToggleStructureMode != null) Surface(
                onClick = {
                    onStructureSelect(null)
                    onToggleStructureMode()
                },
                modifier = Modifier.testTag(MindMapStructureToggleTag)
                    .padding(horizontal = 2.dp)
                    .semantics {
                        contentDescription =
                            if (structureMode) "Organize mode on. Tap to finish organizing."
                            else "Organize: move branches between workstreams and tasks"
                    },
                shape = RoundedCornerShape(50),
                color = if (structureMode) Color(0xFF16A34A) else Color(0xFFEFF3EF),
            ) {
                Text(
                    if (structureMode) "Done" else "Organize",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = if (structureMode) Color.White else Color(0xFF17202C),
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Export .xmind") }, onClick = {
                        menu = false; onExportXmind()
                    })
                    DropdownMenuItem(text = { Text("Export Virlin map") }, onClick = {
                        menu = false; onExportVirlin()
                    })
                    DropdownMenuItem(text = { Text("Preview a Virlin map file") }, onClick = {
                        menu = false; onOpenVirlinMap()
                    })
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize().testTag(MindMapCanvasTag)
                    .onSizeChanged { viewport = it }
                    .semantics {
                        contentDescription =
                            "Project mind map. Drag to pan, pinch to zoom. " +
                                "Use the outline button for a readable list of every node."
                    }
                    .pointerInput(geometry) {
                        detectTransformGestures { centroid, pan, gestureZoom, _ ->
                            // While a branch is lifted the canvas holds still, so a drag can
                            // never be mistaken for a pan.
                            if (dragId != null) return@detectTransformGestures
                            // These read the CURRENT pan/zoom. A value captured at composition
                            // would go stale after the first gesture and the hit test with it.
                            val here = Offset(offsetX, offsetY)
                            val next = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            // Keep the world point under the fingers fixed while scaling.
                            val scaled = centroid - (centroid - here) * (next / zoom) + pan
                            offsetX = scaled.x
                            offsetY = scaled.y
                            zoom = next
                        }
                    }
                    // A tap SELECTS in both modes and never navigates: opening is an explicit
                    // action, so a tap can neither leave the map mid-edit nor be mistaken for
                    // one. `onNodeOpen` is still the Open action's and the outline's path.
                    .pointerInput(geometry, selectingColor) {
                        detectTapGestures { tap ->
                            val world =
                                (tap - Offset(offsetX, offsetY)) / (density * zoom)

                            val frame = geometry.frameNear(
                                world = world,
                                slopWorld = touchSlopWorld(zoom)
                            )

                            if (frame == null) {
                                onStructureSelect(null)
                                return@detectTapGestures
                            }

                            val topic = byId[frame.id] ?: return@detectTapGestures
                            onStructureSelect(topic)

                            if (selectingColor) {
                                showColors = true
                            }
                        }
                    }
            ) {
                drawIntoCanvas { composeCanvas ->
                    val native = composeCanvas.nativeCanvas
                    native.save()
                    native.translate(offset.x, offset.y)
                    native.scale(density * zoom, density * zoom)
                    val visible = RectF(
                        -offset.x / (density * zoom),
                        -offset.y / (density * zoom),
                        (size.width - offset.x) / (density * zoom),
                        (size.height - offset.y) / (density * zoom)
                    )
                    paintMap(
                        native, geometry, current, visible, selectedId,
                        dragId = dragId, hoverId = hoverId,
                        hoverValid = dragId?.let { source ->
                            hoverId?.let { target -> isValidTarget(source, target) }
                        } ?: false
                    )
                    native.restore()
                }
                // The insertion line: where the branch would sit among its new siblings. It is
                // drawn above the map and below the lifted preview, so the preview never hides
                // the very thing it is aiming at.
                dropPlan?.let { plan ->
                    val lineStart = plan.indicatorStart
                    val lineEnd = plan.indicatorEnd
                    if (lineStart != null && lineEnd != null && indicatorAlpha > 0f) {
                        val green = Color(0xFF13A86B).copy(alpha = indicatorAlpha)
                        drawLine(
                            color = green, start = lineStart, end = lineEnd,
                            strokeWidth = 3.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        )
                        // An endpoint marker, so the line reads as a slot rather than a divider.
                        drawCircle(color = green, radius = 5.dp.toPx(), center = lineStart)
                    }
                }
                // The lifted preview and the proposed connector are drawn in SCREEN space on
                // top of everything, so they stay legible at any zoom and the preview keeps
                // following the finger while the canvas pans underneath.
                val lifted = dragId
                if (lifted != null) {
                    // A plan exists means this release would be accepted somewhere.
                    val valid = dropPlan != null
                    // Only an INTO proposes a new parent, so only INTO draws the connector to
                    // one. A BEFORE/AFTER already says everything with its line.
                    hoverId?.let { target ->
                        geometry.frames[target]?.let { frame ->
                            // The proposed attachment: from the destination's right edge, where
                            // a child's connector leaves it, to the branch in hand.
                            val from = Offset(
                                (frame.x + frame.w) * density * zoom + offsetX,
                                (frame.y + frame.h / 2f) * density * zoom + offsetY
                            )
                            drawLine(
                                if (valid) Color(0xFF16A34A) else Color(0xFFC33938),
                                from, dragPointer, strokeWidth = 3.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect
                                    .dashPathEffect(floatArrayOf(14f, 10f))
                            )
                        }
                    }
                    val w = 196.dp.toPx() * liftScale
                    val h = 58.dp.toPx() * liftScale
                    // Position follows the finger exactly; only the SIZE is animated.
                    val topLeft = Offset(dragPointer.x - w / 2f, dragPointer.y - h - 14.dp.toPx())
                    val size2 = androidx.compose.ui.geometry.Size(w, h)
                    val radius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
                    drawRoundRect(Color.Black.copy(alpha = .16f), topLeft + Offset(0f, 5f), size2, radius)
                    drawRoundRect(Color.White, topLeft, size2, radius)
                    drawRoundRect(
                        if (valid) Color(0xFF16A34A) else Color(0xFF9AA4AF),
                        topLeft, size2, radius, style = Stroke(width = 2.dp.toPx())
                    )
                    drawIntoCanvas { c ->
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(25, 34, 41)
                            textSize = 13.dp.toPx()
                            typeface = android.graphics.Typeface.create(
                                "sans-serif-medium", android.graphics.Typeface.BOLD
                            )
                        }
                        val shown = TextUtils.ellipsize(
                            byId[lifted]?.title.orEmpty(), android.text.TextPaint(paint),
                            w - 24.dp.toPx(), TextUtils.TruncateAt.END
                        ).toString()
                        c.nativeCanvas.drawText(shown, topLeft.x + 12.dp.toPx(), topLeft.y + 24.dp.toPx(), paint)
                        paint.textSize = 10.dp.toPx()
                        paint.typeface = android.graphics.Typeface.create("sans-serif", 0)
                        // The destination in words. "Before" and "After" are what distinguishes
                        // a precise insertion from an append, so the preview has to say which.
                        val plan = dropPlan
                        val destination = plan?.let {
                            val name = byId[it.targetId]?.title.orEmpty()
                            when (it.kind) {
                                MapDropKind.INTO -> "Inside " + name
                                MapDropKind.BEFORE -> "Before " + name
                                MapDropKind.AFTER -> "After " + name
                            }
                        }
                        if (destination != null) {
                            paint.color = android.graphics.Color.rgb(19, 168, 107)
                            paint.typeface = android.graphics.Typeface.create(
                                "sans-serif-medium", android.graphics.Typeface.BOLD
                            )
                            val shownDestination = TextUtils.ellipsize(
                                destination, android.text.TextPaint(paint),
                                w - 24.dp.toPx(), TextUtils.TruncateAt.END
                            ).toString()
                            c.nativeCanvas.drawText(
                                shownDestination, topLeft.x + 12.dp.toPx(), topLeft.y + 44.dp.toPx(), paint
                            )
                        } else {
                            paint.color = android.graphics.Color.rgb(99, 114, 131)
                            // Nowhere to land yet: say what is in hand instead.
                            val count = branchSizeOf(lifted)
                            val moving = if (count <= 1) "this task alone"
                            else "this task and " + (count - 1) + " below it"
                            c.nativeCanvas.drawText(moving, topLeft.x + 12.dp.toPx(), topLeft.y + 44.dp.toPx(), paint)
                        }
                    }
                }
            }
            // A selected task gets a real 48dp drag handle. This owns its pointer sequence;
            // the canvas's pan/zoom/tap recognizers cannot steal a drag from this child.
            // A tap selects the node first. The handle remains usable even at Fit zoom.
            val selectedTask = selectedId?.let(byId::get)
            val selectedFrame = selectedId?.let(geometry.frames::get)
            // Organize does NOT gate the handle: a selected task is draggable wherever it was
            // selected, so the affordance is where the selection is rather than behind a mode.
            if (selectedTask?.kind == MapKind.TASK && selectedFrame != null &&
                viewport != IntSize.Zero) {
                val handlePx = 48f * density
                // The handle must stay OUT of the system back-gesture inset on the right. It
                // sits on the node's right edge while targets are usually to the left, so the
                // natural drag is leftward - which, started inside that inset, Android claims
                // as Back and the drop never reaches the app. Measured on device: a handle at
                // x = 1016 of 1080 lost every mostly-horizontal leftward drag.
                val gestureReserve = 56f * density
                val handleLeft = ((selectedFrame.x + selectedFrame.w) * density * zoom + offsetX +
                    4f * density)
                    .coerceIn(0f, (viewport.width - handlePx - gestureReserve).coerceAtLeast(0f))
                val handleTop = ((selectedFrame.y + selectedFrame.h / 2f) * density * zoom +
                    offsetY - handlePx / 2f).coerceIn(0f, (viewport.height - handlePx).coerceAtLeast(0f))
                Box(
                    Modifier.offset { IntOffset(handleLeft.roundToInt(), handleTop.roundToInt()) }
                        .size(48.dp)
                        .testTag("mind_map_branch_drag_handle")
                        .semantics { contentDescription = "Drag ${selectedTask.title} to another task or workstream" }
                        // Where the handle actually sits, recorded from layout. The gesture reads
                        // this STATE rather than closing over the computed position, so the
                        // detector never has to restart when the handle moves - a restart
                        // mid-drag would drop the gesture.
                        .onGloballyPositioned { handleOrigin = it.positionInParent() }
                        .background(Color(0xFF16A34A), RoundedCornerShape(24.dp))
                        // Keys that can change while a finger is down would tear down an
                        // active drag. Only the source and the geometry it hit-tests against.
                        .pointerInput(selectedTask.id, geometry) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    dragId = selectedTask.id
                                    dropPlan = null
                                    // Read live: a value captured when the detector was created
                                    // would be stale the moment the handle moved.
                                    dragPointer = handleOrigin + start
                                    // The boxes in screen pixels. The canvas transform is frozen
                                    // for the whole gesture, so this is captured once here
                                    // rather than rebuilt on every move event.
                                    dropNodes = geometry.dropNodes(byId, density, zoom, offsetX, offsetY)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (dragId == selectedTask.id) {
                                        dragPointer += amount
                                        dropPlan = planMapDrop(
                                            pointer = dragPointer, sourceId = selectedTask.id,
                                            nodes = dropNodes, canPlaceInto = isValidTarget,
                                            canPlaceBeside = canInsertBeside, density = density,
                                        )
                                    }
                                },
                                onDragEnd = {
                                    val source = dragId
                                    // Resolved from the FINAL pointer, not from the last move
                                    // event: the finger can travel between the two.
                                    val plan = source?.let {
                                        planMapDrop(
                                            pointer = dragPointer, sourceId = it,
                                            nodes = dropNodes, canPlaceInto = isValidTarget,
                                            canPlaceBeside = canInsertBeside, density = density,
                                        )
                                    }
                                    dragId = null
                                    dropPlan = null
                                    dropNodes = emptyList()
                                    if (source != null) {
                                        if (plan != null) onBranchDrop(source, plan)
                                        else {
                                            // Nothing was planned. If a box was nonetheless under
                                            // the finger, the drop was refused rather than aimed
                                            // at empty canvas - say which, and why.
                                            val world = (dragPointer - Offset(offsetX, offsetY)) /
                                                (density * zoom)
                                            geometry.frameNear(world, touchSlopWorld(zoom))
                                                ?.takeIf { it.id != source }
                                                ?.let { onInvalidDrop(source, it.id) }
                                        }
                                    }
                                },
                                onDragCancel = {
                                    dragId = null
                                    dropPlan = null
                                    dropNodes = emptyList()
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) { Text("↗", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold) }
            }
            // The bar FLOATS over the canvas instead of sitting above it in the column.
            // As a sibling in the column its height changed when a node was selected, which
            // moved the canvas out from under a finger that was already dragging - the target
            // then slid away and the drop landed on nothing.
            // The bar appears whenever something is selected, not only while organizing, so a
            // drag handle can never be on screen without its action bar.
            if (structureMode || selectedId != null) {
                Box(Modifier.align(Alignment.TopCenter)) { structureBar?.invoke() }
            }
            // The selected task's actions, sitting directly above the Fit/zoom controls. Like the
            // structure bar it is an OVERLAY, not a sibling in a column, so selecting a task
            // cannot resize the canvas or move its hit-test origin.
            val barTask = selectedId?.let(byId::get)?.takeIf { it.kind == MapKind.TASK }
            if (barTask != null && dragId == null && paletteTask == null && !showOutline &&
                !showLayouts && !showColors && !selectingColor
            ) {
                MapSelectedTaskBar(
                    task = barTask,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 68.dp),
                    onOpenTask = onNodeOpen,
                    onMove = onMoveTask,
                    onPage = onOpenTaskPage,
                    onAdd = { task -> addPaletteTaskId = task.id },
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { fit() }) { Text("Fit") }
                OutlinedButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(MIN_ZOOM) }) {
                    Icon(Icons.Default.Remove, "Zoom out", Modifier.size(18.dp))
                }
                Text("${(zoom * 100).toInt()}%", fontSize = 12.sp)
                OutlinedButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(MAX_ZOOM) }) {
                    Icon(Icons.Default.Add, "Zoom in", Modifier.size(18.dp))
                }
            }
            if (selectingColor && !showColors) Text(
                "Tap a node to choose its colour",
                Modifier.align(Alignment.TopCenter).padding(12.dp)
                    .background(Color.White, RoundedCornerShape(12.dp)).padding(10.dp),
                fontSize = 12.sp
            )
            // LAST in the Box, so it sits above the canvas, the handle, the bars and the zoom
            // controls. It is an overlay like everything else here: opening it changes no
            // dimension and no origin, so the map cannot shift under a finger.
            if (paletteTask != null) {
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = .10f))
                        // A tap outside dismisses and writes nothing.
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { addPaletteTaskId = null }
                )
                MapAddPalette(
                    selectedTaskTitle = paletteTask.title,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onDismiss = { addPaletteTaskId = null },
                    onChoose = { choice ->
                        // Read the source from the LIVE lookup, not from a captured snapshot.
                        val source = paletteTask
                        addPaletteTaskId = null
                        onAddChoice(source, choice)
                    },
                )
                // Android Back closes the palette before it leaves the map, and writes nothing.
                androidx.activity.compose.BackHandler { addPaletteTaskId = null }
            }
        }
    }

    // A drawn canvas cannot be read by a screen reader, so the same tree is also a real list.
    if (showOutline) ModalBottomSheet(onDismissRequest = { showOutline = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text("Map outline", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Every node, in order. Select one to open it.", fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp).testTag(MindMapOutlineTag)) {
            items(geometry.topics, key = { it.id }) { topic ->
                val depth = geometry.depth[topic.id] ?: 0
                val status = statusLabel(topic.status)
                Column(
                    Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { showOutline = false; onNodeOpen(topic) }
                        .semantics {
                            contentDescription = listOf(
                                topic.title,
                                topic.kind.name.lowercase(),
                                "level ${depth + 1}",
                                status,
                                topic.detail
                            ).filter { it.isNotBlank() }.joinToString(", ")
                        }
                        .padding(start = (18 + depth * 14).dp, end = 18.dp, top = 10.dp, bottom = 10.dp)
                ) {
                    Text(topic.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val sub = listOf(topic.detail, status).filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = Color(0xFF647087))
                }
            }
        }
    }

    if (showLayouts) ModalBottomSheet(onDismissRequest = { showLayouts = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text("Choose a layout", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "All six show the same project, workstreams, tasks and subtasks. " +
                    "Switching never moves or changes a task.", fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            MapLayout.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { candidate ->
                        FilterChip(
                            selected = current.layout == candidate,
                            onClick = { current = current.copy(layout = candidate) },
                            label = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    TemplateMiniature(candidate)
                                    Text(candidate.label, fontSize = 11.sp)
                                }
                            }, modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Button(
                // `current.layout` changed when the chip was picked, so `geometry` has already
                // been rebuilt for the new template: fitting here frames the NEW layout.
                onClick = { onAppearanceChange(current); fit(); showLayouts = false },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply layout") }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showColors) ModalBottomSheet(onDismissRequest = { showColors = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text("Map colours", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Choose a palette, then colour one node or a whole branch. " +
                    "A node's own colour wins, then its nearest branch colour, then the palette.",
                fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mapPalettes.indices.forEach { index ->
                    FilterChip(
                        selected = current.paletteIndex == index,
                        onClick = { current = current.copy(paletteIndex = index) },
                        label = { Text("Palette ${index + 1}") })
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Selected: ${selectedId?.let(byId::get)?.title ?: "tap a node on the map"}")
            Spacer(Modifier.height(6.dp))
            customColors.chunked(8).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { argb ->
                        Box(
                            Modifier.padding(4.dp).size(30.dp)
                                .background(Color(argb.toInt()), RoundedCornerShape(50))
                                .border(
                                    if (pickedColor == argb) 3.dp else 0.dp,
                                    Color(0xFF17202C), RoundedCornerShape(50)
                                )
                                .semantics { contentDescription = "Colour swatch" }
                                .clickable(enabled = selectedId != null) { pickedColor = argb })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = selectedId != null && pickedColor != null,
                    onClick = {
                        current = current.copy(
                            nodeColors = current.nodeColors + (selectedId!! to pickedColor!!)
                        )
                    }) { Text("This node") }
                OutlinedButton(
                    enabled = selectedId != null && pickedColor != null,
                    onClick = {
                        current = current.copy(
                            branchColors = current.branchColors + (selectedId!! to pickedColor!!)
                        )
                    }) { Text("Branch") }
                OutlinedButton(
                    enabled = selectedId != null,
                    onClick = {
                        current = current.copy(
                            nodeColors = current.nodeColors - selectedId!!,
                            branchColors = current.branchColors - selectedId!!
                        )
                    }) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onAppearanceChange(current); showColors = false; selectingColor = false },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save colours") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun statusLabel(status: MapStatus) = when (status) {
    MapStatus.CURRENT -> "Current"
    MapStatus.DONE -> "✓ Done"
    MapStatus.BLOCKED -> "! Blocked"
    MapStatus.TODO -> "○ To do"
    MapStatus.NONE -> ""
}

/** Tiny structural previews; all six use exactly the same underlying task tree. */
@Composable
private fun TemplateMiniature(layout: MapLayout) {
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        val green = Color(0xFF50A968)
        val blue = Color(0xFF81A4D4)
        val root: Offset
        val children: List<Offset>
        when (layout) {
            MapLayout.RIGHT_TREE -> {
                root = Offset(size.width * .13f, size.height * .5f)
                children = listOf(.50f to .18f, .50f to .50f, .50f to .82f)
                    .map { Offset(size.width * it.first, size.height * it.second) }
            }
            MapLayout.BALANCED -> {
                root = Offset(size.width * .50f, size.height * .5f)
                children = listOf(.14f to .22f, .14f to .78f, .86f to .22f, .86f to .78f)
                    .map { Offset(size.width * it.first, size.height * it.second) }
            }
            MapLayout.RADIAL -> {
                root = Offset(size.width * .50f, size.height * .5f)
                children = (0..5).map { i ->
                    val a = i * PI / 3
                    Offset(
                        root.x + cos(a).toFloat() * size.width * .34f,
                        root.y + sin(a).toFloat() * size.height * .33f
                    )
                }
            }
            MapLayout.TOP_DOWN -> {
                root = Offset(size.width * .50f, size.height * .13f)
                children = listOf(.18f to .53f, .5f to .53f, .82f to .53f)
                    .map { Offset(size.width * it.first, size.height * it.second) }
            }
            MapLayout.BRANCH_LANES -> {
                root = Offset(size.width * .12f, size.height * .50f)
                children = listOf(.42f to .20f, .50f to .50f, .42f to .80f)
                    .map { Offset(size.width * it.first, size.height * it.second) }
            }
            MapLayout.COMPACT_OUTLINE -> {
                root = Offset(size.width * .17f, size.height * .13f)
                children = listOf(.30f to .34f, .39f to .55f, .48f to .76f)
                    .map { Offset(size.width * it.first, size.height * it.second) }
            }
        }
        children.forEachIndexed { index, child ->
            drawLine(if (index % 2 == 0) green else blue, root, child, strokeWidth = 2.dp.toPx())
            drawRoundRect(
                if (index % 2 == 0) green else blue,
                topLeft = child - Offset(8.dp.toPx(), 4.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(16.dp.toPx(), 8.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
            )
            if (layout != MapLayout.COMPACT_OUTLINE) {
                val end = child + Offset(if (child.x >= root.x) 13.dp.toPx() else -13.dp.toPx(), 0f)
                drawLine(blue.copy(alpha = .7f), child, end, strokeWidth = 1.dp.toPx())
            }
        }
        drawCircle(Color(0xFF1E8E58), 7.dp.toPx(), root, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun paintMap(
    canvas: android.graphics.Canvas, g: MapGeometry, appearance: MapAppearance,
    viewport: RectF, selectedId: String?,
    dragId: String? = null, hoverId: String? = null, hoverValid: Boolean = false,
) {
    val palette = mapPalettes[appearance.paletteIndex.mod(mapPalettes.size)]
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val branchTint = mutableMapOf<String, Long>()
    val tint = mutableMapOf<String, Int>()
    for (topic in g.topics) {
        val inherited = g.parent[topic.id]?.let(branchTint::get)
        val branchColor = appearance.branchColors[topic.id] ?: inherited
            ?: palette[g.branch.getValue(topic.id) % palette.size]
        branchTint[topic.id] = branchColor
        tint[topic.id] = (appearance.nodeColors[topic.id] ?: branchColor).toInt()
    }
    fun accent(topic: MapTopic): Int = tint.getValue(topic.id)

    for (topic in g.topics) {
        val parent = g.parent[topic.id]?.let(g.frames::get) ?: continue
        val child = g.frames.getValue(topic.id)
        val lineBounds = RectF(
            min(parent.x, child.x) - 20f, min(parent.y, child.y) - 20f,
            max(parent.x + parent.w, child.x + child.w) + 20f,
            max(parent.y + parent.h, child.y + child.h) + 20f
        )
        if (!RectF.intersects(viewport, lineBounds)) continue
        val fromX = if (child.x >= parent.x) parent.x + parent.w else parent.x
        val toX = if (child.x >= parent.x) child.x else child.x + child.w
        val fromY = parent.y + parent.h / 2f
        val toY = child.y + child.h / 2f
        val curve = (toX - fromX) * .48f
        val path = Path().apply {
            moveTo(fromX, fromY)
            cubicTo(fromX + curve, fromY, toX - curve, toY, toX, toY)
        }
        paint.color = accent(topic); paint.alpha = 210
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL; paint.alpha = 255
    }

    for (topic in g.topics) {
        val f = g.frames.getValue(topic.id)
        val r = RectF(f.x, f.y, f.x + f.w, f.y + f.h)
        if (!RectF.intersects(viewport, r)) continue
        val color = accent(topic)
        val selected = selectedId == topic.id
        // While a branch is lifted, the nodes that would refuse it recede.
        val dimmed = dragId != null && topic.id != dragId && topic.id != hoverId
        paint.color = android.graphics.Color.WHITE
        canvas.drawRoundRect(r, 17f, 17f, paint)
        paint.style = Paint.Style.STROKE
        // 1.2f vs 2.4f was a sub-pixel difference at the 18-26% zoom Fit lands on, which is
        // why a tap looked like it did nothing. A selection now reads as one.
        paint.strokeWidth = if (selected) 5f else 1.2f
        paint.color = color
        paint.alpha = if (selected) 255 else if (dimmed) 55 else 130
        canvas.drawRoundRect(r, 17f, 17f, paint)
        if (selected) {
            val halo = RectF(r.left - 7f, r.top - 7f, r.right + 7f, r.bottom + 7f)
            paint.strokeWidth = 3f
            paint.alpha = 90
            canvas.drawRoundRect(halo, 23f, 23f, paint)
        }
        // The node under the finger: green when it would accept the branch, red when it would
        // refuse it, so an invalid target is visibly unavailable before release.
        if (topic.id == hoverId && dragId != null) {
            paint.strokeWidth = 6f
            paint.alpha = 255
            paint.color = if (hoverValid) android.graphics.Color.rgb(22, 163, 74)
            else android.graphics.Color.rgb(195, 57, 56)
            canvas.drawRoundRect(
                RectF(r.left - 5f, r.top - 5f, r.right + 5f, r.bottom + 5f), 22f, 22f, paint
            )
        }
        paint.style = Paint.Style.FILL; paint.alpha = 255
        paint.color = color
        canvas.drawCircle(f.x + 19f, f.y + 23f, 7f, paint)
        paint.color = android.graphics.Color.rgb(25, 34, 41)
        paint.typeface =
            android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        paint.textSize = 15f
        val title = TextUtils.ellipsize(
            topic.title, android.text.TextPaint(paint), f.w - 48f, TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(title, f.x + 33f, f.y + 29f, paint)
        paint.typeface = android.graphics.Typeface.create("sans-serif", 0)
        paint.textSize = 11f
        // Colour is never the only signal: every status also carries a word and a glyph.
        paint.color = when (topic.status) {
            MapStatus.BLOCKED -> android.graphics.Color.rgb(195, 57, 56)
            MapStatus.DONE -> android.graphics.Color.rgb(34, 126, 63)
            else -> android.graphics.Color.rgb(99, 114, 131)
        }
        val detail = listOf(topic.detail, statusLabel(topic.status))
            .filter { it.isNotBlank() }.joinToString(" · ")
        val sub = TextUtils.ellipsize(
            detail, android.text.TextPaint(paint), f.w - 28f, TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(sub, f.x + 14f, f.y + f.h - 14f, paint)
    }
}
