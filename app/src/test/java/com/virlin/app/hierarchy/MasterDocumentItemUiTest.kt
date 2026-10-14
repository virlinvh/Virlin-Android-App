package com.virlin.app.hierarchy

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.MasterDocumentEntry
import com.virlin.app.ui.hierarchy.MasterDocumentItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MasterDocumentItemUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun todoContentReadsInlineAndStillOpensItsEditor() {
        var opened = false
        compose.setContent {
            MasterDocumentItem(
                MasterDocumentEntry("todo-1", "To-do", "To-do", "☐ Review the draft",
                    "todo-route"),
                ink = Color.Black,
                accent = Color(0xFF6033B1),
                onOpen = { opened = true },
            )
        }
        compose.onNodeWithText("☐ Review the draft").assertExists()
        compose.onNodeWithTag("map_page_item_todo-1").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
}
