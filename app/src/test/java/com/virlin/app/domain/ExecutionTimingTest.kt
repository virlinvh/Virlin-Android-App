package com.virlin.app.domain

import com.virlin.app.domain.model.ExecutionTiming
import com.virlin.app.domain.model.FocusSession
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ExecutionTimingTest {
    private val start = Instant.parse("2026-10-02T06:00:00Z")
    private val now = start.plusSeconds(3_600)

    private fun task(id: String, parent: String? = null, order: Int = 0) = Task(
        id = id, title = id.replaceFirstChar(Char::uppercase), projectId = "p",
        workStreamId = "ws", parentTaskId = parent, order = order,
        createdAt = start, updatedAt = start
    )

    @Test fun exactLeafTimeRollsUpThroughEveryAncestor_withoutDoubleCounting() {
        val tasks = listOf(task("create"), task("reminder", "create"), task("natural", "reminder"), task("image", "create", 1))
        val sessions = listOf(
            FocusSession("a", "ws", null, start, start.plusSeconds(600), "natural"),
            FocusSession("b", "ws", null, start.plusSeconds(900), start.plusSeconds(1_800), "image")
        )
        val stream = WorkStream("ws", "Claude", "p", state = WorkStreamState.READY,
            createdAt = start, updatedAt = start)

        val result = ExecutionTiming.workStream(stream, tasks, sessions, now)

        assertEquals(600L, result.exactTaskFocusSeconds["natural"])
        assertEquals(600L, result.inclusiveTaskFocusSeconds["reminder"])
        assertEquals(1_500L, result.inclusiveTaskFocusSeconds["create"])
        assertEquals(1_500L, result.totalFocusSeconds)
    }

    @Test fun focusedSummaryUsesOpenSessionAndExactCurrentTask() {
        val tasks = listOf(task("natural"))
        val stream = WorkStream("ws", "Claude", "p", state = WorkStreamState.FOCUS,
            activeTaskId = "natural", createdAt = start, updatedAt = start)
        val sessions = listOf(FocusSession("open", "ws", null, start.plusSeconds(3_000), taskId = "natural"))

        val result = ExecutionTiming.workStream(stream, tasks, sessions, now)

        assertEquals("Focusing", result.stateName)
        assertEquals(600L, result.currentSessionSeconds)
        assertEquals("Focusing · Natural · 10:00", result.summary)
    }

    @Test fun projectTotalIncludesItsStreamsOnly() {
        val streams = listOf(
            WorkStream("ws", "Mine", "p", state = WorkStreamState.READY, createdAt = start, updatedAt = start),
            WorkStream("other", "Other", "q", state = WorkStreamState.READY, createdAt = start, updatedAt = start)
        )
        val sessions = listOf(
            FocusSession("mine", "ws", null, start, start.plusSeconds(300)),
            FocusSession("other", "other", null, start, start.plusSeconds(900))
        )
        assertEquals(300L, ExecutionTiming.projectFocusSeconds("p", streams, sessions, now))
    }
}
