package com.virlin.app.domain

import androidx.room.Room
import com.virlin.app.data.db.*
import com.virlin.app.domain.action.*
import com.virlin.app.domain.id.UuidIdProvider
import com.virlin.app.domain.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MasterResumeRoomTest {
    @Test fun newRepositoryRestoresPersistedMasterCohortIncludingOldVersionPause() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VirlinDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val start = Instant.parse("2026-10-09T00:00:00Z")
            val repo = RoomWorkStreamRepository.create(db)
            val stream = WorkStream("s", "Stream", state = WorkStreamState.READY, createdAt = start, updatedAt = start)
            repo.transaction {
                saveStream(stream)
                saveTask(Task("a", "External", workStreamId = "s", createdAt = start, updatedAt = start,
                    execution = TaskExecution(state = WorkStreamState.PAUSED, externalTrackingPaused = true, externalTrackedSeconds = 600)))
                // Compatibility with pauses already performed before this toggle update.
                appendEvent(WorkStreamEvent("old-pause", "s", EventType.PAUSED, start,
                    fromState = WorkStreamState.CHECK, toState = WorkStreamState.PAUSED, detail = "task:a · All timers paused"))
            }
            val restored = RoomWorkStreamRepository.create(db)
            val clock = FakeClock(start.plusSeconds(3600))
            val actions = DefaultVirlinActions(restored, clock, UuidIdProvider)
            assertEquals(ActionResult.Success(1), actions.resumeAllTimers())
            assertEquals(WorkStreamState.CHECK, restored.getTask("a")!!.execution!!.state)
            clock.current = start.plusSeconds(3660)
            assertEquals(660L, ExternalTiming.seconds(restored.getTask("a")!!.execution, clock.current))
            assertEquals(ActionResult.Success(0), actions.resumeAllTimers())
        } finally { db.close() }
    }
}
