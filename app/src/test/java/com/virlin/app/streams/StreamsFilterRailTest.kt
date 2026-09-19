package com.virlin.app.streams

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.screens.StreamsFilterRail
import com.virlin.app.ui.screens.StreamsFilterRailTag
import com.virlin.app.ui.screens.streamsFilterTag
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Streams filter rail: content-width chips, single-line labels, horizontal scroll on narrow widths.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamsFilterRailTest {

    @get:Rule val composeRule = createComposeRule()

    private fun setRail(widthDp: Int, fontScale: Float = 1f, initial: String = "All"): () -> String {
        var filter by mutableStateOf(initial)
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale)
            ) {
                VirlinTheme {
                    Box(Modifier.width(widthDp.dp).height(88.dp)) {
                        StreamsFilterRail(
                            filter = filter,
                            onFilterSelected = { filter = it }
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return { filter }
    }

    private fun assertSingleLineHorizontal(tag: String) {
        composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        val bounds = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        // Vertical stacking (R\ne\na\nd\ny) makes height >> width for short labels.
        assertTrue(
            "Expected single-line horizontal chip for $tag (w=${bounds.width}, h=${bounds.height})",
            bounds.width > bounds.height * 1.2f
        )
    }

    @Test
    fun allFiveFiltersExist() {
        setRail(411)
        listOf("All", "Projects", "Need You", "Processing", "Ready").forEach { label ->
            composeRule.onNodeWithTag(streamsFilterTag(label)).performScrollTo().assertExists()
        }
    }

    @Test
    fun readyProcessingNeedYou_areSingleLine_onNarrowWidth() {
        setRail(320)
        assertSingleLineHorizontal(streamsFilterTag("Ready"))
        assertSingleLineHorizontal(streamsFilterTag("Processing"))
        assertSingleLineHorizontal(streamsFilterTag("Need You"))
    }

    @Test
    fun narrowRail_canScrollHorizontally_andRevealReady() {
        setRail(320)
        composeRule.onNodeWithTag(StreamsFilterRailTag).assert(hasScrollAction())
        composeRule.onNodeWithTag(streamsFilterTag("Ready"))
            .performScrollTo()
            .assertIsDisplayed()
        assertSingleLineHorizontal(streamsFilterTag("Ready"))
    }

    @Test
    fun selectingReady_updatesFilterAndSelectedSemantics() {
        val readFilter = setRail(360)
        composeRule.onNodeWithTag(streamsFilterTag("Ready")).performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals("Ready", readFilter())
        composeRule.onNodeWithTag(streamsFilterTag("Ready")).assert(isSelected())
        composeRule.onNodeWithTag(streamsFilterTag("All")).assert(isNotSelected())
    }

    @Test
    fun swipeLeft_keepsReadySingleLine() {
        setRail(360)
        composeRule.onNodeWithTag(StreamsFilterRailTag).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertSingleLineHorizontal(streamsFilterTag("Ready"))
        assertSingleLineHorizontal(streamsFilterTag("Processing"))
    }

    @Test
    fun fontScale13_chipsWidenButStaySingleLine() {
        setRail(360, fontScale = 1.3f)
        composeRule.onNodeWithTag(StreamsFilterRailTag).assert(hasScrollAction())
        assertSingleLineHorizontal(streamsFilterTag("Ready"))
        assertSingleLineHorizontal(streamsFilterTag("Need You"))
    }

    @Test
    fun width320_labelsStayHorizontal() {
        setRail(320)
        assertSingleLineHorizontal(streamsFilterTag("Ready"))
        assertSingleLineHorizontal(streamsFilterTag("Processing"))
        assertSingleLineHorizontal(streamsFilterTag("Need You"))
    }

    @Test
    fun width393_labelsStayHorizontal() {
        setRail(393)
        assertSingleLineHorizontal(streamsFilterTag("Ready"))
        assertSingleLineHorizontal(streamsFilterTag("Processing"))
        assertSingleLineHorizontal(streamsFilterTag("Need You"))
    }

    @Test
    fun width411_labelsStayHorizontal() {
        setRail(411)
        assertSingleLineHorizontal(streamsFilterTag("Ready"))
        assertSingleLineHorizontal(streamsFilterTag("Processing"))
        assertSingleLineHorizontal(streamsFilterTag("Need You"))
    }
}
