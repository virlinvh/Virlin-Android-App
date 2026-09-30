package com.virlin.app.hierarchy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.data.voice.VoiceFileStore
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.CreateWorkStream
import com.virlin.app.domain.action.Field
import com.virlin.app.domain.action.TaskUpdate
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.VoiceClip
import com.virlin.app.domain.model.WorkStream
import java.io.File
import java.io.FileOutputStream

/**
 * A LABELLED, REVERSIBLE test set for Project Activity, written through the app's own actions
 * and its own storage — never into the UI.
 *
 * Every row it creates has a stable id beginning with [PREFIX], so running it twice updates the
 * same records instead of making new ones, and [remove] can take exactly this set back out
 * without touching anything the user made. Timestamps are the app's real clock: no history is
 * forged, so everything it creates is dated now.
 */
object Phase08ActivitySeed {

    const val PREFIX = "p08t"
    const val PROJECT_TITLE = "Phase 08 Project"
    const val STREAM_TITLE = "Android App"
    const val LABEL = "[TEST]"

    private val actions get() = VirlinGraph.actions
    private val repository get() = VirlinGraph.repository

    data class Seeded(
        val project: Project,
        val stream: WorkStream,
        val liveTask: Task,
        val completedTask: Task
    )

    // ---------------------------------------------------------------- seed

    suspend fun seed(context: Context): Seeded {
        val project = requireNotNull(repository.projects.value.firstOrNull { it.title == PROJECT_TITLE }) {
            "$PROJECT_TITLE does not exist; create it in the app first."
        }
        val stream = repository.streams.value
            .firstOrNull { it.projectId == project.id && it.title == STREAM_TITLE }
            ?: (actions.createWorkStream(
                CreateWorkStream(
                    title = STREAM_TITLE, projectId = project.id,
                    executionPreference = ExecutionPreference.INHERIT, id = "$PREFIX-stream"
                )
            ) as ActionResult.Success).value

        // 5. A task that is created, moved TODO -> IN_PROGRESS, and left live, plus one that is
        //    created and completed. Both write real events through the real action layer.
        val liveTask = task("$PREFIX-task-live", "$LABEL Wire the Activity timeline", stream.id, project.id)
        // Each step is guarded: re-running writes no second event for work already recorded.
        if (liveTask.status != TaskStatus.IN_PROGRESS) {
            actions.updateTask(liveTask.id, TaskUpdate(inProgress = Field.Set(true)))
        }
        if (repository.getStream(stream.id)?.activeTaskId != liveTask.id) {
            actions.setActiveTask(stream.id, liveTask.id)
        }

        val doneTask = task("$PREFIX-task-done", "$LABEL Ship the Activity tab", stream.id, project.id)
        if (!doneTask.status.isTerminal) {
            actions.updateTask(doneTask.id, TaskUpdate(inProgress = Field.Set(true)))
            actions.completeTask(doneTask.id)
        }

        // 7. A WorkStream-level event with no task at all: block, then unblock. Once only.
        // An unblock carries no detail, so there would be no way to tell a seeded one from the
        // user's later: the ids it wrote are recorded here so removal can take exactly those.
        if (repository.getEvents(stream.id).none { it.detail?.contains(LABEL) == true }) {
            val before = repository.getEvents(stream.id).map { it.id }.toSet()
            actions.blockStream(stream.id, "$LABEL waiting on the design review")
            actions.unblockStream(stream.id)
            val written = repository.getEvents(stream.id).map { it.id }.filterNot { it in before }
            eventLedger(context).appendText(written.joinToString(separator = "\n", postfix = "\n"))
        }

        // 1. A multiline prompt: heading, numbered steps and a code block, kept verbatim.
        prompt(project, stream, liveTask)
        // 2a. A short text note against the task.
        shortNote(project, stream, liveTask)
        // 6. A checklist note, saved partially complete and then updated to complete.
        checklist(project, stream)
        // 6b. An ORDERED note: numbered blocks, which Knowledge classifies as Steps.
        steps(project, stream, liveTask)
        // 2b. A longer document as a real file import.
        document(context, project, stream)
        // 3. An image with real bytes, imported the way the app imports one.
        image(context, project, stream)
        // 4. A recording with real audio bytes and a real duration.
        audio(context, project, stream)
        // 8a. A link capture.
        link(project, stream)
        // 8b. An AI response kept as a note, filed to the PROJECT only — no task, no stream.
        aiResponse(project)

        return Seeded(project, stream, liveTask, doneTask)
    }

    private suspend fun task(id: String, title: String, streamId: String, projectId: String): Task =
        repository.getTask(id) ?: (actions.createTask(
            CreateTask(title = title, projectId = projectId, workStreamId = streamId, id = id)
        ) as ActionResult.Success).value

    // ---------------------------------------------------------------- captures

    private fun text(value: String) = NoteBlock(id = "b-${value.hashCode()}", type = NoteBlockType.TEXT, plainText = value)

    private suspend fun prompt(project: Project, stream: WorkStream, task: Task) {
        val id = "$PREFIX-prompt"
        val blocks = listOf(
            NoteBlock("$id-h", NoteBlockType.HEADING_2, "Activity timeline prompt"),
            NoteBlock("$id-1", NoteBlockType.NUMBERED_LIST, "Read the project's event history."),
            NoteBlock("$id-2", NoteBlockType.NUMBERED_LIST, "Group it by the viewer's own local day."),
            NoteBlock("$id-3", NoteBlockType.NUMBERED_LIST, "Never reorder it when Knowledge is refiled."),
            NoteBlock("$id-code", NoteBlockType.CODE, "fun build(events: List<WorkStreamEvent>) =\n    events.sortedByDescending { it.at }")
        )
        if (repository.getCapture(id) == null) {
            actions.createPrompt(
                title = "$LABEL Timeline prompt", description = "Multiline, formatted, verbatim.",
                tags = listOf("test"), blocks = blocks,
                context = CaptureContext(project.id, stream.id, task.id), captureId = id, promptId = "$id-doc"
            )
        } else {
            actions.savePrompt(id, "$LABEL Timeline prompt", "Multiline, formatted, verbatim.", listOf("test"), blocks)
        }
    }

    private suspend fun shortNote(project: Project, stream: WorkStream, task: Task) {
        val id = "$PREFIX-note"
        val blocks = listOf(text("Check the 23:45 row lands on the previous local day."))
        if (repository.getCapture(id) == null) {
            actions.createTextNote("$LABEL Short note", blocks,
                CaptureContext(project.id, stream.id, task.id), captureId = id, noteId = "$id-doc")
        } else actions.saveTextNote(id, "$LABEL Short note", blocks)
    }

    /**
     * Saved partially complete FIRST and then updated to complete, so the "current saved state"
     * wording on a checklist row can be seen to be exactly that.
     */
    private suspend fun checklist(project: Project, stream: WorkStream) {
        val id = "$PREFIX-checklist"
        fun steps(doneCount: Int) = listOf(
            NoteBlock("$id-c1", NoteBlockType.CHECKBOX, "Batch the event read", checked = doneCount > 0),
            NoteBlock("$id-c2", NoteBlockType.CHECKBOX, "Bound the LazyColumn height", checked = doneCount > 1),
            NoteBlock("$id-c3", NoteBlockType.CHECKBOX, "Snapshot task events", checked = doneCount > 2)
        )
        if (repository.getCapture(id) == null) {
            actions.createTextNote("$LABEL Release checklist", steps(1),
                CaptureContext(project.id, stream.id), captureId = id, noteId = "$id-doc")
        }
        actions.saveTextNote(id, "$LABEL Release checklist", steps(3))
    }

    /** Ordered steps live in a note's NUMBERED_LIST blocks — the app's own block type. */
    private suspend fun steps(project: Project, stream: WorkStream, task: Task) {
        val id = "$PREFIX-steps"
        val blocks = listOf(
            NoteBlock("$id-1", NoteBlockType.NUMBERED_LIST, "Freeze the release branch"),
            NoteBlock("$id-2", NoteBlockType.NUMBERED_LIST, "Run the instrumented suites"),
            NoteBlock("$id-3", NoteBlockType.NUMBERED_LIST, "Tag and upload the build")
        )
        if (repository.getCapture(id) == null) {
            actions.createTextNote("$LABEL Release steps", blocks,
                CaptureContext(project.id, stream.id, task.id), captureId = id, noteId = "$id-doc")
        } else actions.saveTextNote(id, "$LABEL Release steps", blocks)
    }

    private suspend fun document(context: Context, project: Project, stream: WorkStream) {
        val body = buildString {
            appendLine("$LABEL Activity specification")
            repeat(40) { appendLine("Paragraph ${it + 1}: the record is chronological and append-only.") }
        }
        val source = File(context.cacheDir, "$PREFIX-doc.txt").apply { writeText(body) }
        attach("$PREFIX-file", source, "text/plain", AttachmentKind.TEXT,
            "$LABEL Activity specification.txt", CaptureContext(project.id, stream.id))
    }

    private suspend fun image(context: Context, project: Project, stream: WorkStream) {
        // Two visibly different pictures, so the gallery shows real decoded bitmaps rather than
        // one flat fill that could be mistaken for a placeholder.
        photo(context, project, stream, "$PREFIX-image", "$LABEL Timeline screenshot.png",
            intArrayOf(0xFF2E4A7D.toInt(), 0xFF6FA8DC.toInt()), "Timeline")
        photo(context, project, stream, "$PREFIX-image-2", "$LABEL Orb study.png",
            intArrayOf(0xFF7A3E12.toInt(), 0xFFE0A36B.toInt()), "Orb study")
    }

    private suspend fun photo(
        context: Context, project: Project, stream: WorkStream,
        id: String, displayName: String, ramp: IntArray, caption: String
    ) {
        val source = File(context.cacheDir, "$id-v2.png")
        if (!source.exists()) {
            val bitmap = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawPaint(Paint().apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 720f, 480f, ramp[0], ramp[1], android.graphics.Shader.TileMode.CLAMP
                )
            })
            canvas.drawCircle(540f, 150f, 96f, Paint().apply {
                color = android.graphics.Color.argb(120, 255, 255, 255); isAntiAlias = true
            })
            canvas.drawCircle(180f, 360f, 140f, Paint().apply {
                color = android.graphics.Color.argb(70, 0, 0, 0); isAntiAlias = true
            })
            canvas.drawText(caption, 44f, 440f, Paint().apply {
                color = android.graphics.Color.WHITE; textSize = 46f; isAntiAlias = true
            })
            FileOutputStream(source).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        attach(id, source, "image/png", AttachmentKind.IMAGE, displayName,
            CaptureContext(project.id, stream.id))
    }

    private suspend fun attach(
        id: String, source: File, mime: String, kind: AttachmentKind,
        displayName: String, context: CaptureContext
    ) {
        val app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val existing = VirlinGraph.actions.getAttachmentByCaptureId(id)
        // The app's own import path: stream a content/file Uri into managed storage.
        val imported = AttachmentFileStore.importFromUri(
            app, Uri.fromFile(source), attachmentId = existing?.id ?: "$id-doc", fallbackName = displayName
        )
        if (existing == null) {
            VirlinGraph.actions.createAttachment(
                displayName = displayName, mimeType = mime, sizeBytes = imported.sizeBytes,
                relativePath = imported.relativePath, kind = kind, context = context,
                captureId = id, attachmentId = "$id-doc"
            )
        } else {
            VirlinGraph.actions.saveAttachment(id, displayName, mime, imported.sizeBytes, imported.relativePath, kind)
        }
    }

    /**
     * Real audio bytes, real duration, played by the app's own MediaPlayer. The waveform is a
     * generated tone rather than a microphone recording — the capture, the document, the managed
     * storage and the playback are the app's; only the sound is synthetic.
     */
    private suspend fun audio(context: Context, project: Project, stream: WorkStream) {
        val id = "$PREFIX-voice"
        val clipId = "$id-clip"
        val file = VoiceFileStore.clipFile(context, id, clipId)
        val durationMs = 3_000L
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            writeWav(file, durationMs)
        }
        val clip = VoiceClip(
            id = clipId, displayName = "$LABEL Standup recording",
            relativePath = VoiceFileStore.relativePath(id, clipId),
            durationMs = durationMs, sizeBytes = file.length(), mimeType = "audio/wav",
            sortOrder = 0, createdAt = VirlinGraph.clock.now()
        )
        if (repository.getCapture(id) == null) {
            actions.createVoice("$LABEL Standup recording", listOf(clip),
                CaptureContext(project.id, stream.id), captureId = id, voiceId = "$id-doc")
        } else actions.saveVoice(id, "$LABEL Standup recording", listOf(clip))
    }

    /** 16-bit mono 8 kHz PCM WAV — a real, playable file with a real duration. */
    private fun writeWav(file: File, durationMs: Long) {
        val rate = 8_000
        val samples = (rate * durationMs / 1000).toInt()
        val data = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = (Math.sin(2.0 * Math.PI * 440.0 * i / rate) * 12_000).toInt().toShort()
            data[i * 2] = (value.toInt() and 0xFF).toByte()
            data[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        fun le(value: Int, bytes: Int) = ByteArray(bytes) { ((value shr (8 * it)) and 0xFF).toByte() }
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray()); out.write(le(36 + data.size, 4)); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); out.write(le(16, 4)); out.write(le(1, 2)); out.write(le(1, 2))
            out.write(le(rate, 4)); out.write(le(rate * 2, 4)); out.write(le(2, 2)); out.write(le(16, 2))
            out.write("data".toByteArray()); out.write(le(data.size, 4)); out.write(data)
        }
    }

    private suspend fun link(project: Project, stream: WorkStream) {
        val id = "$PREFIX-link"
        if (repository.getCapture(id) == null) {
            actions.createCapture(
                CreateCapture(
                    type = CaptureType.LINK, content = "Compose LazyColumn inside a scrollable parent",
                    title = "$LABEL Lazy layout docs",
                    sourceUrl = "https://developer.android.com/develop/ui/compose/lists",
                    context = CaptureContext(project.id, stream.id), id = id
                )
            )
        }
    }

    /** A project-level record with NO task and NO WorkStream. */
    private suspend fun aiResponse(project: Project) {
        val id = "$PREFIX-ai"
        val blocks = listOf(
            NoteBlock("$id-h", NoteBlockType.HEADING_3, "Assistant response"),
            text("Activity is a record, not a dashboard: it never reorders when Knowledge is refiled.")
        )
        if (repository.getCapture(id) == null) {
            actions.createTextNote("$LABEL AI response (project level)", blocks,
                CaptureContext(projectId = project.id), captureId = id, noteId = "$id-doc")
        } else actions.saveTextNote(id, "$LABEL AI response (project level)", blocks)
    }

    /** Test-owned file: the ids of the detail-less events this set wrote. */
    private fun eventLedger(context: Context) = File(context.filesDir, "$PREFIX-events.txt")

    // ---------------------------------------------------------------- removal

    /**
     * Takes the seeded set back out. Activity is append-only through the action layer by design,
     * so the events it wrote are removed here with direct SQL — a test-only escape hatch that
     * exists so this labelled set leaves no trace, not a path the app itself has.
     */
    fun remove(context: Context) {
        val db = com.virlin.app.data.db.VirlinDatabase.open(context)
        val ids = "'$PREFIX-%'"
        db.openHelper.writableDatabase.apply {
            execSQL("DELETE FROM note_documents WHERE captureItemId LIKE $ids")
            execSQL("DELETE FROM prompt_documents WHERE captureItemId LIKE $ids")
            execSQL("DELETE FROM attachment_documents WHERE captureItemId LIKE $ids")
            execSQL("DELETE FROM voice_documents WHERE captureItemId LIKE $ids")
            execSQL("DELETE FROM captures WHERE id LIKE $ids")
            execSQL("DELETE FROM events WHERE detail LIKE $ids OR detail LIKE '%$LABEL%'")
            // The detail-less WorkStream events this set wrote, by the ids it recorded.
            val ledger = eventLedger(context)
            ledger.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.forEach { eventId ->
                execSQL("DELETE FROM events WHERE id = ?", arrayOf(eventId))
            }
            ledger.delete()
            // Belt and braces for sets written before the ledger existed: this seed is the only
            // thing that ever blocks or unblocks the labelled WorkStream.
            execSQL(
                "DELETE FROM events WHERE type IN ('BLOCKED','UNBLOCKED') AND workStreamId IN " +
                    "(SELECT id FROM workstreams WHERE title = '$STREAM_TITLE')"
            )
            execSQL("DELETE FROM tasks WHERE id LIKE $ids")
        }
        AttachmentFileStore.deleteAttachmentTree(context, "$PREFIX-file-doc")
        AttachmentFileStore.deleteAttachmentTree(context, "$PREFIX-image-doc")
        AttachmentFileStore.deleteAttachmentTree(context, "$PREFIX-image-2-doc")
        VoiceFileStore.deleteCaptureTree(context, "$PREFIX-voice")
    }
}
