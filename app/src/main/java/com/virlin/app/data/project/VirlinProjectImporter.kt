package com.virlin.app.data.project

import android.content.Context
import com.virlin.app.data.flashcard.FlashcardDeckStyleStore
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.data.flashcard.SavedFlashcard
import com.virlin.app.data.noteaudio.NoteAudioStore
import com.virlin.app.data.noteimage.NoteImageStore
import com.virlin.app.domain.mappreview.MapNodePreview
import com.virlin.app.domain.mappreview.MapPreviewCodec
import com.virlin.app.domain.model.*
import com.virlin.app.domain.note.NoteDocumentCodec
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import com.virlin.app.domain.notedoc.rich.RichNoteFormat
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.ui.map.MapAppearanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What a checked `.virlin` file will add, shown before anything is written. */
data class VirlinImportPreview(
    val summary: VirlinFileSummary,
    val exportedAt: Instant?,
    val appVersion: String?,
    val workstreams: Int,
    val tasks: Int,
    val steps: Int,
    val notes: Int,
    val captures: Int,
    val flashcards: Int,
    val mapPreviews: Int,
    val focusSessions: Int,
    val events: Int,
    /** Workstreams and tasks that were in Focus; they arrive Ready so the current Focus is never taken. */
    val leavesFocus: Int,
)

data class VirlinImportResult(val project: Project, val warnings: List<String>)

/**
 * Reads a `.virlin` file ([VirlinProjectFile] format 1) back into a new project.
 *
 * The file is proven whole first, then decoded completely before anything is written, so a file
 * that cannot be imported changes nothing. Every record gets a new id: importing the same file twice,
 * or onto the device it came from, adds a second project and never touches the original.
 * Media files are copied byte for byte (checked against the file's SHA-256) before one atomic
 * database commit; if the commit fails, every copied file and card is taken back.
 */
class VirlinProjectImporter(
    private val context: Context,
    private val repository: WorkStreamRepository,
    private val clock: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val tools = ProjectStorageTools(context, repository)

    suspend fun inspect(file: File): VirlinImportPreview = withContext(Dispatchers.IO) {
        readable {
            val summary = VirlinProjectFile.verify(file)
            val (header, data) = read(file)
            val plan = plan(header, data, summary.projectTitle.ifBlank { "Imported project" }.take(60), clock())
            val contents = header.optJSONObject("contents")
            VirlinImportPreview(summary, header.optString("exportedAt").takeIf { it.isNotBlank() }?.let(Instant::parse),
                header.optJSONObject("generator")?.optString("versionName")?.takeIf { it.isNotBlank() },
                plan.streams.size, plan.tasks.size, plan.steps.size, plan.richNotes.size + plan.notes.size,
                plan.captures.size, plan.cards.size, plan.previews.size, plan.sessions.size,
                contents?.optInt("events", plan.events.size) ?: plan.events.size, plan.leavesFocus)
        }
    }

    suspend fun import(file: File, title: String): VirlinImportResult = withContext(Dispatchers.IO) {
        val name = title.trim()
        require(name.isNotEmpty() && name.length <= 60) { "Enter a project name of 1–60 characters." }
        val plan = readable {
            VirlinProjectFile.verify(file)
            val (header, data) = read(file)
            plan(header, data, name, clock())
        }
        val created = mutableListOf<File>()
        var cardsAdded = false
        var committed = false
        try {
            ZipFile(file).use { zip -> plan.media.forEach { copy(zip, it, created) } }
            FlashcardStore.importCards(context, plan.cards); cardsAdded = true
            repository.transaction {
                require(getProject(plan.project.id) == null) { "Project already exists." }
                saveProject(plan.project)
                plan.streams.forEach { saveStream(it) }
                val remaining = plan.tasks.toMutableList(); val written = mutableSetOf<String>()
                while (remaining.isNotEmpty()) {
                    val ready = remaining.filter { it.parentTaskId == null || it.parentTaskId in written }
                    require(ready.isNotEmpty()) { "The project's tasks form a loop. Nothing was imported." }
                    ready.forEach { saveTask(it); written += it.id }; remaining.removeAll(ready.toSet())
                }
                plan.stages.forEach { saveStage(it) }
                plan.cycles.forEach { saveCycle(it) }
                plan.sessions.forEach { saveFocusSession(it) }
                plan.snapshots.forEach { saveSnapshot(it) }
                plan.events.forEach { appendEvent(it) }
                plan.runs.forEach { saveExternalRun(it) }
                plan.captures.forEach { saveCapture(it) }
                plan.notes.forEach { saveNoteDocument(it) }
                plan.prompts.forEach { savePromptDocument(it) }
                plan.attachments.forEach { saveAttachmentDocument(it) }
                plan.voices.forEach { saveVoiceDocument(it) }
                plan.richNotes.forEach { saveNoteDoc(it) }
                plan.steps.forEach { saveStep(it) }
                plan.tags.forEach { saveTag(it) }
                plan.links.forEach { saveLink(it) }
                plan.pageBlocks.forEach { savePageBlock(it) }
                plan.previews.forEach { saveMapNodePreview(it) }
            }
            committed = true
        } finally {
            if (!committed) {
                created.forEach { it.delete() }
                if (cardsAdded) runCatching { FlashcardStore.removeCards(context, plan.cards.map { it.id }.toSet()) }
            }
        }
        VirlinImportResult(plan.project, restoreSettings(plan))
    }

    // ---- Reading -------------------------------------------------------------------------------

    private inline fun <T> readable(block: () -> T): T = try { block() } catch (e: ZipException) {
        throw IllegalArgumentException("Not a Virlin project file.", e)
    } catch (e: JSONException) {
        throw IllegalArgumentException("The project data could not be read. Nothing was imported.", e)
    } catch (e: java.time.format.DateTimeParseException) {
        throw IllegalArgumentException("The project data has an invalid date. Nothing was imported.", e)
    }

    private fun read(file: File): Pair<JSONObject, JSONObject> = ZipFile(file).use { zip ->
        fun text(name: String) = zip.getInputStream(requireNotNull(zip.getEntry(name)) { "The file is incomplete ($name)." })
            .use { it.readBytes() }.toString(Charsets.UTF_8)
        JSONObject(text(VirlinProjectFile.HEADER_ENTRY)) to JSONObject(text(VirlinProjectFile.DATA_ENTRY))
    }

    private class MediaCopy(val entry: String, val dest: File, val bytes: Long, val sha256: String, val modified: Long)

    private class Plan(
        val project: Project, val streams: List<WorkStream>, val tasks: List<Task>,
        val captures: List<CaptureItem>, val notes: List<NoteDocument>, val prompts: List<PromptDocument>,
        val attachments: List<AttachmentDocument>, val voices: List<VoiceDocument>, val richNotes: List<VirlinNoteDoc>,
        val pageBlocks: List<TaskPageBlock>, val steps: List<TaskStep>, val tags: List<ProjectTag>, val links: List<TagLink>,
        val previews: List<MapNodePreview>, val stages: List<ExternalStage>, val cycles: List<Cycle>,
        val sessions: List<FocusSession>, val snapshots: List<ContextSnapshot>, val events: List<WorkStreamEvent>,
        val runs: List<ExternalRunInterval>, val cards: List<SavedFlashcard>, val media: List<MediaCopy>,
        val settings: JSONObject?, val leavesFocus: Int,
        val id: (String) -> String?, val owner: (String) -> String, val token: (String) -> String,
    )

    /** Decodes the whole file into new records. Pure apart from read-only checks of existing note media. */
    private fun plan(header: JSONObject, data: JSONObject, title: String, now: Instant): Plan {
        val p = data.getJSONObject("project")
        val sourceProject = p.getString("id")
        val timing = VirlinTiming.valueOf(header.getJSONObject("timing").getString("mode"))
        val exportedAt = header.t("exportedAt") ?: now
        val shift = Duration.between(exportedAt, now).takeIf { !it.isNegative } ?: Duration.ZERO
        val history = data.optJSONObject("history") ?: JSONObject()

        val ids = HashMap<String, String>()
        fun id(old: String) = ids.getOrPut(old, newId)
        fun ref(old: String?) = old?.let { ids[it] }
        fun need(old: String, what: String) = requireNotNull(ids[old]) { "The file refers to a missing $what. Nothing was imported." }
        val projectId = id(sourceProject)
        fun register(array: JSONArray?, key: String = "id") = array.objects().forEach { id(it.getString(key)) }
        listOf("workstreams", "tasks", "captures", "notes", "prompts", "attachments", "voices", "pageBlocks", "steps", "tags")
            .forEach { register(data.optJSONArray(it)) }
        data.optJSONArray("voices").objects().forEach { register(it.optJSONArray("clips")) }
        listOf("externalStages", "cycles", "snapshots", "events").forEach { register(history.optJSONArray(it)) }
        history.optJSONArray("focusSessions").objects().forEach { s -> s.getString("id").split('/').forEach(::id) }

        // Ids also appear inside text (note links, owner keys, map settings): UUID-shaped ones are replaced there too.
        val text = HashMap<String, String>()
        ids.forEach { (old, new) -> if (UUID_PATTERN.matches(old)) text[old] = new }
        fun remapText(s: String) = UUID_PATTERN.replace(s) { m -> text[m.value] ?: m.value }
        fun token(s: String) = ids[s] ?: remapText(s)

        val owners = HashMap<String, String>()
        fun owner(key: String): String {
            val cut = key.indexOf('#').let { if (it < 0) key.length else it }
            val base = key.substring(0, cut)
            val mapped = when {
                base == "project-$sourceProject" -> "project-$projectId"
                base.startsWith("task-") && base.removePrefix("task-") in ids -> TaskPageTypeKeys.noteOwner(ids.getValue(base.removePrefix("task-")))
                base.startsWith("capture-") && base.removePrefix("capture-") in ids -> CaptureNoteBridge.owner(ids.getValue(base.removePrefix("capture-")))
                else -> owners.getOrPut(base) { "copy-${newId()}" }
            }
            return mapped + key.substring(cut)
        }

        // Media: managed stores move under the new owner's folder; note media keep their names unless taken.
        val paths = HashMap<Pair<String, String>, String>()
        val folders = HashMap<String, String>()
        fun mediaPath(store: String, path: String): String = paths.getOrPut(store to path) {
            val first = path.substringBefore('/', "")
            if (first.isEmpty()) "${newId()}/$path" else (ids[first] ?: folders.getOrPut(first, newId)) + "/" + path.substringAfter('/')
        }
        val items = header.getJSONObject("media").getJSONArray("items").objects()
        val media = ArrayList<MediaCopy>()
        items.filter { it.getString("store") in NOTE_STORES }.forEach { item ->
            val store = item.getString("store"); val name = item.getString("path")
            val existing = noteFile(store, name) ?: throw IllegalArgumentException("A note file has an invalid name: $name")
            when {
                !existing.exists() -> media += item.copyTo(existing)
                existing.isFile && existing.length() == item.getLong("bytes") &&
                    existing.inputStream().use(VirlinProjectFile::sha256) == item.getString("sha256") -> Unit // identical file already here
                else -> {
                    val old = requireNotNull(UUID_PATTERN.find(name)) { "A note file has an invalid name: $name" }.value
                    val renamed = name.replace(old, text.getOrPut(old, newId))
                    media += item.copyTo(requireNotNull(noteFile(store, renamed)))
                }
            }
        }

        val streamsJson = data.getJSONArray("workstreams").objects()
        val tasksJson = data.getJSONArray("tasks").objects()
        val streamIds = streamsJson.map { it.getString("id") }.toSet()
        val taskIds = tasksJson.map { it.getString("id") }.toSet()
        require(tasksJson.all { t -> t.s("workStreamId").let { it == null || it in streamIds } &&
            t.s("parentTaskId").let { it == null || it in taskIds } }) { "The project contains invalid ownership links. Nothing was imported." }
        var leavesFocus = 0
        fun arrive(state: WorkStreamState) = if (state == WorkStreamState.FOCUS) { leavesFocus++; WorkStreamState.READY } else state
        /** KEEP: a running external clock resumes from what it showed at export. RESET: running work starts now. */
        fun clockOf(state: WorkStreamState, started: Instant?, paused: Boolean, checkAt: Instant?): Pair<Instant?, Instant?> = when {
            timing == VirlinTiming.KEEP && started != null && !paused -> started.plus(shift) to checkAt?.plus(shift)
            timing == VirlinTiming.RESET && started == null && !paused && state == WorkStreamState.PROCESSING -> now to checkAt
            else -> started to checkAt
        }

        val project = Project(id = projectId, title = title, description = p.s("description"), status = p.e("status"),
            priority = p.e("priority"), dueAt = p.t("dueAt"), estimatedEffort = p.s("estimatedEffort")?.let(Duration::parse),
            defaultExecutionMode = p.e("defaultExecutionMode"), createdAt = p.at("createdAt"), updatedAt = p.at("updatedAt"),
            completedAt = p.t("completedAt"), iconPath = p.s("iconPath")?.let { mediaPath("icons", it) }, iconId = p.s("iconId"))
        val streams = streamsJson.map { w ->
            val state = arrive(w.e("state"))
            val (started, checkAt) = clockOf(state, w.t("processingStartedAt"), w.getBoolean("externalTrackingPaused"), w.t("checkAt"))
            WorkStream(id = need(w.getString("id"), "workstream"), title = w.getString("title"), projectId = projectId,
                tool = w.s("tool"), externalActorId = w.s("externalActorId"), executionPreference = w.e("executionPreference"),
                state = state, priority = w.e("priority"), pinned = w.getBoolean("pinned"), attentionRank = w.i("attentionRank"),
                sortOrder = w.getInt("sortOrder"), lastHumanAction = w.s("lastHumanAction"), waitingFor = w.s("waitingFor"),
                nextHumanAction = w.s("nextHumanAction"), blockerReason = w.s("blockerReason"), processingStartedAt = started,
                externalTrackedSeconds = w.getLong("externalTrackedSeconds"), externalTrackingPaused = w.getBoolean("externalTrackingPaused"),
                checkAt = checkAt, snoozedUntil = w.t("snoozedUntil"), snoozeReason = w.eOrNull<SnoozeReason>("snoozeReason"),
                currentCycleId = ref(w.s("currentCycleId")), cycleCount = w.getInt("cycleCount"), activeTaskId = ref(w.s("activeTaskId")),
                createdAt = w.at("createdAt"), updatedAt = w.at("updatedAt"), completedAt = w.t("completedAt"))
        }
        val tasks = tasksJson.map { t ->
            val execution = t.optJSONObject("execution")?.let { a ->
                val state = arrive(a.e("state"))
                val (started, checkAt) = clockOf(state, a.t("processingStartedAt"), a.getBoolean("externalTrackingPaused"), a.t("checkAt"))
                TaskExecution(state = state, processingStartedAt = started, externalTrackedSeconds = a.getLong("externalTrackedSeconds"),
                    externalTrackingPaused = a.getBoolean("externalTrackingPaused"), checkAt = checkAt,
                    snoozeReason = a.eOrNull<SnoozeReason>("snoozeReason"), blockerReason = a.s("blockerReason"),
                    currentCycleId = ref(a.s("currentCycleId")), cycleCount = a.getInt("cycleCount"), actorId = a.s("actorId"),
                    tool = a.s("tool"), instruction = a.s("instruction"), attentionRank = a.i("attentionRank"))
            }
            Task(id = need(t.getString("id"), "task"), title = t.getString("title"), description = t.s("description"),
                projectId = t.s("projectId")?.let { if (it == sourceProject) projectId else ids[it] },
                workStreamId = ref(t.s("workStreamId")), parentTaskId = ref(t.s("parentTaskId")), status = t.e("status"),
                order = t.getInt("order"), estimatedEffort = t.s("estimatedEffort")?.let(Duration::parse), dueAt = t.t("dueAt"),
                reminderAt = t.t("reminderAt"), priority = t.e("priority"), notes = t.s("notes"),
                executionPreference = t.e("executionPreference"), createdAt = t.at("createdAt"), updatedAt = t.at("updatedAt"),
                completedAt = t.t("completedAt"), execution = execution)
        }
        val captures = data.optJSONArray("captures").objects().map { c ->
            CaptureItem(id = need(c.getString("id"), "capture"), type = c.e("type"), content = c.getString("content"),
                title = c.s("title"), sourceUrl = c.s("sourceUrl"),
                projectId = c.s("projectId")?.let { if (it == sourceProject) projectId else ids[it] },
                workStreamId = ref(c.s("workStreamId")), taskId = ref(c.s("taskId")), status = c.e("status"),
                convertedTaskId = ref(c.s("convertedTaskId")), createdAt = c.at("createdAt"), updatedAt = c.at("updatedAt"),
                archivedAt = c.t("archivedAt"))
        }
        val notes = data.optJSONArray("notes").objects().map { n ->
            NoteDocument(id = need(n.getString("id"), "note"), captureItemId = need(n.getString("captureItemId"), "capture"),
                title = n.s("title"), blocks = NoteDocumentCodec.decodePayload(remapText(compact(n.getJSONObject("payload")))),
                createdAt = n.at("createdAt"), updatedAt = n.at("updatedAt"))
        }
        val prompts = data.optJSONArray("prompts").objects().map { n ->
            PromptDocument(id = need(n.getString("id"), "prompt"), captureItemId = need(n.getString("captureItemId"), "capture"),
                title = n.s("title"), description = n.s("description"),
                tags = n.optJSONArray("tags").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) } },
                blocks = NoteDocumentCodec.decodePayload(remapText(compact(n.getJSONObject("payload")))),
                sourceText = n.optString("sourceText"), mode = n.e("mode"), language = n.s("language"),
                responseText = n.s("responseText"), createdAt = n.at("createdAt"), updatedAt = n.at("updatedAt"),
                codeText = n.s("codeText"))
        }
        val attachments = data.optJSONArray("attachments").objects().map { a ->
            AttachmentDocument(id = need(a.getString("id"), "file"), captureItemId = need(a.getString("captureItemId"), "capture"),
                displayName = a.getString("displayName"), mimeType = a.getString("mimeType"), sizeBytes = a.getLong("sizeBytes"),
                relativePath = mediaPath("attachments", a.getString("relativePath")), kind = a.e("kind"),
                createdAt = a.at("createdAt"), updatedAt = a.at("updatedAt"))
        }
        val voices = data.optJSONArray("voices").objects().map { v ->
            VoiceDocument(id = need(v.getString("id"), "voice note"), captureItemId = need(v.getString("captureItemId"), "capture"),
                title = v.s("title"), createdAt = v.at("createdAt"), updatedAt = v.at("updatedAt"),
                clips = v.optJSONArray("clips").objects().map { c ->
                    VoiceClip(id = need(c.getString("id"), "recording"), displayName = c.getString("displayName"),
                        relativePath = mediaPath("voices", c.getString("relativePath")), durationMs = c.getLong("durationMs"),
                        sizeBytes = c.getLong("sizeBytes"), mimeType = c.getString("mimeType"), sortOrder = c.getInt("sortOrder"),
                        createdAt = c.at("createdAt"))
                })
        }
        val richNotes = data.optJSONArray("richNotes").objects().map { n ->
            val ownerKey = owner(n.getString("ownerKey"))
            val base = VirlinNoteDoc(ownerKey = ownerKey, title = n.s("title"), createdAt = n.at("createdAt"),
                updatedAt = n.at("updatedAt"), revision = n.optInt("revision", 0))
            val payload = n.optJSONObject("payload")
            if (payload == null) {
                base.copy(loadError = "This note could not be read when it was exported; its saved text is kept as it was.",
                    damagedRaw = remapText(n.s("damagedRaw").orEmpty()))
            } else when (val stored = RichNoteFormat.decode(remapText(compact(payload)))) {
                is RichNoteFormat.Stored.Rich -> base.copy(blocks = stored.blocks, richDocument = stored.document)
                is RichNoteFormat.Stored.Legacy -> base.copy(blocks = stored.blocks)
                is RichNoteFormat.Stored.Damaged -> base.copy(loadError = stored.reason, damagedRaw = stored.raw)
            }
        }
        val pageBlocks = data.optJSONArray("pageBlocks").objects().map { b ->
            val typeKey = b.getString("typeKey"); val contentId = b.getString("contentId")
            val content = when {
                typeKey == TaskPageTypeKeys.NOTE -> owner(contentId)
                typeKey == TaskPageTypeKeys.TODO || typeKey.startsWith("capture.") -> ids[contentId]
                else -> throw IllegalArgumentException("This file has Page content this version cannot import ($typeKey). Nothing was imported.")
            }
            TaskPageBlock(id = need(b.getString("id"), "Page block"), taskId = need(b.getString("taskId"), "task"), typeKey = typeKey,
                contentId = requireNotNull(content) { "A Page block refers to missing content. Nothing was imported." },
                order = b.getInt("order"), createdAt = b.at("createdAt"), updatedAt = b.at("updatedAt"))
        }
        val steps = data.optJSONArray("steps").objects().map { s ->
            TaskStep(id = need(s.getString("id"), "step"), taskId = need(s.getString("taskId"), "task"), text = s.getString("text"),
                done = s.getBoolean("done"), order = s.getInt("order"), createdAt = s.at("createdAt"), updatedAt = s.at("updatedAt"))
        }
        val tags = data.optJSONArray("tags").objects().map { t ->
            ProjectTag(id = need(t.getString("id"), "tag"), projectId = projectId, name = t.getString("name"),
                createdAt = t.at("createdAt"), updatedAt = t.at("updatedAt"))
        }
        val links = data.optJSONArray("tagLinks").objects().map { l ->
            TagLink(tagId = need(l.getString("tagId"), "tag"), targetType = l.e("targetType"),
                targetId = need(l.getString("targetId"), "tagged item"), createdAt = l.at("createdAt"))
        }
        val previews = data.optJSONArray("mapPreviews").objects().map { m ->
            val ownerId = need(m.getString("ownerId"), "mind-map node")
            MapPreviewCodec.decode(ownerId, compact(m.getJSONObject("payload"))).let { preview ->
                preview.copy(createdAt = m.t("createdAt"), updatedAt = m.t("updatedAt"),
                    images = preview.images.map { it.copy(relativePath = mediaPath("previews", it.relativePath)) })
            }
        }
        val stages = history.optJSONArray("externalStages").objects().map { s ->
            ExternalStage(id = need(s.getString("id"), "stage"), workStreamId = need(s.getString("workStreamId"), "workstream"),
                title = s.getString("title"), order = s.getInt("order"), expectedMinutes = s.l("expectedMinutes"),
                status = s.e("status"), startedAt = s.t("startedAt"), completedAt = s.t("completedAt"))
        }
        val cycles = history.optJSONArray("cycles").objects().map { c ->
            Cycle(id = need(c.getString("id"), "cycle"), workStreamId = need(c.getString("workStreamId"), "workstream"),
                number = c.getInt("number"), startedAt = c.at("startedAt"), handedOffAt = c.t("handedOffAt"), endedAt = c.t("endedAt"))
        }
        val sessions = history.optJSONArray("focusSessions").objects().map { s ->
            val started = s.at("startedAt")
            // Nothing imported is in Focus, so a session still open at export ends there.
            FocusSession(id = s.getString("id").split('/').joinToString("/") { ids.getValue(it) },
                workStreamId = need(s.getString("workStreamId"), "workstream"), cycleId = ref(s.s("cycleId")), startedAt = started,
                endedAt = s.t("endedAt") ?: maxOf(started, exportedAt), taskId = ref(s.s("taskId")))
        }
        val snapshots = history.optJSONArray("snapshots").objects().map { s ->
            ContextSnapshot(id = need(s.getString("id"), "snapshot"), workStreamId = need(s.getString("workStreamId"), "workstream"),
                cycleId = ref(s.s("cycleId")), createdAt = s.at("createdAt"), reason = s.e("reason"),
                lastHumanAction = s.s("lastHumanAction"), waitingFor = s.s("waitingFor"), nextHumanAction = s.s("nextHumanAction"),
                checkAt = s.t("checkAt"), contextLabel = s.s("contextLabel"), note = s.s("note"), taskId = ref(s.s("taskId")))
        }
        val events = history.optJSONArray("events").objects().map { e ->
            WorkStreamEvent(id = need(e.getString("id"), "event"), workStreamId = need(e.getString("workStreamId"), "workstream"),
                type = e.e("type"), at = e.at("at"), cycleId = ref(e.s("cycleId")), fromState = e.eOrNull<WorkStreamState>("fromState"),
                toState = e.eOrNull<WorkStreamState>("toState"), detail = e.s("detail")?.let(::remapText))
        }
        val runs = history.optJSONArray("externalIntervals").objects().map { r ->
            ExternalRunInterval(need(r.getString("ownerId"), "workstream"), ref(r.s("taskId")), r.at("startedAt"), r.at("endedAt"))
        }
        val cards = data.optJSONArray("flashcards").objects().mapIndexed { index, row ->
            val remapped = JSONObject(remapText(row.toString()))
                .put("id", newId()).put("ownerKey", owner(row.getString("ownerKey")))
            FlashcardStore.decodeRow(remapped, index)
        }
        items.filter { it.getString("store") !in NOTE_STORES }.forEach { item ->
            val store = item.getString("store")
            media += item.copyTo(tools.resolve(store, mediaPath(store, item.getString("path"))))
        }
        return Plan(project, streams, tasks, captures, notes, prompts, attachments, voices, richNotes, pageBlocks, steps, tags,
            links, previews, stages, cycles, sessions, snapshots, events, runs, cards, media, data.optJSONObject("settings"),
            leavesFocus, { ids[it] }, ::owner, ::token)
    }

    private fun noteFile(store: String, name: String): File? = when (store) {
        "note-images" -> NoteImageStore.resolve(context, name)
        "note-audio" -> NoteAudioStore.resolve(context, name)
        else -> null
    }

    private fun JSONObject.copyTo(dest: File) = MediaCopy(getString("entry"), dest, getLong("bytes"), getString("sha256"),
        t("modifiedAt")?.toEpochMilli() ?: 0L)

    // ---- Writing -------------------------------------------------------------------------------

    private fun copy(zip: ZipFile, m: MediaCopy, created: MutableList<File>) {
        check(!m.dest.exists()) { "A file is already in the way (${m.dest.name}). Nothing was imported." }
        val parent = requireNotNull(m.dest.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Could not prepare storage. Nothing was imported." }
        val entry = requireNotNull(zip.getEntry(m.entry)) { "A media file is missing from the package." }
        created += m.dest
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        zip.getInputStream(entry).use { input -> m.dest.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read); output.write(buffer, 0, read); bytes += read }
        } }
        check(bytes == m.bytes && with(VirlinProjectFile) { digest.digest().hex() } == m.sha256) {
            "A media file did not copy exactly (${m.dest.name}). Nothing was imported."
        }
        if (m.modified > 0) m.dest.setLastModified(m.modified)
    }

    /** Per-project presentation settings. The project is already safely imported, so a failure here is reported, not fatal. */
    private suspend fun restoreSettings(plan: Plan): List<String> {
        val settings = plan.settings ?: return emptyList()
        val warnings = mutableListOf<String>()
        suspend fun attempt(what: String, block: suspend () -> Unit) {
            runCatching { block() }.onFailure { warnings += "$what could not be restored." }
        }
        suspend fun prefs(name: String, field: String, key: (String) -> String?) = attempt("Some display settings") {
            val entries = settings.optJSONObject(field) ?: return@attempt
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            entries.keys().forEach { old ->
                val k = key(old) ?: return@forEach
                val e = entries.getJSONObject(old)
                when (e.getString("type")) {
                    "boolean" -> edit.putBoolean(k, e.getBoolean("value"))
                    "int" -> edit.putInt(k, e.getInt("value"))
                    "long" -> edit.putLong(k, e.getLong("value"))
                    "float" -> edit.putFloat(k, e.getDouble("value").toFloat())
                    "stringSet" -> edit.putStringSet(k, e.getJSONArray("value").let { a -> (0 until a.length()).map { plan.token(a.getString(it)) }.toSet() })
                    else -> edit.putString(k, plan.token(e.getString("value")))
                }
            }
            check(edit.commit())
        }
        prefs("project_appearance", "projectAppearance", plan.id)
        prefs("hierarchy_timer_display", "timerDisplay") { k ->
            if (k.startsWith("project:")) plan.id(k.removePrefix("project:"))?.let { "project:$it" } else plan.id(k)
        }
        prefs("project_block_order", "workstreamBlocks", plan.id)
        prefs("note_page_setup", "notePageSetup", plan.owner)
        prefs("note_positions", "notePositions", plan.owner)
        attempt("The mind map's colours and layout") {
            settings.optJSONObject("mapAppearance")?.let { MapAppearanceStore.get(context).importJson(plan.project.id, remap(it, plan.token) as JSONObject) }
        }
        attempt("Flashcard deck styles") {
            val decks = settings.optJSONObject("flashcardDecks") ?: return@attempt
            val out = JSONObject()
            decks.keys().forEach { key -> plan.id(key.substringAfter(':'))?.let { out.put(key.substringBefore(':') + ":" + it, decks.getJSONObject(key)) } }
            FlashcardDeckStyleStore.importEntries(context, out)
        }
        return warnings.distinct()
    }

    private fun remap(value: Any?, token: (String) -> String): Any? = when (value) {
        is JSONObject -> JSONObject().also { out -> value.keys().forEach { k -> out.put(token(k), remap(value.get(k), token)) } }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(remap(value.get(i), token)) }
        is String -> token(value)
        else -> value
    }

    private companion object {
        val NOTE_STORES = setOf("note-images", "note-audio")
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
        fun JSONObject.s(key: String): String? = if (isNull(key)) null else getString(key)
        fun JSONObject.t(key: String): Instant? = s(key)?.let(Instant::parse)
        fun JSONObject.at(key: String): Instant = Instant.parse(getString(key))
        fun JSONObject.i(key: String): Int? = if (isNull(key)) null else getInt(key)
        fun JSONObject.l(key: String): Long? = if (isNull(key)) null else getLong(key)
        inline fun <reified E : Enum<E>> JSONObject.e(key: String): E = enumOf(getString(key))
        inline fun <reified E : Enum<E>> JSONObject.eOrNull(key: String): E? = s(key)?.let { enumOf<E>(it) }
        inline fun <reified E : Enum<E>> enumOf(name: String): E = enumValues<E>().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("This file uses a value this version does not know ($name). Nothing was imported.")

        /** Compact JSON text for the app's own note and preview readers; `/` is left unescaped. */
        fun compact(value: Any?): String = StringBuilder().also { write(it, value) }.toString()
        private fun write(out: StringBuilder, value: Any?) {
            when (value) {
                null, JSONObject.NULL -> out.append("null")
                is JSONObject -> { out.append('{'); var first = true
                    value.keys().forEach { k -> if (!first) out.append(','); first = false; quote(out, k); out.append(':'); write(out, value.get(k)) }
                    out.append('}') }
                is JSONArray -> { out.append('['); for (i in 0 until value.length()) { if (i > 0) out.append(','); write(out, value.get(i)) }; out.append(']') }
                is String -> quote(out, value)
                is Boolean, is Int, is Long -> out.append(value.toString())
                is Number -> { val d = value.toDouble(); require(d.isFinite()) { "Invalid number in note data." }; out.append(value.toString()) }
                else -> quote(out, value.toString())
            }
        }
        private fun quote(out: StringBuilder, s: String) {
            out.append('"')
            s.forEach { c -> when (c) {
                '"' -> out.append("\\\""); '\\' -> out.append("\\\\"); '\n' -> out.append("\\n"); '\r' -> out.append("\\r"); '\t' -> out.append("\\t")
                '\b' -> out.append("\\b"); '\u000C' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            } }
            out.append('"')
        }
    }
}
