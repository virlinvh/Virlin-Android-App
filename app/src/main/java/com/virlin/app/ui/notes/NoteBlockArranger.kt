package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.arrangeableNoteBlocks
import kotlinx.coroutines.launch

/**
 * Arrange mode: every visible block as its own bordered card with a light six-dot handle on the
 * left. Dragging the handle, or long-pressing anywhere on a card, lifts the card; the others slide
 * aside as it passes them and the list scrolls near its edges. Dropping hands the new position to
 * [onMove], which may refuse it, in which case the cards return to the note's real order. The
 * cards are read-only previews drawn by [content], so nothing in them can be edited or tapped.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteBlockArranger(
    blocks: List<NoteDocBlock>,
    onMove: (blockId: String, toIndex: Int) -> Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (block: NoteDocBlock, before: List<NoteDocBlock>) -> Unit,
) {
    BackHandler(onBack = onDone)
    val visible = remember(blocks) { arrangeableNoteBlocks(blocks) }
    val visibleIds = remember(visible) { visible.map { it.id } }
    val byId = remember(visible) { visible.associateBy { it.id } }
    val positions = remember(blocks) { blocks.withIndex().associate { it.value.id to it.index } }
    val folded = remember(blocks, visible) {
        val counts = HashMap<String, Int>()
        var owner: String? = null
        val shown = visibleIds.toHashSet()
        blocks.forEach { block ->
            if (block.id in shown) owner = block.id
            else owner?.let { counts[it] = (counts[it] ?: 0) + 1 }
        }
        counts
    }

    val order = remember { mutableStateListOf<String>().apply { addAll(visibleIds) } }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var startOffset by remember { mutableFloatStateOf(0f) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    var settlingId by remember { mutableStateOf<String?>(null) }
    val settle = remember { Animatable(0f) }
    val list = rememberLazyListState()
    LaunchedEffect(visibleIds, draggingId) {
        if (draggingId == null && order.toList() != visibleIds) {
            // A refused move puts the cards back; the list stays where it was instead of
            // following whichever card happened to be at the top.
            val first = list.firstVisibleItemIndex
            val offset = list.firstVisibleItemScrollOffset
            order.clear()
            order.addAll(visibleIds)
            list.scrollToItem(first, offset)
        }
    }

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 72.dp.toPx() }
    val maxStep = with(density) { 18.dp.toPx() }
    val currentVisibleIds by rememberUpdatedState(visibleIds)
    val currentOnMove by rememberUpdatedState(onMove)

    fun info(id: String): LazyListItemInfo? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
    fun draggedShift(): Float {
        val item = draggingId?.let(::info) ?: return 0f
        return startOffset + dragDelta - item.offset
    }
    fun swap(from: Int, to: Int) {
        // The list keeps its place by the first visible key; pin it by index while that card moves.
        val first = list.firstVisibleItemIndex
        if (from == first || to == first) {
            val offset = list.firstVisibleItemScrollOffset
            scope.launch { list.scrollToItem(first, offset) }
        }
        order.add(to, order.removeAt(from))
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    fun followFinger() {
        val id = draggingId ?: return
        val dragged = info(id) ?: return
        val middle = startOffset + dragDelta + dragged.size / 2f
        val index = order.indexOf(id)
        val next = order.getOrNull(index + 1)?.let(::info)
        val previous = order.getOrNull(index - 1)?.let(::info)
        when {
            next != null && middle > next.offset + next.size / 2f -> swap(index, index + 1)
            previous != null && middle < previous.offset + previous.size / 2f -> swap(index, index - 1)
        }
    }
    fun startDrag(id: String) {
        if (draggingId != null) return
        val item = info(id) ?: return
        scope.launch { settle.snapTo(0f) }
        settlingId = null
        startOffset = item.offset.toFloat()
        dragDelta = 0f
        draggingId = id
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    fun dragBy(dy: Float) {
        if (draggingId == null) return
        dragDelta += dy
        followFinger()
    }
    fun endDrag() {
        val id = draggingId ?: return
        val shift = draggedShift()
        val from = currentVisibleIds.indexOf(id)
        val to = order.indexOf(id)
        settlingId = id
        draggingId = null
        scope.launch {
            settle.snapTo(shift)
            settle.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
            if (settlingId == id) settlingId = null
        }
        if (from >= 0 && to >= 0 && from != to) currentOnMove(id, to)
    }

    // Near the top or bottom edge the list scrolls under the lifted card, faster the closer it is.
    LaunchedEffect(draggingId) {
        val id = draggingId ?: return@LaunchedEffect
        while (draggingId == id) {
            withFrameNanos { }
            val dragged = info(id) ?: continue
            val top = startOffset + dragDelta
            val bottom = top + dragged.size
            val start = list.layoutInfo.viewportStartOffset.toFloat()
            val end = list.layoutInfo.viewportEndOffset.toFloat()
            val step = when {
                bottom > end - edge && list.canScrollForward -> ((bottom - (end - edge)) / edge).coerceIn(0f, 1f) * maxStep
                top < start + edge && list.canScrollBackward -> -((start + edge - top) / edge).coerceIn(0f, 1f) * maxStep
                else -> 0f
            }
            if (step != 0f) {
                list.scrollBy(step)
                followFinger()
            }
        }
    }

    Column(modifier.testTag("notes_arrange")) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Drag the dots, or long-press a block, to move it", color = NotesMuted, fontSize = 12.sp,
                modifier = Modifier.weight(1f))
            Text("Done", color = NotesAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDone)
                    .padding(horizontal = 12.dp, vertical = 10.dp).testTag("notes_arrange_done"))
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("notes_arrange_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            itemsIndexed(order.toList(), key = { _, id -> id }) { index, id ->
                val block = byId[id] ?: return@itemsIndexed
                val lifted = draggingId == id
                val moving = lifted || settlingId == id
                val scale by animateFloatAsState(if (lifted) 1.02f else 1f, label = "arrangeLift")
                val before = remember(blocks, id) { blocks.take(positions[id] ?: 0) }
                ArrangeCard(
                    block = block,
                    lifted = lifted,
                    foldedCount = folded[id] ?: 0,
                    modifier = Modifier
                        .then(if (moving) Modifier else Modifier.animateItemPlacement(
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)))
                        .zIndex(if (moving) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (lifted) draggedShift() else if (settlingId == id) settle.value else 0f
                            scaleX = scale
                            scaleY = scale
                            shadowElevation = if (moving) 10.dp.toPx() * (if (lifted) 1f else (scale - 1f) / .02f) else 0f
                            shape = RoundedCornerShape(14.dp)
                            clip = false
                        },
                    handle = Modifier
                        .pointerInput(id) {
                            detectDragGestures(
                                onDragStart = { startDrag(id) },
                                onDragEnd = { endDrag() },
                                onDragCancel = { endDrag() },
                                onDrag = { change, amount -> change.consume(); dragBy(amount.y) },
                            )
                        }
                        .semantics {
                            contentDescription = "Move block ${index + 1} of ${order.size}"
                            customActions = listOfNotNull(
                                CustomAccessibilityAction("Move up") { currentOnMove(id, index - 1) }.takeIf { index > 0 },
                                CustomAccessibilityAction("Move down") { currentOnMove(id, index + 1) }.takeIf { index < order.size - 1 },
                            )
                        }
                        .testTag("notes_arrange_handle_$id"),
                    body = Modifier.pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { startDrag(id) },
                            onDragEnd = { endDrag() },
                            onDragCancel = { endDrag() },
                            onDrag = { change, amount -> change.consume(); dragBy(amount.y) },
                        )
                    },
                ) { content(block, before) }
            }
        }
    }
}

@Composable
private fun ArrangeCard(
    block: NoteDocBlock,
    lifted: Boolean,
    foldedCount: Int,
    modifier: Modifier,
    handle: Modifier,
    body: Modifier,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.fillMaxWidth().clip(shape)
            .background(Color.White)
            .border(if (lifted) 1.5.dp else 1.dp, if (lifted) NotesAccent.copy(alpha = .55f) else NotesBorder, shape)
            .testTag("notes_arrange_block_${block.id}"),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 44.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).clipToBounds()) { content() }
            if (foldedCount > 0) {
                Spacer(Modifier.size(4.dp))
                Text(if (foldedCount == 1) "+1 folded line moves with it" else "+$foldedCount folded lines move with it",
                    color = NotesMuted, fontSize = 11.sp)
            }
        }
        // Covers the preview so its buttons stay inert; a long press here lifts the card.
        Box(Modifier.matchParentSize().padding(start = 40.dp).then(body))
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.width(40.dp).fillMaxHeight().then(handle),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 24.dp, height = 32.dp).clip(RoundedCornerShape(8.dp))
                        .background(NotesWash.copy(alpha = if (lifted) .9f else .55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.DragIndicator, contentDescription = null,
                        tint = (if (lifted) NotesAccent else NotesMuted).copy(alpha = if (lifted) 1f else .7f),
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
