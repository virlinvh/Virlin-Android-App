package com.virlin.app.data.project

import android.content.Context
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.data.voice.VoiceFileStore
import com.virlin.app.data.projecticon.ProjectIconStore
import com.virlin.app.data.mappreview.MapPreviewImageStore
import com.virlin.app.domain.project.*
import com.virlin.app.domain.repository.WorkStreamRepository
import java.io.File
import java.time.Instant
import java.util.UUID

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
    internal fun files(s: ProjectSnapshot): Map<Pair<String, String>, File> {
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

    /** The full split for the storage screen: every media file and the project's saved data. */
    suspend fun breakdown(s: ProjectSnapshot): ProjectStorageBreakdown =
        projectStorageBreakdown(context, s, projectFlashcards(context, s))

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

    /** Writes the whole project as a verified `.virlin` file in the app cache, ready to be saved elsewhere. */
    suspend fun exportVirlin(s: ProjectSnapshot, timing: VirlinTiming): VirlinExport =
        VirlinProjectWriter(context, repository, this).write(s, timing)
}
