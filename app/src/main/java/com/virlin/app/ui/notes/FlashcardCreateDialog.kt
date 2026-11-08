package com.virlin.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteRuns
import com.virlin.app.domain.notedoc.editFlashcardSide
import com.virlin.app.ui.components.SafeDialog

private val Ink = Color(0xFF182722)
private val Secondary = Color(0xFF62756C)
private val Accent = Color(0xFF08765B)
private val Outline = Color(0xFFDCE7DE)
private val FrontPaper = Color(0xFFFFF3E0)
private val BackPaper = Color(0xFFF3EAFE)
private val ErrorInk = Color(0xFFAA4940)

/** Plain-summary limit the card file has always enforced for one face. */
private const val MAX_SIDE_CHARS = 20_000

/** The faces an existing card opens with in the editor. */
internal class FlashcardEditStart(val front: List<NoteDocBlock>, val back: List<NoteDocBlock>,
                                  val difficulty: String, val frontCardStyle: Boolean = true)

/** Cards made before rich sides keep only plain summaries; those become one block per face. */
internal fun flashcardEditStart(card: com.virlin.app.data.flashcard.SavedFlashcard): FlashcardEditStart {
    val front = card.frontBlocks
        ?: card.frontContent?.let { listOf(it.copy(id = "card-front-0")) }
        ?: card.recallPrompt.takeIf(String::isNotBlank)
            ?.let { listOf(NoteDocBlock("card-front-0", NoteDocBlockType.PARAGRAPH, it)) }
        ?: emptyList()
    val back = card.backBlocks ?: listOf(card.content.copy(id = "card-back-0"))
    return FlashcardEditStart(front, back, card.difficulty, card.frontCardStyle)
}

/**
 * Builds a card from the exact note passage: [source] keeps its block types and every run, and
 * each face is a list of those blocks. The Back starts as the whole passage, the Front starts
 * empty, and words selected in the passage can be sent to either face.
 */
@Composable
internal fun FlashcardCreateDialog(
    source: List<NoteDocBlock>, saving: Boolean, error: String?, onDismiss: () -> Unit,
    /** Edits an existing card: its faces open ready to change and its schedule is left alone. */
    edit: FlashcardEditStart? = null,
    onSave: (front: List<NoteDocBlock>, back: List<NoteDocBlock>, difficulty: String, intervalDays: Int,
             frontCardStyle: Boolean) -> Unit,
) {
    val editing = edit != null
    val passageBlocks = remember(source) { flashcardBlocks(source) }
    val passage = remember(passageBlocks) { flashcardPassageText(passageBlocks, Accent) }
    var selection by remember(source) { mutableStateOf(TextRange.Zero) }
    var front by remember(source, edit) { mutableStateOf(edit?.front ?: emptyList()) }
    var back by remember(source, edit) { mutableStateOf(edit?.back ?: passageBlocks) }
    var editFront by remember(source, edit) { mutableStateOf(editing) }
    var editBack by remember(source, edit) { mutableStateOf(editing) }
    var difficulty by remember(edit) { mutableStateOf(edit?.difficulty ?: "NORMAL") }
    var frontCardStyle by remember(edit) { mutableStateOf(edit?.frontCardStyle ?: true) }
    val frontFitsCardStyle = remember(front) { flashcardFrontFitsCardStyle(front) }
    var interval by remember { mutableIntStateOf(1) }
    var customDays by remember { mutableStateOf("") }
    val days = if (interval == 0) customDays.toIntOrNull() else interval
    val frontChars = remember(front) { flashcardSummaryBlock(front).text.length }
    val backChars = remember(back) { flashcardSummaryBlock(back).text.length }
    val valid = front.hasFlashcardContent() && back.hasFlashcardContent() &&
        frontChars <= MAX_SIDE_CHARS && backChars <= MAX_SIDE_CHARS && days in 1..365

    fun send(toFront: Boolean) {
        val picked = if (selection.collapsed) passageBlocks
            else sliceProjectedRange(passageBlocks, selection.min, selection.max)
        if (picked.isEmpty()) return
        if (toFront) { front = picked; editFront = false } else { back = picked; editBack = false }
        selection = TextRange(selection.max)
    }

    SafeDialog(onDismissRequest = { if (!saving) onDismiss() }, scrollContent = false) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFFFFFEFA),
            modifier = Modifier.fillMaxWidth().testTag("flashcard_create_dialog")) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 14.dp,
                    bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (editing) "Edit flashcard" else "Create flashcard", color = Ink, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Box(Modifier.size(48.dp).clickable(enabled = !saving) { onDismiss() }
                        .semantics { contentDescription = "Close" }.testTag("flashcard_close"),
                        contentAlignment = Alignment.Center) {
                        Text("×", color = Secondary, fontSize = 28.sp)
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    if (!editing) PassageCard(passage, selection, onSelect = { selection = it },
                        onSend = { send(it) })
                    SideCard("FRONT", FrontPaper, front, editFront,
                        if (editing) "Empty. Tap ✎ to write the question."
                        else "Select a question or heading above, then tap Front",
                        "flashcard_prompt", onEdit = { editFront = !editFront },
                        onClear = if (editing) null else ({ front = emptyList(); editFront = false }),
                        onChange = { front = it },
                        cardStyle = frontCardStyle && frontFitsCardStyle,
                        styleToggle = {
                            CardStyleToggle(frontCardStyle, frontFitsCardStyle) { frontCardStyle = it }
                        })
                    SideCard("BACK", BackPaper, back, editBack,
                        if (editing) "Empty. Tap ✎ to write the answer."
                        else "Select the answer above, then tap Back", "flashcard_answer",
                        onEdit = { editBack = !editBack },
                        onClear = if (editing) null else ({ back = emptyList(); editBack = false }),
                        onChange = { back = it })
                    SectionLabel("DIFFICULTY")
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("EASY" to "Easy", "NORMAL" to "Moderate", "HARD" to "Hard")
                            .forEach { (value, label) ->
                                ChoiceTile(label, difficulty == value, Modifier.weight(1f)) {
                                    difficulty = value
                                }
                            }
                    }
                    if (!editing) SectionLabel("REVIEW AGAIN")
                    if (!editing) Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(1 to "1d", 3 to "3d", 7 to "7d", 0 to "Custom")
                            .forEach { (value, label) ->
                                ChoiceTile(label, interval == value, Modifier.weight(1f)) {
                                    interval = value
                                }
                            }
                    }
                    if (!editing && interval == 0) BasicTextField(customDays,
                        { customDays = it.filter(Char::isDigit).take(3) },
                        modifier = Modifier.fillMaxWidth().background(Color.White,
                            RoundedCornerShape(12.dp)).border(1.dp, Outline,
                            RoundedCornerShape(12.dp)).padding(12.dp)
                            .testTag("flashcard_custom_days"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, decorationBox = { inner -> Box {
                            if (customDays.isEmpty()) Text("Days until review (1–365)",
                                color = Secondary)
                            inner()
                        } })
                    if (frontChars > MAX_SIDE_CHARS || backChars > MAX_SIDE_CHARS)
                        Text("Each side can hold up to 20,000 characters.", color = ErrorInk,
                            fontSize = 12.sp)
                    if (error != null) Text(error, color = ErrorInk, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                }
                Button(onClick = {
                    days?.let { onSave(flashcardBlocks(front), flashcardBlocks(back), difficulty, it, frontCardStyle) }
                },
                    enabled = valid && !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp,
                        vertical = 12.dp).height(52.dp).testTag("flashcard_save"),
                    shape = RoundedCornerShape(15.dp)) {
                    Text(if (saving) "Saving…" else if (editing) "Save changes" else "Create flashcard",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PassageCard(passage: AnnotatedString, selection: TextRange,
    onSelect: (TextRange) -> Unit, onSend: (toFront: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().border(1.dp, Outline, RoundedCornerShape(18.dp))
        .background(Color(0xFFF6FAF5), RoundedCornerShape(18.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("SELECTED PASSAGE", Modifier.weight(1f), Accent)
            Text(if (selection.collapsed) "Whole passage"
                else "${selection.length} selected", color = Secondary,
                fontSize = 11.sp, modifier = Modifier.testTag("flashcard_selection_status"))
        }
        Spacer(Modifier.height(8.dp))
        // The system Copy / Select all bar would sit over the passage; Front and Back below replace it.
        CompositionLocalProvider(LocalTextToolbar provides NoTextToolbar) {
            Box(Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 200.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState()).padding(12.dp)) {
                BasicTextField(
                    value = TextFieldValue(passage, selection),
                    onValueChange = { onSelect(it.selection) },
                    readOnly = true,
                    textStyle = TextStyle(color = Ink, fontSize = 15.sp, lineHeight = 22.sp),
                    modifier = Modifier.fillMaxWidth().testTag("flashcard_source"),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SendButton("→ Front", FrontPaper, Modifier.weight(1f).testTag("flashcard_to_front")) {
                onSend(true)
            }
            SendButton("→ Back", BackPaper, Modifier.weight(1f).testTag("flashcard_to_back")) {
                onSend(false)
            }
        }
        Text(if (selection.collapsed) "Select words to send only those, or send the whole passage."
            else "Send the selected words to the Front or the Back.",
            color = Secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

@Composable
private fun SideCard(label: String, paper: Color, blocks: List<NoteDocBlock>, editing: Boolean,
    hint: String, tag: String, onEdit: () -> Unit, onClear: (() -> Unit)?,
    onChange: (List<NoteDocBlock>) -> Unit, cardStyle: Boolean = false,
    styleToggle: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(paper, RoundedCornerShape(18.dp))
        .padding(start = 15.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(label, Modifier.weight(1f))
            styleToggle?.invoke()
            IconText(if (editing) "Done" else "✎", Accent, 15.sp,
                if (editing) "Finish editing ${label.lowercase()}" else "Edit ${label.lowercase()}",
                "${tag}_edit", onEdit)
            if (onClear != null) IconText("×", Secondary, 21.sp, "Clear ${label.lowercase()}", "${tag}_remove", onClear)
        }
        Box(Modifier.padding(end = 9.dp)) {
            when {
                editing -> SideEditor(blocks, tag, onChange)
                blocks.isEmpty() -> Text(hint, color = Secondary, fontSize = 14.sp,
                    lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().testTag(tag))
                else -> Box(Modifier.fillMaxWidth().heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()).testTag(tag)) {
                    if (cardStyle) CardStyleFront(flashcardFrontText(blocks), Ink, scale = .8f)
                    else NoteDocumentPreview(blocks, LocalNotesPalette.current)
                }
            }
        }
    }
}

/** Edits a face block by block, so headings, code and media keep their type while text changes. */
@Composable
private fun SideEditor(blocks: List<NoteDocBlock>, tag: String, onChange: (List<NoteDocBlock>) -> Unit) {
    val selections = remember { mutableStateMapOf<String, TextRange>() }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableIntStateOf(0) }
    val focused = blocks.firstOrNull { it.id == focusedId && it.type.isTextual }
    val focusedSelection = focused?.let { selections[it.id] } ?: TextRange.Zero

    fun format(mark: NoteDocMark) {
        val block = focused ?: return
        val a = focusedSelection.min
        val b = focusedSelection.max
        if (a >= b) return
        val next = block.copy(runs = NoteRuns.applyMark(block.runs, block.text.length, a, b, mark,
            add = !NoteRuns.rangeHasMark(block.runs, a, b, mark)))
        onChange(blocks.map { if (it.id == block.id) next else it })
    }

    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    if (block.type.isTextual) {
                        Column(Modifier.fillMaxWidth().background(Color.White,
                            RoundedCornerShape(10.dp)).padding(10.dp)) {
                            block.promptTitle?.takeIf(String::isNotBlank)?.let {
                                Text(it, color = Secondary, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                            BasicTextField(
                                value = TextFieldValue(block.text,
                                    selections[block.id] ?: TextRange(block.text.length)),
                                onValueChange = { value ->
                                    selections[block.id] = value.selection
                                    if (value.text != block.text) onChange(blocks.map {
                                        if (it.id == block.id) editFlashcardSide(it, value.text) else it
                                    })
                                },
                                textStyle = editStyle(block.type),
                                visualTransformation = remember(block.runs) {
                                    FlashcardRunTransformation(block.runs)
                                },
                                modifier = Modifier.fillMaxWidth()
                                    .onFocusChanged { if (it.isFocused) focusedId = block.id }
                                    .testTag("${tag}_field_$index"),
                            )
                        }
                    } else NoteDocumentPreview(listOf(block), LocalNotesPalette.current)
                }
                IconText("×", Secondary, 18.sp, "Remove this part", "${tag}_block_remove_$index") {
                    onChange(blocks.filterNot { it.id == block.id })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val canFormat = !focusedSelection.collapsed && focused != null
            ActionChip("B", canFormat, Modifier.testTag("${tag}_bold")) { format(NoteDocMark.BOLD) }
            ActionChip("I", canFormat, Modifier.testTag("${tag}_italic")) { format(NoteDocMark.ITALIC) }
            ActionChip("+ Line", true, Modifier.testTag("${tag}_add_line")) {
                added += 1
                onChange(blocks + NoteDocBlock("card-line-$added-${blocks.size}",
                    NoteDocBlockType.PARAGRAPH))
            }
        }
    }
}

private fun editStyle(type: NoteDocBlockType): TextStyle = when (type) {
    NoteDocBlockType.HEADING_1 -> TextStyle(color = Ink, fontSize = 24.sp, lineHeight = 30.sp,
        fontWeight = FontWeight.Bold)
    NoteDocBlockType.HEADING_2 -> TextStyle(color = Ink, fontSize = 21.sp, lineHeight = 27.sp,
        fontWeight = FontWeight.Bold)
    NoteDocBlockType.HEADING_3, NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5 ->
        TextStyle(color = Ink, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    NoteDocBlockType.CODE, NoteDocBlockType.PROMPT -> TextStyle(color = Ink, fontSize = 14.sp,
        lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
    else -> TextStyle(color = Ink, fontSize = 15.sp, lineHeight = 22.sp)
}

private object NoTextToolbar : TextToolbar {
    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden
    override fun hide() = Unit
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) = Unit
}

private class FlashcardRunTransformation(private val runs: List<NoteDocRun>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            runs.forEach { run ->
                val start = run.start.coerceIn(0, text.length)
                val end = run.end.coerceIn(start, text.length)
                if (start < end) addStyle(run.toSpanStyle(Accent), start, end)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Secondary) {
    Text(text, modifier, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp)
}

@Composable
private fun IconText(glyph: String, color: Color, size: androidx.compose.ui.unit.TextUnit,
    description: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClick = onClick)
        .semantics { contentDescription = description }.testTag(tag),
        contentAlignment = Alignment.Center) {
        Text(glyph, color = color, fontSize = size, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Switches the front between the deck's card typeface (on) and the note's own formatting (off).
 * A front with code, equations, tables or media always keeps its formatting, so the switch rests.
 */
@Composable
private fun CardStyleToggle(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val shown = on && enabled
    val shape = RoundedCornerShape(9.dp)
    Box(Modifier.size(44.dp)
        .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .semantics {
            contentDescription = if (enabled) "Card style for the front"
                else "Card style needs a text-only front"
        }.testTag("flashcard_front_style"),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 34.dp, height = 26.dp)
            .background(if (shown) Accent else Color.Transparent, shape)
            .border(1.dp, if (shown) Accent else Outline, shape),
            contentAlignment = Alignment.Center) {
            Text("Aa", color = if (shown) Color.White else Secondary.copy(alpha = if (enabled) 1f else .45f),
                fontFamily = if (shown) CardSerif else FontFamily.Default,
                fontWeight = if (shown) FontWeight.Medium else FontWeight.Normal,
                fontSize = 14.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun SendButton(label: String, paper: Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.heightIn(min = 46.dp).background(paper, shape).border(1.dp, Outline, shape)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ActionChip(label: String, enabled: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(label, modifier.background(Color.White, shape).border(1.dp, Outline, shape)
        .clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
        color = if (enabled) Ink else Secondary.copy(alpha = .55f),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ChoiceTile(label: String, selected: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(label, modifier.background(if (selected) Color(0xFFDBF4E6) else Color.White, shape)
        .border(1.dp, if (selected) Accent else Outline, shape)
        .clickable(onClick = onClick).padding(vertical = 9.dp),
        color = if (selected) Accent else Ink, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, textAlign = TextAlign.Center)
}
