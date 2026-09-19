package com.virlin.app.domain

import android.content.Context
import android.util.Log
import com.virlin.app.data.db.RoomWorkStreamRepository
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.debug.VirlinStartup
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.id.UuidIdProvider
import com.virlin.app.domain.repository.BootstrappingWorkStreamRepository
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.schedule.AttentionScheduler
import com.virlin.app.domain.schedule.SchedulingWorkStreamRepository
import com.virlin.app.platform.AndroidAttentionScheduler
import com.virlin.app.domain.time.SystemVirlinClock
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.mock.DomainDisplayBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Composition root for the domain layer until Hilt arrives.
 *
 * Cold start (production): [init] opens Room; [startAsync] hydrates on IO without blocking
 * Main; [repository] is always a safe [BootstrappingWorkStreamRepository] collectors may
 * subscribe to before READY. Background receivers call [ensureReady] when they need truth.
 */
object VirlinGraph {
    val clock: VirlinClock = SystemVirlinClock
    val ids: IdProvider = UuidIdProvider

    @Volatile private var database: VirlinDatabase? = null
    @Volatile private var platformScheduler: AttentionScheduler? = null

    private val bootstrap = BootstrappingWorkStreamRepository()
    private val _readiness = MutableStateFlow<StartupReadiness>(StartupReadiness.Initializing)
    private val ensureMutex = Mutex()
    private var ensureJob: CompletableDeferred<Unit>? = null
    @Volatile private var bridgeStarted = false
    @Volatile private var startedAsync = false

    /**
     * TEST/DEBUG only: artificial delay inside [ensureReady] hydration (ms). Production stays 0.
     * Used to prove the UI shell appears before READY.
     */
    @Volatile var debugHydrationDelayMs: Long = 0

    /**
     * TEST/DEBUG only: if true, [ensureReady] fails once (then clears) so Retry can recover.
     */
    @Volatile var debugFailNextHydration: Boolean = false

    val scheduler: AttentionScheduler get() = platformScheduler ?: AttentionScheduler.NoOp

    /** Always safe to collect — empty until Room/in-memory bind. */
    val repository: WorkStreamRepository get() = bootstrap

    val startupReadiness: StateFlow<StartupReadiness> = _readiness.asStateFlow()

    /** Idempotent. Call before [startAsync] / [ensureReady] (MainActivity, receivers). */
    fun init(context: Context) {
        if (database == null) synchronized(this) {
            if (database == null) {
                platformScheduler = AndroidAttentionScheduler(context.applicationContext)
                database = VirlinDatabase.open(context)
            }
        }
    }

    /**
     * Canonical suspend entry: hydrate once, bind bootstrap, mark READY.
     * Safe for concurrent callers (shared deferred). Receivers must await this before writes.
     */
    suspend fun ensureReady() {
        if (_readiness.value is StartupReadiness.Ready && bootstrap.isBound) return
        val deferred = ensureMutex.withLock {
            if (_readiness.value is StartupReadiness.Ready && bootstrap.isBound) return
            ensureJob ?: CompletableDeferred<Unit>().also { job ->
                ensureJob = job
                VirlinStartup.mark(
                    "ensureReady_LAUNCH",
                    Thread.currentThread().stackTrace
                        .asSequence()
                        .dropWhile { it.className.contains("VirlinGraph") || it.className.startsWith("java.") || it.className.startsWith("kotlin") }
                        .take(3)
                        .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
                        .ifEmpty { "unknown" }
                )
                // Launch hydration on IO from graph scope; callers await [job].
                scope.launch(Dispatchers.IO) {
                    try {
                        hydrateAndBind()
                        job.complete(Unit)
                    } catch (t: Throwable) {
                        Log.e(TAG, "repository hydration failed", t)
                        _readiness.value = StartupReadiness.Error(t.message ?: t.javaClass.simpleName)
                        ensureJob = null
                        job.completeExceptionally(t)
                    }
                }
            }
        }
        deferred.await()
    }

    private suspend fun hydrateAndBind() {
        if (bootstrap.isBound) {
            _readiness.value = StartupReadiness.Ready
            return
        }
        VirlinStartup.mark("prepareRepository_BODY_START")
        val delayMs = if (com.virlin.app.BuildConfig.DEBUG) debugHydrationDelayMs else 0L
        if (delayMs > 0) {
            VirlinStartup.mark("prepareRepository_DEBUG_DELAY", "ms=$delayMs")
            kotlinx.coroutines.delay(delayMs)
        }
        if (com.virlin.app.BuildConfig.DEBUG && debugFailNextHydration) {
            // Sticky until retryStartup() clears it — so a racing second ensureReady
            // cannot swallow the simulated failure.
            throw IllegalStateException("Simulated repository hydration failure")
        }
        val t0 = android.os.SystemClock.elapsedRealtime()
        val db = database
        val ready: WorkStreamRepository = if (db == null) {
            val seed = DemoSeed.build(clock.now())
            InMemoryWorkStreamRepository(seed.streams, seed.projects, seed.tasks)
        } else {
            DemoSeed.applyIfEmpty(db, clock.now())
            VirlinStartup.mark("prepareRepository_DemoSeed_DONE")
            SchedulingWorkStreamRepository(
                RoomWorkStreamRepository.create(db),
                scheduler
            ) { e ->
                Log.w(TAG, "scheduler failed after commit (startup rescheduling will heal it)", e)
            }
        }
        bootstrap.bind(ready, scope)
        val dt = android.os.SystemClock.elapsedRealtime() - t0
        VirlinStartup.mark(
            "prepareRepository_BODY_END",
            "durationMs=$dt streams=${ready.streams.value.size}"
        )
        startBridgeOnce()
        _readiness.value = StartupReadiness.Ready
        VirlinStartup.mark("startup_READY")
        scope.launch { reconcileDue(); rescheduleAll() }
    }

    /** Retry after [StartupReadiness.Error]. */
    fun retryStartup() {
        if (_readiness.value !is StartupReadiness.Error) return
        debugFailNextHydration = false
        _readiness.value = StartupReadiness.Initializing
        scope.launch { runCatching { ensureReady() } }
    }

    /**
     * Production UI entry: kick hydration on IO without blocking the caller.
     * Idempotent. Call from MainActivity after [init] + [setContent].
     */
    fun startAsync() {
        if (startedAsync) return
        startedAsync = true
        VirlinStartup.mark("startAsync_ENTER")
        scope.launch {
            runCatching { ensureReady() }
                .onFailure { Log.e(TAG, "startAsync ensureReady failed", it) }
        }
    }

    @Deprecated("Use startAsync() from UI; ensureReady() from receivers", ReplaceWith("startAsync()"))
    fun start() {
        startAsync()
    }

    private fun startBridgeOnce() {
        if (bridgeStarted) return
        bridgeStarted = true
        DomainDisplayBridge.start(scope, repository, clock)
        VirlinStartup.mark("DomainDisplayBridge_STARTED")
    }

    val actions: VirlinActions by lazy { DefaultVirlinActions(repository, clock, ids) }
    val commands: com.virlin.app.domain.command.CommandEngine by lazy {
        com.virlin.app.domain.command.CommandEngine(actions, repository, clock, zone)
    }
    val zone: java.time.ZoneId get() = java.time.ZoneId.systemDefault()
    val timeParser: com.virlin.app.domain.command.time.TimeExpressionParser by lazy {
        com.virlin.app.domain.command.time.TimeExpressionParser(clock, zone)
    }
    val interpreter: com.virlin.app.domain.command.text.TextCommandInterpreter by lazy {
        com.virlin.app.domain.command.text.TextCommandInterpreter(timeParser)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun rescheduleAll(): Int {
        ensureReady()
        val streams = repository.streams.value
        streams.forEach { s ->
            runCatching { scheduler.sync(s) }.onFailure { Log.w(TAG, "reschedule ${s.id} failed", it) }
        }
        return streams.count { com.virlin.app.domain.schedule.AttentionSchedule.of(it) != null }
    }

    suspend fun reconcileDue() {
        ensureReady()
        val now = clock.now()
        val due = repository.streams.value.filter {
            (it.state == com.virlin.app.domain.model.WorkStreamState.PROCESSING ||
                it.state == com.virlin.app.domain.model.WorkStreamState.SNOOZED) &&
                it.checkAt?.let { t -> !now.isBefore(t) } == true
        }
        due.forEach { s ->
            Log.d(TAG, "reconcile due ${s.id}: ${actions.checkDue(s.id)::class.simpleName}")
        }
    }

    /** JVM tests: reset graph between cases (in-memory only). */
    internal fun resetForTests() {
        database = null
        platformScheduler = null
        bridgeStarted = false
        startedAsync = false
        ensureJob = null
        debugHydrationDelayMs = 0
        debugFailNextHydration = false
        _readiness.value = StartupReadiness.Initializing
        DomainDisplayBridge.stopForTests()
        // Unbind bootstrap so the next ensureReady can re-hydrate.
        kotlinx.coroutines.runBlocking { bootstrap.resetForTests() }
    }

    private const val TAG = "VirlinGraph"
}
