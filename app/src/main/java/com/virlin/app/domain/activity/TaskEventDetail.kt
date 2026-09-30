package com.virlin.app.domain.activity

import com.virlin.app.domain.model.TaskStatus

/**
 * Task-history events carry the task id in `WorkStreamEvent.detail`. Reading the *current*
 * task to describe a *past* event is wrong — the title and the status have moved on since.
 * So the event also carries an immutable snapshot of the title and of the status transition
 * that produced it.
 *
 * Backward compatible on purpose: events written before this existed hold a bare id, and
 * [decode] still reads them (with no snapshot, which is the honest answer for those rows).
 */
object TaskEventDetail {

    private const val SEP = '\u001F'

    data class Snapshot(
        val taskId: String,
        /** The title as it was when the event happened; null for pre-snapshot history. */
        val title: String? = null,
        val fromStatus: TaskStatus? = null,
        val toStatus: TaskStatus? = null
    )

    fun encode(
        taskId: String,
        title: String,
        fromStatus: TaskStatus? = null,
        toStatus: TaskStatus? = null
    ): String = listOf(taskId, title, fromStatus?.name.orEmpty(), toStatus?.name.orEmpty())
        .joinToString(SEP.toString())

    fun decode(detail: String?): Snapshot? {
        val raw = detail?.takeIf { it.isNotBlank() } ?: return null
        val parts = raw.split(SEP)
        if (parts.size == 1) return Snapshot(parts[0])
        return Snapshot(
            taskId = parts[0],
            title = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
            fromStatus = parts.getOrNull(2)?.let { status(it) },
            toStatus = parts.getOrNull(3)?.let { status(it) }
        )
    }

    private fun status(value: String): TaskStatus? =
        TaskStatus.entries.firstOrNull { it.name == value }
}
