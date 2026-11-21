package com.virlin.app.data.project

import android.content.Context
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.data.flashcard.SavedFlashcard
import com.virlin.app.data.mappreview.MapPreviewImageStore
import com.virlin.app.data.noteaudio.NoteAudioStore
import com.virlin.app.data.noteimage.NoteImageStore
import com.virlin.app.data.projecticon.ProjectIconStore
import com.virlin.app.data.voice.VoiceFileStore
import com.virlin.app.domain.model.AttachmentKind
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.notedoc.CaptureNoteBridge
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.project.ProjectSnapshot
import java.io.File

enum class StorageGroup { MEDIA, DATA }

/** One line of the split. [items] counts files for media and records for data. */
data class StorageRow(val key: String, val label: String, val group: StorageGroup, val bytes: Long, val items: Int)

/**
 * Everything one project keeps on this device, split by kind. Media is the real size of each
 * referenced file (counted once, even when shared); data is the exact encoded size of the
 * project's saved records. Nothing here writes or removes anything.
 */
data class ProjectStorageBreakdown(val rows: List<StorageRow>, val missing: Int) {
    val media: Long get() = rows.filter { it.group == StorageGroup.MEDIA }.sumOf { it.bytes }
    val data: Long get() = rows.filter { it.group == StorageGroup.DATA }.sumOf { it.bytes }
    val total: Long get() = media + data
    val files: Int get() = rows.filter { it.group == StorageGroup.MEDIA }.sumOf { it.items }
    fun row(key: String): StorageRow = rows.first { it.key == key }
}

object ProjectStorageKeys {
    const val NOTE_PICTURES = "note_pictures"
    const val NOTE_RECORDINGS = "note_recordings"
    const val FLASHCARD_PICTURES = "flashcard_pictures"
    const val IMAGES = "images"
    const val PDFS = "pdfs"
    const val FILES = "files"
    const val VOICE = "voice"
    const val MAP_IMAGES = "map_images"
    const val ICON = "icon"
    const val NOTES = "notes"
    const val CAPTURES = "captures"
    const val STRUCTURE = "structure"
    const val FLASHCARDS = "flashcards"
}

/** The project's own flashcards: cards of any of its notes (extra notes `task-1#note-…` included). */
internal suspend fun projectFlashcards(context: Context, s: ProjectSnapshot): List<SavedFlashcard> {
    val owners = s.richNotes.map { it.ownerKey } + "project-${s.project.id}" +
        s.tasks.map { TaskPageTypeKeys.noteOwner(it.id) } + s.captures.map { CaptureNoteBridge.owner(it.id) }
    return FlashcardStore.list(context, owners.toSet())
}

internal fun projectStorageBreakdown(context: Context, s: ProjectSnapshot, cards: List<SavedFlashcard>): ProjectStorageBreakdown {
    val seen = HashSet<String>()
    var missing = 0
    val bytes = HashMap<String, Long>()
    val counts = HashMap<String, Int>()
    fun count(key: String, file: File?) {
        if (file == null || !seen.add(file.canonicalPath)) return
        if (!file.isFile) { missing++; return }
        bytes[key] = (bytes[key] ?: 0L) + file.length()
        counts[key] = (counts[key] ?: 0) + 1
    }
    fun managed(root: File, path: String): File? {
        if (path.isBlank() || File(path).isAbsolute) return null
        val base = root.canonicalFile
        val file = File(base, path).canonicalFile
        return file.takeIf { it.path.startsWith(base.path + File.separator) }
    }

    fun blocksImages(blocks: List<NoteDocBlock>) = blocks.mapNotNull { it.imagePath }
    fun blocksAudio(blocks: List<NoteDocBlock>) = blocks.mapNotNull { it.audio?.file }
    val noteBlocks = s.richNotes.flatMap { it.blocks }
    blocksImages(noteBlocks).distinct().forEach { count(ProjectStorageKeys.NOTE_PICTURES, NoteImageStore.resolve(context, it)) }
    val cardBlocks = cards.flatMap { card ->
        listOfNotNull(card.content, card.frontContent) + card.frontBlocks.orEmpty() + card.backBlocks.orEmpty()
    }
    blocksAudio(noteBlocks + cardBlocks).distinct().forEach { count(ProjectStorageKeys.NOTE_RECORDINGS, NoteAudioStore.resolve(context, it)) }
    blocksImages(cardBlocks).distinct().forEach { count(ProjectStorageKeys.FLASHCARD_PICTURES, NoteImageStore.resolve(context, it)) }

    val attachments = AttachmentFileStore.attachmentsRoot(context)
    s.attachments.forEach { attachment ->
        val key = when (attachment.kind) {
            AttachmentKind.IMAGE -> ProjectStorageKeys.IMAGES
            AttachmentKind.PDF -> ProjectStorageKeys.PDFS
            else -> ProjectStorageKeys.FILES
        }
        val main = managed(attachments, attachment.relativePath)
        count(key, main)
        // The attachment owns its folder: edited copies and other sidecars belong to it too.
        val owner = managed(attachments, attachment.id)
        if (main != null && owner != null && owner.isDirectory && main.path.startsWith(owner.path + File.separator))
            owner.walkTopDown().filter { it.isFile }.forEach { count(key, it) }
    }
    val voices = VoiceFileStore.voicesRoot(context)
    s.voices.flatMap { it.clips }.forEach { count(ProjectStorageKeys.VOICE, managed(voices, it.relativePath)) }
    val previews = MapPreviewImageStore.root(context)
    s.previews.flatMap { it.images }.forEach { count(ProjectStorageKeys.MAP_IMAGES, managed(previews, it.relativePath)) }
    s.project.iconPath?.let { count(ProjectStorageKeys.ICON, managed(ProjectIconStore.root(context), it)) }

    val manifest = ProjectArchive.manifest(s)
    fun encoded(vararg sections: String): Long =
        sections.sumOf { manifest.opt(it)?.toString()?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }

    fun media(key: String, label: String) = StorageRow(key, label, StorageGroup.MEDIA, bytes[key] ?: 0L, counts[key] ?: 0)
    return ProjectStorageBreakdown(listOf(
        media(ProjectStorageKeys.NOTE_PICTURES, "Pictures in notes"),
        media(ProjectStorageKeys.NOTE_RECORDINGS, "Recordings in notes"),
        media(ProjectStorageKeys.FLASHCARD_PICTURES, "Flashcard pictures"),
        media(ProjectStorageKeys.IMAGES, "Images"),
        media(ProjectStorageKeys.PDFS, "PDFs"),
        media(ProjectStorageKeys.FILES, "Other files"),
        media(ProjectStorageKeys.VOICE, "Voice notes"),
        media(ProjectStorageKeys.MAP_IMAGES, "Mind-map images"),
        media(ProjectStorageKeys.ICON, "Project icon"),
        StorageRow(ProjectStorageKeys.NOTES, "Notes & to-do lists", StorageGroup.DATA,
            encoded("richNotes", "notes"), s.richNotes.size + s.notes.size),
        StorageRow(ProjectStorageKeys.CAPTURES, "Captures, prompts & links", StorageGroup.DATA,
            encoded("captures", "prompts"), s.captures.size),
        StorageRow(ProjectStorageKeys.STRUCTURE, "Workstreams, tasks & Pages", StorageGroup.DATA,
            encoded("project", "workstreams", "tasks", "pageBlocks", "steps", "tags", "tagLinks",
                "attachments", "voices", "mapPreviews"),
            1 + s.streams.size + s.tasks.size + s.steps.size + s.pageBlocks.size),
        StorageRow(ProjectStorageKeys.FLASHCARDS, "Flashcards", StorageGroup.DATA,
            FlashcardStore.encodedBytes(cards), cards.size),
    ), missing)
}
