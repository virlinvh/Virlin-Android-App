package com.virlin.app.domain.activity

import com.virlin.app.domain.model.AttachmentDocument
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.CaptureItem
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.EventType
import com.virlin.app.domain.model.NoteBlock
import com.virlin.app.domain.model.NoteBlockType
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.PromptDocument
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.VoiceDocument
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamEvent
import java.time.Instant

/**
 * PROJECT ACTIVITY — the chronological record of what actually happened inside one project.
 *
 * It is a pure projection over things the app already persists: the append-only
 * [WorkStreamEvent] history of the project's WorkStreams, and the [CaptureItem]s filed to the
 * project. Nothing is invented: a project with no history produces an empty list, and an event
 * is described from the snapshot IT carries rather than from today's state of the task.
 *
 * Knowledge organises the source item; Activity stays chronological, so re-filing a capture
 * never reorders history.
 */
enum class ActivityEntryKind { PROMPT, NOTE, LINK, IMAGE, AUDIO, FILE, TASK, CHECKLIST, EVENT }

data class ProjectActivityEntry(
    val id: String,
    val projectId: String,
    val occurredAt: Instant,
    val kind: ActivityEntryKind,
    val title: String,
    val description: String = "",
    val workStreamId: String? = null,
    val workStreamName: String? = null,
    val taskId: String? = null,
    val taskName: String? = null,
    /** Verbatim body where one exists (prompt / note text, link URL). Never reformatted. */
    val content: String? = null,
    /** The capture this row came from, when it came from one. */
    val captureId: String? = null,
    /** Managed-storage path of the image / audio / file, relative to that store's root. */
    val mediaPath: String? = null,
    val fileName: String? = null,
    val audioDurationMillis: Long? = null,
    val completedSteps: Int? = null,
    val totalSteps: Int? = null,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    /** True for an event that reads the way it did then, from its own recorded snapshot. */
    val hasSnapshot: Boolean = true,
    val sourceLabel: String? = null
)

object ProjectActivity {

    /** Documents already resolved in one batched pass — never per row. */
    data class Documents(
        val notes: Map<String, NoteDocument> = emptyMap(),
        val prompts: Map<String, PromptDocument> = emptyMap(),
        val attachments: Map<String, AttachmentDocument> = emptyMap(),
        val voices: Map<String, VoiceDocument> = emptyMap()
    )

    /**
     * [events] must already be scoped to [project]'s WorkStreams (the repository does that in
     * one batched read). Captures are filtered here by their explicit `projectId`, so a capture
     * filed to another project — or to nothing — can never appear.
     */
    fun build(
        project: Project,
        streams: List<WorkStream>,
        tasks: List<Task>,
        events: List<WorkStreamEvent>,
        captures: List<CaptureItem>,
        documents: Documents = Documents()
    ): List<ProjectActivityEntry> {
        val projectStreams = streams.filter { it.projectId == project.id }
        val streamById = projectStreams.associateBy { it.id }
        val taskById = tasks.associateBy { it.id }

        val fromEvents = events
            .filter { it.workStreamId in streamById }
            .mapNotNull { event -> entry(project, event, streamById, taskById) }

        val fromCaptures = captures
            .filter { it.projectId == project.id }
            .map { capture -> entry(project, capture, streamById, taskById, documents) }

        // Deduplicate by id: a retried write that re-appends the same event id is one row.
        return (fromEvents + fromCaptures)
            .distinctBy { it.id }
            .sortedWith(compareByDescending<ProjectActivityEntry> { it.occurredAt }.thenByDescending { it.id })
    }

    // ------------------------------------------------------------------ events

    private fun entry(
        project: Project,
        event: WorkStreamEvent,
        streams: Map<String, WorkStream>,
        tasks: Map<String, Task>
    ): ProjectActivityEntry? {
        val stream = streams[event.workStreamId] ?: return null
        val snapshot = TaskEventDetail.decode(event.detail).takeIf { event.type.isTaskEvent }
        val task = snapshot?.taskId?.let { tasks[it] }
        val isTask = event.type.isTaskEvent
        return ProjectActivityEntry(
            id = event.id,
            projectId = project.id,
            occurredAt = event.at,
            kind = if (isTask) ActivityEntryKind.TASK else ActivityEntryKind.EVENT,
            title = title(event, snapshot?.title ?: task?.title),
            description = if (isTask) "" else event.detail.orEmpty(),
            workStreamId = stream.id,
            workStreamName = stream.title,
            taskId = snapshot?.taskId ?: task?.id,
            // The title recorded WITH the event, not the task's title today.
            taskName = snapshot?.title ?: task?.title,
            // A transition is only worth stating when something actually moved: editing a task's
            // title records the same status on both sides, and "In progress -> In progress" is
            // noise pretending to be history.
            fromStatus = (snapshot?.fromStatus?.label() ?: event.fromState?.label())
                ?.takeIf { it != (snapshot?.toStatus?.label() ?: event.toState?.label()) },
            toStatus = (snapshot?.toStatus?.label() ?: event.toState?.label())
                ?.takeIf { it != (snapshot?.fromStatus?.label() ?: event.fromState?.label()) },
            // Pre-snapshot task history carries only an id, so it cannot be described as it was.
            hasSnapshot = !isTask || snapshot?.title != null,
            sourceLabel = stream.title
        )
    }

    private val EventType.isTaskEvent: Boolean
        get() = this == EventType.TASK_CREATED || this == EventType.TASK_UPDATED ||
            this == EventType.TASK_COMPLETED || this == EventType.ACTIVE_TASK_SET ||
            this == EventType.ACTIVE_TASK_CLEARED

    private fun title(event: WorkStreamEvent, taskTitle: String?): String {
        val name = taskTitle ?: "Task"
        return when (event.type) {
            EventType.TASK_CREATED -> "Added $name"
            EventType.TASK_COMPLETED -> "Completed $name"
            EventType.TASK_UPDATED -> "Updated $name"
            EventType.ACTIVE_TASK_SET -> "Working on $name"
            EventType.ACTIVE_TASK_CLEARED -> "Stopped working on $name"
            EventType.STREAM_CREATED -> "WorkStream created"
            EventType.FOCUS_STARTED -> "Focus started"
            EventType.FOCUS_LEFT, EventType.LEFT -> "Left focus"
            EventType.HANDOFF -> "Handed off"
            EventType.PROCESSING_STARTED -> "Processing started"
            EventType.RESULT_READY -> "Result ready"
            EventType.RETURN_DEFERRED -> "Return deferred"
            EventType.CHECKED -> "Checked"
            EventType.CHECK_DUE -> "Check due"
            EventType.SNOOZED -> "Snoozed"
            EventType.READY -> "Marked ready"
            EventType.BLOCKED -> "Blocked"
            EventType.UNBLOCKED -> "Unblocked"
            EventType.PAUSED -> "Paused"
            EventType.RESUMED -> "Resumed"
            EventType.CONTEXT_UPDATED -> "Context updated"
            EventType.NOTE_ADDED -> "Note added"
            EventType.COMPLETED -> "WorkStream completed"
        }
    }

    private fun Enum<*>.label(): String =
        name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    // ------------------------------------------------------------------ captures

    private fun entry(
        project: Project,
        capture: CaptureItem,
        streams: Map<String, WorkStream>,
        tasks: Map<String, Task>,
        documents: Documents
    ): ProjectActivityEntry {
        val attachment = documents.attachments[capture.id]
        val voice = documents.voices[capture.id]
        val note = documents.notes[capture.id]
        val prompt = documents.prompts[capture.id]
        val checkboxes = (note?.blocks.orEmpty() + prompt?.blocks.orEmpty()).checkboxes()

        val kind = when (capture.type) {
            CaptureType.PROMPT -> ActivityEntryKind.PROMPT
            CaptureType.LINK -> ActivityEntryKind.LINK
            CaptureType.VOICE -> ActivityEntryKind.AUDIO
            CaptureType.FILE ->
                if (attachment?.kind == AttachmentKind.IMAGE) ActivityEntryKind.IMAGE
                else ActivityEntryKind.FILE
            CaptureType.NOTE ->
                if (checkboxes.isNotEmpty()) ActivityEntryKind.CHECKLIST else ActivityEntryKind.NOTE
        }
        val clip = voice?.sortedClips()?.firstOrNull()
        val body = prompt?.blocks?.plainText() ?: note?.blocks?.plainText() ?: capture.content
        val stream = capture.workStreamId?.let { streams[it] }
        val task = capture.taskId?.let { tasks[it] }
        return ProjectActivityEntry(
            id = "capture:${capture.id}",
            projectId = project.id,
            occurredAt = capture.createdAt,
            kind = kind,
            title = capture.title?.takeIf { it.isNotBlank() }
                ?: attachment?.displayName
                ?: voice?.title?.takeIf { it.isNotBlank() }
                ?: capture.sourceUrl
                ?: body.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
                ?: capture.type.name.lowercase().replaceFirstChar { it.uppercase() },
            description = capture.sourceUrl.orEmpty(),
            // A project-wide capture has no WorkStream and no task; that is normal.
            workStreamId = stream?.id,
            workStreamName = stream?.title,
            taskId = task?.id,
            taskName = task?.title,
            content = body.takeIf { it.isNotBlank() } ?: capture.sourceUrl,
            captureId = capture.id,
            mediaPath = attachment?.relativePath ?: clip?.relativePath,
            fileName = attachment?.displayName ?: clip?.displayName,
            audioDurationMillis = clip?.durationMs,
            completedSteps = checkboxes.count { it.checked }.takeIf { checkboxes.isNotEmpty() },
            totalSteps = checkboxes.size.takeIf { checkboxes.isNotEmpty() },
            // A checklist count is the document's CURRENT saved state: Virlin records no
            // per-tick history, so this row cannot claim to show the state as it was.
            hasSnapshot = kind != ActivityEntryKind.CHECKLIST,
            sourceLabel = "Capture"
        )
    }

    private fun List<NoteBlock>.checkboxes(): List<NoteBlock> =
        filter { it.type == NoteBlockType.CHECKBOX }

    private fun List<NoteBlock>.plainText(): String = NoteBlockText.render(this)

}

/**
 * A note or prompt document as plain text, keeping the structure the writer gave it: headings,
 * list markers, checkbox state and code fences. One renderer, so an excerpt in Knowledge, the
 * body in Activity and what Copy puts on the clipboard are all the same text.
 */
object NoteBlockText {
    fun render(blocks: List<NoteBlock>): String = buildString {
        var number = 0
        blocks.forEach { block ->
            if (block.type == NoteBlockType.NUMBERED_LIST) number++ else number = 0
            val line = when (block.type) {
                NoteBlockType.HEADING_1 -> "# ${block.plainText}"
                NoteBlockType.HEADING_2 -> "## ${block.plainText}"
                NoteBlockType.HEADING_3 -> "### ${block.plainText}"
                NoteBlockType.BULLETED_LIST -> "- ${block.plainText}"
                NoteBlockType.NUMBERED_LIST -> "$number. ${block.plainText}"
                NoteBlockType.CHECKBOX -> "${if (block.checked) "[x]" else "[ ]"} ${block.plainText}"
                NoteBlockType.QUOTE -> "> ${block.plainText}"
                NoteBlockType.DIVIDER -> "---"
                NoteBlockType.CODE -> "```\n${block.plainText}\n```"
                else -> block.plainText
            }
            if (line.isNotBlank()) appendLine(line)
            val children = render(block.children)
            if (children.isNotBlank()) appendLine(children)
        }
    }.trim()

}

/**
 * What a saved document actually IS, read from the blocks the writer saved rather than from a
 * label attached later. A document holding checkboxes is a checklist; one holding an ordered
 * list is a set of steps. There is one canonical capture either way — this classifies it, it
 * never copies it.
 */
object NoteBlockShape {

    data class Counted(val completed: Int, val total: Int)

    /** Checkbox rows, in document order, including those nested inside a toggle. */
    fun checkboxes(blocks: List<NoteBlock>): List<NoteBlock> =
        flatten(blocks).filter { it.type == NoteBlockType.CHECKBOX }

    /** Ordered steps, in document order. */
    fun steps(blocks: List<NoteBlock>): List<NoteBlock> =
        flatten(blocks).filter { it.type == NoteBlockType.NUMBERED_LIST }

    fun checklistProgress(blocks: List<NoteBlock>): Counted? =
        checkboxes(blocks).takeIf { it.isNotEmpty() }
            ?.let { Counted(it.count { box -> box.checked }, it.size) }

    fun stepProgress(blocks: List<NoteBlock>): Counted? =
        steps(blocks).takeIf { it.isNotEmpty() }?.let { Counted(0, it.size) }

    private fun flatten(blocks: List<NoteBlock>): List<NoteBlock> =
        blocks.flatMap { listOf(it) + flatten(it.children) }
}
