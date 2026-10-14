package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
    @Test fun rootHeaderAndToolbarBothStartTheSameScroll() = checkChromeScroll(emptyList())
    @Test fun nestedHeaderAndToolbarBothStartTheSameScroll() = checkChromeScroll(listOf("parent", "nested"))

    @Test fun backToParentRestoresItsListAndHeaderPosition() {
        val root = List(22) { index -> StreamTaskUi("root$index", null, "Root $index", index) }
        val children = List(22) { index -> StreamTaskUi("child$index", "root18", "Child $index", index) }
        val actions = Proxy.newProxyInstance(StreamTaskActions::class.java.classLoader,
            arrayOf(StreamTaskActions::class.java)) { _, _, _ -> null } as StreamTaskActions
        val ui = StreamTasksUi("w", "Workstream", "Project", 0, StreamExecutionMode.HUMAN,
            StreamExecutionMode.HUMAN, root + children)
        compose.setContent { VirlinStreamTasks(ui, actions) }
        fun headerHeight() = compose.onNodeWithTag("hierarchy_collapsible_details")
            .fetchSemanticsNode().boundsInRoot.height
        val expanded = headerHeight()
        compose.onNodeWithTag("hierarchy_task_list").performTouchInput { swipeUp() }
        val collapsed = headerHeight()
        assertTrue(collapsed < expanded)
        compose.onNodeWithTag("hierarchy_task_list").performScrollToIndex(18)
        compose.onNodeWithTag(taskRowTag("root18")).performClick()
        compose.onNodeWithTag("hierarchy_task_list").performScrollToIndex(18)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag(taskRowTag("root18")).assertIsDisplayed()
        assertTrue("Parent header should remain collapsed after Back", headerHeight() <= collapsed + 1f)
    }

    @Test fun compactAddButtonCanReopenAtDeepTaskLevel() {
        val tasks = listOf(StreamTaskUi("parent", null, "Parent", 0),
            StreamTaskUi("nested", "parent", "Nested", 0))
        val actions = Proxy.newProxyInstance(StreamTaskActions::class.java.classLoader,
            arrayOf(StreamTaskActions::class.java)) { _, _, _ -> null } as StreamTaskActions
        val ui = StreamTasksUi("w", "Workstream", "Project", 0, StreamExecutionMode.HUMAN,
            StreamExecutionMode.HUMAN, tasks)
        compose.setContent { VirlinStreamTasks(ui, actions, initialPath = listOf("parent", "nested")) }
        val button = compose.onNodeWithContentDescription("Add subtask")
        button.assertIsDisplayed().assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
        val list = compose.onNodeWithTag("hierarchy_task_list").fetchSemanticsNode().boundsInRoot
        val add = button.fetchSemanticsNode().boundsInRoot
        assertTrue("Subtask list should extend beneath the floating add button", add.top < list.bottom)
        repeat(2) {
            button.performClick()
            compose.onNodeWithText("Subtask name").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            button.assertIsDisplayed()
        }
    }

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

    private fun checkChromeScroll(path: List<String>) {
        val ancestors = listOf(StreamTaskUi("parent", null, "Parent", 0, childCount = 1),
            StreamTaskUi("nested", "parent", "Nested", 0, childCount = 20))
        val tasks = List(20) { StreamTaskUi("t$it", path.lastOrNull(), "Item $it", it) }
        val actions = Proxy.newProxyInstance(StreamTaskActions::class.java.classLoader,
            arrayOf(StreamTaskActions::class.java)) { _, _, _ -> null } as StreamTaskActions
        val ui = StreamTasksUi("w", "Workstream", "Project", 0, StreamExecutionMode.HUMAN,
            StreamExecutionMode.HUMAN, ancestors + tasks)
        compose.setContent { VirlinStreamTasks(ui, actions, initialPath = path) }
        fun height() = compose.onNodeWithTag("hierarchy_collapsible_details")
            .fetchSemanticsNode().boundsInRoot.height
        val expanded = height()
        compose.onNodeWithTag("hierarchy_top_bar").performTouchInput { swipeUp() }
        val afterToolbar = height()
        assertTrue("Toolbar swipe should collapse the $path header", afterToolbar < expanded)
        repeat(6) { compose.onNodeWithTag("hierarchy_top_bar").performTouchInput { swipeUp() } }
        compose.onNodeWithTag(taskRowTag("t0")).assertIsNotDisplayed()
        repeat(12) { compose.onNodeWithTag("hierarchy_task_list").performTouchInput { swipeDown() } }
        val reopened = height()
        assertTrue("Header should still reopen after list returns to top", reopened > afterToolbar)
        compose.onNodeWithTag("hierarchy_collapsible_details").performTouchInput { swipeUp() }
        assertTrue("Header swipe should collapse the $path header", height() < reopened)
    }
}
