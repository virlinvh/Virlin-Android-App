package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.hierarchy.projectKnowledgeBlocks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ProjectKnowledgeStorageTest {
    @Test fun checklistEditToggleReorderAndClearRetainIdentityAndNeverCreateTasks() = runTest {
        val now = Instant.EPOCH
        val repo = InMemoryWorkStreamRepository(seedProjects = listOf(Project("p", "Project", createdAt = now, updatedAt = now)))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        val created = (actions.createTextNote("To-do list", projectKnowledgeBlocks("First\nSecond", true), CaptureContext(projectId = "p")) as ActionResult.Success).value
        val edited = listOf(created.blocks[1], created.blocks[0].copy(plainText = "Updated", checked = true))
        assertTrue(actions.saveTextNote(created.captureItemId, "Renamed", edited) is ActionResult.Success)
        assertEquals(edited, actions.getNoteByCaptureId(created.captureItemId)!!.blocks)
        val marker = listOf(NoteBlock("empty", NoteBlockType.CHECKBOX))
        assertTrue(actions.saveTextNote(created.captureItemId, "Renamed", marker) is ActionResult.Success)
        assertEquals(marker, actions.getNoteByCaptureId(created.captureItemId)!!.blocks)
        assertEquals(com.virlin.app.domain.activity.NoteBlockShape.Counted(0, 0),
            com.virlin.app.domain.activity.NoteBlockShape.checklistProgress(marker))
        assertEquals(1, repo.captures.value.size)
        assertEquals("p", repo.captures.value.single().projectId)
        assertTrue(repo.tasks.value.isEmpty())
        assertTrue(repo.taskSteps.value.isEmpty())
    }
    @Test fun everyPaletteTypeIsStoredWithoutCreatingHierarchyAndCanAttachLater() = runTest {
        val now = Instant.parse("2026-10-05T00:00:00Z")
        val repo = InMemoryWorkStreamRepository(seedProjects = listOf(Project("p", "Project", createdAt = now, updatedAt = now)))
        val actions = DefaultVirlinActions(repo, FakeClock(now), SequentialIdProvider())
        val owner = CaptureContext(projectId = "p")
        assertTrue(actions.createTextNote("To-do", projectKnowledgeBlocks("First\nSecond", true), owner) is ActionResult.Success)
        assertTrue(actions.createTextNote("Note", projectKnowledgeBlocks("Full note\n\nDetails", false), owner) is ActionResult.Success)
        assertTrue(actions.createCapture(CreateCapture(CaptureType.PROMPT, content = "Full prompt\nDetails", context = owner)) is ActionResult.Success)
        assertTrue(actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "https://example.com", context = owner)) is ActionResult.Success)
        for ((name, mime, kind) in listOf(Triple("file.pdf", "application/pdf", AttachmentKind.PDF),
            Triple("image.png", "image/png", AttachmentKind.IMAGE), Triple("file.zip", "application/zip", AttachmentKind.ARCHIVE),
            Triple("audio.mp3", "audio/mpeg", AttachmentKind.AUDIO))) {
            assertTrue(actions.createAttachment(name, mime, 128, "managed/$name", kind, owner) is ActionResult.Success)
        }
        assertTrue(actions.createVoice("Recording", listOf(VoiceClip("clip", "Audio", "managed/clip.m4a", 1000, 128,
            sortOrder = 0, createdAt = now)), owner) is ActionResult.Success)
        val captures = repo.captures.value
        val audio = captures.first { it.title == "audio.mp3" }
        val attachment = actions.getAttachmentByCaptureId(audio.id)!!
        val item = com.virlin.app.ui.hierarchy.knowledgeItems("p", listOf(
            com.virlin.app.ui.hierarchy.HierarchyViewModel.KnowledgeEntry(audio, attachment = attachment)), emptyList(), emptyList()).single()
        assertEquals(com.virlin.app.ui.hierarchy.KnowledgeType.AUDIO, item.type)
        assertEquals(attachment.relativePath, item.mediaPath)
        assertEquals(9, captures.size)
        assertTrue(captures.all { it.projectId == "p" && it.taskId == null && it.workStreamId == null && it.status == CaptureStatus.INBOX })
        assertTrue(repo.tasks.value.isEmpty())
        assertTrue(repo.streams.value.isEmpty())
        val task = (actions.createTask(CreateTask("Organized task", projectId = "p")) as ActionResult.Success).value
        val note = captures.first { it.title == "Note" }
        assertTrue(actions.attachCapture(note.id, CaptureContext(projectId = "p", taskId = task.id)) is ActionResult.Success)
        val attached = repo.captures.value.first { it.id == note.id }
        assertEquals(task.id, attached.taskId)
        assertEquals(note.content, attached.content)
        assertEquals(note.id, attached.id)
        assertEquals(1, repo.tasks.value.size)
    }

    @Test fun invalidLinkAndMissingProjectDoNotLeaveCaptures() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
        assertTrue(actions.createCapture(CreateCapture(CaptureType.LINK, sourceUrl = "not a URL")) is ActionResult.Rejected)
        assertTrue(actions.createTextNote("Draft", projectKnowledgeBlocks("Body", false), CaptureContext(projectId = "missing")) !is ActionResult.Success)
        assertTrue(repo.captures.value.isEmpty())
    }
}
