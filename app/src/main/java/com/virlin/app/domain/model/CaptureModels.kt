package com.virlin.app.domain.model

import java.time.Instant

/**
 * Capture (Pass 10): low-friction external memory. A [CaptureItem] is NOT a Task, Project or
 * WorkStream — it is something the user did not want to lose and did not want to file yet.
 * Context (project / WorkStream / task) is optional and always chosen explicitly; nothing is
 * inferred from the content. Content is stored verbatim (line breaks preserved).
 */
enum class CaptureType { NOTE, PROMPT, LINK, FILE, VOICE }

/** Not Task statuses on purpose: capture is preservation, not execution. */
enum class CaptureStatus {
    /** Unfiled. Shows in the Capture Inbox. */
    INBOX,
    /** Filed into Virlin structure (e.g. converted to a Task). Leaves the Inbox. */
    ORGANIZED,
    /** Kept for history, out of the Inbox. Never hard-deleted by default. */
    ARCHIVED;

    val isInInbox: Boolean get() = this == INBOX
}

data class CaptureItem(
    val id: String,
    val type: CaptureType,
    /** NOTE/PROMPT text; for LINK an optional note. Verbatim. */
    val content: String,
    val title: String? = null,
    /** LINK only. Stored as entered — never fetched. */
    val sourceUrl: String? = null,
    /** Optional explicit context. `taskId` is the most specific; the others are the task's ancestry. */
    val projectId: String? = null,
    val workStreamId: String? = null,
    val taskId: String? = null,
    val status: CaptureStatus = CaptureStatus.INBOX,
    /** Set when the capture was converted to a Task (ORGANIZED). */
    val convertedTaskId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant? = null
) {
    val hasContext: Boolean get() = projectId != null || workStreamId != null || taskId != null
}
