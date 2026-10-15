package com.virlin.app.ui.map

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.zIndex
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.virlin.app.ui.screens.findActivity

const val MindMapCanvasTag = "mind_map_canvas"
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

private const val MIN_ZOOM = MAP_MIN_ZOOM
private const val MAX_ZOOM = MAP_MAX_ZOOM

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
/**
 * [expanded] is the one node currently showing its preview, with the extra height that preview
 * needs. Only the selected node can be expanded, so there is at most one. Passing it here rather
 * than drawing a taller box later is what makes connectors genuinely reflow: the node's frame grows
 * and everything below it is pushed down, so an expanded node never overlaps its neighbours.
 */
private fun estimatedTitleWidth(title: String) = title.sumOf { ch ->
        when { ch.isWhitespace() -> 4.0; ch in "ilI.,'" -> 4.5; ch in "MW@" -> 13.0; else -> 8.5 }
    }.toFloat()
private fun minimumCardWidth(title: String) =
    (estimatedTitleWidth(title) / 3f + 66f).coerceIn(110f, 560f)

private fun compactCardSize(title: String, widthOverride: Float? = null): Pair<Float, Float> {
    val titleWidth = estimatedTitleWidth(title)
    val naturalWidth = (titleWidth + 66f).coerceIn(110f, 240f)
    val width = widthOverride?.takeIf { it.isFinite() }
        ?.coerceIn(minimumCardWidth(title), 560f) ?: naturalWidth
    val lines = kotlin.math.ceil(titleWidth / (width - 66f)).toInt().coerceIn(1, 3)
    return width to max(64f, lines * 19f + 24f)
}

internal fun arrange(
    topics: List<MapTopic>,
    layout: MapLayout,
    expanded: Pair<String, Float>? = null,
    nodeWidths: Map<String, Float> = emptyMap(),
): MapGeometry {
    require(topics.map { it.id }.distinct().size == topics.size) { "Map IDs must be unique" }
    val root = topics.singleOrNull { it.parentId == null }
        ?: error("Map requires one root")
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
            if (previousBranch != -1 && previousBranch != b) {
                leaf += when (layout) {
                    MapLayout.CARD_CASCADE -> 64f
                    MapLayout.SECTION_BANDS -> 88f
                    else -> 0f
                }
            }
            y[node.id] = leaf
            leaf += when (layout) {
                MapLayout.CARD_CASCADE -> 124f
                MapLayout.STEP_RAIL -> 128f
                else -> 140f
            }
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
    // Make room for an expanded preview. Shifting by y rather than by sibling index keeps this
    // correct for a parent node too: its children sit in another column, so only same-column
    // neighbours below it need to move.
    val extraHeight = expanded?.takeIf { it.first in y }?.let {
        // The expanded host also has a taller header than the compact card.
        it.second + 96f - compactCardSize(ordered.first { node -> node.id == it.first }.title,
            nodeWidths[it.first]).second
    } ?: 0f
    if (extraHeight > 0f) {
        val pivot = y.getValue(expanded!!.first)
        y.keys.toList().forEach { id ->
            if (id != expanded.first && y.getValue(id) > pivot) y[id] = y.getValue(id) + extraHeight
        }
        leaf += extraHeight
    }
    val frames = linkedMapOf<String, Frame>()
    ordered.forEach { node ->
        val d = depth.getValue(node.id)
        val b = branch.getValue(node.id)
        // Short titles no longer inherit a large fixed rectangle. Keep the preview host's
        // existing width when expanded, and reserve a separate right-hand control lane.
        val compact = compactCardSize(node.title, nodeWidths[node.id])
        val w = if (node.id == expanded?.first) 240f else compact.first
        val h = if (node.id == expanded?.first) 96f + expanded.second else compact.second
        val row = y.getValue(node.id)
        val x: Float
        val cy: Float
        when (layout) {
            MapLayout.RIGHT_TREE -> {
                x = d * 268f; cy = row
            }
            MapLayout.FLOW_FORKS -> {
                x = d * 300f; cy = row
            }
            MapLayout.LOGIC_BRACKETS -> {
                x = d * 284f; cy = row
            }
            MapLayout.CARD_CASCADE -> {
                x = d * 284f + if (d == 0) 0f else (b % 3) * 16f
                cy = row
            }
            MapLayout.STEP_RAIL -> {
                x = d * 290f + if (d == 0) 0f else (b % 3) * 22f
                cy = row
            }
            MapLayout.SECTION_BANDS -> {
                x = d * 278f; cy = row
            }
            MapLayout.NESTED_BRACKETS -> {
                x = d * 300f; cy = row
            }
            MapLayout.ROUNDED_FORKS -> {
                x = d * 300f; cy = row
            }
            MapLayout.SCULPTED_BRANCHES -> {
                x = d * 304f; cy = row
            }
            MapLayout.CLOUD_OUTCOMES -> {
                x = d * 300f; cy = row
            }
            MapLayout.FAN_FORKS -> {
                x = d * 302f; cy = row
            }
            MapLayout.FACETED_PATHS -> {
                x = d * 300f; cy = row
            }
            MapLayout.LOOP_FORKS -> {
                x = d * 310f; cy = row
            }
        }
        // Grow downward from the compact card's top rather than upward into the preceding card.
        val anchoredCy = cy + if (node.id == expanded?.first) (h - compact.second) / 2f else 0f
        frames[node.id] = Frame(node.id, x, anchoredCy - h / 2f, w, h)
    }
    // A widened card must push every later depth to the right. This preserves connector
    // direction and prevents overlap regardless of which left-to-right layout is selected.
    val baseStep = when (layout) {
        MapLayout.RIGHT_TREE -> 268f
        MapLayout.FLOW_FORKS, MapLayout.NESTED_BRACKETS, MapLayout.ROUNDED_FORKS -> 300f
        MapLayout.LOGIC_BRACKETS, MapLayout.CARD_CASCADE -> 284f
        MapLayout.STEP_RAIL -> 290f
        MapLayout.SECTION_BANDS -> 278f
        MapLayout.SCULPTED_BRANCHES -> 304f
        MapLayout.CLOUD_OUTCOMES -> 300f
        MapLayout.FAN_FORKS -> 302f
        MapLayout.FACETED_PATHS -> 300f
        MapLayout.LOOP_FORKS -> 310f
    }
    val widestAtDepth = ordered.groupBy { depth.getValue(it.id) }
        .mapValues { (_, level) -> level.maxOf { frames.getValue(it.id).w } }
    var extraX = 0f
    for (level in 0..depth.values.max()) {
        if (extraX > 0f) ordered.filter { depth.getValue(it.id) == level }.forEach { node ->
            frames[node.id] = frames.getValue(node.id).let { it.copy(x = it.x + extraX) }
        }
        extraX += ((widestAtDepth[level] ?: 0f) + 28f - baseStep).coerceAtLeast(0f)
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
    onExportPdf: () -> Unit = {},
    onExportPng: () -> Unit = {},
    onExportMarkdown: () -> Unit = {},
    onExportJson: () -> Unit = {},
    modifier: Modifier = Modifier,
    onFocusModeChange: (Boolean) -> Unit = {},
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
     * Node ids that already have a saved preview. The selected node shows a pencil when its id is
     * here and a faint plus when it is not.
     */
    previewOwners: Set<String> = emptySet(),
    /** The one node currently showing its preview, and the extra height that preview needs. */
    expandedPreviewId: String? = null,
    expandedPreviewHeight: Float = 0f,
    /** Left caret: reveals or conceals saved content. It never enters editing. */
    onTogglePreview: (MapTopic) -> Unit = {},
    /**
     * Node-level plus/pencil. This opens ONLY the preview editor and is entirely separate from the
     * lower action bar's Add, which remains the canonical feature palette.
     */
    onEditPreview: (MapTopic) -> Unit = {},
    /** Draws the expanded node itself, in screen coordinates supplied by the map. */
    expandedPreviewContent: (@Composable (androidx.compose.ui.geometry.Rect) -> Unit)? = null,
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
    /** Project/workstream creation menu; task content retains its real task owner. */
    onAddContainer: (MapTopic) -> Unit = {},
    onAddChildTask: (MapTopic) -> Unit = {},
) {
    var current by remember(appearance) { mutableStateOf(appearance) }
    var tasksOnly by rememberSaveable { mutableStateOf(false) }
    var focusMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusMode) { onFocusModeChange(focusMode) }
    DisposableEffect(Unit) { onDispose { onFocusModeChange(false) } }
    val focusContext = LocalContext.current
    val focusView = LocalView.current
    DisposableEffect(focusMode) {
        if (!focusMode) onDispose { }
        else {
            val controller = focusContext.findActivity()?.window?.let {
                WindowCompat.getInsetsController(it, focusView)
            }
            val previousBehavior = controller?.systemBarsBehavior
            controller?.apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            onDispose {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                previousBehavior?.let { controller?.systemBarsBehavior = it }
            }
        }
    }
    var collapsedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var lockedBranchId by rememberSaveable { mutableStateOf<String?>(null) }
    var collapsedBeforeFocus by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    var tasksOnlyBeforeFocus by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var zoomBeforeFocus by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var offsetXBeforeFocus by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var offsetYBeforeFocus by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    val focusedRootId = lockedBranchId?.takeIf { id -> topics.any { it.id == id } }
    androidx.compose.runtime.LaunchedEffect(lockedBranchId, focusedRootId) {
        if (lockedBranchId != null && focusedRootId == null) {
            collapsedIds = ArrayList(collapsedBeforeFocus.orEmpty())
            collapsedBeforeFocus = null
            tasksOnlyBeforeFocus?.let { tasksOnly = it }
            tasksOnlyBeforeFocus = null
            lockedBranchId = null
        }
    }
    val baseTopics = remember(topics, tasksOnly, focusedRootId) {
        val branch = focusedMapTopics(topics, focusedRootId)
        if (tasksOnly) taskOnlyMapTopics(branch) else branch
    }
    val visibleTopics = remember(baseTopics, collapsedIds) { foldedMapTopics(baseTopics, collapsedIds.toSet()) }
    val branchCounts = remember(baseTopics) {
        baseTopics.associate { it.id to mapDescendants(baseTopics, it.id).size }
    }
    val visiblePreviewId = expandedPreviewId.takeIf {
        !tasksOnly && !focusMode && visibleTopics.any { topic -> topic.id == it }
    }
    val visiblePreviewOwners = if (tasksOnly || focusMode) emptySet() else previewOwners
    val geometry = remember(visibleTopics, current.layout, current.nodeWidths,
        visiblePreviewId, expandedPreviewHeight) {
        arrange(
            visibleTopics,
            current.layout,
            visiblePreviewId?.takeIf { expandedPreviewHeight > 0f }?.let { it to expandedPreviewHeight },
            current.nodeWidths,
        )
    }
    val byId = remember(geometry) { geometry.topics.associateBy { it.id } }
    LaunchedEffect(visibleTopics, selectedId) {
        if (selectedId != null && visibleTopics.none { it.id == selectedId }) onStructureSelect(null)
    }
    var outgoingGeometry by remember { mutableStateOf<MapGeometry?>(null) }
    val geometryFade = remember { androidx.compose.animation.core.Animatable(1f) }
    val transitionScope = rememberCoroutineScope()
    var geometryTransitionJob by remember { mutableStateOf<Job?>(null) }
    var geometryTransitionId by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current.density
    var resizingNodeId by remember { mutableStateOf<String?>(null) }
    var resizeEdgeLeft by remember { mutableFloatStateOf(0f) }
    var resizeDragX by remember { mutableFloatStateOf(0f) }
    var resizeStartWidth by remember { mutableFloatStateOf(0f) }
    var resizeOriginalWidths by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // Pan/zoom survive a rotation or a process restart; a colour change must not disturb them.
    var zoom by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var offsetY by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var showLayouts by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var colorTargetId by remember { mutableStateOf<String?>(null) }
    var colorSearch by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var exportMenuPage by remember { mutableStateOf(false) }
    var fitAfterViewChange by remember { mutableStateOf(false) }
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
    val paletteTask = addPaletteTaskId?.let(byId::get)
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
        zoom = fitMapZoom(viewport.width - margin, viewport.height - margin, width, height)
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
    fun zoomBy(factor: Float) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        offsetX = zoomMapOffset(offsetX, viewport.width / 2f, zoom, next)
        offsetY = zoomMapOffset(offsetY, viewport.height / 2f, zoom, next)
        zoom = next
    }
    fun animateFoldTo(next: Set<String>, fitAfter: Boolean = false) {
        if (next == collapsedIds.toSet() || outgoingGeometry != null) return
        val transitionId = ++geometryTransitionId
        geometryTransitionJob?.cancel()
        geometryTransitionJob = transitionScope.launch {
            // Establish the old frame before changing the fold state. Otherwise the new tree
            // can appear for one frame at full opacity before the animation starts.
            geometryFade.snapTo(0f)
            outgoingGeometry = geometry
            val startZoom = zoom
            val startX = offsetX
            val startY = offsetY
            val nextVisible = foldedMapTopics(baseTopics, next)
            val nextPreview = visiblePreviewId?.takeIf { id -> nextVisible.any { it.id == id } }
            val nextGeometry = arrange(nextVisible, current.layout,
                nextPreview?.takeIf { expandedPreviewHeight > 0f }?.let { it to expandedPreviewHeight },
                current.nodeWidths)
            val margin = 72f * density
            val targetZoom = if (fitAfter && viewport != IntSize.Zero)
                fitMapZoom(viewport.width - margin, viewport.height - margin,
                    (nextGeometry.right - nextGeometry.left) * density,
                    (nextGeometry.bottom - nextGeometry.top) * density)
            else startZoom
            val targetX = if (fitAfter && viewport != IntSize.Zero)
                viewport.width / 2f - (nextGeometry.left + nextGeometry.right) / 2f * density * targetZoom
            else startX
            val targetY = if (fitAfter && viewport != IntSize.Zero)
                viewport.height / 2f - (nextGeometry.top + nextGeometry.bottom) / 2f * density * targetZoom
            else startY
            collapsedIds = ArrayList(next)
            try {
                kotlinx.coroutines.coroutineScope {
                    launch { geometryFade.animateTo(1f, androidx.compose.animation.core.tween(260)) }
                    if (fitAfter && startZoom.isFinite() && startX.isFinite() && startY.isFinite()) {
                        androidx.compose.animation.core.animate(0f, 1f,
                            animationSpec = androidx.compose.animation.core.tween(260)) { fraction, _ ->
                            zoom = startZoom + (targetZoom - startZoom) * fraction
                            offsetX = startX + (targetX - startX) * fraction
                            offsetY = startY + (targetY - startY) * fraction
                        }
                    }
                }
            } finally {
                if (geometryTransitionId == transitionId) outgoingGeometry = null
            }
        }
    }
    fun toggleBranchFocus() {
        // Read the live state here. A remembered control callback can outlive the composition
        // that captured focusedRootId, so that derived value may still describe the old view.
        if (lockedBranchId == null) {
            val target = selectedId?.takeIf { id -> topics.any { it.id == id } } ?: return
            collapsedBeforeFocus = ArrayList(collapsedIds)
            tasksOnlyBeforeFocus = tasksOnly
            zoomBeforeFocus = zoom
            offsetXBeforeFocus = offsetX
            offsetYBeforeFocus = offsetY
            collapsedIds = ArrayList(collapsedIds - (mapDescendants(topics, target) + target))
            outgoingGeometry = geometry
            lockedBranchId = target
            fitAfterViewChange = true
        } else {
            outgoingGeometry = geometry
            collapsedIds = ArrayList(collapsedBeforeFocus.orEmpty())
            collapsedBeforeFocus = null
            tasksOnlyBeforeFocus?.let { tasksOnly = it }
            tasksOnlyBeforeFocus = null
            lockedBranchId = null
            fitAfterViewChange = false
            if (zoomBeforeFocus.isFinite()) zoom = zoomBeforeFocus
            if (offsetXBeforeFocus.isFinite()) offsetX = offsetXBeforeFocus
            if (offsetYBeforeFocus.isFinite()) offsetY = offsetYBeforeFocus
            zoomBeforeFocus = Float.NaN
            offsetXBeforeFocus = Float.NaN
            offsetYBeforeFocus = Float.NaN
        }
        val transitionId = ++geometryTransitionId
        geometryTransitionJob?.cancel()
        geometryTransitionJob = transitionScope.launch {
            try {
                geometryFade.snapTo(0f)
                geometryFade.animateTo(1f, androidx.compose.animation.core.tween(320))
            } finally {
                if (geometryTransitionId == transitionId) outgoingGeometry = null
            }
        }
    }
    androidx.activity.compose.BackHandler(focusedRootId != null && !showLayouts && !showColors) {
        toggleBranchFocus()
    }
    androidx.activity.compose.BackHandler(focusMode && !showLayouts && !showColors) {
        focusMode = false
    }
    LaunchedEffect(tasksOnly, fitAfterViewChange, geometry) {
        if (fitAfterViewChange) {
            fit()
            fitAfterViewChange = false
        }
    }

    val projectPalette = com.virlin.app.ui.hierarchy.LocalProjectPalette.current
    val projectAccent = projectPalette?.let { Color(it.main) } ?: Color(0xFF16A34A)
    val projectText = projectPalette?.let { Color(it.text) } ?: Color(0xFF17202C)
    val projectMuted = projectPalette?.let { Color(it.muted) } ?: projectText.copy(alpha = .58f)
    val projectMenuSurface = projectPalette?.let { Color(it.tertiary) } ?: Color(0xFFF1F8F4)
    val pageSurface = projectPalette?.let { Color(it.surface) } ?: Color(0xFFFBFAF8)
    fun closeEditor() {
        current = appearance
        showLayouts = false
        showColors = false
    }
    if (showLayouts || showColors) {
        androidx.activity.compose.BackHandler { closeEditor() }
        if (showLayouts) {
            MapLayoutPage(
                projectTitle = projectTitle,
                topics = layoutPreviewTopics(baseTopics),
                current = current.layout,
                applied = appearance.layout,
                hasChanges = current != appearance,
                accent = projectAccent,
                textColor = projectText,
                surface = pageSurface,
                onSelect = { current = current.copy(layout = it) },
                onBack = ::closeEditor,
                onSave = {
                    onAppearanceChange(current)
                    showLayouts = false
                    fitAfterViewChange = true
                },
                modifier = modifier,
            )
        } else {
            MapStudioPage(
                projectTitle = projectTitle,
                topics = baseTopics,
                appearance = current,
                hasChanges = current != appearance,
                targetId = colorTargetId,
                search = colorSearch,
                pickedColor = pickedColor,
                accent = projectAccent,
                textColor = projectText,
                surface = pageSurface,
                onSearch = { colorSearch = it },
                onTarget = { colorTargetId = it },
                onColor = { pickedColor = it },
                onAppearance = { current = it },
                onBack = ::closeEditor,
                onSave = { onAppearanceChange(current); showColors = false },
                modifier = modifier,
            )
        }
        return
    }
    Column(modifier.fillMaxSize().background(projectPalette?.let { Color(it.surface) } ?: Color(0xFFFBFAF8))) {
        if (!focusMode) Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (focusedRootId != null) toggleBranchFocus() else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,
                    if (focusedRootId != null) "Back to full map" else "Back to project")
            }
            Text(
                projectTitle, Modifier.weight(1f), fontSize = 18.sp,
                fontWeight = FontWeight.Bold, color = projectText,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = { current = appearance; colorTargetId = selectedId; colorSearch = ""; pickedColor = null; showColors = true }) {
                Icon(Icons.Default.Palette, "Map colours")
            }
            IconButton(onClick = { current = appearance; showLayouts = true }) {
                Icon(Icons.Default.Dashboard, "Map layout")
            }
            Box {
                IconButton(onClick = { exportMenuPage = false; menu = true }) {
                    Icon(Icons.Default.MoreVert, "More")
                }
                androidx.compose.material3.MaterialTheme(colorScheme =
                    androidx.compose.material3.MaterialTheme.colorScheme.copy(
                        surface = projectMenuSurface,
                        surfaceContainer = projectMenuSurface,
                        surfaceContainerLow = projectMenuSurface,
                        surfaceContainerHigh = projectMenuSurface,
                        onSurface = projectText,
                        onSurfaceVariant = projectMuted,
                    )) { DropdownMenu(
                    expanded = menu,
                    onDismissRequest = {
                        menu = false; exportMenuPage = false
                    },
                    modifier = Modifier.width(216.dp),
                ) {
                    MapActionsMenu(
                        showingExport = exportMenuPage,
                        structureMode = structureMode,
                        onToggleStructureMode = onToggleStructureMode,
                        accent = projectAccent,
                        textColor = projectText,
                        mutedColor = projectMuted,
                        onShowExport = { exportMenuPage = true },
                        onShowMain = { exportMenuPage = false },
                        onDismiss = { menu = false; exportMenuPage = false },
                        onExportXmind = onExportXmind,
                        onExportVirlin = onExportVirlin,
                        onExportPdf = onExportPdf,
                        onExportPng = onExportPng,
                        onExportMarkdown = onExportMarkdown,
                        onExportJson = onExportJson,
                    )
                } }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(
                Modifier.fillMaxSize().testTag(MindMapCanvasTag)
                    .onSizeChanged { next ->
                        val previous = viewport
                        if (previous != IntSize.Zero && previous != next &&
                            offsetX.isFinite() && offsetY.isFinite()) {
                            // Keep the same map point centered when chrome or orientation changes.
                            offsetX += (next.width - previous.width) / 2f
                            offsetY += (next.height - previous.height) / 2f
                        }
                        viewport = next
                    }
                    .semantics {
                        contentDescription =
                            "Project mind map. Drag to pan, pinch to zoom. " +
                                "Tap a box to select it, then use the actions below to open it."
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
                    // one. `onNodeOpen` remains the selected box's explicit Open action.
                    // selectedId is a KEY, not merely captured: the preview caret and
                    // plus/pencil belong to the already-selected node, so a detector built before
                    // the selection existed would compare against a stale null and never fire
                    // them. This is the same staleness the pan/zoom handler above guards against.
                    .pointerInput(geometry, selectedId) {
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

                            // Gesture priority: the caret and the plus/pencil belong to the node
                            // that is ALREADY selected, so a first tap can only ever select. That
                            // keeps selection the cheapest gesture on the map and means these
                            // controls can never swallow a tap aimed at a different node.
                            if (topic.id == selectedId && !tasksOnly) {
                                if (caretZone(frame).contains(world.x, world.y)) {
                                    if (topic.id in previewOwners) onTogglePreview(topic)
                                    return@detectTapGestures
                                }
                                if (previewEditZone(frame).contains(world.x, world.y)) {
                                    onEditPreview(topic)
                                    return@detectTapGestures
                                }
                            }

                            onStructureSelect(topic)

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
                    outgoingGeometry?.let { previous ->
                        val layer = native.saveLayerAlpha(null, ((1f - geometryFade.value) * 255).roundToInt().coerceIn(0, 255))
                        paintMap(native, previous, current, visible, null)
                        native.restoreToCount(layer)
                    }
                    val layer = if (outgoingGeometry != null)
                        native.saveLayerAlpha(null, (geometryFade.value * 255).roundToInt().coerceIn(0, 255))
                    else null
                    paintMap(native, geometry, current, visible, selectedId,
                        previewOwners = visiblePreviewOwners,
                        expandedId = visiblePreviewId,
                        showPreviewControls = !tasksOnly && !focusMode,
                        dragId = dragId, hoverId = hoverId,
                        hoverValid = dragId?.let { source ->
                            hoverId?.let { target -> isValidTarget(source, target) }
                        } ?: false)
                    if (layer != null) native.restoreToCount(layer)
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
                        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
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
            // The expanded node, drawn as ONE Compose overlay in screen space from the reflowed
            // frame. Only the selected node can be expanded, so at most one of these exists and
            // the map still costs no composables per node.
            if (!focusMode) visiblePreviewId?.let { id ->
                geometry.frames[id]?.let { frame ->
                    val scale = density * zoom
                    val rect = androidx.compose.ui.geometry.Rect(
                        left = frame.x * scale + offsetX,
                        top = frame.y * scale + offsetY,
                        right = (frame.x + frame.w) * scale + offsetX,
                        bottom = (frame.y + frame.h) * scale + offsetY,
                    )
                    expandedPreviewContent?.invoke(rect)
                }
            }
            // A selected task or workstream gets a real 48dp drag handle. This owns its pointer sequence;
            // the canvas's pan/zoom/tap recognizers cannot steal a drag from this child.
            // A tap selects the node first. The handle remains usable even at Fit zoom.
            val selectedTask = selectedId?.let(byId::get)
            val selectedFrame = selectedId?.let(geometry.frames::get)
            if (structureMode && !focusMode && !tasksOnly && selectedTask != null &&
                selectedFrame != null && visiblePreviewId != selectedTask.id && dragId == null &&
                viewport != IntSize.Zero && offsetX.isFinite() && offsetY.isFinite()
            ) {
                // The transparent touch strip hugs the box border; no separate resize button.
                // Its minimum screen-space height keeps the edge usable at Fit zoom.
                val edgeWidthPx = 24f * density
                val edgeHeightPx = max(selectedFrame.h * density * zoom, 44f * density)
                val right = (selectedFrame.x + selectedFrame.w) * density * zoom + offsetX
                val centreY = (selectedFrame.y + selectedFrame.h / 2f) * density * zoom + offsetY
                val left = if (resizingNodeId == selectedTask.id) resizeEdgeLeft else
                    (right - 4f * density).coerceIn(0f,
                        (viewport.width - edgeWidthPx).coerceAtLeast(0f))
                val top = (centreY - edgeHeightPx / 2f)
                    .coerceIn(0f, (viewport.height - edgeHeightPx).coerceAtLeast(0f))
                val latestLeft by rememberUpdatedState(left)
                Box(
                    Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(width = 24.dp, height = (edgeHeightPx / density).dp)
                        .zIndex(1f)
                        .testTag("mind_map_resize_edge_${selectedTask.id}")
                        .semantics { contentDescription =
                            "Drag the right edge of ${selectedTask.title} to resize" }
                        .pointerInput(selectedTask.id, density, zoom) {
                            detectDragGestures(
                                onDragStart = {
                                    resizingNodeId = selectedTask.id
                                    resizeEdgeLeft = latestLeft
                                    resizeDragX = 0f
                                    resizeOriginalWidths = current.nodeWidths
                                    resizeStartWidth = current.nodeWidths[selectedTask.id]
                                        ?: compactCardSize(selectedTask.title).first
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    resizeDragX += amount.x
                                    val width = (resizeStartWidth +
                                        resizeDragX / (density * zoom))
                                        .coerceIn(minimumCardWidth(selectedTask.title), 560f)
                                    current = current.copy(nodeWidths =
                                        current.nodeWidths + (selectedTask.id to width))
                                },
                                onDragEnd = {
                                    resizingNodeId = null
                                    onAppearanceChange(current)
                                },
                                onDragCancel = {
                                    resizingNodeId = null
                                    current = current.copy(nodeWidths = resizeOriginalWidths)
                                },
                            )
                        },
                )
            }
            // Canvas glyphs shrink with zoom. Give each disclosure/edit glyph a real screen-space
            // target so the pan recognizer cannot consume a tap intended for a tiny symbol.
            if (!focusMode && selectedTask != null && selectedFrame != null && !tasksOnly &&
                visiblePreviewId != selectedTask.id && dragId == null && paletteTask == null &&
                offsetX.isFinite() && offsetY.isFinite()
            ) {
                val scale = density * zoom
                listOf(false, true).forEach { edit ->
                    // Targets must follow the painted glyphs. Enlarging two adjacent targets
                    // to 48dp displaced the edit target and let the caret swallow pencil taps.
                    // Keep a generous width, but cap height at the glyph separation.
                    val targetHeightPx = min(48f * density, selectedFrame.h * .35f * scale)
                    val targetHeightDp = (targetHeightPx / density).dp
                    val centreY = (selectedFrame.y + selectedFrame.h * if (edit) .15f else .5f) * scale + offsetY
                    val centreX = (selectedFrame.x + selectedFrame.w - 24f) * scale + offsetX
                    Box(Modifier.offset {
                        IntOffset((centreX - 24.dp.toPx()).roundToInt(),
                            (centreY - targetHeightPx / 2f).roundToInt())
                    }.size(width = 48.dp, height = targetHeightDp)
                        .testTag(if (edit) "map_node_edit_preview" else "map_node_toggle_preview")
                        .semantics {
                            contentDescription = if (edit) "Edit node content"
                                else if (selectedTask.id in previewOwners) "Expand node content"
                                else "No saved node content"
                        }
                        .clickable(role = Role.Button) {
                            if (edit) onEditPreview(selectedTask)
                            else if (selectedTask.id in previewOwners) onTogglePreview(selectedTask)
                        })
                }
            }
            // Direct dragging is an explicit mode; selection and the toolbar work without it.
            if (!focusMode && structureMode && selectedTask != null && selectedTask.kind != MapKind.PROJECT && selectedFrame != null &&
                viewport != IntSize.Zero && offsetX.isFinite() && offsetY.isFinite()) {
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
                // Keep the external drag target below the centered disclosure target.
                val handleTop = ((selectedFrame.y + selectedFrame.h / 2f) * density * zoom +
                    offsetY + handlePx / 2f + 4f * density).coerceIn(0f, (viewport.height - handlePx).coerceAtLeast(0f))
                Box(
                    Modifier.offset { IntOffset(handleLeft.roundToInt(), handleTop.roundToInt()) }
                        .size(48.dp)
                        .zIndex(2f)
                        .testTag("mind_map_branch_drag_handle")
                        .semantics { contentDescription = "Drag ${selectedTask.title} to another task or workstream" }
                        // Where the handle actually sits, recorded from layout. The gesture reads
                        // this STATE rather than closing over the computed position, so the
                        // detector never has to restart when the handle moves - a restart
                        // mid-drag would drop the gesture.
                        .onGloballyPositioned { handleOrigin = it.positionInParent() }
                        .background(Color(0xE6F0F5F2), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFD8E6DE), RoundedCornerShape(12.dp))
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
                                        // onDragStart already includes the motion that crossed
                                        // touch slop. Adding its overslop again shifts the drop
                                        // away from the finger; use the actual local position.
                                        dragPointer = handleOrigin + change.position
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
                ) {
                    Canvas(Modifier.size(20.dp)) {
                        for (x in listOf(.3f, .7f)) for (y in listOf(.2f, .5f, .8f))
                            drawCircle(Color(0xFF52645A), 2.dp.toPx(), Offset(size.width*x, size.height*y))
                    }
                }
            }
            // The bar FLOATS over the canvas instead of sitting above it in the column.
            // As a sibling in the column its height changed when a node was selected, which
            // moved the canvas out from under a finger that was already dragging - the target
            // then slid away and the drop landed on nothing.
            // The bar appears whenever something is selected, not only while organizing, so a
            // drag handle can never be on screen without its action bar.
            if (structureMode && !focusMode) {
                // Match the three-dot button's right inset: the final Clear icon lands directly
                // beneath it, and the remaining actions extend left across the map.
                Box(Modifier.align(Alignment.TopEnd).padding(end = 4.dp)) {
                    structureBar?.invoke()
                }
            }
            // The selected task's actions, sitting directly above the Fit/zoom controls. Like the
            // structure bar it is an OVERLAY, not a sibling in a column, so selecting a task
            // cannot resize the canvas or move its hit-test origin.
            val barTask = selectedId?.let(byId::get)
            if (!focusMode && barTask != null && dragId == null && paletteTask == null &&
                !showLayouts && !showColors
            ) {
                MapSelectedTaskBar(
                    task = barTask,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    onOpenTask = onNodeOpen,
                    onMove = onMoveTask,
                    onPage = onOpenTaskPage,
                    onAdd = { task ->
                        addPaletteTaskId = task.id
                    },
                )
            }
            if ((!structureMode || focusMode) && paletteTask == null) {
                val controlSelection = selectedId?.let { id -> baseTopics.firstOrNull { it.id == id } }
                val selectedBranchIds = controlSelection?.let { mapDescendants(baseTopics, it.id) + it.id }
                MapGlassControls(
                    modifier = Modifier.align(Alignment.BottomEnd).zIndex(10f).padding(start = 8.dp, end = 8.dp,
                        bottom = if (!focusMode && barTask != null && dragId == null && !showLayouts && !showColors) 100.dp else 8.dp),
                    tasksOnly = tasksOnly,
                    focusMode = focusMode,
                    onToggleFocus = {
                        focusMode = !focusMode
                        addPaletteTaskId = null
                        menu = false
                    },
                    height = minOf(440.dp, maxHeight - 16.dp),
                    locked = focusedRootId != null,
                    lockEnabled = selectedId != null,
                    onToggleLock = ::toggleBranchFocus,
                    onFit = { fit() },
                    onZoomOut = { zoomBy(1f / 1.25f) },
                    onZoomIn = { zoomBy(1.25f) },
                    onTasksOnly = { tasksOnly = !tasksOnly; collapsedIds = arrayListOf(); fitAfterViewChange = true },
                    onExpandAll = {
                        animateFoldTo(if (controlSelection == null)
                            expandMapLevel(baseTopics, collapsedIds.toSet())
                        else expandMapBranchLevel(baseTopics, collapsedIds.toSet(), controlSelection.id),
                            fitAfter = true)
                    },
                    anyCollapsed = if (selectedBranchIds == null) collapsedIds.isNotEmpty()
                        else collapsedIds.any { it in selectedBranchIds },
                    onCollapseAll = {
                        animateFoldTo(if (controlSelection == null)
                            collapseMapLevel(baseTopics, collapsedIds.toSet())
                        else collapseMapBranchLevel(baseTopics, collapsedIds.toSet(), controlSelection.id),
                            fitAfter = true)
                        if (controlSelection == null) onStructureSelect(null)
                    },
                    expandDescription = controlSelection?.let { "Open next level under ${it.title}" }
                        ?: "Open next branch level",
                    collapseDescription = controlSelection?.let { "Fold deepest level under ${it.title}" }
                        ?: "Fold deepest visible level",
                )
            }
            if (!focusMode && offsetX.isFinite() && offsetY.isFinite() && dragId == null) {
                geometry.frames.values.forEach { frame ->
                    val count = branchCounts[frame.id] ?: 0
                    if (count > 0) {
                        val x = (frame.x + frame.w) * density * zoom + offsetX
                        // Keep folding away from the selected node's external drag target.
                        val y = (frame.y + frame.h / 2f) * density * zoom + offsetY +
                            if (frame.id == selectedId) 52f * density else 0f
                        if (x in -48f..viewport.width.toFloat() && y in 0f..viewport.height.toFloat()) {
                            val folded = frame.id in collapsedIds
                            Box(Modifier.offset { IntOffset(x.roundToInt(), (y - 24.dp.toPx()).roundToInt()) }
                                .size(48.dp).testTag("map_branch_${frame.id}")
                                .graphicsLayer { alpha = if (outgoingGeometry != null) geometryFade.value else 1f }
                                .semantics { contentDescription = if (folded) "Expand $count hidden cards" else "Collapse $count descendant cards" }
                                .clickable(enabled = outgoingGeometry == null, role = Role.Button) {
                                    animateFoldTo((if (folded) collapsedIds - frame.id else collapsedIds + frame.id).toSet())
                                }, contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawLine(Color(0xFF76B99C), Offset(0f, size.height/2), Offset(12.dp.toPx(), size.height/2), 1.dp.toPx())
                                }
                                Text(if (folded) count.toString() else "−", fontSize = 12.sp,
                                    color = Color(0xFF07533E), modifier = Modifier
                                        .background(Color(0xFFE0F5EB), RoundedCornerShape(50))
                                        .padding(horizontal = 7.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            }
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
                    onAddChild = { val parent = paletteTask; addPaletteTaskId = null;
                        if (parent.kind == MapKind.TASK) onAddChildTask(parent) else onAddContainer(parent) },
                    addChildLabel = if (paletteTask.kind == MapKind.PROJECT) "New task or workstream" else if (paletteTask.kind == MapKind.WORKSTREAM) "New task" else "New subtask · Single or Bulk",
                    allowedKinds = if (paletteTask.kind == MapKind.WORKSTREAM) setOf(MapAddKind.TODO, MapAddKind.NOTE, MapAddKind.TASK, MapAddKind.LINK, MapAddKind.AUDIO) else MapAddKind.entries.toSet(),
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

}

@Composable
private fun MapActionsMenu(
    showingExport: Boolean,
    structureMode: Boolean,
    onToggleStructureMode: (() -> Unit)?,
    accent: Color,
    textColor: Color,
    mutedColor: Color,
    onShowExport: () -> Unit,
    onShowMain: () -> Unit,
    onDismiss: () -> Unit,
    onExportXmind: () -> Unit,
    onExportVirlin: () -> Unit,
    onExportPdf: () -> Unit,
    onExportPng: () -> Unit,
    onExportMarkdown: () -> Unit,
    onExportJson: () -> Unit,
) {
    AnimatedContent(
        targetState = showingExport,
        transitionSpec = {
            val direction = if (targetState) 1 else -1
            (fadeIn(tween(170)) + slideInHorizontally(tween(170)) { direction * it / 5 }) togetherWith
                (fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -direction * it / 5 })
        },
        label = "Map menu page",
    ) { exportPage ->
        Column(Modifier.width(216.dp)) {
            if (!exportPage) {
                if (onToggleStructureMode != null) DropdownMenuItem(
                    modifier = Modifier.testTag(MindMapStructureToggleTag),
                    text = { Text("Organize", color = textColor, fontWeight = FontWeight.Medium) },
                    trailingIcon = {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = accent.copy(alpha = if (structureMode) .18f else .09f),
                        ) {
                            Text(
                                if (structureMode) "On" else "Off",
                                Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                color = if (structureMode) accent else mutedColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    },
                    onClick = { onDismiss(); onToggleStructureMode() },
                )
                androidx.compose.material3.HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    color = accent.copy(alpha = 0.16f),
                )
                DropdownMenuItem(
                    modifier = Modifier.testTag("map_menu_export"),
                    text = { Text("Export", color = textColor, fontWeight = FontWeight.Medium) },
                    trailingIcon = {
                        Icon(Icons.Default.ChevronRight, null, tint = accent, modifier = Modifier.size(20.dp))
                    },
                    onClick = onShowExport,
                )
            } else {
                DropdownMenuItem(
                    modifier = Modifier.testTag("map_export_heading"),
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = accent) },
                    text = { Text("Export", color = textColor, fontWeight = FontWeight.SemiBold) },
                    onClick = onShowMain,
                )
                androidx.compose.material3.HorizontalDivider(color = accent.copy(alpha = .16f))
                MapExportMenuItem("map_export_xmind", "XMind (.xmind)", Icons.Default.AccountTree,
                    accent, textColor) { onDismiss(); onExportXmind() }
                MapExportMenuItem("map_export_virlin", "VMap (.vmap)", Icons.Default.Layers,
                    accent, textColor) { onDismiss(); onExportVirlin() }
                MapExportMenuItem("map_export_pdf", "PDF (.pdf)", Icons.Default.PictureAsPdf,
                    accent, textColor) { onDismiss(); onExportPdf() }
                MapExportMenuItem("map_export_png", "PNG image (.png)", Icons.Default.Image,
                    accent, textColor) { onDismiss(); onExportPng() }
                MapExportMenuItem("map_export_md", "Markdown (.md)", Icons.Default.Description,
                    accent, textColor) { onDismiss(); onExportMarkdown() }
                MapExportMenuItem("map_export_json", "JSON (.json)", Icons.Default.Code,
                    accent, textColor) { onDismiss(); onExportJson() }
            }
        }
    }
}

@Composable
private fun MapExportMenuItem(
    tag: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    textColor: Color,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        modifier = Modifier.testTag(tag),
        leadingIcon = { Icon(icon, null, tint = accent) },
        text = { Text(label, color = textColor) },
        onClick = onClick,
    )
}

@Composable
private fun MapEditorHeader(title: String, projectTitle: String, textColor: Color, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to map") }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textColor)
            Text(projectTitle, fontSize = 13.sp, color = textColor.copy(alpha = .64f), maxLines = 1)
        }
    }
}

@Composable
private fun MapEditorFooter(
    saveLabel: String,
    enabled: Boolean,
    accent: Color,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Surface(shadowElevation = 8.dp, color = Color.White) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = onSave,
                enabled = enabled,
                modifier = Modifier.weight(1.5f),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = com.virlin.app.ui.hierarchy.readableOn(accent),
                ),
            ) { Text(saveLabel) }
        }
    }
}

@Composable
private fun MapLayoutPage(
    projectTitle: String,
    topics: List<MapTopic>,
    current: MapLayout,
    applied: MapLayout,
    hasChanges: Boolean,
    accent: Color,
    textColor: Color,
    surface: Color,
    onSelect: (MapLayout) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(surface)) {
        MapEditorHeader("Map layout", projectTitle, textColor, onBack)
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(width = 20.dp, height = 3.dp)
                        .background(accent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.size(8.dp))
                Text("LOGIC CHART STYLES", fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = accent)
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(MapLayout.entries) { candidate ->
                val chosen = current == candidate
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(candidate) }
                        .semantics { contentDescription = "${candidate.label} layout" },
                    shape = RoundedCornerShape(20.dp),
                    color = if (chosen) accent.copy(alpha = .10f) else Color.White,
                    border = androidx.compose.foundation.BorderStroke(if (chosen) 2.dp else 1.dp,
                        if (chosen) accent else textColor.copy(alpha = .12f)),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        TemplateMiniature(candidate, topics)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.label, fontWeight = FontWeight.SemiBold, color = textColor)
                                Text(
                                    "${candidate.fork} · ${candidate.node} · ${candidate.endpoint}",
                                    fontSize = 12.sp, color = textColor.copy(alpha = .65f),
                                )
                            }
                            if (chosen || candidate == applied) {
                                Text(
                                    if (chosen && current != applied) "Previewing" else "Applied",
                                    color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = hasChanges,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 2 },
        ) {
            MapEditorFooter("Save layout", true, accent, onBack, onSave)
        }
    }
}

@Composable
private fun MapColorsPage(
    projectTitle: String,
    topics: List<MapTopic>,
    appearance: MapAppearance,
    hasChanges: Boolean,
    targetId: String?,
    search: String,
    pickedColor: Long?,
    accent: Color,
    textColor: Color,
    surface: Color,
    onSearch: (String) -> Unit,
    onTarget: (String) -> Unit,
    onColor: (Long) -> Unit,
    onAppearance: (MapAppearance) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = topics.firstOrNull { it.id == targetId }
    val shownTopics = remember(topics, search) {
        if (search.isBlank()) topics else topics.filter { it.title.contains(search, ignoreCase = true) }
    }
    Column(modifier.fillMaxSize().background(surface)) {
        MapEditorHeader("Map colours", projectTitle, textColor, onBack)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("Choose a palette", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                Text("A palette colours the entire map. You can also give one item or a branch its own colour.",
                    fontSize = 13.sp, color = textColor.copy(alpha = .68f))
            }
            items(mapPalettes.indices.toList()) { index ->
                val chosen = appearance.paletteIndex == index
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onAppearance(appearance.copy(paletteIndex = index)) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (chosen) accent.copy(alpha = .10f) else Color.White,
                    border = androidx.compose.foundation.BorderStroke(if (chosen) 2.dp else 1.dp,
                        if (chosen) accent else textColor.copy(alpha = .12f)),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Palette ${index + 1}", Modifier.weight(1f), color = textColor,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                        mapPalettes[index].forEach { argb ->
                            Box(Modifier.padding(start = 3.dp).size(24.dp)
                                .background(Color(argb.toInt()), RoundedCornerShape(50)))
                        }
                    }
                }
            }
            item {
                Text("Colour a specific item", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                Text("Selected: ${target?.title ?: "Choose an item below"}", color = accent, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    customColors.take(8).forEach { argb -> MapColorSwatch(argb, pickedColor == argb, onColor) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    customColors.drop(8).forEach { argb -> MapColorSwatch(argb, pickedColor == argb, onColor) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        enabled = target != null && pickedColor != null,
                        onClick = { if (target != null && pickedColor != null) onAppearance(
                            appearance.copy(nodeColors = appearance.nodeColors + (target.id to pickedColor))) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Item", fontSize = 12.sp) }
                    OutlinedButton(
                        enabled = target != null && pickedColor != null,
                        onClick = { if (target != null && pickedColor != null) onAppearance(
                            appearance.copy(branchColors = appearance.branchColors + (target.id to pickedColor))) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Branch", fontSize = 12.sp) }
                    OutlinedButton(
                        enabled = target != null,
                        onClick = { if (target != null) onAppearance(appearance.copy(
                            nodeColors = appearance.nodeColors - target.id,
                            branchColors = appearance.branchColors - target.id)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear", fontSize = 12.sp) }
                }
                Text("Item colour takes priority over its branch, then the palette.",
                    fontSize = 12.sp, color = textColor.copy(alpha = .64f))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Find a project, workstream or task") }, singleLine = true,
                )
            }
            items(shownTopics, key = { it.id }) { topic ->
                val chosen = topic.id == targetId
                val assigned = appearance.nodeColors[topic.id] ?: appearance.branchColors[topic.id]
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onTarget(topic.id) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (chosen) accent.copy(alpha = .10f) else Color.White,
                    border = androidx.compose.foundation.BorderStroke(if (chosen) 2.dp else 1.dp,
                        if (chosen) accent else textColor.copy(alpha = .10f)),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(topic.title, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(topic.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                                fontSize = 11.sp, color = textColor.copy(alpha = .58f))
                        }
                        if (assigned != null) Box(Modifier.size(20.dp)
                            .background(Color(assigned.toInt()), RoundedCornerShape(50)))
                        if (chosen) Text("  Selected", color = accent, fontSize = 11.sp)
                    }
                }
            }
        }
        MapEditorFooter("Save colours", hasChanges, accent, onBack, onSave)
    }
}

@Composable
private fun MapColorSwatch(argb: Long, selected: Boolean, onClick: (Long) -> Unit) {
    Box(Modifier.padding(vertical = 6.dp).size(34.dp)
        .background(Color(argb.toInt()), RoundedCornerShape(50))
        .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFF17202C) else Color.Black.copy(alpha = .15f),
            RoundedCornerShape(50))
        .semantics { contentDescription = "Colour swatch ${argb.toString(16)}" }
        .clickable { onClick(argb) })
}

private fun statusLabel(status: MapStatus) = when (status) {
    MapStatus.CURRENT -> "Current"
    MapStatus.DONE -> "✓ Done"
    MapStatus.BLOCKED -> "! Blocked"
    MapStatus.TODO -> "○ To do"
    MapStatus.NONE -> ""
}

/** Structural previews of the same left-to-right hierarchy in each connector language. */
private val layoutPreviewExample = listOf(
    MapTopic("preview-root", null, "Project", kind = MapKind.PROJECT),
    MapTopic("preview-plan", "preview-root", "Plan", kind = MapKind.WORKSTREAM, order = 0),
    MapTopic("preview-build", "preview-root", "Build", kind = MapKind.WORKSTREAM, order = 1),
    MapTopic("preview-brief", "preview-plan", "Brief", kind = MapKind.TASK, order = 0),
    MapTopic("preview-test", "preview-build", "Test", kind = MapKind.TASK, order = 0),
)

internal fun layoutPreviewTopics(topics: List<MapTopic>): List<MapTopic> {
    val root = topics.firstOrNull { it.parentId == null } ?: return layoutPreviewExample
    val children = topics.groupBy { it.parentId }.mapValues { (_, nodes) ->
        nodes.sortedWith(compareBy<MapTopic> { it.order }.thenBy { it.id })
    }
    val allTop = children[root.id].orEmpty()
    val prioritized = allTop.filter { children[it.id].orEmpty().isNotEmpty() } +
        allTop.filter { children[it.id].orEmpty().isEmpty() }
    val distinctTitles = prioritized.distinctBy { it.title.trim().lowercase() }
    val top = (distinctTitles + prioritized.filterNot { candidate ->
        distinctTitles.any { it.id == candidate.id }
    }).take(2)
    if (top.size < 2 || top.none { children[it.id].orEmpty().isNotEmpty() })
        return layoutPreviewExample
    return buildList {
        add(root)
        top.forEach { parent ->
            add(parent)
            addAll(children[parent.id].orEmpty().take(1))
        }
    }
}

/** The picker renders a real map subtree through the same geometry and painter as the canvas. */
@Composable
private fun TemplateMiniature(layout: MapLayout, topics: List<MapTopic>) {
    val geometry = remember(layout, topics) { arrange(topics, layout) }
    Canvas(
        Modifier.fillMaxWidth().height(112.dp).semantics {
            contentDescription = "Rendered ${layout.label} preview"
        }
    ) {
        val graphWidth = geometry.right - geometry.left
        val graphHeight = geometry.bottom - geometry.top
        val padding = 12.dp.toPx()
        val scale = min(
            (size.width - padding * 2f) / graphWidth,
            (size.height - padding * 2f) / graphHeight,
        ).coerceAtLeast(.01f)
        val left = (size.width - graphWidth * scale) / 2f
        val top = (size.height - graphHeight * scale) / 2f
        drawIntoCanvas { composeCanvas ->
            val native = composeCanvas.nativeCanvas
            native.save()
            native.translate(left, top)
            native.scale(scale, scale)
            native.translate(-geometry.left, -geometry.top)
            paintMap(
                native, geometry, MapAppearance(layout = layout),
                RectF(geometry.left - 24f, geometry.top - 24f,
                    geometry.right + 24f, geometry.bottom + 24f),
                selectedId = null, showPreviewControls = false,
            )
            native.restore()
        }
    }
}

private fun drawLayoutNode(
    canvas: android.graphics.Canvas,
    rect: RectF,
    layout: MapLayout,
    isLeaf: Boolean,
    radius: Float,
    paint: Paint,
) {
    val path = when {
        layout == MapLayout.CLOUD_OUTCOMES && isLeaf -> Path().apply {
            val l = rect.left; val t = rect.top; val w = rect.width(); val h = rect.height()
            moveTo(l + w * .10f, t + h * .34f)
            cubicTo(l + w * .03f, t + h * .10f, l + w * .27f, t - h * .02f, l + w * .36f, t + h * .14f)
            cubicTo(l + w * .48f, t - h * .09f, l + w * .68f, t + h * .01f, l + w * .70f, t + h * .15f)
            cubicTo(l + w * .96f, t + h * .05f, l + w * 1.05f, t + h * .39f, l + w * .93f, t + h * .51f)
            cubicTo(l + w * 1.07f, t + h * .81f, l + w * .77f, t + h * 1.04f, l + w * .66f, t + h * .89f)
            cubicTo(l + w * .48f, t + h * 1.07f, l + w * .31f, t + h * .96f, l + w * .24f, t + h * .86f)
            cubicTo(l - w * .03f, t + h * .95f, l - w * .04f, t + h * .50f, l + w * .10f, t + h * .34f)
            close()
        }
        layout == MapLayout.SCULPTED_BRANCHES -> Path().apply {
            val l = rect.left; val t = rect.top; val w = rect.width(); val h = rect.height()
            moveTo(l + w * .12f, t + h * .08f)
            cubicTo(l + w * .39f, t - h * .03f, l + w * .67f, t - h * .03f, l + w * .88f, t + h * .08f)
            cubicTo(l + w * 1.02f, t + h * .23f, l + w * 1.02f, t + h * .77f, l + w * .88f, t + h * .92f)
            cubicTo(l + w * .65f, t + h * 1.03f, l + w * .36f, t + h * 1.03f, l + w * .12f, t + h * .92f)
            cubicTo(l - w * .02f, t + h * .77f, l - w * .02f, t + h * .23f, l + w * .12f, t + h * .08f)
            close()
        }
        layout == MapLayout.FACETED_PATHS -> Path().apply {
            val cut = min(16f, rect.width() * .10f)
            moveTo(rect.left + cut, rect.top)
            lineTo(rect.right - cut, rect.top)
            lineTo(rect.right, rect.centerY())
            lineTo(rect.right - cut, rect.bottom)
            lineTo(rect.left + cut, rect.bottom)
            lineTo(rect.left, rect.centerY())
            close()
        }
        layout == MapLayout.LOOP_FORKS && isLeaf -> Path().apply {
            val notch = min(13f, rect.width() * .10f)
            moveTo(rect.left + notch, rect.top)
            lineTo(rect.right - radius, rect.top)
            cubicTo(rect.right, rect.top, rect.right, rect.top + radius, rect.right, rect.centerY())
            cubicTo(rect.right, rect.bottom - radius, rect.right, rect.bottom, rect.right - radius, rect.bottom)
            lineTo(rect.left + notch, rect.bottom)
            cubicTo(rect.left, rect.bottom, rect.left, rect.centerY() + notch,
                rect.left + notch, rect.centerY())
            cubicTo(rect.left, rect.centerY() - notch, rect.left, rect.top,
                rect.left + notch, rect.top)
            close()
        }
        else -> null
    }
    if (path == null) canvas.drawRoundRect(rect, radius, radius, paint)
    else canvas.drawPath(path, paint)
}

internal fun paintMap(
    canvas: android.graphics.Canvas, g: MapGeometry, appearance: MapAppearance,
    viewport: RectF, selectedId: String?,
    dragId: String? = null, hoverId: String? = null, hoverValid: Boolean = false,
    /** Node ids that have a saved preview, so the selected one shows a pencil rather than a plus. */
    previewOwners: Set<String> = emptySet(),
    /** The expanded node, drawn by the Compose overlay instead of here. */
    expandedId: String? = null,
    showPreviewControls: Boolean = true,
) {
    val palette = appearance.customPalette.takeIf { it.isNotEmpty() }
        ?: mapPalettes[appearance.paletteIndex.mod(mapPalettes.size)]
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

    if (appearance.layout == MapLayout.SECTION_BANDS) {
        // Background bands are presentation-only: the real tree and hit frames are unchanged.
        val rootId = g.topics.firstOrNull { g.parent[it.id] == null }?.id
        val groups = g.topics.filter { it.id != rootId }.groupBy { g.branch[it.id] }
        for (group in groups.values) {
            val frames = group.mapNotNull { g.frames[it.id] }
            if (frames.isEmpty()) continue
            val band = RectF(frames.minOf { it.x } - 20f, frames.minOf { it.y } - 20f,
                frames.maxOf { it.x + it.w } + 20f, frames.maxOf { it.y + it.h } + 20f)
            if (!RectF.intersects(viewport, band)) continue
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = accent(group.first())
            paint.alpha = 32
            canvas.drawRoundRect(band, 26f, 26f, paint)
        }
        paint.alpha = 255
    }
    val sharedSpines = appearance.layout == MapLayout.RIGHT_TREE ||
        appearance.layout == MapLayout.LOGIC_BRACKETS ||
        appearance.layout == MapLayout.STEP_RAIL ||
        appearance.layout == MapLayout.SECTION_BANDS ||
        appearance.layout == MapLayout.NESTED_BRACKETS
    if (sharedSpines) for ((parentId, children) in g.topics.filter { g.parent[it.id] != null }.groupBy { g.parent[it.id] }) {
        val parent = g.frames[parentId] ?: continue
        val fromX = parent.x + parent.w
        val fromY = parent.y + parent.h / 2f
        val bracketStyle = appearance.layout == MapLayout.LOGIC_BRACKETS ||
            appearance.layout == MapLayout.NESTED_BRACKETS
        val spineX = fromX + when (appearance.layout) {
            MapLayout.NESTED_BRACKETS -> 42f
            MapLayout.LOGIC_BRACKETS -> 30f
            MapLayout.STEP_RAIL -> 24f
            else -> 14f
        }
        val ys = children.map { g.frames.getValue(it.id).let { f -> f.y + f.h / 2f } }
        paint.color = accent(g.topics.first { it.id == parentId })
        paint.alpha = if (bracketStyle) 190 else 170
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (bracketStyle) 2f else 1.5f
        canvas.drawLine(fromX, fromY, spineX, fromY, paint)
        canvas.drawLine(spineX, min(fromY, ys.min()), spineX, max(fromY, ys.max()), paint)
        children.forEach { child ->
            val f = g.frames.getValue(child.id)
            val y = f.y + f.h / 2f
            canvas.drawLine(spineX, y, f.x, y, paint)
            if (bracketStyle) {
                // A short vertical cap makes a sibling group read as one logic bracket.
                canvas.drawLine(f.x - 7f, y - 8f, f.x - 7f, y + 8f, paint)
            }
        }
        if (appearance.layout == MapLayout.NESTED_BRACKETS) {
            canvas.drawLine(spineX + 8f, ys.min() - 10f, spineX + 8f, ys.max() + 10f, paint)
            canvas.drawLine(spineX, ys.min() - 10f, spineX + 8f, ys.min() - 10f, paint)
            canvas.drawLine(spineX, ys.max() + 10f, spineX + 8f, ys.max() + 10f, paint)
        }
        paint.style = Paint.Style.FILL; paint.alpha = 255
    }
    for (topic in g.topics) {
        if (sharedSpines) continue
        val parent = g.parent[topic.id]?.let(g.frames::get) ?: continue
        val child = g.frames.getValue(topic.id)
        val lineBounds = RectF(
            min(parent.x, child.x) - 20f, min(parent.y, child.y) - 20f,
            max(parent.x + parent.w, child.x + child.w) + 20f,
            max(parent.y + parent.h, child.y + child.h) + 20f
        )
        if (!RectF.intersects(viewport, lineBounds)) continue
        val fromX = parent.x + parent.w
        val toX = child.x
        val fromY = parent.y + parent.h / 2f
        val toY = child.y + child.h / 2f
        val path = Path().apply {
            moveTo(fromX, fromY)
            when (appearance.layout) {
                MapLayout.FLOW_FORKS, MapLayout.SCULPTED_BRANCHES -> {
                    val curve = (toX - fromX) *
                        if (appearance.layout == MapLayout.SCULPTED_BRANCHES) .40f else .52f
                    cubicTo(fromX + curve, fromY, toX - curve, toY, toX, toY)
                }
                MapLayout.ROUNDED_FORKS -> {
                    val gap = toX - fromX
                    cubicTo(fromX + gap * .26f, fromY,
                        fromX + gap * .28f, toY, toX, toY)
                }
                MapLayout.FAN_FORKS -> {
                    val gap = toX - fromX
                    cubicTo(fromX + gap * .48f, fromY,
                        fromX + gap * .42f, toY, toX, toY)
                }
                MapLayout.LOOP_FORKS -> {
                    val gap = toX - fromX
                    cubicTo(fromX + gap * .82f, fromY,
                        fromX + gap * .12f, toY, toX, toY)
                }
                MapLayout.FACETED_PATHS -> {
                    val middle = fromX + (toX - fromX) * .48f
                    lineTo(middle, fromY + (toY - fromY) * .55f)
                    lineTo(toX, toY)
                }
                MapLayout.CARD_CASCADE -> {
                    val step = fromX + (toX - fromX) * .42f
                    lineTo(step, fromY)
                    lineTo(step, toY)
                    lineTo(toX, toY)
                }
                else -> lineTo(toX, toY)
            }
        }
        paint.color = accent(topic); paint.alpha = 210
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (appearance.layout == MapLayout.FLOW_FORKS ||
            appearance.layout == MapLayout.ROUNDED_FORKS ||
            appearance.layout == MapLayout.SCULPTED_BRANCHES ||
            appearance.layout == MapLayout.FAN_FORKS ||
            appearance.layout == MapLayout.LOOP_FORKS) 2.8f else 1.8f
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL; paint.alpha = 255
    }

    for (topic in g.topics) {
        val f = g.frames.getValue(topic.id)
        val r = RectF(f.x, f.y, f.x + f.w, f.y + f.h)
        if (!RectF.intersects(viewport, r)) continue
        // The expanded node is a Compose overlay: formatted text and a swipeable carousel cannot be
        // drawn here without reimplementing text layout and paging by hand. Its connectors are
        // still drawn above, from the reflowed frame, so the geometry stays in one place.
        if (topic.id == expandedId) continue
        val color = accent(topic)
        val selected = selectedId == topic.id
        val finish = appearance.nodeFinishes[topic.id] ?: appearance.boxFinish
        val isLeaf = g.topics.none { g.parent[it.id] == topic.id }
        // While a branch is lifted, the nodes that would refuse it recede.
        val dimmed = dragId != null && topic.id != dragId && topic.id != hoverId
        val radius = when (appearance.layout) {
            MapLayout.FLOW_FORKS -> min(27f, f.h / 2f)
            MapLayout.LOGIC_BRACKETS -> 5f
            MapLayout.CARD_CASCADE -> 12f
            MapLayout.RIGHT_TREE -> 17f
            MapLayout.STEP_RAIL -> 12f
            MapLayout.SECTION_BANDS -> 15f
            MapLayout.NESTED_BRACKETS -> 5f
            MapLayout.ROUNDED_FORKS -> if (isLeaf) 8f else min(27f, f.h / 2f)
            MapLayout.SCULPTED_BRANCHES -> 19f
            MapLayout.CLOUD_OUTCOMES -> if (isLeaf) 23f else 4f
            MapLayout.FAN_FORKS -> min(25f, f.h / 2f)
            MapLayout.FACETED_PATHS -> 0f
            MapLayout.LOOP_FORKS -> if (isLeaf) 16f else 23f
        }
        if (appearance.layout == MapLayout.CARD_CASCADE) {
            paint.shader = null
            paint.color = androidx.core.graphics.ColorUtils.blendARGB(
                android.graphics.Color.WHITE, color, .13f)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(RectF(r.left + 4f, r.top + 4f, r.right + 4f, r.bottom + 4f),
                radius, radius, paint)
        }
        // Presentation only: tint follows the existing palette/custom node colour.
        // Clear the shader immediately so connectors, text and affordances retain their colours.
        paint.shader = android.graphics.LinearGradient(
            r.left, r.top, r.right, r.bottom,
            intArrayOf(
                android.graphics.Color.rgb(253, 255, 252),
                androidx.core.graphics.ColorUtils.blendARGB(android.graphics.Color.WHITE, color, if (selected) .12f else .05f),
                androidx.core.graphics.ColorUtils.blendARGB(android.graphics.Color.WHITE, color, if (selected) .25f else .14f),
            ), floatArrayOf(0f, .45f, 1f), android.graphics.Shader.TileMode.CLAMP,
        )
        if (finish.isHandDrawn()) drawHandDrawnBox(canvas, r, paint, finish, selected)
        else drawLayoutNode(canvas, r, appearance.layout, isLeaf, radius, paint)
        paint.shader = null
        if (finish != MapBoxFinish.SMOOTH && !finish.isHandDrawn()) {
            canvas.save()
            val clip = Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
            canvas.clipPath(clip)
            paint.style = Paint.Style.FILL
            when (finish) {
                MapBoxFinish.FROSTED, MapBoxFinish.GLASS, MapBoxFinish.EMBOSSED -> {
                    paint.color = android.graphics.Color.WHITE
                    paint.alpha = if (finish == MapBoxFinish.GLASS) 105 else 65
                    canvas.drawRect(r.left, r.top, r.right, r.top + r.height() * .42f, paint)
                    if (finish == MapBoxFinish.EMBOSSED) {
                        paint.color = color; paint.alpha = 35
                        canvas.drawRect(r.left, r.bottom - 5f, r.right, r.bottom, paint)
                    }
                }
                MapBoxFinish.PAPER, MapBoxFinish.LINEN, MapBoxFinish.GRAIN -> {
                    paint.color = color
                    paint.alpha = if (finish == MapBoxFinish.GRAIN) 38 else 24
                    val step = if (finish == MapBoxFinish.GRAIN) 10f else 7f
                    var x = r.left + 4f
                    while (x < r.right) {
                        var y = r.top + 4f
                        while (y < r.bottom) {
                            if (finish == MapBoxFinish.GRAIN) canvas.drawCircle(x, y, .9f, paint)
                            else canvas.drawLine(x, y, x + 2f, y, paint)
                            y += step
                        }
                        x += step
                    }
                    if (finish == MapBoxFinish.LINEN) {
                        paint.alpha = 18
                        x = r.left + 4f
                        while (x < r.right) { canvas.drawLine(x, r.top, x, r.bottom, paint); x += step }
                    }
                }
                MapBoxFinish.PENCIL_HATCH -> {
                    // Fine, deterministic diagonal pencil marks, clipped to the box. Keeping
                    // them translucent leaves the title and the chosen palette readable.
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f
                    paint.color = color
                    paint.alpha = 36
                    var x = r.left - r.height()
                    while (x < r.right) {
                        canvas.drawLine(x, r.bottom, x + r.height(), r.top, paint)
                        x += 7f
                    }
                    paint.style = Paint.Style.FILL
                }
                MapBoxFinish.OUTLINE -> {
                    paint.color = android.graphics.Color.WHITE; paint.alpha = 210
                    canvas.drawRect(r, paint)
                }
                else -> Unit
            }
            canvas.restore()
            paint.alpha = 255
        }
        paint.style = Paint.Style.STROKE
        // 1.2f vs 2.4f was a sub-pixel difference at the 18-26% zoom Fit lands on, which is
        // why a tap looked like it did nothing. A selection now reads as one.
        paint.strokeWidth = if (selected) 2f else 1f
        paint.color = color
        paint.alpha = if (selected) 255 else if (dimmed) 55 else 130
        if (!finish.isHandDrawn())
            drawLayoutNode(canvas, r, appearance.layout, isLeaf, radius, paint)
        if (appearance.layout == MapLayout.FAN_FORKS && isLeaf) {
            paint.style = Paint.Style.FILL
            paint.alpha = if (dimmed) 55 else 180
            canvas.drawCircle(r.right + 5f, r.centerY(), 5f, paint)
            paint.alpha = 255
        }
        // The node under the finger: green when it would accept the branch, red when it would
        // refuse it, so an invalid target is visibly unavailable before release.
        if (topic.id == hoverId && dragId != null) {
            paint.strokeWidth = 6f
            paint.alpha = 255
            paint.color = if (hoverValid) android.graphics.Color.rgb(22, 163, 74)
            else android.graphics.Color.rgb(195, 57, 56)
            canvas.drawRoundRect(
                RectF(r.left - 5f, r.top - 5f, r.right + 5f, r.bottom + 5f),
                radius + 5f, radius + 5f, paint
            )
        }
        paint.style = Paint.Style.FILL; paint.alpha = 255
        paint.color = color
        canvas.drawCircle(f.x, f.y + f.h / 2f, 3f, paint)
        paint.color = android.graphics.Color.rgb(25, 34, 41)
        paint.typeface =
            android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        paint.textSize = 15f
        val titleLayout = android.text.StaticLayout.Builder.obtain(topic.title, 0, topic.title.length,
            android.text.TextPaint(paint), (f.w - 66f).toInt())
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        canvas.save()
        canvas.translate(f.x + 16f, f.y + (f.h - titleLayout.height) / 2f)
        titleLayout.draw(canvas)
        canvas.restore()
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
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

        // Disclosure is visible by default; the edit icon appears only on the selected node.
        if (showPreviewControls) drawPreviewAffordances(canvas, paint, f, topic.id in previewOwners, false, selected)
    }
}


/** Hit zone of the disclosure caret, in world units, relative to a node frame. */
internal fun caretZone(f: Frame) = RectF(f.x + f.w - 44f, f.y + f.h * .3f, f.x + f.w, f.y + f.h * .7f)

/** Hit zone of the node-level plus/pencil. Separate from the lower Add action, which is untouched. */
internal fun previewEditZone(f: Frame) =
    RectF(f.x + f.w - 44f, f.y, f.x + f.w, f.y + f.h * .3f)

/**
 * Right-side disclosure below the selected node's edit icon, without a text label.
 */
private fun drawPreviewAffordances(
    canvas: android.graphics.Canvas,
    paint: Paint,
    f: Frame,
    hasPreview: Boolean,
    expanded: Boolean,
    showEdit: Boolean = true,
) {
    val cy = f.y + f.h / 2f
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2f
    paint.color = android.graphics.Color.rgb(99, 114, 131)
    paint.alpha = 190
    // Caret: right-facing when collapsed, downward when expanded.
    val cx = f.x + f.w - 24f
    val path = Path()
    if (expanded) {
        path.moveTo(cx - 5f, cy - 2.5f); path.lineTo(cx, cy + 3f); path.lineTo(cx + 5f, cy - 2.5f)
    } else {
        path.moveTo(cx - 5f, cy - 2.5f); path.lineTo(cx, cy + 3f); path.lineTo(cx + 5f, cy - 2.5f)
    }
    canvas.drawPath(path, paint)
    if (!showEdit) { paint.style = Paint.Style.FILL; paint.alpha = 255; return }

    // Right affordance, very faint: a plus when there is nothing yet, a pencil when there is.
    val ex = f.x + f.w - 24f
    val editY = f.y + f.h * .15f
    paint.alpha = 110
    if (hasPreview) {
        canvas.drawLine(ex - 4f, editY + 4f, ex + 4f, editY - 4f, paint)
        canvas.drawLine(ex + 1.5f, editY - 5.5f, ex + 4.5f, editY - 2.5f, paint)
    } else {
        canvas.drawLine(ex - 4f, editY + 4f, ex + 4f, editY - 4f, paint)
        canvas.drawLine(ex + 1.5f, editY - 5.5f, ex + 4.5f, editY - 2.5f, paint)
    }
    paint.style = Paint.Style.FILL
    paint.alpha = 255
}
