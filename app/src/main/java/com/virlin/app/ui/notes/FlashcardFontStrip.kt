package com.virlin.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteRuns

/** A font a card can use; [name] null is the card's default type. */
internal data class FlashcardFont(val name: String?, val label: String)

/**
 * Handwritten and stylish faces first, then easy-to-read ones. Every name is a note font, so a
 * card's font also renders in its note and in every export that knows note fonts.
 */
internal val FlashcardFonts: List<FlashcardFont> by lazy {
    listOf(FlashcardFont(null, "Default")) + listOf(
        "Caveat", "Patrick Hand", "Indie Flower", "Kalam", "Shadows Into Light", "Architects Daughter",
        "Gloria Hallelujah", "Handlee", "Dancing Script", "Pacifico", "Great Vibes", "Satisfy", "Lobster",
        "Permanent Marker", "Amatic SC", "Abril Fatface", "Playfair Display", "Cinzel",
        "Lexend", "Nunito", "Open Sans", "Lato", "Roboto", "Merriweather", "Lora", "Source Serif 4",
        "Courier Prime",
    ).filter { NoteFonts.familyFor(it) != null }.map { FlashcardFont(it, it) }
}

/** The one font every character of [blocks]' text uses, or null when mixed or default. */
internal fun flashcardFaceFont(blocks: List<NoteDocBlock>): String? {
    val texts = blocks.filter { it.type.isTextual && it.text.isNotEmpty() }
    if (texts.isEmpty()) return null
    val names = HashSet<String?>()
    texts.forEach { block ->
        var at = 0
        NoteRuns.normalize(block.runs, block.text.length).forEach { r ->
            if (r.start > at) names += null
            names += r.fontFamily
            at = r.end
        }
        if (at < block.text.length) names += null
        if (names.size > 1) return null
    }
    return names.singleOrNull()
}

/** [font] over `[start, end)` of [block], or over all of its text when the range is empty. */
internal fun withFlashcardFont(block: NoteDocBlock, font: String?, start: Int = 0, end: Int = block.text.length): NoteDocBlock {
    if (!block.type.isTextual || block.text.isEmpty()) return block
    val a = start.coerceIn(0, block.text.length)
    val b = end.coerceIn(a, block.text.length)
    if (a == b) return block
    return block.copy(runs = NoteRuns.applyAttribute(block.runs, block.text.length, a, b) { it.copy(fontFamily = font) })
}

@Composable
internal fun FlashcardFontStrip(current: String?, tag: String, onPick: (String?) -> Unit) {
    LazyRow(Modifier.testTag("${tag}_fonts"), horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(end = 8.dp)) {
        items(FlashcardFonts, key = { it.name ?: "" }) { font ->
            val picked = font.name == current
            val shape = RoundedCornerShape(12.dp)
            Text(font.label, Modifier.heightIn(min = 40.dp)
                .background(if (picked) Color(0xFFDBF4E6) else Color.White, shape)
                .border(1.dp, if (picked) Color(0xFF08765B) else Color(0xFFDCE7DE), shape)
                .semantics { selected = picked }
                .clickable(role = Role.RadioButton, onClickLabel = "Use ${font.label}") { onPick(font.name) }
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("${tag}_font_${font.label}"),
                color = if (picked) Color(0xFF08765B) else Color(0xFF182722),
                fontFamily = NoteFonts.familyFor(font.name), fontSize = 15.sp, maxLines = 1)
        }
    }
}
