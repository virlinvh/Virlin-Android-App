package com.virlin.app.domain

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.data.flashcard.FlashcardDeckStyleStore
import com.virlin.app.data.flashcard.FlashcardDeckStyle
import com.virlin.app.data.flashcard.FlashcardDesign
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.data.mappreview.MapPreviewImageStore
import com.virlin.app.data.noteimage.NoteImageStore
import com.virlin.app.data.project.ProjectStorageTools
import com.virlin.app.data.project.VirlinProjectImporter
import com.virlin.app.data.project.VirlinTiming
import com.virlin.app.data.projecticon.ProjectIconStore
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.mappreview.MapNodePreview
import com.virlin.app.domain.mappreview.MapPreviewImage
import com.virlin.app.domain.model.*
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.project.projectSnapshot
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.repository.WorkStreamWriter
import com.virlin.app.ui.hierarchy.BulkCreationDialog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** A `.virlin` file comes back as a new project, exactly as it was exported. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h900dp-xhdpi")
class VirlinProjectImportTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    // Exports stamp the real clock: the fixture lies before it and the import after it.
    private val at = Instant.parse("2025-03-01T09:00:00Z")
    private val later = Instant.parse("2031-01-01T10:00:00Z")

    private fun file(f: File, bytes: Int, seed: Int): ByteArray {
        f.parentFile!!.mkdirs(); val data = Random(seed).nextBytes(bytes); f.writeBytes(data); return data
    }

    private class World(val repo: InMemoryWorkStreamRepository, val picture: String, val bytes: Map<String, ByteArray>)

    private suspend fun world(): World {
        File(context.filesDir, "flashcards-v1.json").delete()
        File(context.filesDir, "flashcard-deck-styles-v1.json").delete()
        val repo = InMemoryWorkStreamRepository()
        repo.transaction {
            saveProject(Project("p", "Launch", description = "The launch", iconPath = "p/icon.png", createdAt = at, updatedAt = at))
            saveStream(WorkStream("s", "Build", projectId = "p", state = WorkStreamState.PROCESSING,
                processingStartedAt = at, externalTrackedSeconds = 5400, checkAt = at.plusSeconds(7200), cycleCount = 1,
                currentCycleId = "c1", createdAt = at, updatedAt = at))
            saveStream(WorkStream("h", "Write", projectId = "p", state = WorkStreamState.FOCUS, activeTaskId = "t",
                createdAt = at, updatedAt = at))
            saveTask(Task("t", "Draft", projectId = "p", workStreamId = "h", notes = "plain notes", createdAt = at, updatedAt = at))
            saveTask(Task("t2", "Child", projectId = "p", workStreamId = "h", parentTaskId = "t", status = TaskStatus.DONE,
                createdAt = at, updatedAt = at, completedAt = at))
            saveCycle(Cycle("c1", "s", 1, at, handedOffAt = at))
            saveFocusSession(FocusSession("f1", "h", null, at, at.plusSeconds(1500), taskId = "t"))
            saveFocusSession(FocusSession("f1/f2", "h", null, at.plusSeconds(1600), null, taskId = "t"))
            saveSnapshot(ContextSnapshot("sn1", "s", "c1", at, WorkStreamState.PROCESSING, "Sent build", "CI", "Check logs", at.plusSeconds(7200)))
            appendEvent(WorkStreamEvent("e1", "s", EventType.values().first(), at, "c1", WorkStreamState.FOCUS, WorkStreamState.PROCESSING, "handed off"))
            saveStep(TaskStep("st1", "t", "Outline", done = true, order = 0, createdAt = at, updatedAt = at))
            saveTag(ProjectTag("tag", "p", "urgent", at, at))
            saveLink(TagLink("tag", TagTargetType.TASK, "t", at))
            saveCapture(CaptureItem("cap", CaptureType.FILE, "", title = "Spec", projectId = "p", taskId = "t", createdAt = at, updatedAt = at))
            saveAttachmentDocument(AttachmentDocument("a1", "cap", "spec.pdf", "application/pdf", 5000, "a1/spec.pdf", AttachmentKind.PDF, at, at))
            saveCapture(CaptureItem("pr", CaptureType.PROMPT, "Prompt", projectId = "p", createdAt = at, updatedAt = at))
            savePromptDocument(PromptDocument("pd", "pr", "Prompt", blocks = emptyList(), sourceText = "Do it",
                mode = PromptContentMode.CODE, codeText = "fun main() = Unit", createdAt = at, updatedAt = at))
            savePageBlock(TaskPageBlock("pb1", "t", TaskPageTypeKeys.NOTE, TaskPageTypeKeys.extraNoteOwner("t", "n1"), 0, at, at))
            savePageBlock(TaskPageBlock("pb2", "t", TaskPageTypeKeys.capture(CaptureType.FILE), "cap", 1, at, at))
            saveMapNodePreview(MapNodePreview("t", images = listOf(MapPreviewImage(id = "img", relativePath = "t/pic.png",
                width = 10, height = 10, sizeBytes = 900)), expanded = true, createdAt = at, updatedAt = at))
        }
        val bytes = mutableMapOf<String, ByteArray>()
        bytes["pdf"] = file(AttachmentFileStore.resolve(context, "a1/spec.pdf"), 5000, 1)
        bytes["sidecar"] = file(AttachmentFileStore.resolve(context, "a1/edited.json"), 300, 2)
        bytes["icon"] = file(File(ProjectIconStore.root(context), "p/icon.png"), 640, 3)
        bytes["preview"] = file(MapPreviewImageStore.resolve(context, "t/pic.png"), 900, 4)
        val picture = UUID.randomUUID().toString()
        bytes["picture"] = file(NoteImageStore.resolve(context, picture)!!, 2048, 5)
        val actions = DefaultVirlinActions(repo, FakeClock(at), SequentialIdProvider())
        actions.saveNoteDoc("project-p", "Plan", listOf(NoteDocBlock("b1", text = "Hello **world**"),
            NoteDocBlock("i1", NoteDocBlockType.IMAGE, imagePath = picture)))
        actions.saveNoteDoc(TaskPageTypeKeys.extraNoteOwner("t", "n1"), "Extra", listOf(NoteDocBlock("x", text = "Second note")))
        FlashcardStore.add(context, "project-p", "Plan", NoteDocBlock("c", text = "Answer"),
            backBlocks = listOf(NoteDocBlock("ci", NoteDocBlockType.IMAGE, imagePath = picture)))
        FlashcardDeckStyleStore.save(context, FlashcardDeckStyleStore.deckKey("project", "p"), FlashcardDeckStyle(design = FlashcardDesign.KRAFT))
        context.getSharedPreferences("project_appearance", 0).edit().putString("p", "palette-p").commit()
        context.getSharedPreferences("hierarchy_timer_display", 0).edit().putInt("project:p", 9).commit()
        context.getSharedPreferences("note_page_setup", 0).edit().putString("project-p", "setup").putBoolean("project-p#on", true).commit()
        return World(repo, picture, bytes)
    }

    private suspend fun export(w: World, timing: VirlinTiming = VirlinTiming.KEEP) =
        ProjectStorageTools(context, w.repo).exportVirlin(w.repo.projectSnapshot("p"), timing).file

    private fun importer(repo: WorkStreamRepository, now: Instant = later) = VirlinProjectImporter(context, repo, clock = { now })

    private fun attachmentFiles() = AttachmentFileStore.attachmentsRoot(context).walkTopDown().filter { it.isFile }.count()

    @Test fun theWholeProjectComesBackAsANewProject() = runTest {
        val w = world()
        val original = w.repo.projectSnapshot("p")
        val file = export(w)
        val preview = importer(w.repo).inspect(file)
        assertEquals("Launch", preview.summary.projectTitle)
        assertEquals(2, preview.workstreams); assertEquals(2, preview.tasks); assertEquals(1, preview.leavesFocus)
        assertEquals(2, preview.focusSessions); assertEquals(1, preview.flashcards)
        assertEquals(1, w.repo.projects.value.size)                                                       // checking adds nothing

        val result = importer(w.repo).import(file, "Launch again")
        assertTrue(result.warnings.isEmpty())
        val copy = w.repo.projectSnapshot(result.project.id)
        assertNotEquals("p", copy.project.id)
        assertEquals(original, w.repo.projectSnapshot("p"))                                                 // the original is untouched
        assertEquals("Launch again", copy.project.title); assertEquals("The launch", copy.project.description)
        assertEquals(original.project.createdAt, copy.project.createdAt)

        val build = copy.streams.single { it.title == "Build" }
        assertEquals(WorkStreamState.PROCESSING, build.state); assertEquals(5400, build.externalTrackedSeconds)
        val shift = java.time.Duration.between(exportedAtOf(file), later)
        assertEquals(at.plus(shift), build.processingStartedAt)                                           // the clock resumes where it was
        assertEquals(at.plusSeconds(7200).plus(shift), build.checkAt)
        assertEquals(WorkStreamState.READY, copy.streams.single { it.title == "Write" }.state)            // never steals Focus
        val draft = copy.tasks.single { it.title == "Draft" }
        assertEquals(draft.id, copy.streams.single { it.title == "Write" }.activeTaskId)
        assertEquals(draft.id, copy.tasks.single { it.title == "Child" }.parentTaskId)
        assertEquals("plain notes", draft.notes)
        assertEquals(listOf("Outline"), copy.steps.map { it.text }); assertTrue(copy.steps.single().done)
        assertEquals(listOf("urgent"), copy.tags.map { it.name }); assertEquals(draft.id, copy.links.single().targetId)

        val prompt = copy.prompts.single()
        assertEquals("fun main() = Unit", prompt.codeText); assertEquals(PromptContentMode.CODE, prompt.mode)
        val attachment = copy.attachments.single()
        assertArrayEquals(w.bytes["pdf"], AttachmentFileStore.resolve(context, attachment.relativePath).readBytes())
        assertTrue(attachment.relativePath.startsWith(attachment.id + "/"))
        assertArrayEquals(w.bytes["sidecar"], AttachmentFileStore.resolve(context, "${attachment.id}/edited.json").readBytes())
        assertArrayEquals(w.bytes["icon"], File(ProjectIconStore.root(context), copy.project.iconPath!!).readBytes())
        val preview2 = copy.previews.single()
        assertEquals(draft.id, preview2.ownerId); assertTrue(preview2.expanded)
        assertArrayEquals(w.bytes["preview"], MapPreviewImageStore.resolve(context, preview2.images.single().relativePath).readBytes())

        val plan = copy.richNotes.single { it.ownerKey == "project-${copy.project.id}" }
        assertEquals("Hello **world**", plan.blocks.first().text)
        assertEquals(w.picture, plan.blocks[1].imagePath)                                                   // identical picture is shared
        val extraOwner = TaskPageTypeKeys.extraNoteOwner(draft.id, "n1")
        assertEquals("Second note", copy.richNotes.single { it.ownerKey == extraOwner }.blocks.single().text)
        assertEquals(setOf(extraOwner, copy.captures.single { it.type == CaptureType.FILE }.id),
            copy.pageBlocks.map { it.contentId }.toSet())

        val sessions = w.repo.allFocusSessions().filter { it.workStreamId == copy.streams.single { s -> s.title == "Write" }.id }
        assertEquals(2, sessions.size)
        val continued = sessions.single { '/' in it.id }
        assertEquals(sessions.single { '/' !in it.id }.id, continued.periodRootId)
        assertEquals(exportedAtOf(file), continued.endedAt)                                                // open session ends at export
        assertEquals(1, w.repo.getEvents(build.id).size)

        val cards = FlashcardStore.list(context, "project-${copy.project.id}")
        assertEquals("Answer", cards.single().content.text)
        assertEquals(w.picture, cards.single().backBlocks!!.single().imagePath)
        assertEquals(1, FlashcardStore.list(context, "project-p").size)
        assertEquals("palette-p", context.getSharedPreferences("project_appearance", 0).getString(copy.project.id, null))
        assertEquals(9, context.getSharedPreferences("hierarchy_timer_display", 0).getInt("project:${copy.project.id}", 0))
        assertTrue(context.getSharedPreferences("note_page_setup", 0).getBoolean("project-${copy.project.id}#on", false))
        assertEquals(FlashcardDesign.KRAFT, FlashcardDeckStyleStore.get(context, "project:${copy.project.id}").design)

        val again = importer(w.repo).import(file, "Launch third")                                          // twice → two projects
        assertNotEquals(copy.project.id, again.project.id); assertEquals(3, w.repo.projects.value.size)
        file.delete()
    }

    @Test fun aDamagedFileIsRefusedAndAddsNothing() = runTest {
        val w = world()
        val file = export(w)
        val tampered = File(file.parentFile, "damaged.virlin")
        ZipFile(file).use { zip -> ZipOutputStream(tampered.outputStream()).use { out ->
            zip.entries().toList().forEach { entry ->
                var bytes = zip.getInputStream(entry).readBytes()
                if (entry.name == "media/attachments/a1/spec.pdf") bytes = bytes.copyOf().also { it[5] = (it[5] + 1).toByte() }
                out.putNextEntry(ZipEntry(entry.name).apply {
                    if (entry.method == ZipEntry.STORED) { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = java.util.zip.CRC32().apply { update(bytes) }.value }
                })
                out.write(bytes); out.closeEntry()
            }
        } }
        val files = attachmentFiles()
        try { importer(w.repo).inspect(tampered); fail("damaged") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("spec.pdf")) }
        try { importer(w.repo).import(tampered, "X"); fail("damaged") } catch (e: IllegalArgumentException) { }
        val notAZip = File(file.parentFile, "plain.virlin").apply { writeText("hello") }
        try { importer(w.repo).inspect(notAZip); fail("not a file") } catch (e: IllegalArgumentException) {
            assertEquals("Not a Virlin project file.", e.message)
        }
        assertEquals(1, w.repo.projects.value.size); assertEquals(files, attachmentFiles())
        listOf(file, tampered, notAZip).forEach { it.delete() }
    }

    @Test fun aFailedCommitTakesBackEveryCopiedFileAndCard() = runTest {
        val w = world()
        val file = export(w)
        val failing = object : WorkStreamRepository by w.repo {
            override suspend fun <T> transaction(block: suspend WorkStreamWriter.() -> T): T = w.repo.transaction {
                val writer = this
                block(object : WorkStreamWriter by writer { override suspend fun saveTag(tag: ProjectTag) = error("disk full") })
            }
        }
        val files = attachmentFiles(); val cards = FlashcardStore.list(context, setOf("project-p")).size
        val previews = MapPreviewImageStore.root(context).walkTopDown().filter { it.isFile }.count()
        try { importer(failing).import(file, "Broken"); fail("must fail") } catch (e: IllegalStateException) { assertEquals("disk full", e.message) }
        assertEquals(1, w.repo.projects.value.size)
        assertEquals(files, attachmentFiles())
        assertEquals(previews, MapPreviewImageStore.root(context).walkTopDown().filter { it.isFile }.count())
        assertEquals(cards, FlashcardStore.list(context, setOf("project-p")).size)
        assertEquals(1, File(context.filesDir, "flashcards-v1.json").readText().split("\"ownerKey\"").size - 1)
        file.delete()
    }

    @Test fun aDifferentPictureWithTheSameNameIsKeptSeparately() = runTest {
        val w = world()
        val file = export(w)
        val local = NoteImageStore.resolve(context, w.picture)!!
        val changed = Random(99).nextBytes(2048).also(local::writeBytes)                                  // this device's file differs
        val copy = w.repo.projectSnapshot(importer(w.repo).import(file, "Launch").project.id)
        val name = copy.richNotes.single { it.ownerKey.startsWith("project-") }.blocks[1].imagePath!!
        assertNotEquals(w.picture, name)
        assertArrayEquals(w.bytes["picture"], NoteImageStore.resolve(context, name)!!.readBytes())
        assertArrayEquals(changed, local.readBytes())                                                       // the local file is untouched
        assertEquals(name, FlashcardStore.list(context, "project-${copy.project.id}").single().backBlocks!!.single().imagePath)
        file.delete()
    }

    @Test fun aFileWithoutTimeStartsRunningWorkNow() = runTest {
        val w = world()
        val file = export(w, VirlinTiming.RESET)
        val copy = w.repo.projectSnapshot(importer(w.repo).import(file, "Fresh").project.id)
        val build = copy.streams.single { it.title == "Build" }
        assertEquals(0, build.externalTrackedSeconds); assertEquals(later, build.processingStartedAt)
        assertNull(copy.streams.single { it.title == "Write" }.processingStartedAt)
        assertTrue(w.repo.allFocusSessions().none { it.workStreamId in copy.streams.map { s -> s.id } })
        file.delete()
    }

    @Test fun theProjectDialogOffersNewAndImportOnly() {
        compose.setContent { androidx.compose.material3.MaterialTheme {
            BulkCreationDialog("New project", "Project name", null, {}, {})
        } }
        compose.onNodeWithText("New").assertIsDisplayed()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Bulk")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Import").performClick()
        compose.onNodeWithText("Import project").assertIsDisplayed()
        compose.onNodeWithTag("project_import_choose").assertIsDisplayed()
        compose.onNodeWithText("Choose file").assertIsDisplayed()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Paste JSON")).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("bulk_creation_dialog").captureRoboImage("build/outputs/project-import-dialog.png")
    }

    private fun exportedAtOf(file: File): Instant = ZipFile(file).use { zip ->
        Instant.parse(org.json.JSONObject(zip.getInputStream(zip.getEntry("virlin.json")).reader().readText()).getString("exportedAt"))
    }
}
