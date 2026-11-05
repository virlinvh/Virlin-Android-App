package com.virlin.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import com.virlin.app.domain.notedoc.NoteRuns
import com.virlin.app.domain.notedoc.editFlashcardSide
import com.virlin.app.domain.notedoc.flashcardSideFromSelection
import com.virlin.app.ui.components.SafeDialog

private val Ink = Color(0xFF182722)
private val Secondary = Color(0xFF62756C)
private val Accent = Color(0xFF08765B)
private val Outline = Color(0xFFDCE7DE)
private val FrontPaper = Color(0xFFFFF3E0)
private val BackPaper = Color(0xFFF3EAFE)

/** The full note selection stays available; users explicitly assign excerpts to either face. */
@Composable
internal fun FlashcardCreateDialog(
    content: NoteDocBlock, saving: Boolean, error: String?, onDismiss: () -> Unit,
    onSave: (front: NoteDocBlock, back: NoteDocBlock, difficulty: String, intervalDays: Int) -> Unit,
) {
    var source by remember(content) { mutableStateOf(content) }
    var sourceSelection by remember(content) { mutableStateOf(TextRange.Zero) }
    var front by remember(content) { mutableStateOf<NoteDocBlock?>(null) }
    var back by remember(content) { mutableStateOf<NoteDocBlock?>(null) }
    var frontSelection by remember(content) { mutableStateOf(TextRange.Zero) }
    var backSelection by remember(content) { mutableStateOf(TextRange.Zero) }
    var editFront by remember(content) { mutableStateOf(false) }
    var editBack by remember(content) { mutableStateOf(false) }
    var difficulty by remember { mutableStateOf("NORMAL") }
    var interval by remember { mutableIntStateOf(1) }
    var customDays by remember { mutableStateOf("") }
    val days = if (interval == 0) customDays.toIntOrNull() else interval
    val valid = front?.text?.isNotBlank() == true && front!!.text.length <= 300 &&
        back?.text?.isNotBlank() == true && back!!.text.length <= 20_000 && days in 1..365

    fun assign(toFront: Boolean) {
        val selected = flashcardSideFromSelection(source, sourceSelection) ?: return
        if (toFront) { front = selected; frontSelection = TextRange.Zero; editFront = false }
        else { back = selected; backSelection = TextRange.Zero; editBack = false }
    }
    fun formatSource(mark: NoteDocMark) {
        val a = sourceSelection.min
        val b = sourceSelection.max
        if (a == b) return
        val already = NoteRuns.rangeHasMark(source.runs, a, b, mark)
        source = source.copy(runs = NoteRuns.applyMark(source.runs, source.text.length,
            a, b, mark, add = !already))
    }

    SafeDialog(onDismissRequest = { if (!saving) onDismiss() }, fullScreen = true,
        scrollContent = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(shape = RoundedCornerShape(topStart = 29.dp, topEnd = 29.dp),
                color = Color(0xFFFFFEFA), modifier = Modifier.fillMaxWidth()
                    .fillMaxHeight(.95f).testTag("flashcard_create_dialog")) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 15.dp,
                        bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Create flashcard", color = Ink, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("×", color = Secondary, fontSize = 29.sp,
                            modifier = Modifier.clickable(enabled = !saving) { onDismiss() }
                                .padding(horizontal = 10.dp).testTag("flashcard_close"))
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Column(Modifier.fillMaxWidth().border(1.dp, Outline,
                            RoundedCornerShape(18.dp)).background(Color(0xFFF6FAF5),
                            RoundedCornerShape(18.dp)).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("SELECTED PASSAGE", color = Accent, fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                                    modifier = Modifier.weight(1f))
                                Text("${content.text.length} characters", color = Secondary,
                                    fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ActionChip("B", sourceSelection.collapsed.not(),
                                    Modifier.testTag("flashcard_source_bold")) {
                                    formatSource(NoteDocMark.BOLD)
                                }
                                ActionChip("I", sourceSelection.collapsed.not(),
                                    Modifier.testTag("flashcard_source_italic")) {
                                    formatSource(NoteDocMark.ITALIC)
                                }
                                ActionChip("→ Front", !sourceSelection.collapsed,
                                    Modifier.testTag("flashcard_to_front"), prominent = true) { assign(true) }
                                ActionChip("→ Back", !sourceSelection.collapsed,
                                    Modifier.testTag("flashcard_to_back")) { assign(false) }
                            }
                            Spacer(Modifier.height(9.dp))
                            val sourceScroll = rememberScrollState()
                            Box(Modifier.fillMaxWidth().heightIn(min = 130.dp, max = 190.dp)
                                .background(Color.White, RoundedCornerShape(12.dp))
                                .verticalScroll(sourceScroll).padding(12.dp)) {
                                BasicTextField(
                                    value = TextFieldValue(source.text, sourceSelection),
                                    onValueChange = { sourceSelection = it.selection },
                                    readOnly = true,
                                    textStyle = TextStyle(color = Ink, fontSize = 15.sp,
                                        lineHeight = 22.sp),
                                    visualTransformation = remember(source.runs) {
                                        FlashcardRunTransformation(source.runs)
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("flashcard_source"),
                                )
                            }
                            Text("Select words above, then send them to a side.",
                                color = Secondary, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        ComposerSide("FRONT", FrontPaper, front, editFront, frontSelection,
                            "Select a question or heading above", "flashcard_prompt",
                            onEdit = {
                                if (front == null) front = NoteDocBlock("flashcard-front",
                                    NoteDocBlockType.PARAGRAPH)
                                editFront = !editFront
                            }, onRemove = { front = null; editFront = false },
                            onChange = { next ->
                                front?.let { front = editFlashcardSide(it, next.text) }
                                frontSelection = next.selection
                            }, onFormat = { mark ->
                                front?.let { side ->
                                    val a = frontSelection.min; val b = frontSelection.max
                                    if (a < b) front = side.copy(runs = NoteRuns.applyMark(side.runs,
                                        side.text.length, a, b, mark,
                                        !NoteRuns.rangeHasMark(side.runs, a, b, mark)))
                                }
                            })
                        ComposerSide("BACK", BackPaper, back, editBack, backSelection,
                            "Select the answer above", "flashcard_answer",
                            onEdit = {
                                if (back == null) back = NoteDocBlock("flashcard-back",
                                    NoteDocBlockType.PARAGRAPH)
                                editBack = !editBack
                            }, onRemove = { back = null; editBack = false },
                            onChange = { next ->
                                back?.let { back = editFlashcardSide(it, next.text) }
                                backSelection = next.selection
                            }, onFormat = { mark ->
                                back?.let { side ->
                                    val a = backSelection.min; val b = backSelection.max
                                    if (a < b) back = side.copy(runs = NoteRuns.applyMark(side.runs,
                                        side.text.length, a, b, mark,
                                        !NoteRuns.rangeHasMark(side.runs, a, b, mark)))
                                }
                            })
                        Text("DIFFICULTY", color = Secondary, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf("EASY" to "Easy", "NORMAL" to "Moderate", "HARD" to "Hard")
                                .forEach { (value, label) ->
                                    ChoiceTile(label, difficulty == value, Modifier.weight(1f)) {
                                        difficulty = value
                                    }
                                }
                        }
                        Text("REVIEW AGAIN", color = Secondary, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            listOf(1 to "1d", 3 to "3d", 7 to "7d", 0 to "Custom")
                                .forEach { (value, label) ->
                                    ChoiceTile(label, interval == value, Modifier.weight(1f)) {
                                        interval = value
                                    }
                                }
                        }
                        if (interval == 0) BasicTextField(customDays,
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
                        if (front != null && front!!.text.length > 300)
                            Text("Front must be 300 characters or fewer.", color = Color(0xFFAA4940),
                                fontSize = 12.sp)
                        if (error != null) Text(error, color = Color(0xFFAA4940), fontSize = 12.sp)
                        Spacer(Modifier.height(7.dp))
                    }
                    Button(onClick = {
                        val cue = front; val answer = back
                        if (cue != null && answer != null && days != null)
                            onSave(cue, answer, difficulty, days)
                    }, enabled = valid && !saving,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp,
                            vertical = 12.dp).height(52.dp).testTag("flashcard_save"),
                        shape = RoundedCornerShape(15.dp)) {
                        Text(if (saving) "Saving…" else "Create flashcard",
                            fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerSide(label: String, paper: Color, side: NoteDocBlock?, editing: Boolean,
    selection: TextRange, hint: String, tag: String, onEdit: () -> Unit, onRemove: () -> Unit,
    onChange: (TextFieldValue) -> Unit, onFormat: (NoteDocMark) -> Unit) {
    Column(Modifier.fillMaxWidth().background(paper, RoundedCornerShape(18.dp))
        .border(1.dp, paper.copy(alpha = .85f), RoundedCornerShape(18.dp))
        .padding(horizontal = 15.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            Text(if (editing) "Done" else "✎", color = Accent, fontSize = 15.sp,
                modifier = Modifier.clickable(onClick = onEdit).padding(horizontal = 9.dp)
                    .testTag("${tag}_edit"))
            Text("×", color = Secondary, fontSize = 20.sp,
                modifier = Modifier.clickable(onClick = onRemove).padding(horizontal = 7.dp)
                    .testTag("${tag}_remove"))
        }
        Spacer(Modifier.height(5.dp))
        if (editing) {
            BasicTextField(value = TextFieldValue(side?.text.orEmpty(), selection),
                onValueChange = onChange, textStyle = TextStyle(color = Ink, fontSize = 15.sp,
                    lineHeight = 22.sp, fontWeight = FontWeight.Medium),
                visualTransformation = remember(side?.runs) {
                    FlashcardRunTransformation(side?.runs.orEmpty())
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 45.dp, max = 150.dp)
                    .verticalScroll(rememberScrollState()).testTag(tag))
            Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip("B", !selection.collapsed, Modifier.testTag("${tag}_bold")) {
                    onFormat(NoteDocMark.BOLD)
                }
                ActionChip("I", !selection.collapsed, Modifier.testTag("${tag}_italic")) {
                    onFormat(NoteDocMark.ITALIC)
                }
            }
        } else Text(side?.let(::styledBlock) ?: AnnotatedString(hint),
            color = if (side == null) Secondary else Ink, fontSize = 15.sp, lineHeight = 21.sp,
            fontWeight = FontWeight.Medium, maxLines = 5,
            modifier = Modifier.fillMaxWidth().testTag(tag))
    }
}

private fun styledBlock(block: NoteDocBlock): AnnotatedString = buildAnnotatedString {
    append(block.text)
    block.runs.forEach { run ->
        if (run.start >= 0 && run.end <= block.text.length && run.start < run.end)
            addStyle(run.toSpanStyle(Accent), run.start, run.end)
    }
}

private class FlashcardRunTransformation(private val runs: List<com.virlin.app.domain.notedoc.NoteDocRun>) :
    VisualTransformation {
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
private fun ActionChip(label: String, enabled: Boolean, modifier: Modifier = Modifier,
    prominent: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(label, modifier.background(if (prominent && enabled) Color(0xFFDBF4E6)
        else Color.White, shape).border(1.dp, Outline, shape)
        .clickable(enabled = enabled, onClick = onClick).then(modifier)
        .padding(horizontal = 11.dp, vertical = 8.dp),
        color = if (enabled) (if (prominent) Accent else Ink) else Secondary.copy(alpha = .55f),
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
