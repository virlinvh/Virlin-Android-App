package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.model.VoiceClip
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Audio v1 = a RECORDED voice note owned by a task.
 *
 * These tests pin the ownership chain that makes the mind-map Audio entry work: an explicit task
 * context reaches `createVoice`, the capture is filed against that task, and `CaptureActions`
 * registers exactly one `capture.voice` Page block in the same transaction. Imported audio is
 * deliberately NOT part of this lane; it stays with `AttachmentKind.AUDIO`.
 */
class AudioWorkspaceTest {

    private val clock = FakeClock(Instant.parse("2026-10-01T09:00:00Z"))
    private val ids = SequentialIdProvider()

    private fun clip(id: String = "c1", ms: Long = 1_500L) = VoiceClip(
        id = id,
        displayName = "Clip 1",
        relativePath = "cap-1/$id.m4a",
        durationMs = ms,
        sizeBytes = 2_048L,
        sortOrder = 0,
        createdAt = clock.now(),
    )

    private suspend fun seed(repo: InMemoryWorkStreamRepository): Task {
        val now = clock.now()
        return repo.transaction {
            val project = Project(id = "p1", title = "Audio project", createdAt = now, updatedAt = now)
            saveProject(project)
            val stream = WorkStream(
                id = "ws1", title = "Stream", state = WorkStreamState.READY,
                projectId = project.id, createdAt = now, updatedAt = now,
            )
            saveStream(stream)
            val task = Task(
                id = "t1", title = "Task", workStreamId = stream.id, projectId = project.id,
                createdAt = now, updatedAt = now,
            )
            saveTask(task)
            task
        }
    }

    // ---- route contract -------------------------------------------------------------------

    @Test fun taskAudioRoute_isIdBasedAndParsable() {
        val route = com.virlin.app.ui.voice.voiceEditorForTask("t1")
        assertEquals("voice_editor/new/task/t1", route)
        // The registered pattern and the built route agree on shape.
        assertEquals(
            com.virlin.app.ui.voice.VoiceEditorTaskRoute.replace("{taskId}", "t1"),
            route,
        )
    }

    @Test fun taskAudioRoute_rejectsABlankTask() {
        listOf("", "   ").forEach { bad ->
            runCatching { com.virlin.app.ui.voice.voiceEditorForTask(bad) }
                .onSuccess { throw AssertionError("accepted a blank task id") }
        }
    }

    @Test fun existingVoiceRoutes_remainCompatible() {
        assertEquals("voice_editor", com.virlin.app.ui.voice.voiceEditorRoute(null))
        assertEquals("voice_editor", com.virlin.app.ui.voice.voiceEditorRoute(""))
        assertEquals("voice_editor/cap-9", com.virlin.app.ui.voice.voiceEditorRoute("cap-9"))
    }

    @Test fun taskPageReopensByPersistedCaptureId() {
        // The Page block stores the capture id; reopening must route by that id, never by title.
        assertEquals("voice_editor/cap-7", com.virlin.app.ui.voice.voiceEditorRoute("cap-7"))
        assertEquals("file_viewer/cap-8", com.virlin.app.ui.file.fileViewerRoute("cap-8"))
    }

    // ---- ownership and Page registration ----------------------------------------------------

    @Test fun savingWithTaskContext_createsATaskOwnedVoiceCapture() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)

        val result = actions.createVoice(
            title = "Recorded idea",
            clips = listOf(clip()),
            context = CaptureContext(taskId = task.id),
        )
        assertTrue(result is ActionResult.Success)

        val capture = repo.captures.value.single()
        assertEquals(CaptureType.VOICE, capture.type)
        assertEquals(task.id, capture.taskId)
        // Project and WorkStream are derived from the task, so ownership has one source of truth.
        assertEquals(task.workStreamId, capture.workStreamId)
        assertEquals(task.projectId, capture.projectId)
    }

    @Test fun savingRegistersExactlyOneVoicePageBlock() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)

        actions.createVoice("Recorded", listOf(clip()), CaptureContext(taskId = task.id))

        val blocks = repo.getTaskPageBlocks(task.id)
        assertEquals(1, blocks.size)
        assertEquals(TaskPageTypeKeys.capture(CaptureType.VOICE), blocks.single().typeKey)
        assertEquals(repo.captures.value.single().id, blocks.single().contentId)
    }

    @Test fun resavingTheSameRecording_doesNotDuplicateThePageBlock() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)

        val created = actions.createVoice("First", listOf(clip()), CaptureContext(taskId = task.id))
        val captureId = repo.captures.value.single().id
        assertTrue(created is ActionResult.Success)

        actions.saveVoice(captureId, "Edited title", listOf(clip(ms = 3_000L)))
        actions.saveVoice(captureId, "Edited again", listOf(clip(ms = 4_000L)))

        assertEquals(1, repo.getTaskPageBlocks(task.id).size)
        assertEquals(1, repo.captures.value.size)
    }

    @Test fun globalRecordingCreatesNoTaskPageBlock() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)

        actions.createVoice("Inbox note", listOf(clip()), CaptureContext.None)

        assertNull(repo.captures.value.single().taskId)
        assertTrue(repo.getTaskPageBlocks(task.id).isEmpty())
    }

    @Test fun cancellingWithoutARecording_createsNothing() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)

        // No clips: the editor refuses to commit, so neither a capture nor a Page block appears.
        val result = actions.createVoice("Nothing recorded", emptyList(), CaptureContext(taskId = task.id))

        assertTrue(result is ActionResult.Rejected)
        assertTrue(repo.captures.value.isEmpty())
        assertTrue(repo.getTaskPageBlocks(task.id).isEmpty())
    }

    @Test fun aZeroLengthClipIsNotMeaningfulContent() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)

        val result = actions.createVoice(
            "Empty", listOf(clip(ms = 0L)), CaptureContext(taskId = task.id),
        )
        assertTrue(result is ActionResult.Rejected)
        assertTrue(repo.getTaskPageBlocks(task.id).isEmpty())
    }

    @Test fun savedRecordingStaysReadableAndEditable() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)

        actions.createVoice("Original", listOf(clip()), CaptureContext(taskId = task.id))
        val captureId = repo.captures.value.single().id

        val reloaded = repo.getVoiceByCaptureId(captureId)
        assertNotNull(reloaded)
        assertEquals("Original", reloaded!!.title)
        assertEquals(1, reloaded.clips.size)

        actions.saveVoice(captureId, "Renamed", listOf(clip(), clip(id = "c2")))
        val afterEdit = repo.getVoiceByCaptureId(captureId)!!
        assertEquals("Renamed", afterEdit.title)
        assertEquals(2, afterEdit.clips.size)
        // The capture keeps its task ownership across edits.
        assertEquals(task.id, repo.captures.value.single().taskId)
    }

    @Test fun recordingOnOneTaskDoesNotAppearOnAnother() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)
        val other = repo.transaction {
            val t = Task(
                id = "t2", title = "Other", workStreamId = "ws1", projectId = "p1",
                createdAt = clock.now(), updatedAt = clock.now(),
            )
            saveTask(t); t
        }

        actions.createVoice("Owned by t1", listOf(clip()), CaptureContext(taskId = task.id))

        assertEquals(1, repo.getTaskPageBlocks(task.id).size)
        assertTrue(repo.getTaskPageBlocks(other.id).isEmpty())
    }
}
