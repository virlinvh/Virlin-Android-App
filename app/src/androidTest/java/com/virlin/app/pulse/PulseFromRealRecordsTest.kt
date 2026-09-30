package com.virlin.app.pulse

import androidx.test.platform.app.InstrumentationRegistry
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.model.Cycle
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.ui.pulse.PulseInput
import com.virlin.app.ui.pulse.PulseInterval
import com.virlin.app.ui.pulse.PulseKind
import com.virlin.app.ui.pulse.PulsePeriod
import com.virlin.app.ui.pulse.PulseProject
import com.virlin.app.ui.pulse.PulseTask
import com.virlin.app.ui.pulse.PulseWorkstream
import com.virlin.app.ui.pulse.calculatePulse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pulse against the REAL database: focusing a stream through the Action Layer writes a
 * FocusSession, and that session is what Pulse reports. No sample metrics are involved.
 */
class PulseFromRealRecordsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before fun setUp() = runBlocking {
        VirlinGraph.init(context)
        VirlinGraph.ensureReady()
        withTimeout(20_000) {
            while (VirlinGraph.repository.streams.value.isEmpty()) delay(100)
        }
    }

    /** The same mapping PulseViewModel performs, from the same repository reads. */
    private fun input(
        sessions: List<FocusSession>,
        cycles: List<Cycle>,
        streams: List<WorkStream>,
        tasks: List<Task>
    ): PulseInput {
        val streamById = streams.associateBy { it.id }
        val intervals = sessions.map { s ->
            PulseInterval(
                "focus:" + s.id, s.startedAt, s.endedAt ?: s.startedAt, PulseKind.FOCUS,
                streamById[s.workStreamId]?.projectId, s.workStreamId, s.taskId,
                open = s.endedAt == null
            )
        } + cycles.mapNotNull { c ->
            val handedOff = c.handedOffAt ?: return@mapNotNull null
            PulseInterval(
                "external:" + c.id, handedOff, c.endedAt ?: handedOff, PulseKind.EXTERNAL,
                streamById[c.workStreamId]?.projectId, c.workStreamId,
                streamById[c.workStreamId]?.activeTaskId, open = c.endedAt == null
            )
        }
        return PulseInput(
            intervals,
            VirlinGraph.repository.projects.value.map { PulseProject(it.id, it.title) },
            streams.mapNotNull { s -> s.projectId?.let { PulseWorkstream(s.id, it, s.title) } },
            tasks.map { PulseTask(it.id, it.title, it.workStreamId, it.completedAt, it.projectId) }
        )
    }

    @Test fun focusingAStreamProducesTimeThatPulseReports() = runBlocking {
        val repository = VirlinGraph.repository
        val actions = VirlinGraph.actions
        // A stream that can actually take focus: READY is the ordinary starting point.
        val stream = repository.streams.value.first {
            it.projectId != null && it.state == com.virlin.app.domain.model.WorkStreamState.READY
        }

        val before = repository.allFocusSessions().size
        val focused = actions.focusStream(stream.id)
        assertTrue("focusing must be accepted, was $focused", focused is ActionResult.Success)
        // A real session row now exists and is open.
        val sessions = repository.allFocusSessions()
        assertEquals(before + 1, sessions.size)
        val opened = sessions.last { it.workStreamId == stream.id }
        assertEquals(null, opened.endedAt)

        // Pulse measures an open session up to now, not to the end of the day.
        val today = LocalDate.now(zone)
        val now = Instant.now().plusSeconds(120)
        val report = calculatePulse(
            input(sessions, repository.allCycles(), repository.streams.value, repository.tasks.value),
            PulsePeriod.TODAY, today, zone, now
        )
        assertTrue("an open session contributes real time", report.focusMs > 0L)
        assertTrue(
            "the focused stream's project carries it",
            report.projectTotals.any { it.project.id == stream.projectId && it.focusMs > 0L }
        )
        // Overlap never exceeds either total it is part of.
        assertTrue(report.savedMs <= report.focusMs)
        assertTrue(report.savedMs <= report.externalMs)

        // Leaving closes the session; the recorded time stays.
        actions.leaveFocus(stream.id)
        val closed = repository.allFocusSessions().last { it.workStreamId == stream.id }
        assertTrue("leaving ends the session", closed.endedAt != null)
        val after = calculatePulse(
            input(repository.allFocusSessions(), repository.allCycles(),
                repository.streams.value, repository.tasks.value),
            PulsePeriod.TODAY, today, zone, now
        )
        assertTrue(after.focusMs > 0L)
        assertEquals(1, after.sessionDurationsMs.count { it > 0L }.coerceAtMost(1))
    }
}
