package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.action.Field
import com.virlin.app.domain.action.FocusTargetOutcome
import com.virlin.app.domain.action.TaskUpdate
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.model.FocusInvestment
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.progress.ProgressCalculator
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
 * Phase 09 — Current Focus as real execution: focus an exact WORK ITEM, one human focus at a time,
 * investment that belongs to the item and survives leaving and resuming, LEAVE ≠ COMPLETE, and
 * deterministic NEXT. Deterministic time only (FakeClock); nothing here waits.
 */
class FocusExecutionTest {

    private val t0: Instant = Instant.parse("2026-09-23T09:00:00Z")
    private val clock = FakeClock(t0)

    private fun stream(id: String, state: WorkStreamState = WorkStreamState.READY) = WorkStream(
        id = id, title = id, projectId = "p1", state = state, createdAt = t0, updatedAt = t0
    )
    private lateinit var repo: InMemoryWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions

    private fun harness(streams: List<WorkStream> = listOf(stream("ws1"))) {
        clock.current = t0
        repo = InMemoryWorkStreamRepository(
            seed = streams,
            seedProjects = listOf(Project(id = "p1", title = "Virlin", createdAt = t0, updatedAt = t0))
        )
        actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
    }
    private suspend fun task(title: String, ws: String = "ws1", parent: String? = null): Task =
        (actions.createTask(CreateTask(title = title, workStreamId = ws, parentTaskId = parent)) as ActionResult.Success).value
    private fun advance(minutes: Long) { clock.current = clock.current.plus(Duration.ofMinutes(minutes)) }
    private suspend fun invested(taskId: String): Duration =
        FocusInvestment.total(repo.getFocusSessions("ws1") + repo.getFocusSessions("ws2"), taskId, clock.current)
    private fun focusedStream() = repo.streams.value.firstOrNull { it.state == WorkStreamState.FOCUS }
    private fun activeItem() = focusedStream()?.activeTaskId

    /** ws1: Orb (Opening ✓ · Listening · Speaking) · Test */
    private suspend fun seedTree(): Map<String, Task> {
        val orb = task("Orb Interaction")
        val opening = task("Opening", parent = orb.id)
        val listening = task("Listening", parent = orb.id)
        val speaking = task("Speaking", parent = orb.id)
        val test = task("Test Pixel 8")
        actions.completeTask(opening.id)
        return mapOf("orb" to orb, "opening" to opening, "listening" to listening, "speaking" to speaking, "test" to test)
    }

    // ------------------------------------------------------------------ 1–9: the focus target

    @Test fun test1_2_focusLeafDirectly_andContainerResolvesFirstOpenLeaf() = runBlocking {
        harness(); val t = seedTree()
        val direct = actions.startFocus(t["speaking"]!!.id) as ActionResult.Success
        assertEquals("Speaking", direct.value.target.workItem.title)
        actions.leaveFocus("ws1")

        val viaContainer = actions.startFocus(t["orb"]!!.id) as ActionResult.Success
        assertEquals("Listening", viaContainer.value.target.workItem.title)   // Opening is done → first OPEN leaf
        assertEquals("ws1", viaContainer.value.target.workStreamId)
        // Resolving never mutated completion state.
        assertTrue(repo.getTask(t["opening"]!!.id)!!.status.isCompleted)
        assertTrue(!repo.getTask(t["listening"]!!.id)!!.status.isTerminal)
    }

    @Test fun test3_4_5_completedCancelledAndExhaustedContainersAreRejected() = runBlocking {
        harness(); val t = seedTree()
        assertEquals(DomainError.TaskAlreadyClosed, (actions.startFocus(t["opening"]!!.id) as ActionResult.Rejected).reason)
        actions.cancelTask(t["listening"]!!.id)
        assertEquals(DomainError.TaskAlreadyClosed, (actions.startFocus(t["listening"]!!.id) as ActionResult.Rejected).reason)
        // Container with only terminal leaves → rejected, nothing focused, nothing reopened.
        actions.completeTask(t["speaking"]!!.id)
        assertEquals(DomainError.TaskAlreadyClosed, (actions.startFocus(t["orb"]!!.id) as ActionResult.Rejected).reason)
        assertNull(focusedStream())
        assertTrue(repo.getTask(t["speaking"]!!.id)!!.status.isCompleted)
    }

    @Test fun test6_7_8_stableIdentity_renameSafe_andHierarchyContextResolves() = runBlocking {
        harness(); val t = seedTree()
        actions.startFocus(t["listening"]!!.id)
        actions.updateTask(t["listening"]!!.id, TaskUpdate(title = Field.Set("Listening state")))
        assertEquals(t["listening"]!!.id, activeItem())                        // focus is by id, not title
        val path = (actions.activePath("ws1") as ActionResult.Success).value.map { it.title }
        assertEquals(listOf("Listening state", "Orb Interaction"), path)       // leaf-first ancestry
        assertEquals("p1", repo.getStream("ws1")!!.projectId)
    }

    @Test fun test9_onlyOneHumanFocusEverExists() = runBlocking {
        harness(listOf(stream("ws1"), stream("ws2"))); val t = seedTree()
        val other = task("Question 17", ws = "ws2")
        actions.startFocus(t["listening"]!!.id)
        actions.startFocus(other.id)
        assertEquals(1, repo.streams.value.count { it.state == WorkStreamState.FOCUS })
        assertEquals("ws2", focusedStream()!!.id)
        assertEquals(1, repo.getFocusSessions("ws1").count { it.isOpen } + repo.getFocusSessions("ws2").count { it.isOpen })
    }

    // ------------------------------------------------------------------ 10–21: timer and investment

    @Test fun test10_11_12_13_14_15_investmentAccumulatesAcrossSessions() = runBlocking {
        harness(); val t = seedTree()
        actions.startFocus(t["listening"]!!.id)
        advance(18)
        assertEquals(Duration.ofMinutes(18), invested(t["listening"]!!.id))    // derived from the open session
        actions.leaveFocus("ws1")                                              // session closes at 18m
        advance(60)                                                             // time away is NOT invested
        assertEquals(Duration.ofMinutes(18), invested(t["listening"]!!.id))
        actions.startFocus(t["listening"]!!.id)                                // resume: new session from 0
        advance(10)
        assertEquals(Duration.ofMinutes(28), invested(t["listening"]!!.id))    // 18 + 10
        assertEquals(2, repo.getFocusSessions("ws1").count { it.taskId == t["listening"]!!.id })
    }

    @Test fun test16_17_completeAndSwitchPersistTheFinalDuration() = runBlocking {
        harness(listOf(stream("ws1"), stream("ws2"))); val t = seedTree()
        val other = task("Question 17", ws = "ws2")
        actions.startFocus(t["listening"]!!.id); advance(7)
        actions.startFocus(other.id)                                           // SWITCH closes the old session
        assertEquals(Duration.ofMinutes(7), invested(t["listening"]!!.id))
        advance(5)
        assertEquals(Duration.ofMinutes(7), invested(t["listening"]!!.id))     // frozen after the switch
        actions.completeTask(other.id)
        assertEquals(Duration.ofMinutes(5), invested(other.id))
    }

    @Test fun test18_19_20_noPerSecondWrites_recoveryAndLargeDurations() = runBlocking {
        harness(); val t = seedTree()
        actions.startFocus(t["listening"]!!.id)
        val writes = repo.getFocusSessions("ws1").size
        repeat(120) { advance(1); invested(t["listening"]!!.id) }              // two hours of "ticks"
        assertEquals("one row per session, never per second", writes, repo.getFocusSessions("ws1").size)
        assertEquals(Duration.ofHours(2), invested(t["listening"]!!.id))
        // "Restart": rebuild every object from the persisted rows; the open session keeps accruing.
        val rebuilt = InMemoryWorkStreamRepository(seed = repo.streams.value.map { it.copy() })
        assertEquals(t["listening"]!!.id, rebuilt.streams.value.first { it.state == WorkStreamState.FOCUS }.activeTaskId)
        advance(600)
        assertEquals(Duration.ofHours(12), invested(t["listening"]!!.id))      // 12h renders fine
    }

    // ------------------------------------------------------------------ 22–31: LEAVE

    @Test fun test22_23_24_leaveKeepsWorkIncomplete_clearsFocus_keepsInvestment() = runBlocking {
        harness(); val t = seedTree()
        actions.startFocus(t["listening"]!!.id); advance(12)
        actions.leaveFocus("ws1")
        assertNull(focusedStream())
        assertTrue(!repo.getTask(t["listening"]!!.id)!!.status.isTerminal)     // NOT completed
        assertEquals(Duration.ofMinutes(12), invested(t["listening"]!!.id))
        assertEquals(t["listening"]!!.id, repo.getStream("ws1")!!.activeTaskId) // context preserved
        assertEquals(WorkStreamState.READY, repo.getStream("ws1")!!.state)
    }

    @Test fun test25_26_27_28_leaveWithReturnTime_schedulesTheSameWork() = runBlocking {
        listOf(3L, 5L, 10L, 45L).forEach { minutes ->
            harness(); val t = seedTree()
            actions.startFocus(t["listening"]!!.id); advance(4)
            val tasksBefore = repo.tasks.value.size
            actions.leaveFocus("ws1", returnAt = clock.current.plus(Duration.ofMinutes(minutes)))
            val ws = repo.getStream("ws1")!!
            assertEquals("+$minutes", WorkStreamState.SNOOZED, ws.state)
            assertEquals(clock.current.plus(Duration.ofMinutes(minutes)), ws.snoozedUntil)
            assertEquals(com.virlin.app.domain.model.SnoozeReason.HUMAN_RETURN, ws.snoozeReason)
            assertEquals(t["listening"]!!.id, ws.activeTaskId)                  // returns to the EXACT item
            assertEquals("no duplicate work item", tasksBefore, repo.tasks.value.size)
            assertEquals(Duration.ofMinutes(4), invested(t["listening"]!!.id))
        }
    }

    @Test fun test29_30_31_leaveWithoutReminder() = runBlocking {
        harness(); val t = seedTree()
        actions.startFocus(t["listening"]!!.id); advance(3)
        val before = repo.tasks.value.size
        actions.leaveFocus("ws1", returnAt = null)
        val ws = repo.getStream("ws1")!!
        assertEquals(WorkStreamState.READY, ws.state)                          // available, not scheduled
        assertNull(ws.snoozedUntil)
        assertEquals(before, repo.tasks.value.size)
        assertEquals(t["listening"]!!.id, ws.activeTaskId)
        assertTrue(!repo.getTask(t["listening"]!!.id)!!.status.isTerminal)
    }

    // ------------------------------------------------------------------ 32–41: COMPLETE and NEXT

    @Test fun test32_33_34_35_completeMarksExactlyThatLeaf() = runBlocking {
        harness(); val t = seedTree()
        actions.startFocus(t["listening"]!!.id); advance(9)
        actions.completeTask(t["listening"]!!.id)
        assertTrue(repo.getTask(t["listening"]!!.id)!!.status.isCompleted)
        assertTrue("parent never auto-completes", !repo.getTask(t["orb"]!!.id)!!.status.isCompleted)
        val p = ProgressCalculator.ofWorkStream(repo.tasks.value, "ws1") as ProgressResult.Structured
        assertEquals(2, p.completedLeaves); assertEquals(4, p.totalLeaves)
        assertNull("focus clears, never auto-advances", repo.getStream("ws1")!!.activeTaskId)
        assertEquals(Duration.ofMinutes(9), invested(t["listening"]!!.id))     // final duration persisted
    }

    @Test fun test36_37_38_39_nextAndFocusNextRequireIntent() = runBlocking {
        harness(); val t = seedTree()
        actions.startFocus(t["listening"]!!.id); advance(2)
        actions.completeTask(t["listening"]!!.id)
        assertEquals("Speaking", (actions.nextTaskCandidate("ws1") as ActionResult.Success).value?.title)
        assertNull("nothing starts on its own", repo.getStream("ws1")!!.activeTaskId)   // DONE FOR NOW
        val next = (actions.focusNext("ws1") as ActionResult.Success).value!!            // FOCUS NEXT
        assertEquals("Speaking", next.target.workItem.title)
        assertEquals(t["speaking"]!!.id, activeItem())
        assertEquals(1, repo.getFocusSessions("ws1").count { it.isOpen })                // exactly one session
        // …and when everything is closed there is simply no candidate.
        actions.completeTask(t["speaking"]!!.id); actions.completeTask(t["test"]!!.id)
        assertNull((actions.focusNext("ws1") as ActionResult.Success).value)
    }

    @Test fun test40_41_nestedTraversalSkipsTerminalItems() = runBlocking {
        harness(); val t = seedTree()
        actions.cancelTask(t["listening"]!!.id)
        val started = (actions.startFocus(t["orb"]!!.id) as ActionResult.Success).value
        assertEquals("Speaking", started.target.workItem.title)                // cancelled skipped
        actions.completeTask(t["speaking"]!!.id)
        assertEquals("Test Pixel 8", (actions.nextTaskCandidate("ws1") as ActionResult.Success).value?.title)
    }

    // ------------------------------------------------------------------ 42–48: switching

    @Test fun test42_43_resolvePreviewsWithoutMutating_andCancelChangesNothing() = runBlocking {
        harness(listOf(stream("ws1"), stream("ws2"))); val t = seedTree()
        val other = task("Question 17", ws = "ws2")
        actions.startFocus(t["listening"]!!.id); advance(6)
        val snapshot = repo.streams.value
        val preview = (actions.resolveFocusTarget(other.id) as ActionResult.Success).value
        assertTrue(preview.isSwitch)
        assertEquals("ws1", preview.displacedStreamId)
        assertEquals(t["listening"]!!.id, preview.displacedWorkItemId)
        assertEquals("preview must not write", snapshot, repo.streams.value)   // CANCEL = simply do nothing
        assertEquals(t["listening"]!!.id, activeItem())
    }

    @Test fun test44_45_46_47_48_switchClosesTheOldSessionAndStartsOne() = runBlocking {
        harness(listOf(stream("ws1"), stream("ws2"))); val t = seedTree()
        val other = task("Question 17", ws = "ws2")
        actions.startFocus(t["listening"]!!.id); advance(15)
        val outcome = (actions.startFocus(other.id) as ActionResult.Success).value
        assertEquals("ws1", outcome.displaced?.id)
        assertEquals(WorkStreamState.READY, repo.getStream("ws1")!!.state)
        assertTrue(!repo.getTask(t["listening"]!!.id)!!.status.isTerminal)      // old work stays open
        assertEquals(Duration.ofMinutes(15), invested(t["listening"]!!.id))     // and keeps its investment
        assertEquals(t["listening"]!!.id, repo.getStream("ws1")!!.activeTaskId) // and its context
        assertEquals(other.id, activeItem())
        assertEquals(0, repo.getFocusSessions("ws1").count { it.isOpen })
        assertEquals(1, repo.getFocusSessions("ws2").count { it.isOpen })
    }

    // ------------------------------------------------------------------ 49–54: the other subsystems

    @Test fun test49_50_51_52_53_54_attentionIsUnaffectedByHumanFocus() = runBlocking {
        val check = WorkStream(
            id = "wsCheck", title = "Claude · Virlin", projectId = "p1", state = WorkStreamState.CHECK,
            checkAt = t0.minusSeconds(600), attentionRank = 1, createdAt = t0, updatedAt = t0
        )
        harness(listOf(stream("ws1"), check)); val t = seedTree()
        val queueBefore = NeedsYouOrder.queue(repo.streams.value).map { it.stream.id to it.rank }
        val dueBefore = repo.getStream("wsCheck")!!.checkAt
        actions.startFocus(t["listening"]!!.id); advance(5)
        actions.leaveFocus("ws1", returnAt = clock.current.plus(Duration.ofMinutes(5)))
        assertEquals("Needs You queue untouched", queueBefore, NeedsYouOrder.queue(repo.streams.value).map { it.stream.id to it.rank })
        assertEquals(dueBefore, repo.getStream("wsCheck")!!.checkAt)
        assertEquals(1, repo.getStream("wsCheck")!!.attentionRank)
        // The human return is a SNOOZE on the same stream — it never becomes external processing.
        assertEquals(WorkStreamState.SNOOZED, repo.getStream("ws1")!!.state)
        assertTrue(repo.streams.value.none { it.state == WorkStreamState.PROCESSING })
        assertNotNull(repo.getStream("ws1")!!.activeTaskId)
    }

    // ------------------------------------------------------------------ 32 (edge): completed elsewhere

    @Test fun completingTheFocusedItemFromTheHierarchy_leavesNoStaleFocus() = runBlocking {
        harness(); val t = seedTree()
        actions.startFocus(t["listening"]!!.id); advance(11)
        actions.completeTask(t["listening"]!!.id)                               // "completed elsewhere"
        assertNull(repo.getStream("ws1")!!.activeTaskId)
        assertEquals(Duration.ofMinutes(11), invested(t["listening"]!!.id))     // investment kept
        assertEquals("Speaking", (actions.nextTaskCandidate("ws1") as ActionResult.Success).value?.title)
    }
}
