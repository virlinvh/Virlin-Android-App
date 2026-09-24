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

@Dao
interface NoteDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(n: NoteDocumentEntity)
    @Query("SELECT * FROM note_documents WHERE id = :id") suspend fun byId(id: String): NoteDocumentEntity?
    @Query("SELECT * FROM note_documents WHERE captureItemId = :captureItemId") suspend fun byCaptureId(captureItemId: String): NoteDocumentEntity?
    @Query("SELECT COUNT(*) FROM note_documents") suspend fun count(): Int
}

@Dao
interface PromptDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: PromptDocumentEntity)
    @Query("SELECT * FROM prompt_documents WHERE id = :id") suspend fun byId(id: String): PromptDocumentEntity?
    @Query("SELECT * FROM prompt_documents WHERE captureItemId = :captureItemId") suspend fun byCaptureId(captureItemId: String): PromptDocumentEntity?
    @Query("SELECT COUNT(*) FROM prompt_documents") suspend fun count(): Int
}

@Dao
interface AttachmentDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(a: AttachmentDocumentEntity)
    @Query("SELECT * FROM attachment_documents WHERE id = :id") suspend fun byId(id: String): AttachmentDocumentEntity?
    @Query("SELECT * FROM attachment_documents WHERE captureItemId = :captureItemId") suspend fun byCaptureId(captureItemId: String): AttachmentDocumentEntity?
    @Query("SELECT COUNT(*) FROM attachment_documents") suspend fun count(): Int
}

@Dao
interface VoiceDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(v: VoiceDocumentEntity)
    @Query("SELECT * FROM voice_documents WHERE id = :id") suspend fun byId(id: String): VoiceDocumentEntity?
    @Query("SELECT * FROM voice_documents WHERE captureItemId = :captureItemId") suspend fun byCaptureId(captureItemId: String): VoiceDocumentEntity?
    @Query("SELECT COUNT(*) FROM voice_documents") suspend fun count(): Int
}

/**
 * Virlin's durable schema. v1 (Pass 5): projects, workstreams, tasks, cycles, focus_sessions,
 * context_snapshots, events, meta. v2 (Pass 10): + captures. v3: execution responsibility
 * (Project.defaultExecutionMode, WorkStream.executionPreference replacing mode,
 * Task.executionPreference). v4: + note_documents (Capture Text Note block documents).
 * v5: + prompt_documents (Capture Prompt documents).
 * v6: + attachment_documents (Capture File/Image metadata; bytes in managed files).
 * v7: + voice_documents (Capture Voice notes; clip audio in managed files).
 * Every version change ships an explicit [Migration] proven by `VirlinMigrationTest`;
 * there is NO destructive fallback.
 */
@Database(
    entities = [
        ProjectEntity::class, WorkStreamEntity::class, TaskEntity::class, CycleEntity::class,
        FocusSessionEntity::class, ContextSnapshotEntity::class, EventEntity::class, MetaEntity::class,
        CaptureEntity::class, NoteDocumentEntity::class, PromptDocumentEntity::class,
        AttachmentDocumentEntity::class, VoiceDocumentEntity::class
    ],
    version = 9,
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
    abstract fun noteDocuments(): NoteDocumentDao
    abstract fun promptDocuments(): PromptDocumentDao
    abstract fun attachmentDocuments(): AttachmentDocumentDao
    abstract fun voiceDocuments(): VoiceDocumentDao

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
        /**
         * v2 → v3: additive execution-responsibility columns.
         * - projects.defaultExecutionMode = HUMAN for all existing rows
         * - workstreams.mode → executionPreference (HUMAN/EXTERNAL preserved as explicit)
         * - tasks.executionPreference = INHERIT for all existing rows
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `projects` ADD COLUMN `defaultExecutionMode` TEXT NOT NULL DEFAULT 'HUMAN'")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workstreams_new` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `projectId` TEXT, `tool` TEXT, " +
                        "`executionPreference` TEXT NOT NULL, `state` TEXT NOT NULL, `priority` TEXT NOT NULL, `pinned` INTEGER NOT NULL, " +
                        "`lastHumanAction` TEXT, `waitingFor` TEXT, `nextHumanAction` TEXT, `blockerReason` TEXT, " +
                        "`processingStartedAt` INTEGER, `checkAt` INTEGER, `snoozedUntil` INTEGER, `snoozeReason` TEXT, " +
                        "`currentCycleId` TEXT, `cycleCount` INTEGER NOT NULL, `activeTaskId` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "INSERT INTO `workstreams_new` (`id`,`title`,`projectId`,`tool`,`executionPreference`,`state`,`priority`,`pinned`," +
                        "`lastHumanAction`,`waitingFor`,`nextHumanAction`,`blockerReason`,`processingStartedAt`,`checkAt`,`snoozedUntil`," +
                        "`snoozeReason`,`currentCycleId`,`cycleCount`,`activeTaskId`,`createdAt`,`updatedAt`,`completedAt`) " +
                        "SELECT `id`,`title`,`projectId`,`tool`,`mode`,`state`,`priority`,`pinned`," +
                        "`lastHumanAction`,`waitingFor`,`nextHumanAction`,`blockerReason`,`processingStartedAt`,`checkAt`,`snoozedUntil`," +
                        "`snoozeReason`,`currentCycleId`,`cycleCount`,`activeTaskId`,`createdAt`,`updatedAt`,`completedAt` FROM `workstreams`"
                )
                db.execSQL("DROP TABLE `workstreams`")
                db.execSQL("ALTER TABLE `workstreams_new` RENAME TO `workstreams`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workstreams_projectId` ON `workstreams` (`projectId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workstreams_state` ON `workstreams` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workstreams_checkAt` ON `workstreams` (`checkAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workstreams_activeTaskId` ON `workstreams` (`activeTaskId`)")

                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `executionPreference` TEXT NOT NULL DEFAULT 'INHERIT'")
            }
        }

        /** v3 → v4: additive note_documents for Capture Text Note block documents. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_documents` (`id` TEXT NOT NULL, `captureItemId` TEXT NOT NULL, " +
                        "`title` TEXT, `documentJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_note_documents_captureItemId` ON `note_documents` (`captureItemId`)")
            }
        }

        /** v4 → v5: additive prompt_documents for Capture Prompt documents. */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `prompt_documents` (`id` TEXT NOT NULL, `captureItemId` TEXT NOT NULL, " +
                        "`title` TEXT, `description` TEXT, `tagsJson` TEXT NOT NULL, `documentJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_prompt_documents_captureItemId` ON `prompt_documents` (`captureItemId`)")
            }
        }

        /** v5 → v6: additive attachment_documents for Capture File/Image metadata. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `attachment_documents` (`id` TEXT NOT NULL, `captureItemId` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                        "`relativePath` TEXT NOT NULL, `kind` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_attachment_documents_captureItemId` ON `attachment_documents` (`captureItemId`)")
            }
        }

        /** v6 → v7: additive voice_documents for Capture Voice notes. */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_documents` (`id` TEXT NOT NULL, `captureItemId` TEXT NOT NULL, " +
                        "`title` TEXT, `clipsJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_voice_documents_captureItemId` ON `voice_documents` (`captureItemId`)")
            }
        }
        /** v7 → v8: additive nullable `projects.iconPath` (custom project identity icon). */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `projects` ADD COLUMN `iconPath` TEXT")
            }
        }
        /** v8 → v9: additive nullable `projects.iconId` (chosen built-in project icon). */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `projects` ADD COLUMN `iconId` TEXT")
            }
        }
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

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
