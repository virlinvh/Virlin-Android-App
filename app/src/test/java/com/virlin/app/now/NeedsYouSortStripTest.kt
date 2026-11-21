package com.virlin.app.now

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.ui.screens.NeedsYouSortControl
import com.virlin.app.ui.screens.NeedsYouSortControlTag
import com.virlin.app.ui.screens.NeedsYouSortMenuTag
import com.virlin.app.ui.screens.NeedsYouSortMode
import com.virlin.app.ui.screens.NeedsYouSortStrip
import com.virlin.app.ui.screens.VirlinNeedsYouSection
import com.virlin.app.ui.screens.needsYouSortOptionTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sort icon opens a strip under the Needs You header instead of a popup. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class NeedsYouSortStripTest {
    @get:Rule val compose = createComposeRule()

    private var mode by mutableStateOf(NeedsYouSortMode.PRIORITY)
    private var open by mutableStateOf(false)

    private fun show() = compose.setContent {
        MaterialTheme {
            Column(Modifier.width(360.dp).padding(12.dp)) {
                VirlinNeedsYouSection(tasks = emptyList(), nowEpochMillis = 0L, onCheck = {}, onFilterClick = {},
                    animate = false,
                    filterContent = { NeedsYouSortControl(mode, open, onExpandedChange = { open = it }, tint = Color(0xFF7C3AED)) },
                    belowHeader = {
                        NeedsYouSortStrip(open, mode, onSelect = { mode = it }, onClose = { open = false }, accent = Color(0xFF7C3AED))
                    })
            }
        }
    }

    @Test fun theIconTogglesAStripWithEveryOptionAndPickingOneFoldsItAway() {
        show()
        compose.onNodeWithTag(NeedsYouSortMenuTag).assertDoesNotExist()
        compose.onNodeWithTag(NeedsYouSortControlTag).performClick()
        compose.onNodeWithTag(NeedsYouSortMenuTag).assertIsDisplayed()
        compose.onNodeWithTag(needsYouSortOptionTag(NeedsYouSortMode.PRIORITY)).assertIsSelected()
        compose.onRoot().captureRoboImage("build/outputs/needs-you-sort-strip.png")
        NeedsYouSortMode.entries.forEach { compose.onNodeWithTag(needsYouSortOptionTag(it)).performScrollTo() }
        compose.onNodeWithTag(needsYouSortOptionTag(NeedsYouSortMode.LONGEST_WAITING)).assertIsNotSelected()
        compose.onNodeWithTag(needsYouSortOptionTag(NeedsYouSortMode.BY_PROJECT)).performClick()
        compose.waitForIdle()
        assertEquals(NeedsYouSortMode.BY_PROJECT, mode)
        compose.onNodeWithTag(NeedsYouSortMenuTag).assertDoesNotExist()
    }

    @Test fun tappingTheIconAgainClosesTheStripWithoutChangingTheSort() {
        show()
        compose.onNodeWithTag(NeedsYouSortControlTag).performClick()
        compose.onNodeWithTag(NeedsYouSortMenuTag).assertIsDisplayed()
        compose.onNodeWithTag(NeedsYouSortControlTag).performClick()
        compose.onNodeWithTag(NeedsYouSortMenuTag).assertDoesNotExist()
        assertEquals(NeedsYouSortMode.PRIORITY, mode)
    }
}
