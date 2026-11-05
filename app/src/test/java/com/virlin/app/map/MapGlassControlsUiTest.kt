package com.virlin.app.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.map.MapGlassControls
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapGlassControlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactHeightKeepsFocusExitAvailable() {
        val focus = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                MapGlassControls(false, onFit = {}, onZoomOut = {}, onZoomIn = {},
                    onTasksOnly = {}, onExpandAll = {}, height = 220.dp,
                    focusMode = focus.value, onToggleFocus = { focus.value = !focus.value })
            }
        }
        compose.onNodeWithTag("map_control_stack").assertIsDisplayed().performClick()
        compose.onNodeWithTag("map_control_focus").assertIsDisplayed().assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(false, focus.value) }
    }

    @Test fun lockRequiresSelectionAndCanBeToggledBackOff() {
        val selectable = mutableStateOf(false)
        val locked = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                MapGlassControls(false, onFit = {}, onZoomOut = {}, onZoomIn = {},
                    onTasksOnly = {}, onExpandAll = {}, lockEnabled = selectable.value,
                    locked = locked.value, onToggleLock = { locked.value = !locked.value })
            }
        }
        compose.onNodeWithTag("map_control_stack").performClick()
        compose.onNodeWithTag("map_control_lock").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { selectable.value = true }
        compose.onNodeWithTag("map_control_lock").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("map_control_lock").performClick().assertIsNotSelected()
    }

    @Test fun launcherOpensVerticalColumnAboveAndPreservesAnchor() {
        val tasksOnly = mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                MapGlassControls(tasksOnly.value,
                    onFit = { calls += "fit" }, onZoomOut = { calls += "out" },
                    onZoomIn = { calls += "in" },
                    onTasksOnly = { tasksOnly.value = !tasksOnly.value },
                    onExpandAll = { tasksOnly.value = false; calls += "all" },
                    onCollapseAll = { calls += "fold" })
            }
        }
        compose.onNodeWithTag("map_control_fit").assertDoesNotExist()
        val closed = compose.onNodeWithTag("map_control_stack").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("map_control_stack").performClick()
        val stack = compose.onNodeWithTag("map_control_stack").fetchSemanticsNode().boundsInRoot
        assertEquals(closed, stack)
        val first = compose.onNodeWithTag("map_control_lock").fetchSemanticsNode().boundsInRoot
        assertEquals(stack.left, first.left)
        var previousBottom = first.top
        listOf("lock", "collapse", "expand", "tasks", "plus", "minus", "fit").forEach { kind ->
            val bounds = compose.onNodeWithTag("map_control_$kind").fetchSemanticsNode().boundsInRoot
            assertEquals(stack.right, bounds.right)
            assert(bounds.top >= previousBottom)
            assert(bounds.bottom < stack.top)
            previousBottom = bounds.bottom
        }
        compose.onNodeWithTag("map_control_tasks").performClick().assertIsSelected()
        compose.onNodeWithTag("map_control_collapse").performClick()
        compose.onNodeWithTag("map_control_collapse").performClick()
        compose.onNodeWithTag("map_control_expand").performClick()
        compose.onNodeWithTag("map_control_tasks").assertIsNotSelected()
        compose.onNodeWithTag("map_control_fit").performClick()
        compose.onNodeWithTag("map_control_minus").performClick()
        compose.onNodeWithTag("map_control_plus").performClick()
        compose.runOnIdle { assertEquals(listOf("fold", "fold", "all", "fit", "out", "in"), calls) }
        compose.onNodeWithTag("map_control_stack").performClick()
        compose.onNodeWithTag("map_control_fit").assertDoesNotExist()
    }
}
