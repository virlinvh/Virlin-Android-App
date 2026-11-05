package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TaskNavigationArrowUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyTaskDepthAndCompletedRow_hasArrowWithoutChangingActions() {
        var opened = ""
        var controlled = ""
        val tasks = listOf(StreamTaskUi("root", null, "Task", 0),
            StreamTaskUi("child", "root", "Subtask", 0, activityState = WorkstreamState.PAUSED),
            StreamTaskUi("deep", "child", "Deeper", 0, completed = true))
        compose.setContent {
            Column {
                tasks.forEach { task ->
                    TaskRow(task, 1, false, false, false, WorkstreamState.READY,
                        onOpen = { opened = task.id }, onComplete = {},
                        onWorkControls = { controlled = task.id }, onMenu = {}, onReorder = {})
                }
            }
        }
        tasks.forEach { compose.onNodeWithTag("task_navigation_arrow_${it.id}", useUnmergedTree = true).assertExists() }
        val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("Subtask").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(14.sp, titleLayouts.single().layoutInput.style.fontSize)
        compose.onNodeWithText("Subtask").performClick()
        assertEquals("child", opened)
        compose.onNodeWithText("WORK").performClick()
        assertEquals("root", controlled)
    }
}
