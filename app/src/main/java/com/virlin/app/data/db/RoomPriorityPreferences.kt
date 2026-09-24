package com.virlin.app.data.db

import com.virlin.app.data.db.VirlinMappers.toDomain
import com.virlin.app.data.db.VirlinMappers.toEntity
import com.virlin.app.domain.attention.PriorityPreference
import com.virlin.app.domain.attention.PriorityPreferences
import com.virlin.app.domain.attention.PriorityScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * The durable [PriorityPreferences] (Phase 07): Needs You priority policies survive process death
 * in the app's own Room database. It is the production binding; nothing above the interface knows
 * Room exists, and no Room entity ever leaves this class.
 *
 * - One row per item (`streamId` primary key) — saving again REPLACES the policy.
 * - `OneTime` is never stored; it is a move now, not a policy.
 * - A row whose scope cannot be read back (corrupt or from a newer build) is ignored rather than
 *   crashing attention.
 * - All work happens on [io]; the main thread never touches the database.
 */
class RoomPriorityPreferences(
    private val db: VirlinDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : PriorityPreferences {

    private val dao get() = db.priorityPreferences()

    override suspend fun get(streamId: String): PriorityPreference? = withContext(io) {
        dao.byStream(streamId)?.toDomain()
    }

    override suspend fun all(): List<PriorityPreference> = withContext(io) {
        dao.all().mapNotNull { it.toDomain() }
    }

    override suspend fun save(preference: PriorityPreference) {
        if (preference.scope == PriorityScope.OneTime) return   // transient by definition
        withContext(io) { dao.upsert(preference.toEntity()) }
    }

    override suspend fun remove(streamId: String) = withContext(io) { dao.delete(streamId) }

    /** Expiry is handled in SQL, so it does not depend on any screen being open. */
    override suspend fun cleanupExpired(now: Instant): Int = withContext(io) { dao.deleteExpired(now.toEpochMilli()) }
}
