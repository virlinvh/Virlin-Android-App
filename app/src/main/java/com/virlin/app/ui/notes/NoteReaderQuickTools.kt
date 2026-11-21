package com.virlin.app.ui.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import com.virlin.app.domain.notedoc.NoteDocBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The text selected in the reading view. Read-only blocks keep their selection to themselves;
 * this is where they report it so the reader's Flashcard tool can use it like the editor's.
 */
internal class NoteReaderTextSelection {
    var blockId by mutableStateOf<String?>(null)
        private set
    var range by mutableStateOf(TextRange.Zero)
        private set

    fun report(blockId: String, range: TextRange) {
        this.blockId = blockId
        this.range = range
    }

    fun clear() {
        blockId = null
        range = TextRange.Zero
    }
}

internal val LocalNoteReaderSelection = staticCompositionLocalOf<NoteReaderTextSelection?> { null }

/**
 * The reading view's top bar folds away while reading down and comes back on any scroll up.
 * It takes the scroll while it moves, so the text follows the finger exactly, and fades as it
 * goes; after a fling it settles fully open or fully closed.
 */
internal class NoteReaderHeaderScroll(private val reducedMotion: Boolean) : NestedScrollConnection {
    /** 0 = fully shown, -[fullPx] = fully folded away. */
    var offsetPx by mutableFloatStateOf(0f)
        private set
    var fullPx = 0f

    val shown: Float get() = if (fullPx <= 0f) 1f else 1f + offsetPx / fullPx

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (fullPx <= 0f || available.y == 0f) return Offset.Zero
        val next = (offsetPx + available.y).coerceIn(-fullPx, 0f)
        val used = next - offsetPx
        offsetPx = next
        return Offset(0f, used)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (fullPx <= 0f || offsetPx == 0f || offsetPx == -fullPx) return Velocity.Zero
        val target = if (offsetPx > -fullPx / 2) 0f else -fullPx
        if (reducedMotion) offsetPx = target
        else androidx.compose.animation.core.animate(offsetPx, target,
            animationSpec = androidx.compose.animation.core.tween(180)) { value, _ -> offsetPx = value }
        return Velocity.Zero
    }
}

/** Lays the bar out only as tall as its shown part, so the note below always starts under it. */
internal fun Modifier.noteReaderHeader(scroll: NoteReaderHeaderScroll): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        scroll.fullPx = placeable.height.toFloat()
        val offset = scroll.offsetPx.roundToInt().coerceIn(-placeable.height, 0)
        layout(placeable.width, placeable.height + offset) { placeable.placeRelative(0, offset) }
    }.graphicsLayer { alpha = scroll.shown.coerceIn(0f, 1f) }

/**
 * The reading view's heading fold: [level] headings fold, null opens every heading. Applied to
 * the reader's own view state only; [seq] makes each step a new request.
 */
data class NoteReaderFold(val level: Int?, val seq: Int)

/**
 * The editor's and the reading view's flashcard save: the same dialog, store and messages.
 * [scope] belongs to the screen, so closing the dialog never cancels a save in progress.
 */
@Composable
internal fun NoteFlashcardSaveDialog(
    ownerKey: String,
    noteTitle: String,
    content: List<NoteDocBlock>,
    sourceBlockId: String?,
    scope: CoroutineScope,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var saving by remember(content) { mutableStateOf(false) }
    var error by remember(content) { mutableStateOf<String?>(null) }
    FlashcardCreateDialog(content, saving, error,
        onDismiss = onClose,
        onSave = { front, back, difficulty, days, frontCardStyle ->
            if (!saving) scope.launch {
                saving = true
                runCatching {
                    val question = flashcardSummaryBlock(front)
                    val prompt = question.text.trim().take(300)
                    com.virlin.app.data.flashcard.FlashcardStore.add(context, ownerKey,
                        noteTitle, flashcardSummaryBlock(back), prompt,
                        difficulty, days,
                        frontContent = question.takeIf { it.text.trim() == prompt },
                        frontBlocks = front, backBlocks = back,
                        sourceBlockId = sourceBlockId?.takeIf { it.length <= 256 },
                        frontCardStyle = frontCardStyle)
                }
                    .onSuccess {
                        error = null
                        onClose()
                        android.widget.Toast.makeText(context, "Flashcard saved", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .onFailure { error = "Could not save flashcard: ${it.message}" }
                saving = false
            }
        })
}
