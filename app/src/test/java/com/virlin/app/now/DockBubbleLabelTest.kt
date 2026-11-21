package com.virlin.app.now

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.ui.navigation.ApprovedNowDock
import com.virlin.app.ui.navigation.RootDestination
import com.virlin.app.ui.navigation.bottomNavItemTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockBubbleLabelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun momentumSitsInsideTheBubbleAndKeepsItsTab() {
        val route = mutableStateOf("pulse")
        compose.setContent {
            Box(Modifier.width(411.dp)) { ApprovedNowDock(route.value, 14, {}) }
        }
        compose.onRoot().captureRoboImage("build/outputs/dock-momentum-selected.png")
        compose.onNodeWithTag(bottomNavItemTag(RootDestination.PULSE)).assertExists()
        route.value = "streams"
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/dock-projects-selected.png")
    }
}
