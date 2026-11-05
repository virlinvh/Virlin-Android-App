package com.virlin.app.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.virlin.app.domain.structure.HierarchyNode
import com.virlin.app.domain.structure.NodeKind
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapMoveDestinationSheet
import com.virlin.app.ui.map.MapTopic
import com.virlin.app.ui.map.destinationsFor
import com.virlin.app.ui.map.mapDestinationTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MapMoveDestinationSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun browsesThroughCurrentParentButConfirmsOnlyLegalDestination() {
        val nodes = listOf(
            HierarchyNode("project", "project", NodeKind.PROJECT, null, 0),
            HierarchyNode("stream", "project", NodeKind.WORKSTREAM, "project", 0),
            HierarchyNode("source", "project", NodeKind.TASK, "stream", 0),
            HierarchyNode("sibling", "project", NodeKind.TASK, "stream", 1),
            HierarchyNode("child", "project", NodeKind.TASK, "sibling", 0),
        )
        val titles = mapOf("stream" to "Stream", "source" to "Source",
            "sibling" to "Sibling", "child" to "Child")
        val legal = destinationsFor(nodes, titles, "source", "Project")
        var chosen: String? = null
        compose.setContent {
            MaterialTheme {
                MapMoveDestinationSheet(
                    source = MapTopic("source", "stream", "Source", kind = MapKind.TASK),
                    affectedCount = 1, projectTitle = "Project", nodes = nodes,
                    titles = titles, destinations = legal, onDismiss = {},
                    onChoose = { chosen = it.node.id },
                )
            }
        }
        compose.onNodeWithText("Choose a destination").assertExists()
        compose.onNodeWithTag(mapDestinationTag("stream")).assertExists()
        compose.onNodeWithContentDescription("Explore Stream").performClick()
        compose.onNodeWithTag(mapDestinationTag("sibling")).performClick()
        compose.onNodeWithTag("move_destination_confirm").performClick()
        assertEquals("sibling", chosen)
    }
}
