package com.virlin.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.data.db.RoomWorkStreamRepository
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Needs You priority ranking — F (restart) and G (leave/return) against the REAL Room file:
 * PHASE A writes through `VirlinActions`, every in-memory object is destroyed, PHASE B rebuilds
 * purely from the database file and the order must be identical.
 */
@RunWith(AndroidJUnit4::class)
class NeedsYouRankPersistenceTest {

    private class Clock(var now: Instant) : VirlinClock { override fun now() = now }
    private class Ids : IdProvider { private var n = 0; override fun newId(prefix: String) = "$prefix-${++n}" }

    private val t0: Instant = Instant.parse("2026-09-22T09:00:00Z")
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "virlin-needs-you-rank-test.db"
    private lateinit var db: VirlinDatabase
    private lateinit var repo: RoomWorkStreamRepository
    private lateinit var actions: DefaultVirlinActions

    private fun open() = runBlocking {
        db = androidx.room.Room.databaseBuilder(ctx, VirlinDatabase::class.java, dbName).addMigrations(*VirlinDatabase.MIGRATIONS).build()
        repo = RoomWorkStreamRepository.create(db)
        actions = DefaultVirlinActions(repo, Clock(t0.plusSeconds(100)), Ids())
    }
    private fun reload() { db.close(); open() }

    @Before fun setUp() { ctx.deleteDatabase(dbName); open() }
    @After fun tearDown() { db.close(); ctx.deleteDatabase(dbName) }

    private fun check(id: String, sinceOffset: Long) = WorkStream(
        id = id, title = id, state = WorkStreamState.CHECK, checkAt = t0.plusSeconds(sinceOffset),
        createdAt = t0.minusSeconds(3600), updatedAt = t0.minusSeconds(3600)
    )
    private fun order() = NeedsYouOrder.order(repo.streams.value).map { it.id }

    @Test fun F_manualOrder_survivesProcessDeath() = runBlocking {
        repo.transaction { listOf(check("A", 0), check("B", 10), check("C", 20), check("D", 30)).forEach { saveStream(it) } }
        assertEquals(listOf("A", "B", "C", "D"), order())                              // A: default = longest waiting first
        assert(actions.reorderNeedsYou("D", 2) is ActionResult.Success)
        assertEquals(listOf("A", "D", "B", "C"), order())

        reload()                                                                        // PHASE B: from the file only
        assertEquals(listOf("A", "D", "B", "C"), order())
        assertEquals(listOf(1, 2, 3, 4), NeedsYouOrder.order(repo.streams.value).map { it.attentionRank })   // E: dense, unique
        assertEquals(t0.plusSeconds(30), NeedsYouOrder.waitingSince(repo.getStream("D")!!))                  // I: timer basis untouched

        // G: leaving clears the rank on disk; returning enters unranked (after the ranked block)
        assert(actions.continueProcessing("D", t0.plusSeconds(900)) is ActionResult.Success)
        reload()
        assertNull(repo.getStream("D")!!.attentionRank)
        assertEquals(listOf("A", "B", "C"), order())
        assert(actions.checkDue("D") is ActionResult.Success)
        reload()
        assertNull(repo.getStream("D")!!.attentionRank)
        assertEquals(listOf("A", "B", "C", "D"), order())
    }
}
