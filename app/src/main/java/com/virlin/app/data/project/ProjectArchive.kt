package com.virlin.app.data.project

import com.virlin.app.domain.project.ProjectSnapshot
import com.virlin.app.domain.model.*
import com.virlin.app.domain.note.NoteDocumentCodec
import com.virlin.app.domain.notedoc.VirlinNoteCodec
import com.virlin.app.domain.mappreview.MapPreviewCodec
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Explicit, versioned fields: never reflection/obfuscated names or another project's database. */
object ProjectArchive {
    fun json(vararg fields: Pair<String, Any?>): JSONObject = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, when (value) {
            null -> JSONObject.NULL
            is Instant -> value.toString()
            is java.time.Duration -> value.toString()
            is Enum<*> -> value.name
            else -> value
        }) }
    }
    fun manifest(s: ProjectSnapshot): JSONObject {
        val p = s.project
        return json("format" to "virlin-project-archive", "version" to 1, "exportedAt" to Instant.now(),
            "project" to json("id" to p.id, "title" to p.title, "description" to p.description,
                "status" to p.status, "priority" to p.priority, "dueAt" to p.dueAt,
                "estimatedEffort" to p.estimatedEffort, "defaultExecutionMode" to p.defaultExecutionMode,
                "createdAt" to p.createdAt, "updatedAt" to p.updatedAt, "completedAt" to p.completedAt,
                "iconId" to p.iconId, "iconPath" to p.iconPath),
            "workstreams" to JSONArray(s.streams.map { w -> json("id" to w.id, "projectId" to w.projectId,
                "title" to w.title, "tool" to w.tool, "externalActorId" to w.externalActorId,
                "executionPreference" to w.executionPreference, "state" to w.state, "priority" to w.priority,
                "pinned" to w.pinned, "attentionRank" to w.attentionRank, "sortOrder" to w.sortOrder,
                "lastHumanAction" to w.lastHumanAction, "waitingFor" to w.waitingFor, "nextHumanAction" to w.nextHumanAction,
                "blockerReason" to w.blockerReason, "processingStartedAt" to w.processingStartedAt,
                "externalTrackedSeconds" to w.externalTrackedSeconds, "externalTrackingPaused" to w.externalTrackingPaused,
                "checkAt" to w.checkAt, "snoozedUntil" to w.snoozedUntil, "snoozeReason" to w.snoozeReason,
                "currentCycleId" to w.currentCycleId, "cycleCount" to w.cycleCount, "activeTaskId" to w.activeTaskId,
                "createdAt" to w.createdAt, "updatedAt" to w.updatedAt, "completedAt" to w.completedAt) }),
            "tasks" to JSONArray(s.tasks.map { t -> json("id" to t.id, "projectId" to t.projectId,
                "workStreamId" to t.workStreamId, "parentTaskId" to t.parentTaskId, "title" to t.title,
                "description" to t.description, "notes" to t.notes, "status" to t.status, "order" to t.order,
                "estimatedEffort" to t.estimatedEffort, "dueAt" to t.dueAt, "reminderAt" to t.reminderAt,
                "priority" to t.priority, "executionPreference" to t.executionPreference,
                "createdAt" to t.createdAt, "updatedAt" to t.updatedAt, "completedAt" to t.completedAt,
                "execution" to t.execution?.let { a -> json("state" to a.state, "processingStartedAt" to a.processingStartedAt,
                    "externalTrackedSeconds" to a.externalTrackedSeconds, "externalTrackingPaused" to a.externalTrackingPaused,
                    "checkAt" to a.checkAt, "snoozeReason" to a.snoozeReason, "blockerReason" to a.blockerReason,
                    "currentCycleId" to a.currentCycleId, "cycleCount" to a.cycleCount, "actorId" to a.actorId,
                    "tool" to a.tool, "instruction" to a.instruction,
                    "attentionRank" to a.attentionRank) }) }),
            "captures" to JSONArray(s.captures.map { c -> json("id" to c.id, "type" to c.type, "title" to c.title,
                "content" to c.content, "sourceUrl" to c.sourceUrl, "projectId" to c.projectId,
                "workStreamId" to c.workStreamId, "taskId" to c.taskId, "status" to c.status,
                "convertedTaskId" to c.convertedTaskId, "createdAt" to c.createdAt, "updatedAt" to c.updatedAt, "archivedAt" to c.archivedAt) }),
            "notes" to JSONArray(s.notes.map { n -> json("id" to n.id, "captureItemId" to n.captureItemId,
                "title" to n.title, "payload" to JSONObject(NoteDocumentCodec.encodePayload(n.blocks)),
                "createdAt" to n.createdAt, "updatedAt" to n.updatedAt) }),
            "prompts" to JSONArray(s.prompts.map { n -> json("id" to n.id, "captureItemId" to n.captureItemId,
                "title" to n.title, "description" to n.description, "tags" to JSONArray(n.tags),
                "payload" to JSONObject(NoteDocumentCodec.encodePayload(n.blocks)), "sourceText" to n.sourceText,
                "mode" to n.mode, "language" to n.language, "responseText" to n.responseText,
                "createdAt" to n.createdAt, "updatedAt" to n.updatedAt) }),
            "attachments" to JSONArray(s.attachments.map { a -> json("id" to a.id, "captureItemId" to a.captureItemId,
                "displayName" to a.displayName, "mimeType" to a.mimeType, "sizeBytes" to a.sizeBytes,
                "relativePath" to a.relativePath, "kind" to a.kind, "createdAt" to a.createdAt, "updatedAt" to a.updatedAt) }),
            "voices" to JSONArray(s.voices.map { v -> json("id" to v.id, "captureItemId" to v.captureItemId,
                "title" to v.title, "createdAt" to v.createdAt, "updatedAt" to v.updatedAt,
                "clips" to JSONArray(v.clips.map { c -> json("id" to c.id, "displayName" to c.displayName,
                    "relativePath" to c.relativePath, "durationMs" to c.durationMs, "sizeBytes" to c.sizeBytes,
                    "mimeType" to c.mimeType, "sortOrder" to c.sortOrder, "createdAt" to c.createdAt) })) }),
            "richNotes" to JSONArray(s.richNotes.map { n -> json("ownerKey" to n.ownerKey, "title" to n.title,
                "payload" to JSONObject(VirlinNoteCodec.encode(n.blocks)), "revision" to n.revision,
                "createdAt" to n.createdAt, "updatedAt" to n.updatedAt) }),
            "pageBlocks" to JSONArray(s.pageBlocks.map { b -> json("id" to b.id, "taskId" to b.taskId,
                "typeKey" to b.typeKey, "contentId" to b.contentId, "order" to b.order, "createdAt" to b.createdAt, "updatedAt" to b.updatedAt) }),
            "steps" to JSONArray(s.steps.map { t -> json("id" to t.id, "taskId" to t.taskId, "text" to t.text,
                "done" to t.done, "order" to t.order, "createdAt" to t.createdAt, "updatedAt" to t.updatedAt) }),
            "tags" to JSONArray(s.tags.map { t -> json("id" to t.id, "projectId" to t.projectId, "name" to t.name,
                "createdAt" to t.createdAt, "updatedAt" to t.updatedAt) }),
            "tagLinks" to JSONArray(s.links.map { l -> json("tagId" to l.tagId, "targetType" to l.targetType,
                "targetId" to l.targetId, "createdAt" to l.createdAt) }),
            "mapPreviews" to JSONArray(s.previews.map { p -> json("ownerId" to p.ownerId,
                "payload" to JSONObject(MapPreviewCodec.encode(p)), "createdAt" to p.createdAt, "updatedAt" to p.updatedAt) }))
    }

    fun markdown(s: ProjectSnapshot): String = buildString {
        fun label(text: String) = text.replace("\\", "\\\\").replace("#", "\\#").replace("[", "\\[").replace("]", "\\]").replace("\n", " ")
        fun content(text: String?) { if (!text.isNullOrBlank()) {
            val fence = "`".repeat((Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0).coerceAtLeast(2) + 1)
            appendLine(fence); appendLine(text); appendLine(fence); appendLine()
        } }
        appendLine("# ${label(s.project.title)}"); appendLine()
        appendLine("Status: ${s.project.status.name} · Default execution: ${s.project.defaultExecutionMode.name}")
        appendLine(); content(s.project.description)
        fun tree(streamId: String?) {
            val grouped = s.tasks.filter { it.workStreamId == streamId }.groupBy { it.parentTaskId }
            val pending = java.util.ArrayDeque<Pair<Task, Int>>()
            grouped[null].orEmpty().sortedBy { it.order }.asReversed().forEach { pending.addLast(it to 0) }
            val seen = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val (task, depth) = pending.removeLast(); if (!seen.add(task.id)) continue
                appendLine("${"  ".repeat(depth.coerceAtMost(32))}- [${if (task.status.isCompleted) "x" else " "}] ${label(task.title)} (${task.status.name}, ${task.executionPreference.name})")
                content(task.description); content(task.notes)
                s.steps.filter { it.taskId == task.id }.sortedBy { it.order }.forEach { appendLine("  - [${if (it.done) "x" else " "}] ${label(it.text)}") }
                grouped[task.id].orEmpty().sortedBy { it.order }.asReversed().forEach { pending.addLast(it to depth + 1) }
            }
            appendLine()
        }
        appendLine("## Standalone tasks"); tree(null)
        s.streams.sortedBy { it.sortOrder }.forEach { stream ->
            appendLine("## ${label(stream.title)}"); appendLine("${stream.state.name} · ${stream.executionPreference.name}"); appendLine(); tree(stream.id)
        }
        appendLine("## Knowledge and content"); appendLine()
        s.captures.forEach { c ->
            appendLine("### ${label(c.title ?: c.type.name)}"); content(c.content); content(c.sourceUrl)
            s.notes.filter { it.captureItemId == c.id }.forEach { n ->
                fun blocks(list: List<NoteBlock>) { list.forEach { content(it.plainText); blocks(it.children) } }; blocks(n.blocks)
            }
            s.prompts.filter { it.captureItemId == c.id }.forEach { prompt ->
                content(prompt.description); content(prompt.sourceText); content(prompt.responseText)
                if (prompt.sourceText.isBlank()) {
                    fun blocks(list: List<NoteBlock>) { list.forEach { content(it.plainText); blocks(it.children) } }
                    blocks(prompt.blocks)
                }
            }
            s.attachments.filter { it.captureItemId == c.id }.forEach { appendLine("File: ${label(it.displayName)} (${it.sizeBytes} bytes, ${it.mimeType})") }
            s.voices.filter { it.captureItemId == c.id }.flatMap { it.clips }.forEach { appendLine("Audio: ${label(it.displayName)} (${it.durationMs} ms)") }
            appendLine()
        }
        s.richNotes.forEach { n -> appendLine("### ${label(n.title ?: "Notes")}"); n.blocks.forEach { content(it.text); it.table?.cells?.forEach(::content) } }
        s.previews.forEach { p -> appendLine("### Map preview · ${label(p.ownerId)}"); content(p.description.text); appendLine("${p.images.size} images (originals included in ZIP archive)") }
        appendLine(); appendLine("Text summary. PDF/Markdown do not embed media originals or restore the project. Use the ZIP archive to keep managed media and structured data.")
    }
}
