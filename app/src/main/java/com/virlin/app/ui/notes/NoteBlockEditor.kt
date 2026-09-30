package com.virlin.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun

private val Ink = Color(0xFF17221D)
private val Green = Color(0xFF087848)
private val Mint = Color(0xFFE2F5EA)
private val Border = Color(0xFFE3E9E5)
private val Muted = Color(0xFF66758A)
private val CodeBg = Color(0xFFF4F6F5)

/**
 * The live editing surface rendered into the frozen shell's `editor` slot.
 *
 * One [BasicTextField] per block, so selection, IME composition, paste and the system caret are
 * the platform's own rather than a simulation. Stored runs are painted through a
 * [VisualTransformation]: without one the marks would persist but never appear.
 */
@Composable
fun NoteBlockEditor(
    state: NotesUiState,
    vm: NotesViewModel,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(modifier.fillMaxWidth().verticalScroll(scroll)) {
        state.blocks.forEachIndexed { index, block ->
            val numberIndex = if (block.type == NoteDocBlockType.NUMBERED) {
                // Numbering restarts after any block that is not a numbered item at this indent.
                state.blocks.take(index).reversed()
                    .takeWhile { it.type == NoteDocBlockType.NUMBERED && it.indent == block.indent }
                    .size + 1
            } else 0
            NoteBlockRow(block, numberIndex, state, vm)
            Spacer(Modifier.height(if (block.type == NoteDocBlockType.DIVIDER) 6.dp else 2.dp))
        }
        // Room to scroll the last line clear of the Add-a-block row.
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NoteBlockRow(
    block: NoteDocBlock,
    numberIndex: Int,
    state: NotesUiState,
    vm: NotesViewModel,
) {
    val indent = (block.indent * 18).dp
    when (block.type) {
        NoteDocBlockType.DIVIDER -> Box(
            Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Border)
                .semantics { contentDescription = "Divider" },
        )

        NoteDocBlockType.TABLE -> NoteTableBlock(block, vm)

        NoteDocBlockType.PROMPT -> Column(
            Modifier.fillMaxWidth().padding(start = indent, top = 4.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp)).background(Mint).padding(12.dp),
        ) {
            // A user-authored callout. Nothing here is generated for the user.
            BasicTextField(
                value = block.promptTitle.orEmpty(),
                onValueChange = { vm.onPromptTitleChanged(block.id, it) },
                textStyle = TextStyle(color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(Green),
                decorationBox = { inner ->
                    if (block.promptTitle.isNullOrEmpty()) {
                        Text("Prompt title", color = Green.copy(alpha = .55f), fontSize = 13.sp)
                    }
                    inner()
                },
                modifier = Modifier.fillMaxWidth().testTag("notes_prompt_title_${block.id}"),
            )
            Spacer(Modifier.height(6.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth())
        }

        NoteDocBlockType.QUOTE -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
        ) {
            Box(Modifier.width(3.dp).height(24.dp).background(Green.copy(alpha = .45f)))
            Spacer(Modifier.width(10.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth())
        }

        NoteDocBlockType.CODE -> Box(
            Modifier.fillMaxWidth().padding(start = indent, top = 4.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(10.dp)).background(CodeBg).padding(12.dp),
        ) {
            BlockTextField(block, state, vm, Modifier.fillMaxWidth())
        }

        NoteDocBlockType.CHECKLIST -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            NoteCheckBox(
                checked = block.checked,
                onToggle = { vm.onChecklistToggled(block.id) },
                modifier = Modifier.padding(top = 3.dp).testTag("notes_check_${block.id}"),
            )
            Spacer(Modifier.width(10.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth())
        }

        NoteDocBlockType.BULLET -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("•", color = Ink, fontSize = 16.sp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth())
        }

        NoteDocBlockType.NUMBERED -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("$numberIndex.", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(8.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth())
        }

        else -> Box(Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp)) {
            BlockTextField(block, state, vm, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun NoteCheckBox(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) Green else Color.White)
            .border(1.dp, if (checked) Green else Border, RoundedCornerShape(5.dp))
            .clickableNoRipple(onToggle)
            .semantics {
                role = Role.Checkbox
                contentDescription = if (checked) "Done" else "Not done"
            },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = Color.White, fontSize = 13.sp)
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = MutableInteractionSource(),
    indication = null,
    role = Role.Checkbox,
    onClick = onClick,
)

/**
 * One block's text field.
 *
 * The value is fully controlled by the ViewModel so a toolbar action, an undo or a programmatic
 * focus move is reflected immediately; the field never keeps a private copy of the text that
 * could drift from the stored document.
 */
@Composable
private fun BlockTextField(
    block: NoteDocBlock,
    state: NotesUiState,
    vm: NotesViewModel,
    modifier: Modifier = Modifier,
) {
    val focused = state.focusedBlockId == block.id
    val requester = remember(block.id) { FocusRequester() }
    LaunchedEffect(focused, block.id) {
        if (focused) runCatching { requester.requestFocus() }
    }
    val size = (
        block.runs.firstOrNull()?.fontSizeSp
            ?: NotesViewModel.defaultSizeFor(block.type)
        ).sp
    val style = TextStyle(
        color = Ink,
        fontSize = size,
        lineHeight = size * block.lineSpacing.coerceAtLeast(1f) * 1.35f,
        fontWeight = if (block.type.isHeading) FontWeight.Bold else FontWeight.Normal,
        fontFamily = if (block.type == NoteDocBlockType.CODE) FontFamily.Monospace else null,
        textAlign = when (block.align) {
            NoteDocAlign.CENTER -> TextAlign.Center
            NoteDocAlign.RIGHT -> TextAlign.End
            NoteDocAlign.LEFT -> TextAlign.Start
        },
    )
    val value = TextFieldValue(
        text = block.text,
        selection = if (focused) {
            TextRange(
                state.selection.start.coerceIn(0, block.text.length),
                state.selection.end.coerceIn(0, block.text.length),
            )
        } else {
            TextRange(block.text.length)
        },
    )
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (next.text != block.text) {
                vm.onTextChanged(block.id, next.text, next.selection)
            } else {
                vm.onSelectionChanged(block.id, next.selection)
            }
        },
        textStyle = style,
        cursorBrush = SolidColor(Green),
        visualTransformation = remember(block.runs, block.text, block.type) {
            NoteRunTransformation(block.runs)
        },
        decorationBox = { inner ->
            if (block.text.isEmpty() && !focused) {
                Text(
                    placeholderFor(block, state),
                    color = Muted.copy(alpha = .75f),
                    style = style.copy(color = Muted.copy(alpha = .75f)),
                )
            }
            inner()
        },
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged { vm.onFocusChanged(block.id, it.isFocused) }
            .onPreviewKeyEvent { event ->
                // Backspace at offset zero joins blocks, which a text field alone cannot see.
                if (event.type == KeyEventType.KeyDown && event.key == Key.Backspace &&
                    focused && state.selection.collapsed && state.selection.start == 0
                ) {
                    vm.onBackspaceAtStart(block.id)
                } else {
                    false
                }
            }
            .testTag("notes_block_${block.id}"),
    )
}

private val NoteDocBlockType.isHeading: Boolean
    get() = this == NoteDocBlockType.HEADING_1 || this == NoteDocBlockType.HEADING_2 ||
        this == NoteDocBlockType.HEADING_3

private fun placeholderFor(block: NoteDocBlock, state: NotesUiState): String = when {
    block.type == NoteDocBlockType.PARAGRAPH && state.blocks.size == 1 ->
        "Write a note for this task…"
    block.type.isHeading -> "Heading"
    block.type == NoteDocBlockType.QUOTE -> "Quote"
    block.type == NoteDocBlockType.CODE -> "Code"
    block.type == NoteDocBlockType.CHECKLIST -> "To-do"
    block.type == NoteDocBlockType.PROMPT -> "Prompt body"
    else -> ""
}

/** Paints stored runs. Offsets are unchanged, so the caret and selection stay exact. */
private class NoteRunTransformation(private val runs: List<NoteDocRun>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (runs.isEmpty()) return TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text.text)
            runs.forEach { r ->
                val start = r.start.coerceIn(0, text.length)
                val end = r.end.coerceIn(start, text.length)
                if (end <= start) return@forEach
                addStyle(r.toSpanStyle(), start, end)
            }
        }
        return TransformedText(styled, androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}

private fun NoteDocRun.toSpanStyle(): SpanStyle {
    val decorations = buildList {
        if (NoteDocMark.UNDERLINE in marks || linkUrl != null) add(TextDecoration.Underline)
        if (NoteDocMark.STRIKE in marks) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        fontWeight = if (NoteDocMark.BOLD in marks) FontWeight.Bold else null,
        fontStyle = if (NoteDocMark.ITALIC in marks) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        color = when {
            linkUrl != null -> Green
            color != null -> Color(color.toULong().toLong())
            else -> Color.Unspecified
        },
        background = highlight?.let { Color(it.toULong().toLong()) } ?: Color.Unspecified,
        fontFamily = when {
            NoteDocMark.CODE in marks -> FontFamily.Monospace
            fontFamily == "Serif" -> FontFamily.Serif
            fontFamily == "Mono" -> FontFamily.Monospace
            fontFamily == "Inter" -> FontFamily.SansSerif
            else -> null
        },
        fontSize = fontSizeSp?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    )
}

/** A real, editable grid. Each cell is its own text field writing straight into the document. */
@Composable
private fun NoteTableBlock(block: NoteDocBlock, vm: NotesViewModel) {
    val table = block.table ?: return
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color.White),
    ) {
        repeat(table.rows) { r ->
            Row(Modifier.fillMaxWidth()) {
                repeat(table.cols) { c ->
                    Box(
                        Modifier.weight(1f)
                            .background(Border)
                            .padding(1.dp)
                            .background(Color.White)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        BasicTextField(
                            value = table.cell(r, c),
                            onValueChange = { vm.onTableCellChanged(block.id, r, c, it) },
                            textStyle = LocalTextStyle.current.copy(color = Ink, fontSize = 14.sp),
                            cursorBrush = SolidColor(Green),
                            modifier = Modifier.fillMaxWidth()
                                .testTag("notes_cell_${block.id}_${r}_$c"),
                        )
                    }
                }
            }
        }
    }
}
