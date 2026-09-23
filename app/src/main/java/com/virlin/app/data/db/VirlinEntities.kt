package com.virlin.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import java.time.Duration
import java.time.Instant

/**
 * Room entities — persistence shape only. Domain models stay in `domain/model`; mapping lives
 * in [VirlinMappers]. Every timestamp is an ABSOLUTE instant (epoch millis); no countdowns or
 * elapsed counters are stored. Enums are stored by NAME (stable strings), never by ordinal.
 *
 * V1 relationship policy: ids reference each other by convention (indexed), with NO SQL
 * foreign keys and NO cascades. Virlin values history; there is no delete API yet, and the
 * delete/archive product policy is not finalized — so nothing can be removed accidentally.
 *
 * Schema v3: Project.defaultExecutionMode, WorkStream.executionPreference (replaces mode),
 * Task.executionPreference.
 */

class VirlinConverters {
    @TypeConverter fun instantToLong(i: Instant?): Long? = i?.toEpochMilli()
    @TypeConverter fun longToInstant(l: Long?): Instant? = l?.let(Instant::ofEpochMilli)
    @TypeConverter fun durationToLong(d: Duration?): Long? = d?.toMillis()
    @TypeConverter fun longToDuration(l: Long?): Duration? = l?.let(Duration::ofMillis)
}

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val status: String,
    val priority: String,
    val dueAt: Instant?,
    val estimatedEffort: Duration?,
    /** HUMAN | EXTERNAL — Project default for inheriting descendants. */
    val defaultExecutionMode: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    /** v8: relative path of the custom project icon in the managed store; null = fallback avatar. */
    val iconPath: String? = null,
    /** v9: chosen built-in icon id (semantic string); null = automatic. */
    val iconId: String? = null
)

/**
 * v11 — a durable Needs You priority policy (Phase 07). ONE row per WorkStream (the stable id is
 * the primary key), so saving again replaces the policy instead of creating a competing one.
 * `OneTime` is never stored. No foreign key: a preference may outlive its stream and is simply
 * ignored and cleaned up, so attention can never crash on stale data.
 */
@Entity(tableName = "priority_preferences")
data class PriorityPreferenceEntity(
    @PrimaryKey val streamId: String,
    /** 1-based position the user asked for; clamped against the live queue when applied. */
    val preferredPosition: Int,
    /** ALWAYS | CURRENT_TERM | UNTIL — the typed scope, never a UI label. */
    val scopeType: String,
    val createdAt: Instant,
    /** Set only for UNTIL. */
    val expiresAt: Instant?
)

@Entity(
    tableName = "workstreams",
    indices = [Index("projectId"), Index("state"), Index("checkAt"), Index("activeTaskId")]
)
data class WorkStreamEntity(
    @PrimaryKey val id: String,
    val title: String,
    val projectId: String?,
    val tool: String?,
    /** INHERIT | HUMAN | EXTERNAL — replaces v1/v2 `mode`. */
    val executionPreference: String,
    val state: String,
    val priority: String,
    val pinned: Boolean,
    val lastHumanAction: String?,
    val waitingFor: String?,
    val nextHumanAction: String?,
    val blockerReason: String?,
    val processingStartedAt: Instant?,
    val checkAt: Instant?,
    val snoozedUntil: Instant?,
    val snoozeReason: String?,
    val currentCycleId: String?,
    val cycleCount: Int,
    /** The only stored current-task truth. The path is derived from tasks.parentTaskId. */
    val activeTaskId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
    /** v10: explicit Needs You position (1-based) while in CHECK; null = unranked. */
    val attentionRank: Int? = null
)

@Entity(
    tableName = "tasks",
    indices = [Index("projectId"), Index("workStreamId"), Index("parentTaskId"), Index("status")]
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val projectId: String?,
    val workStreamId: String?,
    /** Recursive nesting; no depth limit anywhere. */
    val parentTaskId: String?,
    val status: String,
    @ColumnInfo(name = "sortOrder") val order: Int,
    val estimatedEffort: Duration?,
    val dueAt: Instant?,
    val reminderAt: Instant?,
    val priority: String,
    val notes: String?,
    /** INHERIT | HUMAN | EXTERNAL. */
    val executionPreference: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?
)

/**
 * Capture (Pass 10, schema v2). Enums by name, timestamps as epoch millis, content as TEXT
 * (no artificial length limit). No UI/form state is persisted.
 */
@Entity(tableName = "captures", indices = [Index("status"), Index("createdAt"), Index("workStreamId"), Index("projectId"), Index("taskId")])
data class CaptureEntity(
    @PrimaryKey val id: String,
    val type: String,
    val content: String,
    val title: String?,
    val sourceUrl: String?,
    val projectId: String?,
    val workStreamId: String?,
    val taskId: String?,
    val status: String,
    val convertedTaskId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant?
)

@Entity(tableName = "cycles", indices = [Index("workStreamId")])
data class CycleEntity(
    @PrimaryKey val id: String,
    val workStreamId: String,
    val number: Int,
    val startedAt: Instant,
    val handedOffAt: Instant?,
    val endedAt: Instant?,
    /** Insertion order, so "latest" queries are deterministic. */
    val seq: Long
)

@Entity(tableName = "focus_sessions", indices = [Index("workStreamId"), Index("endedAt")])
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val workStreamId: String,
    val cycleId: String?,
    val startedAt: Instant,
    /** Null = still open. Duration is always derived; never stored. */
    val endedAt: Instant?,
    val taskId: String?,
    val seq: Long
)

@Entity(tableName = "context_snapshots", indices = [Index("workStreamId")])
data class ContextSnapshotEntity(
    @PrimaryKey val id: String,
    val workStreamId: String,
    val cycleId: String?,
    val createdAt: Instant,
    val reason: String,
    val lastHumanAction: String?,
    val waitingFor: String?,
    val nextHumanAction: String?,
    val checkAt: Instant?,
    val contextLabel: String?,
    val note: String?,
    val taskId: String?,
    val seq: Long
)

@Entity(tableName = "events", indices = [Index("workStreamId")])
data class EventEntity(
    @PrimaryKey val id: String,
    val workStreamId: String,
    val type: String,
    val at: Instant,
    val cycleId: String?,
    val fromState: String?,
    val toState: String?,
    val detail: String?,
    val seq: Long
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String
)

/**
 * Text Note document (schema v4). Block tree lives in [documentJson] via [com.virlin.app.domain.note.NoteDocumentCodec].
 * Lifecycle remains on [CaptureEntity]; one note per captureItemId.
 */
@Entity(
    tableName = "note_documents",
    indices = [Index(value = ["captureItemId"], unique = true)]
)
data class NoteDocumentEntity(
    @PrimaryKey val id: String,
    val captureItemId: String,
    val title: String?,
    val documentJson: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Prompt document (schema v5). Block tree in [documentJson] via NoteDocumentCodec;
 * tags in [tagsJson]. Lifecycle remains on [CaptureEntity].
 */
@Entity(
    tableName = "prompt_documents",
    indices = [Index(value = ["captureItemId"], unique = true)]
)
data class PromptDocumentEntity(
    @PrimaryKey val id: String,
    val captureItemId: String,
    val title: String?,
    val description: String?,
    val tagsJson: String,
    val documentJson: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * File/Image attachment metadata (schema v6). Original bytes live under managed storage;
 * [relativePath] is relative to filesDir/attachments/. Lifecycle remains on [CaptureEntity].
 */
@Entity(
    tableName = "attachment_documents",
    indices = [Index(value = ["captureItemId"], unique = true)]
)
data class AttachmentDocumentEntity(
    @PrimaryKey val id: String,
    val captureItemId: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val relativePath: String,
    val kind: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Voice note document (schema v7). Clip metadata in [clipsJson] via VoiceDocumentCodec;
 * audio bytes under managed filesDir/voices/. Lifecycle remains on [CaptureEntity].
 */
@Entity(
    tableName = "voice_documents",
    indices = [Index(value = ["captureItemId"], unique = true)]
)
data class VoiceDocumentEntity(
    @PrimaryKey val id: String,
    val captureItemId: String,
    val title: String?,
    val clipsJson: String,
    val createdAt: Instant,
    val updatedAt: Instant
)
