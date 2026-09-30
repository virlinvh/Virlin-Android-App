package com.virlin.app.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Where a dragged branch would land.
 *
 * INTO attaches the branch as a child of the node under the finger. BEFORE and AFTER are
 * positions within a sibling group — they are how a user says "third, not last".
 */
enum class MapDropKind { BEFORE, AFTER, INTO }

/**
 * One node as the planner sees it: a rectangle in CANVAS-LOCAL SCREEN PIXELS, the same space the
 * drag pointer is reported in, so no conversion happens inside the planner.
 *
 * Sibling grouping is by [parentId] alone. In this map a node's parent IS its container — the
 * project root, a workstream, or another task — so the parent already determines the workstream;
 * there is no case where two nodes share a parent but belong to different streams.
 */
internal data class MapDropNode(
    val id: String,
    val parentId: String?,
    val bounds: Rect,
    /** The node's canonical sibling order, read from the domain — never from its y position. */
    val siblingOrder: Int,
    val acceptsChildren: Boolean,
)

/**
 * A planned drop.
 *
 * [insertionIndex] is a position within the sibling group **as the planner saw it on screen**.
 * It is deliberately NOT sent to the domain: the caller resolves it against the canonical
 * sibling order into a stable anchor id, because screen order and stored order are two different
 * things and only the stored one may decide where a record goes.
 */
data class MapDropPlan(
    val kind: MapDropKind,
    val targetId: String,
    val destinationParentId: String?,
    val insertionIndex: Int?,
    /**
     * The insertion line, in canvas-local screen pixels. Two points rather than a y plus an
     * x-range, because this map does not always stack siblings vertically: its balanced and
     * radial layouts spread a parent's children around it, and there the slot between two
     * siblings is a VERTICAL line. Null for INTO, which draws a highlight instead.
     */
    val indicatorStart: Offset?,
    val indicatorEnd: Offset?,
)

/**
 * Plans a drop visually. It reads geometry and answers a question; it mutates nothing.
 *
 * A hit in the middle band of a box attaches inside it. Nearer the top or bottom edge — or in the
 * empty gap between two siblings — chooses an insertion slot instead, resolved by the siblings'
 * vertical midpoints. Slot 0 is before the first sibling and slot N after the last, so the ends
 * of a group are reachable even though no box sits there.
 *
 * Returns null when the finger is nowhere meaningful, which is what makes an empty-canvas release
 * a cancel rather than a silent reorder.
 *
 * Sibling rows are assumed to stack vertically, which is how this map's tree layouts arrange
 * them. A layout that placed siblings side by side would need its own axis choice here.
 */
internal fun planMapDrop(
    pointer: Offset,
    sourceId: String,
    nodes: List<MapDropNode>,
    canPlaceInto: (sourceId: String, targetId: String) -> Boolean,
    canPlaceBeside: (sourceId: String, siblingId: String) -> Boolean,
    density: Float,
): MapDropPlan? {
    val candidates = nodes.filter { it.id != sourceId }
    val rowReach = 34f * density
    val horizontalReach = 42f * density

    // Centre band: attach as a child. It is narrower than the box so the top and bottom
    // eighths stay available for insertion — three zones on one small target.
    val inside = candidates
        .asSequence()
        .filter { it.acceptsChildren && canPlaceInto(sourceId, it.id) }
        .filter { node ->
            val b = node.bounds
            pointer.x in b.left..b.right &&
                pointer.y in (b.top + b.height * 0.28f)..(b.bottom - b.height * 0.28f)
        }
        .minByOrNull { node -> hypot(pointer.x - node.bounds.center.x, pointer.y - node.bounds.center.y) }

    if (inside != null) {
        return MapDropPlan(
            kind = MapDropKind.INTO,
            targetId = inside.id,
            destinationParentId = inside.id,
            insertionIndex = null,
            indicatorStart = null, indicatorEnd = null,
        )
    }

    // The sibling column nearest the finger. Works in the gap BETWEEN boxes, where no box is
    // under the pointer at all.
    val anchor = candidates
        .asSequence()
        .filter { canPlaceBeside(sourceId, it.id) }
        .filter { pointer.x >= it.bounds.left - horizontalReach && pointer.x <= it.bounds.right + horizontalReach }
        .minByOrNull { node ->
            val b = node.bounds
            val dx = when {
                pointer.x < b.left -> b.left - pointer.x
                pointer.x > b.right -> pointer.x - b.right
                else -> 0f
            }
            hypot(dx, abs(pointer.y - b.center.y))
        } ?: return null

    // Ordered by the CANONICAL sibling order, with y only as a tie-break. Sorting by y first
    // would let the drawing decide the database order.
    val siblings = candidates
        .filter { it.parentId == anchor.parentId && canPlaceBeside(sourceId, it.id) }
        .sortedWith(compareBy<MapDropNode> { it.siblingOrder }.thenBy { it.bounds.center.y })

    if (siblings.isEmpty()) return null

    // Which way does this group actually run? A right-hand tree stacks siblings vertically, but
    // the balanced and radial layouts fan them around the parent, where the slot between two
    // siblings is a vertical line. Asking the group's own bounding box costs nothing and keeps
    // the planner correct for every layout instead of just the common one.
    val minLeft = siblings.minOf { it.bounds.left }
    val maxRight = siblings.maxOf { it.bounds.right }
    val minTop = siblings.minOf { it.bounds.top }
    val maxBottom = siblings.maxOf { it.bounds.bottom }
    // Compared on the CENTRES, not the bounding box. A vertical column of wide boxes spans more
    // width than height, so a bounding-box test would call it horizontal and then try to slot
    // between siblings that all share the same x - which is no ordering at all.
    val centreXSpread = siblings.maxOf { it.bounds.center.x } - siblings.minOf { it.bounds.center.x }
    val centreYSpread = siblings.maxOf { it.bounds.center.y } - siblings.minOf { it.bounds.center.y }
    val vertical = centreYSpread >= centreXSpread

    fun axisOf(node: MapDropNode) = if (vertical) node.bounds.center.y else node.bounds.center.x
    val along = if (vertical) pointer.y else pointer.x
    val groupStart = if (vertical) minTop else minLeft
    val groupEnd = if (vertical) maxBottom else maxRight

    // Bound the active region: a release far out on empty canvas must never reorder anything.
    if (along < groupStart - rowReach || along > groupEnd + rowReach) return null

    // Slot 0 is before the first sibling; slot N is after the last.
    val byScreen = siblings.sortedBy(::axisOf)
    val slotOnScreen = byScreen.indexOfFirst { along < axisOf(it) }
        .let { if (it == -1) byScreen.size else it }

    val before = byScreen.getOrNull(slotOnScreen)
    val after = byScreen.getOrNull(slotOnScreen - 1)

    // The slot expressed in canonical terms: how many siblings sit at or before this point in
    // the STORED order. This is what the caller turns into an anchor id.
    val canonicalSlot = when {
        before != null -> siblings.indexOfFirst { it.id == before.id }
        else -> siblings.size
    }

    // Where the line sits along the group's axis: in the gap between two siblings, or just
    // outside the group at either end.
    val at = when {
        before != null && after != null ->
            if (vertical) (after.bounds.bottom + before.bounds.top) / 2f
            else (after.bounds.right + before.bounds.left) / 2f
        before != null -> (if (vertical) before.bounds.top else before.bounds.left) - 7f * density
        else -> {
            val node = requireNotNull(after)
            (if (vertical) node.bounds.bottom else node.bounds.right) + 7f * density
        }
    }
    // The line spans the group ACROSS its axis, so it reads as a slot in that column or row.
    val pad = 8f * density
    val start = if (vertical) Offset(minLeft - pad, at) else Offset(at, minTop - pad)
    val end = if (vertical) Offset(maxRight + pad, at) else Offset(at, maxBottom + pad)
    val reference = before ?: requireNotNull(after)

    return MapDropPlan(
        kind = if (before != null) MapDropKind.BEFORE else MapDropKind.AFTER,
        targetId = reference.id,
        destinationParentId = reference.parentId,
        insertionIndex = canonicalSlot,
        indicatorStart = start,
        indicatorEnd = end,
    )
}

/**
 * Projects the laid-out map into the planner's screen-pixel space.
 *
 * The same transform the canvas paints with, applied once, so the rectangles the planner tests
 * against are exactly the boxes the user can see and the pointer shares their coordinate space.
 */
internal fun MapGeometry.dropNodes(
    byId: Map<String, MapTopic>,
    density: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): List<MapDropNode> {
    val scale = density * zoom
    return frames.values.mapNotNull { frame ->
        val topic = byId[frame.id] ?: return@mapNotNull null
        MapDropNode(
            id = frame.id,
            parentId = topic.parentId,
            bounds = Rect(
                left = frame.x * scale + offsetX,
                top = frame.y * scale + offsetY,
                right = (frame.x + frame.w) * scale + offsetX,
                bottom = (frame.y + frame.h) * scale + offsetY,
            ),
            siblingOrder = topic.order,
            // Every kind in this schema can own children: a project holds standalone tasks, a
            // workstream holds tasks, a task holds subtasks. The domain rules do the real
            // filtering through canPlaceInto.
            acceptsChildren = true,
        )
    }
}
