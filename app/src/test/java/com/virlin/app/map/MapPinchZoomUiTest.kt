package com.virlin.app.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapTopic
import com.virlin.app.ui.map.MindMapCanvasTag
import com.virlin.app.ui.map.VirlinProjectMindMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Pinching scales the map about the fingers, and the on-map controls follow the boxes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MapPinchZoomUiTest {
    @get:Rule val compose = createComposeRule()

    private val topics = listOf(
        MapTopic("p", null, "Project", kind = MapKind.PROJECT),
        MapTopic("a", "p", "First task", kind = MapKind.TASK),
        MapTopic("b", "p", "Second task", kind = MapKind.TASK),
    )

    private fun foldCentre(): Offset =
        compose.onNodeWithTag("map_branch_p").fetchSemanticsNode().layoutInfo.coordinates.boundsInRoot().center

    @Test fun pinchOutZoomsAboutTheFingersAndPinchInReturns() {
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", topics, MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = {}, onBack = {}, onExportXmind = {},
                    onExportVirlin = {}, onOpenVirlinMap = {})
            }
        }
        compose.waitForIdle()
        val canvas = compose.onNodeWithTag(MindMapCanvasTag).fetchSemanticsNode().layoutInfo.coordinates.boundsInRoot()
        val before = foldCentre()
        compose.onNodeWithTag(MindMapCanvasTag).performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(160f, 0f),
                center + Offset(40f, 0f), center + Offset(160f, 0f), durationMillis = 400)
        }
        compose.waitForIdle()
        val zoomed = foldCentre()
        val pivot = canvas.center
        val ratio = (zoomed - pivot).getDistance() / (before - pivot).getDistance()
        assertTrue("pinch out should enlarge distances from the fingers, was $ratio", ratio > 1.5f)

        compose.onNodeWithTag(MindMapCanvasTag).performTouchInput {
            pinch(center - Offset(160f, 0f), center - Offset(40f, 0f),
                center + Offset(160f, 0f), center + Offset(40f, 0f), durationMillis = 400)
        }
        compose.waitForIdle()
        val back = foldCentre()
        assertEquals(before.x, back.x, maxOf(12f, abs(before.x - pivot.x) * .25f))
        assertEquals(before.y, back.y, maxOf(12f, abs(before.y - pivot.y) * .25f))
    }
}
