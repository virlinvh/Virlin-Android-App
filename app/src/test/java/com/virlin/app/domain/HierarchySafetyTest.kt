package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HierarchySafetyTest {
    private val now = Instant.parse("2026-10-04T08:00:00Z")
    private fun task(id: String, parent: String? = null, external: Boolean = false) = Task(id, id,
        workStreamId = "s", parentTaskId = parent,
        executionPreference = if (external) ExecutionPreference.EXTERNAL else ExecutionPreference.HUMAN,
        createdAt = now, updatedAt = now)
    @Test fun parentCompletion_closesDescendantsAndCycles_notSibling() = runTest {
        val repo = InMemoryWorkStreamRepository(listOf(WorkStream("s", "Stream", state = WorkStreamState.READY, createdAt = now, updatedAt = now)),
            seedTasks = listOf(task("parent"), task("human", "parent"), task("nested", "parent"),
                task("external", "nested", true), task("sibling", external = true)), taskExecutionEnabled = true)
        val clock = FakeClock(now)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(actions.startFocus("human") is ActionResult.Success)
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "external")) is ActionResult.Success)
        assertTrue(actions.startExternalWork(StartExternalWork("s", workItemId = "sibling")) is ActionResult.Success)
        clock.advance(java.time.Duration.ofSeconds(90))
        assertTrue(actions.completeTask("parent") is ActionResult.Success)
        listOf("parent", "human", "nested", "external").forEach { assertEquals(TaskStatus.DONE, repo.getTask(it)!!.status) }
        assertEquals(90L, repo.getTask("external")!!.execution!!.externalTrackedSeconds)
        assertNull(repo.getTask("external")!!.execution!!.currentCycleId)
        assertNull(repo.getTask("external")!!.execution!!.checkAt)
        assertEquals(0, repo.allFocusSessions().count { it.isOpen })
        assertEquals(WorkStreamState.PROCESSING, repo.getTask("sibling")!!.execution!!.state)
        assertTrue(actions.restoreTask("external") is ActionResult.Success)
        assertNull(repo.getTask("external")!!.execution!!.currentCycleId)
        assertEquals(TaskStatus.TODO, repo.getTask("parent")!!.status)
        assertEquals(TaskStatus.TODO, repo.getTask("nested")!!.status)
    }
    @Test fun descendants_handlesTenThousandDeepNodesWithoutRecursion() {
        val tasks = (0..10_000).map { task("$it", if (it == 0) null else "${it - 1}") }
        assertEquals(10_000, TaskHierarchy.descendants(tasks, "0").size)
        assertEquals(com.virlin.app.domain.progress.ProgressCalculator.ofTask(tasks, "0"),
            com.virlin.app.domain.progress.ProgressCalculator.forTasks(tasks).getValue("0"))
    }
    @Test fun descendants_doesNotLoopOnCorruptCycle() {
        assertEquals(listOf("b"), TaskHierarchy.descendants(listOf(task("a", "b"), task("b", "a")), "a").map { it.id })
    }
    @Test fun parentCompletion_failureRollsBackTasksAndSessions() = runTest {
        val repo = InMemoryWorkStreamRepository(listOf(WorkStream("s", "Stream", state = WorkStreamState.READY, createdAt = now, updatedAt = now)),
            seedTasks = listOf(task("parent"), task("human", "parent"), task("external", "parent", true)), taskExecutionEnabled = true)
        val clock = FakeClock(now)
        val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())
        assertTrue(actions.startFocus("human") is ActionResult.Success)
        val before = repo.tasks.value
        val sessions = repo.allFocusSessions()
        val failing = object : WorkStreamRepository by repo {
            override suspend fun <T> transaction(block: suspend WorkStreamWriter.() -> T): T = repo.transaction {
                val writer = this
                val injected = object : WorkStreamWriter by writer {
                    override suspend fun saveTask(task: Task) {
                        if (task.id == "external") error("Simulated disk failure")
                        writer.saveTask(task)
                    }
                }
                injected.block()
            }
        }
        assertTrue(DefaultVirlinActions(failing, clock, SequentialIdProvider()).completeTask("parent") is ActionResult.Failure)
        assertEquals(before, repo.tasks.value)
        assertEquals(sessions, repo.allFocusSessions())
    }
    @Test fun batchedProgress_matchesExistingCountAndEffortRules() {
        val statuses = listOf(TaskStatus.TODO, TaskStatus.DONE, TaskStatus.CANCELLED)
        val tasks = (0..200).map { i -> task("$i", if (i == 0) null else "${(i - 1) / 5}")
            .copy(status = statuses[i % 3], estimatedEffort = if (i % 4 == 0) null else java.time.Duration.ofMinutes((i % 8).toLong())) }
        val results = com.virlin.app.domain.progress.ProgressCalculator.forTasks(tasks)
        tasks.forEach { assertEquals(it.id, com.virlin.app.domain.progress.ProgressCalculator.ofTask(tasks, it.id), results[it.id]) }
    }
}
