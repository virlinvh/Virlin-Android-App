package com.virlin.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * DAOs are persistence only: insert/upsert and queries. No domain rule lives here — transitions,
 * the single-Focus invariant, task ownership and progress stay in the Action Layer.
 */
@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: ProjectEntity)
    @Query("SELECT * FROM projects ORDER BY createdAt, id") suspend fun all(): List<ProjectEntity>
    @Query("SELECT * FROM projects WHERE id = :id") suspend fun byId(id: String): ProjectEntity?
}

@Dao
interface WorkStreamDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: WorkStreamEntity)
    @Query("SELECT * FROM workstreams ORDER BY createdAt, id") suspend fun all(): List<WorkStreamEntity>
    @Query("SELECT * FROM workstreams WHERE id = :id") suspend fun byId(id: String): WorkStreamEntity?
    @Query("SELECT * FROM workstreams WHERE state = 'FOCUS' LIMIT 1") suspend fun activeFocus(): WorkStreamEntity?
    @Query("SELECT * FROM workstreams WHERE projectId = :projectId ORDER BY createdAt, id") suspend fun byProject(projectId: String): List<WorkStreamEntity>
    @Query("SELECT COUNT(*) FROM workstreams") suspend fun count(): Int
    /** Streams whose planned look-again time has passed — startup reconciliation. */
    @Query("SELECT * FROM workstreams WHERE state IN ('PROCESSING','SNOOZED') AND checkAt IS NOT NULL AND checkAt <= :now")
    suspend fun dueBy(now: Long): List<WorkStreamEntity>
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(t: TaskEntity)
    @Query("SELECT * FROM tasks ORDER BY createdAt, sortOrder, id") suspend fun all(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun byId(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY sortOrder, createdAt") suspend fun byProject(projectId: String): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE workStreamId = :workStreamId ORDER BY sortOrder, createdAt") suspend fun byWorkStream(workStreamId: String): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE parentTaskId = :parentId ORDER BY sortOrder, createdAt") suspend fun children(parentId: String): List<TaskEntity>
}

@Dao
interface CaptureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: CaptureEntity)
    /** Newest first by persisted createdAt (never by insertion order). */
    @Query("SELECT * FROM captures ORDER BY createdAt DESC, id DESC") suspend fun all(): List<CaptureEntity>
    @Query("SELECT * FROM captures WHERE id = :id") suspend fun byId(id: String): CaptureEntity?
    @Query("SELECT * FROM captures WHERE status = :status ORDER BY createdAt DESC, id DESC") suspend fun byStatus(status: String): List<CaptureEntity>
    @Query("SELECT COUNT(*) FROM captures") suspend fun count(): Int
}

@Dao
interface CycleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: CycleEntity)
    @Query("SELECT * FROM cycles WHERE id = :id") suspend fun byId(id: String): CycleEntity?
    @Query("SELECT * FROM cycles WHERE workStreamId = :ws ORDER BY seq") suspend fun byWorkStream(ws: String): List<CycleEntity>
    @Query("SELECT * FROM cycles WHERE workStreamId = :ws AND endedAt IS NULL ORDER BY seq DESC LIMIT 1") suspend fun current(ws: String): CycleEntity?
    @Query("SELECT COALESCE(MAX(seq), 0) FROM cycles") suspend fun maxSeq(): Long
}

@Dao
interface FocusSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: FocusSessionEntity)
    @Query("SELECT * FROM focus_sessions WHERE id = :id") suspend fun byId(id: String): FocusSessionEntity?
    @Query("SELECT * FROM focus_sessions WHERE workStreamId = :ws ORDER BY seq") suspend fun byWorkStream(ws: String): List<FocusSessionEntity>
    @Query("SELECT * FROM focus_sessions WHERE workStreamId = :ws AND endedAt IS NULL ORDER BY seq DESC LIMIT 1") suspend fun open(ws: String): FocusSessionEntity?
    @Query("SELECT COALESCE(MAX(seq), 0) FROM focus_sessions") suspend fun maxSeq(): Long
}

@Dao
interface ContextSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(s: ContextSnapshotEntity)
    @Query("SELECT * FROM context_snapshots WHERE workStreamId = :ws ORDER BY seq DESC LIMIT 1") suspend fun latest(ws: String): ContextSnapshotEntity?
    @Query("SELECT * FROM context_snapshots WHERE workStreamId = :ws ORDER BY seq") suspend fun byWorkStream(ws: String): List<ContextSnapshotEntity>
    @Query("SELECT COALESCE(MAX(seq), 0) FROM context_snapshots") suspend fun maxSeq(): Long
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(e: EventEntity)
    @Query("SELECT * FROM events WHERE workStreamId = :ws ORDER BY seq") suspend fun byWorkStream(ws: String): List<EventEntity>
    @Query("SELECT COALESCE(MAX(seq), 0) FROM events") suspend fun maxSeq(): Long
}

@Dao
interface MetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(m: MetaEntity)
    @Query("SELECT value FROM meta WHERE `key` = :key") suspend fun get(key: String): String?
}

/**
 * Virlin's durable schema. v1 (Pass 5): projects, workstreams, tasks, cycles, focus_sessions,
 * context_snapshots, events, meta. v2 (Pass 10): + captures. Every version change ships an
 * explicit [Migration] proven by `VirlinMigrationTest`; there is NO destructive fallback.
 */
@Database(
    entities = [
        ProjectEntity::class, WorkStreamEntity::class, TaskEntity::class, CycleEntity::class,
        FocusSessionEntity::class, ContextSnapshotEntity::class, EventEntity::class, MetaEntity::class,
        CaptureEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(VirlinConverters::class)
abstract class VirlinDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao
    abstract fun workStreams(): WorkStreamDao
    abstract fun tasks(): TaskDao
    abstract fun cycles(): CycleDao
    abstract fun focusSessions(): FocusSessionDao
    abstract fun snapshots(): ContextSnapshotDao
    abstract fun events(): EventDao
    abstract fun meta(): MetaDao
    abstract fun captures(): CaptureDao

    companion object {
        const val NAME = "virlin.db"

        /** v1 -> v2: add the captures table (additive; every v1 row is untouched). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `captures` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                        "`title` TEXT, `sourceUrl` TEXT, `projectId` TEXT, `workStreamId` TEXT, `taskId` TEXT, `status` TEXT NOT NULL, " +
                        "`convertedTaskId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `archivedAt` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_status` ON `captures` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_createdAt` ON `captures` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_workStreamId` ON `captures` (`workStreamId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_projectId` ON `captures` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_taskId` ON `captures` (`taskId`)")
            }
        }
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /** Production database. One instance per process (held by `VirlinGraph`). */
        fun open(context: Context): VirlinDatabase =
            Room.databaseBuilder(context.applicationContext, VirlinDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        /** Isolated in-memory database for tests. */
        fun inMemory(context: Context): VirlinDatabase =
            Room.inMemoryDatabaseBuilder(context, VirlinDatabase::class.java).build()
    }
}
