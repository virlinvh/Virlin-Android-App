package com.virlin.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virlin.app.data.db.RoomPriorityPreferences
import com.virlin.app.data.db.RoomWorkStreamRepository
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.attention.PriorityPreference
import com.virlin.app.domain.attention.PriorityScope
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * Phase 07 against the REAL database: the v10 → v11 migration keeps every existing row, durable
 * policies survive closing and reopening the database (the honest stand-in for process death, since
 * nothing is kept in memory across `open()`), and expiry is handled in SQL.
 */
@RunWith(AndroidJUnit4::class)
class PriorityPersistenceRoomTest {

    private class Clock(var now: Instant) : VirlinClock { override fun now() = now }
    private class Ids : IdProvider { private var n = 0; override fun newId(prefix: String) = "$prefix-${++n}" }

    private val t0: Instant = Instant.parse("2026-09-23T10:00:00Z")
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "virlin-priority-persistence-test.db"
    private lateinit var db: VirlinDatabase
    private lateinit var repo: RoomWorkStreamRepository
    private lateinit var prefs: RoomPriorityPreferences
    private lateinit var actions: DefaultVirlinActions
    private val clock = Clock(t0)

    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), VirlinDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    private fun open() = runBlocking {
        db = Room.databaseBuilder(ctx, VirlinDatabase::class.java, dbName).addMigrations(*VirlinDatabase.MIGRATIONS).build()
        repo = RoomWorkStreamRepository.create(db)
        prefs = RoomPriorityPreferences(db)
        actions = DefaultVirlinActions(repo, clock, Ids(), prefs)
    }
    /** Destroy every in-memory object and rebuild purely from the file. */
    private fun restart() { db.close(); open() }

    @Before fun setUp() { ctx.deleteDatabase(dbName); clock.now = t0; open() }
    @After fun tearDown() { db.close(); ctx.deleteDatabase(dbName) }

    private fun item(id: String, offset: Long) = WorkStream(
        id = id, title = id, state = WorkStreamState.CHECK, checkAt = t0.plusSeconds(offset),
        createdAt = t0.minusSeconds(7200), updatedAt = t0.minusSeconds(7200)
    )
    private suspend fun seed() = repo.transaction { listOf(item("A", 0), item("B", 10), item("C", 20), item("D", 30)).forEach { saveStream(it) } }
    private fun ids() = NeedsYouOrder.queue(repo.streams.value).map { it.stream.id }

    // ------------------------------------------------------------------ 23–25: the migration

    @Test fun migrate10To11_addsPriorityPreferences_andKeepsEveryExistingRow() {
        val migrationDb = "virlin-migration-10-11.db"
        ctx.deleteDatabase(migrationDb)
        val ms = t0.toEpochMilli()
        helper.createDatabase(migrationDb, 10).apply {
            execSQL("INSERT INTO projects (id,title,description,status,priority,dueAt,estimatedEffort,defaultExecutionMode,createdAt,updatedAt,completedAt,iconPath,iconId) " +
                "VALUES ('p1','Virlin Development','d','ACTIVE','HIGH',NULL,144000000,'HUMAN',$ms,$ms,NULL,'p1/icon-1.png','code')")
            execSQL("INSERT INTO workstreams (id,title,projectId,tool,executionPreference,state,priority,pinned,lastHumanAction,waitingFor,nextHumanAction,blockerReason,processingStartedAt,checkAt,snoozedUntil,snoozeReason,currentCycleId,cycleCount,activeTaskId,createdAt,updatedAt,completedAt,attentionRank) " +
                "VALUES ('s3','Claude · Virlin',NULL,'Claude','EXTERNAL','CHECK','NORMAL',0,NULL,'route decision',NULL,NULL,$ms,${ms + 600_000},NULL,NULL,NULL,1,NULL,$ms,$ms,NULL,2)")
            execSQL("INSERT INTO tasks (id,title,projectId,workStreamId,parentTaskId,status,sortOrder,estimatedEffort,priority,executionPreference,createdAt,updatedAt,completedAt) " +
                "VALUES ('t1','Route structure','p1','s3',NULL,'TODO',0,900000,'NORMAL','INHERIT',$ms,$ms,NULL)")
            close()
        }
        helper.runMigrationsAndValidate(migrationDb, 11, true, VirlinDatabase.MIGRATION_10_11).close()

        val migrated = Room.databaseBuilder(ctx, VirlinDatabase::class.java, migrationDb).addMigrations(*VirlinDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                // Existing data survives untouched — including Phase 05 timing and Phase 03 rank.
                val p = migrated.projects().byId("p1")!!
                assertEquals("Virlin Development", p.title); assertEquals("p1/icon-1.png", p.iconPath); assertEquals("code", p.iconId)
                val s = migrated.workStreams().byId("s3")!!
                assertEquals("CHECK", s.state)
                assertEquals(ms + 600_000, s.checkAt!!.toEpochMilli())        // dueAt intact
                assertEquals(2, s.attentionRank)                               // canonical rank intact
                assertEquals("Route structure", migrated.tasks().byId("t1")!!.title)
                // …and the new table exists and is usable.
                assertTrue(migrated.priorityPreferences().all().isEmpty())
                RoomPriorityPreferences(migrated).save(PriorityPreference("s3", 2, PriorityScope.Always, t0))
                assertEquals(2, RoomPriorityPreferences(migrated).get("s3")!!.preferredPosition)
            }
        } finally { migrated.close(); ctx.deleteDatabase(migrationDb) }
    }

    @Test fun freshInstall_isV11() {
        runBlocking { assertTrue(db.priorityPreferences().all().isEmpty()) }
        assertEquals(11, db.openHelper.readableDatabase.version)
    }

    // ------------------------------------------------------------------ process-death recovery

    @Test fun always_survivesDatabaseReopen_andAppliesOnReEntry() = runBlocking {
        seed()
        actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        actions.continueProcessing("D", AttentionTiming.checkAgainAt(t0, 5))      // leaves Needs You
        restart()                                                                  // ← everything rebuilt from the file
        assertEquals(2, prefs.get("D")!!.preferredPosition)
        assertEquals(PriorityScope.Always, prefs.get("D")!!.scope)
        clock.now = t0.plus(Duration.ofMinutes(5))
        actions.checkDue("D")
        assertEquals(listOf("A", "D", "B", "C"), ids())
        assertEquals(2, NeedsYouOrder.effectiveRank(repo.streams.value, "D"))
    }

    @Test fun oneTime_writesNothing_andCurrentTermKeepsItsType() = runBlocking {
        seed()
        actions.setNeedsYouPriority("D", 1, PriorityScope.OneTime)
        actions.setNeedsYouPriority("C", 2, PriorityScope.CurrentTerm)
        restart()
        assertNull(prefs.get("D"))
        assertEquals(PriorityScope.CurrentTerm, prefs.get("C")!!.scope)             // distinct scope on disk
        assertEquals(1, prefs.all().size)
    }

    @Test fun until_appliesBeforeExpiry_andIsIgnoredAfter() = runBlocking {
        seed()
        actions.setNeedsYouPriority("D", 2, PriorityScope.Until(t0.plus(Duration.ofHours(1))))
        actions.continueProcessing("D", AttentionTiming.checkAgainAt(t0, 5))
        restart()
        clock.now = t0.plus(Duration.ofMinutes(30))                                 // before expiry
        actions.checkDue("D")
        assertEquals(2, NeedsYouOrder.effectiveRank(repo.streams.value, "D"))

        actions.continueProcessing("D", AttentionTiming.checkAgainAt(clock.now, 5))
        restart()
        clock.now = t0.plus(Duration.ofHours(2))                                    // after expiry
        actions.checkDue("D")
        assertEquals(listOf("A", "B", "C", "D"), ids())
        assertNull("expired row is deleted", prefs.get("D"))
    }

    @Test fun expiryCleanupHappensInSql_withoutAnyScreen() = runBlocking {
        seed()
        prefs.save(PriorityPreference("A", 1, PriorityScope.Always, t0))
        prefs.save(PriorityPreference("B", 2, PriorityScope.Until(t0.plus(Duration.ofHours(1))), t0))
        prefs.save(PriorityPreference("C", 3, PriorityScope.CurrentTerm, t0))
        restart()
        assertEquals(1, prefs.cleanupExpired(t0.plus(Duration.ofHours(2))))
        assertEquals(setOf("A", "C"), prefs.all().map { it.streamId }.toSet())
    }

    @Test fun replacementAndRemovalKeepOneRowPerItem() = runBlocking {
        seed()
        actions.setNeedsYouPriority("D", 2, PriorityScope.Always)
        actions.setNeedsYouPriority("D", 3, PriorityScope.Until(t0.plus(Duration.ofDays(1))))
        restart()
        assertEquals(1, prefs.all().size)
        assertEquals(3, prefs.get("D")!!.preferredPosition)
        actions.clearPriorityPreference("D")
        restart()
        assertTrue(prefs.all().isEmpty())
    }

    @Test fun orphanAndCorruptRowsAreSafe() = runBlocking {
        seed()
        prefs.save(PriorityPreference("ghost", 1, PriorityScope.Always, t0))         // no such stream
        db.openHelper.writableDatabase.execSQL(                                       // a scope this build cannot read
            "INSERT OR REPLACE INTO priority_preferences (streamId, preferredPosition, scopeType, createdAt, expiresAt) VALUES ('weird', 1, 'FROM_THE_FUTURE', ${t0.toEpochMilli()}, NULL)"
        )
        restart()
        assertNull("unknown scope reads as null, never a crash", prefs.get("weird"))
        assertEquals(listOf("ghost"), prefs.all().map { it.streamId })                 // the corrupt row is skipped
        actions.checkDue("A")                                                          // attention keeps working
        assertEquals(listOf("A", "B", "C", "D"), ids())
    }
}
