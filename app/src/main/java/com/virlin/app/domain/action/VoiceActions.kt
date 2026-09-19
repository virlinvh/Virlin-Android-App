package com.virlin.app.domain.action

import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.VoiceClip
import com.virlin.app.domain.model.VoiceDocument
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.domain.voice.VoiceDocumentCodec

/**
 * Voice note ops. Capture lifecycle stays on CaptureItem(VOICE);
 * audio bytes live in managed storage — this layer persists metadata + clip list.
 */
internal class VoiceActions(
    private val repository: WorkStreamRepository,
    private val clock: VirlinClock,
    private val ids: IdProvider,
    private val capture: CaptureActions
) {

    suspend fun createVoice(
        title: String?,
        clips: List<VoiceClip>,
        context: CaptureContext = CaptureContext.None,
        captureId: String? = null,
        voiceId: String? = null
    ): ActionResult<VoiceDocument> = tx {
        val now = clock.now()
        val doc = VoiceDocument(
            id = voiceId ?: ids.newId("vox"),
            captureItemId = captureId ?: ids.newId("cap"),
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            clips = clips,
            createdAt = now,
            updatedAt = now
        )
        if (!doc.hasMeaningfulContent()) return@tx ActionResult.Rejected(DomainError.EmptyCapture)
        val preview = VoiceDocumentCodec.preview(doc)
        val create = CreateCapture(
            type = CaptureType.VOICE,
            content = preview,
            title = doc.title,
            context = context,
            id = doc.captureItemId
        )
        when (val r = capture.createCaptureIn(this, create)) {
            is ActionResult.Success -> Unit
            is ActionResult.Rejected -> return@tx ActionResult.Rejected(r.reason)
            is ActionResult.NotFound -> return@tx r
            is ActionResult.Failure -> return@tx r
        }
        saveVoiceDocument(doc)
        ActionResult.Success(doc)
    }

    suspend fun saveVoice(
        captureItemId: String,
        title: String?,
        clips: List<VoiceClip>
    ): ActionResult<VoiceDocument> = tx {
        val cap = getCapture(captureItemId)
            ?: return@tx ActionResult.Rejected(DomainError.CaptureNotFound(captureItemId))
        if (cap.type != CaptureType.VOICE) return@tx ActionResult.Rejected(DomainError.NotAVoiceNote)
        val now = clock.now()
        val existing = getVoiceByCaptureId(captureItemId)
        val doc = (existing ?: VoiceDocument(
            id = ids.newId("vox"),
            captureItemId = captureItemId,
            title = null,
            clips = emptyList(),
            createdAt = cap.createdAt,
            updatedAt = now
        )).copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            clips = clips,
            updatedAt = now
        )
        if (!doc.hasMeaningfulContent()) return@tx ActionResult.Rejected(DomainError.EmptyCapture)
        val preview = VoiceDocumentCodec.preview(doc)
        saveCapture(
            cap.copy(
                title = doc.title,
                content = preview,
                updatedAt = now
            )
        )
        saveVoiceDocument(doc)
        ActionResult.Success(doc)
    }

    suspend fun getVoiceByCaptureId(captureItemId: String): VoiceDocument? =
        repository.getVoiceByCaptureId(captureItemId)

    private suspend fun <T> tx(block: suspend WorkStreamWriter.() -> ActionResult<T>): ActionResult<T> =
        try {
            repository.transaction(block)
        } catch (e: Exception) {
            ActionResult.Failure(e)
        }
}
