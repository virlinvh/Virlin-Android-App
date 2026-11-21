package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.screens.FocusCompleteTag
import com.virlin.app.ui.screens.FocusHandOffTag
import com.virlin.app.ui.screens.FocusInvestedTag
import com.virlin.app.ui.screens.FocusLeaveTag
import com.virlin.app.ui.screens.FocusProjectTag
import com.virlin.app.ui.screens.FocusResumeTag
import com.virlin.app.ui.screens.FocusTaskTag
import com.virlin.app.ui.screens.VirlinCurrentFocusCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusCardPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun taskIsTheHeadlineAndActionsKeepTheirLabels() {
        var left = 0
        var completed = 0
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                VirlinCurrentFocusCard(
                    projectName = "Psychology", taskName = "SI stands for Super Intelligence",
                    elapsedTime = "00:09", investedTime = "Task total · <1m",
                    nextAction = "Review answer", nextEstimate = "",
                    onLeave = { left++ }, onComplete = { completed++ }, timerContent = {},
                    hierarchyLabels = listOf("Answer", "Questions", "Hello"),
                )
            }
        }
        compose.onNodeWithTag(FocusTaskTag, useUnmergedTree = true).assertTextEquals("SI stands for Super Intelligence")
        compose.onNodeWithTag(FocusProjectTag, useUnmergedTree = true).assertTextEquals("Psychology")
        compose.onNodeWithTag(FocusInvestedTag, useUnmergedTree = true).assertTextEquals("Task total · <1m")
        compose.onNodeWithTag(FocusLeaveTag).assertTextEquals("LEAVE").performClick()
        compose.onNodeWithTag(FocusCompleteTag).assertTextEquals("COMPLETE").performClick()
        assertEquals(1, left)
        assertEquals(1, completed)
    }

    @Test fun pausedCardStaysWithResumeInPlaceOfLeave() {
        var resumed = 0
        var left = 0
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                VirlinCurrentFocusCard(
                    projectName = "Psychology", taskName = "Answer questions",
                    elapsedTime = "12:38", investedTime = "Task total · 12m",
                    nextAction = "", nextEstimate = "", onLeave = { left++ }, onComplete = {},
                    isFocusActive = false, onResume = { resumed++ }, timerContent = {},
                )
            }
        }
        compose.onNodeWithText("FOCUS PAUSED").assertExists()
        compose.onNodeWithTag(FocusInvestedTag, useUnmergedTree = true).assertTextEquals("Task total · 12m")
        compose.onNodeWithTag(FocusLeaveTag).assertDoesNotExist()
        compose.onNodeWithTag(FocusResumeTag).assertTextEquals("RESUME").performClick()
        compose.onNodeWithTag(FocusCompleteTag).assertTextEquals("COMPLETE")
        assertEquals(1, resumed)
        assertEquals(0, left)
    }

    @Test fun withoutATaskTheProjectBecomesTheHeadline() {
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                VirlinCurrentFocusCard(
                    projectName = "Psychology", taskName = "", elapsedTime = "", investedTime = "",
                    nextAction = "", nextEstimate = "", onLeave = {}, onComplete = {},
                    primaryLabel = "HAND OFF", timerContent = {},
                    hierarchyLabels = listOf("Research stream"),
                )
            }
        }
        compose.onNodeWithTag(FocusTaskTag, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(FocusProjectTag, useUnmergedTree = true).assertTextEquals("Psychology")
        compose.onNodeWithTag(FocusHandOffTag).assertTextEquals("HAND OFF")
    }
}
