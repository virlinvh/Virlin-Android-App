package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** The Focus timer stops while the app is away and continues as the same Focus period. */
class FocusClockPauseTest {
    private val start = Instant.parse("2026-11-10T09:00:00Z")
    private fun stream(id: String) =
        WorkStream(id, id, projectId = "p", state = WorkStreamState.READY, createdAt = start, updatedAt = start)

    private class Fixture(taskExecution: Boolean = false, tasks: List<Task> = emptyList(), streams: List<WorkStream>) {
        val repo = InMemoryWorkStreamRepository(streams, seedTasks = tasks, taskExecutionEnabled = taskExecution)
        val clock = FakeClock(Instant.parse("2026-11-10T09:00:00Z"))
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        fun at(seconds: Long) { clock.current = Instant.parse("2026-11-10T09:00:00Z").plusSeconds(seconds) }
        suspend fun pause() = (actions.suspendFocusClock() as ActionResult.Success).value
        suspend fun resume(p: FocusClockPause) = (actions.resumeFocusClock(p) as ActionResult.Success).value
        suspend fun split(id: String, task: String?, paused: String? = null) =
            FocusInvestment.split(repo.getFocusSessions(id), task, clock.current, paused)
    }

    @Test fun timeAwayIsNotCountedAndThePeriodContinues() = runTest {
        val f = Fixture(streams = listOf(stream("s")))
        assertTrue(f.actions.focusStream("s") is ActionResult.Success)
        f.at(600)
        val pause = f.pause()!!
        assertEquals(WorkStreamState.FOCUS, f.repo.getStream("s")!!.state)
        f.at(4_200) // an hour away
        assertEquals("the flap holds its value while away", 600L, f.split("s", null, pause.sessionId).currentSeconds)
        assertTrue(f.resume(pause))
        f.at(4_290)
        val split = f.split("s", null)
        assertEquals("continues from where it stopped", 690L, split.currentSeconds)
        assertEquals(0L, split.priorSeconds)
        assertTrue(f.repo.getFocusSessions("s").any { it.isOpen && it.id.startsWith(pause.sessionId + "/") })
        assertEquals(1, f.repo.getFocusSessions("s").count { it.isOpen })
    }

    @Test fun aNewFocusPeriodStartsAtZeroWhileTheTotalKeepsEverything() = runTest {
        val f = Fixture(streams = listOf(stream("s")))
        f.actions.focusStream("s")
        f.at(300); val p = f.pause()!!
        f.at(900); f.resume(p)
        f.at(1_000)
        assertTrue(f.actions.leaveFocus("s") is ActionResult.Success)
        f.at(86_400) // the next day
        f.actions.focusStream("s")
        f.at(86_460)
        val split = f.split("s", null)
        assertEquals("the flap starts the new period at zero", 60L, split.currentSeconds)
        assertEquals("the earlier periods stay saved", 400L, split.priorSeconds)
    }

    @Test fun taskTimeIsSavedPerTaskAcrossPauses() = runTest {
        val t = Task("t", "Write", workStreamId = "s", projectId = "p", createdAt = start, updatedAt = start)
        val f = Fixture(tasks = listOf(t), streams = listOf(stream("s")))
        f.actions.setActiveTask("s", "t")
        f.actions.focusStream("s")
        f.at(120); val p = f.pause()!!
        assertEquals("t", p.taskId)
        f.at(5_000); f.resume(p)
        f.at(5_030)
        assertEquals(150L, FocusInvestment.total(f.repo.getFocusSessions("s"), "t", f.clock.current).seconds)
    }

    @Test fun nothingResumesWhenFocusChangedWhileAway() = runTest {
        val f = Fixture(streams = listOf(stream("a"), stream("b")))
        f.actions.focusStream("a")
        f.at(60); val p = f.pause()!!
        f.at(120); f.actions.focusStream("b") // e.g. from a notification
        f.at(180)
        assertFalse(f.resume(p))
        assertNull(f.repo.getFocusSessions("a").firstOrNull { it.isOpen })
        assertEquals("the paused minute stays saved", 60L, f.split("a", null).priorSeconds)
    }

    @Test fun pausingWithoutFocusDoesNothingAndResumeIsIdempotent() = runTest {
        val f = Fixture(streams = listOf(stream("s")))
        assertNull(f.pause())
        f.actions.focusStream("s")
        f.at(60); val p = f.pause()!!
        assertTrue(f.resume(p))
        assertFalse("a second resume never opens a second session", f.resume(p))
        assertEquals(1, f.repo.getFocusSessions("s").count { it.isOpen })
    }

    @Test fun aStopTimeIsClampedToTheSession() = runTest {
        val f = Fixture(streams = listOf(stream("s")))
        f.at(100); f.actions.focusStream("s")
        f.at(400)
        val p = (f.actions.suspendFocusClock(start.plusSeconds(50)) as ActionResult.Success).value!!
        val closed = f.repo.getFocusSessions("s").first { it.id == p.sessionId }
        assertEquals("never before the session began", start.plusSeconds(100), closed.endedAt)
        f.resume(p)
        f.at(430)
        assertEquals(30L, f.split("s", null).currentSeconds)
    }

    @Test fun manualPauseHoldsTheTimeUntilResume() = runTest {
        val f = Fixture(streams = listOf(stream("s")))
        f.actions.focusStream("s")
        f.at(300)
        assertNotNull(f.pause())
        f.at(2_000) // a long break, still in Focus
        assertEquals(WorkStreamState.FOCUS, f.repo.getStream("s")!!.state)
        val held = f.repo.getFocusSessions("s").maxByOrNull { it.startedAt }!!.id
        assertEquals(300L, f.split("s", null, held).currentSeconds)
        assertTrue((f.actions.resumePausedFocus() as ActionResult.Success).value)
        f.at(2_060)
        assertEquals("continues from the held time", 360L, f.split("s", null).currentSeconds)
        assertFalse("nothing to resume while running", (f.actions.resumePausedFocus() as ActionResult.Success).value)
    }

    @Test fun manualPauseSurvivesTheAppGoingAwayAndBack() = runTest {
        val f = Fixture(streams = listOf(stream("s")))
        f.actions.focusStream("s")
        f.at(120); f.pause()
        f.at(500)
        assertNull("leaving the app stops nothing more", f.pause())
        f.at(900)
        assertEquals(1, f.repo.getFocusSessions("s").size)
        assertTrue((f.actions.resumePausedFocus() as ActionResult.Success).value)
        f.at(930)
        assertEquals(150L, f.split("s", null).currentSeconds)
    }

    @Test fun resumeAfterTheTaskChangedStartsAFreshSessionForIt() = runTest {
        val t = Task("t", "Write", workStreamId = "s", projectId = "p", createdAt = start, updatedAt = start)
        val f = Fixture(tasks = listOf(t), streams = listOf(stream("s")))
        f.actions.focusStream("s")
        f.at(100); f.pause()
        // The current task changed while paused (setActiveTask opens a session for it: no longer paused).
        f.actions.setActiveTask("s", "t")
        assertFalse((f.actions.resumePausedFocus() as ActionResult.Success).value)
        assertEquals(1, f.repo.getFocusSessions("s").count { it.isOpen && it.taskId == "t" })
    }

    @Test fun independentTaskExecutionPausesAndResumesTheSameTask() = runTest {
        val t = Task("t", "Write", workStreamId = "s", projectId = "p",
            executionPreference = ExecutionPreference.HUMAN, createdAt = start, updatedAt = start)
        val f = Fixture(taskExecution = true, tasks = listOf(t), streams = listOf(stream("s")))
        assertTrue(f.actions.startFocus("t") is ActionResult.Success)
        f.at(200); val p = f.pause()!!
        assertEquals("t", p.taskId)
        assertEquals(WorkStreamState.FOCUS, f.repo.getTask("t")!!.execution!!.state)
        f.at(3_000); assertTrue(f.resume(p))
        f.at(3_100)
        assertEquals(300L, FocusInvestment.total(f.repo.allFocusSessions(), "t", f.clock.current).seconds)
    }
}
