package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskListCollapseUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun rootTasksScrollBeforeHeaderReturns() = checkLevel(emptyList())
    @Test fun deeplyNestedSubtasksScrollBeforeHeaderReturns() = checkLevel(listOf("parent", "nested"))

    private fun checkLevel(path: List<String>) {
        val ancestors = listOf(StreamTaskUi("parent", null, "Parent", 0, childCount = 1),
            StreamTaskUi("nested", "parent", "Nested", 0, childCount = 30))
        val tasks = List(30) { StreamTaskUi("t$it", path.lastOrNull(), "Item $it", it) }
        val actions = Proxy.newProxyInstance(StreamTaskActions::class.java.classLoader,
            arrayOf(StreamTaskActions::class.java)) { _, _, _ -> null } as StreamTaskActions
        val ui = StreamTasksUi("w", "Workstream", "Project", 0, StreamExecutionMode.HUMAN,
            StreamExecutionMode.HUMAN, if (path.isEmpty()) tasks else ancestors + tasks)
        compose.setContent { VirlinStreamTasks(ui, actions, initialPath = path) }
        fun height() = compose.onNodeWithTag("hierarchy_collapsible_details").fetchSemanticsNode().boundsInRoot.height
        val before = height()
        compose.onNodeWithTag("hierarchy_task_list").performTouchInput { swipeUp() }
        val collapsed = height()
        assertTrue(collapsed < before)
        compose.onNodeWithTag("hierarchy_task_list").performScrollToIndex(29)
        compose.onNodeWithTag("hierarchy_task_list").performTouchInput {
            swipe(center, center.copy(y = center.y + 80f), durationMillis = 600)
        }
        assertTrue("List must return first", height() <= collapsed + 1f)
        repeat(12) { compose.onNodeWithTag("hierarchy_task_list").performTouchInput { swipeDown() } }
        assertTrue("Header returns at list start", height() > collapsed)
        compose.onNodeWithText("Item 0").assertIsDisplayed()
        compose.onAllNodesWithText("Workstream", substring = false).assertCountEquals(2)
    }
}
