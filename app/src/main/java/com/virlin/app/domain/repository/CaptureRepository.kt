package com.virlin.app.domain.repository

import com.virlin.app.domain.model.AttachmentDocument
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.model.PromptDocument
import com.virlin.app.domain.model.VoiceDocument
import kotlinx.coroutines.flow.StateFlow

/**
 * Capture persistence contract (Pass 10). Kept as its own small abstraction, but implemented
 * by the SAME repository/transaction as WorkStreams so "convert capture to Task" can create
 * the Task and mark the capture ORGANIZED atomically. Publish-on-commit, like everything else.
 *
 * Text Note documents are optional companions of NOTE captures (schema v4).
 * Prompt documents are optional companions of PROMPT captures (schema v5).
 * Attachment documents are optional companions of FILE captures (schema v6).
 * Voice documents are optional companions of VOICE captures (schema v7).
 */
interface CaptureRepository {
    /** Every capture, newest first by persisted `createdAt` (ties broken by id). */
    val captures: StateFlow<List<CaptureItem>>
    suspend fun getCapture(id: String): CaptureItem?
    suspend fun getNoteByCaptureId(captureItemId: String): NoteDocument?
    suspend fun getNoteDocument(id: String): NoteDocument?
    suspend fun getPromptByCaptureId(captureItemId: String): PromptDocument?
    suspend fun getPromptDocument(id: String): PromptDocument?
    suspend fun getAttachmentByCaptureId(captureItemId: String): AttachmentDocument?
    suspend fun getAttachmentDocument(id: String): AttachmentDocument?
    suspend fun getVoiceByCaptureId(captureItemId: String): VoiceDocument?
    suspend fun getVoiceDocument(id: String): VoiceDocument?
}

/** Capture writes inside a repository transaction. */
interface CaptureWriter {
    suspend fun getCapture(id: String): CaptureItem?
    suspend fun saveCapture(capture: CaptureItem)
    suspend fun getNoteByCaptureId(captureItemId: String): NoteDocument?
    suspend fun saveNoteDocument(note: NoteDocument)
    suspend fun getPromptByCaptureId(captureItemId: String): PromptDocument?
    suspend fun savePromptDocument(prompt: PromptDocument)
    suspend fun getAttachmentByCaptureId(captureItemId: String): AttachmentDocument?
    suspend fun saveAttachmentDocument(attachment: AttachmentDocument)
    suspend fun getVoiceByCaptureId(captureItemId: String): VoiceDocument?
    suspend fun saveVoiceDocument(voice: VoiceDocument)
}

fun List<CaptureItem>.newestFirst(): List<CaptureItem> =
    sortedWith(compareByDescending<CaptureItem> { it.createdAt }.thenByDescending { it.id })
