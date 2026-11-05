package com.virlin.app.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.project.*
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.domain.model.*
import com.virlin.app.domain.project.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProjectStorageToolsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val at = Instant.EPOCH
    private suspend fun fixture(): InMemoryWorkStreamRepository {
        val repo = InMemoryWorkStreamRepository()
        repo.transaction {
            saveProject(Project("p", "Export Ω", createdAt = at, updatedAt = at))
            saveProject(Project("other", "Private unrelated", createdAt = at, updatedAt = at))
            saveTask(Task("t", "Nested text", projectId = "p", createdAt = at, updatedAt = at))
            saveCapture(CaptureItem("cap", CaptureType.FILE, "", title = "File", projectId = "p", createdAt = at, updatedAt = at))
            saveAttachmentDocument(AttachmentDocument("att", "cap", "example.txt", "text/plain", 5, "att/example.txt", AttachmentKind.TEXT, at, at))
        }
        val file = AttachmentFileStore.resolve(context, "att/example.txt")
        file.parentFile!!.mkdirs(); file.writeText("hello")
        return repo
    }
    @Test fun usageAndArchiveAreScopedAndIncludeOriginalMedia() = runTest {
        val repo = fixture(); val source = repo.projectSnapshot("p"); val tools = ProjectStorageTools(context, repo)
        val usage = tools.usage(source)
        assertEquals(5L, usage.bytes); assertEquals(1, usage.files); assertEquals(0, usage.missing)
        val file = tools.export(source, ProjectExportFormat.BACKUP)
        ZipFile(file).use { zip ->
            val json = JSONObject(zip.getInputStream(zip.getEntry("project.json")).reader().readText())
            assertEquals(1, json.getInt("version")); assertEquals("p", json.getJSONObject("project").getString("id"))
            assertFalse(json.toString().contains("Private unrelated"))
            val media = json.getJSONArray("media").getJSONObject(0)
            assertEquals(64, media.getString("sha256").length)
            assertEquals("hello", zip.getInputStream(zip.getEntry(media.getString("entry"))).reader().readText())
            assertNotNull(zip.getEntry("project.md")); assertNotNull(zip.getEntry("README.txt"))
        }
        assertEquals(source, repo.projectSnapshot("p"))
    }
    @Test fun fullCopyHasIndependentFileAndStructureDoesNotCopyAttachments() = runTest {
        val repo = fixture(); val source = repo.projectSnapshot("p"); val tools = ProjectStorageTools(context, repo)
        val copy = tools.duplicate(source, "Full", ProjectCopyMode.FULL)
        val attachment = repo.projectSnapshot(copy.id).attachments.single()
        assertNotEquals("att/example.txt", attachment.relativePath)
        val newFile = AttachmentFileStore.resolve(context, attachment.relativePath)
        assertEquals("hello", newFile.readText()); newFile.writeText("changed")
        assertEquals("hello", AttachmentFileStore.resolve(context, "att/example.txt").readText())
        val structure = tools.duplicate(source, "Structure", ProjectCopyMode.STRUCTURE)
        assertTrue(repo.projectSnapshot(structure.id).attachments.isEmpty())
    }
    @Test fun missingMediaFailsBackupAndFullCopyWithoutChangingProject() = runTest {
        val repo = fixture(); val source = repo.projectSnapshot("p"); val tools = ProjectStorageTools(context, repo)
        AttachmentFileStore.resolve(context, "att/example.txt").delete()
        assertEquals(1, tools.usage(source).missing)
        try { tools.export(source, ProjectExportFormat.BACKUP); fail("Must fail") } catch (_: IllegalArgumentException) { }
        try { tools.duplicate(source, "Full", ProjectCopyMode.FULL); fail("Must fail") } catch (_: IllegalArgumentException) { }
        assertEquals(2, repo.projects.value.size); assertEquals(source, repo.projectSnapshot("p"))
    }
    @Test fun metadataFailureCleansOnlyNewCopyFiles() = runTest {
        val repo = fixture()
        repo.transaction { savePageBlock(TaskPageBlock("invalid", "t", "unsupported", "missing", 0, at, at)) }
        val source = repo.projectSnapshot("p"); val tools = ProjectStorageTools(context, repo)
        fun files() = AttachmentFileStore.attachmentsRoot(context).walkTopDown().filter { it.isFile }.map { it.canonicalPath }.toSet()
        val before = files()
        try { tools.duplicate(source, "Fails", ProjectCopyMode.FULL); fail("Must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(before, files()); assertEquals(2, repo.projects.value.size)
        assertEquals("hello", AttachmentFileStore.resolve(context, "att/example.txt").readText())
    }
    @Test fun pathsCannotEscapeManagedStorageAndTextExportsAreValid() = runTest {
        val repo = fixture(); val source = repo.projectSnapshot("p"); val tools = ProjectStorageTools(context, repo)
        try { tools.resolve("attachments", "../../database.db"); fail("Must reject") } catch (_: IllegalArgumentException) { }
        val markdown = tools.export(source, ProjectExportFormat.MARKDOWN).readText()
        assertTrue(markdown.contains("Export Ω")); assertTrue(markdown.contains("Nested text"))
        // Android PdfDocument's native implementation is verified by the on-device test.
    }
}
