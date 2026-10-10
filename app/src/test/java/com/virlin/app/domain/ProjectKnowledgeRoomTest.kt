package com.virlin.app.domain

import androidx.room.Room
import com.virlin.app.data.db.*
import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.*
import com.virlin.app.ui.hierarchy.projectKnowledgeBlocks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectKnowledgeRoomTest {
    @Test fun projectKnowledgeDocumentsSurviveRepositoryRecreation() = runTest {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), VirlinDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val actions = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), SequentialIdProvider())
            val project = (actions.createProject(CreateProject("Knowledge")) as ActionResult.Success).value
            val owner = CaptureContext(projectId = project.id)
            val richBlocks = listOf(com.virlin.app.domain.notedoc.NoteDocBlock("rich", text = "Rich text",
                runs = listOf(com.virlin.app.domain.notedoc.NoteDocRun(0, 4, color = 0xFF123456L, fontSizeSp = 24))))
            assertTrue(actions.saveNoteDoc(com.virlin.app.domain.notedoc.CaptureNoteBridge.draftOwner(project.id, "rich-capture"), "Rich note", richBlocks) is ActionResult.Success)
            val todo = (actions.createTextNote("Checklist", projectKnowledgeBlocks("First\nSecond", true), owner) as ActionResult.Success).value
            val prompt = (actions.createCapture(CreateCapture(CaptureType.PROMPT, "Full prompt\nSecond line", context = owner)) as ActionResult.Success).value
            val file = (actions.createAttachment("file.pdf", "application/pdf", 128, "managed/file.pdf", AttachmentKind.PDF, owner) as ActionResult.Success).value
            val reopened = RoomWorkStreamRepository.create(db)
            val reloaded = DefaultVirlinActions(reopened, FakeClock(Instant.EPOCH), SequentialIdProvider())
            assertEquals(4, reopened.captures.value.size)
            assertEquals(richBlocks, reloaded.loadNoteDoc("capture-rich-capture")!!.blocks)
            assertTrue(reopened.captures.value.all { it.projectId == project.id && it.taskId == null && it.workStreamId == null })
            assertEquals(listOf("First", "Second"), reloaded.getNoteByCaptureId(todo.captureItemId)!!.blocks.map { it.plainText })
            assertEquals(prompt.content, reopened.captures.value.first { it.id == prompt.id }.content)
            assertEquals(file.relativePath, reloaded.getAttachmentByCaptureId(file.captureItemId)!!.relativePath)
            assertTrue(reopened.tasks.value.isEmpty())
            assertTrue(reopened.streams.value.isEmpty())
            assertTrue(reloaded.deleteProject(project.id) is ActionResult.Success)
            assertNull(reopened.getNoteDoc("capture-rich-capture"))
        } finally { db.close() }
    }
}
