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
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?
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
    val mode: String,
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
    val completedAt: Instant?
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

/** One-row bookkeeping table: has the demo seed been applied to this database? */
@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)
