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
    @Query("SELECT * FROM workstreams ORDER BY sortOrder, createdAt, id") suspend fun all(): List<WorkStreamEntity>
    @Query("SELECT * FROM workstreams WHERE id = :id") suspend fun byId(id: String): WorkStreamEntity?
    @Query("SELECT * FROM workstreams WHERE state = 'FOCUS' LIMIT 1") suspend fun activeFocus(): WorkStreamEntity?
    @Query("SELECT * FROM workstreams WHERE projectId = :projectId ORDER BY sortOrder, createdAt, id") suspend fun byProject(projectId: String): List<WorkStreamEntity>
    @Query("SELECT COUNT(*) FROM workstreams") suspend fun count(): Int
    /** Streams whose planned look-again time has passed — startup reconciliation. */
    @Query("SELECT * FROM workstreams WHERE state IN ('PROCESSING','SNOOZED') AND checkAt IS NOT NULL AND checkAt <= :now")
    suspend fun dueBy(now: Long): List<WorkStreamEntity>
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(t: TaskEntity)
    // sortOrder FIRST: this is the sibling order the user arranged, and it is what every
    // consumer must see. Ordering by createdAt first made creation order win, so a branch
    // reordered by hand came back out of this query in the order it happened to be typed.
    @Query("SELECT * FROM tasks ORDER BY sortOrder, createdAt, id") suspend fun all(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun byId(id: String): TaskEntity?
    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY sortOrder, createdAt") suspend fun byProject(projectId: String): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE workStreamId = :workStreamId ORDER BY sortOrder, createdAt") suspend fun byWorkStream(workStreamId: String): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE parentTaskId = :parentId ORDER BY sortOrder, createdAt") suspend fun children(parentId: String): List<TaskEntity>
}

@Dao
interface PriorityPreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: PriorityPreferenceEntity)
    @Query("SELECT * FROM priority_preferences ORDER BY createdAt, streamId") suspend fun all(): List<PriorityPreferenceEntity>
    @Query("SELECT * FROM priority_preferences WHERE streamId = :streamId") suspend fun byStream(streamId: String): PriorityPreferenceEntity?
    @Query("DELETE FROM priority_preferences WHERE streamId = :streamId") suspend fun delete(streamId: String)
    /** Expiry cleanup in the data layer — never dependent on a screen being open. Epoch millis, converter-free. */
    @Query("DELETE FROM priority_preferences WHERE expiresAt IS NOT NULL AND expiresAt <= :nowEpochMillis") suspend fun deleteExpired(nowEpochMillis: Long): Int
}

@Dao
interface ExternalStageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: ExternalStageEntity)
    @Query("SELECT * FROM external_stages ORDER BY workStreamId, sortOrder, id") suspend fun all(): List<ExternalStageEntity>
    @Query("SELECT * FROM external_stages WHERE workStreamId = :workStreamId ORDER BY sortOrder, id")
    suspend fun byStream(workStreamId: String): List<ExternalStageEntity>
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
    /** Pulse reads every cycle: external processing is a property of the whole history. */
    @Query("SELECT * FROM cycles ORDER BY seq") suspend fun all(): List<CycleEntity>
}

@Dao
interface FocusSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(s: FocusSessionEntity)
    @Query("SELECT * FROM focus_sessions WHERE id = :id") suspend fun byId(id: String): FocusSessionEntity?
    @Query("SELECT * FROM focus_sessions WHERE workStreamId = :ws ORDER BY seq") suspend fun byWorkStream(ws: String): List<FocusSessionEntity>
    @Query("SELECT * FROM focus_sessions WHERE workStreamId = :ws AND endedAt IS NULL ORDER BY seq DESC LIMIT 1") suspend fun open(ws: String): FocusSessionEntity?
    @Query("SELECT COALESCE(MAX(seq), 0) FROM focus_sessions") suspend fun maxSeq(): Long
    /** Pulse reads every session; a period is clipped from them, never queried per stream. */
    @Query("SELECT * FROM focus_sessions ORDER BY seq") suspend fun all(): List<FocusSessionEntity>
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
    /** Project Activity: every stream of one project in ONE paginated read, newest first. */
    @Query("SELECT * FROM events WHERE workStreamId IN (:ids) ORDER BY at DESC, seq DESC LIMIT :limit OFFSET :offset")
    suspend fun byWorkStreams(ids: List<String>, limit: Int, offset: Int): List<EventEntity>
}

@Dao
interface ProjectTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(tag: ProjectTagEntity)
    @Query("SELECT * FROM project_tags ORDER BY nameKey, id") suspend fun all(): List<ProjectTagEntity>
    @Query("SELECT * FROM project_tags WHERE id = :id") suspend fun byId(id: String): ProjectTagEntity?
    @Query("SELECT * FROM project_tags WHERE projectId = :projectId ORDER BY nameKey")
    suspend fun byProject(projectId: String): List<ProjectTagEntity>
    @Query("DELETE FROM project_tags WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface TagLinkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(link: TagLinkEntity)
    @Query("SELECT * FROM tag_links") suspend fun all(): List<TagLinkEntity>
    @Query("SELECT * FROM tag_links WHERE targetType = :type AND targetId = :id")
    suspend fun byTarget(type: String, id: String): List<TagLinkEntity>
    @Query("DELETE FROM tag_links WHERE tagId = :tagId AND targetType = :type AND targetId = :id")
    suspend fun delete(tagId: String, type: String, id: String)
    /** Deleting a tag removes its links; it never removes what was tagged. */
    @Query("DELETE FROM tag_links WHERE tagId = :tagId") suspend fun deleteByTag(tagId: String)
    @Query("UPDATE OR REPLACE tag_links SET tagId = :into WHERE tagId = :from")
    suspend fun reassign(from: String, into: String)
}

@Dao
interface TaskStepDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(step: TaskStepEntity)
    @Query("SELECT * FROM task_steps ORDER BY taskId, sortOrder, id") suspend fun all(): List<TaskStepEntity>
    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY sortOrder, id")
    suspend fun byTask(taskId: String): List<TaskStepEntity>
    @Query("SELECT * FROM task_steps WHERE id = :id") suspend fun byId(id: String): TaskStepEntity?
    @Query("DELETE FROM task_steps WHERE id = :id") suspend fun delete(id: String)
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
    /** Batched: Project Activity resolves every document of a project in one read. */
    @Query("SELECT * FROM note_documents WHERE captureItemId IN (:captureItemIds)")
    suspend fun byCaptureIds(captureItemIds: List<String>): List<NoteDocumentEntity>
    @Query("SELECT COUNT(*) FROM note_documents") suspend fun count(): Int
}

@Dao
interface PromptDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(p: PromptDocumentEntity)
    @Query("SELECT * FROM prompt_documents WHERE id = :id") suspend fun byId(id: String): PromptDocumentEntity?
    @Query("SELECT * FROM prompt_documents WHERE captureItemId = :captureItemId") suspend fun byCaptureId(captureItemId: String): PromptDocumentEntity?
    /** Batched: Project Activity resolves every document of a project in one read. */
    @Query("SELECT * FROM prompt_documents WHERE captureItemId IN (:captureItemIds)")
    suspend fun byCaptureIds(captureItemIds: List<String>): List<PromptDocumentEntity>
    @Query("SELECT COUNT(*) FROM prompt_documents") suspend fun count(): Int
}

@Dao
interface AttachmentDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(a: AttachmentDocumentEntity)
    @Query("SELECT * FROM attachment_documents WHERE id = :id") suspend fun byId(id: String): AttachmentDocumentEntity?
    @Query("SELECT * FROM attachment_documents WHERE captureItemId = :captureItemId") suspend fun byCaptureId(captureItemId: String): AttachmentDocumentEntity?
    /** Batched: Project Activity resolves every document of a project in one read. */
    @Query("SELECT * FROM attachment_documents WHERE captureItemId IN (:captureItemIds)")
    suspend fun byCaptureIds(captureItemIds: List<String>): List<AttachmentDocumentEntity>
    @Query("SELECT COUNT(*) FROM attachment_documents") suspend fun count(): Int
}

@Dao
interface VoiceDocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(v: VoiceDocumentEntity)
    @Query("SELECT * FROM voice_documents WHERE id = :id") suspend fun byId(id: String): VoiceDocumentEntity?
    @Query("SELECT * FROM voice_documents WHERE captureItemId = :captureItemId") suspend fun byCaptureId(captureItemId: String): VoiceDocumentEntity?
    /** Batched: Project Activity resolves every document of a project in one read. */
    @Query("SELECT * FROM voice_documents WHERE captureItemId IN (:captureItemIds)")
    suspend fun byCaptureIds(captureItemIds: List<String>): List<VoiceDocumentEntity>
    @Query("SELECT COUNT(*) FROM voice_documents") suspend fun count(): Int
}

/** The Notes page's own documents (v16). Shares no table with the capture note documents. */
@Dao
interface VirlinNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(n: VirlinNoteEntity)
    @Query("SELECT * FROM virlin_notes WHERE ownerKey = :ownerKey") suspend fun byOwner(ownerKey: String): VirlinNoteEntity?
    @Query("SELECT * FROM virlin_notes") suspend fun all(): List<VirlinNoteEntity>
    @Query("DELETE FROM virlin_notes WHERE ownerKey = :ownerKey") suspend fun deleteByOwner(ownerKey: String)
    @Query("SELECT COUNT(*) FROM virlin_notes") suspend fun count(): Int
}

@Dao
interface TaskPageBlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(block: TaskPageBlockEntity)
    @Query("SELECT * FROM task_page_blocks ORDER BY taskId, sortOrder, createdAt, id")
    suspend fun all(): List<TaskPageBlockEntity>
    @Query("SELECT * FROM task_page_blocks WHERE taskId = :taskId ORDER BY sortOrder, createdAt, id")
    suspend fun byTask(taskId: String): List<TaskPageBlockEntity>
    @Query("SELECT * FROM task_page_blocks WHERE id = :id") suspend fun byId(id: String): TaskPageBlockEntity?
    @Query("DELETE FROM task_page_blocks WHERE id = :id") suspend fun delete(id: String)
}

/**
 * Virlin's durable schema. v1 (Pass 5): projects, workstreams, tasks, cycles, focus_sessions,
 * context_snapshots, events, meta. v2 (Pass 10): + captures. v3: execution responsibility
 * (Project.defaultExecutionMode, WorkStream.executionPreference replacing mode,
 * Task.executionPreference). v4: + note_documents (Capture Text Note block documents).
 * v5: + prompt_documents (Capture Prompt documents).
 * v6: + attachment_documents (Capture File/Image metadata; bytes in managed files).
 * v7: + voice_documents (Capture Voice notes; clip audio in managed files).
 * v10: + workstreams.attentionRank (explicit Needs You position; null = unranked).
 * v11: + priority_preferences (durable Needs You priority policies; OneTime is never stored).
 * Every version change ships an explicit [Migration] proven by `VirlinMigrationTest`;
 * there is NO destructive fallback.
 */
@Database(
    entities = [
        ProjectEntity::class, WorkStreamEntity::class, TaskEntity::class, CycleEntity::class,
        FocusSessionEntity::class, ContextSnapshotEntity::class, EventEntity::class, MetaEntity::class,
        CaptureEntity::class, NoteDocumentEntity::class, PromptDocumentEntity::class,
        AttachmentDocumentEntity::class, VoiceDocumentEntity::class, PriorityPreferenceEntity::class,
        ExternalStageEntity::class, ProjectTagEntity::class, TagLinkEntity::class,
        TaskStepEntity::class, VirlinNoteEntity::class, TaskPageBlockEntity::class
    ],
    version = 17,
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
    abstract fun priorityPreferences(): PriorityPreferenceDao
    abstract fun externalStages(): ExternalStageDao
    abstract fun attachmentDocuments(): AttachmentDocumentDao
    abstract fun voiceDocuments(): VoiceDocumentDao
    abstract fun taskPageBlocks(): TaskPageBlockDao
    abstract fun projectTags(): ProjectTagDao
    abstract fun tagLinks(): TagLinkDao
    abstract fun taskSteps(): TaskStepDao
    abstract fun virlinNotes(): VirlinNoteDao

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
        /** v9 → v10: additive nullable `workstreams.attentionRank` (explicit Needs You position). */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workstreams` ADD COLUMN `attentionRank` INTEGER")
            }
        }
        /** v10 → v11: additive `priority_preferences` table (durable Needs You priority policies). */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `priority_preferences` (" +
                        "`streamId` TEXT NOT NULL, `preferredPosition` INTEGER NOT NULL, `scopeType` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `expiresAt` INTEGER, PRIMARY KEY(`streamId`))"
                )
            }
        }
        /**
         * v11 -> v12: external work (Phase 10). Adds `workstreams.externalActorId` and the
         * `external_stages` table. Purely additive — every existing row, check time, rank and
         * priority policy is untouched.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workstreams` ADD COLUMN `externalActorId` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `external_stages` (" +
                        "`id` TEXT NOT NULL, `workStreamId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`sortOrder` INTEGER NOT NULL, `expectedMinutes` INTEGER, `status` TEXT NOT NULL, " +
                        "`startedAt` INTEGER, `completedAt` INTEGER, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_external_stages_workStreamId` ON `external_stages` (`workStreamId`)")
            }
        }

        /**
         * v13: project tags, their links, and task steps. Additive only — no existing table is
         * touched, so nothing that was already saved can be lost.
         */
        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `project_tags` (" +
                        "`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`nameKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_tags_projectId` ON `project_tags` (`projectId`)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_project_tags_projectId_nameKey` " +
                        "ON `project_tags` (`projectId`, `nameKey`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tag_links` (" +
                        "`tagId` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetId` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`tagId`, `targetType`, `targetId`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_links_tagId` ON `tag_links` (`tagId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_links_targetType_targetId` ON `tag_links` (`targetType`, `targetId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_steps` (" +
                        "`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`done` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_steps_taskId` ON `task_steps` (`taskId`)")
            }
        }

        /**
         * v14: a persisted sibling order for workstreams.
         *
         * Until now nothing stored one: the mind map drew workstreams alphabetically and the
         * project list read them by creation time, so the two surfaces disagreed and neither
         * order was the user's. The column is backfilled with the map's existing alphabetical
         * sequence, so the surface this order was built for looks exactly as it did before the
         * upgrade; the project list adopts that same sequence instead of creation order.
         *
         * Additive: one new NOT NULL column with a default, then a backfill. No row is deleted
         * and no existing column is touched, so nothing already saved can be lost.
         */
        val MIGRATION_13_14: Migration = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workstreams` ADD COLUMN `sortOrder` INTEGER NOT NULL DEFAULT 0")
                // Rank within the project group by title, exactly as the map sorted them.
                // Streams with no project form one group, which is why projectId is compared
                // with an explicit NULL-safe test rather than `=`.
                db.execSQL(
                    "UPDATE `workstreams` SET `sortOrder` = (" +
                        "SELECT COUNT(*) FROM `workstreams` AS w2 WHERE " +
                        "((w2.`projectId` IS NULL AND `workstreams`.`projectId` IS NULL) " +
                        "OR w2.`projectId` = `workstreams`.`projectId`) AND (" +
                        "LOWER(w2.`title`) < LOWER(`workstreams`.`title`) OR (" +
                        "LOWER(w2.`title`) = LOWER(`workstreams`.`title`) " +
                        "AND w2.`id` < `workstreams`.`id`)))"
                )
            }
        }

        /**
         * v15: a task's own rich Note document (`task_note_documents`).
         *
         * Purely additive - one new table, no existing table touched - so every capture note,
         * task, step and plain `tasks.notes` string survives untouched. The legacy plain text is
         * deliberately NOT cleared here: it is imported into the block document on first open and
         * left in place, so the migration stays reversible by dropping this table alone.
         */
        val MIGRATION_14_15: Migration = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_note_documents` (" +
                        "`taskId` TEXT NOT NULL, `id` TEXT NOT NULL, `title` TEXT, " +
                        "`documentJson` TEXT NOT NULL, `importedLegacyNotes` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`))"
                )
            }
        }

        /**
         * v16: the Notes page's own documents (`virlin_notes`), and removal of the unused
         * `task_note_documents` table added in v15.
         *
         * `task_note_documents` was created in v15 but never wired to a mapper, repository or
         * codec, so it is provably empty in every build that has existed; dropping it loses no
         * user data. The Notes page is deliberately a self-contained feature, so it gets its own
         * table keyed by an opaque `ownerKey` rather than reusing the capture note documents.
         * Nothing else is touched: every project, task, step, capture and the legacy plain
         * `tasks.notes` string survive unchanged.
         */
        val MIGRATION_15_16: Migration = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `virlin_notes` (" +
                        "`ownerKey` TEXT NOT NULL, `title` TEXT, `documentJson` TEXT NOT NULL, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerKey`))"
                )
                db.execSQL("DROP TABLE IF EXISTS `task_note_documents`")
            }
        }

        /** v17: ordered references for each task's mixed-content Page. */
        val MIGRATION_16_17: Migration = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_page_blocks` (" +
                        "`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `typeKey` TEXT NOT NULL, " +
                        "`contentId` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_page_blocks_taskId` ON `task_page_blocks` (`taskId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_task_page_blocks_taskId_typeKey_contentId` ON `task_page_blocks` (`taskId`, `typeKey`, `contentId`)")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)

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
