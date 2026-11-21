package com.virlin.app.hierarchy

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class StageBackNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var leftWorkStream = 0
    private val actions = Proxy.newProxyInstance(StreamTaskActions::class.java.classLoader,
        arrayOf(StreamTaskActions::class.java)) { _, method, _ ->
        if (method.name == "back") leftWorkStream++
        null
    } as StreamTaskActions

    private val ui = StreamTasksUi("w", "Psychology", "Psychology", 0, StreamExecutionMode.HUMAN,
        StreamExecutionMode.HUMAN, listOf(
            StreamTaskUi("answer", null, "Answer Questions", 0, childCount = 1),
            StreamTaskUi("read", null, "Read Chapter", 1),
            StreamTaskUi("q11", "answer", "Questions 11-20", 0, childCount = 1),
            StreamTaskUi("q17", "q11", "Question 17", 0)))

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun systemBackClimbsOneLevelAtATimeThenLeaves() {
        compose.setContent { VirlinStreamTasks(ui, actions, initialPath = listOf("answer", "q11")) }
        compose.onNodeWithTag(taskRowTag("q17")).assertIsDisplayed()

        systemBack()
        compose.onNodeWithTag(taskRowTag("q11")).assertIsDisplayed()
        compose.onNodeWithTag(taskRowTag("q17")).assertDoesNotExist()
        assertEquals(0, leftWorkStream)

        systemBack()
        compose.onNodeWithTag(taskRowTag("answer")).assertIsDisplayed()
        compose.onNodeWithTag(taskRowTag("read")).assertIsDisplayed()
        assertEquals(0, leftWorkStream)

        // At the WorkStream's own list the screen lets Back through to navigation (→ project).
        assertEquals(false, compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test fun projectBadgeSitsInTheTopBarAndOpensTheProject() {
        var opened = 0
        compose.setContent {
            VirlinStreamTasks(ui, actions, initialPath = listOf("answer"), projectBadge = {
                Box(Modifier.size(48.dp).testTag(StreamProjectBadgeTag).clickable { opened++ })
            })
        }
        compose.onNodeWithTag(StreamProjectBadgeTag).assertIsDisplayed().performClick()
        assertEquals(1, opened)
        compose.onNodeWithTag(taskRowTag("q11")).assertIsDisplayed()
    }
}
