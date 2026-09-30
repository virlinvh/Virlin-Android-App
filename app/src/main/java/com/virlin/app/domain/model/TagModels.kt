package com.virlin.app.domain.model

import java.time.Instant

/**
 * PROJECT TAGS — a project's own vocabulary. A tag belongs to exactly one Project, so a tag
 * made in one project can never label another project's work.
 *
 * The id is stable for the tag's whole life: renaming changes the name, never the id, so every
 * link survives a rename untouched.
 */
data class ProjectTag(
    val id: String,
    val projectId: String,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    /** Two tags in a project may not differ only by case or surrounding space. */
    val key: String get() = name.trim().lowercase()
}

/** What a tag is attached to. Tags label things; they never own them. */
enum class TagTargetType { CAPTURE, TASK }

/** One tag attached to one thing. The pair is the identity — a tag applies at most once. */
data class TagLink(
    val tagId: String,
    val targetType: TagTargetType,
    val targetId: String,
    val createdAt: Instant
)

/**
 * TASK STEPS — the ordered checkboxes inside a task.
 *
 * A step is NOT a task: it has no attention state, no execution mode and no place in progress.
 * Ticking every step does not complete the task, because completion is the Action Layer's
 * decision and Virlin has no rule that says otherwise.
 */
/**
 * What one "clear completed" removed, kept so the operation can be reversed exactly.
 * [survivorIds] is the list as it stood straight after the clear; if it no longer matches, the
 * list has been edited since and a faithful restore is refused rather than approximated.
 */
data class ClearedSteps(
    val taskId: String,
    val removed: List<TaskStep>,
    val survivorIds: List<String>,
)

data class TaskStep(
    val id: String,
    val taskId: String,
    val text: String,
    val done: Boolean = false,
    val order: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant
)
