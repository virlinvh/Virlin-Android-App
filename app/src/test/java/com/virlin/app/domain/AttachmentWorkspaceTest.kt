package com.virlin.app.domain

import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.attachment.AttachmentKindResolver
import com.virlin.app.domain.attachment.MarkdownPreviewParser
import com.virlin.app.domain.attachment.TextFileReader
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The task-scoped Attachment workspace.
 *
 * Attachment introduces no new persistence: one imported file is one `CaptureItem(FILE)` plus one
 * `AttachmentDocument`, and the existing capture transaction registers its `capture.file` Page
 * block. These tests pin kind resolution, that ownership and Page registration behave per file,
 * and that the preview readers stay bounded and safe.
 */
class AttachmentWorkspaceTest {

    private val clock = FakeClock(Instant.parse("2026-10-01T09:00:00Z"))
    private val ids = SequentialIdProvider()

    private suspend fun seed(repo: InMemoryWorkStreamRepository): Task {
        val now = clock.now()
        return repo.transaction {
            saveProject(Project(id = "p1", title = "Files", createdAt = now, updatedAt = now))
            saveStream(
                WorkStream(
                    id = "ws1", title = "Stream", state = WorkStreamState.READY,
                    projectId = "p1", createdAt = now, updatedAt = now,
                )
            )
            val t = Task(
                id = "t1", title = "Prepare release notes", workStreamId = "ws1", projectId = "p1",
                createdAt = now, updatedAt = now,
            )
            saveTask(t); t
        }
    }

    private suspend fun importOne(
        repo: InMemoryWorkStreamRepository, taskId: String,
        name: String, mime: String, size: Long = 2_048L,
    ): ActionResult<com.virlin.app.domain.model.AttachmentDocument> {
        val actions = DefaultVirlinActions(repo, clock, ids)
        val kind = AttachmentKindResolver.resolve(mime, name)
        return actions.createAttachment(
            displayName = name, mimeType = mime, sizeBytes = size,
            relativePath = "att-$name/original", kind = kind,
            context = CaptureContext(taskId = taskId),
        )
    }

    // ---- 1-5: kind resolution -------------------------------------------------------------

    @Test fun extensionAndMimeNormalisation() {
        assertEquals(AttachmentKind.PDF, AttachmentKindResolver.resolve("application/pdf", "a.pdf"))
        assertEquals(AttachmentKind.IMAGE, AttachmentKindResolver.resolve("image/png", "a.png"))
        assertEquals(AttachmentKind.VIDEO, AttachmentKindResolver.resolve("video/mp4", "a.mp4"))
        assertEquals(AttachmentKind.AUDIO, AttachmentKindResolver.resolve("audio/mpeg", "a.mp3"))
        assertEquals(AttachmentKind.MARKDOWN, AttachmentKindResolver.resolve("text/markdown", "notes.md"))
        assertEquals(AttachmentKind.ARCHIVE, AttachmentKindResolver.resolve("application/zip", "b.zip"))
    }

    @Test fun conflictingMimeAndExtension_prefersTheStrongerSignal() {
        // A real PDF mislabelled by the picker is still a PDF by extension.
        assertEquals(AttachmentKind.PDF, AttachmentKindResolver.resolve("application/octet-stream", "report.pdf"))
        // And a PDF mime wins even with a misleading name.
        assertEquals(AttachmentKind.PDF, AttachmentKindResolver.resolve("application/pdf", "report.txt"))
    }

    @Test fun unknownTypeFallsBackToUnsupported() {
        assertEquals(AttachmentKind.UNSUPPORTED, AttachmentKindResolver.resolve("application/octet-stream", "blob.bin"))
        assertEquals(AttachmentKind.UNSUPPORTED, AttachmentKindResolver.resolve(null, "noextension"))
    }

    @Test fun uppercaseAndMultiDotNamesResolve() {
        assertEquals(AttachmentKind.ARCHIVE, AttachmentKindResolver.resolve(null, "Backup.Final.ZIP"))
        assertEquals(AttachmentKind.MARKDOWN, AttachmentKindResolver.resolve(null, "release.notes.MD"))
        assertEquals(AttachmentKind.IMAGE, AttachmentKindResolver.resolve(null, "PHOTO.JPEG"))
    }

    @Test fun archivesAreNeverTreatedAsPreviewable() {
        listOf("a.zip", "a.rar", "a.7z", "a.tar", "a.gz").forEach {
            val kind = AttachmentKindResolver.resolve(null, it)
            assertEquals(AttachmentKind.ARCHIVE, kind)
            assertEquals(AttachmentKindResolver.Preview.DETAILS_ONLY, AttachmentKindResolver.previewOf(kind))
        }
    }

    // ---- 6: routing is kind-based ----------------------------------------------------------

    @Test fun previewRoutingIsKindBased() {
        assertEquals(AttachmentKindResolver.Preview.PDF_WORKSPACE, AttachmentKindResolver.previewOf(AttachmentKind.PDF))
        assertEquals(AttachmentKindResolver.Preview.DETAILS_ONLY, AttachmentKindResolver.previewOf(AttachmentKind.UNSUPPORTED))
        listOf(
            AttachmentKind.MARKDOWN, AttachmentKind.TEXT, AttachmentKind.CSV, AttachmentKind.IMAGE,
            AttachmentKind.VIDEO, AttachmentKind.AUDIO, AttachmentKind.DOCX, AttachmentKind.XLSX, AttachmentKind.PPTX,
        ).forEach {
            assertEquals(AttachmentKindResolver.Preview.IN_APP, AttachmentKindResolver.previewOf(it))
        }
    }

    @Test fun routeIsIdBasedAndRejectsBlankTask() {
        assertEquals(
            "attachment_workspace/new/task/t1",
            com.virlin.app.ui.attachment.attachmentWorkspaceForTask("t1"),
        )
        runCatching { com.virlin.app.ui.attachment.attachmentWorkspaceForTask("") }
            .onSuccess { throw AssertionError("accepted a blank task id") }
    }

    // ---- 7-9: ownership and Page registration ----------------------------------------------

    @Test fun importedFileIsTaskOwnedAndRegistersOnePageBlock() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)

        assertTrue(importOne(repo, task.id, "notes.md", "text/markdown") is ActionResult.Success)

        val capture = repo.captures.value.single()
        assertEquals(CaptureType.FILE, capture.type)
        assertEquals(task.id, capture.taskId)
        val blocks = repo.getTaskPageBlocks(task.id)
        assertEquals(1, blocks.size)
        assertEquals(TaskPageTypeKeys.capture(CaptureType.FILE), blocks.single().typeKey)
        assertEquals(capture.id, blocks.single().contentId)
    }

    @Test fun multipleFilesCreateIndependentRecordsAndBlocks() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)

        importOne(repo, task.id, "notes.md", "text/markdown")
        importOne(repo, task.id, "demo.mp4", "video/mp4")
        importOne(repo, task.id, "bundle.zip", "application/zip")

        assertEquals(3, repo.captures.value.size)
        assertEquals(3, repo.getTaskPageBlocks(task.id).size)
        // Each block points at its own capture - no grouping, no shared contentId.
        val contentIds = repo.getTaskPageBlocks(task.id).map { it.contentId }.toSet()
        assertEquals(3, contentIds.size)
    }

    @Test fun oneTasksFilesDoNotAppearOnAnother() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val other = repo.transaction {
            val t = Task(
                id = "t2", title = "Other", workStreamId = "ws1", projectId = "p1",
                createdAt = clock.now(), updatedAt = clock.now(),
            )
            saveTask(t); t
        }
        importOne(repo, task.id, "a.txt", "text/plain")

        assertEquals(1, repo.getTaskPageBlocks(task.id).size)
        assertTrue(repo.getTaskPageBlocks(other.id).isEmpty())
    }

    @Test fun emptyFileIsRejectedAndRegistersNothing() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)

        val result = importOne(repo, task.id, "empty.txt", "text/plain", size = 0L)

        assertTrue(result is ActionResult.Rejected)
        assertTrue(repo.captures.value.isEmpty())
        assertTrue(repo.getTaskPageBlocks(task.id).isEmpty())
    }

    @Test fun legacyGlobalAttachmentStaysReadableAndCreatesNoBlock() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)

        // An attachment with no task context behaves exactly as before this feature existed.
        val legacy = actions.createAttachment(
            displayName = "old.bin", mimeType = "application/octet-stream", sizeBytes = 10L,
            relativePath = "att-old/original", kind = AttachmentKind.UNSUPPORTED,
            context = CaptureContext.None,
        )
        assertTrue(legacy is ActionResult.Success)
        val captureId = repo.captures.value.single().id
        assertNotNull(actions.getAttachmentByCaptureId(captureId))
        assertTrue(repo.getTaskPageBlocks(task.id).isEmpty())
    }

    @Test fun removingOneFileLeavesTheOthers() = runTest {
        val repo = InMemoryWorkStreamRepository()
        val task = seed(repo)
        val actions = DefaultVirlinActions(repo, clock, ids)

        importOne(repo, task.id, "keep.txt", "text/plain")
        importOne(repo, task.id, "remove.txt", "text/plain")
        val target = repo.captures.value.first { it.title == "remove.txt" || it.content.contains("remove") }

        assertTrue(actions.archiveCapture(target.id) is ActionResult.Success)

        // The other file's record and its Page block survive untouched.
        val remaining = repo.captures.value.filter { it.archivedAt == null }
        assertEquals(1, remaining.size)
    }

    // ---- 10-13: bounded, defensive readers --------------------------------------------------

    @Test fun largeTextPreviewIsTruncatedAndDisclosed() {
        val huge = "x".repeat(600_000)
        val result = TextFileReader.read(huge.byteInputStream(), "big.log", "text/plain", maxChars = 512_000)
        assertTrue(result.truncated)
        assertEquals(512_000, result.text.length)
    }

    @Test fun shortTextIsNotMarkedTruncated() {
        val result = TextFileReader.read("hello".byteInputStream(), "a.txt", "text/plain")
        assertFalse(result.truncated)
        assertEquals("hello", result.text)
    }

    @Test fun malformedEncodingDoesNotCrash() {
        val invalidUtf8 = byteArrayOf(0x48, 0x69, 0xC3.toByte(), 0x28, 0x21)
        val result = TextFileReader.read(invalidUtf8.inputStream(), "bad.txt", "text/plain")
        // Decoded defensively: the replacement character appears instead of an exception.
        assertTrue(result.text.isNotEmpty())
    }

    @Test fun codeLikeFilesRenderMonospaced() {
        assertTrue(TextFileReader.isMonospaced("a.json", "application/json"))
        assertTrue(TextFileReader.isMonospaced("a.kt", "text/plain"))
        assertFalse(TextFileReader.isMonospaced("a.txt", "text/plain"))
    }

    // ---- 14-16: Markdown safety -------------------------------------------------------------

    @Test fun markdownParsesTheApprovedDocumentShape() {
        val src = """
            # Release preparation

            This document outlines the key steps.

            ## Checklist
            - [x] Finalize feature set
            - [ ] Schedule release

            ```bash
            npm run build
            ```
        """.trimIndent()
        val blocks = MarkdownPreviewParser.parse(src)
        assertTrue(blocks.any { it is MarkdownPreviewParser.Block.Heading && it.level == 1 })
        assertTrue(blocks.any { it is MarkdownPreviewParser.Block.Task && it.checked })
        assertTrue(blocks.any { it is MarkdownPreviewParser.Block.Task && !it.checked })
        val code = blocks.filterIsInstance<MarkdownPreviewParser.Block.Code>().single()
        assertEquals("bash", code.language)
        assertEquals(listOf("npm run build"), code.lines)
    }

    @Test fun markdownNeverProducesAnExecutableHtmlBlock() {
        val hostile = "<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n[a](javascript:alert(1))"
        val blocks = MarkdownPreviewParser.parse(hostile)
        // Everything degrades to literal text; there is no HTML or script block type at all.
        assertTrue(blocks.all { it is MarkdownPreviewParser.Block.Paragraph })
        val text = blocks.filterIsInstance<MarkdownPreviewParser.Block.Paragraph>().joinToString(" ") { it.text }
        assertTrue(text.contains("<script>"))
    }

    @Test fun malformedEmphasisStaysLiteral() {
        val inline = MarkdownPreviewParser.inline("**unclosed and `code")
        assertTrue(inline.text.contains("**unclosed"))
        assertTrue(inline.spans.isEmpty())
    }

    @Test fun inlineEmphasisProducesSpansOverCleanText() {
        val inline = MarkdownPreviewParser.inline("a **bold** and `code`")
        assertEquals("a bold and code", inline.text)
        assertEquals(2, inline.spans.size)
    }

    @Test fun markdownPreviewIsBlockBounded() {
        val many = (1..5_000).joinToString("\n") { "- item $it" }
        assertTrue(MarkdownPreviewParser.parse(many, maxBlocks = 100).size <= 100)
    }
}
