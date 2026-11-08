package com.virlin.app.data.flashcard

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The surface a deck's cards are drawn on. */
enum class FlashcardDesign {
    CLASSIC, PAPER, HAND_DRAWN, STICKY, INDEX, KRAFT, WATERCOLOR,
    DOTTED, POLAROID, OUTLINE, VINTAGE, BOLD, CHALKBOARD, BLUEPRINT,
}

/** Where a card's colour comes from: its own shade, one fill, a two-colour gradient or the project. */
enum class FlashcardColorMode { MIXED, FILL, GRADIENT, PROJECT }

enum class FlashcardGradientDirection { DOWN, DIAGONAL, ACROSS }

enum class FlashcardTypeface { SERIF, SANS, HANDWRITTEN, MARKER, ELEGANT, POSTER, ROUNDED, TYPEWRITER }

enum class FlashcardTextSize { SMALL, MEDIUM, LARGE }

enum class FlashcardCorners { ROUND, SOFT, SQUARE }

/**
 * How one branch's deck looks. The defaults are the deck's original appearance, so a branch that
 * was never customised draws exactly as before. Only the card fronts and surfaces change; the
 * cards themselves and their schedules are never touched.
 */
data class FlashcardDeckStyle(
    val design: FlashcardDesign = FlashcardDesign.CLASSIC,
    val colorMode: FlashcardColorMode = FlashcardColorMode.MIXED,
    val color: Int = 0xFFFFD8A8.toInt(),
    val color2: Int = 0xFFC9B6FF.toInt(),
    val direction: FlashcardGradientDirection = FlashcardGradientDirection.DIAGONAL,
    val typeface: FlashcardTypeface = FlashcardTypeface.SERIF,
    val textSize: FlashcardTextSize = FlashcardTextSize.MEDIUM,
    val centered: Boolean = false,
    val corners: FlashcardCorners = FlashcardCorners.ROUND,
    val scattered: Boolean = false,
    val showDetails: Boolean = true,
)

/** One small file of per-branch deck styles, keyed by the branch the deck was opened for. */
object FlashcardDeckStyleStore {
    private val mutex = Mutex()
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "flashcard-deck-styles-v1.json"))

    fun deckKey(kind: String, id: String): String = "$kind:$id"

    suspend fun get(context: Context, deckKey: String): FlashcardDeckStyle = mutex.withLock {
        withContext(Dispatchers.IO) {
            read(context).optJSONObject(deckKey)?.let(::decode) ?: FlashcardDeckStyle()
        }
    }

    /** Saving the default style removes the branch's entry, so it simply follows the defaults. */
    suspend fun save(context: Context, deckKey: String, style: FlashcardDeckStyle) = mutex.withLock {
        require(deckKey.length in 1..512) { "Deck key must be 1–512 characters" }
        withContext(Dispatchers.IO) {
            val decks = read(context)
            if (style == FlashcardDeckStyle()) decks.remove(deckKey) else decks.put(deckKey, encode(style))
            write(context, decks)
        }
    }

    private fun read(context: Context): JSONObject {
        val atomic = file(context)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return JSONObject()
        val root = JSONObject(atomic.openRead().bufferedReader().use { it.readText() })
        if (root.optInt("v") != 1) error("Unsupported deck style version")
        return root.optJSONObject("decks") ?: JSONObject()
    }

    private fun write(context: Context, decks: JSONObject) {
        val root = JSONObject().put("v", 1).put("decks", decks)
        val atomic = file(context)
        val output = atomic.startWrite()
        try {
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
            // On Windows-hosted Robolectric, AtomicFile's renameTo cannot replace an existing
            // target and silently leaves .new behind; promote it with replacement semantics.
            val pending = File(atomic.baseFile.path + ".new")
            if (pending.exists()) Files.move(pending.toPath(), atomic.baseFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun encode(style: FlashcardDeckStyle) = JSONObject()
        .put("design", style.design.name).put("colorMode", style.colorMode.name)
        .put("color", style.color).put("color2", style.color2)
        .put("direction", style.direction.name).put("typeface", style.typeface.name)
        .put("textSize", style.textSize.name).put("centered", style.centered)
        .put("corners", style.corners.name).put("scattered", style.scattered)
        .put("showDetails", style.showDetails)

    // Names written by a newer build fall back to the default for that one setting.
    private fun decode(json: JSONObject): FlashcardDeckStyle {
        val d = FlashcardDeckStyle()
        return FlashcardDeckStyle(
            design = json.enumOr("design", d.design),
            colorMode = json.enumOr("colorMode", d.colorMode),
            color = json.optInt("color", d.color),
            color2 = json.optInt("color2", d.color2),
            direction = json.enumOr("direction", d.direction),
            typeface = json.enumOr("typeface", d.typeface),
            textSize = json.enumOr("textSize", d.textSize),
            centered = json.optBoolean("centered", d.centered),
            corners = json.enumOr("corners", d.corners),
            scattered = json.optBoolean("scattered", d.scattered),
            showDetails = json.optBoolean("showDetails", d.showDetails),
        )
    }

    private inline fun <reified E : Enum<E>> JSONObject.enumOr(field: String, fallback: E): E =
        optString(field).let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: fallback
}
