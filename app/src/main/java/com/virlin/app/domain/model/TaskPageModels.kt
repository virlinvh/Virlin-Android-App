package com.virlin.app.domain.model

import java.time.Instant

/** An ordered reference from a task Page to content owned by another feature. */
data class TaskPageBlock(
    val id: String,
    val taskId: String,
    val typeKey: String,
    val contentId: String,
    val order: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

object TaskPageTypeKeys {
    const val TODO = "task.todo"
    const val NOTE = "task.note"
    fun capture(type: CaptureType): String = "capture.${type.name.lowercase()}"
    /** Canonical task Notes owner. Keep aligned with Notes routes; existing rows use this form. */
    fun noteOwner(taskId: String): String = "task-$taskId"

    private const val EXTRA_NOTE = "#note-"

    /** Owner of one more note on the same task. Task ids never contain [EXTRA_NOTE]. */
    fun extraNoteOwner(taskId: String, noteId: String): String = "${noteOwner(taskId)}$EXTRA_NOTE$noteId"

    fun isExtraNoteOwner(ownerKey: String): Boolean = ownerKey.startsWith("task-") && EXTRA_NOTE in ownerKey

    /** The task a task note (canonical or extra) belongs to; null for any other owner. */
    fun noteTaskId(ownerKey: String): String? =
        if (ownerKey.startsWith("task-")) ownerKey.removePrefix("task-").substringBefore(EXTRA_NOTE) else null

    /** Extra task notes share their task's canonical owner for deletion and ownership checks. */
    fun canonicalNoteOwner(ownerKey: String): String =
        if (isExtraNoteOwner(ownerKey)) noteOwner(noteTaskId(ownerKey)!!) else ownerKey

    /** SQL-safe prefix shared by every extra note of [taskId]. */
    fun extraNotePrefix(taskId: String): String = "${noteOwner(taskId)}$EXTRA_NOTE"
}
