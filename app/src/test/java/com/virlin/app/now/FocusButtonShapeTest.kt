package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.ui.screens.NowChooseButtonShape
import com.virlin.app.ui.screens.NowCompleteButtonShape
import com.virlin.app.ui.screens.NowEmptyFocusCard
import com.virlin.app.ui.screens.NowLeaveButtonShape
import com.virlin.app.ui.screens.VirlinCurrentFocusCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusButtonShapeTest {
    @get:Rule val compose = createComposeRule()

    private fun bounds(shape: androidx.compose.ui.graphics.Shape, w: Float, h: Float) =
        (shape.createOutline(Size(w, h), LayoutDirection.Ltr, Density(2.6f)) as Outline.Generic).path.getBounds()

    @Test
    fun everyButtonOutlineStaysInsideItsBox() {
        for (shape in listOf(NowChooseButtonShape, NowLeaveButtonShape, NowCompleteButtonShape))
            for ((w, h) in listOf(130f to 49f, 340f to 49f, 800f to 140f)) {
                val b = bounds(shape, w, h)
                assertTrue("$b in $w x $h", b.left >= -.01f && b.top >= -.01f && b.right <= w + .01f && b.bottom <= h + .01f)
            }
    }

    @Test
    fun leaveAndCompleteAreMirroredNotIdentical() {
        assertNotEquals(bounds(NowLeaveButtonShape, 300f, 100f), bounds(NowCompleteButtonShape, 300f, 100f))
    }

    @Test
    fun chooseATaskKeepsItsActionAndLabel() {
        var chosen = 0
        compose.setContent {
            Box(Modifier.width(360.dp)) { NowEmptyFocusCard(chooseTaskLabel = "Pick") { chosen++ } }
        }
        compose.onRoot().captureRoboImage("build/outputs/now-choose-task-button.png")
        compose.onNodeWithTag("now_choose_task").assert(SemanticsMatcher("labelled") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label == "Pick"
        }).performClick()
        assertEquals(1, chosen)
    }

    @Test
    fun leaveAndCompleteRender() {
        compose.setContent {
            Box(Modifier.width(360.dp)) {
                VirlinCurrentFocusCard(
                    projectName = "Windows", taskName = "Profitable Methods of Study", elapsedTime = "00:08",
                    investedTime = "Task total · <1m", nextAction = "", nextEstimate = "",
                    onLeave = {}, onComplete = {}, timerContent = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/now-leave-complete-buttons.png")
    }
}
