package com.virlin.app.domain

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.data.flashcard.FlashcardDeckStyle
import com.virlin.app.data.flashcard.FlashcardDeckStyleStore
import com.virlin.app.data.flashcard.FlashcardDesign
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.data.noteaudio.NoteAudioStore
import com.virlin.app.data.noteimage.NoteImageStore
import com.virlin.app.data.project.ProjectStorageTools
import com.virlin.app.data.project.VirlinProjectFile
import com.virlin.app.data.project.VirlinTiming
import com.virlin.app.data.projecticon.ProjectIconStore
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
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** A `.virlin` file holds the whole project, every media file byte for byte, and proves it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1400dp-xhdpi")
class VirlinProjectFileTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val at = Instant.parse("2026-11-01T09:00:00Z")

    private fun file(f: File, bytes: Int, seed: Int): ByteArray {
        f.parentFile!!.mkdirs(); val data = Random(seed).nextBytes(bytes); f.writeBytes(data); return data
    }

    private class World(val repo: InMemoryWorkStreamRepository, val originals: Map<String, ByteArray>)

    private suspend fun world(): World {
        val repo = InMemoryWorkStreamRepository()
        val originals = mutableMapOf<String, ByteArray>()
        repo.transaction {
            saveProject(Project("p", "Mine / Ω", iconPath = "p/icon.png", createdAt = at, updatedAt = at))
            saveProject(Project("other", "Not mine", createdAt = at, updatedAt = at))
            saveStream(WorkStream("s", "Stream", projectId = "p", state = WorkStreamState.PROCESSING,
                processingStartedAt = at, externalTrackedSeconds = 5400, createdAt = at, updatedAt = at))
            saveTask(Task("t", "A task", projectId = "p", workStreamId = "s", createdAt = at, updatedAt = at))
            saveFocusSession(FocusSession("f1", "s", null, at, at.plusSeconds(1500), taskId = "t"))
            saveCapture(CaptureItem("img", CaptureType.FILE, "", title = "Photo", projectId = "p", createdAt = at, updatedAt = at))
            saveAttachmentDocument(AttachmentDocument("a1", "img", "photo.jpg", "image/jpeg", 3000, "a1/photo.jpg", AttachmentKind.IMAGE, at, at))
            saveCapture(CaptureItem("pdf", CaptureType.FILE, "", title = "Doc", projectId = "p", createdAt = at, updatedAt = at))
            saveAttachmentDocument(AttachmentDocument("a2", "pdf", "doc.pdf", "application/pdf", 5000, "a2/doc.pdf", AttachmentKind.PDF, at, at))
            saveCapture(CaptureItem("x", CaptureType.FILE, "", title = "Other", projectId = "other", createdAt = at, updatedAt = at))
            saveAttachmentDocument(AttachmentDocument("a3", "x", "secret.txt", "text/plain", 9, "a3/secret.txt", AttachmentKind.TEXT, at, at))
        }
        originals["attachments/a1/photo.jpg"] = file(AttachmentFileStore.resolve(context, "a1/photo.jpg"), 3000, 1)
        originals["attachments/a2/doc.pdf"] = file(AttachmentFileStore.resolve(context, "a2/doc.pdf"), 5000, 2)
        originals["attachments/a2/edited.json"] = file(AttachmentFileStore.resolve(context, "a2/edited.json"), 700, 3)
        AttachmentFileStore.resolve(context, "a3/secret.txt").apply { parentFile!!.mkdirs(); writeText("other project") }
        originals["icons/p/icon.png"] = file(File(ProjectIconStore.root(context), "p/icon.png"), 640, 4)
        val picture = UUID.randomUUID().toString(); val cardPicture = UUID.randomUUID().toString(); val clip = "${UUID.randomUUID()}.m4a"
        originals["note-images/$picture"] = file(NoteImageStore.resolve(context, picture)!!, 4096, 5)
        originals["note-images/$cardPicture"] = file(NoteImageStore.resolve(context, cardPicture)!!, 1024, 6)
        originals["note-audio/$clip"] = file(NoteAudioStore.resolve(context, clip)!!, 2048, 7)
        val actions = DefaultVirlinActions(repo, FakeClock(at), SequentialIdProvider())
        actions.saveNoteDoc("project-p", "Note", listOf(
            NoteDocBlock("b1", text = "Hello **world**"),
            NoteDocBlock("i1", NoteDocBlockType.IMAGE, imagePath = picture),
            NoteDocBlock("au", NoteDocBlockType.AUDIO, audio = NoteDocAudio(clip, 1000)),
        ))
        File(context.filesDir, "flashcards-v1.json").delete()
        FlashcardStore.add(context, "project-p", "Note", NoteDocBlock("c", text = "Answer"),
            backBlocks = listOf(NoteDocBlock("ci", NoteDocBlockType.IMAGE, imagePath = cardPicture)))
        FlashcardStore.add(context, "project-other", "Other", NoteDocBlock("c2", text = "Not mine"))
        FlashcardDeckStyleStore.save(context, FlashcardDeckStyleStore.deckKey("project", "p"), FlashcardDeckStyle(design = FlashcardDesign.KRAFT))
        context.getSharedPreferences("project_appearance", 0).edit().putString("p", "palette-p").putString("other", "palette-other").commit()
        context.getSharedPreferences("hierarchy_timer_display", 0).edit().putInt("project:p", 9).commit()
        return World(repo, originals)
    }

    @Test fun everyMediaFileComesOutByteForByteWithTheWholeProject() = runTest {
        val w = world()
        val before = w.repo.projectSnapshot("p")
        val export = ProjectStorageTools(context, w.repo).exportVirlin(before, VirlinTiming.KEEP)
        ZipFile(export.file).use { zip ->
            val first = zip.entries().nextElement()
            assertEquals(VirlinProjectFile.MIMETYPE_ENTRY, first.name); assertEquals(ZipEntry.STORED, first.method)
            assertEquals(VirlinProjectFile.MIME_TYPE, zip.getInputStream(first).readBytes().toString(Charsets.US_ASCII))
            w.originals.forEach { (path, bytes) ->
                val entry = zip.getEntry("media/$path") ?: error("missing $path")
                assertArrayEquals("exact bytes of $path", bytes, zip.getInputStream(entry).readBytes())
            }
            assertEquals(ZipEntry.STORED, zip.getEntry("media/attachments/a1/photo.jpg").method)   // already compressed: kept as is
            assertEquals(null, zip.getEntry("media/attachments/a3/secret.txt"))                     // another project's file
            val data = JSONObject(zip.getInputStream(zip.getEntry(VirlinProjectFile.DATA_ENTRY)).reader().readText())
            assertFalse(data.toString().contains("Not mine")); assertFalse(data.toString().contains("palette-other"))
            assertEquals(5400, data.getJSONArray("workstreams").getJSONObject(0).getLong("externalTrackedSeconds"))
            assertEquals(1, data.getJSONObject("history").getJSONArray("focusSessions").length())
            assertEquals(1, data.getJSONArray("flashcards").length())
            assertTrue(data.getJSONArray("richNotes").getJSONObject(0).toString().contains("world"))
            val settings = data.getJSONObject("settings")
            assertEquals("palette-p", settings.getJSONObject("projectAppearance").getJSONObject("p").getString("value"))
            assertEquals(9, settings.getJSONObject("timerDisplay").getJSONObject("project:p").getInt("value"))
            assertEquals("KRAFT", settings.getJSONObject("flashcardDecks").getJSONObject("project:p").getString("design"))
        }
        val summary = VirlinProjectFile.verify(export.file)
        assertEquals(w.originals.size, summary.mediaFiles)
        assertEquals(w.originals.values.sumOf { it.size.toLong() }, summary.mediaBytes)
        assertEquals("Mine / Ω", summary.projectTitle); assertEquals(VirlinTiming.KEEP, summary.timing)
        assertTrue(summary.missing.isEmpty())
        assertEquals(export.sha256, export.file.inputStream().use(VirlinProjectFile::sha256))
        assertEquals(before, w.repo.projectSnapshot("p"))                                           // exporting changes nothing
        export.file.delete()
    }

    @Test fun startingFromZeroClearsOnlyTimeSpent() = runTest {
        val w = world()
        val export = ProjectStorageTools(context, w.repo).exportVirlin(w.repo.projectSnapshot("p"), VirlinTiming.RESET)
        ZipFile(export.file).use { zip ->
            val data = JSONObject(zip.getInputStream(zip.getEntry(VirlinProjectFile.DATA_ENTRY)).reader().readText())
            val stream = data.getJSONArray("workstreams").getJSONObject(0)
            assertEquals(0, stream.getLong("externalTrackedSeconds")); assertTrue(stream.isNull("processingStartedAt"))
            assertEquals("PROCESSING", stream.getString("state"))
            assertEquals(0, data.getJSONObject("history").getJSONArray("focusSessions").length())
            assertEquals(1, data.getJSONArray("tasks").length()); assertEquals(1, data.getJSONArray("flashcards").length())
            assertEquals(w.originals.size, JSONObject(zip.getInputStream(zip.getEntry(VirlinProjectFile.HEADER_ENTRY)).reader().readText())
                .getJSONObject("media").getInt("files"))
        }
        assertEquals(VirlinTiming.RESET, VirlinProjectFile.verify(export.file).timing)
        assertEquals(5400, w.repo.getStream("s")!!.externalTrackedSeconds)                         // the project keeps its time
        assertEquals(1, w.repo.allFocusSessions().size)
        export.file.delete()
    }

    @Test fun aChangedByteIsCaught() = runTest {
        val w = world()
        val export = ProjectStorageTools(context, w.repo).exportVirlin(w.repo.projectSnapshot("p"), VirlinTiming.KEEP)
        val tampered = File(export.file.parentFile, "tampered.virlin")
        ZipFile(export.file).use { zip -> ZipOutputStream(tampered.outputStream()).use { out ->
            zip.entries().toList().forEach { entry ->
                var bytes = zip.getInputStream(entry).readBytes()
                if (entry.name == "media/attachments/a2/doc.pdf") bytes = bytes.copyOf().also { it[10] = (it[10] + 1).toByte() }
                out.putNextEntry(ZipEntry(entry.name).apply {
                    if (entry.method == ZipEntry.STORED) { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                        crc = java.util.zip.CRC32().apply { update(bytes) }.value }
                })
                out.write(bytes); out.closeEntry()
            }
        } }
        try { VirlinProjectFile.verify(tampered); fail("A damaged file must be rejected") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("doc.pdf")) }
        tampered.delete(); export.file.delete()
    }

    @Test fun pictureWithoutExtensionIsRecognisedAndKeptUncompressed() = runTest {
        val w = world()
        val name = UUID.randomUUID().toString()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + Random(9).nextBytes(5000)
        NoteImageStore.resolve(context, name)!!.writeBytes(jpeg)
        val actions = DefaultVirlinActions(w.repo, FakeClock(at), SequentialIdProvider())
        val doc = actions.loadNoteDoc("project-p")!!
        actions.saveNoteDoc("project-p", doc.title, doc.blocks + NoteDocBlock("j", NoteDocBlockType.IMAGE, imagePath = name))
        val export = ProjectStorageTools(context, w.repo).exportVirlin(w.repo.projectSnapshot("p"), VirlinTiming.KEEP)
        ZipFile(export.file).use { zip ->
            val entry = zip.getEntry("media/note-images/$name")
            assertEquals(ZipEntry.STORED, entry.method)
            assertArrayEquals(jpeg, zip.getInputStream(entry).readBytes())
            val items = JSONObject(zip.getInputStream(zip.getEntry(VirlinProjectFile.HEADER_ENTRY)).reader().readText())
                .getJSONObject("media").getJSONArray("items")
            val item = (0 until items.length()).map { items.getJSONObject(it) }.single { it.getString("path") == name }
            assertEquals("image/jpeg", item.getString("kind"))
        }
        export.file.delete()
    }

    @Test fun fileNameIsSafeAndEndsInVirlin() {
        assertEquals("Mine _ Ω.virlin", VirlinProjectFile.fileName("Mine / Ω"))
        assertEquals("Project.virlin", VirlinProjectFile.fileName("  "))
    }

    @Test fun theExportScreenOffersOnlyTheVirlinFile() {
        val w = runBlocking { world() }
        val project = runBlocking { w.repo.getProject("p")!! }
        compose.setContent { androidx.compose.material3.MaterialTheme { ProjectStorageSettings(project, w.repo) } }
        compose.onNodeWithText("Export project").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("7 original files", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("export_virlin").assertIsDisplayed()
        compose.onNodeWithTag("virlin_timing_keep").assertIsDisplayed()
        compose.onNodeWithTag("virlin_timing_reset").performClick()
        listOf("PDF", "Markdown", "Backup archive (.zip)").forEach {
            assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText(it)).fetchSemanticsNodes().isEmpty())
        }
        compose.onNodeWithTag("project_storage_settings").captureRoboImage("build/outputs/project-virlin-export.png")
    }
}
