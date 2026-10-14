package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.virlin.app.ui.screens.VirlinCurrentFocusCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusContextUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tenLevelsWrapAndKeepTaskAndActions() {
        var opened = 0
        var completed = 0
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                VirlinCurrentFocusCard(projectName = "Virlin Development", headline = "Claude · Virlin",
                    taskName = "Specific focused task", elapsedTime = "00:10", investedTime = "",
                    nextAction = "", nextEstimate = "", onLeave = {}, onComplete = { completed++ },
                    onContextClick = { opened++ }, timerContent = {},
                    hierarchyLabels = (1..10).map { "Level$it full name" })
            }
        }
        compose.onNodeWithText("Virlin Development", useUnmergedTree = true).assertExists()
        (1..10).forEach { compose.onNodeWithText("Level$it", useUnmergedTree = true).assertExists() }
        compose.onNodeWithText("Specific focused task").performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("COMPLETE").performClick()
        assertEquals(1, completed)
    }
}
