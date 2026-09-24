package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.NewExternalStage
import com.virlin.app.domain.action.StartExternalWork
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.external.ExternalWork
import com.virlin.app.domain.model.ExternalActor
import com.virlin.app.domain.model.ExternalStageStatus
import com.virlin.app.domain.model.ExternalStages
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * PHASE 10 — external work ("Working For You"): the run's identity, its derived check time, the
 * due hand-over to Needs You, the check outcomes and the stage model.
 *
 * Everything here runs on a [FakeClock]: no sleeps, no real time, no per-second writes.
 */
class ExternalWorkTest {

    private val t0: Instant = Instant.parse("2026-09-23T09:00:00Z")
    private val clock = FakeClock(t0)

    private fun stream(id: String, state: WorkStreamState = WorkStreamState.READY, project: String? = "p1") = WorkStream(
        id = id, title = id, projectId = project, state = state, createdAt = t0, updatedAt = t0
    )

    private fun harness(vararg streams: WorkStream): Pair<InMemoryWorkStreamRepository, DefaultVirlinActions> {
        val repo = InMemoryWorkStreamRepository(
            seed = streams.toList(),
            seedProjects = listOf(Project(id = "p1", title = "Virlin", createdAt = t0, updatedAt = t0))
        )
        return repo to DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }

    private suspend fun DefaultVirlinActions.task(title: String, ws: String): Task =
        (createTask(CreateTask(title = title, workStreamId = ws)) as ActionResult.Success).value

    private suspend fun DefaultVirlinActions.delegate(
        ws: String, actor: ExternalActor = ExternalActor.CLAUDE_CODE, instruction: String? = "Implement listening state",
        workItemId: String? = null, minutes: Long? = 5, stages: List<NewExternalStage> = emptyList()
    ) = startExternalWork(
        StartExternalWork(ws, actor, instruction, workItemId, checkInMinutes = minutes, stages = stages)
    )

    private fun working(repo: InMemoryWorkStreamRepository, now: Instant = clock.now()) =
        ExternalWork.workingForYou(repo.streams.value, now, repo.stages.value, repo.projects.value, repo.tasks.value)

    // ------------------------------------------------------------------ 1-10 basic external work

    @Test fun startExternalWork_keepsActorInstructionWorkItemAndDerivesCheckTime() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        val item = actions.task("Implement listening state", "ws1")
        val result = actions.delegate("ws1", workItemId = item.id, minutes = 5)
        assertTrue(result is ActionResult.Success)

        val s = repo.getStream("ws1")!!
        assertEquals(WorkStreamState.PROCESSING, s.state)                       // 8: projects to Working For You
        assertEquals("claude_code", s.externalActorId)                          // 2: actor retained
        assertEquals("Implement listening state", s.waitingFor)                 // 5: instruction retained
        assertEquals(item.id, s.activeTaskId)                                   // 3: hierarchy work item retained
        assertEquals(t0, s.processingStartedAt)                                 // 6: startedAt retained
        assertEquals(t0.plus(Duration.ofMinutes(5)), s.checkAt)                 // 7: checkAt calculated
        assertEquals("ws1", working(repo).single().id)                          // 4: stable identity is the stream
        assertEquals("Claude Code", working(repo).single().actorName)
    }

    @Test fun multipleExternalRunsCoexist_andHumanFocusIsUntouched() = runBlocking {
        val (repo, actions) = harness(stream("ws1"), stream("ws2"), stream("human"))
        actions.focusStream("human")
        actions.delegate("ws1", ExternalActor.CLAUDE_CODE, minutes = 4)
        actions.delegate("ws2", ExternalActor.GEMINI, instruction = "Research", minutes = 12)

        assertEquals(2, working(repo).size)                                     // 9: many are normal
        val human = repo.getStream("human")!!
        assertEquals(WorkStreamState.FOCUS, human.state)                        // 10: Current Focus unaffected
        assertNotNull(repo.getOpenFocusSession("human"))
        assertNull(human.checkAt)
    }

    // ------------------------------------------------------------------ 11-20 time

    @Test fun countdownIsDerived_survivesBackgrounding_andNeverWritesPerSecond() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        actions.delegate("ws1", minutes = 5)
        val updatedAt = repo.getStream("ws1")!!.updatedAt

        assertEquals("00:05:00", AttentionTiming.format(repo.getStream("ws1")!!.checkAt, clock.now()))   // 11
        clock.advance(Duration.ofMinutes(2))
        assertEquals("00:03:00", AttentionTiming.format(repo.getStream("ws1")!!.checkAt, clock.now()))   // 12
        // 13/14: the app was away for two minutes and NOTHING was written; only the clock moved.
        assertEquals(updatedAt, repo.getStream("ws1")!!.updatedAt)
        // 15: a restart re-reads the same timestamps, so the derivation is identical.
        val reopened = ExternalWork.item(repo.getStream("ws1")!!)
        assertEquals("00:03:00", reopened.countdown(clock.now()))
    }

    @Test fun longRuns_dueBoundary_andOverdueBoundary() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        actions.delegate("ws1", minutes = 27 * 60 + 15)                          // 16: > 24h
        val due = repo.getStream("ws1")!!.checkAt!!
        assertEquals("27:15:00", AttentionTiming.format(due, clock.now()))
        clock.current = due
        assertEquals("00:00:00", AttentionTiming.format(due, clock.now()))       // 17: due boundary
        assertTrue(working(repo, clock.now()).isEmpty())                         // no longer "working for you"
        clock.advance(Duration.ofSeconds(12))
        assertEquals("+00:00:12", AttentionTiming.format(due, clock.now()))      // 18: overdue boundary
    }

    @Test fun workingForYouIsSortedBySoonestCheck_withDeterministicTies() = runBlocking {
        val (repo, actions) = harness(stream("b"), stream("a"), stream("c"), stream("d"))
        actions.delegate("c", minutes = 12)
        actions.delegate("b", minutes = 4)
        actions.delegate("a", minutes = 4)
        actions.delegate("d", minutes = null)                                    // running, nothing planned
        // 19 soonest first; 20 equal checkAt breaks by id; no-check items come last.
        assertEquals(listOf("a", "b", "c", "d"), working(repo).map { it.id })
    }

    // ------------------------------------------------------------------ 21-28 due transition

    @Test fun dueExternalWorkBecomesNeedsYou_sameIdentity_noDuplicate() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        val item = actions.task("Implement listening state", "ws1")
        actions.delegate("ws1", workItemId = item.id, minutes = 5)

        assertEquals(1, working(repo).size)                                      // 21: before checkAt
        clock.advance(Duration.ofMinutes(5))
        assertTrue(working(repo).isEmpty())                                      // 22: at checkAt it is attention
        actions.checkDue("ws1")                                                  // the scheduler's transition
        val s = repo.getStream("ws1")!!
        assertEquals(WorkStreamState.CHECK, s.state)                             // 23: Needs You
        assertEquals("ws1", s.id)                                                // 24: same stable identity
        assertEquals(item.id, s.activeTaskId)
        assertEquals("claude_code", s.externalActorId)
        assertEquals(1, repo.streams.value.count { it.id == "ws1" })             // 25: no duplicate row
        // 26/27/28: it enters the canonical Needs You queue, so ordering and policies still apply.
        val queue = NeedsYouOrder.queue(repo.streams.value)
        assertEquals(listOf("ws1"), queue.map { it.stream.id })
        assertEquals(1, queue.single().rank)
    }

    // ------------------------------------------------------------------ 29-39 check outcomes

    @Test fun checkItselfCompletesNothing_andStillRunningReturnsTheSameRun() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        val item = actions.task("Implement listening state", "ws1")
        actions.delegate("ws1", workItemId = item.id, minutes = 5, stages = listOf(NewExternalStage("Implement", 5)))
        clock.advance(Duration.ofMinutes(5)); actions.checkDue("ws1")

        actions.checkStream("ws1")
        assertEquals(WorkStreamState.CHECK, repo.getStream("ws1")!!.state)       // 29: CHECK completes nothing
        assertTrue(!repo.getTask(item.id)!!.status.isTerminal)

        // 31/33-37: still running → PROCESSING again with a new check time; nothing else changes.
        listOf(3L, 5L, 10L, 47L).forEach { minutes ->
            val at = AttentionTiming.checkAgainAt(clock.now(), minutes)
            assertTrue(actions.markExternalStillRunning("ws1", at) is ActionResult.Success)
            val s = repo.getStream("ws1")!!
            assertEquals(WorkStreamState.PROCESSING, s.state)
            assertEquals(at, s.checkAt)
            assertEquals("claude_code", s.externalActorId)                       // 38: actor preserved
            assertEquals(item.id, s.activeTaskId)                                // 39: work item preserved
            assertEquals(1, repo.streams.value.size)                             // 37: same identity, no copy
            assertEquals(ExternalStageStatus.IN_PROGRESS, repo.stages.value.single().status)
            clock.advance(Duration.ofMinutes(minutes)); actions.checkDue("ws1")
        }
    }

    @Test fun blockedExternalWorkIsHumanAttention_withReason() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        actions.delegate("ws1", minutes = 5)
        clock.advance(Duration.ofMinutes(5)); actions.checkDue("ws1")

        assertTrue(actions.markExternalBlocked("ws1", "Which API should I use?") is ActionResult.Success)  // 32
        val s = repo.getStream("ws1")!!
        assertEquals(WorkStreamState.BLOCKED, s.state)
        assertEquals("Which API should I use?", s.blockerReason)
        assertTrue(working(repo).isEmpty())                                      // not Working For You any more
    }

    // ------------------------------------------------------------------ 40-45 result ready

    @Test fun resultReady_staysAttention_andFocusNowTakesTheExactWorkItem() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        val item = actions.task("Implement listening state", "ws1")
        actions.delegate("ws1", workItemId = item.id, minutes = 5, stages = listOf(NewExternalStage("Implement", 5)))
        clock.advance(Duration.ofMinutes(5)); actions.checkDue("ws1")

        assertTrue(actions.markExternalResultReady("ws1") is ActionResult.Success)   // 30
        val ready = repo.getStream("ws1")!!
        assertEquals(WorkStreamState.CHECK, ready.state)                            // 40: remains Needs You
        assertTrue(working(repo).isEmpty())                                         // not back to Working For You
        assertTrue(!repo.getTask(item.id)!!.status.isTerminal)                      // external ≠ human completion
        assertEquals(ExternalStageStatus.DONE, repo.stages.value.single().status)

        assertTrue(actions.focusExternalResult("ws1") is ActionResult.Success)       // 42
        val focused = repo.getStream("ws1")!!
        assertEquals(WorkStreamState.FOCUS, focused.state)
        assertEquals(item.id, focused.activeTaskId)                                 // exact work item
        assertEquals(1, repo.tasks.value.size)                                      // 44: no duplicate Task
        assertNull(focused.checkAt)                                                 // 45: no longer processing
        assertNotNull(repo.getOpenFocusSession("ws1"))
    }

    @Test fun deferringAReadyResultStaysAttention_neverProcessing() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        actions.delegate("ws1", minutes = 5)
        clock.advance(Duration.ofMinutes(5)); actions.checkDue("ws1")
        actions.markExternalResultReady("ws1")

        val later = clock.now().plus(Duration.ofMinutes(10))
        assertTrue(actions.deferReadyResult("ws1", later) is ActionResult.Success)   // 41
        val s = repo.getStream("ws1")!!
        assertEquals(WorkStreamState.SNOOZED, s.state)
        assertEquals(com.virlin.app.domain.model.SnoozeReason.EXTERNAL_RESULT_READY, s.snoozeReason)
        assertNull(s.processingStartedAt)
        assertTrue(working(repo).isEmpty())
    }

    @Test fun focusNowOnAReadyResultWhileFocusedElsewhere_displacesExactlyLikePhase09() = runBlocking {
        val (repo, actions) = harness(stream("ws1"), stream("human"))
        val item = actions.task("Implement listening state", "ws1")
        actions.focusStream("human")
        actions.delegate("ws1", workItemId = item.id, minutes = 5)
        clock.advance(Duration.ofMinutes(5)); actions.checkDue("ws1"); actions.markExternalResultReady("ws1")

        assertTrue(actions.focusExternalResult("ws1") is ActionResult.Success)       // 43: one switching system
        assertEquals(WorkStreamState.FOCUS, repo.getStream("ws1")!!.state)
        assertEquals(WorkStreamState.READY, repo.getStream("human")!!.state)
        assertEquals(1, repo.streams.value.count { it.state == WorkStreamState.FOCUS })
    }

    // ------------------------------------------------------------------ 46-56 stages

    @Test fun stages_areExplicitlyOrdered_andAdvanceOnlyWhenAsked() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        actions.delegate(
            "ws1", minutes = null, instruction = "Build authentication",
            stages = listOf(
                NewExternalStage("Analyze current implementation", 3),
                NewExternalStage("Implement fix", 8),
                NewExternalStage("Run tests", 5),
                NewExternalStage("Report result", 2)
            )
        )
        val stages = repo.getStages("ws1")
        assertEquals(4, stages.size)                                                // 46/47
        assertEquals(listOf(0, 1, 2, 3), stages.map { it.order })                   // 48: explicit order
        assertEquals("Analyze current implementation", ExternalStages.current(stages, "ws1")!!.title)   // 49
        assertEquals(3L, stages.first().expectedMinutes)                            // 50
        assertEquals(t0.plus(Duration.ofMinutes(3)), repo.getStream("ws1")!!.checkAt) // 53 (first stage)

        // 51/52: the result of stage 1 completes it and offers stage 2 — nothing starts by itself.
        clock.advance(Duration.ofMinutes(3)); actions.checkDue("ws1")
        actions.markExternalResultReady("ws1")
        assertEquals(ExternalStageStatus.DONE, repo.getStages("ws1").first().status)
        assertEquals(WorkStreamState.CHECK, repo.getStream("ws1")!!.state)          // 54: REVIEW FIRST = no start
        assertEquals("Implement fix", ExternalStages.current(repo.getStages("ws1"), "ws1")!!.title)

        // 53: START NEXT STAGE derives the new check time from the stage's expected duration.
        val startedAt = clock.now()
        assertTrue(actions.startNextExternalStage("ws1") is ActionResult.Success)
        val s = repo.getStream("ws1")!!
        assertEquals(WorkStreamState.PROCESSING, s.state)
        assertEquals(startedAt.plus(Duration.ofMinutes(8)), s.checkAt)
        assertEquals("Implement fix", ExternalStages.current(repo.getStages("ws1"), "ws1")!!.title)
        assertEquals(1, working(repo).size)
        assertEquals("Stage 2 · Implement fix", working(repo).single().stageLabel())
    }

    @Test fun finalStageHasNoNextStage() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        actions.delegate("ws1", minutes = null, stages = listOf(NewExternalStage("Only", 2)))
        clock.advance(Duration.ofMinutes(2)); actions.checkDue("ws1"); actions.markExternalResultReady("ws1")

        val result = actions.startNextExternalStage("ws1")                          // 55
        assertTrue(result is ActionResult.Rejected && result.reason == DomainError.NoNextStage)
        assertTrue(ExternalStages.next(repo.getStages("ws1"), "ws1", ExternalStages.current(repo.getStages("ws1"), "ws1")) == null)
    }

    @Test fun stagesAndRunSurviveAReload_becauseNothingIsHeldInMemory() = runBlocking {
        val (repo, actions) = harness(stream("ws1"))
        actions.delegate("ws1", minutes = 5, stages = listOf(NewExternalStage("Implement", 5), NewExternalStage("Test", 3)))
        // 56: a "restart" re-reads the same persisted rows; the projection is identical.
        val before = working(repo).single()
        val reloaded = ExternalWork.workingForYou(
            repo.streams.value, clock.now(), repo.getStages("ws1"), repo.projects.value, repo.tasks.value
        ).single()
        assertEquals(before.stageLabel(), reloaded.stageLabel())
        assertEquals(before.checkAt, reloaded.checkAt)
        assertEquals(before.actorName, reloaded.actorName)
    }

    // ------------------------------------------------------------------ performance (§52)

    @Test fun twentyFiveConcurrentRuns_projectInOnePass_withOneClockValue() = runBlocking {
        val streams = (1..25).map { stream("ws$it") }
        val (repo, actions) = harness(*streams.toTypedArray())
        streams.forEachIndexed { i, s -> actions.delegate(s.id, minutes = (i + 1).toLong()) }

        val start = System.nanoTime()
        val items = working(repo)
        val millis = (System.nanoTime() - start) / 1_000_000
        assertEquals(25, items.size)
        assertTrue("projection took ${millis}ms", millis < 250)
        assertEquals((1..25).map { "ws$it" }, items.map { it.id })                  // soonest first, deterministic
        // One clock value renders them all; no run holds a ticker or writes per second.
        val now = clock.now()
        assertEquals(items.map { AttentionTiming.format(it.checkAt, now) }, items.map { it.countdown(now) })
    }
}
