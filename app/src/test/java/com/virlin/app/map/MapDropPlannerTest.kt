package com.virlin.app.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.virlin.app.ui.map.MapDropKind
import com.virlin.app.ui.map.MapDropNode
import com.virlin.app.ui.map.planMapDrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The three drop zones.
 *
 * A drop has to answer two different questions with one finger: which parent, and which position
 * among that parent's children. These tests pin the boundary between those answers, the slots at
 * the two ends of a group where no box sits, and the rule that keeps a release on empty canvas a
 * cancel rather than a silent reorder.
 *
 * Geometry is a vertical stack of four sibling rows, sized like the real map at a readable zoom.
 */
class MapDropPlannerTest {

    private val density = 2.625f          // Pixel 8
    private val rowHeight = 60f
    private val gap = 40f
    private val left = 300f
    private val right = 600f

    /** Rows at y = 0, 100, 200, 300 (top edges), each 60 tall. */
    private fun rows(count: Int = 4) = (0 until count).map { index ->
        MapDropNode(
            id = "s$index",
            parentId = "parent",
            bounds = Rect(
                left = left,
                top = index * (rowHeight + gap),
                right = right,
                bottom = index * (rowHeight + gap) + rowHeight,
            ),
            siblingOrder = index,
            acceptsChildren = true,
        )
    }

    private fun plan(
        pointer: Offset,
        nodes: List<MapDropNode> = rows(),
        source: String = "dragged",
        into: (String, String) -> Boolean = { _, _ -> true },
        beside: (String, String) -> Boolean = { _, _ -> true },
    ) = planMapDrop(pointer, source, nodes, into, beside, density)

    private fun centreOf(node: MapDropNode) = node.bounds.center

    // ------------------------------------------------------------------ INTO

    @Test fun theMiddleOfABoxAttachesInsideIt() {
        val target = rows()[1]
        val result = plan(centreOf(target))!!
        assertEquals(MapDropKind.INTO, result.kind)
        assertEquals("s1", result.targetId)
        assertEquals("s1", result.destinationParentId)
        // INTO carries no line to draw.
        assertNull(result.indicatorStart)
    }

    @Test fun aNodeThatRefusesChildrenIsNeverAnIntoTarget() {
        val target = rows()[1]
        val result = plan(centreOf(target), into = { _, id -> id != "s1" })!!
        // It falls through to an insertion instead of attaching.
        assertEquals(setOf(MapDropKind.BEFORE, MapDropKind.AFTER).contains(result.kind), true)
    }

    // ------------------------------------------------------------------ BEFORE / AFTER

    @Test fun nearTheTopEdgeOfABoxInsertsAboveIt() {
        val target = rows()[2]
        // 6px below the top edge: inside the box, but well above the centre band.
        val result = plan(Offset(target.bounds.center.x, target.bounds.top + 6f))!!
        assertEquals(MapDropKind.BEFORE, result.kind)
        assertEquals(2, result.insertionIndex)
        assertNotNull(result.indicatorStart)
    }

    @Test fun nearTheBottomEdgeOfABoxInsertsBelowIt() {
        val target = rows()[1]
        val result = plan(Offset(target.bounds.center.x, target.bounds.bottom - 6f))!!
        // Below s1's centre is the slot in front of s2.
        assertEquals(MapDropKind.BEFORE, result.kind)
        assertEquals(2, result.insertionIndex)
    }

    @Test fun theGapBetweenTwoSiblingsIsAnInsertionPoint() {
        val rows = rows()
        val midGap = (rows[1].bounds.bottom + rows[2].bounds.top) / 2f
        val result = plan(Offset(rows[1].bounds.center.x, midGap))!!
        assertEquals(MapDropKind.BEFORE, result.kind)
        assertEquals(2, result.insertionIndex)
        // The line sits in the gap and spans the whole column, horizontally.
        assertEquals(midGap, result.indicatorStart!!.y, 0.5f)
        assertEquals(midGap, result.indicatorEnd!!.y, 0.5f)
        assertEquals(left - 8f * density, result.indicatorStart!!.x, 0.5f)
        assertEquals(right + 8f * density, result.indicatorEnd!!.x, 0.5f)
    }

    /** Slot 0: above the first box, where no sibling sits. */
    @Test fun thereIsASlotBeforeTheFirstSibling() {
        val rows = rows()
        val result = plan(Offset(rows[0].bounds.center.x, rows[0].bounds.top - 12f))!!
        assertEquals(MapDropKind.BEFORE, result.kind)
        assertEquals(0, result.insertionIndex)
    }

    /** Slot N: below the last box. This is the only way to say "last" without appending blindly. */
    @Test fun thereIsASlotAfterTheLastSibling() {
        val rows = rows()
        val result = plan(Offset(rows[3].bounds.center.x, rows[3].bounds.bottom + 12f))!!
        assertEquals(MapDropKind.AFTER, result.kind)
        assertEquals(4, result.insertionIndex)
        assertEquals("s3", result.targetId)
    }

    // ------------------------------------------------------------------ bounds

    @Test fun aReleaseFarBelowTheGroupPlansNothing() {
        val rows = rows()
        assertNull(plan(Offset(rows[0].bounds.center.x, rows[3].bounds.bottom + 400f)))
    }

    @Test fun aReleaseFarAboveTheGroupPlansNothing() {
        val rows = rows()
        assertNull(plan(Offset(rows[0].bounds.center.x, rows[0].bounds.top - 400f)))
    }

    @Test fun aReleaseFarToTheSidePlansNothing() {
        val rows = rows()
        assertNull(plan(Offset(right + 600f, rows[1].bounds.center.y)))
    }

    @Test fun theSourceIsNeverItsOwnTarget() {
        val rows = rows()
        // Dragging s1: its own box must not plan anything against itself.
        val result = plan(centreOf(rows[1]), source = "s1")
        assertEquals(true, result == null || result.targetId != "s1")
    }

    /** A descendant the domain refuses is not offered as a neighbour either. */
    @Test fun siblingsTheRulesRefuseAreExcludedFromTheSlotMath() {
        val rows = rows()
        val midGap = (rows[1].bounds.bottom + rows[2].bounds.top) / 2f
        val result = plan(
            Offset(rows[1].bounds.center.x, midGap),
            into = { _, _ -> false },
            beside = { _, id -> id != "s0" },   // s0 is inside the moving branch
        )!!
        // With s0 gone the group is s1,s2,s3 and the gap is the slot in front of s2, index 1.
        assertEquals(1, result.insertionIndex)
    }

    // ------------------------------------------------------------------ non-vertical layouts

    /**
     * This map's balanced and radial layouts fan a parent's children around it rather than
     * stacking them, so the slot between two siblings is a vertical line and the slot maths has
     * to run along x. A y-only planner silently mis-slots every drop in those layouts.
     */
    @Test fun aGroupLaidOutSideBySideSlotsAlongXAndDrawsAVerticalLine() {
        val row = listOf(
            MapDropNode("a", "parent", Rect(0f, 500f, 180f, 560f), 0, true),
            MapDropNode("b", "parent", Rect(300f, 500f, 480f, 560f), 1, true),
            MapDropNode("c", "parent", Rect(600f, 500f, 780f, 560f), 2, true),
        )
        // In the gap between a and b, vertically level with the row.
        val result = plan(Offset(240f, 530f), nodes = row, into = { _, _ -> false })!!
        assertEquals(MapDropKind.BEFORE, result.kind)
        assertEquals("b", result.targetId)
        assertEquals(1, result.insertionIndex)
        // The line is VERTICAL: constant x, spanning the row's height.
        val start = result.indicatorStart!!
        val end = result.indicatorEnd!!
        assertEquals(start.x, end.x, 0.5f)
        assertEquals(240f, start.x, 1f)
        assertEquals(true, end.y > start.y)
    }

    @Test fun aSideBySideGroupStillHasASlotBeforeTheFirstAndAfterTheLast() {
        val row = listOf(
            MapDropNode("a", "parent", Rect(0f, 500f, 180f, 560f), 0, true),
            MapDropNode("b", "parent", Rect(300f, 500f, 480f, 560f), 1, true),
        )
        val first = plan(Offset(-10f, 530f), nodes = row, into = { _, _ -> false })!!
        assertEquals(0, first.insertionIndex)
        val last = plan(Offset(500f, 530f), nodes = row, into = { _, _ -> false })!!
        assertEquals(MapDropKind.AFTER, last.kind)
        assertEquals(2, last.insertionIndex)
    }

    // ------------------------------------------------------------------ stored order wins

    /**
     * The planner must never let the drawing decide the database order. Here the boxes are drawn
     * bottom-to-top relative to their stored order, and the canonical slot has to follow the
     * STORED sequence, not the visual one.
     */
    @Test fun theCanonicalSlotFollowsStoredOrderNotScreenPosition() {
        val scrambled = listOf(
            MapDropNode("a", "parent", Rect(left, 0f, right, rowHeight), siblingOrder = 2, acceptsChildren = true),
            MapDropNode("b", "parent", Rect(left, 100f, right, 100f + rowHeight), siblingOrder = 0, acceptsChildren = true),
            MapDropNode("c", "parent", Rect(left, 200f, right, 200f + rowHeight), siblingOrder = 1, acceptsChildren = true),
        )
        // Drop in the gap between the boxes drawn first and second, i.e. in front of "b".
        val result = plan(Offset((left + right) / 2f, 80f), nodes = scrambled, into = { _, _ -> false })!!
        assertEquals(MapDropKind.BEFORE, result.kind)
        assertEquals("b", result.targetId)
        // "b" is stored FIRST, so the canonical slot is 0 even though it is drawn second.
        assertEquals(0, result.insertionIndex)
    }
}
