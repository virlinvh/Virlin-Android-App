package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.hierarchy.HierarchySectionHeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HierarchySectionHeadingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longTitleHasItsOwnRowAndManageStillWorks() = checkHeading(1f)

    @Test fun narrowLayoutSupportsLargeAccessibilityText() = checkHeading(1.6f)

    private fun checkHeading(fontScale: Float) {
        val title = "Phase 0 — Architecture, Benchmark & Technical Baseline"
        val progress = "0% · 0/0 complete"
        var clicks = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    HierarchySectionHeading(title, progress, { clicks++ }, Modifier.width(280.dp))
                }
            }
        }
        val titleBounds = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(title).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(23.sp, titleLayouts.single().layoutInput.style.fontSize)
        val progressBounds = compose.onNodeWithText(progress).fetchSemanticsNode().boundsInRoot
        val manageBounds = compose.onNodeWithText("Manage").fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.bottom <= progressBounds.top)
        assertTrue(titleBounds.bottom <= manageBounds.top)
        assertTrue(progressBounds.right <= manageBounds.left)
        compose.onNodeWithText("Manage").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
