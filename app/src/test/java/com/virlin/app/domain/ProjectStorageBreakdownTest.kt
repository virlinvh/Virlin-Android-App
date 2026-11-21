package com.virlin.app.domain

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.data.flashcard.SavedFlashcard
import com.virlin.app.data.noteaudio.NoteAudioStore
import com.virlin.app.data.noteimage.NoteImageStore
import com.virlin.app.data.project.ProjectStorageKeys as K
import com.virlin.app.data.project.ProjectStorageTools
import com.virlin.app.data.project.projectStorageBreakdown
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.model.*
import com.virlin.app.domain.notedoc.NoteDocAudio
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.project.projectSnapshot
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.hierarchy.ProjectStorageSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.UUID

/** Every kind a project stores is counted exactly once, under the right heading, and nothing else is. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h900dp-xhdpi")
class ProjectStorageBreakdownTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val at = Instant.EPOCH

    private fun file(f: File, bytes: Int): File { f.parentFile!!.mkdirs(); f.writeBytes(ByteArray(bytes)); return f }
    private fun noteImage(bytes: Int): String = UUID.randomUUID().toString().also { file(NoteImageStore.resolve(context, it)!!, bytes) }
    private fun noteAudio(bytes: Int): String = "${UUID.randomUUID()}.m4a".also { file(NoteAudioStore.resolve(context, it)!!, bytes) }

    private suspend fun fixture(): Triple<InMemoryWorkStreamRepository, DefaultVirlinActions, Map<String, String>> {
        val repo = InMemoryWorkStreamRepository()
        repo.transaction {
            saveProject(Project("p", "Mine", createdAt = at, updatedAt = at))
            saveProject(Project("other", "Not mine", createdAt = at, updatedAt = at))
            saveTask(Task("t", "A task", projectId = "p", createdAt = at, updatedAt = at))
            saveCapture(CaptureItem("img", CaptureType.FILE, "", title = "Photo", projectId = "p", createdAt = at, updatedAt = at))
            saveAttachmentDocument(AttachmentDocument("a1", "img", "photo.jpg", "image/jpeg", 300, "a1/photo.jpg", AttachmentKind.IMAGE, at, at))
            saveCapture(CaptureItem("pdf", CaptureType.FILE, "", title = "Doc", projectId = "p", createdAt = at, updatedAt = at))
            saveAttachmentDocument(AttachmentDocument("a2", "pdf", "doc.pdf", "application/pdf", 500, "a2/doc.pdf", AttachmentKind.PDF, at, at))
            saveCapture(CaptureItem("x", CaptureType.FILE, "", title = "Other", projectId = "other", createdAt = at, updatedAt = at))
            saveAttachmentDocument(AttachmentDocument("a3", "x", "big.bin", "application/octet-stream", 9000, "a3/big.bin", AttachmentKind.TEXT, at, at))
        }
        file(AttachmentFileStore.resolve(context, "a1/photo.jpg"), 300)
        file(AttachmentFileStore.resolve(context, "a2/doc.pdf"), 500)
        file(AttachmentFileStore.resolve(context, "a2/edited.json"), 70) // the PDF's sidecar
        file(AttachmentFileStore.resolve(context, "a3/big.bin"), 9000)
        val shared = noteImage(1000); val noteOnly = noteImage(400); val cardOnly = noteImage(250); val clip = noteAudio(800)
        val actions = DefaultVirlinActions(repo, FakeClock(at), SequentialIdProvider())
        actions.saveNoteDoc("project-p", "Note", listOf(
            NoteDocBlock("b1", text = "Hello"),
            NoteDocBlock("i1", NoteDocBlockType.IMAGE, imagePath = shared),
            NoteDocBlock("i2", NoteDocBlockType.IMAGE, imagePath = noteOnly),
            NoteDocBlock("i3", NoteDocBlockType.IMAGE, imagePath = shared),
            NoteDocBlock("au", NoteDocBlockType.AUDIO, audio = NoteDocAudio(clip, 1000)),
            NoteDocBlock("gone", NoteDocBlockType.IMAGE, imagePath = UUID.randomUUID().toString()),
        ))
        return Triple(repo, actions, mapOf("shared" to shared, "cardOnly" to cardOnly))
    }

    private fun card(owner: String, vararg back: NoteDocBlock) = SavedFlashcard("c-${UUID.randomUUID()}", owner, "Note",
        NoteDocBlock("c", text = "Answer"), 0L, 0, backBlocks = back.toList())

    @Test fun eachKindIsCountedOnceUnderItsOwnHeading() = runTest {
        val (repo, _, names) = fixture()
        val cards = listOf(card("project-p",
            NoteDocBlock("s", NoteDocBlockType.IMAGE, imagePath = names.getValue("shared")),
            NoteDocBlock("o", NoteDocBlockType.IMAGE, imagePath = names.getValue("cardOnly"))))
        val b = projectStorageBreakdown(context, repo.projectSnapshot("p"), cards)
        assertEquals(1400L, b.row(K.NOTE_PICTURES).bytes); assertEquals(2, b.row(K.NOTE_PICTURES).items)
        assertEquals(800L, b.row(K.NOTE_RECORDINGS).bytes)
        assertEquals(250L, b.row(K.FLASHCARD_PICTURES).bytes)                   // the shared picture is not counted twice
        assertEquals(300L, b.row(K.IMAGES).bytes)
        assertEquals(570L, b.row(K.PDFS).bytes); assertEquals(2, b.row(K.PDFS).items)   // with its sidecar
        assertEquals(0L, b.row(K.FILES).bytes)                                   // the other project's file is excluded
        assertEquals(1, b.missing)
        assertEquals(1400L + 800 + 250 + 300 + 570, b.media)
        assertEquals(7, b.files)
        assertEquals(FlashcardStore.encodedBytes(cards), b.row(K.FLASHCARDS).bytes)
        assertTrue(b.row(K.NOTES).bytes > 0 && b.row(K.STRUCTURE).bytes > 0 && b.row(K.CAPTURES).bytes > 0)
        assertEquals(b.media + b.data, b.total)
    }

    @Test fun addingContentGrowsExactlyTheMatchingRow() = runTest {
        val (repo, actions, _) = fixture()
        val before = projectStorageBreakdown(context, repo.projectSnapshot("p"), emptyList())
        repo.transaction { saveTask(Task("t2", "Another task with a longer title", projectId = "p", createdAt = at, updatedAt = at)) }
        val withTask = projectStorageBreakdown(context, repo.projectSnapshot("p"), emptyList())
        assertTrue(withTask.row(K.STRUCTURE).bytes > before.row(K.STRUCTURE).bytes)
        assertEquals(before.row(K.NOTES).bytes, withTask.row(K.NOTES).bytes)
        assertEquals(before.media, withTask.media)
        val doc = actions.loadNoteDoc("project-p")!!
        val picture = noteImage(2048)
        actions.saveNoteDoc("project-p", doc.title, doc.blocks + NoteDocBlock("new", NoteDocBlockType.IMAGE, imagePath = picture))
        val withPicture = projectStorageBreakdown(context, repo.projectSnapshot("p"), emptyList())
        assertEquals(withTask.row(K.NOTE_PICTURES).bytes + 2048, withPicture.row(K.NOTE_PICTURES).bytes)
    }

    @Test fun flashcardBytesMatchWhatTheStoreWrites() = runTest {
        val store = File(context.filesDir, "flashcards-v1.json"); store.delete()
        FlashcardStore.add(context, "project-p", "Note", NoteDocBlock("a", text = "First answer"))
        FlashcardStore.add(context, "project-p", "Note", NoteDocBlock("b", text = "Second answer"))
        val cards = FlashcardStore.list(context, "project-p")
        // {"v":1,"cards":[ row , row ]} — the envelope and separator are the only other bytes.
        assertEquals(store.length(), FlashcardStore.encodedBytes(cards) + "{\"v\":1,\"cards\":[]}".length + 1)
        store.delete()
    }

    @Test fun theScreenShowsTheSplitAndUpdatesLiveWhenSomethingIsAdded() {
        val (repo, _, _) = runBlocking { fixture() }
        val project = runBlocking { repo.getProject("p")!! }
        compose.setContent { androidx.compose.material3.MaterialTheme { ProjectStorageSettings(project, repo) } }
        compose.onNodeWithText("File and media storage").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("project_storage_total")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("project_storage_row_${K.PDFS}").assertIsDisplayed()
        compose.onNodeWithTag("project_storage_missing").assertExists()
        compose.onNodeWithTag("project_storage_settings").captureRoboImage("build/outputs/project-storage-split.png")
        fun tasksShown() = compose.onAllNodes(hasTestTag("project_storage_row_${K.STRUCTURE}") and
            androidx.compose.ui.test.hasAnyDescendant(hasText("3 items", substring = true)), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
        val tools = ProjectStorageTools(context, repo)
        val structureBefore = runBlocking { tools.breakdown(repo.projectSnapshot("p")).row(K.STRUCTURE).items }
        assertEquals(2, structureBefore)                                         // the project and its one task
        runBlocking { repo.transaction { saveTask(Task("live", "Added while open", projectId = "p", createdAt = at, updatedAt = at)) } }
        compose.waitUntil(10_000) { tasksShown() }
    }
}
