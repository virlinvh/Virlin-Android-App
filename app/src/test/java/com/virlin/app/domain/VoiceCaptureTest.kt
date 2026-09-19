package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.VoiceClip
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.domain.voice.VoiceDocumentCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VoiceCaptureTest {

    private val clock = object : VirlinClock {
        override fun now(): Instant = Instant.parse("2026-09-15T12:00:00Z")
    }
    private val repo = InMemoryWorkStreamRepository()
    private val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())

    @Test
    fun codec_roundTripsClips() {
        val clip = VoiceClip(
            id = "c1",
            displayName = "Voice 1",
            relativePath = "cap/c1.m4a",
            durationMs = 4200,
            sizeBytes = 9000,
            sortOrder = 0,
            createdAt = Instant.parse("2026-09-15T12:00:00Z")
        )
        val json = VoiceDocumentCodec.encodeClips(listOf(clip))
        val back = VoiceDocumentCodec.decodeClips(json)
        assertEquals(1, back.size)
        assertEquals("Voice 1", back[0].displayName)
        assertEquals(4200L, back[0].durationMs)
        assertEquals("0:04", VoiceDocumentCodec.formatDuration(4200))
    }

    @Test
    fun createVoice_commitsOnce() = runBlocking {
        val clip = VoiceClip(
            id = "c1",
            displayName = "Voice 1",
            relativePath = "cap_v/c1.m4a",
            durationMs = 23000,
            sizeBytes = 40_000,
            sortOrder = 0,
            createdAt = clock.now()
        )
        val r = actions.createVoice(
            title = "Meeting Ideas",
            clips = listOf(clip),
            captureId = "cap_v",
            voiceId = "vox1"
        )
        assertTrue(r is ActionResult.Success)
        val doc = (r as ActionResult.Success).value
        assertEquals(CaptureType.VOICE, repo.getCapture(doc.captureItemId)!!.type)
        assertEquals("Meeting Ideas", repo.getVoiceByCaptureId(doc.captureItemId)!!.title)
        assertEquals(1, repo.captures.value.count { it.id == doc.captureItemId })

        val again = actions.saveVoice(
            captureItemId = doc.captureItemId,
            title = "Meeting Ideas",
            clips = listOf(clip, clip.copy(id = "c2", displayName = "Voice 2", sortOrder = 1))
        )
        assertTrue(again is ActionResult.Success)
        assertEquals(2, repo.getVoiceByCaptureId(doc.captureItemId)!!.clips.size)
        assertEquals(1, repo.captures.value.count { it.id == doc.captureItemId })
    }

    @Test
    fun emptyVoice_rejected() = runBlocking {
        val r = actions.createVoice(title = "x", clips = emptyList())
        assertTrue(r is ActionResult.Rejected)
        assertEquals(DomainError.EmptyCapture, (r as ActionResult.Rejected).reason)
    }

    @Test
    fun saveVoice_rejectsNonVoice() = runBlocking {
        actions.createCapture(
            com.virlin.app.domain.action.CreateCapture(type = CaptureType.NOTE, content = "hi", id = "cap_n")
        )
        val r = actions.saveVoice(
            "cap_n",
            "t",
            listOf(
                VoiceClip("c", "V", "a/b.m4a", 1000, 100, sortOrder = 0, createdAt = clock.now())
            )
        )
        assertTrue(r is ActionResult.Rejected)
        assertEquals(DomainError.NotAVoiceNote, (r as ActionResult.Rejected).reason)
    }
}
