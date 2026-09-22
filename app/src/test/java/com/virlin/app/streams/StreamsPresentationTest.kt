package com.virlin.app.streams

import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.screens.StreamsDomainFacts
import com.virlin.app.ui.screens.StreamsFilter
import com.virlin.app.ui.screens.StreamsPresentation
import com.virlin.app.ui.screens.StreamsSection
import com.virlin.app.ui.theme.StreamsSectionColors
import com.virlin.app.ui.theme.VirlinPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Streams section system — pure rules: which sections, in which order, with which semantic identity. */
class StreamsPresentationTest {

    private val now: Instant = Instant.parse("2026-09-22T12:00:00Z")
    private fun ws(id: String, state: StreamState, title: String = id, subtitle: String = "", est: Int? = null) =
        WorkStream(id, title, subtitle, "p1", state, est, 0, 0, null, false, null)

    private val all = listOf(
        ws("f", StreamState.FOCUS), ws("n1", StreamState.NEEDS_YOU), ws("n2", StreamState.NEEDS_YOU), ws("p", StreamState.PROCESSING),
        ws("r", StreamState.READY, est = 300), ws("z", StreamState.SNOOZED), ws("b", StreamState.BLOCKED), ws("x", StreamState.PAUSED), ws("d", StreamState.DONE)
    )

    // A: correct sections, approved order, empty ones omitted under All; PAUSED and undated DONE never appear
    @Test fun A_all_rendersOnlyNonEmptySections_inOrder() {
        val s = StreamsPresentation.sections(all, StreamsFilter.ALL, emptyMap(), now)
        assertEquals(listOf(StreamsSection.FOCUS, StreamsSection.NEEDS_YOU, StreamsSection.PROCESSING, StreamsSection.READY, StreamsSection.SNOOZED, StreamsSection.BLOCKED), s.map { it.section })
        assertEquals(listOf("n1", "n2"), s[1].streams.map { it.id })
        val fewer = StreamsPresentation.sections(all.filter { it.state != StreamState.SNOOZED }, StreamsFilter.ALL, emptyMap(), now)
        assertTrue(fewer.none { it.section == StreamsSection.SNOOZED })
    }

    // A2: existing row order (e.g. Needs You priority) is preserved, never re-sorted here
    @Test fun A2_rowOrderIsPreserved() {
        val s = StreamsPresentation.sections(listOf(ws("n2", StreamState.NEEDS_YOU), ws("n1", StreamState.NEEDS_YOU)), StreamsFilter.NEED_YOU, emptyMap(), now)
        assertEquals(listOf("n2", "n1"), s.single().streams.map { it.id })
    }

    // B: semantic identity is deterministic and distinct per section
    @Test fun B_semanticIdentity_isDeterministic_andDistinct() {
        assertEquals(StreamsSection.NEEDS_YOU.look, StreamsSection.NEEDS_YOU.look)
        assertEquals(VirlinPalette.Sunflower, StreamsSection.NEEDS_YOU.look.accent)
        assertEquals(VirlinPalette.Mint, StreamsSection.FOCUS.look.accent)
        assertEquals(VirlinPalette.Aqua, StreamsSection.PROCESSING.look.accent)
        assertEquals(VirlinPalette.Grass, StreamsSection.READY.look.accent)
        assertEquals(VirlinPalette.Lavender, StreamsSection.SNOOZED.look.accent)
        assertEquals(VirlinPalette.Bittersweet, StreamsSection.BLOCKED.look.accent)
        assertEquals(VirlinPalette.LightGrayDark, StreamsSection.PROJECTS.look.accent)
        val accents = StreamsSection.entries.filter { it != StreamsSection.COMPLETED }.map { it.look.accent }
        assertEquals(accents.size, accents.distinct().size)
        assertEquals(StreamsSectionColors.NeedsYou, StreamsSection.NEEDS_YOU.look)
    }

    // C: filter behaviour unchanged (+ Snoozed / Blocked only narrow)
    @Test fun C_filters_selectTheirSection_projectsShowsNoStreams() {
        assertEquals(listOf(StreamsSection.NEEDS_YOU), StreamsPresentation.sections(all, StreamsFilter.NEED_YOU, emptyMap(), now).map { it.section })
        assertEquals(listOf(StreamsSection.PROCESSING), StreamsPresentation.sections(all, StreamsFilter.PROCESSING, emptyMap(), now).map { it.section })
        assertEquals(listOf(StreamsSection.READY), StreamsPresentation.sections(all, StreamsFilter.READY, emptyMap(), now).map { it.section })
        assertEquals(listOf(StreamsSection.SNOOZED), StreamsPresentation.sections(all, StreamsFilter.SNOOZED, emptyMap(), now).map { it.section })
        assertEquals(listOf(StreamsSection.BLOCKED), StreamsPresentation.sections(all, StreamsFilter.BLOCKED, emptyMap(), now).map { it.section })
        assertTrue(StreamsPresentation.sections(all, StreamsFilter.PROJECTS, emptyMap(), now).isEmpty())
        assertEquals(StreamsFilter.ALL, StreamsFilter.byLabel("nonsense"))
        assertEquals(listOf("All", "Projects", "Need You", "Processing", "Ready", "Snoozed", "Blocked"), StreamsFilter.entries.map { it.label })
    }

    // C2: search semantics unchanged — title or subtitle, case-insensitive
    @Test fun C2_search_titleOrSubtitle_caseInsensitive() {
        val list = listOf(ws("a", StreamState.READY, "Claude · Virlin", "Navigation"), ws("b", StreamState.READY, "Codex", "Methodology research"))
        assertEquals(listOf("a"), StreamsPresentation.search(list, "virlin").map { it.id })
        assertEquals(listOf("b"), StreamsPresentation.search(list, "RESEARCH").map { it.id })
        assertEquals(2, StreamsPresentation.search(list, "").size)
    }

    // F: timers — >1 h never clips; estimates only from real values
    @Test fun F_clock_andEstimate() {
        assertEquals("00:05", StreamsPresentation.clock(5)); assertEquals("04:18", StreamsPresentation.clock(258))
        assertEquals("1:00:00", StreamsPresentation.clock(3600)); assertEquals("2:03:04", StreamsPresentation.clock(7384))
        assertEquals("~5m", StreamsPresentation.estimate(300)); assertEquals("~10m", StreamsPresentation.estimate(600))
        assertEquals("~1h 30m", StreamsPresentation.estimate(5400)); assertNull(StreamsPresentation.estimate(null)); assertNull(StreamsPresentation.estimate(0))
    }

    // G: a state change moves the item between sections (no duplicate, no ghost)
    @Test fun G_stateChange_movesItemBetweenSections() {
        val before = StreamsPresentation.sections(all, StreamsFilter.ALL, emptyMap(), now)
        assertTrue(before.first { it.section == StreamsSection.NEEDS_YOU }.streams.any { it.id == "n1" })
        val after = StreamsPresentation.sections(all.map { if (it.id == "n1") it.copy(state = StreamState.PROCESSING) else it }, StreamsFilter.ALL, emptyMap(), now)
        assertTrue(after.first { it.section == StreamsSection.NEEDS_YOU }.streams.none { it.id == "n1" })
        assertTrue(after.first { it.section == StreamsSection.PROCESSING }.streams.any { it.id == "n1" })
        assertEquals(1, after.flatMap { it.streams }.count { it.id == "n1" })
    }

    // K: under a specific filter the (empty) section is still returned so the UI shows its calm message
    @Test fun K_emptySection_underFilter_isReturnedWithMessage() {
        val s = StreamsPresentation.sections(all.filter { it.state != StreamState.PROCESSING }, StreamsFilter.PROCESSING, emptyMap(), now).single()
        assertTrue(s.streams.isEmpty()); assertEquals("Nothing is running right now.", s.section.emptyMessage)
        assertEquals("Nothing needs your attention.", StreamsSection.NEEDS_YOU.emptyMessage); assertEquals("No blocked work.", StreamsSection.BLOCKED.emptyMessage)
    }

    // L: RECENTLY COMPLETED only from real DONE + completedAt inside the window; newest first; capped
    @Test fun L_recentlyCompleted_isBackedByRealCompletionStamps() {
        val done = (1..7).map { ws("d$it", StreamState.DONE) }
        val facts = done.associate { it.id to StreamsDomainFacts(completedAt = now.minusSeconds(it.id.drop(1).toLong() * 3600)) } +
            ("d7" to StreamsDomainFacts(completedAt = now.minusSeconds(30 * 3600)))                          // outside 24 h
        val s = StreamsPresentation.sections(done + ws("u", StreamState.DONE), StreamsFilter.ALL, facts, now).single()
        assertEquals(StreamsSection.COMPLETED, s.section)
        assertEquals(listOf("d1", "d2", "d3", "d4", "d5"), s.streams.map { it.id })                              // cap 5, newest first, d7 excluded, "u" (no stamp) excluded
        assertTrue(StreamsPresentation.sections(listOf(ws("u", StreamState.DONE)), StreamsFilter.ALL, emptyMap(), now).isEmpty())
        assertEquals("2 h ago", StreamsPresentation.completedLabel(now.minusSeconds(7200), now)); assertEquals("just now", StreamsPresentation.completedLabel(now, now))
    }

    // Snoozed: wake label only from a real time; nothing fabricated
    @Test fun snoozed_wakeLabel_onlyFromRealTime() {
        val zone = ZoneId.of("UTC")
        assertNull(StreamsPresentation.wakeLabel(null, now, zone))
        assertTrue(StreamsPresentation.wakeLabel(now.plusSeconds(3600), now, zone)!!.startsWith("until "))
        assertTrue(StreamsPresentation.wakeLabel(now.plusSeconds(86400 * 2), now, zone)!!.matches(Regex("until [A-Z][a-z]{2} .*")))
    }

    // Duplicate display ids never produce duplicate rows (keyed lists)
    @Test fun duplicateIds_areCollapsed() {
        val s = StreamsPresentation.sections(listOf(ws("s", StreamState.READY), ws("s", StreamState.READY)), StreamsFilter.READY, emptyMap(), now).single()
        assertEquals(1, s.streams.size)
    }
}
