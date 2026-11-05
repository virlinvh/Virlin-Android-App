package com.virlin.app.domain.project

import com.virlin.app.domain.model.*
import com.virlin.app.domain.mappreview.MapNodePreview
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import com.virlin.app.domain.repository.WorkStreamRepository

/** A detached, project-scoped content snapshot. Reading never changes running work. */
data class ProjectSnapshot(
    val project: Project,
    val streams: List<WorkStream>,
    val tasks: List<Task>,
    val captures: List<CaptureItem>,
    val notes: List<NoteDocument>,
    val prompts: List<PromptDocument>,
    val attachments: List<AttachmentDocument>,
    val voices: List<VoiceDocument>,
    val richNotes: List<VirlinNoteDoc>,
    val pageBlocks: List<TaskPageBlock>,
    val steps: List<TaskStep>,
    val tags: List<ProjectTag>,
    val links: List<TagLink>,
    val previews: List<MapNodePreview>,
    val stages: List<ExternalStage> = emptyList(),
)

suspend fun WorkStreamRepository.projectSnapshot(projectId: String): ProjectSnapshot {
    val captureIds = captures.value.map { it.id }
    return transaction {
        val project = requireNotNull(getProject(projectId)) { "Project no longer exists." }
        val streams = allStreams().filter { it.projectId == projectId }
        val streamIds = streams.map { it.id }.toSet()
        val tasks = allTasks().filter { it.projectId == projectId || it.workStreamId in streamIds }
        val taskIds = tasks.map { it.id }.toSet()
        val blocks = tasks.flatMap { pageBlocksOf(it.id) }
        val referenced = blocks.filter { it.typeKey.startsWith("capture.") }.map { it.contentId }.toSet()
        val captures = (captureIds + referenced).distinct().mapNotNull { getCapture(it) }.filter {
            it.projectId == projectId || it.workStreamId in streamIds || it.taskId in taskIds || it.id in referenced
        }
        val owners = listOf("project-$projectId") + tasks.map { TaskPageTypeKeys.noteOwner(it.id) } +
            captures.map { CaptureNoteBridge.owner(it.id) } + blocks.filter { it.typeKey == TaskPageTypeKeys.NOTE }.map { it.contentId }
        val tags = allTags().filter { it.projectId == projectId }
        val tagIds = tags.map { it.id }.toSet()
        val captureSet = captures.map { it.id }.toSet()
        ProjectSnapshot(project, streams, tasks, captures,
            captures.mapNotNull { getNoteByCaptureId(it.id) },
            captures.mapNotNull { getPromptByCaptureId(it.id) },
            captures.mapNotNull { getAttachmentByCaptureId(it.id) },
            captures.mapNotNull { getVoiceByCaptureId(it.id) },
            owners.distinct().mapNotNull { noteDocOf(it) }, blocks,
            tasks.flatMap { stepsOf(it.id) }, tags,
            allLinks().filter { it.tagId in tagIds &&
                (if (it.targetType == TagTargetType.TASK) it.targetId in taskIds else it.targetId in captureSet) },
            (listOf(projectId) + streamIds + taskIds).mapNotNull { mapNodePreviewOf(it) },
            (streamIds + taskIds).flatMap { stagesOf(it) })
    }
}
