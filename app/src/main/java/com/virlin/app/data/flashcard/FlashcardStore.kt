package com.virlin.app.data.flashcard

import android.content.Context
import android.util.AtomicFile
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.VirlinNoteCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SavedFlashcard(
    val id: String,
    val ownerKey: String,
    val noteTitle: String,
    val content: NoteDocBlock,
    val createdAt: Long,
    val colorIndex: Int,
    val recallPrompt: String = "",
    val difficulty: String = "NORMAL",
    val intervalDays: Int = 1,
    val nextReviewAt: Long = createdAt + 86_400_000L,
    val reviewCount: Int = 0,
    val lastReviewedAt: Long? = null,
    val isStarred: Boolean = false,
    /** Optional rich front face; legacy cards continue to use recallPrompt. */
    val frontContent: NoteDocBlock? = null,
    /**
     * The exact note blocks on each face (headings, code, prompts, media, every run's font and
     * colour). Null on cards made before rich sides existed; [content] and [recallPrompt] remain
     * the plain summaries every card has.
     */
    val frontBlocks: List<NoteDocBlock>? = null,
    val backBlocks: List<NoteDocBlock>? = null,
    /** Kept but out of every study queue until restored. */
    val isArchived: Boolean = false,
    /** The note block the card was made from; null on older cards, which are found by text. */
    val sourceBlockId: String? = null,
    /** The front is drawn in the deck's card typeface; off keeps the note's own formatting. */
    val frontCardStyle: Boolean = true,
)

enum class FlashcardRating { AGAIN, HARD, GOOD, EASY }

/** Independent, atomic card collection. Saving a card never edits its source note. */
object FlashcardStore {
    /** Most blocks one face may hold; a card is a passage, not a whole note. */
    const val MAX_SIDE_BLOCKS = 200
    private val mutex = Mutex()
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "flashcards-v1.json"))

    suspend fun list(context: Context, ownerKey: String): List<SavedFlashcard> = mutex.withLock {
        withContext(Dispatchers.IO) { read(context).filter { it.ownerKey == ownerKey }.sortedByDescending { it.createdAt } }
    }

    /** Cards of [ownerKeys], including those of each owner's own notes (`task-1#note-…` under `task-1`). */
    suspend fun list(context: Context, ownerKeys: Set<String>): List<SavedFlashcard> = mutex.withLock {
        withContext(Dispatchers.IO) {
            read(context).filter { it.ownerKey in ownerKeys || it.ownerKey.substringBefore('#') in ownerKeys }
                .sortedByDescending { it.createdAt }
        }
    }

    suspend fun add(context: Context, ownerKey: String, noteTitle: String,
                    content: NoteDocBlock, recallPrompt: String = "",
                    difficulty: String = "NORMAL", intervalDays: Int = 1,
                    frontContent: NoteDocBlock? = null,
                    frontBlocks: List<NoteDocBlock>? = null,
                    backBlocks: List<NoteDocBlock>? = null,
                    sourceBlockId: String? = null,
                    frontCardStyle: Boolean = true): SavedFlashcard = mutex.withLock {
        require(ownerKey.isNotBlank() && ownerKey.length <= 256)
        require(intervalDays in 1..365)
        requireFaces(content, recallPrompt, difficulty, frontContent, frontBlocks, backBlocks)
        require(sourceBlockId == null || sourceBlockId.length in 1..256)
        withContext(Dispatchers.IO) {
            val existing = read(context)
            val now = System.currentTimeMillis()
            val card = SavedFlashcard(UUID.randomUUID().toString(), ownerKey, noteTitle.take(200),
                content, now, (existing.maxOfOrNull { it.colorIndex } ?: -1) + 1,
                recallPrompt.trim(), difficulty, intervalDays, now + intervalDays * 86_400_000L,
                frontContent = frontContent, frontBlocks = frontBlocks, backBlocks = backBlocks,
                sourceBlockId = sourceBlockId, frontCardStyle = frontCardStyle)
            write(context, existing + card)
            card
        }
    }

    /**
     * Replaces a card's faces and difficulty. Its schedule, reviews, star, archive state, colour
     * and source stay as they were, so editing never resets learning progress.
     */
    suspend fun update(context: Context, cardId: String, content: NoteDocBlock,
                       recallPrompt: String, difficulty: String,
                       frontContent: NoteDocBlock?, frontBlocks: List<NoteDocBlock>?,
                       backBlocks: List<NoteDocBlock>?,
                       frontCardStyle: Boolean = true): SavedFlashcard = mutex.withLock {
        requireFaces(content, recallPrompt, difficulty, frontContent, frontBlocks, backBlocks)
        withContext(Dispatchers.IO) {
            val existing = read(context)
            val card = existing.firstOrNull { it.id == cardId } ?: error("Flashcard no longer exists")
            val updated = card.copy(content = content, recallPrompt = recallPrompt.trim(),
                difficulty = difficulty, frontContent = frontContent,
                frontBlocks = frontBlocks, backBlocks = backBlocks, frontCardStyle = frontCardStyle)
            write(context, existing.map { if (it.id == cardId) updated else it })
            updated
        }
    }

    private fun requireFaces(content: NoteDocBlock, recallPrompt: String, difficulty: String,
                             frontContent: NoteDocBlock?, frontBlocks: List<NoteDocBlock>?,
                             backBlocks: List<NoteDocBlock>?) {
        require(content.text.isNotBlank() && content.text.length <= 20_000)
        require(recallPrompt.length <= 300)
        require(difficulty in setOf("EASY", "NORMAL", "HARD"))
        require(frontContent == null || frontContent.text.trim() == recallPrompt.trim())
        require(frontBlocks == null || frontBlocks.size in 1..MAX_SIDE_BLOCKS)
        require(backBlocks == null || backBlocks.size in 1..MAX_SIDE_BLOCKS)
    }

    /** Updates only the reviewed card. A failed write leaves its previous schedule recoverable. */
    suspend fun review(context: Context, cardId: String, rating: FlashcardRating,
                       now: Long = System.currentTimeMillis()): SavedFlashcard = mutex.withLock {
        withContext(Dispatchers.IO) {
            val existing = read(context)
            val card = existing.firstOrNull { it.id == cardId } ?: error("Flashcard no longer exists")
            val days = when (rating) {
                FlashcardRating.AGAIN -> 0
                FlashcardRating.HARD -> if (card.reviewCount == 0 || card.intervalDays <= 1) 0
                    else (card.intervalDays / 2).coerceAtLeast(1)
                FlashcardRating.GOOD -> if (card.reviewCount == 0 || card.intervalDays == 0) 1
                    else (card.intervalDays * 2).coerceAtMost(365)
                FlashcardRating.EASY -> if (card.reviewCount == 0 || card.intervalDays == 0) 3
                    else (card.intervalDays * 3).coerceAtMost(365)
            }
            val next = when (rating) {
                FlashcardRating.AGAIN -> now + 10 * 60_000L
                FlashcardRating.HARD -> now + if (days == 0) 12 * 3_600_000L else days * 86_400_000L
                else -> now + days * 86_400_000L
            }
            val updated = card.copy(intervalDays = days, nextReviewAt = next,
                reviewCount = card.reviewCount + 1, lastReviewedAt = now)
            write(context, existing.map { if (it.id == cardId) updated else it })
            updated
        }
    }

    /** Cards follow their note when it moves to a new owner key; nothing else on them changes. */
    suspend fun rekey(context: Context, fromOwnerKey: String, toOwnerKey: String): Int = mutex.withLock {
        require(toOwnerKey.isNotBlank() && toOwnerKey.length <= 256)
        withContext(Dispatchers.IO) {
            val existing = read(context)
            val moved = existing.count { it.ownerKey == fromOwnerKey }
            if (moved > 0) write(context, existing.map {
                if (it.ownerKey == fromOwnerKey) it.copy(ownerKey = toOwnerKey) else it
            })
            moved
        }
    }

    suspend fun setStarred(context: Context, cardId: String, starred: Boolean): SavedFlashcard =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val existing = read(context)
                val card = existing.firstOrNull { it.id == cardId }
                    ?: error("Flashcard no longer exists")
                val updated = card.copy(isStarred = starred)
                write(context, existing.map { if (it.id == cardId) updated else it })
                updated
            }
        }

    suspend fun setArchived(context: Context, cardId: String, archived: Boolean): SavedFlashcard =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val existing = read(context)
                val card = existing.firstOrNull { it.id == cardId }
                    ?: error("Flashcard no longer exists")
                val updated = card.copy(isArchived = archived)
                write(context, existing.map { if (it.id == cardId) updated else it })
                updated
            }
        }

    private fun read(context: Context): List<SavedFlashcard> {
        val atomic = file(context)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return emptyList()
        val root = JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
        if (root.optInt("v") != 1) error("Unsupported flashcard store version")
        val rows = root.getJSONArray("cards")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val content = VirlinNoteCodec.decode(row.getString("content")).single()
            SavedFlashcard(row.getString("id"), row.getString("ownerKey"),
                row.getString("noteTitle"), content, row.getLong("createdAt"),
                row.optInt("colorIndex", index), row.optString("recallPrompt"),
                row.optString("difficulty", "NORMAL"), row.optInt("intervalDays", 1),
                row.optLong("nextReviewAt", row.getLong("createdAt") + 86_400_000L),
                row.optInt("reviewCount", 0),
                row.optLong("lastReviewedAt", 0L).takeIf { it > 0L },
                row.optBoolean("isStarred", false),
                row.optString("frontContent").takeIf { it.isNotBlank() }
                    ?.let { VirlinNoteCodec.decode(it).singleOrNull() },
                row.richSide("frontBlocks"), row.richSide("backBlocks"),
                isArchived = row.optBoolean("isArchived", false),
                sourceBlockId = row.optString("sourceBlockId").takeIf { it.isNotBlank() },
                frontCardStyle = row.optBoolean("frontCardStyle", true))
        }
    }

    // A face that fails to decode falls back to the card's plain summary instead of hiding the card.
    private fun JSONObject.richSide(field: String): List<NoteDocBlock>? =
        optString(field).takeIf { it.isNotBlank() }
            ?.let { runCatching { VirlinNoteCodec.decode(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

    private fun write(context: Context, cards: List<SavedFlashcard>) {
        val root = JSONObject().put("v", 1).put("cards", JSONArray().also { rows ->
            cards.forEach { card -> rows.put(JSONObject()
                .put("id", card.id).put("ownerKey", card.ownerKey)
                .put("noteTitle", card.noteTitle).put("createdAt", card.createdAt)
                .put("colorIndex", card.colorIndex)
                .put("recallPrompt", card.recallPrompt).put("difficulty", card.difficulty)
                .put("intervalDays", card.intervalDays).put("nextReviewAt", card.nextReviewAt)
                .put("reviewCount", card.reviewCount)
                .put("lastReviewedAt", card.lastReviewedAt ?: 0L)
                .put("isStarred", card.isStarred)
                .put("isArchived", card.isArchived)
                .put("frontCardStyle", card.frontCardStyle)
                .put("content", VirlinNoteCodec.encode(listOf(card.content)))
                .also { json -> card.frontContent?.let {
                    json.put("frontContent", VirlinNoteCodec.encode(listOf(it)))
                } }
                .also { json ->
                    card.frontBlocks?.let { json.put("frontBlocks", VirlinNoteCodec.encode(it)) }
                    card.backBlocks?.let { json.put("backBlocks", VirlinNoteCodec.encode(it)) }
                    card.sourceBlockId?.let { json.put("sourceBlockId", it) }
                }) }
        })
        val atomic = file(context)
        val output = atomic.startWrite()
        try {
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            // On Windows-hosted Robolectric, AtomicFile's renameTo cannot replace an existing
            // target and silently leaves .new behind. Promote it with replacement semantics;
            // on Android the helper already moved it and this path is not taken.
            val pending = File(atomic.baseFile.path + ".new")
            if (pending.exists()) Files.move(pending.toPath(), atomic.baseFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}
