package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.hierarchy.KnowledgeActions
import com.virlin.app.ui.hierarchy.KnowledgeItem
import com.virlin.app.ui.hierarchy.KnowledgeProjectTask
import com.virlin.app.ui.hierarchy.KnowledgeTask
import com.virlin.app.ui.hierarchy.KnowledgeType
import com.virlin.app.ui.hierarchy.KnowledgeWorkstream
import com.virlin.app.ui.hierarchy.VirlinProjectKnowledgeLibrary
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

/**
 * The Knowledge library on a device: type, workstream, grouping and search compose into one
 * result set, another project's items never appear, and the destructive controls only act on
 * an explicit confirmation.
 */
class ProjectKnowledgeLibraryUiTest {

    @get:Rule val composeRule = createComposeRule()

    private val moved = mutableListOf<Triple<String, String?, String?>>()
    private val deleted = mutableListOf<String>()
    private val duplicated = mutableListOf<String>()
    private val downloaded = mutableListOf<String>()
    private val movedMany = mutableListOf<Triple<Set<String>, String?, String?>>()
    private val openedTasks = mutableListOf<String>()
    private val placedTasks = mutableListOf<Pair<String, String?>>()
    private val organized = mutableListOf<Triple<Set<String>, Set<String>, String?>>()

    private val t0 = 1_800_000_000_000L

    private val items = listOf(
        KnowledgeItem(
            id = "prompt-1", projectId = "p1", type = KnowledgeType.PROMPT,
            title = "Timeline prompt", snippet = "## Heading\n1. Read the history",
            workstreamId = "w1", workstreamName = "Android App",
            taskId = "t1", taskName = "Wire the timeline", updatedAtMillis = t0
        ),
        KnowledgeItem(
            id = "image-1", projectId = "p1", type = KnowledgeType.IMAGE,
            title = "Screenshot", fileName = "screenshot.png",
            workstreamId = "w1", workstreamName = "Android App",
            updatedAtMillis = t0 - 1_000, sizeBytes = 9_961, mediaPath = "missing/path",
            downloadable = true
        ),
        KnowledgeItem(
            id = "audio-1", projectId = "p1", type = KnowledgeType.AUDIO,
            title = "Standup recording", updatedAtMillis = t0 - 2_000,
            durationSeconds = 3, mediaPath = "missing/clip", downloadable = true
        ),
        // Project-wide: no workstream and no task at all.
        KnowledgeItem(
            id = "note-1", projectId = "p1", type = KnowledgeType.DOCUMENT,
            title = "AI response", snippet = "Activity is a record, not a dashboard",
            updatedAtMillis = t0 - 3_000
        ),
        // Another project's item must never be shown here.
        KnowledgeItem(
            id = "other-1", projectId = "p2", type = KnowledgeType.DOCUMENT,
            title = "Someone else's note", updatedAtMillis = t0
        )
    )

    private fun show() {
        composeRule.setContent {
            VirlinTheme {
                Box(Modifier.fillMaxSize().height(3000.dp)) {
                    VirlinProjectKnowledgeLibrary(
                        projectId = "p1",
                        items = items,
                        workstreams = listOf(KnowledgeWorkstream("w1", "Android App")),
                        tasks = listOf(KnowledgeTask("t1", "Wire the timeline", "w1")),
                        actions = KnowledgeActions(
                            onAdd = {}, onOpen = {},
                            onMove = { id, ws, task -> moved += Triple(id, ws, task) },
                            onDuplicate = { duplicated += it },
                            onDownload = { downloaded += it },
                            onDelete = { deleted += it },
                            onMoveMany = { ids, ws, task -> movedMany += Triple(ids, ws, task) },
                            onOpenTask = { openedTasks += it },
                            onAddToTask = {},
                            onPlaceTask = { taskId, ws -> placedTasks += (taskId to ws) },
                            onOrganizeMany = { k, t, ws -> organized += Triple(k, t, ws) }
                        ),
                        projectTasks = listOf(
                            KnowledgeProjectTask(
                                id = "t1", projectId = "p1", title = "Wire the timeline",
                                status = "In progress", updatedAtMillis = t0 - 500,
                                workstreamId = "w1", workstreamName = "Android App"
                            )
                        ),
                        zone = ZoneId.of("Asia/Kolkata"),
                        imagePreview = { _, _ -> }
                    )
                }
            }
        }
    }

    /** The type chip, not a tile that happens to carry the same type label. */
    /** Lazy list: bring a tile into composition before touching it. */
    private fun scrollTo(description: String) {
        composeRule.onNodeWithTag(com.virlin.app.ui.hierarchy.KnowledgeListTag)
            .performScrollToNode(androidx.compose.ui.test.hasContentDescription(description))
    }

    private fun chip(label: String) = composeRule.onAllNodesWithText(label)[0]

    private fun countOf(text: String) =
        composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    @Test fun countsAndScopeAreRealAndProjectOnly() {
        show()
        // The count strip is symbol + number, one entry per type the project actually holds.
        listOf("▧ 1", "♪ 1", "❞ 1", "▤ 1", "☐ 1").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        assertEquals(0, countOf("Someone else's note"))
    }

    @Test fun typeFilterAndSearchCompose() {
        show()
        chip("Prompts").performClick()
        composeRule.onNodeWithText("Timeline prompt").assertIsDisplayed()
        assertEquals(0, countOf("Standup recording"))

        composeRule.onNodeWithText("Search prompts").performTextInput("nothing here")
        composeRule.onNodeWithText("No saved items match").assertIsDisplayed()
    }

    @Test fun groupingByWorkstreamNamesTheProjectWideSection() {
        show()
        composeRule.onNodeWithText("By type").performClick()
        composeRule.onNodeWithText("By workstream").performClick()
        // Two saved items plus the project task that lives in this workstream.
        composeRule.onNodeWithText("Android App (3)").assertIsDisplayed()
        // Items with no workstream are not homeless: they belong to the project.
        composeRule.onNodeWithText("Project-wide (2)").assertIsDisplayed()
    }

    @Test fun anItemMovesOnlyOnExplicitConfirmation() {
        show()
        composeRule.onNodeWithContentDescription("Options for Timeline prompt").performClick()
        composeRule.onNodeWithText("Move to…").performClick()
        // The sheet names the item it is about (the tile behind it carries the title too).
        assertTrue(countOf("Timeline prompt") >= 2)
        composeRule.onNodeWithText("Save directly to project").performClick()
        // Choosing a destination is not the move; the button is.
        assertEquals(0, moved.size)
        composeRule.onNodeWithText("Move to destination").performClick()
        assertEquals(listOf(Triple("prompt-1", null, null)), moved)
    }

    @Test fun deleteAsksFirst_andCancelDoesNothing() {
        show()
        composeRule.onNodeWithContentDescription("Options for Timeline prompt").performClick()
        composeRule.onNodeWithText("Archive").performClick()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted.size)

        composeRule.onNodeWithContentDescription("Options for Timeline prompt").performClick()
        composeRule.onNodeWithText("Archive").performClick()
        composeRule.onNodeWithText("Archive item").performClick()
        assertEquals(listOf("prompt-1"), deleted)
    }

    @Test fun downloadIsOfferedOnlyWhenAFileBacksTheItem() {
        show()
        composeRule.onNodeWithContentDescription("Options for Timeline prompt").performClick()
        assertEquals(0, countOf("Download"))
        composeRule.onNodeWithText("Move to…").performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        scrollTo("Options for Standup recording")
        composeRule.onNodeWithContentDescription("Options for Standup recording").performClick()
        composeRule.onNodeWithText("Download").performClick()
        assertEquals(listOf("audio-1"), downloaded)
    }

    @Test fun multiSelectSurvivesAFilterChangeAndMovesInOneCall() {
        show()
        composeRule.onNodeWithText("Select").performClick()
        composeRule.onNodeWithContentDescription("Select Timeline prompt").performClick()
        scrollTo("Select Standup recording")
        composeRule.onNodeWithContentDescription("Select Standup recording").performClick()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()

        // Narrowing the view must not silently drop what is already selected.
        chip("Images").performClick()
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Select visible").performClick()
        composeRule.onNodeWithText("3 selected").assertIsDisplayed()

        composeRule.onNodeWithText("Move 3 to…").performClick()
        composeRule.onNodeWithText("Save directly to project").performClick()
        assertEquals(0, movedMany.size)
        composeRule.onNodeWithText("Move to destination").performClick()
        assertEquals(1, movedMany.size)
        assertEquals(setOf("prompt-1", "audio-1", "image-1"), movedMany.single().first)
    }
}
