package com.virlin.app.pulse

import com.virlin.app.ui.pulse.PulseInput
import com.virlin.app.ui.pulse.PulseInterval
import com.virlin.app.ui.pulse.PulseKind
import com.virlin.app.ui.pulse.PulsePeriod
import com.virlin.app.ui.pulse.PulseProject
import com.virlin.app.ui.pulse.PulseTask
import com.virlin.app.ui.pulse.PulseWorkstream
import com.virlin.app.ui.pulse.calculatePulse
import com.virlin.app.ui.pulse.pulseWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The arithmetic Pulse rests on: a wall-clock union that never double-counts, overlap that is
 * part of both totals but drawn once, and periods that start and end on the viewer's calendar.
 */
class PulseAnalyticsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day: LocalDate = LocalDate.of(2026, 9, 27)
    private val minute = 60_000L

    private fun at(hour: Int, minute: Int = 0): Instant =
        day.atTime(hour, minute).atZone(zone).toInstant()

    private fun focus(id: String, from: Instant, to: Instant, task: String? = "t1", project: String? = "p1") =
        PulseInterval(id, from, to, PulseKind.FOCUS, project, "w1", task)

    private fun external(id: String, from: Instant, to: Instant, task: String? = "t2", project: String? = "p1") =
        PulseInterval(id, from, to, PulseKind.EXTERNAL, project, "w1", task)

    private fun input(vararg intervals: PulseInterval) = PulseInput(
        intervals = intervals.toList(),
        projects = listOf(PulseProject("p1", "Psychology")),
        workstreams = listOf(PulseWorkstream("w1", "p1", "Psychology")),
        tasks = listOf(
            PulseTask("t1", "Question 17", "w1", null, "p1"),
            PulseTask("t2", "Route decision", "w1", null, "p1")
        )
    )

    private fun report(vararg intervals: PulseInterval) =
        calculatePulse(input(*intervals), PulsePeriod.TODAY, day, zone, now = at(23, 59))

    // ------------------------------------------------------------------ union

    @Test fun overlappingFocusTimersAreCountedOnce() {
        val r = report(
            focus("a", at(9), at(10)),
            focus("b", at(9, 30), at(10, 30))
        )
        // 09:00–10:30 of wall clock, not 2h.
        assertEquals(90 * minute, r.focusMs)
        // The clash is reported rather than hidden: 09:30–10:00 had two records.
        assertEquals(30 * minute, r.concurrentFocusMs)
    }

    @Test fun touchingIntervalsDoNotDoubleCountTheBoundary() {
        val r = report(focus("a", at(9), at(10)), focus("b", at(10), at(11)))
        assertEquals(120 * minute, r.focusMs)
        // Half-open [start,end): the shared instant belongs to one side only.
        assertEquals(0L, r.concurrentFocusMs)
    }

    @Test fun overlappingExternalProcessesAreCountedOnce() {
        val r = report(
            external("x", at(9), at(11)),
            external("y", at(10), at(12))
        )
        assertEquals(180 * minute, r.externalMs)
    }

    @Test fun aPausedIntervalContributesNothing() {
        val r = report(focus("a", at(9), at(10)).copy(paused = true))
        assertEquals(0L, r.focusMs)
        assertEquals(0, r.sessionDurationsMs.size)
    }

    // ------------------------------------------------------------------ saved time

    @Test fun savedTimeIsFocusOnOneTaskWhileAnotherRunsExternally() {
        val r = report(
            focus("a", at(9), at(11), task = "t1"),
            external("x", at(10), at(12), task = "t2")
        )
        assertEquals(120 * minute, r.focusMs)
        assertEquals(120 * minute, r.externalMs)
        // Only the hour they truly overlapped.
        assertEquals(60 * minute, r.savedMs)
        // And it is part of both totals, never added on top of them.
        assertTrue(r.savedMs <= r.focusMs && r.savedMs <= r.externalMs)
    }

    @Test fun sameTaskOverlapSavesNothing() {
        val r = report(
            focus("a", at(9), at(11), task = "t1"),
            external("x", at(9), at(11), task = "t1")
        )
        assertEquals(0L, r.savedMs)
    }

    @Test fun overlapWithUnknownAttributionSavesNothing() {
        val r = report(
            focus("a", at(9), at(11), task = null),
            external("x", at(9), at(11), task = "t2")
        )
        assertEquals(0L, r.savedMs)
    }

    // ------------------------------------------------------------------ periods

    @Test fun theDayStartsAndEndsOnTheViewersCalendar() {
        val (from, to) = pulseWindow(PulsePeriod.TODAY, day, zone)
        assertEquals(day.atStartOfDay(zone).toInstant(), from)
        assertEquals(day.plusDays(1).atStartOfDay(zone).toInstant(), to)
    }

    @Test fun workSpanningMidnightIsClippedToTheDayItIsViewedIn() {
        val lateNight = day.minusDays(1).atTime(23, 0).atZone(zone).toInstant()
        val earlyMorning = day.atTime(1, 0).atZone(zone).toInstant()
        val r = report(focus("a", lateNight, earlyMorning))
        // Only the hour that fell inside today.
        assertEquals(60 * minute, r.focusMs)
    }

    @Test fun aRunningTimerIsMeasuredToNow_notToTheEndOfThePeriod() {
        val open = PulseInterval("a", at(9), at(9), PulseKind.FOCUS, "p1", "w1", "t1", open = true)
        val r = calculatePulse(input(open), PulsePeriod.TODAY, day, zone, now = at(10))
        assertEquals(60 * minute, r.focusMs)
    }

    @Test fun anEmptyPeriodReportsNothingRatherThanZeroedStatistics() {
        val r = report()
        assertEquals(0L, r.focusMs)
        assertEquals(0L, r.externalMs)
        assertEquals(0L, r.savedMs)
        assertTrue("no sessions means no duration statistics", r.sessionDurationsMs.isEmpty())
        assertTrue(r.projectTotals.all { it.focusMs == 0L })
    }

    // ------------------------------------------------------------------ attribution

    @Test fun projectAndWorkstreamTotalsReconcileWithTheirTasks() {
        val r = report(
            focus("a", at(9), at(10), task = "t1"),
            focus("b", at(11), at(12), task = "t2")
        )
        val project = r.projectTotals.single { it.project.id == "p1" }
        assertEquals(120 * minute, project.focusMs)
        val stream = project.workstreams.single()
        assertEquals(project.focusMs, stream.focusMs)
        assertEquals(stream.focusMs, stream.tasks.sumOf { it.focusMs })
        assertEquals(0L, project.unassignedMs)
    }

    @Test fun focusWithNoTaskShowsAsUnassignedRatherThanVanishing() {
        val r = report(focus("a", at(9), at(10), task = null))
        val project = r.projectTotals.single()
        assertEquals(60 * minute, project.focusMs)
        // It belongs to the workstream but to no task, so it is unassigned THERE — the
        // project's own "unassigned" means focus outside any workstream, which this is not.
        val stream = project.workstreams.single()
        assertEquals(60 * minute, stream.focusMs)
        assertEquals(0L, stream.tasks.sumOf { it.focusMs })
        assertEquals(0L, project.unassignedMs)
    }

    @Test fun focusWithNoWorkstreamIsUnassignedAtTheProjectLevel() {
        val loose = PulseInterval("a", at(9), at(10), PulseKind.FOCUS, "p1", null, null)
        val r = report(loose)
        val project = r.projectTotals.single()
        assertEquals(60 * minute, project.focusMs)
        assertEquals(60 * minute, project.unassignedMs)
    }

    @Test fun theBucketsSumToTheUnionWithoutCountingOverlapTwice() {
        val r = report(
            focus("a", at(9), at(11), task = "t1"),
            external("x", at(10), at(12), task = "t2")
        )
        assertEquals(r.focusMs, r.buckets.sumOf { it.focusMs })
        assertEquals(r.externalMs, r.buckets.sumOf { it.externalMs })
        assertEquals(r.savedMs, r.buckets.sumOf { it.overlapMs })
        // The wall-clock union: 09:00–12:00.
        val union = r.buckets.sumOf { it.focusMs + it.externalMs - it.overlapMs }
        assertEquals(180 * minute, union)
    }
}
