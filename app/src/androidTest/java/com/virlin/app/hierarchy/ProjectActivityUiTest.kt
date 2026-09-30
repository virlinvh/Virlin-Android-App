package com.virlin.app.hierarchy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.virlin.app.ui.hierarchy.ActivityActions
import com.virlin.app.ui.hierarchy.ActivityKind
import com.virlin.app.ui.hierarchy.ActivityRecord
import com.virlin.app.ui.hierarchy.ActivityWorkstream
import com.virlin.app.ui.hierarchy.VirlinProjectActivity
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Activity timeline on a device: what the chips claim is what the list shows, the record is
 * grouped by the user's own local day, and an entry opens its own detail rather than expanding
 * the day around it.
 */
class ProjectActivityUiTest {

    @get:Rule val composeRule = createComposeRule()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 9, 25)
    private val opened = mutableListOf<String>()
    private val copied = mutableListOf<String>()

    /** 09:00 local on [day]. */
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) =
        day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val records = listOf(
        ActivityRecord(
            id = "r1", projectId = "p1", occurredAtMillis = at(today, 14),
            kind = ActivityKind.TASK, title = "Completed Draft the spec",
            workstreamId = "w1", workstreamName = "Planning",
            oldStatus = "In_progress", newStatus = "Done"
        ),
        ActivityRecord(
            id = "r2", projectId = "p1", occurredAtMillis = at(today.minusDays(1), 10),
            kind = ActivityKind.PROMPT, title = "Route structure prompt",
            workstreamId = "w2", workstreamName = "Navigation",
            content = "line one\n\n- bullet", knowledgeId = "c1", captureId = "c1"
        ),
        // Just before local midnight: it belongs to the PREVIOUS day in this zone, and would
        // land on a different day entirely if the feed grouped by UTC.
        ActivityRecord(
            id = "r3", projectId = "p1", occurredAtMillis = at(today.minusDays(1), 23, 45),
            kind = ActivityKind.EVENT, title = "Blocked", workstreamId = "w2",
            workstreamName = "Navigation"
        ),
        // Another project's row must never reach this timeline.
        ActivityRecord(
            id = "r4", projectId = "p2", occurredAtMillis = at(today, 15),
            kind = ActivityKind.TASK, title = "Someone else's task"
        )
    )

    private fun show() {
        composeRule.setContent {
            VirlinTheme {
                Box(Modifier.fillMaxSize().height(900.dp)) {
                    VirlinProjectActivity(
                        projectId = "p1",
                        records = records,
                        workstreams = listOf(ActivityWorkstream("w1", "Planning"), ActivityWorkstream("w2", "Navigation")),
                        actions = ActivityActions(
                            onBack = {}, onOpenTask = { opened += it }, onOpenKnowledge = { opened += it },
                            onOpenAsset = { opened += it }, onCopyPrompt = { copied += it }
                        ),
                        zone = zone,
                        today = today,
                        imagePreview = { _, _ -> }
                    )
                }
            }
        }
    }

    @Test fun mixedFeedGroupsByTheUsersOwnDay_andExcludesOtherProjects() {
        show()
        composeRule.onNodeWithText("Today · 25 Sep 2026").assertIsDisplayed()
        composeRule.onNodeWithText("Yesterday · 24 Sep 2026").assertIsDisplayed()
        composeRule.onNodeWithText("Completed Draft the spec").assertIsDisplayed()
        // 23:45 local on the 24th is yesterday's row, not today's.
        composeRule.onNodeWithText("Blocked").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Someone else's task").fetchSemanticsNodes().size)
    }

    @Test fun filteringByTypeAgreesWithTheChips() {
        show()
        composeRule.onNodeWithText("☷  Filter").performClick()
        composeRule.onNodeWithText("Prompt").performClick()
        composeRule.onNodeWithText("Show 1 result").performClick()
        composeRule.onNodeWithText("1 active filters").assertIsDisplayed()
        composeRule.onNodeWithText("Route structure prompt").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Completed Draft the spec").fetchSemanticsNodes().size)
    }

    @Test fun anEntryOpensItsOwnDetail_andCopiesTheExactText() {
        show()
        composeRule.onNodeWithText("Route structure prompt").performClick()
        // A detail, not an expanded day: the other entries are gone.
        assertEquals(0, composeRule.onAllNodesWithText("Completed Draft the spec").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Copy").performClick()
        assertEquals(listOf("line one\n\n- bullet"), copied)
        composeRule.onNodeWithText("Open in Knowledge").performClick()
        assertEquals(listOf("c1"), opened)
    }

    @Test fun orderCanBeReversed() {
        show()
        composeRule.onNodeWithText("Newest first ⌄").performClick()
        composeRule.onNodeWithText("Oldest first").performClick()
        composeRule.onNodeWithText("Oldest first ⌄").assertIsDisplayed()
        composeRule.onNodeWithText("Yesterday · 24 Sep 2026").assertIsDisplayed()
    }

    @Test fun aProjectWithNoActivitySaysSo() {
        composeRule.setContent {
            VirlinTheme {
                Box(Modifier.fillMaxSize().height(700.dp)) {
                    VirlinProjectActivity(
                        projectId = "empty", records = emptyList(), workstreams = emptyList(),
                        actions = ActivityActions({}, {}, {}, {}, {}),
                        zone = zone, today = today, imagePreview = { _, _ -> }
                    )
                }
            }
        }
        composeRule.onNodeWithText("Nothing has happened in this project yet").assertIsDisplayed()
    }
}
