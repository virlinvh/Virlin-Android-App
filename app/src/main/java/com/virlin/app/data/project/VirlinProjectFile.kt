package com.virlin.app.data.project

import android.content.Context
import com.virlin.app.BuildConfig
import com.virlin.app.data.flashcard.FlashcardDeckStyleStore
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.data.flashcard.SavedFlashcard
import com.virlin.app.data.noteaudio.NoteAudioStore
import com.virlin.app.data.noteimage.NoteImageStore
import com.virlin.app.domain.project.ProjectSnapshot
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.ui.map.MapAppearanceStore
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** KEEP writes every recorded second; RESET writes the same project with time spent at zero. */
enum class VirlinTiming { KEEP, RESET }

data class VirlinExport(val file: File, val sha256: String, val summary: VirlinFileSummary)

data class VirlinFileSummary(
    val formatVersion: Int,
    val projectId: String,
    val projectTitle: String,
    val timing: VirlinTiming,
    val mediaFiles: Int,
    val mediaBytes: Long,
    val missing: List<String>,
)

/**
 * The `.virlin` project file: one ZIP container that holds a whole project so it can be
 * imported again exactly as it was.
 *
 * Layout (format version 1):
 * - `mimetype` — first entry, stored uncompressed: [MIME_TYPE]. Identifies the file without parsing it.
 * - `virlin.json` — header: format, generator, timing mode, counts and the SHA-256 of every other part.
 * - `data/project.json` — every record of the project (structure, notes, Pages, captures, mind map,
 *   flashcards, history) plus its per-project settings, in explicit versioned fields.
 * - `data/summary.md` — a readable summary for people opening the container by hand.
 * - `media/<store>/<path>` — each media file byte for byte, at the same relative path it has on the
 *   device. Already-compressed kinds (photos, PDFs, audio, office files) are stored as they are;
 *   other files use lossless deflate. Either way the bytes that come out are the bytes that went in,
 *   and the header's SHA-256 proves it.
 *
 * Writing never changes the project. Import reads the same layout ([verify] is its first step).
 */
object VirlinProjectFile {
    const val EXTENSION = "virlin"
    const val MIME_TYPE = "application/vnd.virlin.project+zip"
    /** What the save dialog is given, so no provider renames `.virlin` to another extension. */
    const val SAVE_MIME_TYPE = "application/octet-stream"
    const val FORMAT = "virlin-project"
    const val FORMAT_VERSION = 1
    const val DATABASE_SCHEMA = 24
    internal const val MIMETYPE_ENTRY = "mimetype"
    internal const val HEADER_ENTRY = "virlin.json"
    internal const val DATA_ENTRY = "data/project.json"
    internal const val SUMMARY_ENTRY = "data/summary.md"
    internal const val README_ENTRY = "README.txt"
    internal const val MEDIA_PREFIX = "media/"
    private val STORED_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif",
        "pdf", "m4a", "mp3", "aac", "ogg", "opus", "flac", "mp4", "mov", "webm", "3gp", "mkv",
        "zip", "gz", "7z", "rar", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "virlin",
    )

    fun fileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60).ifBlank { "Project" } + ".$EXTENSION"

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        return digest.digest().hex()
    }

    internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    internal fun stored(path: String, kind: String?) =
        path.substringAfterLast('.', "").lowercase() in STORED_EXTENSIONS || kind in COMPRESSED_KINDS

    private val COMPRESSED_KINDS = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heif",
        "application/pdf", "application/zip", "video/mp4", "audio/mp4", "audio/mpeg", "audio/ogg")

    /** The file's real type from its first bytes; note pictures and recordings are saved without an extension. */
    internal fun sniff(file: File): String? {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) return null
        fun at(offset: Int, text: String) = read >= offset + text.length &&
            text.indices.all { head[offset + it] == text[it].code.toByte() }
        return when {
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "image/jpeg"
            head[0] == 0x89.toByte() && at(1, "PNG") -> "image/png"
            at(0, "RIFF") && at(8, "WEBP") -> "image/webp"
            at(0, "GIF8") -> "image/gif"
            at(0, "%PDF") -> "application/pdf"
            at(0, "PK") -> "application/zip"
            at(0, "OggS") -> "audio/ogg"
            at(0, "ID3") || (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0) -> "audio/mpeg"
            at(4, "ftyp") -> when {
                at(8, "M4A") -> "audio/mp4"
                at(8, "heic") || at(8, "heix") || at(8, "mif1") -> "image/heif"
                else -> "video/mp4"
            }
            else -> null
        }
    }

    internal fun safeEntry(name: String) = name.isNotBlank() && name.length <= 1024 && !name.startsWith("/") &&
        '\\' !in name && ':' !in name && name.split('/').none { it == ".." || it == "." || it.isEmpty() }

    /**
     * Opens a `.virlin` file and proves it whole: the identifying first entry, a supported header,
     * the project data and every media file against their recorded size and SHA-256.
     */
    fun verify(file: File): VirlinFileSummary = ZipFile(file).use { zip ->
        val names = zip.entries().toList()
        require(names.isNotEmpty() && names.first().name == MIMETYPE_ENTRY && names.first().method == ZipEntry.STORED) { "Not a Virlin project file." }
        require(zip.getInputStream(names.first()).use { it.readBytes() }.toString(Charsets.US_ASCII) == MIME_TYPE) { "Not a Virlin project file." }
        require(names.all { safeEntry(it.name) } && names.map { it.name }.toSet().size == names.size) { "The file contains an unsafe entry." }
        val header = JSONObject(zip.read(HEADER_ENTRY).toString(Charsets.UTF_8))
        require(header.optString("format") == FORMAT) { "Not a Virlin project file." }
        val version = header.optInt("formatVersion")
        require(version in 1..FORMAT_VERSION) { "This file was made by a newer Virlin (format $version)." }
        val dataInfo = header.getJSONObject("data")
        val data = zip.read(DATA_ENTRY)
        require(data.size.toLong() == dataInfo.getLong("bytes") && MessageDigest.getInstance("SHA-256").digest(data).hex() == dataInfo.getString("sha256")) { "Project data is damaged." }
        val project = JSONObject(data.toString(Charsets.UTF_8)).getJSONObject("project")
        require(project.getString("id") == header.getJSONObject("project").getString("id")) { "Project data does not match the header." }
        val media = header.getJSONObject("media")
        val items = media.getJSONArray("items")
        var bytes = 0L
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val name = item.getString("entry")
            require(name == MEDIA_PREFIX + item.getString("store") + "/" + item.getString("path")) { "A media entry is misplaced." }
            val entry = requireNotNull(zip.getEntry(name)) { "A media file is missing from the package." }
            val counted = CountingStream(zip.getInputStream(entry))
            val sha = counted.use { sha256(it) }
            require(counted.count == item.getLong("bytes") && sha == item.getString("sha256")) { "A media file is damaged: ${item.getString("path")}" }
            bytes += counted.count
        }
        val missing = media.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        VirlinFileSummary(version, project.getString("id"), project.optString("title"),
            VirlinTiming.valueOf(header.getJSONObject("timing").getString("mode")), items.length(), bytes, missing)
    }

    private fun ZipFile.read(name: String): ByteArray =
        requireNotNull(getEntry(name)) { "The file is incomplete ($name)." }.let { entry -> getInputStream(entry).use { it.readBytes() } }

    private class CountingStream(private val inner: InputStream) : InputStream() {
        var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }
}

/** Writes one project as a `.virlin` file in the app's cache. The project itself is only read. */
internal class VirlinProjectWriter(
    private val context: Context,
    private val repository: WorkStreamRepository,
    private val tools: ProjectStorageTools,
) {
    private data class Media(val store: String, val path: String, val file: File) {
        val entry get() = VirlinProjectFile.MEDIA_PREFIX + "$store/$path"
    }
    private data class Measured(val media: Media, val bytes: Long, val sha256: String, val crc: Long, val modified: Long, val kind: String?) {
        val stored get() = VirlinProjectFile.stored(media.path, kind)
    }

    suspend fun write(s: ProjectSnapshot, timing: VirlinTiming, now: Instant = Instant.now()): VirlinExport {
        val cards = projectFlashcards(context, s)
        val (media, missing) = media(s, cards)
        val measured = media.map { measure(it) }
        val data = data(s, cards, timing)
        val dataBytes = data.toString(2).toByteArray(Charsets.UTF_8)
        val header = header(s, timing, now, dataBytes, measured, missing, data)
        val dir = File(context.cacheDir, "project-exports").apply { check(isDirectory || mkdirs()) }
        val file = File(dir, "${UUID.randomUUID()}.${VirlinProjectFile.EXTENSION}")
        try {
            ZipOutputStream(file.outputStream().buffered()).use { zip ->
                zip.putStored(VirlinProjectFile.MIMETYPE_ENTRY, VirlinProjectFile.MIME_TYPE.toByteArray(Charsets.US_ASCII))
                zip.putDeflated(VirlinProjectFile.HEADER_ENTRY, header.toString(2).toByteArray(Charsets.UTF_8))
                zip.putDeflated(VirlinProjectFile.DATA_ENTRY, dataBytes)
                zip.putDeflated(VirlinProjectFile.SUMMARY_ENTRY, ProjectArchive.markdown(s).toByteArray(Charsets.UTF_8))
                zip.putDeflated(VirlinProjectFile.README_ENTRY, README.toByteArray(Charsets.UTF_8))
                measured.forEach { zip.putMedia(it) }
            }
            val summary = VirlinProjectFile.verify(file)
            return VirlinExport(file, file.inputStream().use(VirlinProjectFile::sha256), summary)
        } catch (error: Throwable) { file.delete(); throw error }
    }

    /** Every file the project references, each once; references to files already gone are listed, not hidden. */
    private fun media(s: ProjectSnapshot, cards: List<SavedFlashcard>): Pair<List<Media>, List<String>> {
        val found = mutableListOf<Media>()
        val missing = mutableListOf<String>()
        val seen = HashSet<String>()
        fun add(store: String, path: String, file: File?) {
            if (!seen.add("$store/$path")) return
            if (file == null || !file.isFile || !VirlinProjectFile.safeEntry("$store/$path")) missing += "$store/$path"
            else found += Media(store, path, file)
        }
        tools.files(s).forEach { (key, file) -> add(key.first, key.second, file) }
        val noteBlocks = s.richNotes.flatMap { it.blocks }
        val cardBlocks = cards.flatMap { card -> listOfNotNull(card.content, card.frontContent) + card.frontBlocks.orEmpty() + card.backBlocks.orEmpty() }
        (noteBlocks + cardBlocks).mapNotNull { it.imagePath }.distinct().forEach { add("note-images", it, NoteImageStore.resolve(context, it)) }
        (noteBlocks + cardBlocks).mapNotNull { it.audio?.file }.distinct().forEach { add("note-audio", it, NoteAudioStore.resolve(context, it)) }
        return found to missing
    }

    private fun measure(media: Media): Measured {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var bytes = 0L
        media.file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read); crc.update(buffer, 0, read); bytes += read }
        }
        return Measured(media, bytes, with(VirlinProjectFile) { digest.digest().hex() }, crc.value, media.file.lastModified(),
            runCatching { VirlinProjectFile.sniff(media.file) }.getOrNull())
    }

    private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong(); this.crc = crc.value })
        write(bytes); closeEntry()
    }

    private fun ZipOutputStream.putDeflated(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED }); write(bytes); closeEntry()
    }

    private fun ZipOutputStream.putMedia(m: Measured) {
        putNextEntry(ZipEntry(m.media.entry).apply {
            if (m.modified > 0) time = m.modified
            if (m.stored) { method = ZipEntry.STORED; size = m.bytes; compressedSize = m.bytes; crc = m.crc }
            else method = ZipEntry.DEFLATED
        })
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        m.media.file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read); write(buffer, 0, read); bytes += read }
        }
        closeEntry()
        check(bytes == m.bytes && with(VirlinProjectFile) { digest.digest().hex() } == m.sha256) {
            "A file changed while exporting (${m.media.path}). Nothing was saved; please try again."
        }
    }

    private suspend fun data(s: ProjectSnapshot, cards: List<SavedFlashcard>, timing: VirlinTiming): JSONObject {
        val data = ProjectArchive.manifest(s)
        data.put("history", history(s))
        data.put("flashcards", JSONArray(cards.map(FlashcardStore::exportRow)))
        data.put("settings", settings(s))
        if (timing == VirlinTiming.RESET) resetTiming(data)
        return data
    }

    private suspend fun history(s: ProjectSnapshot): JSONObject {
        val ids = (s.streams.map { it.id } + s.tasks.map { it.id }).toSet()
        return JSONObject()
            .put("snapshots", JSONArray(ids.flatMap { repository.getSnapshots(it) }.map { snap ->
                ProjectArchive.json("id" to snap.id, "workStreamId" to snap.workStreamId, "cycleId" to snap.cycleId,
                    "createdAt" to snap.createdAt, "reason" to snap.reason, "lastHumanAction" to snap.lastHumanAction,
                    "waitingFor" to snap.waitingFor, "nextHumanAction" to snap.nextHumanAction, "checkAt" to snap.checkAt,
                    "contextLabel" to snap.contextLabel, "note" to snap.note, "taskId" to snap.taskId)
            }))
            .put("externalStages", JSONArray(s.stages.map { stage ->
                ProjectArchive.json("id" to stage.id, "workStreamId" to stage.workStreamId, "title" to stage.title,
                    "order" to stage.order, "expectedMinutes" to stage.expectedMinutes, "status" to stage.status,
                    "startedAt" to stage.startedAt, "completedAt" to stage.completedAt)
            }))
            .put("focusSessions", JSONArray(repository.allFocusSessions().filter { it.workStreamId in ids || it.taskId in ids }.map { session ->
                ProjectArchive.json("id" to session.id, "workStreamId" to session.workStreamId, "taskId" to session.taskId,
                    "cycleId" to session.cycleId, "startedAt" to session.startedAt, "endedAt" to session.endedAt)
            }))
            .put("cycles", JSONArray(repository.allCycles().filter { it.workStreamId in ids }.map { cycle ->
                ProjectArchive.json("id" to cycle.id, "workStreamId" to cycle.workStreamId, "number" to cycle.number,
                    "startedAt" to cycle.startedAt, "handedOffAt" to cycle.handedOffAt, "endedAt" to cycle.endedAt)
            }))
            .put("events", JSONArray(ids.flatMap { repository.getEvents(it) }.map { event ->
                ProjectArchive.json("id" to event.id, "workStreamId" to event.workStreamId, "type" to event.type,
                    "at" to event.at, "cycleId" to event.cycleId, "fromState" to event.fromState, "toState" to event.toState, "detail" to event.detail)
            }))
            .put("externalIntervals", JSONArray(repository.externalRunIntervals().filter { it.ownerId in ids }.map {
                ProjectArchive.json("ownerId" to it.ownerId, "taskId" to it.taskId, "startedAt" to it.start, "endedAt" to it.end)
            }))
    }

    /** Per-project settings kept outside the database, each as its exact saved value and type. */
    private suspend fun settings(s: ProjectSnapshot): JSONObject {
        val owners = (listOf(s.project.id) + s.streams.map { it.id } + s.tasks.map { it.id }).toSet()
        val notes = s.richNotes.map { it.ownerKey }.toSet()
        fun prefs(name: String, keep: (String) -> Boolean) = JSONObject().also { out ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (value != null && keep(key)) out.put(key, JSONObject().put("type", when (value) {
                    is Boolean -> "boolean"; is Int -> "int"; is Long -> "long"; is Float -> "float"
                    is Set<*> -> "stringSet"; else -> "string"
                }).put("value", if (value is Set<*>) JSONArray(value.map { it.toString() }) else value))
            }
        }
        return JSONObject()
            .put("projectAppearance", prefs("project_appearance") { it in owners })
            .put("timerDisplay", prefs("hierarchy_timer_display") { it in owners || it == "project:${s.project.id}" })
            .put("workstreamBlocks", prefs("project_block_order") { it in owners })
            .put("notePageSetup", prefs("note_page_setup") { it in notes || it.removeSuffix("#on") in notes })
            .put("notePositions", prefs("note_positions") { it in notes })
            .put("mapAppearance", MapAppearanceStore.get(context).exportJson(s.project.id) ?: JSONObject.NULL)
            .put("flashcardDecks", FlashcardDeckStyleStore.entriesFor(context, owners))
    }

    /** Time spent becomes zero; the work, its states, notes and activity stay exactly as they are. */
    private fun resetTiming(data: JSONObject) {
        fun JSONObject.clear() { put("externalTrackedSeconds", 0); put("processingStartedAt", JSONObject.NULL) }
        data.getJSONArray("workstreams").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).clear() }
        data.getJSONArray("tasks").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).optJSONObject("execution")?.clear() }
        data.getJSONObject("history").put("focusSessions", JSONArray()).put("externalIntervals", JSONArray())
    }

    private fun header(s: ProjectSnapshot, timing: VirlinTiming, now: Instant, data: ByteArray,
                       media: List<Measured>, missing: List<String>, json: JSONObject): JSONObject {
        val history = json.getJSONObject("history")
        return ProjectArchive.json(
            "format" to VirlinProjectFile.FORMAT, "formatVersion" to VirlinProjectFile.FORMAT_VERSION,
            "mimeType" to VirlinProjectFile.MIME_TYPE, "exportedAt" to now,
            "generator" to ProjectArchive.json("app" to "Virlin",
                "versionName" to (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: BuildConfig.VERSION_NAME),
                "versionCode" to BuildConfig.VERSION_CODE, "databaseSchema" to VirlinProjectFile.DATABASE_SCHEMA),
            "project" to ProjectArchive.json("id" to s.project.id, "title" to s.project.title),
            "timing" to ProjectArchive.json("mode" to timing, "meaning" to when (timing) {
                VirlinTiming.KEEP -> "Every focus session, external run and tracked second as recorded. Open sessions and running clocks are open as of exportedAt."
                VirlinTiming.RESET -> "Time spent is zero: no focus sessions, external runs or tracked seconds. Work that was running restarts its clock when imported."
            }),
            "data" to ProjectArchive.json("entry" to VirlinProjectFile.DATA_ENTRY, "bytes" to data.size.toLong(),
                "sha256" to with(VirlinProjectFile) { MessageDigest.getInstance("SHA-256").digest(data).hex() }),
            "contents" to ProjectArchive.json("workstreams" to s.streams.size, "tasks" to s.tasks.size,
                "steps" to s.steps.size, "pageBlocks" to s.pageBlocks.size, "notes" to s.richNotes.size + s.notes.size,
                "captures" to s.captures.size, "prompts" to s.prompts.size, "attachments" to s.attachments.size,
                "voiceNotes" to s.voices.size, "mapPreviews" to s.previews.size, "tags" to s.tags.size,
                "flashcards" to json.getJSONArray("flashcards").length(),
                "focusSessions" to history.getJSONArray("focusSessions").length(),
                "events" to history.getJSONArray("events").length()),
            "media" to ProjectArchive.json("files" to media.size, "bytes" to media.sumOf { it.bytes },
                "items" to JSONArray(media.map { m -> ProjectArchive.json("store" to m.media.store, "path" to m.media.path,
                    "entry" to m.media.entry, "bytes" to m.bytes, "sha256" to m.sha256,
                    "modifiedAt" to (if (m.modified > 0) Instant.ofEpochMilli(m.modified) else null),
                    "kind" to m.kind, "compression" to if (m.stored) "stored" else "deflate") }),
                "missing" to JSONArray(missing)),
        )
    }

    private companion object {
        const val README = "Virlin project file (.virlin), format 1.\n\n" +
            "One project, complete: workstreams, tasks, Pages, notes, captures, mind map, flashcards, activity history, " +
            "per-project settings and every media file byte for byte (see virlin.json for each file's SHA-256).\n" +
            "Open it with Virlin to import the project. It is a ZIP container, so its parts can also be inspected with any ZIP tool; " +
            "data/summary.md is a readable overview.\n\n" +
            "The file may contain private information. Keep it somewhere you trust."
    }
}
