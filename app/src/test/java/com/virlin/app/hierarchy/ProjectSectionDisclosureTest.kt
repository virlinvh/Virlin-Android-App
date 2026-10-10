package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.components.LocalOrbBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.hierarchy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectSectionDisclosureTest {
    @get:Rule val compose = createComposeRule()
    @Test fun measuredOrbClearsBottomControlsButDoesNotShrinkRowsAboveIt() {
        compose.setContent {
            var orb by remember { mutableStateOf<Rect?>(null) }
            Box(Modifier.width(360.dp).height(400.dp)) {
                CompositionLocalProvider(LocalOrbBounds provides orb) {
                    Column(Modifier.fillMaxSize()) {
                        ProjectSectionDisclosure("Standalone tasks", Icons.Outlined.Description, false, "above", {}, {})
                        Spacer(Modifier.weight(1f))
                        ProjectSectionDisclosure("Project knowledge", Icons.Outlined.Description, false, "beside", {}, {})
                    }
                }
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 18.dp).size(52.dp)
                    .onGloballyPositioned { orb = it.boundsInRoot() }.testTag("orb"))
            }
        }
        val orb = compose.onNodeWithTag("orb").fetchSemanticsNode().boundsInRoot
        val beside = compose.onNodeWithTag("beside").fetchSemanticsNode().boundsInRoot
        val above = compose.onNodeWithTag("above").fetchSemanticsNode().boundsInRoot
        assertTrue(beside.right < orb.left)
        assertTrue(above.right > orb.left)
    }
    @Test fun arrowsAreIndependentAndAddDoesNotToggle() {
        var additions = 0
        compose.setContent {
            var tasks by remember { mutableStateOf(false) }
            var knowledge by remember { mutableStateOf(false) }
            Column {
                ProjectSectionDisclosure("Standalone tasks", Icons.Outlined.Description, tasks, "tasks", { tasks = !tasks }, { additions++ }, "add_tasks")
                if (tasks) Text("Task content")
                ProjectSectionDisclosure("Project knowledge", Icons.Outlined.Description, knowledge, "knowledge", { knowledge = !knowledge }, { additions++ })
                if (knowledge) Text("Knowledge content")
            }
        }
        compose.onNodeWithText("Task content").assertDoesNotExist()
        compose.onNodeWithTag("add_tasks").performClick()
        compose.onNodeWithText("Task content").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, additions) }
        compose.onNodeWithTag("tasks").performClick()
        compose.onNodeWithText("Task content").assertIsDisplayed()
        compose.onNodeWithText("Knowledge content").assertDoesNotExist()
        compose.onNodeWithTag("knowledge").performClick()
        compose.onNodeWithTag("tasks").performClick()
        compose.onNodeWithText("Task content").assertDoesNotExist()
        compose.onNodeWithText("Knowledge content").assertIsDisplayed()
    }
    @Test fun headerWashesDeriveFromProjectAccentNotFixedGreen() {
        val red = projectSectionWash(Color.Red, 0.17f)
        val blue = projectSectionWash(Color.Blue, 0.17f)
        assertTrue(red.red > red.blue)
        assertTrue(blue.blue > blue.red)
        assertNotEquals(red, blue)
    }
}
