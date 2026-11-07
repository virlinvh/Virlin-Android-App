package com.virlin.app.ui.notes

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.rich.RichNoteAdapter

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

/** A named swatch; [argb] null clears the colour. */
data class NoteSwatch(val argb: Long?, val name: String)

object NoteColorPalettes {
    val text: List<NoteSwatch> = listOf(
        NoteSwatch(null, "Default"),
        NoteSwatch(0xFF000000L, "Black"), NoteSwatch(0xFF17221DL, "Ink"), NoteSwatch(0xFF37474FL, "Charcoal"),
        NoteSwatch(0xFF546E7AL, "Slate"), NoteSwatch(0xFF66758AL, "Grey"), NoteSwatch(0xFF9E9E9EL, "Silver"),
        NoteSwatch(0xFF7F1D1DL, "Maroon"), NoteSwatch(0xFFB3261EL, "Red"), NoteSwatch(0xFFE53935L, "Bright red"),
        NoteSwatch(0xFFFF6F61L, "Coral"), NoteSwatch(0xFFF28B82L, "Salmon"),
        NoteSwatch(0xFF880E4FL, "Wine"), NoteSwatch(0xFFAD1457L, "Raspberry"), NoteSwatch(0xFFC2185BL, "Magenta"),
        NoteSwatch(0xFFD81B60L, "Pink"), NoteSwatch(0xFFEC407AL, "Hot pink"), NoteSwatch(0xFFF06292L, "Rose"),
        NoteSwatch(0xFF4A148CL, "Plum"), NoteSwatch(0xFF7B1FA2L, "Purple"), NoteSwatch(0xFF8E24AAL, "Violet"),
        NoteSwatch(0xFFAB47BCL, "Orchid"), NoteSwatch(0xFF9575CDL, "Lavender"), NoteSwatch(0xFFB39DDBL, "Lilac"),
        NoteSwatch(0xFF1A237EL, "Navy"), NoteSwatch(0xFF0D47A1L, "Cobalt"), NoteSwatch(0xFF3949ABL, "Indigo"),
        NoteSwatch(0xFF1A56DBL, "Blue"), NoteSwatch(0xFF1E88E5L, "Bright blue"), NoteSwatch(0xFF039BE5L, "Sky"),
        NoteSwatch(0xFF4FC3F7L, "Light blue"),
        NoteSwatch(0xFF006064L, "Petrol"), NoteSwatch(0xFF00796BL, "Sea"), NoteSwatch(0xFF00897BL, "Teal"),
        NoteSwatch(0xFF00ACC1L, "Cyan"), NoteSwatch(0xFF26C6DAL, "Aqua"),
        NoteSwatch(0xFF1B5E20L, "Forest"), NoteSwatch(0xFF087848L, "Green"), NoteSwatch(0xFF2E7D32L, "Emerald"),
        NoteSwatch(0xFF43A047L, "Grass"), NoteSwatch(0xFF7CB342L, "Lime"), NoteSwatch(0xFF827717L, "Olive"),
        NoteSwatch(0xFF9E9D24L, "Khaki"),
        NoteSwatch(0xFF8A5A00L, "Amber"), NoteSwatch(0xFFF9A825L, "Mustard"), NoteSwatch(0xFFFBC02DL, "Gold"),
        NoteSwatch(0xFFFDD835L, "Yellow"), NoteSwatch(0xFFFFB300L, "Honey"),
        NoteSwatch(0xFFBF360CL, "Rust"), NoteSwatch(0xFFE65100L, "Burnt orange"), NoteSwatch(0xFFFB8C00L, "Orange"),
        NoteSwatch(0xFFFF7043L, "Tangerine"), NoteSwatch(0xFFFF8A65L, "Peach"), NoteSwatch(0xFFFFAB40L, "Apricot"),
        NoteSwatch(0xFF3E2723L, "Espresso"), NoteSwatch(0xFF5D4037L, "Brown"), NoteSwatch(0xFF795548L, "Cocoa"),
        NoteSwatch(0xFFA1887FL, "Tan"), NoteSwatch(0xFFBCAAA4L, "Sand"),
    )

    val highlight: List<NoteSwatch> = listOf(
        NoteSwatch(null, "None"),
        NoteSwatch(0xFFFFFF8DL, "Highlighter yellow"), NoteSwatch(0xFFF4FF81L, "Neon yellow"),
        NoteSwatch(0xFFFFFDE7L, "Cream"), NoteSwatch(0xFFFFF9C4L, "Lemon chiffon"), NoteSwatch(0xFFFFF3BFL, "Yellow"),
        NoteSwatch(0xFFFCF4A3L, "Banana"), NoteSwatch(0xFFFFF59DL, "Butter"), NoteSwatch(0xFFFFF176L, "Lemon"),
        NoteSwatch(0xFFFFEE58L, "Canary"), NoteSwatch(0xFFF3E5ABL, "Vanilla"), NoteSwatch(0xFFF5DF8CL, "Straw"),
        NoteSwatch(0xFFFFECB3L, "Pale amber"), NoteSwatch(0xFFFFE082L, "Sunflower"), NoteSwatch(0xFFFFD54FL, "Marigold"),
        NoteSwatch(0xFFFFCA28L, "Gold"),
        NoteSwatch(0xFFFFE0B2L, "Apricot"), NoteSwatch(0xFFFFE5D0L, "Melon"), NoteSwatch(0xFFFFD180L, "Tangerine"),
        NoteSwatch(0xFFFFB74DL, "Highlighter orange"), NoteSwatch(0xFFFFCCBCL, "Peach"),
        NoteSwatch(0xFFFFE0E0L, "Pink"), NoteSwatch(0xFFFCE4ECL, "Blush"), NoteSwatch(0xFFFFCDD2L, "Coral"),
        NoteSwatch(0xFFF8BBD0L, "Rose"), NoteSwatch(0xFFFF80ABL, "Highlighter pink"),
        NoteSwatch(0xFFF3E5F5L, "Orchid"), NoteSwatch(0xFFEDE7F6L, "Lilac"), NoteSwatch(0xFFE1BEE7L, "Lavender"),
        NoteSwatch(0xFFD1C4E9L, "Mauve"),
        NoteSwatch(0xFFE0ECFFL, "Blue"), NoteSwatch(0xFFE1F5FEL, "Ice"), NoteSwatch(0xFFBBDEFBL, "Powder blue"),
        NoteSwatch(0xFFB3E5FCL, "Sky"), NoteSwatch(0xFFC5CAE9L, "Periwinkle"), NoteSwatch(0xFF80D8FFL, "Highlighter blue"),
        NoteSwatch(0xFFB2EBF2L, "Aqua"), NoteSwatch(0xFFB2DFDBL, "Seafoam"),
        NoteSwatch(0xFFE2F5EAL, "Mint"), NoteSwatch(0xFFC8E6C9L, "Sage"), NoteSwatch(0xFFDCEDC8L, "Pistachio"),
        NoteSwatch(0xFFB9F6CAL, "Highlighter green"), NoteSwatch(0xFFCCFF90L, "Spring"), NoteSwatch(0xFFE6EE9CL, "Lime"),
        NoteSwatch(0xFFEEFF41L, "Electric lime"),
        NoteSwatch(0xFFECEFF1L, "Fog"), NoteSwatch(0xFFE0E0E0L, "Silver"), NoteSwatch(0xFFD7CCC8L, "Stone"),
        NoteSwatch(0xFFFAF0E6L, "Linen"),
    )

    /** `#RGB`, `RGB`, `#RRGGBB` or `RRGGBB` as an opaque colour, or null when it is not a colour code. */
    fun parseHex(input: String): Long? {
        val hex = input.trim().removePrefix("#")
        if (hex.length != 3 && hex.length != 6) return null
        if (!hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        return RichNoteAdapter.cssToArgb("#$hex")
    }

    /** A highlight keeps text readable: strong colours are softened towards white, pale ones are kept. */
    fun softenForHighlight(argb: Long): Long {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        if (luminance >= 0.72) return 0xFF000000L or (argb and 0xFFFFFF)
        fun mix(c: Long) = (c + (255 - c) * 0.6).toLong().coerceIn(0, 255)
        return 0xFF000000L or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
    }

    fun hexOf(argb: Long): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
}

/** Callbacks for the inline colour strip shown under the formatting rail. */
class NoteColorStripState(
    val highlight: Boolean,
    val current: Long?,
    val onPick: (Long?) -> Unit,
    val onHexStart: () -> Unit,
    val onHex: (Long) -> Unit,
    val onClose: () -> Unit,
)

private const val MAX_RECENTS = 8

private fun recentsKey(highlight: Boolean) = if (highlight) "recent_highlight" else "recent_text"

private fun loadRecents(context: Context, highlight: Boolean): List<Long> =
    context.getSharedPreferences("note_colors", Context.MODE_PRIVATE)
        .getString(recentsKey(highlight), "").orEmpty()
        .split(',').mapNotNull { it.toLongOrNull() }.take(MAX_RECENTS)

private fun saveRecents(context: Context, highlight: Boolean, values: List<Long>) {
    context.getSharedPreferences("note_colors", Context.MODE_PRIVATE).edit()
        .putString(recentsKey(highlight), values.joinToString(",")).apply()
}

/**
 * A horizontally scrolling strip of round swatches with feathered edges. Swatch taps never take
 * focus, so the editor's selection stays and several colours can be tried in a row. The "#" button
 * switches the strip to a colour-code field; codes added there stay at the front for reuse.
 */
@Composable
internal fun NoteColorStrip(state: NoteColorStripState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recents = remember(state.highlight) {
        mutableStateListOf<Long>().apply { addAll(loadRecents(context, state.highlight)) }
    }
    var hexMode by remember(state.highlight) { mutableStateOf(false) }
    BackHandler { if (hexMode) hexMode = false else state.onClose() }
    val palette = if (state.highlight) NoteColorPalettes.highlight else NoteColorPalettes.text
    val swatches = remember(palette, recents.toList()) {
        val known = palette.mapNotNull { it.argb }.toSet()
        palette.take(1) + recents.filter { it !in known }.map { NoteSwatch(it, NoteColorPalettes.hexOf(it)) } + palette.drop(1)
    }
    val kind = if (state.highlight) "highlight" else "text"

    Box(
        modifier.fillMaxWidth().height(52.dp).background(Color.White)
            .testTag("notes_color_strip_$kind"),
    ) {
        if (hexMode) HexEntry(
            highlight = state.highlight,
            onBack = { hexMode = false },
            onApply = { argb ->
                val stored = if (state.highlight) NoteColorPalettes.softenForHighlight(argb) else argb
                recents.remove(stored); recents.add(0, stored)
                while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
                saveRecents(context, state.highlight, recents.toList())
                state.onHex(stored)
                hexMode = false
            },
        ) else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val listState = rememberLazyListState()
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("notes_color_list_$kind"),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(swatches, key = { it.argb ?: -1L }) { swatch ->
                        Swatch(swatch, selected = swatch.argb == state.current, highlight = state.highlight) {
                            state.onPick(swatch.argb)
                        }
                    }
                }
                val paper = Color.White
                Canvas(Modifier.fillMaxSize()) {
                    val feather = 22.dp.toPx()
                    val steps = 12
                    if (listState.canScrollBackward) for (i in 0 until steps) {
                        val a = 1f - i / steps.toFloat()
                        drawRect(paper.copy(alpha = a), Offset(i * feather / steps, 0f),
                            androidx.compose.ui.geometry.Size(feather / steps + 1f, size.height))
                    }
                    if (listState.canScrollForward) for (i in 0 until steps) {
                        val a = 1f - i / steps.toFloat()
                        drawRect(paper.copy(alpha = a), Offset(size.width - (i + 1) * feather / steps, 0f),
                            androidx.compose.ui.geometry.Size(feather / steps + 1f, size.height))
                    }
                }
            }
            StripButton("Type a colour code", "notes_color_hex_$kind") {
                state.onHexStart(); hexMode = true
            }
            StripButton("Close colours", "notes_color_close_$kind", icon = true) { state.onClose() }
            Box(Modifier.width(6.dp))
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Border))
    }
}

@Composable
private fun Swatch(swatch: NoteSwatch, selected: Boolean, highlight: Boolean, onClick: () -> Unit) {
    val ring = if (selected) Green else Color.Transparent
    Box(
        Modifier.size(36.dp).clip(CircleShape).border(2.dp, ring, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = swatch.name; this.selected = selected }
            .testTag("notes_swatch_${swatch.argb?.let(NoteColorPalettes::hexOf) ?: "none"}"),
        contentAlignment = Alignment.Center,
    ) {
        val argb = swatch.argb
        Box(
            Modifier.size(28.dp).clip(CircleShape)
                .background(if (argb == null) Color.White else Color(argb.toInt()))
                .border(1.dp, Ink.copy(alpha = .14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (argb == null) Canvas(Modifier.fillMaxSize()) {
                drawLine(Color(0xFFD32F2F), Offset(size.width * .22f, size.height * .78f),
                    Offset(size.width * .78f, size.height * .22f), strokeWidth = 2.dp.toPx())
            } else if (highlight) Text("a", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StripButton(label: String, tag: String, icon: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (icon) Icon(Icons.Outlined.Close, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp))
        else Text("#", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HexEntry(highlight: Boolean, onBack: () -> Unit, onApply: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = NoteColorPalettes.parseHex(text)
    val preview = parsed?.let { if (highlight) NoteColorPalettes.softenForHighlight(it) else it }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val apply = { parsed?.let(onApply); Unit }
    Row(
        Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back to colours" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp)) }
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier.weight(1f).height(40.dp).clip(shape)
                .border(1.dp, if (text.isNotEmpty() && parsed == null) Color(0xFFD32F2F) else Green.copy(alpha = .6f), shape)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("#", color = Muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = text,
                onValueChange = { next ->
                    val cleaned = next.trim().removePrefix("#").filter { it.isLetterOrDigit() }.take(6)
                    text = cleaned.uppercase()
                },
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp),
                cursorBrush = SolidColor(Green),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters,
                    autoCorrect = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { apply() }),
                modifier = Modifier.weight(1f).padding(start = 4.dp).focusRequester(focus)
                    .semantics { contentDescription = "Colour code" }
                    .testTag("notes_color_hex_field"),
            )
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(preview?.let { Color(it.toInt()) } ?: Color.White)
                    .border(1.dp, Ink.copy(alpha = .18f), CircleShape)
                    .testTag("notes_color_hex_preview"),
            )
        }
        Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp))
                .background(if (parsed != null) Green else Border)
                .clickable(enabled = parsed != null, role = Role.Button, onClick = apply)
                .semantics { contentDescription = "Apply colour code" }
                .testTag("notes_color_hex_apply")
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Text("Done", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
