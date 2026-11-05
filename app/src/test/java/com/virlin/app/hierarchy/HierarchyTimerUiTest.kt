package com.virlin.app.hierarchy

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import com.virlin.app.ui.hierarchy.HierarchyTimerAccent
import androidx.compose.ui.test.onNodeWithTag
import com.virlin.app.ui.hierarchy.LocalProjectPalette
import com.virlin.app.ui.hierarchy.ProjectPalette
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.HierarchyTimer
import com.virlin.app.ui.hierarchy.RotatingHierarchyTimer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HierarchyTimerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun timerDigitsFollowLiveProjectPaletteChanges() {
        val palette = mutableStateOf(ProjectPalette.from(Color(0xFFEAB308)))
        compose.setContent {
            CompositionLocalProvider(LocalProjectPalette provides palette.value) { HierarchyTimer(90L) }
        }
        fun assertRenderedColor(color: Color) {
            compose.onNodeWithTag("hierarchy_elapsed").assert(SemanticsMatcher.expectValue(HierarchyTimerAccent, color))
        }
        assertRenderedColor(Color(0xFFEAB308))
        compose.runOnIdle { palette.value = ProjectPalette.from(Color(0xFF2563EB)) }
        assertRenderedColor(Color(0xFF2563EB))
        compose.onNodeWithContentDescription("Elapsed focus time 1:30").assertExists()
    }

    @Test fun rollingTimerUpdatesAcrossHourBoundaryWithoutVisibleLabel() {
        val seconds = mutableStateOf<Long?>(3599L)
        compose.setContent { HierarchyTimer(seconds.value) }
        compose.onNodeWithContentDescription("Elapsed focus time 59:59").assertExists()
        compose.onNodeWithText("ELAPSED").assertDoesNotExist()
        compose.runOnIdle { seconds.value = 3600L }
        compose.onNodeWithContentDescription("Elapsed focus time 1:00:00").assertExists()
        compose.runOnIdle { seconds.value = null }
        compose.onNodeWithContentDescription("No active focus").assertExists()
    }

    @Test fun categoryRotatesWithoutTaskCounts() {
        compose.mainClock.autoAdvance = false
        compose.setContent { RotatingHierarchyTimer(90L, 240L) }
        compose.onNodeWithText("Focused").assertExists()
        compose.mainClock.advanceTimeBy(6_500)
        compose.onNodeWithText("External").assertExists()
        compose.onNodeWithContentDescription("External 4:00").assertExists()
    }
}
