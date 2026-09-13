package com.virlin.app

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.ui.hierarchy.WorkStreamDetailTag
import com.virlin.app.ui.screens.CompleteWorkStreamCancelTag
import com.virlin.app.ui.screens.CompleteWorkStreamConfirmTag
import com.virlin.app.ui.screens.FocusCompleteTag
import com.virlin.app.ui.screens.FocusContextTag
import com.virlin.app.ui.screens.FocusProjectTag
import com.virlin.app.ui.screens.FocusTaskTag
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pass 3 on the real app: the Now Current Focus card is hierarchy-aware and COMPLETE has
 * task-first semantics. The domain is process-wide, so this is ONE ordered scenario.
 */
@RunWith(AndroidJUnit4::class)
class NowHierarchyUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before fun setUp() { composeRule.mainClock.autoAdvance = false }

    private fun pump(realMs: Long) {
        val end = SystemClock.uptimeMillis() + realMs
        val minFrames = (realMs / 16).toInt(); var frames = 0
        while (SystemClock.uptimeMillis() < end || frames < minFrames) {
            composeRule.mainClock.advanceTimeByFrame(); frames++; Thread.sleep(8)
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    @Test fun currentFocus_hierarchy_tap_complete_confirm() {
        pump(300)
        // Seeded focus: stream s1 "Psychology" (project Psychology) with active task Question 17
        composeRule.onNodeWithTag(FocusProjectTag, useUnmergedTree = true).assertIsDisplayed().assertTextEquals("Psychology")
        composeRule.onNodeWithTag(FocusTaskTag, useUnmergedTree = true).assertIsDisplayed().assertTextEquals("Question 17")

        // Tapping the card's work area opens the WorkStream Detail, never Task Detail
        composeRule.onNodeWithTag(FocusContextTag).performClick(); pump(1000)
        composeRule.onNodeWithTag(WorkStreamDetailTag).assertIsDisplayed()
        Espresso.pressBack(); pump(1000)

        // COMPLETE with an active task completes only the task: card stays, task row disappears
        composeRule.onNodeWithTag(FocusCompleteTag, useUnmergedTree = true).performClick(); pump(800)
        composeRule.onNodeWithText("CURRENT FOCUS").assertIsDisplayed()
        composeRule.onNodeWithTag(FocusTaskTag, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(FocusProjectTag, useUnmergedTree = true).assertIsDisplayed()

        // COMPLETE with no active task asks first; cancel changes nothing
        composeRule.onNodeWithTag(FocusCompleteTag, useUnmergedTree = true).performClick(); pump(800)
        composeRule.onNodeWithTag(CompleteWorkStreamConfirmTag).assertIsDisplayed()
        composeRule.onNodeWithTag(CompleteWorkStreamCancelTag).performClick(); pump(600)
        composeRule.onNodeWithTag(CompleteWorkStreamConfirmTag).assertDoesNotExist()
        composeRule.onNodeWithText("CURRENT FOCUS").assertIsDisplayed()
        // The confirm path is proven in NowHierarchyTest (unit) and manually; confirming here
        // would remove the shared Focus stream for the other instrumented classes.
    }
}
