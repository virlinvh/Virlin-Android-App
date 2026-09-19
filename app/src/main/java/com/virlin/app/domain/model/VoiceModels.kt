package com.virlin.app.domain.model

import java.time.Instant

/**
 * Voice note companion to [CaptureItem] type VOICE.
 * Original audio bytes live under managed storage; [clips] hold metadata + relative paths.
 */
data class VoiceClip(
    val id: String,
    val displayName: String,
    val relativePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String = "audio/mp4",
    val sortOrder: Int,
    val createdAt: Instant
)

data class VoiceDocument(
    val id: String,
    val captureItemId: String,
    val title: String?,
    val clips: List<VoiceClip>,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun hasMeaningfulContent(): Boolean = clips.any { it.durationMs > 0L && it.relativePath.isNotBlank() }

    fun sortedClips(): List<VoiceClip> = clips.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }, { it.id }))
}
