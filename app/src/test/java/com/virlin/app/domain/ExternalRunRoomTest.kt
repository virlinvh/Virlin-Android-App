package com.virlin.app.domain

import androidx.room.Room
import com.virlin.app.data.db.*
import com.virlin.app.domain.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ExternalRunRoomTest {
    @Test fun taskNeedsYouRankSurvivesRoomReload() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VirlinDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val now = Instant.parse("2026-10-11T00:00:00Z")
            repo.transaction { saveTask(Task("ranked", "Ranked", createdAt = now, updatedAt = now,
                execution = TaskExecution(state = WorkStreamState.CHECK, attentionRank = 2))) }
            assertEquals(2, RoomWorkStreamRepository.create(db).getTask("ranked")!!.execution!!.attentionRank)
        } finally { db.close() }
    }
    @Test fun closesSegmentsAtomicallyAndRestoresHistory() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VirlinDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val start = Instant.parse("2026-10-11T00:00:00Z")
            val task = Task("t", "Task", createdAt = start, updatedAt = start,
                execution = TaskExecution(state = WorkStreamState.PROCESSING, processingStartedAt = start))
            repo.transaction { saveTask(task) }
            repo.transaction { saveTask(task.copy(updatedAt = start.plusSeconds(10))) }
            assertTrue(repo.externalRunIntervals().isEmpty())
            val stopped = task.copy(updatedAt = start.plusSeconds(60), execution = TaskExecution(state = WorkStreamState.PAUSED))
            repo.transaction { saveTask(stopped); saveTask(stopped) }
            assertEquals(1, repo.externalRunIntervals().size)
            val restored = RoomWorkStreamRepository.create(db)
            assertEquals(listOf(ExternalRunInterval("t", "t", start, start.plusSeconds(60))), restored.externalRunIntervals())
        } finally { db.close() }
    }

    @Test fun lateReviewHandoffPersistsOnlyScheduledRunWindow() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VirlinDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val start = Instant.parse("2026-10-11T00:00:00Z")
            val due = start.plusSeconds(300)
            val task = Task("late", "Late", createdAt = start, updatedAt = start,
                execution = TaskExecution(state = WorkStreamState.PROCESSING,
                    processingStartedAt = start, checkAt = due))
            repo.transaction { saveTask(task) }
            repo.transaction { saveTask(task.copy(updatedAt = due.plusSeconds(900),
                execution = TaskExecution(state = WorkStreamState.CHECK, checkAt = due,
                    externalTrackedSeconds = 300))) }
            assertEquals(listOf(ExternalRunInterval("late", "late", start, due)), repo.externalRunIntervals())
        } finally { db.close() }
    }
}
