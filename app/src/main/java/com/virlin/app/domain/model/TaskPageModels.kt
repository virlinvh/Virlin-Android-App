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
}
