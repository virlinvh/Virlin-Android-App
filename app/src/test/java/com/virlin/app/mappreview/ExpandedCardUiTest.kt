package com.virlin.app.mappreview

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.domain.mappreview.*
import com.virlin.app.ui.map.preview.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpandedCardUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun contentAndRightAlignedControlsRemainFunctional() {
        val calls = mutableListOf<String>()
        val preview = MapNodePreview(ownerId = "task", description = FormattedText("Saved node text"),
            images = listOf(MapPreviewImage("image", "task/image.png", 100, 80, 900)))
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                ExpandedNodeCard(with(density) { Rect(0f, 0f, 240.dp.toPx(), 400.dp.toPx()) },
                    "Example title", preview, density, { calls += "collapse" }, { calls += "edit" })
            }
        }
        compose.onNodeWithText("Saved node text").assertIsDisplayed()
        compose.onNodeWithContentDescription("Image 1 of 1").assertExists()
        compose.onNodeWithText("Progress").assertDoesNotExist()
        compose.onNodeWithText("To do").assertDoesNotExist()
        val edit = compose.onNodeWithTag(MapPreviewEditFromCardTag).fetchSemanticsNode().boundsInRoot
        val collapse = compose.onNodeWithTag(MapPreviewCollapseTag).fetchSemanticsNode().boundsInRoot
        assertEquals(edit.left, collapse.left)
        assertTrue(collapse.top >= edit.bottom)
        compose.onNodeWithTag(MapPreviewEditFromCardTag).performClick()
        compose.onNodeWithTag(MapPreviewCollapseTag).performClick()
        compose.runOnIdle { assertEquals(listOf("edit", "collapse"), calls) }
    }
}
