package com.virlin.app.data.project

import android.content.Context
import com.virlin.app.domain.action.ProjectDeletion
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.schedule.AttentionScheduler
import com.virlin.app.platform.AttentionNotifications
import com.virlin.app.ui.map.MapAppearanceStore
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Receipts are acknowledged only after every exact, app-private target is cleaned. */
object ProjectDeletionCleanup {
    private val mutex = Mutex()
    suspend fun flush(context: Context, repository: WorkStreamRepository, scheduler: AttentionScheduler) = mutex.withLock {
        repository.pendingProjectDeletions().forEach { report ->
            check(when (report.kind) {
                "project" -> repository.getProject(report.projectId) == null
                "workstream" -> repository.getStream(report.projectId) == null
                "task" -> repository.getTask(report.projectId) == null
                else -> false
            }) { "Refusing cleanup for a live or unknown deletion root." }
            (report.streamIds + report.taskIds).forEach {
                scheduler.cancel(it)
                AttentionNotifications.cancel(context, it)
            }
            report.attachmentIds.forEach { deleteManagedTree(context, "attachments", it) }
            report.captureIds.forEach { deleteManagedTree(context, "voices", it) }
            if (report.kind == "project") deleteManagedTree(context, "project-icons", report.projectId)
            report.ownerIds.forEach {
                deleteManagedTree(context, "map_previews", com.virlin.app.data.mappreview.MapPreviewImageStore.safe(it))
            }
            for (name in listOf("project_appearance", "hierarchy_timer_display", "project_block_order")) {
                val edit = context.getSharedPreferences(name, 0).edit()
                report.ownerIds.forEach { edit.remove(it) }
                if (name == "hierarchy_timer_display" && report.kind == "project") edit.remove("project:${report.projectId}")
                check(edit.commit()) { "Could not remove project settings." }
            }
            if (report.kind == "project") MapAppearanceStore.get(context).remove(report.projectId)
            else MapAppearanceStore.get(context).removeOwners(report.ownerIds)
            repository.transaction { acknowledgeProjectDeletion(report.projectId) }
        }
    }

    internal fun deleteManagedTree(context: Context, rootName: String, id: String) {
        require(rootName in setOf("attachments", "voices", "project-icons", "map_previews"))
        require(id.isNotBlank() && id != "." && id != ".." && '/' !in id && '\\' !in id) { "Unsafe managed identifier." }
        val root = File(context.filesDir, rootName).canonicalFile
        require(root.parentFile == context.filesDir.canonicalFile) { "Managed root escapes app storage." }
        val target = File(root, id)
        require(target.canonicalFile.parentFile == root) { "Managed target escapes its root." }
        fun remove(file: File) {
            // Never follow symbolic links to another managed tree or external file.
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) { check(file.delete()); return }
            if (file.isDirectory) {
                val entries = file.listFiles() ?: error("Could not inspect managed directory.")
                entries.forEach { child ->
                    require(java.nio.file.Files.isSymbolicLink(child.toPath()) || child.canonicalPath.startsWith(root.path + File.separator)) { "Unsafe managed child." }
                    remove(child)
                }
            }
            check(!file.exists() || file.delete()) { "Could not remove ${file.name}. Cleanup will retry." }
        }
        remove(target)
    }
}
