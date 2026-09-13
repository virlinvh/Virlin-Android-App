package com.virlin.app.domain

import android.content.Context
import android.util.Log
import com.virlin.app.data.db.RoomWorkStreamRepository
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.id.UuidIdProvider
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.schedule.AttentionScheduler
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.platform.AndroidAttentionScheduler
import com.virlin.app.domain.time.SystemVirlinClock
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.mock.DomainDisplayBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Composition root for the domain layer until Hilt arrives. Holds the single repository,
 * clock, id provider and the unified [VirlinActions] facade.
 *
 * Production ([init] called with a Context): a Room-backed repository over `virlin.db`.
 * Durable truth = Room rows + [VirlinClock]; the display ticker is only an in-process
 * refresh. Without [init] (JVM/Robolectric tests): the deterministic in-memory repository.
 */
object VirlinGraph {
    val clock: VirlinClock = SystemVirlinClock
    val ids: IdProvider = UuidIdProvider

    @Volatile private var database: VirlinDatabase? = null
    @Volatile private var platformScheduler: AttentionScheduler? = null

    /**
     * Wake-up mechanism only (AlarmManager in production, no-op without a Context). Holds no
     * state; every alarm is derived from committed Room rows and re-derivable at any time.
     */
    val scheduler: AttentionScheduler get() = platformScheduler ?: AttentionScheduler.NoOp

    /** Idempotent. Call before anything touches [repository] (MainActivity, receivers). */
    fun init(context: Context) {
        if (database == null) synchronized(this) {
            if (database == null) {
                platformScheduler = AndroidAttentionScheduler(context.applicationContext)
                database = VirlinDatabase.open(context)
            }
        }
    }

    val repository: WorkStreamRepository by lazy {
        val db = database
        if (db == null) {
            // Test/preview path: no database, deterministic demo state in memory.
            val seed = DemoSeed.build(clock.now())
            InMemoryWorkStreamRepository(seed.streams, seed.projects, seed.tasks)
        } else {
            runBlocking { DemoSeed.applyIfEmpty(db, clock.now()) }
            // Room commits first; only then are Android wake-ups scheduled/cancelled.
            SchedulingWorkStreamRepository(RoomWorkStreamRepository(db), scheduler) { e ->
                Log.w(TAG, "scheduler failed after commit (startup rescheduling will heal it)", e)
            }
        }
    }

    val actions: VirlinActions by lazy { DefaultVirlinActions(repository, clock, ids) }

    /** The deterministic command contract (Pass 11): the ONE entry point for future text / voice / structured input layers. */
    val commands: com.virlin.app.domain.command.CommandEngine by lazy { com.virlin.app.domain.command.CommandEngine(actions, repository, clock, zone) }

    /** The user's zone for calendar language (Pass 13). Device default; never a hardcoded offset. */
    val zone: java.time.ZoneId get() = java.time.ZoneId.systemDefault()
    val timeParser: com.virlin.app.domain.command.time.TimeExpressionParser by lazy { com.virlin.app.domain.command.time.TimeExpressionParser(clock, zone) }
    /**
     * The ONE production language layer: deterministic text -> VirlinCommand. Nothing sits behind
     * it - no fallback interpreter, provider, model or network; `Unsupported` stays `Unsupported`.
     */
    val interpreter: com.virlin.app.domain.command.text.TextCommandInterpreter by lazy { com.virlin.app.domain.command.text.TextCommandInterpreter(timeParser) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Idempotent. Call once from the Application/Activity entry point. */
    fun start() {
        DomainDisplayBridge.start(scope, repository, clock)
        scope.launch { reconcileDue(); rescheduleAll() }
    }

    /**
     * Self-healing after process death, scheduler loss or an update: every persisted future
     * attention event gets its wake-up (re)armed — replacing, never duplicating. Streams with
     * nothing pending have any leftover alarm cancelled. @return armed count.
     */
    suspend fun rescheduleAll(): Int {
        val streams = repository.streams.value
        streams.forEach { s -> runCatching { scheduler.sync(s) }.onFailure { Log.w(TAG, "reschedule ${s.id} failed", it) } }
        return streams.count { com.virlin.app.domain.schedule.AttentionSchedule.of(it) != null }
    }

    /**
     * Restart reconciliation: anything whose planned look-again time passed while Virlin was
     * not running (or while the ticker was dead) becomes CHECK now, through the same validated
     * action the ticker uses. Derived from persisted absolute timestamps + the clock — no
     * background execution required.
     */
    suspend fun reconcileDue() {
        val now = clock.now()
        // Derived from persisted absolute timestamps + clock; the snooze reason is untouched by
        // checkDue, so a due item still knows whether it is a human return or a result-ready return.
        val due = repository.streams.value.filter {
            (it.state == com.virlin.app.domain.model.WorkStreamState.PROCESSING || it.state == com.virlin.app.domain.model.WorkStreamState.SNOOZED) &&
                it.checkAt?.let { t -> !now.isBefore(t) } == true
        }
        due.forEach { s -> Log.d(TAG, "reconcile due ${s.id}: ${actions.checkDue(s.id)::class.simpleName}") }
    }

    private const val TAG = "VirlinGraph"
}
