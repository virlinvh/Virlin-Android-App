package com.virlin.app.map

import androidx.compose.ui.geometry.Offset
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.map.MapLayout
import com.virlin.app.ui.map.arrange
import com.virlin.app.ui.map.frameNear
import com.virlin.app.ui.map.projectMapTopics
import com.virlin.app.ui.map.touchSlopWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The map's touch target.
 *
 * At Fit zoom a task box is about 51 x 18 dp on a Pixel 8 — its height is roughly a third of
 * Android's 48 dp minimum — so an exact-containment hit test makes nodes very hard to hit with a
 * finger even though they look hittable. These tests pin the forgiving behaviour AND the rule
 * that keeps it safe: a near miss resolves to the CLOSEST box, never a neighbour further away.
 */
class MapTouchTargetTest {

    private val t0: Instant = Instant.parse("2026-09-28T09:00:00Z")
    private val project = Project(id = "p1", title = "Demo", createdAt = t0, updatedAt = t0)
    private val stream = WorkStream(
        id = "s1", title = "Stream", projectId = "p1", state = WorkStreamState.READY,
        executionPreference = ExecutionPreference.HUMAN, createdAt = t0, updatedAt = t0
    )

    private fun task(id: String, order: Int) = Task(
        id = id, title = id, projectId = "p1", workStreamId = "s1",
        order = order, createdAt = t0, updatedAt = t0
    )

    /** Three siblings, so the gap between adjacent boxes is exercised. */
    private val geometry = arrange(
        projectMapTopics(project, listOf(stream), (0 until 3).map { task("t$it", it) }),
        MapLayout.RIGHT_TREE
    )

    private fun frameOf(id: String) = geometry.frames.getValue(id)

    /** The Fit zoom actually recorded on the device. */
    private val fitZoom = 0.269f

    @Test fun aTouchInsideTheBoxAlwaysHitsThatBox() {
        val f = frameOf("t1")
        val centre = Offset(f.x + f.w / 2f, f.y + f.h / 2f)
        assertEquals("t1", geometry.frameNear(centre, touchSlopWorld(fitZoom))?.id)
    }

    @Test fun aNearMissJustBelowTheBoxStillHitsIt() {
        val f = frameOf("t1")
        // 4 dp below the box at Fit zoom — well inside a fingertip's contact patch, and the
        // kind of miss that made the map feel unresponsive. Beyond half the ~12 dp gap the
        // neighbour is genuinely the closer box and correctly wins instead; the forgiving zone
        // is bounded by the midpoint, which is what stops neighbours being confused.
        val justBelow = Offset(f.x + f.w / 2f, f.y + f.h + 4f / fitZoom)
        assertEquals("t1", geometry.frameNear(justBelow, touchSlopWorld(fitZoom))?.id)
    }

    @Test fun aTouchInTheGapPicksTheNearerNeighbourNotTheFurtherOne() {
        val upper = frameOf("t0")
        val lower = frameOf("t1")
        val gapTop = upper.y + upper.h
        val gapBottom = lower.y
        // Just under the upper box: the upper one must win.
        val nearUpper = Offset(upper.x + upper.w / 2f, gapTop + (gapBottom - gapTop) * .2f)
        assertEquals("t0", geometry.frameNear(nearUpper, touchSlopWorld(fitZoom))?.id)
        // Just above the lower box: the lower one must win.
        val nearLower = Offset(lower.x + lower.w / 2f, gapTop + (gapBottom - gapTop) * .8f)
        assertEquals("t1", geometry.frameNear(nearLower, touchSlopWorld(fitZoom))?.id)
    }

    @Test fun emptyCanvasFarFromAnyBoxStillHitsNothing() {
        val f = frameOf("t0")
        // A long way off to the side: a drop here must still cancel rather than snap somewhere.
        val faraway = Offset(f.x - 4000f, f.y - 4000f)
        assertNull(geometry.frameNear(faraway, touchSlopWorld(fitZoom)))
    }

    @Test fun theSlopShrinksAsYouZoomIn_soItIsAlwaysTheSameOnScreen() {
        // 12 dp of forgiveness whatever the zoom: large in world units when zoomed out, small
        // when zoomed in, which is what keeps precise work precise.
        assertEquals(12f / 0.269f, touchSlopWorld(0.269f), 0.01f)
        assertEquals(12f, touchSlopWorld(1f), 0.01f)
        assertEquals(4f, touchSlopWorld(3f), 0.01f)
    }

    @Test fun everyNodeIsReachableAtFitZoomWithARealisticFingerOffset() {
        // Aim at each box centre but land 8 dp low, as a fingertip's reported centre can.
        geometry.topics.forEach { topic ->
            val f = frameOf(topic.id)
            val aimed = Offset(f.x + f.w / 2f, f.y + f.h / 2f + 8f / fitZoom)
            assertNotNull("${topic.title} unreachable", geometry.frameNear(aimed, touchSlopWorld(fitZoom)))
        }
    }
}
