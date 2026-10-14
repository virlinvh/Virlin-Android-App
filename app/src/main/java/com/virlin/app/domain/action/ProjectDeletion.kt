package com.virlin.app.domain.action

import com.virlin.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Exact owned identifiers, also a durable post-commit cleanup receipt. Never arbitrary paths. */
data class ProjectDeletion(
    val projectId: String,
    val streamIds: Set<String>,
    val taskIds: Set<String>,
    val captureIds: Set<String>,
    val attachmentIds: Set<String> = emptySet(),
    val kind: String = "project",
    val owningProjectId: String? = null,
) {
    // projectId remains the receipt/root key for compatibility with existing receipts.
    val ownerIds get() = streamIds + taskIds + if (kind == "project") setOf(projectId) else emptySet()
    val noteOwners get() = taskIds.map { "task-$it" }.toSet() + captureIds.flatMap { listOf("capture-$it", "capture-legacy-$it") } + if (kind == "project") setOf("project-$projectId") else emptySet()
    fun ownsTaskEvent(type: String, detail: String?): Boolean = type in setOf("TASK_CREATED", "TASK_UPDATED", "TASK_COMPLETED", "ACTIVE_TASK_SET", "ACTIVE_TASK_CLEARED") &&
        com.virlin.app.domain.activity.TaskEventDetail.decode(detail)?.taskId in taskIds
    fun encode() = JSONObject().put("project", projectId)
        .put("streams", JSONArray(streamIds.toList())).put("tasks", JSONArray(taskIds.toList()))
        .put("captures", JSONArray(captureIds.toList())).put("attachments", JSONArray(attachmentIds.toList()))
        .put("kind", kind).put("owningProject", owningProjectId).toString()
    companion object {
        const val KEY_PREFIX = "project-delete:"
        fun decode(raw: String): ProjectDeletion {
            val json = JSONObject(raw)
            fun values(key: String) = json.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            return ProjectDeletion(json.getString("project"), values("streams"), values("tasks"), values("captures"), values("attachments"), json.optString("kind", "project"), json.optString("owningProject").takeIf { it.isNotBlank() })
        }
        /** Iterative, cycle-safe traversal: no recursion or nesting-depth limit. */
        private fun descendants(seeds: Set<String>, tasks: List<Task>): Set<String> {
            val children = tasks.groupBy { it.parentTaskId }
            val found = seeds.toMutableSet()
            val queue = java.util.ArrayDeque(seeds)
            while (queue.isNotEmpty()) children[queue.removeFirst()].orEmpty().forEach {
                if (found.add(it.id)) queue.addLast(it.id)
            }
            return found
        }
        fun branch(rootId: String, kind: String, streams: List<WorkStream>, tasks: List<Task>, captures: List<CaptureItem>): ProjectDeletion {
            require(kind == "workstream" || kind == "task")
            val stream = streams.firstOrNull { it.id == rootId }
            val task = tasks.firstOrNull { it.id == rootId }
            val streamIds = if (kind == "workstream") setOf(rootId) else emptySet()
            val taskIds = descendants(if (kind == "task") setOf(rootId) else tasks.filter { it.workStreamId == rootId }.map { it.id }.toSet(), tasks)
            val ownerProject = if (kind == "workstream") stream?.projectId else task?.projectId ?: streams.firstOrNull { it.id == task?.workStreamId }?.projectId
            val capturesOwned = captures.filter { it.workStreamId in streamIds || it.taskId in taskIds ||
                (it.convertedTaskId in taskIds && (it.projectId == null || it.projectId == ownerProject) && it.taskId == null && it.workStreamId == null)
            }.map { it.id }.toSet()
            return ProjectDeletion(rootId, streamIds, taskIds, capturesOwned, kind = kind, owningProjectId = ownerProject)
        }
        fun scope(projectId: String, streams: List<WorkStream>, tasks: List<Task>, captures: List<CaptureItem>): ProjectDeletion {
            val streamIds = streams.filter { it.projectId == projectId }.map { it.id }.toSet()
            val taskIds = descendants(tasks.filter { it.projectId == projectId || it.workStreamId in streamIds }.map { it.id }.toSet(), tasks)
            val captureIds = captures.filter { it.projectId == projectId || it.workStreamId in streamIds || it.taskId in taskIds ||
                (it.projectId == null && it.convertedTaskId in taskIds) }.map { it.id }.toSet()
            return ProjectDeletion(projectId, streamIds, taskIds, captureIds)
        }
    }
}
