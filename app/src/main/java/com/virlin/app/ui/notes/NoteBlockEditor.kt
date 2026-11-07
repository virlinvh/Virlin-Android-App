package com.virlin.app.ui.notes

import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.TextLayoutResult
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
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
import androidx.compose.ui.platform.LocalFocusManager
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
import com.virlin.app.domain.notedoc.NoteDocTable
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteListStyles
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
    /** Note key under which the reading position is kept across reopening; null keeps none. */
    positionKey: String? = null,
    /**
     * The reading view: the same blocks, fonts and colours, but nothing can change the note.
     * Folding toggles and playing a video still work, as view-only state of this screen.
     */
    readOnly: Boolean = false,
    /** Scrolls above the first block; only used with [readOnly]. */
    readerHeader: (@Composable () -> Unit)? = null,
    /**
     * Page formatting: the note is drawn on paper of the chosen size, at that paper's scale, with
     * its margins, title and page numbers, breaking where the printout and export break.
     */
    pageView: NotePageView? = null,
) {
    val scroll = rememberLazyListState()
    var readerCollapsed by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var readerPlaying by remember { mutableStateOf<String?>(null) }
    val paged = pageView != null && !readOnly && !documentSelectionMode
    val original = state
    val state = if (paged) remember(original) {
        // Pages print everything, so every folded heading and toggle shows open here too.
        if (original.blocks.none { it.isCollapsed }) original
        else original.copy(blocks = original.blocks.map { if (it.isCollapsed) it.copy(isCollapsed = false) else it })
    } else if (!readOnly) state else state.copy(
        blocks = state.blocks.map { block -> readerCollapsed[block.id]?.let { block.copy(isCollapsed = it) } ?: block },
        focusedBlockId = null, focusedCell = null, cellFocusRequest = null, selectedImageId = null,
        playingVideoId = readerPlaying, pasting = false,
    )
    val rowActions = remember(readOnly, vm, paged) {
        if (paged) NoteRowActions(readOnly = false, toggleCollapsed = {}, playVideo = vm::playVideo)
        else if (!readOnly) NoteRowActions(readOnly = false, toggleCollapsed = vm::toggleCollapsed, playVideo = vm::playVideo)
        else NoteRowActions(
            readOnly = true,
            toggleCollapsed = { id ->
                val current = readerCollapsed[id] ?: vm.state.value.blocks.firstOrNull { it.id == id }?.isCollapsed ?: false
                readerCollapsed = readerCollapsed + (id to !current)
            },
            playVideo = { readerPlaying = it },
        )
    }
    var focusRequestFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.focusedBlockId) { focusRequestFor = state.focusedBlockId }
    val visible = remember(state.blocks, headingFoldMode, paged) {
        if (paged) state.blocks else visibleNoteBlocks(state.blocks, foldHeadings = headingFoldMode)
    }
    val numbers = remember(state.blocks) { noteListNumbers(state.blocks) }
    LaunchedEffect(state.focusedBlockId) {
        if (!documentSelectionMode) {
            val target = visible.indexOfFirst { it.id == state.focusedBlockId }
            if (target >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == target })
                scroll.scrollToItem(target)
        }
    }

    // The reading position is the block at the top of the screen. It survives switching into and
    // out of select-across mode, closing and reopening the note, and the app being restored.
    val context = LocalContext.current
    val positions = remember(context) { NotePositionStore(context) }
    var topBlockId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousSelectionMode by remember { mutableStateOf<Boolean?>(null) }
    var selectionLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var selectionTextTop by remember { mutableStateOf<Float?>(null) }
    val currentBlocks by rememberUpdatedState(state.blocks)
    val currentVisible by rememberUpdatedState(visible)
    LaunchedEffect(documentSelectionMode) {
        // The reading view keeps no reading position and never moves the editor's insert point.
        if (readOnly) return@LaunchedEffect
        val cameFrom = previousSelectionMode
        previousSelectionMode = documentSelectionMode
        if (documentSelectionMode) {
            // Open the selectable text at the line that was at the top of the editor.
            val layout = snapshotFlow { selectionLayout?.takeIf { selectionTextTop != null } }.filterNotNull().first()
            val index = currentBlocks.indexOfFirst { it.id == topBlockId }
            if (index > 0) {
                val start = projectedBlocks(currentBlocks)[index].start.coerceIn(0, layout.layoutInput.text.length)
                val y = (selectionTextTop ?: 0f) + layout.getLineTop(layout.getLineForOffset(start))
                scroll.scrollToItem(0, y.roundToInt())
            }
            snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.collect { (item, offset) ->
                val l = selectionLayout ?: return@collect
                val top = selectionTextTop ?: return@collect
                if (item != 0) return@collect
                val char = l.getOffsetForPosition(Offset(0f, (offset - top).coerceAtLeast(0f)))
                val at = projectedBlocks(currentBlocks).indexOfLast { it.start <= char }
                currentBlocks.getOrNull(at)?.id?.let { topBlockId = it }
            }
        } else {
            selectionLayout = null
            selectionTextTop = null
            when (cameFrom) {
                // First show: continue where this note was last read, unless the list kept its own
                // position (the app was restored with its saved state).
                null -> if (positionKey != null && scroll.firstVisibleItemIndex == 0 &&
                    scroll.firstVisibleItemScrollOffset == 0) {
                    positions.load(positionKey)?.let { (id, offset) ->
                        val index = currentVisible.indexOfFirst { it.id == id }
                        if (index > 0 || (index == 0 && offset > 0)) scroll.scrollToItem(index, offset)
                    }
                }
                // Back from select-across: return to the line that was at the top there.
                true -> {
                    val index = currentBlocks.indexOfFirst { it.id == topBlockId }
                    val target = (index downTo 0).asSequence().map { currentBlocks[it].id }
                        .map { id -> currentVisible.indexOfFirst { it.id == id } }.firstOrNull { it >= 0 }
                    if (target != null) scroll.scrollToItem(target)
                }
                false -> Unit
            }
            snapshotFlow {
                Triple(scroll.firstVisibleItemIndex, scroll.firstVisibleItemScrollOffset, scroll.isScrollInProgress)
            }.collect { (item, offset, moving) ->
                if (moving) return@collect
                val id = currentVisible.getOrNull(item)?.id ?: return@collect
                topBlockId = id
                vm.setViewAnchor(id)
                positionKey?.let { positions.save(it, id, offset) }
            }
        }
    }
    val measured = remember { mutableStateMapOf<String, Int>() }
    NotePageScale(if (paged) pageView else null, modifier) { pageModifier, deskGap ->
    LazyColumn(pageModifier.fillMaxWidth().fillMaxHeight().testTag("notes_editor_scroll"),
        state = scroll, contentPadding = PaddingValues(top = if (paged) deskGap else NoteDocumentTopSpace)) {
        if (readOnly && readerHeader != null) item(key = "reader_header") { readerHeader() }
        if (documentSelectionMode) {
            item { Column {
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
                    onTextLayout = { selectionLayout = it },
                    modifier = Modifier.fillMaxWidth()
                        .onGloballyPositioned { selectionTextTop = it.positionInParent().y }
                        .testTag("notes_document_selection_text"),
                )
            } }
        } else if (paged && pageView != null) {
            itemsIndexed(visible, key = { _, block -> block.id }) { visibleIndex, block ->
                val place = pagePlace(pageView, visible, visibleIndex, measured)
                NotePageItem(pageView, place.page, place.lastPage, place.firstOnPage, place.lastOnPage, deskGap, place.fill,
                    onTitleMeasured = { measured[PAGE_TITLE_KEY] = it },
                    onContentMeasured = { measured[block.id] = it }) {
                    if (!place.firstOnPage) Spacer(Modifier.height(NoteDocumentLayout.beforeDp(block, visibleIndex).dp))
                    NoteBlockRow(block, numbers[block.id] ?: 0, state, vm, headingFoldMode,
                        focusRequestFor, { focusRequestFor = null }, rowActions)
                    if (!place.lastOnPage) {
                        Spacer(Modifier.height(NoteDocumentLayout.afterDp(block, visible.getOrNull(visibleIndex + 1)).dp))
                    }
                }
            }
        } else {
            itemsIndexed(visible, key = { _, block -> block.id }) { visibleIndex, block ->
                Spacer(Modifier.height(NoteDocumentLayout.beforeDp(block, visibleIndex).dp))
                NoteBlockRow(block, numbers[block.id] ?: 0, state, vm, headingFoldMode,
                    focusRequestFor, { focusRequestFor = null }, rowActions)
                Spacer(Modifier.height(NoteDocumentLayout.afterDp(block, visible.getOrNull(visibleIndex + 1)).dp))
            }
        }
        item { Spacer(Modifier.height(if (paged) deskGap * 6 else 76.dp)) }
    }
    }
}

/** Each numbered block's number: runs of numbered blocks at one indent count up from 1. */
private fun noteListNumbers(blocks: List<NoteDocBlock>): Map<String, Int> {
    val result = HashMap<String, Int>()
    var previousType: NoteDocBlockType? = null
    var previousIndent = 0
    var number = 0
    blocks.forEach { block ->
        number = if (block.type == NoteDocBlockType.NUMBERED) {
            if (previousType == NoteDocBlockType.NUMBERED && previousIndent == block.indent)
                number + 1 else 1
        } else 0
        if (block.type == NoteDocBlockType.NUMBERED) result[block.id] = number
        previousType = block.type
        previousIndent = block.indent
    }
    return result
}

/**
 * A note drawn exactly as its editor draws it, read only, inside another screen's column: the
 * same rows, fonts, colours, prompt and code boxes with their Copy buttons, tables, images and
 * spacing. There is no editor behind it, so nothing here can change the note; folding toggles and
 * playing a video are view-only state of this preview.
 */
@Composable
internal fun NoteReadOnlyBlocks(blocks: List<NoteDocBlock>, modifier: Modifier = Modifier) {
    var collapsed by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var playing by remember { mutableStateOf<String?>(null) }
    val currentBlocks by rememberUpdatedState(blocks)
    val shown = remember(blocks, collapsed) {
        blocks.map { block -> collapsed[block.id]?.let { block.copy(isCollapsed = it) } ?: block }
    }
    val state = remember(shown, playing) { NotesUiState(loading = false, blocks = shown, playingVideoId = playing) }
    val visible = remember(shown) { visibleNoteBlocks(shown, foldHeadings = false) }
    val numbers = remember(shown) { noteListNumbers(shown) }
    val actions = remember {
        NoteRowActions(
            readOnly = true,
            toggleCollapsed = { id ->
                val current = collapsed[id] ?: currentBlocks.firstOrNull { it.id == id }?.isCollapsed ?: false
                collapsed = collapsed + (id to !current)
            },
            playVideo = { playing = it },
        )
    }
    Column(modifier.fillMaxWidth()) {
        visible.forEachIndexed { index, block ->
            key(block.id) {
                Spacer(Modifier.height(NoteDocumentLayout.beforeDp(block, index).dp))
                NoteBlockRow(block, numbers[block.id] ?: 0, state, null, false, null, {}, actions)
                Spacer(Modifier.height(NoteDocumentLayout.afterDp(block, visible.getOrNull(index + 1)).dp))
            }
        }
    }
}

private const val PAGE_TITLE_KEY = "\u0000page_title"

private class PagePlace(val page: Int, val lastPage: Int, val firstOnPage: Boolean, val lastOnPage: Boolean, val fill: Dp)

/**
 * Where a block sits on the formatted pages. The rest of a page is filled from the blocks'
 * measured heights when they are known, so each sheet is exactly the paper's height; otherwise
 * from the print layout.
 */
@Composable
private fun pagePlace(view: NotePageView, visible: List<NoteDocBlock>, index: Int, measured: Map<String, Int>): PagePlace {
    val p = view.pagination
    val block = visible[index]
    val page = p?.startPage?.get(block.id)
        ?: visible.subList(0, index).asReversed().firstNotNullOfOrNull { p?.endPage?.get(it.id) } ?: 0
    val lastPage = p?.endPage?.get(block.id) ?: page
    val prevEnd = visible.getOrNull(index - 1)?.let { p?.endPage?.get(it.id) }
    val nextStart = visible.getOrNull(index + 1)?.let { p?.startPage?.get(it.id) }
    val first = index == 0 || (prevEnd != null && page > prevEnd)
    val last = index == visible.lastIndex || (nextStart != null && nextStart > lastPage)
    if (!last) return PagePlace(page, lastPage, first, false, 0.dp)
    val s = view.settings
    val margin = s.margins.points
    val density = LocalDensity.current
    var sumPx = 0
    var known = p != null
    var j = index
    while (j >= 0) {
        val b = visible[j]
        val h = measured[b.id]
        if (h == null) known = false else sumPx += h
        val bStart = p?.startPage?.get(b.id) ?: break
        if (bStart < lastPage || j == 0) break
        val before = p.endPage[visible[j - 1].id]
        if (before == null || before < lastPage) break
        j--
    }
    val title = view.title?.trim().orEmpty()
    if (lastPage == 0 && s.includeTitle && title.isNotEmpty()) {
        val t = measured[PAGE_TITLE_KEY]
        if (t == null) known = false else sumPx += t
    }
    val fill = if (known) {
        with(density) { (pagePt(s.pageHeightPt - 2 * margin).toPx() - sumPx).coerceAtLeast(0f).toDp() }
    } else {
        val bottom = p?.blockBottom?.get(block.id) ?: (s.pageHeightPt - margin)
        pagePt((s.pageHeightPt - margin - bottom).coerceAtLeast(0f))
    }
    return PagePlace(page, lastPage, first, true, fill)
}

/**
 * Draws [content] at paper scale: the editor's units become paper units, so a page of the chosen
 * paper exactly fills the width, and text, images and spacing shrink with it as they would print.
 * Without a page view the content is drawn as usual.
 */
@Composable
private fun NotePageScale(view: NotePageView?, modifier: Modifier, content: @Composable (Modifier, Dp) -> Unit) {
    if (view == null) {
        content(modifier, 0.dp)
        return
    }
    BoxWithConstraints(modifier) {
        val gutterPx = with(LocalDensity.current) { 10.dp.toPx() }
        val widthPx = (constraints.maxWidth - 2 * gutterPx).coerceAtLeast(1f)
        val pageDensity = Density((widthPx / pagePt(view.settings.pageWidthPt).value).coerceAtLeast(.2f), 1f)
        CompositionLocalProvider(LocalDensity provides pageDensity) {
            val gutter = with(pageDensity) { gutterPx.toDp() }
            val deskGap = with(pageDensity) { (gutterPx * 1.4f).toDp() }
            content(Modifier.fillMaxSize().padding(horizontal = gutter).testTag("notes_page_view"), deskGap)
        }
    }
}

@Composable
private fun NoteBlockRow(
    block: NoteDocBlock,
    numberIndex: Int,
    state: NotesUiState,
    /** Null draws the block read only: nothing here can change a note without its editor. */
    vm: NotesViewModel?,
    headingFoldMode: Boolean,
    focusRequestFor: String?,
    onFocusHandled: () -> Unit,
    actions: NoteRowActions,
) {
    val indent = (block.indent * 18).dp
    val quoteAccent = Green
    val readOnly = actions.readOnly || vm == null
    when (block.type) {
        NoteDocBlockType.IMAGE -> {
            val focusManager = LocalFocusManager.current
            NoteImageBlock(block.imagePath, Modifier.padding(start = indent),
                selected = state.selectedImageId == block.id,
                onClick = if (readOnly) null else {
                    {
                        // The keyboard steps aside so the image tools strip has the screen.
                        focusManager.clearFocus()
                        vm?.selectImage(block.id)
                    }
                })
        }
        NoteDocBlockType.VIDEO -> {
            val focusManager = LocalFocusManager.current
            NoteVideoCard(
                video = block.video,
                playing = state.playingVideoId == block.id,
                onPlay = { focusManager.clearFocus(); actions.playVideo(block.id) },
                onStop = { actions.playVideo(null) },
                modifier = Modifier.padding(start = indent),
                onEdit = if (readOnly) null else { { vm?.openDialog(NoteDialog.Video(block.id)) } },
                onRemove = if (readOnly) null else { { vm?.deleteVideo(block.id) } },
            )
        }
        NoteDocBlockType.AUDIO -> NoteAudioCard(block.audio, Modifier.padding(start = indent),
            onRemove = if (readOnly) null else { { vm?.deleteAudio(block.id) } })
        NoteDocBlockType.HEADING_1, NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3,
        NoteDocBlockType.HEADING_4, NoteDocBlockType.HEADING_5 -> Row(
            Modifier.fillMaxWidth().padding(start = indent),
            verticalAlignment = if (block.toggleHeading && !headingFoldMode) Alignment.Top else Alignment.CenterVertically,
        ) {
            if (headingFoldMode) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(Mint)
                        .clickable(role = Role.Button) { actions.toggleCollapsed(block.id) }
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
            } else if (block.toggleHeading) {
                NoteToggleArrow(
                    collapsed = block.isCollapsed,
                    textSizeSp = NotesViewModel.defaultSizeFor(block.type),
                    lineHeightSp = NoteDocumentLayout.lineHeightSp(block),
                    color = Ink,
                    contentDescription = if (block.isCollapsed) "Expand toggle heading ${block.text}"
                        else "Collapse toggle heading ${block.text}",
                    testTag = "notes_toggle_heading_${block.id}",
                    onClick = { actions.toggleCollapsed(block.id) },
                )
                Spacer(Modifier.width(2.dp))
            }
            BlockTextField(block, state, vm, Modifier.weight(1f), focusRequestFor, onFocusHandled, readOnly)
        }
        NoteDocBlockType.TOGGLE -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            NoteToggleArrow(
                collapsed = block.isCollapsed,
                textSizeSp = NotesViewModel.defaultSizeFor(block.type),
                lineHeightSp = NoteDocumentLayout.lineHeightSp(block),
                color = Ink,
                contentDescription = if (block.isCollapsed) "Expand toggle" else "Collapse toggle",
                testTag = "notes_toggle_${block.id}",
                onClick = { actions.toggleCollapsed(block.id) },
            )
            Spacer(Modifier.width(2.dp))
            BlockTextField(block, state, vm, Modifier.weight(1f), focusRequestFor, onFocusHandled, readOnly)
        }
        NoteDocBlockType.DIVIDER -> Box(
            Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Border)
                .semantics { contentDescription = "Divider" },
        )

        NoteDocBlockType.TABLE -> NoteTableBlock(block, state, vm, readOnly)

        NoteDocBlockType.PROMPT -> CopyableBlock(
            blockId = block.id,
            copyText = { block.text },
            copyLabel = "Copy",
            copyDescription = "Copy prompt",
            modifier = Modifier.fillMaxWidth().padding(start = indent, top = 4.dp, bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp)).background(Mint).padding(12.dp),
            header = {
                // A user-authored callout. Nothing here is generated for the user.
                if (readOnly) Text(block.promptTitle.orEmpty(), color = Green, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                else BasicTextField(
                    value = block.promptTitle.orEmpty(),
                    onValueChange = { vm?.onPromptTitleChanged(block.id, it) },
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
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled, readOnly)
        }

        NoteDocBlockType.QUOTE -> Box(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
        ) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                .background(quoteAccent.copy(alpha = .045f))
                .drawBehind { drawRect(quoteAccent.copy(alpha = .65f), size = Size(3.dp.toPx(), size.height)) }
                .padding(horizontal = 17.dp, vertical = 15.dp)) {
                BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled, readOnly)
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
                if (!readOnly) Spacer(Modifier.height(6.dp))
                if (!readOnly) Text(
                    if (editingEquation) "Hide LaTeX source" else "Edit LaTeX source",
                    color = Green,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { editingEquation = !editingEquation }
                        .testTag("notes_equation_source_${block.id}"),
                )
                if (editingEquation && !readOnly) {
                    Spacer(Modifier.height(6.dp))
                    BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled, readOnly)
                }
            } else {
                BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled,
                    readOnly, syntax = syntax)
            }
            }
        }

        NoteDocBlockType.CHECKLIST -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            NoteCheckBox(
                checked = block.checked,
                onToggle = { if (!readOnly) vm?.onChecklistToggled(block.id) },
                modifier = Modifier.padding(top = 3.dp).testTag("notes_check_${block.id}"),
            )
            Spacer(Modifier.width(10.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled, readOnly)
        }

        NoteDocBlockType.BULLET -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val marker = NoteListStyles.marker(block.type, block.listStyle)
            Text(marker, color = Ink, fontSize = if (marker == "•") 16.sp else 14.sp,
                modifier = Modifier.padding(top = if (marker == "•") 1.dp else 3.dp).testTag("notes_marker_${block.id}"))
            Spacer(Modifier.width(10.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled, readOnly)
        }

        NoteDocBlockType.NUMBERED -> Row(
            Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(NoteListStyles.marker(block.type, block.listStyle, numberIndex), color = Ink, fontSize = 15.sp,
                modifier = Modifier.padding(top = 1.dp).testTag("notes_marker_${block.id}"))
            Spacer(Modifier.width(8.dp))
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled, readOnly)
        }

        else -> Box(Modifier.fillMaxWidth().padding(start = indent, top = 2.dp, bottom = 2.dp)) {
            BlockTextField(block, state, vm, Modifier.fillMaxWidth(), focusRequestFor, onFocusHandled, readOnly)
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
    vm: NotesViewModel?,
    modifier: Modifier = Modifier,
    focusRequestFor: String?,
    onFocusHandled: () -> Unit,
    readOnly: Boolean = false,
    syntax: CodeHighlight? = null,
) {
    if (readOnly || vm == null) {
        ReadOnlyBlockText(block, modifier, syntax)
        return
    }
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
    val accent = Green
    val style = blockTextStyle(block)
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
                val clip = if (insertedCount < 2) null
                    else clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                val item = clip?.getItemAt(0)
                val pasted = item?.text?.toString() ?: item?.coerceToText(context)?.toString()
                val richHtml = item?.htmlText ?: (item?.text as? Spanned)?.let {
                    Html.toHtml(it, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
                }
                if (!vm.pasteStructured(block.id, next.text,
                        pasted.orEmpty(), richHtml, imageResolver = { source ->
                            runCatching {
                                when {
                                    source.startsWith("data:image/", ignoreCase = true) ->
                                        NoteImageStore.importDataUri(context, source)
                                    Uri.parse(source).scheme == "content" ->
                                        NoteImageStore.import(context, Uri.parse(source))
                                    else -> null
                                }
                            }.getOrNull()
                        }, clipSummary = clip?.let { clipSummary(context, it) })) {
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

/** The block's text field style; shared by the editor and the reading view so both match. */
@Composable
private fun blockTextStyle(block: NoteDocBlock) = TextStyle(
    color = if (block.type == NoteDocBlockType.QUOTE) Muted else Ink,
    fontSize = NotesViewModel.defaultSizeFor(block.type).sp,
    lineHeight = NoteDocumentLayout.lineHeightSp(block).sp,
    fontWeight = if (block.type.isHeading) FontWeight.SemiBold else FontWeight.Normal,
    fontFamily = if (block.type == NoteDocBlockType.CODE) FontFamily.Monospace else null,
    textAlign = when (block.align) {
        NoteDocAlign.CENTER -> TextAlign.Center
        NoteDocAlign.RIGHT -> TextAlign.End
        NoteDocAlign.LEFT -> TextAlign.Start
    },
)

/**
 * A block's text in the reading view: the editor's own field, styles and colours, read only.
 * Text can be selected and copied; the selection lives here and never reaches the note.
 */
@Composable
private fun ReadOnlyBlockText(block: NoteDocBlock, modifier: Modifier, syntax: CodeHighlight?) {
    val accent = Green
    var selection by remember(block.id) { mutableStateOf(TextRange(block.text.length)) }
    BasicTextField(
        value = TextFieldValue(block.text, TextRange(
            selection.start.coerceIn(0, block.text.length), selection.end.coerceIn(0, block.text.length))),
        onValueChange = { next -> if (next.text == block.text) selection = next.selection },
        readOnly = true,
        textStyle = blockTextStyle(block),
        visualTransformation = remember(block.runs, block.text, block.type, accent, syntax) {
            if (syntax != null && syntax.tokens.isNotEmpty()) CodeSyntaxTransformation(syntax.tokens, block.runs, accent)
            else NoteRunTransformation(block.runs, accent)
        },
        modifier = modifier.testTag("notes_block_${block.id}"),
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
    get() = headingLevel > 0

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
            else -> NoteFonts.familyFor(fontFamily)
        },
        fontSize = fontSizeSp?.sp ?: androidx.compose.ui.unit.TextUnit.Unspecified,
    )
}

/**
 * A real, editable grid. Each cell is its own text field writing straight into the document; the
 * focused cell is the target of the text-style tools and the table tools strip.
 */
@Composable
private fun NoteTableBlock(block: NoteDocBlock, state: NotesUiState, vm: NotesViewModel?, readOnly: Boolean = false) {
    val table = block.table ?: return
    if (readOnly || vm == null) {
        ReadOnlyTable(block, table)
        return
    }
    val activeCell = state.activeCell?.takeIf { it.blockId == block.id }
    // Edge "+" controls show while a cell is focused, or briefly after tapping a faint edge, so a
    // stray tap beside the table never changes its shape.
    var inputCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val focusedCell = inputCell ?: activeCell?.let { it.row to it.col }
    var revealed by remember { mutableStateOf(false) }
    var pendingFocus by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val request = state.cellFocusRequest?.takeIf { it.blockId == block.id }
    val active = focusedCell != null || revealed
    LaunchedEffect(revealed, focusedCell) {
        if (revealed && focusedCell == null) { kotlinx.coroutines.delay(5_000); revealed = false }
    }
    val edge = 22.dp
    val accent = Green
    val lineColor = if (table.gridLines) Border else Border.copy(alpha = .3f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val tableWidth = maxWidth - edge - 4.dp
        val explicit = (0 until table.cols).mapNotNull { table.widthAt(it) }
        val autoCount = table.cols - explicit.size
        val autoWidth = if (autoCount == 0) 0.dp
            else ((tableWidth - explicit.sumOf { it }.dp) / autoCount).coerceAtLeast(112.dp)
        Column {
            TableEdgeAdd(active, table.rows < NoteDocTable.MAX_ROWS, "Add row above",
                "notes_table_add_top_${block.id}", onReveal = { revealed = true },
                onAdd = { vm.addTableRow(block.id, atTop = true); pendingFocus = 0 to (focusedCell?.second ?: 0) },
                modifier = Modifier.width(tableWidth).height(20.dp))
            Box(Modifier.width(tableWidth + edge + 4.dp)) {
                Column(Modifier.width(tableWidth).horizontalScroll(rememberScrollState())
                    .clip(RoundedCornerShape(10.dp)).background(Color.White)) {
                    repeat(table.rows) { r ->
                        // A shared intrinsic row height keeps every cell border aligned even when one
                        // cell wraps to several lines on a narrow phone.
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            repeat(table.cols) { c ->
                                val requester = remember { FocusRequester() }
                                if (pendingFocus == r to c) LaunchedEffect(Unit) {
                                    runCatching { requester.requestFocus() }
                                    pendingFocus = null
                                }
                                if (request != null && request.row == r && request.col == c) LaunchedEffect(request) {
                                    runCatching { requester.requestFocus() }
                                    vm.onCellFocusRequestHandled()
                                }
                                val text = table.cell(r, c)
                                val runs = table.runsAt(r, c)
                                val header = table.isHeader(r, c)
                                val fill = table.fillAt(r, c)?.let { Color(it) } ?: if (header) Mint else Color.White
                                val isActive = activeCell != null && activeCell.row == r && activeCell.col == c
                                val value = TextFieldValue(
                                    text = text,
                                    selection = if (isActive) TextRange(
                                        state.selection.start.coerceIn(0, text.length),
                                        state.selection.end.coerceIn(0, text.length),
                                    ) else TextRange(text.length),
                                )
                                Box(Modifier.width(table.widthAt(c)?.dp ?: autoWidth).fillMaxHeight()
                                    .background(lineColor).padding(1.dp)
                                    .background(fill).padding(horizontal = 8.dp, vertical = 8.dp)) {
                                    BasicTextField(
                                        value = value,
                                        onValueChange = { next ->
                                            vm.onTableCellEdited(block.id, r, c, next.text, next.selection)
                                        },
                                        textStyle = LocalTextStyle.current.copy(
                                            color = Ink, fontSize = TABLE_CELL_TEXT_SP.sp,
                                            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                                        ),
                                        cursorBrush = SolidColor(Green),
                                        visualTransformation = remember(runs, accent) { NoteRunTransformation(runs, accent) },
                                        modifier = Modifier.fillMaxWidth()
                                            .focusRequester(requester)
                                            .onFocusChanged { focus ->
                                                if (focus.isFocused) {
                                                    inputCell = r to c
                                                    vm.onTableCellFocused(block.id, r, c)
                                                } else if (inputCell == r to c) inputCell = null
                                            }
                                            .semantics {
                                                contentDescription = "Table cell, row ${r + 1}, column ${c + 1}" +
                                                    if (header) ", header" else ""
                                            }
                                            .testTag("notes_cell_${block.id}_${r}_$c"),
                                    )
                                }
                            }
                        }
                    }
                }
                Box(Modifier.matchParentSize().padding(start = tableWidth + 4.dp)) {
                    TableEdgeAdd(active, table.cols < NoteDocTable.MAX_COLS, "Add column",
                        "notes_table_add_right_${block.id}", onReveal = { revealed = true },
                        onAdd = {
                            vm.addTableColumn(block.id)
                            pendingFocus = (focusedCell?.first ?: 0) to table.cols
                        },
                        modifier = Modifier.fillMaxSize())
                }
            }
            TableEdgeAdd(active, table.rows < NoteDocTable.MAX_ROWS, "Add row below",
                "notes_table_add_bottom_${block.id}", onReveal = { revealed = true },
                onAdd = { vm.addTableRow(block.id, atTop = false); pendingFocus = table.rows to (focusedCell?.second ?: 0) },
                modifier = Modifier.width(tableWidth).height(20.dp))
        }
    }
}

/** The editor's grid in the reading view: same cells, fills, widths and styles; no editing. */
@Composable
private fun ReadOnlyTable(block: NoteDocBlock, table: NoteDocTable) {
    val accent = Green
    val lineColor = if (table.gridLines) Border else Border.copy(alpha = .3f)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("notes_table_${block.id}")) {
        val explicit = (0 until table.cols).mapNotNull { table.widthAt(it) }
        val autoCount = table.cols - explicit.size
        val autoWidth = if (autoCount == 0) 0.dp
            else ((maxWidth - explicit.sumOf { it }.dp) / autoCount).coerceAtLeast(112.dp)
        Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(10.dp)).background(Color.White)) {
            repeat(table.rows) { r ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    repeat(table.cols) { c ->
                        val text = table.cell(r, c)
                        val runs = table.runsAt(r, c)
                        val header = table.isHeader(r, c)
                        val fill = table.fillAt(r, c)?.let { Color(it) } ?: if (header) Mint else Color.White
                        Box(Modifier.width(table.widthAt(c)?.dp ?: autoWidth).fillMaxHeight()
                            .background(lineColor).padding(1.dp)
                            .background(fill).padding(horizontal = 8.dp, vertical = 8.dp)) {
                            Text(
                                buildAnnotatedString {
                                    append(text)
                                    runs.forEach { run ->
                                        val s = run.start.coerceIn(0, text.length)
                                        val e = run.end.coerceIn(s, text.length)
                                        if (e > s) addStyle(run.toSpanStyle(accent), s, e)
                                    }
                                },
                                style = LocalTextStyle.current.copy(
                                    color = Ink, fontSize = TABLE_CELL_TEXT_SP.sp,
                                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                                modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = "Table cell, row ${r + 1}, column ${c + 1}" +
                                        if (header) ", header" else ""
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What a block row may do: the editor's own actions, or the reading view's view-only ones. */
private class NoteRowActions(
    val readOnly: Boolean,
    val toggleCollapsed: (String) -> Unit,
    val playVideo: (String?) -> Unit,
)

@Composable
private fun TableEdgeAdd(
    active: Boolean,
    enabled: Boolean,
    label: String,
    tag: String,
    onReveal: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier,
) {
    if (!enabled) { Box(modifier); return }
    Box(
        modifier.clip(RoundedCornerShape(6.dp))
            .background(if (active) Mint else Color.Transparent)
            .clickable(role = Role.Button) { if (active) onAdd() else onReveal() }
            .semantics { contentDescription = if (active) label else "$label, tap to show" }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null,
            tint = Green.copy(alpha = if (active) 1f else .28f),
            modifier = Modifier.size(if (active) 16.dp else 12.dp))
    }
}
