package com.virlin.app.hierarchy

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class StandaloneNavigationUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun exactTaskNameThenBackShowsStandaloneListAndCanOpenOtherTask() {
        val actions = Proxy.newProxyInstance(StreamTaskActions::class.java.classLoader,
            arrayOf(StreamTaskActions::class.java)) { _, _, _ -> null } as StreamTaskActions
        val ui = StreamTasksUi("standalone:p", "Project", "Project", 50,
            StreamExecutionMode.HUMAN, StreamExecutionMode.HUMAN,
            listOf(StreamTaskUi("send", null, "Send APK to tester", 0),
                StreamTaskUi("notes", null, "Prepare release notes", 1)), standalone = true)
        compose.setContent { VirlinStreamTasks(ui, actions, initialPath = listOf("send")) }
        compose.onAllNodesWithText("Send APK to tester").assertCountEquals(2)
        compose.onNodeWithText("Prepare release notes").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodesWithText("Standalone tasks").assertCountEquals(2)
        compose.onNodeWithTag(taskRowTag("notes")).performClick()
        compose.onAllNodesWithText("Prepare release notes").assertCountEquals(2)
        compose.onNodeWithText("Send APK to tester").assertDoesNotExist()
    }
}
