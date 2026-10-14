package com.virlin.app.map

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.virlin.app.ui.map.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun selectedBoxResizesFromItsInvisibleRightEdgeOnlyInOrganizeMode() {
        val topics = listOf(
            MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("t", "p", "A task with several words", kind = MapKind.TASK),
        )
        val appearance = mutableStateOf(MapAppearance())
        val organizing = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", topics, appearance.value,
                    onAppearanceChange = { appearance.value = it },
                    onNodeOpen = {}, onBack = {}, onExportXmind = {}, onExportVirlin = {},
                    onOpenVirlinMap = {}, structureMode = organizing.value, selectedId = "t")
            }
        }
        compose.onNodeWithTag("mind_map_resize_edge_t").assertExists()
        compose.onNodeWithTag("mind_map_resize_t").assertDoesNotExist()
        compose.onNodeWithTag("mind_map_resize_edge_t").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(120f, 0f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertTrue(appearance.value.nodeWidths.getValue("t") >
                arrange(topics, MapLayout.RIGHT_TREE).frames.getValue("t").w)
            organizing.value = false
        }
        compose.onNodeWithTag("mind_map_resize_edge_t").assertDoesNotExist()
    }

    @Test fun organizeBarShowsCompactIconActionsAndDuplicate() {
        var duplicateCalls = 0
        var branchCalls = 0
        compose.setContent {
            MaterialTheme {
                MapStructureBar(
                    selected = MapTopic("t", "p", "Create Mode", kind = MapKind.TASK),
                    affectedCount = 7, clipboard = null, organizing = true,
                    taskToolbarPresent = true,
                    onOpen = {}, onEdit = {}, onAddBranch = { branchCalls++ }, onMove = {}, onCopy = {},
                    onDuplicate = { duplicateCalls++ }, onPaste = {}, onClear = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Edit").assertExists()
        compose.onNodeWithContentDescription("Copy").assertExists()
        compose.onNodeWithContentDescription("Add branch").performClick()
        compose.onNodeWithContentDescription("Duplicate").performClick()
        compose.onNodeWithContentDescription("Clear selection").assertExists()
        compose.onNodeWithText("Create Mode").assertDoesNotExist()
        compose.onNodeWithText("7 items").assertDoesNotExist()
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, branchCalls)
            assertEquals(1, duplicateCalls)
        }
    }

    @Test fun organizeBarOffersBranchCreationForEveryHierarchyLevel() {
        val kind = mutableStateOf(MapKind.PROJECT)
        var calls = 0
        compose.setContent {
            MaterialTheme {
                MapStructureBar(
                    selected = MapTopic("node", null, "Selected", kind = kind.value),
                    affectedCount = 1, clipboard = null, organizing = true,
                    onOpen = {}, onEdit = {}, onAddBranch = { calls++ },
                    onMove = {}, onCopy = {}, onDuplicate = {}, onPaste = {}, onClear = {},
                )
            }
        }
        for (level in MapKind.entries) {
            compose.runOnIdle { kind.value = level }
            compose.onNodeWithContentDescription("Add branch").performClick()
            compose.runOnIdle {
                assertEquals(MapKind.entries.indexOf(level) + 1, calls)
                }
        }
    }

    @Test fun organizeActionsEndDirectlyBelowMoreMenu() {
        val task = MapTopic("t", "p", "Create Mode", kind = MapKind.TASK)
        val topics = listOf(MapTopic("p", null, "Project", kind = MapKind.PROJECT), task)
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", topics, MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = {}, onBack = {},
                    onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {},
                    structureMode = true, selectedId = task.id,
                    structureBar = {
                        MapStructureBar(task, 1, null, true, true,
                            onOpen = {}, onEdit = {}, onMove = {}, onCopy = {},
                            onDuplicate = {}, onPaste = {}, onClear = {})
                    })
            }
        }
        compose.waitForIdle()
        val menu = compose.onNodeWithContentDescription("More").getUnclippedBoundsInRoot()
        val clear = compose.onNodeWithContentDescription("Clear selection").getUnclippedBoundsInRoot()
        val menuCenterX = (menu.left.value + menu.right.value) / 2f
        val clearCenterX = (clear.left.value + clear.right.value) / 2f
        assertTrue(kotlin.math.abs(menuCenterX - clearCenterX) < 12f)
        assertTrue(clear.top >= menu.bottom)
    }

    @Test fun exportPageReplacesMenuAndRoutesEveryFormat() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project",
                    listOf(MapTopic("p", null, "Project", kind = MapKind.PROJECT)),
                    MapAppearance(), onAppearanceChange = {}, onNodeOpen = {}, onBack = {},
                    onExportXmind = { calls += "xmind" },
                    onExportVirlin = { calls += "virlin" },
                    onExportPdf = { calls += "pdf" },
                    onExportPng = { calls += "png" },
                    onExportMarkdown = { calls += "md" },
                    onExportJson = { calls += "json" },
                    onOpenVirlinMap = { calls += "preview" })
            }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithTag("map_menu_export").assertIsDisplayed()
        compose.onNodeWithTag("map_export_xmind").assertDoesNotExist()
        compose.onNodeWithText("Preview a Virlin map file").assertDoesNotExist()
        compose.onNodeWithTag("map_menu_export").performClick()
        compose.onNodeWithTag("map_export_heading").assertIsDisplayed()
        compose.onNodeWithText("VMap (.vmap)").assertIsDisplayed()
        compose.onNodeWithTag(MindMapStructureToggleTag).assertDoesNotExist()
        listOf("map_export_xmind", "map_export_virlin", "map_export_pdf",
            "map_export_png", "map_export_md", "map_export_json")
            .forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag("map_export_xmind").assertIsDisplayed().performClick()
        compose.onNodeWithTag("map_menu_export").assertDoesNotExist()
        listOf("map_export_virlin", "map_export_pdf", "map_export_png", "map_export_md", "map_export_json")
            .forEach { tag ->
                compose.onNodeWithContentDescription("More").performClick()
                compose.onNodeWithTag("map_menu_export").performClick()
                compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
            }
        compose.runOnIdle { assertEquals(listOf("xmind", "virlin", "pdf", "png", "md", "json"), calls) }
    }

    @Test fun focusModeHidesMapChromeAndRestoresItFromStack() {
        val topics = listOf(
            MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("a", "p", "Branch", kind = MapKind.WORKSTREAM),
            MapTopic("t", "a", "Task", kind = MapKind.TASK),
        )
        val focus = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", topics, MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = {}, onBack = {},
                    onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {},
                    selectedId = "a", onFocusModeChange = { focus.value = it })
            }
        }
        compose.onNodeWithContentDescription("Back to project").assertExists()
        compose.onNodeWithTag("map_branch_a").assertExists()
        compose.onNodeWithTag("map_control_stack").performClick()
        compose.onNodeWithTag("map_control_focus").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back to project").assertDoesNotExist()
        compose.onNodeWithTag("map_branch_a").assertDoesNotExist()
        compose.onNodeWithTag("map_control_stack").assertIsDisplayed()
        compose.runOnIdle { assertEquals(true, focus.value) }
        compose.onNodeWithTag("map_control_stack").performClick()
        compose.onNodeWithTag("map_control_focus").assertIsSelected().performClick()
        compose.onNodeWithContentDescription("Back to project").assertExists()
        compose.onNodeWithTag("map_branch_a").assertExists()
        compose.runOnIdle { assertEquals(false, focus.value) }
    }

    @Test fun repeatedFocusZoomAndBackRestoreWholeMap() {
        val topics = listOf(
            MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("a", "p", "Focus", kind = MapKind.WORKSTREAM),
            MapTopic("a1", "a", "Child", kind = MapKind.TASK),
            MapTopic("b", "p", "Other", kind = MapKind.WORKSTREAM),
            MapTopic("b1", "b", "Other child", kind = MapKind.TASK),
        )
        var exits = 0
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", topics, MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = {}, onBack = { exits++ },
                    onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {},
                    selectedId = "a")
            }
        }
        compose.onNodeWithTag("map_control_stack").performClick()
        repeat(2) {
            compose.onNodeWithTag("map_control_lock").performClick().assertIsSelected()
            compose.onNodeWithTag("map_branch_b").assertDoesNotExist()
            compose.onNodeWithTag("map_control_minus").performClick()
            compose.onNodeWithTag("map_control_lock").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("map_control_lock").assertIsNotSelected()
            compose.onNodeWithTag("map_branch_b").assertExists()
        }
        compose.onNodeWithTag("map_control_lock").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Back to full map").performClick()
        compose.onNodeWithTag("map_branch_b").assertExists()
        compose.runOnIdle { assertEquals(0, exits) }
        compose.onNodeWithContentDescription("Back to project").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test fun appearanceEditorsAreFullPagesAndSaveOnlyConfirmedChanges() {
        val topics = listOf(
            MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("w", "p", "Workstream", kind = MapKind.WORKSTREAM),
        )
        val saved = mutableStateOf(MapAppearance())
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", topics, saved.value,
                    onAppearanceChange = { saved.value = it }, onNodeOpen = {}, onBack = {},
                    onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {})
            }
        }
        compose.onNodeWithContentDescription("Map layout").performClick()
        compose.onNodeWithText("LOGIC CHART STYLES").assertExists()
        compose.onNodeWithContentDescription("Rendered Right tree preview").assertExists()
        compose.onNodeWithText("Find your flow").assertDoesNotExist()
        compose.onNodeWithText("Pick the branch style that feels easiest to follow.").assertDoesNotExist()
        compose.onNodeWithTag(MindMapCanvasTag).assertDoesNotExist()
        compose.onNodeWithText("Save layout").assertDoesNotExist()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        compose.onNodeWithContentDescription("Flow forks layout").performClick()
        compose.onNodeWithText("Previewing").assertExists()
        compose.onNodeWithText("Applied").assertExists()
        compose.onNodeWithText("Save layout").assertIsEnabled()
        compose.onNodeWithContentDescription("Right tree layout").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save layout").assertDoesNotExist()
        compose.onNodeWithContentDescription("Flow forks layout").performClick()
        compose.onNodeWithText("Save layout").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(MapLayout.RIGHT_TREE, saved.value.layout) }
        compose.onNodeWithContentDescription("Map layout").performClick()
        compose.onNodeWithContentDescription("Flow forks layout").performClick()
        compose.onNodeWithText("Save layout").performClick()
        compose.runOnIdle { assertEquals(MapLayout.FLOW_FORKS, saved.value.layout) }

        compose.onNodeWithContentDescription("Map colours").performClick()
        compose.onNodeWithText("Choose a palette").assertExists()
        compose.onNodeWithTag(MindMapCanvasTag).assertDoesNotExist()
        compose.onNodeWithText("Save colours").assertDoesNotExist()
        compose.onNodeWithText("Quiet earth").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save colours").performClick()
        compose.runOnIdle { assertEquals(1, saved.value.paletteIndex) }
    }

    @Test fun externalBadgesFoldNestedBranchesAndToolbarRestoresAll() {
        val topics = listOf(MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("a", "p", "Branch", kind = MapKind.TASK),
            MapTopic("b", "a", "Nested", kind = MapKind.TASK),
            MapTopic("c", "b", "Leaf", kind = MapKind.TASK))
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", topics, MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = {}, onBack = {},
                    onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {})
            }
        }
        compose.onNodeWithContentDescription("Map outline").assertDoesNotExist()
        compose.onNodeWithContentDescription("Map colours").assertExists()
        compose.onNodeWithContentDescription("Map layout").assertExists()
        compose.onNodeWithTag("map_branch_a").assertExists().performClick()
        compose.onNodeWithTag("map_branch_a").assertContentDescriptionEquals("Expand 2 hidden cards")
        compose.onNodeWithTag("map_branch_b").assertDoesNotExist()
        compose.onNodeWithTag("map_branch_a").performClick()
        compose.onNodeWithTag("map_branch_b").assertExists()
        compose.onNodeWithTag("map_branch_c").assertDoesNotExist()
        compose.onNodeWithTag("map_control_stack").performClick()
        compose.onNodeWithTag("map_control_collapse").assertContentDescriptionEquals("Fold deepest visible level").performClick()
        compose.onNodeWithTag("map_branch_b").assertContentDescriptionEquals("Expand 1 hidden cards")
        compose.onNodeWithTag("map_branch_a").assertExists()
        compose.onNodeWithTag("map_control_collapse").performClick()
        compose.onNodeWithTag("map_branch_b").assertDoesNotExist()
        compose.onNodeWithTag("map_control_collapse").performClick()
        compose.onNodeWithTag("map_branch_a").assertDoesNotExist()
        compose.onNodeWithTag("map_branch_p").assertContentDescriptionEquals("Expand 3 hidden cards")
        compose.onNodeWithTag("map_control_expand").assertContentDescriptionEquals("Open next branch level").performClick()
        compose.onNodeWithTag("map_branch_a").assertExists()
        compose.onNodeWithTag("map_branch_b").assertDoesNotExist()
        compose.onNodeWithTag("map_control_expand").performClick()
        compose.onNodeWithTag("map_branch_b").assertExists()
        compose.onNodeWithTag("map_control_expand").performClick()
        compose.onNodeWithTag("map_branch_b").assertContentDescriptionEquals("Collapse 1 descendant cards")
    }

    @Test fun externalGripDragsToAnotherTask() {
        val topics = listOf(MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("source", "p", "Source", kind = MapKind.TASK, order = 0),
            MapTopic("target", "p", "Target", kind = MapKind.TASK, order = 1))
        val drops = mutableListOf<String>()
        var density = 1f
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            MaterialTheme {
                VirlinProjectMindMap("Project", topics, MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = {}, onBack = {},
                    onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {},
                    structureMode = true,
                    selectedId = "source", isValidTarget = { source, target -> source != target },
                    onBranchDrop = { source, plan -> drops += "$source:${plan.targetId}" },
                    onInvalidDrop = { source, target -> drops += "invalid:$source:$target" })
            }
        }
        compose.waitForIdle()
        val canvas = compose.onNodeWithTag(MindMapCanvasTag).fetchSemanticsNode().boundsInRoot
        val grip = compose.onNodeWithTag("mind_map_branch_drag_handle").fetchSemanticsNode().boundsInRoot
        val geometry = arrange(topics, MapLayout.RIGHT_TREE)
        val zoom = fitMapZoom(canvas.width - 72f*density, canvas.height - 72f*density,
            (geometry.right-geometry.left)*density, (geometry.bottom-geometry.top)*density)
        val offsetX = canvas.width/2f - (geometry.left+geometry.right)/2f*density*zoom
        val offsetY = canvas.height/2f - (geometry.top+geometry.bottom)/2f*density*zoom
        val target = geometry.frames.getValue("target")
        val end = androidx.compose.ui.geometry.Offset(
            canvas.left + offsetX + (target.x+target.w/2)*density*zoom - grip.left,
            canvas.top + offsetY + (target.y+target.h/2)*density*zoom - grip.top)
        compose.onNodeWithTag("mind_map_branch_drag_handle").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 30f), delayMillis = 100)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("mind_map_branch_drag_handle").performTouchInput {
            moveTo(end, delayMillis = 100)
            up()
        }
        compose.runOnIdle { assertEquals(listOf("source:target"), drops) }
    }

    @Test fun branchButtonsRespectSelectedBoxAndLeaveOtherBranchesAlone() {
        val topics = listOf(
            MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("a", "p", "Selected", kind = MapKind.WORKSTREAM),
            MapTopic("a1", "a", "Task", kind = MapKind.TASK),
            MapTopic("a2", "a1", "Subtask", kind = MapKind.TASK),
            MapTopic("b", "p", "Other", kind = MapKind.WORKSTREAM),
            MapTopic("b1", "b", "Other task", kind = MapKind.TASK),
        )
        val selected = mutableStateOf<String?>("a")
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", topics, MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = {}, onBack = {},
                    onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {},
                    selectedId = selected.value,
                    onStructureSelect = { selected.value = it?.id })
            }
        }
        compose.onNodeWithTag("map_control_stack").performClick()
        compose.onNodeWithTag("map_control_collapse")
            .assertContentDescriptionEquals("Fold deepest level under Selected").performClick()
        compose.onNodeWithTag("map_branch_a1").assertContentDescriptionEquals("Expand 1 hidden cards")
        compose.onNodeWithTag("map_branch_b").assertContentDescriptionEquals("Collapse 1 descendant cards")
        compose.onNodeWithTag("map_control_expand")
            .assertContentDescriptionEquals("Open next level under Selected").performClick()
        compose.onNodeWithTag("map_branch_a1").assertContentDescriptionEquals("Collapse 1 descendant cards")
        compose.runOnIdle { selected.value = null }
        compose.onNodeWithTag("map_control_collapse")
            .assertContentDescriptionEquals("Fold deepest visible level").performClick()
        compose.onNodeWithTag("map_branch_a1").assertContentDescriptionEquals("Expand 1 hidden cards")
    }

    @Test fun emptyDropdownDoesNotOpenMenusAndSavedPreviewStillExpands() {
        val project = MapTopic("p", null, "Project", kind = MapKind.PROJECT)
        val task = MapTopic("t", "p", "Task", kind = MapKind.TASK)
        val owners = mutableStateOf(emptySet<String>())
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", listOf(project, task), MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = {}, onBack = {},
                    onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {},
                    selectedId = task.id, previewOwners = owners.value,
                    onEditPreview = { calls += "edit:${it.id}" },
                    onTogglePreview = { calls += "expand:${it.id}" })
            }
        }
        compose.onNodeWithTag("map_node_edit_preview").performClick()
        compose.onNodeWithTag("map_node_toggle_preview").performClick()
        compose.runOnIdle { owners.value = setOf(task.id) }
        compose.onNodeWithTag("map_node_toggle_preview").performClick()
        compose.runOnIdle { assertEquals(listOf("edit:t", "expand:t"), calls) }
    }

    @Test fun containerDropdownDoesNotOpenAddMenu() {
        val project = MapTopic("p", null, "Project", kind = MapKind.PROJECT)
        val stream = MapTopic("w", "p", "Workstream", kind = MapKind.WORKSTREAM)
        val selected = mutableStateOf(project.id)
        val organizing = mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                VirlinProjectMindMap("Project", listOf(project, stream), MapAppearance(),
                    onAppearanceChange = {}, onNodeOpen = {}, onBack = {},
                    onExportXmind = {}, onExportVirlin = {}, onOpenVirlinMap = {},
                    structureMode = organizing.value,
                    onToggleStructureMode = { organizing.value = !organizing.value },
                    selectedId = selected.value,
                    onAddContainer = { calls += "add" },
                    onEditPreview = { calls += "edit:${it.id}" })
            }
        }
        compose.onNodeWithTag("map_node_toggle_preview").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), calls); selected.value = stream.id }
        compose.onNodeWithTag("mind_map_branch_drag_handle").assertDoesNotExist()
        compose.onNodeWithTag(MindMapStructureToggleTag).assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithTag(MindMapStructureToggleTag).assertIsDisplayed().performClick()
        compose.onNodeWithTag("mind_map_branch_drag_handle").assertIsDisplayed()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithTag(MindMapStructureToggleTag).performClick()
        compose.onNodeWithTag("mind_map_branch_drag_handle").assertDoesNotExist()
        compose.onNodeWithTag("map_node_toggle_preview").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
        compose.onNodeWithTag("map_node_edit_preview").performClick()
        compose.runOnIdle { assertEquals(listOf("edit:w"), calls) }
    }

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
        // Containers expose the same palette, scoped to their own ownership.
        compose.onNodeWithTag(MapSelectedTaskBarTag).assertIsDisplayed()
        compose.onNodeWithTag(mapTaskActionTag("Open Workstream")).assertIsDisplayed()
        compose.onNodeWithTag(mapTaskActionTag("Page")).performClick()
        compose.onNodeWithTag(mapTaskActionTag("Add")).performClick()
        compose.onNodeWithTag("mind_map_add_note").performClick()
        compose.runOnIdle { selected.value = project.id }
        compose.onNodeWithTag("mind_map_branch_drag_handle").assertDoesNotExist()
        compose.onNodeWithTag(mapTaskActionTag("Open Project")).assertIsDisplayed()
        compose.onNodeWithTag(mapTaskActionTag("Move")).assertDoesNotExist()
        compose.onNodeWithTag(mapTaskActionTag("Page")).performClick()
        compose.onNodeWithTag(mapTaskActionTag("Add")).performClick()
        compose.onNodeWithTag("mind_map_add_note").performClick()
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
            assertEquals(listOf(stream.id to "page", stream.id to "NOTE",
                project.id to "page", project.id to "NOTE") + expected, calls)
        }
    }
}
