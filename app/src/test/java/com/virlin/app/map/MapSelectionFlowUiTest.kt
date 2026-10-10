package com.virlin.app.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.map.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class MapSelectionFlowUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun taskAndSubtaskSelectionExposeToolbarAndAllSupportedAddFlows() {
        val project = MapTopic("project", null, "Project", kind = MapKind.PROJECT)
        val stream = MapTopic("stream", project.id, "Design Review", kind = MapKind.WORKSTREAM)
        val task = MapTopic("task", stream.id, "Waited Test 02", kind = MapKind.TASK)
        val child = MapTopic("child", task.id, "Subtask", kind = MapKind.TASK)
        val selected = mutableStateOf<String?>(stream.id)
        val calls = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", listOf(project, stream, task, child), MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = { calls += it.id to "open" },
                    onBack = {}, onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {},
                    selectedId = selected.value, onStructureSelect = { selected.value = it?.id },
                    onMoveTask = { calls += it.id to "move" },
                    onOpenTaskPage = { calls += it.id to "page" },
                    onAddChoice = { owner, kind -> calls += owner.id to kind.name },
                    onAddContainer = { calls += it.id to "container" })
            }
        }
        // Container Add has a separate owner-safe flow, not a forged task id.
        compose.onNodeWithTag(MapSelectedTaskBarTag).assertIsDisplayed()
        compose.onNodeWithTag(mapTaskActionTag("Open Workstream")).assertIsDisplayed()
        compose.onNodeWithTag(mapTaskActionTag("Add")).performClick()
        compose.runOnIdle { selected.value = project.id }
        compose.onNodeWithTag(mapTaskActionTag("Open Project")).assertIsDisplayed()
        compose.onNodeWithTag(mapTaskActionTag("Move")).assertDoesNotExist()
        compose.onNodeWithTag(mapTaskActionTag("Add")).performClick()
        listOf(task, child).forEach { owner ->
            compose.runOnIdle { selected.value = owner.id }
            compose.onNodeWithTag(MapSelectedTaskBarTag).assertIsDisplayed()
            listOf("Open Task", "Move", "Page").forEach { label ->
                compose.onNodeWithTag(mapTaskActionTag(label)).performClick()
            }
            MapAddKind.entries.forEach { kind ->
                compose.onNodeWithTag(mapTaskActionTag("Add")).performClick()
                compose.onNodeWithText("Sticker").assertDoesNotExist()
                compose.onNodeWithText("Illustration").assertDoesNotExist()
                compose.onNodeWithTag("mind_map_add_${kind.name.lowercase()}")
                    .performScrollTo().performClick()
                compose.onNodeWithTag(MapSelectedTaskBarTag).assertIsDisplayed()
            }
        }
        compose.runOnIdle {
            val expected = listOf(task, child).flatMap { owner ->
                (listOf("open", "move", "page") + MapAddKind.entries.map { it.name })
                    .map { owner.id to it }
            }
            assertEquals(listOf(stream.id to "container", project.id to "container") + expected, calls)
        }
    }
}
