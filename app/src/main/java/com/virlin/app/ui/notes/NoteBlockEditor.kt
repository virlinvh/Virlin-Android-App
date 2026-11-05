package com.virlin.app.ui.notes

import android.content.ClipboardManager
import android.content.ClipData
import android.net.Uri
import android.text.Html
import android.text.Spanned
import com.virlin.app.data.noteimage.NoteImageStore
import com.virlin.app.domain.notedoc.NoteMath
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.virlin.app.domain.notedoc.NoteRuns
import com.virlin.app.domain.notedoc.visibleNoteBlocks

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted
private val CodeBg: Color @Composable get() = NotesWash.copy(alpha = .55f)

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
    headingFoldMode: Boolean = false,
    documentSelectionMode: Boolean = false,
    documentSelection: TextRange = TextRange.Zero,
    onDocumentSelectionChange: (TextRange) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scroll = rememberLazyListState()
    var focusRequestFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.focusedBlockId) { focusRequestFor = state.focusedBlockId }
    val visible = remember(state.blocks, headingFoldMode) {
        visibleNoteBlocks(state.blocks, foldHeadings = headingFoldMode)
    }
    val numbers = remember(state.blocks) {
        val result = HashMap<String, Int>()
        var previousType: NoteDocBlockType? = null
        var previousIndent = 0
        var number = 0
        state.blocks.forEach { block ->
            number = if (block.type == NoteDocBlockType.NUMBERED) {
                if (previousType == NoteDocBlockType.NUMBERED && previousIndent == block.indent)
                    number + 1 else 1
            } else 0
            if (block.type == NoteDocBlockType.NUMBERED) result[block.id] = number
            previousType = block.type
            previousIndent = block.indent
        }
        result
    }
    LaunchedEffect(state.focusedBlockId, documentSelectionMode) {
        if (!documentSelectionMode) {
            val target = visible.indexOfFirst { it.id == state.focusedBlockId }
            if (target >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == target })
                scroll.scrollToItem(target)
        }
    }
    LazyColumn(modifier.fillMaxWidth().fillMaxHeight().testTag("notes_editor_scroll"),
        state = scroll) {
        if (documentSelectionMode) {
            item {
                val context = LocalContext.current
                val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
                val projection = remember(state.blocks) { selectableNoteText(state.blocks) }
                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Long-press and drag across the note", color = Muted, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    Text("Copy all", color = Green, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Virlin note", projection.text))
                        }.testTag("notes_copy_all"))
                }
                BasicTextField(
                    value = TextFieldValue(projection,
                        TextRange(documentSelection.start.coerceIn(0, projection.length),
                            documentSelection.end.coerceIn(0, projection.length))),
                    onValueChange = { next -> onDocumentSelectionChange(next.selection) },
                    readOnly = true,
                    textStyle = TextStyle(color = Ink, fontSize = 16.sp, lineHeight = 25.sp),
                    modifier = Modifier.fillMaxWidth().testTag("notes_document_selection_text"),
                )
            }
        } else {
            itemsIndexed(visible, key = { _, block -> block.id }) { visibleIndex, block ->
                Spacer(Modifier.height(NoteDocumentLayout.beforeDp(block, visibleIndex).dp))
                NoteBlockRow(block, numbers[block.id] ?: 0, state, vm, headingFoldMode,
                    focusRequestFor, { focusRequestFor = null })
                Spacer(Modifier.height(NoteDocumentLayout.afterDp(block, visible.getOrNull(visibleIndex + 1)).dp))
            }
        }
        item { Spacer(Modifier.height(76.dp)) }
    }
}

@Composable
private fun NoteBlockRow(
    block: NoteDocBlock,
    numberIndex: Int,
    state: NotesUiState,
    vm: NotesViewModel,
    headingFoldMode: Boolean,
    focusRequestFor: String?,
    onFocusHandled: () -> Unit,
) {
    val indent = (block.indent * 18).dp
    val quoteAccent = Green
    when (block.type) {
        NoteDocBlockType.IMAGE -> NoteImageBlock(block.imagePath, Modifier.padding(start = indent))
        NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3 -> Row(
            Modifier.fillMaxWidth().padding(start = indent),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (headingFoldMode) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(Mint)
                        .clickable(role = Role.Button) { vm.toggleCollapsed(block.id) }
                        .semantics {
                            contentDescription = if (block.isCollapsed) "Expand heading ${block.text}" else "Collapse heading ${block.text}"
                        }
                        .testTag("notes_heading_toggle_${block.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (block.isCollapsed) Icons.Outlined.Add else Icons.Outlined.Remove,
                        contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            BlockTextField(block, state, vm, Modifier.weight(1f), focusRequestFor, onFocusHandled)
        }
        NoteDocBlockType.TOGGLE -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { vm.toggleCollapsed(block.id) }
                    .semantics {
                        contentDescription = if (block.isCollapsed) "Expand toggle" else "Collapse toggle"
                    }
                    .testTag("notes_toggle_${block.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (block.isCollapsed) "▸" else "▾", color = Green, fontSize = 20.sp)
            }
            Spacer(Modifier.width(4.dp))
            BlockTextField(block, state, vm, Modifier.weight(1f), focusRequestFor, onFocusHandled)
        }
        NoteDocBlockType.DIVIDER -> Box(
            Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Border)
                .semantics { contentDescription = "Divider" },
        )

        NoteDocBlockType.TABLE -> NoteTableBlock(block, vm)

        NoteDocBlockType.PROMPT -> CopyableBlock(
            blockId = block.id,
            copyText = { block.text },
            copyLabel = "Copy",
            copyDescription = "Copy prompt",
            modifier = Modifier.fillMaxWidth().padding(start = indent, top = 4.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp)).background(Mint).padding(12.dp),
            header = {
                // A user-authored callout. Nothing here is generated for the user.
                BasicTextField(
                    value = block.promptTitle.orEmpty(),
                    onValueChange = { vm.onPromptTitleChanged(block.id, it) },
                    readOnly = state.pasting,
                    textStyle = TextStyle(color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Green),
                    decorationBox = { inner ->
                        if (block.promptTitle.isNullOrEmpty()) {
                            Text("Prompt title", color = Green.copy(alpha = .55f), fontSize = 13.sp)
                        }
                        inner()
                    },
                    modifier = Modifier.weight(1f).testTag("notes_prompt_title_${block.id}"),
                )
            },
        ) {
            Spacer(Modifier.height(6.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled)
        }

        NoteDocBlockType.QUOTE -> Box(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
        ) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                .background(quoteAccent.copy(alpha = .045f))
                .drawBehind { drawRect(quoteAccent.copy(alpha = .65f), size = Size(3.dp.toPx(), size.height)) }
                .padding(horizontal = 17.dp, vertical = 15.dp)) {
                BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled)
            }
        }

        NoteDocBlockType.CODE -> {
            val equation = NoteMath.isEquation(block)
            val syntax = remember(block.text, equation) {
                if (equation) null else CodeHighlighter.highlight(block.text)
            }
            CopyableBlock(
                blockId = block.id,
                copyText = { block.text },
                copyLabel = if (equation) "Copy LaTeX" else "Copy",
                copyDescription = if (equation) "Copy equation LaTeX" else "Copy code",
                modifier = Modifier.fillMaxWidth().padding(start = indent, top = 4.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(10.dp)).background(CodeBg).padding(12.dp),
                header = {
                    Text(if (equation) "Equation" else syntax?.language?.label ?: "Code",
                        color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                },
            ) {
            if (equation) {
                var editingEquation by remember(block.id) {
                    mutableStateOf(block.text == "\\( \\)")
                }
                NoteEquationView(block.text)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (editingEquation) "Hide LaTeX source" else "Edit LaTeX source",
                    color = Green,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { editingEquation = !editingEquation }
                        .testTag("notes_equation_source_${block.id}"),
                )
                if (editingEquation) {
                    Spacer(Modifier.height(6.dp))
                    BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled)
                }
            } else {
                BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled,
                    syntax = syntax)
            }
            }
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
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled)
        }

        NoteDocBlockType.BULLET -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("•", color = Ink, fontSize = 16.sp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled)
        }

        NoteDocBlockType.NUMBERED -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text("$numberIndex.", color = Ink, fontSize = 15.sp, modifier = Modifier.padding(top = 1.dp))
            Spacer(Modifier.width(8.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled)
        }

        else -> Box(Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp)) {
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled)
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
    focusRequestFor: String?,
    onFocusHandled: () -> Unit,
    syntax: CodeHighlight? = null,
) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val focused = state.focusedBlockId == block.id
    val requester = remember(block.id) { FocusRequester() }
    val hasInputFocus = remember(block.id) { mutableStateOf(false) }
    LaunchedEffect(focusRequestFor, block.id) {
        if (focused && focusRequestFor == block.id) {
            // Only a newly selected block needs programmatic focus. A style or block-type edit
            // may recreate this field, but must not scroll the whole document to its caret.
            if (!hasInputFocus.value) runCatching { requester.requestFocus() }
            onFocusHandled()
        }
    }
    val size = NotesViewModel.defaultSizeFor(block.type).sp
    val accent = Green
    val style = TextStyle(
        color = if (block.type == NoteDocBlockType.QUOTE) Muted else Ink,
        fontSize = size,
        lineHeight = NoteDocumentLayout.lineHeightSp(block).sp,
        fontWeight = if (block.type.isHeading) FontWeight.SemiBold else FontWeight.Normal,
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
                val (_, _, insertedCount) = NoteRuns.diff(block.text, next.text)
                // A single typed character is never a structured paste; skip copying a possibly
                // huge clipboard across the binder on every keystroke.
                val item = if (insertedCount < 2) null
                    else clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                val pasted = item?.text?.toString() ?: item?.coerceToText(context)?.toString()
                val richHtml = item?.htmlText ?: (item?.text as? Spanned)?.let {
                    Html.toHtml(it, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
                }
                if (!vm.pasteStructured(block.id, next.text,
                        pasted.orEmpty(), richHtml, imageResolver = { source ->
                            runCatching {
                                val uri = Uri.parse(source)
                                if (uri.scheme == "content") NoteImageStore.import(context, uri)
                                else null
                            }.getOrNull()
                        })) {
                    vm.onTextChanged(block.id, next.text, next.selection)
                }
            } else {
                vm.onSelectionChanged(block.id, next.selection)
            }
        },
        readOnly = state.pasting,
        textStyle = style,
        cursorBrush = SolidColor(Green),
        visualTransformation = remember(block.runs, block.text, block.type, accent, syntax) {
            if (syntax != null && syntax.tokens.isNotEmpty()) CodeSyntaxTransformation(syntax.tokens, block.runs, accent)
            else NoteRunTransformation(block.runs, accent)
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
            .onFocusChanged {
                hasInputFocus.value = it.isFocused
                vm.onFocusChanged(block.id, it.isFocused)
                if (it.isFocused && focusRequestFor == block.id) onFocusHandled()
            }
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

/** Replaces the editor when the stored note cannot be read, instead of showing a blank page. */
@Composable
internal fun NoteDamagedPanel(onRecover: () -> Unit, rawPayload: () -> String?, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 12.dp).testTag("notes_damaged_panel")) {
        Text("This note could not be read", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Its stored data has not been changed. Recover the readable text to keep working — " +
                "the original data is kept as a recovery copy first.",
            color = Muted, fontSize = 14.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(16.dp))
        PickerRow("Recover readable text", false, onClick = onRecover)
        if (rawPayload() != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Original stored data", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                CopyAction({ rawPayload().orEmpty() }, "Copy", "Copy the original stored data", "notes_copy_damaged_raw")
            }
        }
    }
}

/** Shown only when formatting a large paste takes long enough to notice. */
@Composable
internal fun NotePasteIndicator(pasting: Boolean, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(pasting) {
        visible = false
        if (pasting) { kotlinx.coroutines.delay(250); visible = true }
    }
    if (!pasting || !visible) return
    Row(
        modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(Color.White)
            .border(1.dp, Border, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = "Formatting paste" }
            .testTag("notes_paste_formatting"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            Modifier.size(14.dp), color = Green, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Formatting paste…", color = Ink, fontSize = 13.sp)
    }
}

private val NoteDocBlockType.isHeading: Boolean
    get() = this == NoteDocBlockType.HEADING_1 || this == NoteDocBlockType.HEADING_2 ||
        this == NoteDocBlockType.HEADING_3

private fun placeholderFor(block: NoteDocBlock, state: NotesUiState): String = when {
    block.type == NoteDocBlockType.PARAGRAPH && state.blocks.indexOfFirst { it.id == block.id }
        .let { index -> index > 0 && state.blocks[index - 1].type == NoteDocBlockType.TOGGLE &&
            block.indent > state.blocks[index - 1].indent } -> "Write inside toggle…"
    block.type == NoteDocBlockType.PARAGRAPH && state.blocks.size == 1 ->
        "Write a note for this task…"
    block.type.isHeading -> "Heading"
    block.type == NoteDocBlockType.QUOTE -> "Quote"
    block.type == NoteDocBlockType.CODE -> "Code"
    block.type == NoteDocBlockType.CHECKLIST -> "To-do"
    block.type == NoteDocBlockType.PROMPT -> "Prompt body"
    block.type == NoteDocBlockType.TOGGLE -> "Toggle heading"
    else -> ""
}

/** Paints stored runs. Offsets are unchanged, so the caret and selection stay exact. */
private class NoteRunTransformation(private val runs: List<NoteDocRun>, private val accent: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (runs.isEmpty()) return TransformedText(text, androidx.compose.ui.text.input.OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text.text)
            runs.forEach { r ->
                val start = r.start.coerceIn(0, text.length)
                val end = r.end.coerceIn(start, text.length)
                if (end <= start) return@forEach
                addStyle(r.toSpanStyle(accent), start, end)
            }
        }
        return TransformedText(styled, androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}

/**
 * Paints display-only syntax colours under the stored runs, so a colour or mark the user chose
 * always wins. Offsets are unchanged, so the caret and selection stay exact.
 */
private class CodeSyntaxTransformation(
    private val tokens: List<CodeToken>,
    private val runs: List<NoteDocRun>,
    private val accent: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            tokens.forEach { token ->
                val start = token.start.coerceIn(0, text.length)
                val end = token.end.coerceIn(start, text.length)
                if (end > start) addStyle(SYNTAX_STYLES[token.kind.ordinal], start, end)
            }
            runs.forEach { r ->
                val start = r.start.coerceIn(0, text.length)
                val end = r.end.coerceIn(start, text.length)
                if (end > start) addStyle(r.toSpanStyle(accent), start, end)
            }
        }
        return TransformedText(styled, androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}

/** Indexed by [CodeTokenKind.ordinal]. Dark enough to read on every project's code wash. */
private val SYNTAX_STYLES: Array<SpanStyle> = CodeTokenKind.entries.map { kind ->
    when (kind) {
        CodeTokenKind.KEYWORD -> SpanStyle(color = Color(0xFFCF222E))
        CodeTokenKind.STRING -> SpanStyle(color = Color(0xFF0A3069))
        CodeTokenKind.COMMENT -> SpanStyle(color = Color(0xFF6E7781), fontStyle = FontStyle.Italic)
        CodeTokenKind.NUMBER -> SpanStyle(color = Color(0xFF0550AE))
        CodeTokenKind.FUNCTION -> SpanStyle(color = Color(0xFF8250DF))
        CodeTokenKind.CONSTANT -> SpanStyle(color = Color(0xFF0550AE))
        CodeTokenKind.PROPERTY -> SpanStyle(color = Color(0xFF0550AE))
        CodeTokenKind.TAG -> SpanStyle(color = Color(0xFF116329))
        CodeTokenKind.DECORATOR -> SpanStyle(color = Color(0xFF953800))
    }
}.toTypedArray()

/**
 * A code, equation or prompt block with a Copy action in its header. A second Copy action
 * appears at the bottom only when the block is taller than the screen.
 */
@Composable
private fun CopyableBlock(
    blockId: String,
    copyText: () -> String,
    copyLabel: String,
    copyDescription: String,
    modifier: Modifier,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenHeightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    var tall by remember(blockId) { mutableStateOf(false) }
    Column(modifier.onSizeChanged { tall = it.height > screenHeightPx }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            header()
            CopyAction(copyText, copyLabel, copyDescription, "notes_copy_top_$blockId")
        }
        content()
        if (tall) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                CopyAction(copyText, copyLabel, copyDescription, "notes_copy_bottom_$blockId")
            }
        }
    }
}

@Composable
private fun CopyAction(text: () -> String, label: String, description: String, tag: String) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    // null = idle, true = copied, false = the system refused (e.g. text beyond its clipboard limit).
    var result by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(result) {
        if (result != null) { kotlinx.coroutines.delay(1_600); result = null }
    }
    Text(
        when (result) { true -> "Copied"; false -> "Too large to copy"; null -> label },
        color = if (result == false) Muted else Green,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button) {
                result = clipboard != null && runCatching {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Virlin note", text()))
                }.isSuccess
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = description }
            .testTag(tag),
    )
}

internal fun NoteDocRun.toSpanStyle(accent: Color): SpanStyle {
    val decorations = buildList {
        if (NoteDocMark.UNDERLINE in marks || linkUrl != null) add(TextDecoration.Underline)
        if (NoteDocMark.STRIKE in marks) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        fontWeight = if (NoteDocMark.BOLD in marks) FontWeight.Bold else null,
        fontStyle = if (NoteDocMark.ITALIC in marks) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        color = when {
            linkUrl != null -> accent
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
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val cellWidth = (maxWidth / table.cols).coerceAtLeast(112.dp)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(10.dp)).background(Color.White)) {
            repeat(table.rows) { r ->
                // A shared intrinsic row height keeps every cell border aligned even when one
                // cell wraps to several lines on a narrow phone.
                Row(Modifier.height(IntrinsicSize.Min)) {
                    repeat(table.cols) { c ->
                        Box(Modifier.width(cellWidth).fillMaxHeight()
                            .background(Border).padding(1.dp)
                            .background(Color.White).padding(horizontal = 8.dp, vertical = 8.dp)) {
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
}
