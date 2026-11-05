package com.virlin.app.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapStructureBar
import com.virlin.app.ui.map.MapTopic
import com.virlin.app.ui.map.VirlinMapSnapshot
import com.virlin.app.ui.map.decodeVirlinMap
import com.virlin.app.ui.map.encodeVirlinMap
import com.virlin.app.ui.map.hiddenMapTopicIds
import com.virlin.app.ui.map.arrange
import com.virlin.app.ui.map.MapLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MapHideTest {
    @get:Rule val compose = createComposeRule()

    private val topics = listOf(
        MapTopic("project", null, "Project", kind = MapKind.PROJECT),
        MapTopic("stream", "project", "Stream", kind = MapKind.WORKSTREAM),
        MapTopic("task", "stream", "Task", kind = MapKind.TASK),
        MapTopic("sibling", "project", "Sibling", kind = MapKind.TASK),
    )

    @Test fun hidingOwnerAffectsOnlyItsMapSubtreeAndSurvivesExport() {
        val hidden = hiddenMapTopicIds(topics, setOf("stream"))
        assertEquals(setOf("stream", "task"), hidden)
        assertEquals(listOf("project", "sibling"), topics.filterNot { it.id in hidden }.map { it.id })
        assertEquals(topics, decodeVirlinMap(encodeVirlinMap(VirlinMapSnapshot(
            "project", "Project", topics, MapAppearance(hiddenNodeIds = setOf("stream"))
        ))).topics)
        val restored = decodeVirlinMap(encodeVirlinMap(VirlinMapSnapshot(
            "project", "Project", topics, MapAppearance(hiddenNodeIds = setOf("stream"))
        )))
        assertEquals(setOf("stream"), restored.appearance.hiddenNodeIds)
        assertFalse("project" in hiddenMapTopicIds(topics, setOf("project")))
    }

    @Test fun hidingFirstBranchDoesNotChangeSiblingPaletteIndex() {
        val rootBranchIndices = mapOf("stream" to 0, "sibling" to 1)
        val visible = topics.filterNot { it.id in hiddenMapTopicIds(topics, setOf("stream")) }
        val geometry = arrange(visible, MapLayout.RIGHT_TREE,
            rootBranchIndices = rootBranchIndices)
        assertEquals(1, geometry.branch["sibling"])
    }

    @Test fun showHiddenRemainsReachableWithoutASelectedBox() {
        compose.setContent {
            MaterialTheme {
                MapStructureBar(selected = null, affectedCount = 0, clipboard = null,
                    organizing = true, hasHiddenBoxes = true, showHidden = true,
                    onOpen = {}, onEdit = {}, onMove = {}, onCopy = {},
                    onDuplicate = {}, onPaste = {}, onClear = {})
            }
        }
        compose.onNodeWithContentDescription("Show hidden").assertExists().assertIsSelected()
    }
}
