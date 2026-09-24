package com.virlin.app.streams

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.model.Project
import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.components.projectIconBuiltInTag
import com.virlin.app.ui.hierarchy.HierarchyViewModel
import com.virlin.app.ui.hierarchy.ProgressLabel
import com.virlin.app.ui.screens.StreamsCompletedToggleTag
import com.virlin.app.ui.screens.StreamsContent
import com.virlin.app.ui.screens.StreamsDomainFacts
import com.virlin.app.ui.screens.StreamsListTag
import com.virlin.app.ui.screens.StreamsSection
import com.virlin.app.ui.screens.streamsEmptyTag
import com.virlin.app.ui.screens.streamsFilterTag
import com.virlin.app.ui.screens.streamsSectionTag
import com.virlin.app.ui.theme.VirlinTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Streams section redesign — the rendered screen (no navigation, no repository): sections, cards, filters, motion, semantics. */
class StreamsContentUiTest {

    @get:Rule val composeRule = createComposeRule()
    private val now: Instant = Instant.parse("2026-09-22T12:00:00Z")
    private val t0 = now.minusSeconds(86400)

    private fun ws(id: String, state: StreamState, title: String = id, subtitle: String = "sub $id", est: Int? = null, focus: Int = 0, proc: Int = 0, reason: String? = null) =
        WorkStream(id, title, subtitle, "p1", state, est, focus, proc, null, false, null, reason)
    private val projects = listOf(
        HierarchyViewModel.ProjectSummary(Project("p1", "Virlin Development", createdAt = t0, updatedAt = t0), 5, ProgressLabel("41%", 0.41f)),
        HierarchyViewModel.ProjectSummary(Project("p2", "A very long project title that keeps going and going", createdAt = t0, updatedAt = t0), 1, ProgressLabel("0%", 0f))
    )
    private val seed = listOf(
        ws("f", StreamState.FOCUS, "Psychology", focus = 53),
        ws("n", StreamState.NEEDS_YOU, "Claude · Virlin"),
        ws("p", StreamState.PROCESSING, "Build · Release APK", proc = 7384),
        ws("r", StreamState.READY, "Notion Transfer", est = 300),
        ws("z", StreamState.SNOOZED, "Design Review"),
        ws("b", StreamState.BLOCKED, "Cloud Sync", reason = "Waiting for authentication credentials"),
        ws("d", StreamState.DONE, "Old thing")
    )

    private lateinit var streams: androidx.compose.runtime.MutableState<List<WorkStream>>
    private val opened = mutableListOf<String>()

    private fun show(widthDp: Int = 411, fontScale: Float = 1f, initialFilter: String = "All", list: List<WorkStream> = seed) {
        opened.clear()
        streams = mutableStateOf(list)
        composeRule.setContent {
            var filter by androidx.compose.runtime.remember { mutableStateOf(initialFilter) }
            var query by androidx.compose.runtime.remember { mutableStateOf("") }
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                VirlinTheme {
                    Box(Modifier.width(widthDp.dp).fillMaxHeight()) {
                        StreamsContent(
                            streams = streams.value, projectSummaries = projects,
                            facts = mapOf("z" to StreamsDomainFacts(snoozedUntil = now.plusSeconds(3600)), "d" to StreamsDomainFacts(completedAt = now.minusSeconds(600))),
                            now = now, searchQuery = query, onSearchChange = { query = it },
                            filter = filter, onFilterSelected = { filter = it },
                            onOpenProject = { opened += "project:$it" }, onOpenStream = { opened += "stream:$it" },
                            reducedMotion = false, zone = ZoneId.of("UTC")
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
    private fun scrollTo(tag: String) { composeRule.onNodeWithTag(StreamsListTag).performScrollToNode(hasTestTag(tag)); composeRule.waitForIdle() }
    private fun node(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    @Test fun A_allSectionsRender_inOrder_withCounts() {
        show()
        listOf(StreamsSection.PROJECTS, StreamsSection.FOCUS, StreamsSection.NEEDS_YOU, StreamsSection.PROCESSING, StreamsSection.READY, StreamsSection.SNOOZED, StreamsSection.BLOCKED, StreamsSection.COMPLETED)
            .forEach { scrollTo(streamsSectionTag(it)); node(streamsSectionTag(it)).assertIsDisplayed() }
        scrollTo(streamsSectionTag(StreamsSection.NEEDS_YOU))
        node(streamsSectionTag(StreamsSection.NEEDS_YOU)).assertContentDescriptionContains("Needs you, 1, Attention required", substring = true)
        scrollTo(streamsSectionTag(StreamsSection.FOCUS))
        val focusY = node(streamsSectionTag(StreamsSection.FOCUS)).fetchSemanticsNode().positionInRoot.y
        scrollTo(streamsSectionTag(StreamsSection.NEEDS_YOU))
        assert(node(streamsSectionTag(StreamsSection.NEEDS_YOU)).fetchSemanticsNode().positionInRoot.y > focusY)
    }

    @Test fun C_D_E_filters_navigation_check() {
        show()
        node("project_row_p1").performClick(); assertEquals(listOf("project:p1"), opened)          // D: project navigation
        scrollTo("stream_row_n"); node("stream_row_n").performClick(); assertEquals("stream:n", opened.last())   // E: Needs You row still opens the stream
        composeRule.onNode(hasTestTag("stream_row_n") and hasAnyDescendant(hasText("CHECK")), useUnmergedTree = true).assertExists()   // CHECK label preserved
        composeRule.onNodeWithTag(streamsFilterTag("Need You")).performClick(); composeRule.waitForIdle()      // C: filter narrows
        composeRule.onNodeWithTag(streamsFilterTag("Need You")).assertIsSelected()
        scrollTo("stream_row_n"); node("stream_row_n").assertIsDisplayed(); composeRule.onAllNodesWithTag("stream_row_p", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithTag("project_row_p1", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag(streamsFilterTag("Blocked")).performScrollTo().performClick(); composeRule.waitForIdle()
        scrollTo("stream_row_b"); node("stream_row_b").assertIsDisplayed(); composeRule.onAllNodesWithTag("stream_row_n", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithTag(streamsFilterTag("Projects")).performClick(); composeRule.waitForIdle()
        scrollTo("project_row_p1"); node("project_row_p1").assertIsDisplayed(); composeRule.onAllNodesWithTag("stream_row_b", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun F_timers_realValues_overOneHour_estimate_wake_reason() {
        show()
        scrollTo("stream_row_p"); composeRule.onNode(hasTestTag("stream_row_p") and hasAnyDescendant(hasText("2:03:04 elapsed")), useUnmergedTree = true).assertExists()
        scrollTo("stream_row_f"); composeRule.onNode(hasTestTag("stream_row_f") and hasAnyDescendant(hasText("00:53 invested")), useUnmergedTree = true).assertExists()
        scrollTo("stream_row_r"); composeRule.onNode(hasTestTag("stream_row_r") and hasAnyDescendant(hasText("~5m")), useUnmergedTree = true).assertExists()
        scrollTo("stream_row_z"); composeRule.onNode(hasTestTag("stream_row_z") and hasAnyDescendant(hasText("until", substring = true)), useUnmergedTree = true).assertExists()
        scrollTo("stream_row_b"); composeRule.onNode(hasTestTag("stream_row_b") and hasAnyDescendant(hasText("Waiting for authentication credentials")), useUnmergedTree = true).assertExists()
        // Timer tick = same row, new text, no re-layout of sections
        streams.value = streams.value.map { if (it.id == "p") it.copy(processingElapsedSec = 7385) else it }; composeRule.waitForIdle()
        scrollTo("stream_row_p"); composeRule.onNode(hasTestTag("stream_row_p") and hasAnyDescendant(hasText("2:03:05 elapsed")), useUnmergedTree = true).assertExists()
    }

    @Test fun G_stateTransition_movesCardBetweenSections() {
        show()
        scrollTo("stream_row_n"); node("stream_row_n").assertIsDisplayed()
        node(streamsSectionTag(StreamsSection.NEEDS_YOU)).assertContentDescriptionContains("Needs you, 1", substring = true)
        streams.value = streams.value.map { if (it.id == "n") it.copy(state = StreamState.PROCESSING) else it }   // CHECK → still running
        composeRule.mainClock.advanceTimeBy(400); composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(streamsSectionTag(StreamsSection.NEEDS_YOU), useUnmergedTree = true).assertCountEquals(0)   // section gone (was its only item)
        scrollTo(streamsSectionTag(StreamsSection.PROCESSING)); node(streamsSectionTag(StreamsSection.PROCESSING)).assertContentDescriptionContains("Processing, 2", substring = true)
        scrollTo("stream_row_n"); composeRule.onAllNodesWithTag("stream_row_n", useUnmergedTree = true).assertCountEquals(1)
        streams.value = streams.value.map { if (it.id == "r") it.copy(state = StreamState.FOCUS) else it }          // Ready → start
        composeRule.mainClock.advanceTimeBy(400); composeRule.waitForIdle()
        scrollTo(streamsSectionTag(StreamsSection.FOCUS)); node(streamsSectionTag(StreamsSection.FOCUS)).assertContentDescriptionContains("Focus, 2", substring = true)
    }

    @Test fun H_projectIcons_unchanged() {
        show()
        composeRule.onNode(hasTestTag("project_row_p1") and hasAnyDescendant(hasTestTag(projectIconBuiltInTag("code"))), useUnmergedTree = true).assertExists()
        node("project_row_p1").assertContentDescriptionContains("Virlin Development, 5 WorkStreams, 41%", substring = true)
    }

    @Test fun I_narrow335_fontScale13_nothingHidden() {
        show(widthDp = 335, fontScale = 1.3f)
        node("project_row_p2").assertIsDisplayed()
        composeRule.onNode(hasTestTag("project_row_p2") and hasAnyDescendant(hasText("0%")), useUnmergedTree = true).assertExists()
        scrollTo("stream_row_p"); composeRule.onNode(hasTestTag("stream_row_p") and hasAnyDescendant(hasText("2:03:04 elapsed")), useUnmergedTree = true).assertExists()
        val row = node("stream_row_p").fetchSemanticsNode().boundsInRoot
        val timer = composeRule.onNode(hasText("2:03:04 elapsed"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assert(timer.right <= row.right + 1f && timer.left >= row.left) { "timer clipped: $timer in $row" }
        scrollTo("stream_row_n"); composeRule.onNode(hasTestTag("stream_row_n") and hasAnyDescendant(hasText("CHECK")), useUnmergedTree = true).assertExists()
    }

    @Test fun J_accessibility_semantics() {
        show()
        scrollTo("stream_row_n"); node("stream_row_n").assertContentDescriptionContains("Claude · Virlin, sub n, Needs you, Check", substring = true)
        scrollTo("stream_row_p"); node("stream_row_p").assertContentDescriptionContains("Processing, 2:03:04 elapsed", substring = true)
        scrollTo("stream_row_b"); node("stream_row_b").assertContentDescriptionContains("Blocked, Waiting for authentication credentials", substring = true)
        composeRule.onNodeWithTag(streamsFilterTag("Ready")).assertContentDescriptionContains("Ready filter", substring = true)
        // decorative project icon does not announce on its own; the row speaks the project once
        composeRule.onAllNodesWithTag("project_icon", useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
            assert(!n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
        }
    }

    @Test fun K_emptyStates_perFilter_andNothingAtAll() {
        show(list = seed.filter { it.state != StreamState.PROCESSING })
        composeRule.onNodeWithTag(streamsFilterTag("Processing")).performClick(); composeRule.waitForIdle()
        node(streamsEmptyTag(StreamsSection.PROCESSING)).assertIsDisplayed().assertTextEquals("Nothing is running right now.")
        composeRule.onNodeWithTag(streamsFilterTag("Need You")).performClick(); composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(streamsEmptyTag(StreamsSection.NEEDS_YOU), useUnmergedTree = true).assertCountEquals(0)   // has an item → no message
        composeRule.onNodeWithTag(streamsFilterTag("All")).performClick(); composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(streamsEmptyTag(StreamsSection.PROCESSING), useUnmergedTree = true).assertCountEquals(0)   // All: empty sections are simply omitted
    }

    @Test fun L_recentlyCompleted_onlyWithRealStamp_collapsedByDefault() {
        show()
        scrollTo(streamsSectionTag(StreamsSection.COMPLETED)); node(streamsSectionTag(StreamsSection.COMPLETED)).assertContentDescriptionContains("Recently completed, 1", substring = true)
        composeRule.onAllNodesWithTag("stream_row_d", useUnmergedTree = true).assertCountEquals(0)                    // collapsed
        node(StreamsCompletedToggleTag).performClick(); composeRule.waitForIdle()
        scrollTo("stream_row_d"); composeRule.onNode(hasTestTag("stream_row_d") and hasAnyDescendant(hasText("10 min ago")), useUnmergedTree = true).assertExists()
        streams.value = streams.value.map { if (it.id == "d") it.copy(id = "d2") else it }                             // a DONE stream with NO stamp
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(streamsSectionTag(StreamsSection.COMPLETED), useUnmergedTree = true).assertCountEquals(0)
    }
}
