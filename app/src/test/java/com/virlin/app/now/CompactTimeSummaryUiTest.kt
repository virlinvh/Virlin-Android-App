package com.virlin.app.now

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.screens.*
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompactTimeSummaryUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun showsAllMetricsWithinExistingBannerHeight() {
        compose.setContent { NowDaySummary(NowDaySummaryValues(timeSaved = "2h 10m", savedUnit = "Time freed",
            timeFocused = "9h 36m", timeExternal = "18h 20m"), modifier = Modifier.width(360.dp)) }
        compose.onNodeWithText("Time freed").assertIsDisplayed()
        compose.onNodeWithText("Focus").assertIsDisplayed()
        compose.onNodeWithText("External").assertIsDisplayed()
        compose.onNodeWithText("9h 36m").assertIsDisplayed()
        compose.onNodeWithText("18h 20m").assertIsDisplayed()
        compose.onNodeWithText("Today").assertDoesNotExist()
        val bar = compose.onNodeWithTag(NowSummaryBarTag).fetchSemanticsNode().boundsInRoot
        // All labels stay within the unchanged banner rather than introducing another row.
        val external = compose.onNodeWithText("18h 20m").fetchSemanticsNode().boundsInRoot
        val focus = compose.onNodeWithText("9h 36m").fetchSemanticsNode().boundsInRoot
        assertEquals(focus.left, external.left, 0.1f)
        assertTrue(external.right <= bar.right)
        assertTrue(external.bottom <= bar.bottom)
    }
}
