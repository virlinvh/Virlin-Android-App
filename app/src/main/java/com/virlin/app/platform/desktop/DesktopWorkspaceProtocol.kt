package com.virlin.app.platform.desktop

import android.content.Context
import com.virlin.app.data.voice.VoiceFileStore
import com.virlin.app.data.noteimage.NoteImageStore
import android.graphics.BitmapFactory
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.BulkHierarchy
import com.virlin.app.domain.action.CreateProject
import com.virlin.app.domain.action.CreateTask
import com.virlin.app.domain.action.CreateWorkStream
import com.virlin.app.domain.action.CreateCapture
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.CaptureUpdate
import com.virlin.app.domain.action.Field
import com.virlin.app.domain.action.TaskUpdate
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.model.ProgressResult
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureStatus
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.PromptContentMode
import com.virlin.app.domain.capture.LinkDocumentCodec
import com.virlin.app.domain.capture.LinkUrl
import com.virlin.app.domain.model.editToken
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.model.ProjectIconCatalog
import com.virlin.app.ui.hierarchy.ProjectPalette
import com.virlin.app.domain.model.ExecutionPreference
import com.virlin.app.domain.model.ExecutionModeResolver
import com.virlin.app.domain.model.effectiveAttentionState
import com.virlin.app.domain.progress.ProgressCalculator
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

/** Versioned, allowlisted requests over one authenticated Virlin Link session. */
class DesktopWorkspaceProtocol(
    private val repository: WorkStreamRepository? = null,
    private val actions: VirlinActions? = null,
    private val filesContext: Context? = null,
) {
    private val maxRequestBytes = 512 * 1024
    private val maxResponseBytes = 1024 * 1024

    fun readLine(input: InputStream, limit: Int): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else throw IOException("Incomplete workspace message")
            if (next == '\n'.code) return bytes.toString(Charsets.UTF_8.name())
            if (bytes.size() >= limit) throw IOException("Workspace message too large")
            bytes.write(next)
        }
    }

    fun writeLine(output: OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > maxResponseBytes) throw IOException("Project list is too large for this workspace version")
        output.write(bytes)
        output.write('\n'.code)
        output.flush()
    }

    private val receipts = LinkedHashMap<String, JSONObject>()
    private data class BulkDraft(val key: String, val target: String, val ownerType: String?, val ownerId: String?, val source: String)
    private var bulkDraft: BulkDraft? = null
    private data class ImageUpload(val projectId: String, val file: File, val total: Long,
        val expectedHash: String, var received: Long = 0, var complete: Boolean = false)
    private val imageUploads = LinkedHashMap<String, ImageUpload>()

    /** Called when the authenticated socket ends; uncommitted media never survives as a usable note image. */
    fun close() {
        imageUploads.values.forEach { it.file.delete() }
        imageUploads.clear()
    }

    fun next(input: InputStream, output: OutputStream): Boolean {
        val raw = readLine(input, maxRequestBytes) ?: return false
        val request = try { JSONObject(raw) } catch (_: Exception) {
            writeLine(output, error("INVALID_REQUEST")); return true
        }
        if (request.optInt("v") != 1) {
            writeLine(output, error("UNSUPPORTED_OPERATION")); return true
        }
        val result = try { when (request.optString("type")) {
            "get_capabilities" -> JSONObject().put("v", 1).put("type", "capabilities")
                .put("knowledgeCreate", JSONArray(listOf("note", "checklist", "steps", "prompt", "link")))
                .put("knowledgeDetail", true).put("knowledgeNoteEdit", true)
                .put("knowledgePromptEdit", true)
                .put("knowledgePromptSections", true)
                .put("knowledgeLinkEdit", true)
                .put("knowledgeAudioPlayback", filesContext != null)
                .put("knowledgeAudioTransfer", false)
                .put("knowledgeImageTransfer", filesContext != null)
            "get_tree" -> tree()
            "get_map_appearance" -> mapAppearance(request)
            "set_map_appearance" -> setMapAppearance(request)
            "map_place" -> mapPlace(request)
            "map_delete" -> mapDelete(request)
            "map_rename" -> mapRename(request)
            "get_knowledge" -> knowledge(request)
            "get_knowledge_item" -> knowledgeItem(request)
            "read_voice_chunk" -> readVoiceChunk(request)
            "upload_note_image" -> uploadNoteImage(request)
            "read_note_image_chunk" -> readNoteImageChunk(request)
            "create_knowledge" -> createKnowledge(request)
            "save_knowledge_note" -> saveKnowledgeNote(request)
            "save_knowledge_prompt" -> saveKnowledgePrompt(request)
            "save_knowledge_link" -> saveKnowledgeLink(request)
            "create_project", "create_workstream", "create_task" -> create(request)
            "task_action" -> taskAction(request)
            "reorder_task" -> reorderTask(request)
            "focus_preview" -> focusPreview(request)
            "focus_start" -> focusStart(request)
            "preview_bulk" -> previewBulk(request)
            "apply_bulk" -> applyBulk(request)
            else -> error("UNSUPPORTED_OPERATION")
        } } catch (failure: Exception) {
            android.util.Log.e("VirlinDesktopLink", "Workspace request failed: ${request.optString("type")}", failure)
            error("ACTION_FAILED")
        }
        writeLine(output, result)
        return true
    }

    private fun previewBulk(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        val target = request.optString("target")
        if (target !in setOf("projects", "workstreams", "tasks", "subtasks")) return error("INVALID_TARGET")
        val ownerType = optionalString(request, "ownerType")
        if ((target == "projects" && ownerType != null) ||
            (target == "workstreams" && ownerType != "project") ||
            (target == "subtasks" && ownerType != "task") ||
            (target == "tasks" && ownerType !in setOf("project", "workstream"))) return error("INVALID_TARGET")
        val ownerId = optionalString(request, "ownerId")
        if ((target == "projects") != (ownerId == null) ||
            (ownerId != null && (ownerId.length > 128 || !ownerId.matches(Regex("[a-zA-Z0-9_-]+"))))) return error("INVALID_OWNER")
        val source = request.optString("source")
        val nodes = try { BulkHierarchy.parse(source) } catch (e: Exception) {
            return error("INVALID_BULK").put("detail", (e.message ?: "Invalid import").take(200))
        }
        bulkDraft = BulkDraft(key, target, ownerType, ownerId, source)
        return JSONObject().put("v", 1).put("type", "bulk_preview")
            .put("count", nodes.sumOf { it.count })
            .put("lines", JSONArray(nodes.flatMap { it.preview(rootType = target) }))
    }

    private fun applyBulk(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val draft = bulkDraft?.takeIf { it.key == key } ?: return error("PREVIEW_REQUIRED")
        val action = actions ?: return error("NOT_READY")
        val outcome = runBlocking {
            when (draft.target) {
                "projects" -> action.bulkCreateNames(draft.source, null)
                "workstreams" -> action.bulkCreateNames(draft.source, draft.ownerId)
                "tasks" -> action.bulkCreateTasks(draft.source, if (draft.ownerType == "project")
                    CreateTask("", projectId = draft.ownerId) else CreateTask("", workStreamId = draft.ownerId))
                "subtasks" -> action.bulkCreateTasks(draft.source, CreateTask("", parentTaskId = draft.ownerId))
                else -> ActionResult.Rejected(com.virlin.app.domain.action.DomainError.OwnershipMismatch)
            }
        }
        val receipt = when (outcome) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "bulk_created").put("count", outcome.value)
            is ActionResult.Rejected -> error("REJECTED_${outcome.reason.javaClass.simpleName}").apply {
                if (outcome.reason is com.virlin.app.domain.action.DomainError.PlacementRejected)
                    put("detail", outcome.reason.reason)
            }
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("IMPORT_FAILED")
        }
        if (receipt.optString("type") == "bulk_created") {
            bulkDraft = null
            rememberReceipt(key, receipt)
        }
        return receipt
    }

    private fun validKey(key: String) = key.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))

    private fun mapAppearanceJson(projectId: String, value: com.virlin.app.ui.map.MapAppearance) =
        JSONObject().put("v", 1).put("type", "map_appearance").put("projectId", projectId)
            .put("hierarchyRevision", (actions?.let { runBlocking { it.hierarchyRevision(projectId) } } ?: 0L).toString())
            .put("paletteIndex", value.paletteIndex)
            .put("customPalette", JSONArray(value.customPalette))
            .put("nodeColors", JSONObject().also { o -> value.nodeColors.forEach { (id, color) -> o.put(id, color) } })
            .put("branchColors", JSONObject().also { o -> value.branchColors.forEach { (id, color) -> o.put(id, color) } })
            .put("nodeWidths", JSONObject().also { o -> value.nodeWidths.forEach { (id, width) -> o.put(id, width.toDouble()) } })

    private fun mapAppearance(request: JSONObject): JSONObject {
        val projectId = request.optString("projectId")
        val repo = repository ?: return error("NOT_READY")
        val context = filesContext ?: return error("NOT_READY")
        if (repo.projects.value.none { it.id == projectId }) return error("NOT_FOUND")
        return mapAppearanceJson(projectId, runBlocking {
            com.virlin.app.ui.map.MapAppearanceStore.get(context).snapshot(projectId)
        })
    }

    private fun setMapAppearance(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val projectId = request.optString("projectId")
        val nodeId = request.optString("nodeId")
        val repo = repository ?: return error("NOT_READY")
        val context = filesContext ?: return error("NOT_READY")
        if (repo.projects.value.none { it.id == projectId }) return error("NOT_FOUND")
        if (nodeId.isBlank() || nodeId.length > 128 || !nodeId.matches(Regex("[a-zA-Z0-9_-]+")))
            return error("INVALID_NODE")
        val belongs = nodeId == projectId || repo.streams.value.any { it.id == nodeId && it.projectId == projectId } ||
            repo.tasks.value.any { it.id == nodeId && it.projectId == projectId }
        if (!belongs) return error("INVALID_NODE")
        val operation = request.optString("operation")
        if (operation !in setOf("color", "width")) return error("UNSUPPORTED_OPERATION")
        val color = if (request.isNull("color")) null else request.optLong("color", -1)
        val width = if (request.isNull("width")) null else request.optDouble("width", Double.NaN).toFloat()
        val expectedColor = if (request.isNull("expectedColor")) null else request.optLong("expectedColor", -1)
        val expectedWidth = if (request.isNull("expectedWidth")) null else request.optDouble("expectedWidth", Double.NaN).toFloat()
        if (operation == "color" && color != null && color !in 0xFF000000L..0xFFFFFFFFL)
            return error("INVALID_COLOR")
        if (operation == "width" && width != null && (!width.isFinite() || width !in 110f..560f))
            return error("INVALID_WIDTH")
        if (request.optBoolean("hasExpected") && operation == "color" &&
            expectedColor != null && expectedColor !in 0xFF000000L..0xFFFFFFFFL)
            return error("INVALID_COLOR")
        if (request.optBoolean("hasExpected") && operation == "width" &&
            expectedWidth != null && (!expectedWidth.isFinite() || expectedWidth !in 110f..560f))
            return error("INVALID_WIDTH")
        val value = try { runBlocking { com.virlin.app.ui.map.MapAppearanceStore.get(context)
            .update(projectId) { old ->
                if (request.optBoolean("hasExpected")) {
                    val current = if (operation == "color") old.nodeColors[nodeId] else old.nodeWidths[nodeId]
                    val expected = if (operation == "color") expectedColor else expectedWidth
                    if (current != expected) throw IllegalStateException("STALE_MAP_APPEARANCE")
                }
                when (operation) {
                "color" -> old.copy(nodeColors = if (color == null) old.nodeColors - nodeId
                    else old.nodeColors + (nodeId to color))
                else -> old.copy(nodeWidths = if (width == null) old.nodeWidths - nodeId
                    else old.nodeWidths + (nodeId to width))
                }
            } } } catch (e: IllegalStateException) {
            if (e.message == "STALE_MAP_APPEARANCE") return error("STALE_MAP_APPEARANCE")
            throw e
        }
        return mapAppearanceJson(projectId, value).also { rememberReceipt(key, it) }
    }

    private fun mapPlace(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val projectId = request.optString("projectId")
        val sourceId = request.optString("sourceId")
        val targetParentId = request.optString("targetParentId")
        val beforeId = optionalString(request, "insertBeforeId")
        val operation = when (request.optString("operation")) {
            "MOVE" -> com.virlin.app.domain.structure.PlacementOperation.MOVE
            "COPY" -> com.virlin.app.domain.structure.PlacementOperation.COPY
            else -> return error("UNSUPPORTED_OPERATION")
        }
        val ids = listOf(projectId, sourceId, targetParentId) + listOfNotNull(beforeId)
        if (ids.any { it.isBlank() || it.length > 128 || !it.matches(Regex("[a-zA-Z0-9_-]+")) })
            return error("INVALID_NODE")
        val action = actions ?: return error("NOT_READY")
        val repo = repository ?: return error("NOT_READY")
        if (repo.projects.value.none { it.id == projectId }) return error("NOT_FOUND")
        val expected = request.optString("expectedRevision").toLongOrNull()
            ?: return error("INVALID_REVISION")
        val result = runBlocking { action.placeBranch(projectId,
            com.virlin.app.domain.structure.Placement(sourceId, targetParentId, beforeId,
                operation, expected)) }
        val response = when (result) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "map_placed")
                .put("projectId", projectId).put("operation", operation.name)
                .put("hierarchyRevision", runBlocking { action.hierarchyRevision(projectId) }.toString())
                .put("sourceId", sourceId).put("targetParentId", targetParentId)
                .put("affectedIds", JSONArray(result.value.affectedIds))
                .put("clonedIds", JSONObject(result.value.clonedIds))
                .put("previousParentTaskId", result.value.previousParentTaskId ?: JSONObject.NULL)
                .put("previousWorkStreamId", result.value.previousWorkStreamId ?: JSONObject.NULL)
                .put("previousInsertBeforeId", result.value.previousInsertBeforeId ?: JSONObject.NULL)
            is ActionResult.Rejected -> error("PLACEMENT_REJECTED").put("detail",
                (result.reason as? com.virlin.app.domain.action.DomainError.PlacementRejected)?.reason
                    ?: "Placement was refused")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        if (response.optString("type") == "map_placed") rememberReceipt(key, response)
        return response
    }

    private fun mapDelete(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val id = request.optString("nodeId")
        val kind = request.optString("kind")
        val projectId = request.optString("projectId")
        val typed = request.optString("confirmTitle")
        val repo = repository ?: return error("NOT_READY")
        if (kind !in setOf("project", "workstream", "task") || id.isBlank() ||
            id.length > 128 || !id.matches(Regex("[a-zA-Z0-9_-]+"))) return error("INVALID_NODE")
        val actual = when (kind) {
            "project" -> repo.projects.value.firstOrNull { it.id == id && it.id == projectId }?.title
            "workstream" -> repo.streams.value.firstOrNull { it.id == id && it.projectId == projectId }?.title
            else -> repo.tasks.value.firstOrNull { it.id == id && it.projectId == projectId }?.title
        } ?: return error("NOT_FOUND")
        if (typed != actual) return error("CONFIRMATION_MISMATCH")
        val latch = java.util.concurrent.CountDownLatch(1)
        var result: ActionResult<com.virlin.app.domain.action.ProjectDeletion>? = null
        com.virlin.app.domain.VirlinGraph.deleteHierarchy(id, kind) {
            result = it; latch.countDown()
        }
        if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) return error("DELETE_TIMEOUT")
        val receipt = when (result) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "map_deleted")
                .put("nodeId", id).put("projectId", projectId)
            is ActionResult.Rejected -> error("DELETE_REJECTED")
            is ActionResult.NotFound -> error("NOT_FOUND")
            else -> error("ACTION_FAILED")
        }
        if (receipt.optString("type") == "map_deleted") rememberReceipt(key, receipt)
        return receipt
    }

    private fun mapRename(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val id = request.optString("nodeId")
        val projectId = request.optString("projectId")
        val kind = request.optString("kind")
        val title = request.optString("title").trim()
        if (title.isEmpty() || title.length > 200) return error("INVALID_TITLE")
        val repo = repository ?: return error("NOT_READY")
        val belongs = when (kind) {
            "project" -> id == projectId && repo.projects.value.any { it.id == id }
            "workstream" -> repo.streams.value.any { it.id == id && it.projectId == projectId }
            "task" -> repo.tasks.value.any { it.id == id && it.projectId == projectId }
            else -> false
        }
        if (!belongs) return error("INVALID_NODE")
        val action = actions ?: return error("NOT_READY")
        val result: ActionResult<*> = runBlocking { when (kind) {
            "project" -> action.updateProject(id, com.virlin.app.domain.action.ProjectUpdate(title = Field.Set(title)))
            "workstream" -> action.updateContext(id, com.virlin.app.domain.action.ContextUpdate(title = Field.Set(title)))
            else -> action.updateTask(id, TaskUpdate(title = Field.Set(title)))
        } }
        val response = when (result) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "map_renamed")
                .put("nodeId", id).put("title", title)
            is ActionResult.Rejected -> error("RENAME_REJECTED")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        if (response.optString("type") == "map_renamed") rememberReceipt(key, response)
        return response
    }

    private fun optionalString(request: JSONObject, name: String): String? =
        request.opt(name)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }

    private fun rememberReceipt(key: String, receipt: JSONObject) {
        if (receipts.size >= 128) receipts.remove(receipts.keys.first())
        receipts[key] = receipt
    }

    private fun create(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val title = request.optString("title").trim()
        if (title.isEmpty() || title.length > 200) return error("INVALID_TITLE")
        val description = optionalString(request, "description")
        if (description != null && description.length > 4096) return error("DESCRIPTION_TOO_LONG")
        val id = optionalString(request, "ownerId")
        if (id != null && (id.length > 128 || !id.matches(Regex("[a-zA-Z0-9_-]+")))) return error("INVALID_OWNER")
        val action = actions ?: return error("NOT_READY")
        val outcome = runBlocking {
            when (request.optString("type")) {
                "create_project" -> action.createProject(CreateProject(title, description))
                "create_workstream" -> if (id == null) ActionResult.Rejected(com.virlin.app.domain.action.DomainError.OwnershipMismatch)
                    else action.createWorkStream(CreateWorkStream(title, projectId = id))
                "create_task" -> when (request.optString("ownerType")) {
                    "project" -> if (id == null) ActionResult.Rejected(com.virlin.app.domain.action.DomainError.OwnershipMismatch)
                        else action.createTask(CreateTask(title, projectId = id, description = description))
                    "workstream" -> if (id == null) ActionResult.Rejected(com.virlin.app.domain.action.DomainError.OwnershipMismatch)
                        else action.createTask(CreateTask(title, workStreamId = id, description = description))
                    "task" -> if (id == null) ActionResult.Rejected(com.virlin.app.domain.action.DomainError.OwnershipMismatch)
                        else action.createTask(CreateTask(title, parentTaskId = id, description = description))
                    else -> ActionResult.Rejected(com.virlin.app.domain.action.DomainError.OwnershipMismatch)
                }
                else -> ActionResult.Rejected(com.virlin.app.domain.action.DomainError.OwnershipMismatch)
            }
        }
        val receipt = when (outcome) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "created")
                .put("id", when (val entity = outcome.value) {
                    is com.virlin.app.domain.model.Project -> entity.id
                    is com.virlin.app.domain.model.WorkStream -> entity.id
                    is com.virlin.app.domain.model.Task -> entity.id
                    else -> ""
                })
            is ActionResult.Rejected -> error("REJECTED_${outcome.reason.javaClass.simpleName}")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        rememberReceipt(key, receipt)
        return receipt
    }

    private fun taskAction(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val id = request.optString("taskId")
        if (id.isBlank() || id.length > 128 || !id.matches(Regex("[a-zA-Z0-9_-]+"))) return error("INVALID_TASK")
        val operation = request.optString("action")
        if (operation !in setOf("rename", "complete", "duplicate", "cancel", "set_execution")) return error("UNSUPPORTED_OPERATION")
        val title = request.optString("title").trim()
        if (operation == "rename" && (title.isEmpty() || title.length > 200)) return error("INVALID_TITLE")
        val preference = request.optString("preference")
        if (operation == "set_execution" && preference !in setOf("INHERIT", "HUMAN", "EXTERNAL"))
            return error("INVALID_EXECUTION")
        val action = actions ?: return error("NOT_READY")
        val outcome: ActionResult<*> = runBlocking {
            when (operation) {
                "rename" -> action.updateTask(id, TaskUpdate(title = Field.Set(title)))
                "complete" -> action.completeTask(id)
                "duplicate" -> action.duplicateTask(id)
                "set_execution" -> if (preference == "INHERIT") action.resetTaskExecutionPreference(id)
                    else action.setTaskExecutionPreference(id, ExecutionPreference.valueOf(preference))
                else -> action.cancelTask(id)
            }
        }
        val receipt = when (outcome) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "task_updated")
                .put("id", (outcome.value as? com.virlin.app.domain.model.Task)?.id ?: id)
                .put("action", operation)
            is ActionResult.Rejected -> error("REJECTED_${outcome.reason.javaClass.simpleName}")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        rememberReceipt(key, receipt)
        return receipt
    }

    /** Reorder one task among its current siblings; the snapshot prevents stale desktop moves. */
    private fun reorderTask(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val taskId = request.optString("taskId")
        val afterId = optionalString(request, "afterId")
        val expected = request.optJSONArray("expectedSiblingIds") ?: return error("INVALID_SNAPSHOT")
        if (!validKnowledgeId(taskId) || (afterId != null && !validKnowledgeId(afterId)) ||
            expected.length() !in 2..200) return error("INVALID_REQUEST")
        val expectedIds = (0 until expected.length()).map { expected.opt(it) }
        if (expectedIds.any { it !is String || !validKnowledgeId(it) } ||
            expectedIds.toSet().size != expectedIds.size) return error("INVALID_SNAPSHOT")
        val repo = repository ?: return error("NOT_READY")
        val moving = repo.tasks.value.firstOrNull { it.id == taskId } ?: return error("NOT_FOUND")
        val streamId = moving.workStreamId ?: return error("UNSUPPORTED_SCOPE")
        val current = repo.tasks.value.filter { it.workStreamId == streamId &&
            it.parentTaskId == moving.parentTaskId }.sortedWith(compareBy({ it.order }, { it.id }))
        if (current.map { it.id } != expectedIds) return error("STALE_TASK_ORDER")
        if (afterId == taskId || (afterId != null && current.none { it.id == afterId }))
            return error("INVALID_POSITION")
        val outcome = runBlocking { actions?.moveTasks(listOf(taskId), moving.parentTaskId, afterId, streamId,
            expectedIds.filterIsInstance<String>()) }
            ?: return error("NOT_READY")
        val receipt = when (outcome) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "task_reordered").put("id", taskId)
            is ActionResult.Rejected -> error("REJECTED_${outcome.reason.javaClass.simpleName}")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        rememberReceipt(key, receipt)
        return receipt
    }

    private fun focusPreview(request: JSONObject): JSONObject {
        val id = request.optString("taskId")
        if (id.isBlank() || id.length > 128 || !id.matches(Regex("[a-zA-Z0-9_-]+"))) return error("INVALID_TASK")
        val action = actions ?: return error("NOT_READY")
        val repository = repository ?: return error("NOT_READY")
        return when (val resolved = runBlocking { action.resolveFocusTarget(id) }) {
            is ActionResult.Success -> {
                val target = resolved.value
                val displacedTitle = target.displacedWorkItemId?.let { oldId ->
                    repository.tasks.value.firstOrNull { it.id == oldId }?.title
                } ?: target.displacedStreamId?.let { streamId ->
                    repository.streams.value.firstOrNull { it.id == streamId }?.title
                }
                JSONObject().put("v", 1).put("type", "focus_target")
                    .put("taskId", id).put("workItemId", target.workItem.id)
                    .put("workItemTitle", target.workItem.title)
                    .put("displacedStreamId", target.displacedStreamId ?: JSONObject.NULL)
                    .put("displacedTitle", displacedTitle ?: JSONObject.NULL)
            }
            is ActionResult.Rejected -> error("REJECTED_${resolved.reason.javaClass.simpleName}")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
    }

    private fun focusStart(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val id = request.optString("taskId")
        val expected = request.optString("expectedWorkItemId")
        if (id.isBlank() || id.length > 128 || expected.isBlank() || expected.length > 128 ||
            !listOf(id, expected).all { it.matches(Regex("[a-zA-Z0-9_-]+")) }) return error("INVALID_TASK")
        val action = actions ?: return error("NOT_READY")
        val preview = runBlocking { action.resolveFocusTarget(id) }
        if (preview !is ActionResult.Success) return error("STALE_FOCUS_PREVIEW")
        if (preview.value.workItem.id != expected ||
            preview.value.displacedStreamId != optionalString(request, "expectedDisplacedStreamId"))
            return error("STALE_FOCUS_PREVIEW")
        val receipt = when (val result = runBlocking { action.startFocus(id) }) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "focused")
                .put("workItemId", result.value.target.workItem.id)
            is ActionResult.Rejected -> error("REJECTED_${result.reason.javaClass.simpleName}")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        rememberReceipt(key, receipt)
        return receipt
    }

    private fun tree(): JSONObject {
        val repository = repository ?: return error("NOT_READY")
        val projects = repository.projects.value
        val streams = repository.streams.value
        val tasks = repository.tasks.value
        val tasksById = tasks.associateBy { it.id }
        val streamsById = streams.associateBy { it.id }
        val projectsById = projects.associateBy { it.id }
        val now = Instant.now()
        if (projects.size + streams.size + tasks.size > 3000) {
            return error("TREE_TOO_LARGE")
        }
        val deviceId = filesContext?.getSharedPreferences("desktop_identity", Context.MODE_PRIVATE)?.let { prefs ->
            prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString("device_id", it).apply()
            }
        }
        val result = JSONObject().put("v", 1).put("type", "tree")
            .put("deviceId", deviceId ?: JSONObject.NULL)
            .put("deviceName", android.os.Build.MODEL ?: "Android phone")
            .put("projects", JSONArray().apply { projects.forEach { project ->
                val progress = ProgressCalculator.ofProject(tasks, streams, project.id)
                put(JSONObject().put("id", project.id).put("title", project.title)
                    .put("description", project.description ?: "").put("status", project.status.name)
                    .put("paletteMain", ProjectPalette.decode(filesContext?.getSharedPreferences("project_appearance", 0)?.getString(project.id, null))?.main ?: JSONObject.NULL)
                    .put("progressPercent", if (progress is ProgressResult.Structured)
                        (progress.fraction * 100).roundToInt() else JSONObject.NULL)
                    .put("iconId", project.iconId ?: ProjectIconCatalog.autoIconId(project.title, project.id))
                    .put("hasCustomIcon", project.iconPath != null))
            } })
            .put("workstreams", JSONArray().apply { streams.filter { it.projectId != null }.forEach { stream ->
                put(JSONObject().put("id", stream.id).put("projectId", stream.projectId)
                    .put("title", stream.title).put("state", stream.state.name)
                    .put("effectiveState", effectiveAttentionState(stream, now).name)
                    .put("externalTrackingPaused", stream.externalTrackingPaused)
                    .put("hasProcessingStarted", stream.processingStartedAt != null)
                    .put("sortOrder", stream.sortOrder))
            } })
            .put("tasks", JSONArray().apply { tasks.filter { it.projectId != null }.forEach { task ->
                put(JSONObject().put("id", task.id).put("projectId", task.projectId)
                    .put("workStreamId", task.workStreamId ?: JSONObject.NULL)
                    .put("parentTaskId", task.parentTaskId ?: JSONObject.NULL)
                    .put("title", task.title).put("status", task.status.name).put("order", task.order)
                    .put("executionState", task.execution?.state?.name ?: JSONObject.NULL)
                    .put("executionPreference", task.executionPreference.name)
                    .put("effectiveExecution", ExecutionModeResolver.resolveTask(task, tasksById,
                        task.workStreamId?.let(streamsById::get), task.projectId?.let(projectsById::get)?.defaultExecutionMode).name))
            } })
        return result
    }

    /** Text-only knowledge creation; files require a separate authenticated transfer protocol. */
    private fun createKnowledge(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val projectId = request.optString("projectId")
        if (projectId.isBlank() || projectId.length > 128 ||
            !projectId.matches(Regex("[a-zA-Z0-9_-]+"))) return error("INVALID_PROJECT")
        val repo = repository ?: return error("NOT_READY")
        if (repo.projects.value.none { it.id == projectId }) return error("NOT_FOUND")
        val taskId = optionalString(request, "taskId")
        if (taskId != null && (!validKnowledgeId(taskId) || repo.tasks.value.none {
                    it.id == taskId && it.projectId == projectId })) return error("INVALID_TASK")
        val kind = request.optString("kind")
        if (kind !in setOf("note", "checklist", "steps", "prompt", "link")) return error("INVALID_KIND")
        if (kind == "note" && !request.isNull("noteBlocks")) {
            if (taskId != null) return error("UNSUPPORTED_PLACEMENT")
            return createRichKnowledgeNote(request, projectId, key)
        }
        val title = optionalString(request, "title")?.trim()
        val rawContent = request.optString("content")
        val content = if (kind == "prompt") rawContent else rawContent.trim()
        val url = optionalString(request, "url")?.trim()
        val description = optionalString(request, "description")?.trim()
        val responseText = optionalString(request, "responseText")
        val codeText = if (request.has("codeText") && !request.isNull("codeText")) request.optString("codeText") else null
        val mode = optionalString(request, "mode") ?: "PROMPT"
        val language = optionalString(request, "language")?.trim()
        val tags = request.optJSONArray("tags")?.let { array ->
            (0 until array.length()).map { array.optString(it).trim() }
        } ?: emptyList()
        if (title != null && title.length > 200) return error("INVALID_TITLE")
        if (content.length > 32768 || (url?.length ?: 0) > 2048) return error("CONTENT_TOO_LONG")
        if ((description?.length ?: 0) > 4096 || (responseText?.length ?: 0) > 32768 ||
            (codeText?.length ?: 0) > 32768 ||
            (language?.length ?: 0) > 40 || mode !in setOf("PROMPT", "CODE") ||
            tags.size > 20 || tags.any { it.length > 80 }) return error("INVALID_PROMPT_FIELDS")
        if (kind != "link" && content.isBlank() && (kind != "prompt" || codeText.isNullOrBlank())) return error("EMPTY_CONTENT")
        if (kind == "link" && url.isNullOrBlank()) return error("INVALID_LINK")
        val action = actions ?: return error("NOT_READY")
        val context = CaptureContext(projectId = projectId, taskId = taskId)
        val outcome = runBlocking {
            when (kind) {
                "note", "checklist", "steps" -> {
                    val type = when (kind) {
                        "checklist" -> NoteBlockType.CHECKBOX
                        "steps" -> NoteBlockType.NUMBERED_LIST
                        else -> NoteBlockType.TEXT
                    }
                    val blocks = if (kind == "note") listOf(NoteBlock("$key-0", type, content))
                        else content.lines().filter { it.isNotBlank() }.mapIndexed { index, line ->
                            NoteBlock("$key-$index", type, line.trim())
                        }
                    action.createTextNote(title, blocks, context)
                }
                "prompt" -> action.createPrompt(title, description, tags, emptyList(), context,
                    sourceText = content, mode = PromptContentMode.valueOf(mode),
                    language = language, responseText = responseText, codeText = codeText)
                else -> action.createCapture(CreateCapture(CaptureType.LINK, content = content,
                    title = title, sourceUrl = url, context = context))
            }
        }
        val receipt = when (outcome) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "knowledge_created")
                .put("id", when (val entity = outcome.value) {
                    is com.virlin.app.domain.model.NoteDocument -> entity.captureItemId
                    is com.virlin.app.domain.model.PromptDocument -> entity.captureItemId
                    is com.virlin.app.domain.model.CaptureItem -> entity.id
                    else -> ""
                })
            is ActionResult.Rejected -> error("REJECTED_${outcome.reason.javaClass.simpleName}")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        if (receipt.optString("type") == "knowledge_created") rememberReceipt(key, receipt)
        return receipt
    }

    /** A rich note is created in one phone transaction, so a failed save leaves no empty capture. */
    private fun createRichKnowledgeNote(request: JSONObject, projectId: String, key: String): JSONObject {
        val source = request.optJSONArray("noteBlocks") ?: return error("INVALID_BLOCKS")
        if (source.length() !in 1..500) return error("INVALID_BLOCKS")
        val title = optionalString(request, "title")?.trim()
        if ((title?.length ?: 0) > 200) return error("INVALID_TITLE")
        var totalLength = 0
        var totalRuns = 0
        val blocks = ArrayList<NoteDocBlock>(source.length())
        val committedImages = ArrayList<String>()
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index) ?: return error("INVALID_BLOCKS")
            val type = runCatching { NoteDocBlockType.valueOf(item.optString("type")) }.getOrNull()
                ?.takeIf { it.isTextual || it == NoteDocBlockType.IMAGE } ?: return error("INVALID_BLOCKS")
            val text = item.optString("text")
            val imagePath = if (type == NoteDocBlockType.IMAGE) item.optString("imagePath") else null
            if (type == NoteDocBlockType.IMAGE) {
                val upload = imageUploads[imagePath ?: return error("INVALID_IMAGE")] ?: return error("INVALID_IMAGE")
                if (!upload.complete || upload.projectId != projectId || text.isNotEmpty()) return error("INVALID_IMAGE")
                committedImages += imagePath
            }
            totalLength += text.length
            if (totalLength > 32768) return error("INVALID_BLOCKS")
            val align = runCatching { NoteDocAlign.valueOf(item.optString("align", "LEFT")) }.getOrNull()
                ?: return error("INVALID_BLOCKS")
            val sourceRuns = item.optJSONArray("runs") ?: return error("INVALID_RUNS")
            totalRuns += sourceRuns.length()
            if (totalRuns > 3000) return error("INVALID_RUNS")
            val runs = ArrayList<NoteDocRun>(sourceRuns.length())
            for (runIndex in 0 until sourceRuns.length()) {
                val run = sourceRuns.optJSONObject(runIndex) ?: return error("INVALID_RUNS")
                val start = run.optInt("start", -1)
                val end = run.optInt("end", -1)
                if (start < 0 || end <= start || end > text.length) return error("INVALID_RUNS")
                val marksArray = run.optJSONArray("marks") ?: return error("INVALID_RUNS")
                val marks = (0 until marksArray.length()).map { markIndex ->
                    runCatching { NoteDocMark.valueOf(marksArray.optString(markIndex)) }.getOrNull()
                        ?: return error("INVALID_RUNS")
                }.toSet()
                val fontFamily = optionalString(run, "fontFamily")
                val linkUrl = optionalString(run, "linkUrl")
                val fontSize = if (run.isNull("fontSizeSp")) null else run.optInt("fontSizeSp", -1)
                if ((fontFamily?.length ?: 0) > 80 || (linkUrl?.length ?: 0) > 2048 ||
                    (fontSize != null && fontSize !in 8..96)) return error("INVALID_RUNS")
                runs += NoteDocRun(start, end, marks,
                    if (run.isNull("color")) null else run.optLong("color"),
                    if (run.isNull("highlight")) null else run.optLong("highlight"),
                    fontFamily, fontSize, linkUrl)
            }
            blocks += NoteDocBlock(id = "desktop-$key-$index", type = type, text = text,
                runs = runs, align = align, indent = item.optInt("indent", 0).coerceIn(0, 6),
                checked = item.optBoolean("checked", false), imagePath = imagePath,
                isCollapsed = item.optBoolean("isCollapsed", false))
        }
        if (blocks.all { it.text.isBlank() && it.type != NoteDocBlockType.IMAGE }) return error("EMPTY_CONTENT")
        val captureId = "desktop-$key"
        val action = actions ?: return error("NOT_READY")
        val outcome = runBlocking {
            action.saveNoteDoc(CaptureNoteBridge.draftOwner(projectId, captureId), title, blocks)
        }
        val receipt = when (outcome) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "knowledge_created")
                .put("id", captureId)
            is ActionResult.Rejected -> error("REJECTED_${outcome.reason.javaClass.simpleName}")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        if (receipt.optString("type") == "knowledge_created") {
            committedImages.forEach(imageUploads::remove)
            rememberReceipt(key, receipt)
        }
        return receipt
    }

    /** Read-only, bounded projection of the phone's canonical captures. Never sends file paths. */
    private fun knowledgeItem(request: JSONObject): JSONObject {
        val repo = repository ?: return error("NOT_READY")
        val projectId = request.optString("projectId")
        val captureId = request.optString("captureId")
        if (!validKnowledgeId(projectId) || !validKnowledgeId(captureId)) return error("INVALID_ID")
        if (repo.projects.value.none { it.id == projectId }) return error("NOT_FOUND")
        val capture = repo.captures.value.firstOrNull {
            it.id == captureId && it.projectId == projectId && it.status != CaptureStatus.ARCHIVED
        } ?: return error("NOT_FOUND")
        val action = actions ?: return error("NOT_READY")
        val prompt = if (capture.type == CaptureType.PROMPT) runBlocking { action.getPromptByCaptureId(captureId) } else null
        val voice = if (capture.type == CaptureType.VOICE) runBlocking { action.getVoiceByCaptureId(captureId) } else null
        val noteDoc = if (capture.type == CaptureType.NOTE) runBlocking {
            action.loadNoteDoc(CaptureNoteBridge.owner(captureId))
        } else null
        val body = runBlocking {
            when (capture.type) {
                CaptureType.NOTE -> noteDoc?.blocks?.joinToString("\n") { it.text } ?: capture.content.orEmpty()
                CaptureType.PROMPT -> prompt?.let { document ->
                    document.sourceText.ifBlank { document.blocks.joinToString("\n") { it.plainText } }
                } ?: capture.content.orEmpty()
                CaptureType.LINK -> LinkDocumentCodec.decode(capture.content).note
                else -> ""
            }
        }
        if (body.length > 32768 || (prompt?.responseText?.length ?: 0) > 32768) return error("KNOWLEDGE_TOO_LARGE")
        if (noteDoc != null && (noteDoc.blocks.size > 500 || noteDoc.blocks.sumOf { it.runs.size } > 3000))
            return error("KNOWLEDGE_TOO_LARGE")
        val media = capture.type == CaptureType.VOICE || capture.type == CaptureType.FILE
        return JSONObject().put("v", 1).put("type", "knowledge_item")
            .put("projectId", projectId).put("id", captureId)
            .put("kind", capture.type.name.lowercase())
            .put("title", capture.title ?: "")
            .put("content", body)
            .put("noteRevision", noteDoc?.revision ?: JSONObject.NULL)
            .put("noteBlocks", noteDoc?.let { doc -> JSONArray().apply { doc.blocks.forEach { block ->
                put(JSONObject().put("id", block.id).put("type", block.type.name).put("text", block.text)
                    .put("align", block.align.name).put("indent", block.indent)
                    .put("checked", block.checked).put("lineSpacing", block.lineSpacing.toDouble())
                    .put("promptTitle", block.promptTitle ?: JSONObject.NULL)
                    .put("imagePath", block.imagePath ?: JSONObject.NULL)
                    .put("isCollapsed", block.isCollapsed)
                    .put("table", block.table?.let { table -> JSONObject().put("rows", table.rows)
                        .put("cols", table.cols).put("cells", JSONArray(table.cells)) } ?: JSONObject.NULL)
                    .put("runs", JSONArray().apply { block.runs.forEach { run ->
                        put(JSONObject().put("start", run.start).put("end", run.end)
                            .put("marks", JSONArray(run.marks.map { it.name }))
                            .put("color", run.color ?: JSONObject.NULL)
                            .put("highlight", run.highlight ?: JSONObject.NULL)
                            .put("fontFamily", run.fontFamily ?: JSONObject.NULL)
                            .put("fontSizeSp", run.fontSizeSp ?: JSONObject.NULL)
                            .put("linkUrl", run.linkUrl ?: JSONObject.NULL))
                    } }))
            } } } ?: JSONObject.NULL)
            .put("description", prompt?.description ?: JSONObject.NULL)
            .put("tags", JSONArray(prompt?.tags ?: emptyList<String>()))
            .put("mode", prompt?.mode?.name ?: JSONObject.NULL)
            .put("language", prompt?.language ?: JSONObject.NULL)
            .put("responseText", prompt?.responseText ?: JSONObject.NULL)
            .put("codeText", prompt?.codeText ?: JSONObject.NULL)
            .put("promptEditToken", prompt?.editToken() ?: JSONObject.NULL)
            .put("promptCanEdit", prompt?.let { canEditPrompt(it) } ?: false)
            .put("linkEditToken", if (capture.type == CaptureType.LINK) capture.editToken() else JSONObject.NULL)
            .put("voiceClips", JSONArray().apply { voice?.sortedClips()?.forEach { clip ->
                put(JSONObject().put("id", clip.id).put("name", clip.displayName.take(200))
                    .put("durationMs", clip.durationMs).put("sizeBytes", clip.sizeBytes)
                    .put("mimeType", clip.mimeType))
            } })
            .put("sourceUrl", capture.sourceUrl ?: JSONObject.NULL)
            .put("updatedAt", capture.updatedAt.toEpochMilli())
            .put("canCopyText", !media).put("canEdit", false)
    }

    private fun validKnowledgeId(id: String) = id.isNotBlank() && id.length <= 128 &&
        id.matches(Regex("[a-zA-Z0-9_-]+"))

    private fun saveKnowledgeLink(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val projectId = request.optString("projectId")
        val captureId = request.optString("captureId")
        if (!validKnowledgeId(projectId) || !validKnowledgeId(captureId)) return error("INVALID_ID")
        val repo = repository ?: return error("NOT_READY")
        val capture = repo.captures.value.firstOrNull { it.id == captureId && it.projectId == projectId &&
            it.type == CaptureType.LINK && it.status != CaptureStatus.ARCHIVED } ?: return error("NOT_FOUND")
        val token = request.optString("expectedToken")
        if (!token.matches(Regex("[a-f0-9]{64}"))) return error("INVALID_REVISION")
        val title = optionalString(request, "title")?.trim()
        val url = optionalString(request, "url")?.trim()
        val note = request.optString("content")
        if ((title?.length ?: 0) > 200 || (url?.length ?: 0) > 2048 || note.length > 32768)
            return error("CONTENT_TOO_LONG")
        if (url == null || LinkUrl.canonicalOrNull(url) == null) return error("INVALID_LINK")
        val content = LinkDocumentCodec.encode(LinkDocumentCodec.decode(capture.content).copy(note = note))
        val action = actions ?: return error("NOT_READY")
        val outcome = runBlocking { action.updateCapture(captureId, CaptureUpdate(
            title = Field.Set(title ?: ""), sourceUrl = Field.Set(url), content = Field.Set(content),
            expectedEditToken = token)) }
        val receipt = when (outcome) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "knowledge_link_saved").put("id", captureId)
            is ActionResult.Rejected -> error("STALE_OR_REJECTED")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        if (receipt.optString("type") == "knowledge_link_saved") rememberReceipt(key, receipt)
        return receipt
    }

    private fun readVoiceChunk(request: JSONObject): JSONObject {
        val context = filesContext ?: return error("MEDIA_UNAVAILABLE")
        val repo = repository ?: return error("NOT_READY")
        val action = actions ?: return error("NOT_READY")
        val projectId = request.optString("projectId")
        val captureId = request.optString("captureId")
        val clipId = request.optString("clipId")
        if (!validKnowledgeId(projectId) || !validKnowledgeId(captureId) || !validKnowledgeId(clipId))
            return error("INVALID_ID")
        if (repo.captures.value.none { it.id == captureId && it.projectId == projectId &&
                    it.type == CaptureType.VOICE && it.status != CaptureStatus.ARCHIVED }) return error("NOT_FOUND")
        val clip = runBlocking { action.getVoiceByCaptureId(captureId) }?.clips?.firstOrNull { it.id == clipId }
            ?: return error("NOT_FOUND")
        if (clip.relativePath != VoiceFileStore.relativePath(captureId, clipId)) return error("INVALID_CLIP")
        val offset = request.optLong("offset", -1)
        if (offset < 0) return error("INVALID_OFFSET")
        val root = VoiceFileStore.voicesRoot(context).canonicalFile
        val file = VoiceFileStore.resolve(context, clip.relativePath).canonicalFile
        if (file.parentFile != java.io.File(root, captureId).canonicalFile || !file.isFile)
            return error("MEDIA_UNAVAILABLE")
        val length = file.length()
        if (length > 20L * 1024 * 1024 || length != clip.sizeBytes || offset > length)
            return error("MEDIA_UNAVAILABLE")
        val bytes = ByteArray(minOf(32768L, length - offset).toInt())
        return try {
            RandomAccessFile(file, "r").use { reader ->
                reader.seek(offset)
                reader.readFully(bytes)
            }
            JSONObject().put("v", 1).put("type", "voice_chunk").put("clipId", clipId)
                .put("offset", offset).put("totalBytes", length)
                .put("mimeType", clip.mimeType).put("data", Base64.getEncoder().encodeToString(bytes))
                .put("done", offset + bytes.size == length)
        } catch (_: Exception) { error("MEDIA_UNAVAILABLE") }
    }

    private fun uploadNoteImage(request: JSONObject): JSONObject {
        val context = filesContext ?: return error("MEDIA_UNAVAILABLE")
        val repo = repository ?: return error("NOT_READY")
        val projectId = request.optString("projectId")
        val uploadId = request.optString("uploadId")
        if (!validKnowledgeId(projectId) ||
            runCatching { java.util.UUID.fromString(uploadId).toString() }.getOrNull() != uploadId ||
            repo.projects.value.none { it.id == projectId }) return error("INVALID_ID")
        val total = request.optLong("totalBytes", -1)
        val offset = request.optLong("offset", -1)
        val hash = request.optString("sha256")
        if (total !in 1..(25L * 1024 * 1024) || offset < 0 ||
            hash.length != 64 || hash.any { !it.isDigit() && it !in 'a'..'f' }) return error("INVALID_IMAGE")
        val bytes = runCatching { Base64.getDecoder().decode(request.optString("data")) }.getOrNull()
            ?: return error("INVALID_IMAGE")
        if (bytes.isEmpty() || bytes.size > 64 * 1024 || offset + bytes.size > total) return error("INVALID_IMAGE")
        val upload = imageUploads[uploadId] ?: run {
            if (offset != 0L || imageUploads.size >= 8) return error("INVALID_OFFSET")
            val file = NoteImageStore.resolve(context, uploadId) ?: return error("INVALID_IMAGE")
            file.parentFile?.mkdirs()
            if (file.exists()) return error("IMAGE_EXISTS")
            ImageUpload(projectId, file, total, hash).also { imageUploads[uploadId] = it }
        }
        if (upload.projectId != projectId || upload.total != total || upload.expectedHash != hash ||
            upload.complete || upload.received != offset) return error("INVALID_OFFSET")
        return try {
            if (offset == 0L) upload.file.writeBytes(bytes) else upload.file.appendBytes(bytes)
            upload.received += bytes.size
            if (upload.received == total) {
                val digest = MessageDigest.getInstance("SHA-256")
                upload.file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actualHash = digest.digest()
                    .joinToString("") { "%02x".format(it) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(upload.file.absolutePath, bounds)
                if (actualHash != hash || bounds.outWidth !in 1..10000 || bounds.outHeight !in 1..10000 ||
                    bounds.outWidth.toLong() * bounds.outHeight > 40_000_000L) {
                    imageUploads.remove(uploadId)?.file?.delete()
                    return error("INVALID_IMAGE")
                }
                upload.complete = true
            }
            JSONObject().put("v", 1).put("type", if (upload.complete) "note_image_uploaded" else "note_image_progress")
                .put("uploadId", uploadId).put("received", upload.received)
        } catch (_: Exception) {
            imageUploads.remove(uploadId)?.file?.delete()
            error("IMAGE_UPLOAD_FAILED")
        }
    }

    private fun readNoteImageChunk(request: JSONObject): JSONObject {
        val context = filesContext ?: return error("MEDIA_UNAVAILABLE")
        val repo = repository ?: return error("NOT_READY")
        val projectId = request.optString("projectId")
        val captureId = request.optString("captureId")
        val imagePath = request.optString("imagePath")
        if (!validKnowledgeId(projectId) || !validKnowledgeId(captureId) ||
            NoteImageStore.resolve(context, imagePath) == null) return error("INVALID_ID")
        if (repo.captures.value.none { it.id == captureId && it.projectId == projectId &&
                    it.type == CaptureType.NOTE && it.status != CaptureStatus.ARCHIVED }) return error("NOT_FOUND")
        val action = actions ?: return error("NOT_READY")
        val doc = runBlocking { action.loadNoteDoc(CaptureNoteBridge.owner(captureId)) } ?: return error("NOT_FOUND")
        if (doc.blocks.none { it.type == NoteDocBlockType.IMAGE && it.imagePath == imagePath }) return error("NOT_FOUND")
        val file = NoteImageStore.resolve(context, imagePath) ?: return error("INVALID_ID")
        val offset = request.optLong("offset", -1)
        val length = file.length()
        if (!file.isFile || length !in 1..(25L * 1024 * 1024) || offset !in 0..length)
            return error("MEDIA_UNAVAILABLE")
        return try {
            val bytes = ByteArray(minOf(64 * 1024L, length - offset).toInt())
            RandomAccessFile(file, "r").use { reader -> reader.seek(offset); reader.readFully(bytes) }
            JSONObject().put("v", 1).put("type", "note_image_chunk")
                .put("imagePath", imagePath).put("offset", offset).put("totalBytes", length)
                .put("data", Base64.getEncoder().encodeToString(bytes))
                .put("done", offset + bytes.size == length)
        } catch (_: Exception) { error("MEDIA_UNAVAILABLE") }
    }

    private fun canEditPrompt(prompt: com.virlin.app.domain.model.PromptDocument): Boolean =
        prompt.blocks.size == 1 && prompt.blocks[0].type in setOf(NoteBlockType.TEXT, NoteBlockType.CODE) &&
            prompt.blocks[0].marks.isEmpty() && prompt.blocks[0].children.isEmpty()

    /** Exact prompt text only; complex legacy prompt blocks remain read-only. */
    private fun saveKnowledgePrompt(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val projectId = request.optString("projectId")
        val captureId = request.optString("captureId")
        if (!validKnowledgeId(projectId) || !validKnowledgeId(captureId)) return error("INVALID_ID")
        val repo = repository ?: return error("NOT_READY")
        if (repo.captures.value.none { it.id == captureId && it.projectId == projectId &&
                    it.type == CaptureType.PROMPT && it.status != CaptureStatus.ARCHIVED }) return error("NOT_FOUND")
        val action = actions ?: return error("NOT_READY")
        val existing = runBlocking { action.getPromptByCaptureId(captureId) } ?: return error("NOT_FOUND")
        if (!canEditPrompt(existing)) return error("COMPLEX_PROMPT_READ_ONLY")
        val expectedToken = request.optString("expectedToken")
        if (!expectedToken.matches(Regex("[a-f0-9]{64}"))) return error("INVALID_REVISION")
        val title = optionalString(request, "title")?.trim()
        val description = optionalString(request, "description")?.trim()
        val sourceText = request.optString("content")
        val responseText = optionalString(request, "responseText")
        val codeText = if (request.has("codeText") && !request.isNull("codeText")) request.optString("codeText") else null
        val language = optionalString(request, "language")?.trim()
        val mode = runCatching { PromptContentMode.valueOf(request.optString("mode")) }.getOrNull()
            ?: return error("INVALID_PROMPT_FIELDS")
        val tagArray = request.optJSONArray("tags") ?: return error("INVALID_PROMPT_FIELDS")
        val tags = (0 until tagArray.length()).map { tagArray.optString(it).trim() }
        if ((title?.length ?: 0) > 200 || (description?.length ?: 0) > 4096 ||
            sourceText.length > 32768 || (responseText?.length ?: 0) > 32768 ||
            (codeText?.length ?: 0) > 32768 ||
            (language?.length ?: 0) > 40 || tags.size > 20 || tags.any { it.length > 80 })
            return error("INVALID_PROMPT_FIELDS")
        val blocks = listOf(existing.blocks.single().copy(plainText = sourceText))
        val outcome = runBlocking { action.savePrompt(captureId, title, description, tags, blocks,
            sourceText, mode, language, responseText, expectedToken, codeText) }
        val receipt = when (outcome) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "knowledge_prompt_saved")
                .put("id", captureId).put("editToken", outcome.value.editToken())
            is ActionResult.Rejected -> error("STALE_OR_REJECTED")
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        if (receipt.optString("type") == "knowledge_prompt_saved") rememberReceipt(key, receipt)
        return receipt
    }

    /** Revisioned edits to existing note blocks only; media and unknown structures stay untouched. */
    private fun saveKnowledgeNote(request: JSONObject): JSONObject {
        val key = request.optString("key")
        if (!validKey(key)) return error("INVALID_KEY")
        receipts[key]?.let { return it }
        val projectId = request.optString("projectId")
        val captureId = request.optString("captureId")
        if (!validKnowledgeId(projectId) || !validKnowledgeId(captureId)) return error("INVALID_ID")
        val repo = repository ?: return error("NOT_READY")
        if (repo.captures.value.none { it.id == captureId && it.projectId == projectId &&
                    it.type == CaptureType.NOTE && it.status != CaptureStatus.ARCHIVED }) return error("NOT_FOUND")
        val action = actions ?: return error("NOT_READY")
        val expectedRevision = request.optInt("expectedRevision", -1)
        if (expectedRevision < 0) return error("INVALID_REVISION")
        val title = optionalString(request, "title")?.trim()
        if ((title?.length ?: 0) > 200) return error("INVALID_TITLE")
        val original = runBlocking { action.loadNoteDoc(CaptureNoteBridge.owner(captureId)) } ?: return error("NOT_FOUND")
        val source = request.optJSONArray("blocks") ?: return error("INVALID_BLOCKS")
        if (source.length() < original.blocks.size || source.length() > 500 ||
            source.length() > original.blocks.size + 50) return error("INVALID_BLOCKS")
        val originalById = original.blocks.associateBy { it.id }
        val sourceIds = (0 until source.length()).map { source.optJSONObject(it)?.optString("id").orEmpty() }
        if (sourceIds.any { !validKnowledgeId(it) } || sourceIds.toSet().size != sourceIds.size ||
            !sourceIds.containsAll(originalById.keys)) return error("INVALID_BLOCKS")
        var totalLength = 0
        var totalRuns = 0
        val blocks = ArrayList<NoteDocBlock>(source.length())
        val committedImages = ArrayList<String>()
        for (index in 0 until source.length()) {
            val payload = source.optJSONObject(index) ?: return error("INVALID_BLOCKS")
            val id = payload.optString("id")
            val previous = originalById[id]
            val type = runCatching { NoteDocBlockType.valueOf(payload.optString("type")) }.getOrNull()
                ?: return error("INVALID_BLOCKS")
            if (!type.isTextual && previous == null && type != NoteDocBlockType.IMAGE) return error("INVALID_BLOCKS")
            if (previous != null && type != previous.type && (!type.isTextual || !previous.type.isTextual))
                return error("INVALID_BLOCKS")
            val text = payload.optString("text")
            totalLength += text.length
            if (totalLength > 32768 || (!type.isTextual && text != (previous?.text ?: ""))) return error("INVALID_BLOCKS")
            val imagePath = if (type == NoteDocBlockType.IMAGE) payload.optString("imagePath") else null
            if (type == NoteDocBlockType.IMAGE) {
                if (previous == null || previous.imagePath != imagePath) {
                    val upload = imageUploads[imagePath ?: return error("INVALID_IMAGE")] ?: return error("INVALID_IMAGE")
                    if (!upload.complete || upload.projectId != projectId) return error("INVALID_IMAGE")
                    committedImages += imagePath
                }
            }
            val sourceRuns = payload.optJSONArray("runs") ?: return error("INVALID_RUNS")
            totalRuns += sourceRuns.length()
            if (totalRuns > 3000) return error("INVALID_RUNS")
            val runs = ArrayList<NoteDocRun>(sourceRuns.length())
            for (runIndex in 0 until sourceRuns.length()) {
                val item = sourceRuns.optJSONObject(runIndex) ?: return error("INVALID_RUNS")
                val start = item.optInt("start", -1)
                val end = item.optInt("end", -1)
                if (start < 0 || end <= start || end > text.length) return error("INVALID_RUNS")
                val marksArray = item.optJSONArray("marks") ?: return error("INVALID_RUNS")
                val marks = (0 until marksArray.length()).map { markIndex ->
                    runCatching { NoteDocMark.valueOf(marksArray.optString(markIndex)) }.getOrNull()
                        ?: return error("INVALID_RUNS")
                }.toSet()
                val fontFamily = optionalString(item, "fontFamily")
                val linkUrl = optionalString(item, "linkUrl")
                if ((fontFamily?.length ?: 0) > 80 || (linkUrl?.length ?: 0) > 2048) return error("INVALID_RUNS")
                val fontSize = if (item.isNull("fontSizeSp")) null else item.optInt("fontSizeSp", -1)
                if (fontSize != null && fontSize !in 8..96) return error("INVALID_RUNS")
                runs += NoteDocRun(start, end, marks,
                    if (item.isNull("color")) null else item.optLong("color"),
                    if (item.isNull("highlight")) null else item.optLong("highlight"),
                    fontFamily, fontSize, linkUrl)
            }
            val basis = previous ?: NoteDocBlock(id = id)
            blocks += basis.copy(type = type, text = text, runs = runs,
                align = runCatching { NoteDocAlign.valueOf(payload.optString("align", basis.align.name)) }
                    .getOrNull() ?: return error("INVALID_BLOCKS"),
                indent = payload.optInt("indent", basis.indent).coerceIn(0, 6),
                imagePath = if (type == NoteDocBlockType.IMAGE) imagePath else basis.imagePath,
                checked = if (type == NoteDocBlockType.CHECKLIST) payload.optBoolean("checked", basis.checked) else basis.checked,
                isCollapsed = payload.optBoolean("isCollapsed", basis.isCollapsed))
        }
        val outcome = runBlocking { action.saveNoteDoc(CaptureNoteBridge.owner(captureId), title, blocks, expectedRevision) }
        val receipt = when (outcome) {
            is ActionResult.Success -> JSONObject().put("v", 1).put("type", "knowledge_note_saved")
                .put("id", captureId).put("revision", outcome.value.revision)
            is ActionResult.Rejected -> error("STALE_OR_REJECTED").apply {
                (outcome.reason as? com.virlin.app.domain.action.DomainError.PlacementRejected)?.let { put("detail", it.reason) }
            }
            is ActionResult.NotFound -> error("NOT_FOUND")
            is ActionResult.Failure -> error("ACTION_FAILED")
        }
        if (receipt.optString("type") == "knowledge_note_saved") {
            committedImages.forEach(imageUploads::remove)
            rememberReceipt(key, receipt)
        }
        return receipt
    }

    /** Read-only, bounded projection of the phone's canonical captures. Never sends file paths. */
    private fun knowledge(request: JSONObject): JSONObject {
        val repository = repository ?: return error("NOT_READY")
        val projectId = request.optString("projectId")
        if (projectId.isBlank() || projectId.length > 128 ||
            !projectId.matches(Regex("[a-zA-Z0-9_-]+"))) return error("INVALID_PROJECT")
        if (repository.projects.value.none { it.id == projectId }) return error("NOT_FOUND")
        val captures = repository.captures.value.filter {
            it.projectId == projectId && it.status != CaptureStatus.ARCHIVED
        }.sortedWith(compareByDescending<com.virlin.app.domain.model.CaptureItem> { it.updatedAt }.thenBy { it.id })
        if (captures.size > 500) return error("KNOWLEDGE_TOO_LARGE")
        val ids = captures.map { it.id }
        val attachments = runBlocking { repository.getAttachmentsByCaptureIds(ids) }
        val notes = runBlocking { repository.getNotesByCaptureIds(ids) }
        val prompts = runBlocking { repository.getPromptsByCaptureIds(ids) }
        val streams = repository.streams.value.associateBy { it.id }
        val tasks = repository.tasks.value.associateBy { it.id }
        return JSONObject().put("v", 1).put("type", "knowledge")
            .put("projectId", projectId).put("items", JSONArray().apply {
                captures.forEach { capture ->
                    val attachment = attachments[capture.id]
                    val note = notes[capture.id]
                    val prompt = prompts[capture.id]
                    val blocks = note?.blocks.orEmpty()
                    val kind = when {
                        attachment?.kind == AttachmentKind.IMAGE -> "image"
                        attachment?.kind == AttachmentKind.AUDIO || capture.type == CaptureType.VOICE -> "audio"
                        attachment?.kind == AttachmentKind.VIDEO -> "video"
                        attachment != null -> "document"
                        capture.type == CaptureType.PROMPT -> "prompt"
                        capture.type == CaptureType.LINK -> "link"
                        blocks.any { it.type == NoteBlockType.CHECKBOX } -> "checklist"
                        blocks.any { it.type == NoteBlockType.NUMBERED_LIST } -> "steps"
                        else -> "note"
                    }
                    val body = when {
                        prompt != null -> prompt.sourceText.ifBlank { prompt.blocks.joinToString("\n") { it.plainText } }
                        note != null -> note.blocks.joinToString("\n") { it.plainText }
                        capture.type == CaptureType.LINK -> LinkDocumentCodec.decode(capture.content).note
                        else -> capture.content
                    }
                    val title = capture.title?.takeIf { it.isNotBlank() }
                        ?: attachment?.displayName ?: note?.title ?: prompt?.title
                        ?: capture.sourceUrl ?: body.lineSequence().firstOrNull { it.isNotBlank() }
                        ?: kind.replaceFirstChar { it.uppercase() }
                    put(JSONObject().put("id", capture.id).put("kind", kind)
                        .put("title", title.take(200)).put("snippet", body.take(420))
                        .put("fileName", attachment?.displayName ?: JSONObject.NULL)
                        .put("fileKind", attachment?.kind?.name ?: JSONObject.NULL)
                        .put("sizeBytes", attachment?.sizeBytes ?: JSONObject.NULL)
                        .put("workstreamId", capture.workStreamId ?: JSONObject.NULL)
                        .put("taskId", capture.taskId ?: JSONObject.NULL)
                        .put("workstreamName", streams[capture.workStreamId]?.title ?: JSONObject.NULL)
                        .put("taskName", tasks[capture.taskId]?.title ?: JSONObject.NULL)
                        .put("updatedAt", capture.updatedAt.toEpochMilli())
                        .put("completedCount", blocks.count { it.type == NoteBlockType.CHECKBOX && it.checked })
                        .put("totalCount", blocks.count { it.type == NoteBlockType.CHECKBOX }))
                }
            })
    }

    private fun error(code: String) = JSONObject().put("v", 1).put("type", "error").put("code", code)
}
