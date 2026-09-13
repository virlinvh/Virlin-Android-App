package com.virlin.app.model

enum class StreamState {
    FOCUS, NEEDS_YOU, PROCESSING, READY, SNOOZED, BLOCKED, PAUSED, DONE
}

data class Project(
    val id: String,
    val name: String
)

data class WorkStream(
    val id: String,
    val title: String,
    val subtitle: String,
    val projectId: String?,
    val state: StreamState,
    val expectedDurationSec: Int? = null,
    val focusInvestedSec: Int = 0,
    val processingElapsedSec: Int = 0,
    val checkInRemainingSec: Int? = null,
    val isOverdue: Boolean = false,
    val nextAction: String? = null,
    val blockerReason: String? = null
)

data class Capture(
    val id: String,
    val type: CaptureType,
    val summary: String,
    val detail: String? = null,
    val timeAgo: String = "Just now"
)

enum class CaptureType {
    TEXT, VOICE, PROMPT, LINK, FILE, IMAGE, BUNDLE
}
