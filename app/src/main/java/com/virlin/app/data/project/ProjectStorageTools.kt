package com.virlin.app.data.project

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.data.voice.VoiceFileStore
import com.virlin.app.data.projecticon.ProjectIconStore
import com.virlin.app.data.mappreview.MapPreviewImageStore
import com.virlin.app.domain.project.*
import com.virlin.app.domain.repository.WorkStreamRepository
import java.io.File
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

enum class ProjectExportFormat(val mime: String, val extension: String) {
    PDF("application/pdf", "pdf"), MARKDOWN("text/markdown", "md"), BACKUP("application/zip", "zip")
}
data class ProjectStorageUsage(val bytes: Long, val files: Int, val missing: Int, val categories: Map<String, Long>)

/** Private-file work only. External destinations are selected explicitly by Android's document picker. */
class ProjectStorageTools(private val context: Context, private val repository: WorkStreamRepository) {
    private fun root(store: String): File = when (store) {
        "attachments" -> AttachmentFileStore.attachmentsRoot(context)
        "voices" -> VoiceFileStore.voicesRoot(context)
        "icons" -> ProjectIconStore.root(context)
        "previews" -> MapPreviewImageStore.root(context)
        else -> error("Unknown media store")
    }
    internal fun resolve(store: String, path: String): File {
        require(path.isNotBlank() && !File(path).isAbsolute) { "Invalid project media path." }
        val base = root(store).canonicalFile
        val file = File(base, path).canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "Media path leaves managed storage." }
        return file
    }
    private fun references(s: ProjectSnapshot): List<Pair<String, String>> = buildList {
        s.project.iconPath?.let { add("icons" to it) }
        s.attachments.forEach { add("attachments" to it.relativePath) }
        s.voices.flatMap { it.clips }.forEach { add("voices" to it.relativePath) }
        s.previews.flatMap { it.images }.forEach { add("previews" to it.relativePath) }
    }.distinct()

    // Include attachment sidecars (e.g. editable PDF data) without counting a file twice.
    private fun files(s: ProjectSnapshot): Map<Pair<String, String>, File> {
        val result = references(s).associateWith { resolve(it.first, it.second) }.toMutableMap()
        s.attachments.forEach { attachment ->
            val file = resolve("attachments", attachment.relativePath)
            val owner = File(root("attachments"), attachment.id).canonicalFile
            if (file.path.startsWith(owner.path + File.separator) && owner.isDirectory) {
                owner.walkTopDown().filter { it.isFile }.forEach { extra ->
                    val path = extra.relativeTo(root("attachments")).invariantSeparatorsPath
                    result["attachments" to path] = resolve("attachments", path)
                }
            }
        }
        return result
    }
    fun usage(s: ProjectSnapshot): ProjectStorageUsage {
        val files = files(s)
        val present = files.filterValues { it.isFile }
        val categories = present.entries.groupBy { it.key.first }.mapValues { (_, values) -> values.sumOf { it.value.length() } }
        return ProjectStorageUsage(categories.values.sum(), present.size, files.size - present.size, categories)
    }

    suspend fun duplicate(s: ProjectSnapshot, name: String, mode: ProjectCopyMode): com.virlin.app.domain.model.Project {
        val created = mutableListOf<File>()
        val copied = mutableMapOf<Triple<String, String, String>, String>()
        var committed = false
        try {
            val result = ProjectDuplicator(repository).duplicate(s, name, mode, Instant.now(), copyFile = { store, path, owner ->
                copied.getOrPut(Triple(store, path, owner)) {
                    val source = resolve(store, path)
                    require(source.isFile) { "A project media file is missing. Original project is unchanged." }
                    val relative = "$owner/${UUID.randomUUID()}.${source.extension.ifBlank { "bin" }}"
                    val dest = resolve(store, relative)
                    check(!dest.exists()); check(dest.parentFile!!.isDirectory || dest.parentFile!!.mkdirs())
                    created += dest
                    source.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
                    if (store == "attachments") {
                        val sourceOwner = requireNotNull(source.parentFile)
                        // The attachment owns its folder. Preserve edited-file sidecars at their relative names.
                        if (sourceOwner.name == s.attachments.firstOrNull { it.relativePath == path }?.id) {
                            sourceOwner.walkTopDown().filter { it.isFile && it != source }.forEach { sidecar ->
                                val sidecarPath = sidecar.relativeTo(root(store)).invariantSeparatorsPath
                                val checked = resolve(store, sidecarPath)
                                val target = resolve(store, "$owner/${sidecar.relativeTo(sourceOwner).invariantSeparatorsPath}")
                                check(!target.exists()); target.parentFile!!.mkdirs(); created += target
                                checked.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                            }
                        }
                    }
                    relative
                }
            })
            committed = true
            // Palette is presentation-only. Database content has already committed atomically.
            runCatching {
                val prefs = context.getSharedPreferences("project_appearance", Context.MODE_PRIVATE)
                prefs.getString(s.project.id, null)?.let { prefs.edit().putString(result.id, it).apply() }
                val timers = context.getSharedPreferences("hierarchy_timer_display", Context.MODE_PRIVATE)
                if (timers.contains("project:${s.project.id}")) timers.edit().putInt("project:${result.id}", timers.getInt("project:${s.project.id}", 6)).apply()
                val blocks = context.getSharedPreferences("project_block_order", Context.MODE_PRIVATE)
                if (blocks.contains(s.project.id)) blocks.edit().putBoolean(result.id, blocks.getBoolean(s.project.id, false)).apply()
            }
            return result
        } finally {
            if (!committed) created.forEach { it.delete() } // exact newly-created files only
        }
    }

    suspend fun export(s: ProjectSnapshot, format: ProjectExportFormat): File {
        val dir = File(context.cacheDir, "project-exports").apply { check(isDirectory || mkdirs()) }
        val file = File(dir, "${UUID.randomUUID()}.${format.extension}")
        try {
            when (format) {
                ProjectExportFormat.MARKDOWN -> file.writeText(ProjectArchive.markdown(s), Charsets.UTF_8)
                ProjectExportFormat.PDF -> writePdf(file, ProjectArchive.markdown(s))
                ProjectExportFormat.BACKUP -> {
                    val media = files(s)
                    require(media.values.all { it.isFile }) { "Some media files are missing. Backup was not created." }
                    val manifest = ProjectArchive.manifest(s)
                    val streamIds = (s.streams.map { it.id } + s.tasks.map { it.id }).toSet()
                    val history = JSONObject()
                    history.put("snapshots", JSONArray(streamIds.flatMap { repository.getSnapshots(it) }.map { snap ->
                        ProjectArchive.json("id" to snap.id, "workStreamId" to snap.workStreamId, "cycleId" to snap.cycleId,
                            "createdAt" to snap.createdAt, "reason" to snap.reason, "lastHumanAction" to snap.lastHumanAction,
                            "waitingFor" to snap.waitingFor, "nextHumanAction" to snap.nextHumanAction, "checkAt" to snap.checkAt,
                            "contextLabel" to snap.contextLabel, "note" to snap.note, "taskId" to snap.taskId)
                    }))
                    history.put("externalStages", JSONArray(s.stages.map { stage ->
                        ProjectArchive.json("id" to stage.id, "workStreamId" to stage.workStreamId, "title" to stage.title,
                            "order" to stage.order, "expectedMinutes" to stage.expectedMinutes, "status" to stage.status,
                            "startedAt" to stage.startedAt, "completedAt" to stage.completedAt)
                    }))
                    history.put("focusSessions", JSONArray(repository.allFocusSessions().filter { it.workStreamId in streamIds || it.taskId in streamIds }.map { session ->
                        ProjectArchive.json("id" to session.id, "workStreamId" to session.workStreamId, "taskId" to session.taskId,
                            "cycleId" to session.cycleId, "startedAt" to session.startedAt, "endedAt" to session.endedAt)
                    }))
                    history.put("cycles", JSONArray(repository.allCycles().filter { it.workStreamId in streamIds }.map { cycle ->
                        ProjectArchive.json("id" to cycle.id, "workStreamId" to cycle.workStreamId, "number" to cycle.number,
                            "startedAt" to cycle.startedAt, "handedOffAt" to cycle.handedOffAt, "endedAt" to cycle.endedAt)
                    }))
                    history.put("events", JSONArray(streamIds.flatMap { repository.getEvents(it) }.map { event ->
                        ProjectArchive.json("id" to event.id, "workStreamId" to event.workStreamId, "type" to event.type,
                            "at" to event.at, "cycleId" to event.cycleId, "fromState" to event.fromState, "toState" to event.toState, "detail" to event.detail)
                    }))
                    history.put("externalIntervals", JSONArray(repository.externalRunIntervals().filter { it.ownerId in streamIds }.map {
                        ProjectArchive.json("ownerId" to it.ownerId, "taskId" to it.taskId, "startedAt" to it.start, "endedAt" to it.end)
                    }))
                    manifest.put("history", history)
                    manifest.put("appearance", ProjectArchive.json("palette" to context.getSharedPreferences("project_appearance", 0).getString(s.project.id, null),
                        "timerRotationSeconds" to context.getSharedPreferences("hierarchy_timer_display", 0).getInt("project:${s.project.id}", 6),
                        "workstreamBlocks" to context.getSharedPreferences("project_block_order", 0).getBoolean(s.project.id, false)))
                    val mediaManifest = JSONArray()
                    ZipOutputStream(file.outputStream().buffered()).use { zip ->
                        media.forEach { (key, source) ->
                            val entry = "media/${key.first}/${source.relativeTo(root(key.first).canonicalFile).invariantSeparatorsPath}"
                            val digest = MessageDigest.getInstance("SHA-256")
                            zip.putNextEntry(ZipEntry(entry))
                            source.inputStream().buffered().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read); zip.write(buffer, 0, read) }
                            }
                            zip.closeEntry()
                            mediaManifest.put(ProjectArchive.json("store" to key.first, "path" to key.second,
                                "entry" to entry, "bytes" to source.length(), "sha256" to digest.digest().joinToString("") { "%02x".format(it) }))
                        }
                        manifest.put("media", mediaManifest)
                        zip.putNextEntry(ZipEntry("project.json")); zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                        zip.putNextEntry(ZipEntry("project.md")); zip.write(ProjectArchive.markdown(s).toByteArray(Charsets.UTF_8)); zip.closeEntry()
                        zip.putNextEntry(ZipEntry("README.txt")); zip.write("Virlin project archive v1. Contains project data, recorded history and managed media. Export only: this app does not currently import or restore archives. Live timers are snapshots, not resumable jobs. PDF/Markdown are text summaries; media originals are in this archive.".toByteArray()); zip.closeEntry()
                    }
                }
            }
            return file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    private fun writePdf(file: File, text: String) {
        val pdf = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = 0xFF17202C.toInt() }
            var pageNumber = 1
            var page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            var y = 48f
            text.lines().forEach { line ->
                var remaining = line
                do {
                    if (y > 790f) { pdf.finishPage(page); page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create()); y = 48f }
                    var count = if (remaining.isEmpty()) 0 else paint.breakText(remaining, true, 499f, null).coerceAtLeast(1)
                    if (count in 1 until remaining.length && remaining[count - 1].isHighSurrogate() && remaining[count].isLowSurrogate()) {
                        count = if (count > 1) count - 1 else 2
                    }
                    page.canvas.drawText(remaining.take(count), 48f, y, paint); y += 16f
                    remaining = remaining.drop(count)
                } while (remaining.isNotEmpty())
            }
            pdf.finishPage(page)
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
    }
}
