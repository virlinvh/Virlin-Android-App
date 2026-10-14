package com.virlin.app.domain.project

import com.virlin.app.domain.model.*
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.repository.WorkStreamRepository
import java.time.Instant
import java.util.UUID

enum class ProjectCopyMode { STRUCTURE, FULL }

/** Files are copied by the platform before this atomic metadata commit. No shared file ownership. */
class ProjectDuplicator(private val repository: WorkStreamRepository) {
    suspend fun duplicate(
        source: ProjectSnapshot,
        title: String,
        mode: ProjectCopyMode,
        now: Instant,
        copyFile: (store: String, path: String, owner: String) -> String,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): Project {
        val name = title.trim()
        require(name.isNotEmpty() && name.length <= 60) { "Enter a project name of 1–60 characters." }
        val full = mode == ProjectCopyMode.FULL
        val sourceStreamIds = source.streams.map { it.id }.toSet()
        val sourceTaskIds = source.tasks.map { it.id }.toSet()
        require(source.tasks.all { (it.workStreamId == null || it.workStreamId in sourceStreamIds) &&
            (it.parentTaskId == null || it.parentTaskId in sourceTaskIds) }) { "Project contains invalid ownership links." }
        val ids = mutableMapOf<String, String>()
        fun id(old: String) = ids.getOrPut(old, newId)
        val projectId = id(source.project.id)
        source.streams.forEach { id(it.id) }; source.tasks.forEach { id(it.id) }
        source.captures.forEach { id(it.id) }; source.tags.forEach { id(it.id) }
        val ownerMap = mutableMapOf("project-${source.project.id}" to "project-$projectId")
        source.tasks.forEach { ownerMap[TaskPageTypeKeys.noteOwner(it.id)] = TaskPageTypeKeys.noteOwner(id(it.id)) }
        source.captures.forEach { ownerMap[CaptureNoteBridge.owner(it.id)] = CaptureNoteBridge.owner(id(it.id)) }
        source.richNotes.forEach { if (it.ownerKey !in ownerMap) ownerMap[it.ownerKey] = "copy-${newId()}" }
        source.pageBlocks.filter { it.typeKey == TaskPageTypeKeys.NOTE }.forEach {
            if (it.contentId !in ownerMap) ownerMap[it.contentId] = "copy-${newId()}"
        }
        val project = source.project.copy(id = projectId, title = name, status = ProjectStatus.ACTIVE,
            completedAt = null, dueAt = null, createdAt = now, updatedAt = now,
            iconPath = source.project.iconPath?.let { copyFile("icons", it, projectId) })
        val streams = source.streams.map { old ->
            old.copy(id = id(old.id), projectId = projectId, state = WorkStreamState.READY,
                attentionRank = null, lastHumanAction = null, waitingFor = null, nextHumanAction = null,
                blockerReason = null, processingStartedAt = null, externalTrackedSeconds = 0,
                externalTrackingPaused = false, checkAt = null, snoozedUntil = null, snoozeReason = null,
                currentCycleId = null, cycleCount = 0, activeTaskId = null, completedAt = null,
                createdAt = now, updatedAt = now)
        }
        val tasks = source.tasks.map { old ->
            old.copy(id = id(old.id), projectId = projectId, workStreamId = old.workStreamId?.let(::id),
                parentTaskId = old.parentTaskId?.let(::id), status = TaskStatus.TODO,
                dueAt = null, reminderAt = null, completedAt = null, execution = null,
                description = old.description.takeIf { full }, notes = old.notes.takeIf { full },
                createdAt = now, updatedAt = now)
        }
        fun resetBlocks(blocks: List<NoteBlock>): List<NoteBlock> = blocks.map {
            it.copy(id = newId(), checked = false, children = resetBlocks(it.children))
        }
        val attachments = if (full) source.attachments.map { old ->
            val docId = id(old.id)
            old.copy(id = docId, captureItemId = id(old.captureItemId),
                relativePath = copyFile("attachments", old.relativePath, docId), createdAt = now, updatedAt = now)
        } else emptyList()
        val voices = if (full) source.voices.map { old ->
            old.copy(id = id(old.id), captureItemId = id(old.captureItemId), createdAt = now, updatedAt = now,
                clips = old.clips.map { clip -> clip.copy(id = newId(), createdAt = now,
                    relativePath = copyFile("voices", clip.relativePath, id(old.captureItemId))) })
        } else emptyList()
        val previews = if (full) source.previews.map { old -> old.copy(ownerId = id(old.ownerId), expanded = false,
            createdAt = now, updatedAt = now, images = old.images.map { image -> image.copy(id = newId(),
                relativePath = copyFile("previews", image.relativePath, id(old.ownerId))) }) } else emptyList()
        return repository.transaction {
            val latest = requireNotNull(getProject(source.project.id)) { "Original project no longer exists." }
            require(latest == source.project && allTasks().filter { it.projectId == latest.id ||
                it.workStreamId in source.streams.map { stream -> stream.id } }.toSet() == source.tasks.toSet() &&
                allStreams().filter { it.projectId == latest.id }.toSet() == source.streams.toSet()) {
                "Project changed while copying. Please try again."
            }
            if (full) {
                require(source.captures.all { getCapture(it.id) == it } &&
                    source.notes.all { getNoteByCaptureId(it.captureItemId) == it } &&
                    source.prompts.all { getPromptByCaptureId(it.captureItemId) == it } &&
                    source.richNotes.all { noteDocOf(it.ownerKey) == it } &&
                    source.tasks.all { stepsOf(it.id) == source.steps.filter { step -> step.taskId == it.id } &&
                        pageBlocksOf(it.id) == source.pageBlocks.filter { block -> block.taskId == it.id } }) {
                    "Project content changed while copying. Please try again."
                }
            }
            saveProject(project)
            streams.forEach { saveStream(it) }
            // Ownership validation can consult parents: save parents before children, without recursion.
            val remaining = tasks.toMutableList(); val written = mutableSetOf<String>()
            while (remaining.isNotEmpty()) {
                val ready = remaining.filter { it.parentTaskId == null || it.parentTaskId in written }
                require(ready.isNotEmpty()) { "Project contains invalid task ancestry." }
                ready.forEach { saveTask(it); written += it.id }; remaining.removeAll(ready.toSet())
            }
            if (full) {
                source.stages.forEach { stage -> saveStage(stage.copy(id = newId(), workStreamId = id(stage.workStreamId),
                    status = ExternalStageStatus.PENDING, startedAt = null, completedAt = null)) }
                source.captures.forEach { old -> saveCapture(old.copy(id = id(old.id), projectId = projectId,
                    workStreamId = old.workStreamId?.let { ids[it] }, taskId = old.taskId?.let { ids[it] },
                    convertedTaskId = old.convertedTaskId?.let { ids[it] }, createdAt = now, updatedAt = now)) }
                source.notes.forEach { saveNoteDocument(it.copy(id = id(it.id), captureItemId = id(it.captureItemId),
                    blocks = resetBlocks(it.blocks), createdAt = now, updatedAt = now)) }
                source.prompts.forEach { savePromptDocument(it.copy(id = id(it.id), captureItemId = id(it.captureItemId),
                    blocks = resetBlocks(it.blocks), createdAt = now, updatedAt = now)) }
                attachments.forEach { saveAttachmentDocument(it) }; voices.forEach { saveVoiceDocument(it) }
                source.richNotes.forEach { doc -> saveNoteDoc(doc.copy(ownerKey = ownerMap.getValue(doc.ownerKey),
                    blocks = doc.blocks.map { it.copy(id = newId(), checked = false) }, createdAt = now, updatedAt = now, revision = 0)) }
                source.steps.forEach { saveStep(it.copy(id = newId(), taskId = id(it.taskId), done = false, createdAt = now, updatedAt = now)) }
                source.tags.forEach { saveTag(it.copy(id = id(it.id), projectId = projectId, createdAt = now, updatedAt = now)) }
                source.links.forEach { saveLink(it.copy(tagId = id(it.tagId), targetId = id(it.targetId), createdAt = now)) }
                source.pageBlocks.forEach { block ->
                    val content = when {
                        block.typeKey == TaskPageTypeKeys.NOTE -> ownerMap[block.contentId]
                        block.typeKey == TaskPageTypeKeys.TODO -> ids[block.contentId]
                        block.typeKey.startsWith("capture.") -> ids[block.contentId]
                        else -> null
                    }
                    require(content != null) { "Unsupported or missing Page content. Nothing was copied." }
                    savePageBlock(block.copy(id = newId(), taskId = id(block.taskId), contentId = content, createdAt = now, updatedAt = now))
                }
                previews.forEach { saveMapNodePreview(it) }
            }
            project
        }
    }
}
