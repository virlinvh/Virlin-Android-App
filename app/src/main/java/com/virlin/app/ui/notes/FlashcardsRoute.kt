package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.visibleNoteBlocks
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.onClick
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import com.virlin.app.ui.hierarchy.LocalProjectPalette
import kotlin.math.sin
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.virlin.app.R
import com.virlin.app.data.flashcard.FlashcardDeckStyle
import com.virlin.app.data.flashcard.FlashcardDeckStyleStore
import com.virlin.app.data.flashcard.FlashcardRating
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.data.flashcard.SavedFlashcard
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.ui.hierarchy.rememberProjectPalette
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

const val FLASHCARDS_ROUTE = "flashcards/{kind}/{id}"
fun flashcardsRoute(kind: String, id: String): String =
    "flashcards/${android.net.Uri.encode(kind)}/${android.net.Uri.encode(id)}"

private val StudyInk = Color(0xFF17251D)
private val StudyMuted = Color(0xFF69796E)
private val StudyGreen = Color(0xFF087454)
private val StudyLine = Color(0xFFDCE8DC)
private val StudyPaper = Color(0xFFFCFDFB)
internal val CardSerif = FontFamily(
    Font(R.font.fraunces_variable, weight = FontWeight.Medium),
    Font(R.font.fraunces_italic_variable, weight = FontWeight.Medium,
        style = FontStyle.Italic),
)

private val CardStyleTypes = setOf(NoteDocBlockType.PARAGRAPH, NoteDocBlockType.HEADING_1,
    NoteDocBlockType.HEADING_2, NoteDocBlockType.HEADING_3, NoteDocBlockType.HEADING_4,
    NoteDocBlockType.HEADING_5, NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED,
    NoteDocBlockType.CHECKLIST, NoteDocBlockType.QUOTE, NoteDocBlockType.TOGGLE, NoteDocBlockType.DIVIDER)

/**
 * Whether a front can be drawn in the card typeface without losing anything. Code, equations,
 * prompts, tables and media keep the note's formatting whatever the card's setting.
 */
internal fun flashcardFrontFitsCardStyle(blocks: List<NoteDocBlock>?): Boolean =
    blocks == null || blocks.all { it.type in CardStyleTypes }

/** A front in the deck's card typeface, sized so a single word reads as a title. */
@Composable
internal fun CardStyleFront(text: String, color: Color, modifier: Modifier = Modifier, scale: Float = 1f,
                            family: FontFamily = CardSerif, weight: FontWeight = FontWeight.Medium,
                            centered: Boolean = false) {
    val size = when {
        text.length <= 18 -> 36f
        text.length <= 60 -> 30f
        text.length <= 140 -> 25f
        else -> 21f
    } * scale
    Text(text, color = color, fontFamily = family, fontWeight = weight,
        fontSize = size.sp, lineHeight = (size * 1.18f).sp,
        textAlign = if (centered) TextAlign.Center else null,
        modifier = if (centered) modifier.fillMaxWidth() else modifier)
}

/** The front as plain text for the card typeface. */
internal fun flashcardFrontText(blocks: List<NoteDocBlock>): String =
    selectableNoteText(blocks).text.trim()
internal val CardSans = FontFamily(
    Font(R.font.dm_sans_variable, weight = FontWeight.Medium),
    Font(R.font.dm_sans_variable, weight = FontWeight.SemiBold),
    Font(R.font.dm_sans_variable, weight = FontWeight.Bold),
)

internal enum class FlashcardGroup { DUE, NEW, FUTURE, ALL, ARCHIVED }
internal enum class FlashcardSort { SCHEDULED, WEAKEST, NEWEST, STARRED, SHUFFLED }

private val GroupOrder = listOf(FlashcardGroup.NEW, FlashcardGroup.DUE, FlashcardGroup.FUTURE,
    FlashcardGroup.ALL, FlashcardGroup.ARCHIVED)
private val SortLabels = listOf(FlashcardSort.SCHEDULED to "Scheduled", FlashcardSort.WEAKEST to "Weakest first",
    FlashcardSort.NEWEST to "Newest first", FlashcardSort.STARRED to "Starred first",
    FlashcardSort.SHUFFLED to "Shuffled")

private fun FlashcardGroup.label() = name.lowercase().replaceFirstChar { it.uppercase() }

/** Archived cards appear only under [FlashcardGroup.ARCHIVED]; every other group skips them. */
internal fun groupedFlashcards(cards: List<SavedFlashcard>, group: FlashcardGroup,
    sort: FlashcardSort, now: Long, shuffleSeed: Int = 0): List<SavedFlashcard> {
    val selected = cards.filter { card ->
        if (group == FlashcardGroup.ARCHIVED) card.isArchived
        else !card.isArchived && when (group) {
            FlashcardGroup.DUE -> card.reviewCount > 0 && card.nextReviewAt <= now
            FlashcardGroup.NEW -> card.reviewCount == 0
            FlashcardGroup.FUTURE -> card.reviewCount > 0 && card.nextReviewAt > now
            else -> true
        }
    }
    val scheduled = compareBy<SavedFlashcard> {
        if (it.reviewCount == 0) Long.MIN_VALUE else it.nextReviewAt
    }.thenBy { it.createdAt }
    return when (sort) {
        FlashcardSort.SCHEDULED -> selected.sortedWith(scheduled)
        FlashcardSort.WEAKEST -> selected.sortedWith(compareByDescending<SavedFlashcard> {
            when (it.difficulty) { "HARD" -> 2; "NORMAL" -> 1; else -> 0 }
        }.thenBy { it.nextReviewAt })
        FlashcardSort.NEWEST -> selected.sortedByDescending { it.createdAt }
        FlashcardSort.STARRED -> selected.sortedWith(compareByDescending<SavedFlashcard> { it.isStarred }
            .then(scheduled))
        FlashcardSort.SHUFFLED -> selected.sortedWith(compareBy<SavedFlashcard> { shuffleKey(it.id, shuffleSeed) }
            .thenBy { it.id })
    }
}

/** Keyed on the card id, so a shuffled order holds still while cards are reviewed or archived. */
private fun shuffleKey(id: String, seed: Int): Int {
    var h = id.hashCode() xor seed
    h = (h xor (h ushr 16)) * 0x45d9f3b
    h = (h xor (h ushr 16)) * 0x45d9f3b
    return h xor (h ushr 16)
}

/** Question, answer and source note title, for the deck search. */
internal fun flashcardMatches(card: SavedFlashcard, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val back = card.backBlocks?.let { selectableNoteText(it).text } ?: card.content.text
    return listOf(frontPreview(card), back, card.noteTitle).any { it.contains(q, ignoreCase = true) }
}

internal fun flashcardGroupCounts(cards: List<SavedFlashcard>, now: Long): Map<FlashcardGroup, Int> =
    GroupOrder.associateWith { group -> groupedFlashcards(cards, group, FlashcardSort.NEWEST, now).size }

/** Index [delta] steps from [index] in a deck of [size], wrapping around both ends. */
internal fun wrappedIndex(index: Int, delta: Int, size: Int): Int =
    if (size <= 0) 0 else ((index + delta) % size + size) % size

/** Branch-scoped flashcards, including all descendant task cards. */
@Composable
fun FlashcardsRoute(navController: NavController, kind: String, id: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cards by remember(kind, id) { mutableStateOf<List<SavedFlashcard>>(emptyList()) }
    var error by remember(kind, id) { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf(false) }
    var starSaving by remember { mutableStateOf(false) }
    val repo = VirlinGraph.repository
    val tasks by repo.tasks.collectAsState()
    val streams by repo.streams.collectAsState()
    val projects by repo.projects.collectAsState()
    val ownerKeys = remember(kind, id, tasks) {
        val taskIds = when (kind) {
            "project" -> tasks.filter { it.projectId == id }.map { it.id }.toSet()
            "workstream" -> tasks.filter { it.workStreamId == id }.map { it.id }.toSet()
            else -> {
                val children = mutableSetOf(id)
                var changed: Boolean
                do {
                    val before = children.size
                    tasks.filter { it.parentTaskId in children }.forEach { children += it.id }
                    changed = children.size != before
                } while (changed)
                children
            }
        }
        taskIds.mapTo(mutableSetOf()) { "task-$it" }.also {
            if (kind == "project") it += "project-$id"
            if (kind == "workstream") it += "workstream-$id"
        }
    }
    LaunchedEffect(ownerKeys) {
        runCatching { FlashcardStore.list(context, ownerKeys) }
            .onSuccess { cards = it; error = null }
            .onFailure { error = "Could not load flashcards: ${it.message}" }
    }
    val title = when (kind) {
        "project" -> projects.firstOrNull { it.id == id }?.title
        "workstream" -> streams.firstOrNull { it.id == id }?.title
        else -> tasks.firstOrNull { it.id == id }?.title
    } ?: "This branch"
    val projectId = when (kind) {
        "project" -> id
        "workstream" -> streams.firstOrNull { it.id == id }?.projectId
        else -> tasks.firstOrNull { it.id == id }?.projectId
    }
    val palette = rememberProjectPalette(projectId)
    val accent = palette?.let { Color(it.secondary) } ?: DefaultDeckAccent
    var editingCard by remember { mutableStateOf<SavedFlashcard?>(null) }
    var editSaving by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var openingNote by remember { mutableStateOf(false) }
    val deckKey = FlashcardDeckStyleStore.deckKey(kind, id)
    var deckStyle by remember(deckKey) { mutableStateOf(FlashcardDeckStyle()) }
    var styleTouched by remember(deckKey) { mutableStateOf(false) }
    LaunchedEffect(deckKey) {
        runCatching { FlashcardDeckStyleStore.get(context, deckKey) }
            .onSuccess { if (!styleTouched) deckStyle = it }
    }
    BackHandler { navController.popBackStack() }
    CompositionLocalProvider(LocalProjectPalette provides palette) {
    FlashcardStudyScreen(title, cards, error, reviewing, onBack = { navController.popBackStack() },
        accent = accent,
        style = deckStyle,
        onStyle = { next ->
            styleTouched = true
            deckStyle = next
            scope.launch {
                runCatching { FlashcardDeckStyleStore.save(context, deckKey, next) }
                    .onFailure { error = "Could not save card design: ${it.message}" }
            }
        },
        // The note opens at the card's own block: the editor restores a saved reading position
        // on open, so that position is pointed at the block first.
        onOpenNote = { card ->
            if (!openingNote) scope.launch {
                openingNote = true
                try {
                    val blocks = runCatching { VirlinGraph.actions.loadNoteDoc(card.ownerKey) }.getOrNull()
                        ?.takeIf { it.loadError == null }?.blocks.orEmpty()
                    flashcardSourceBlockId(card, blocks)?.let { NotePositionStore(context).save(card.ownerKey, it, 0) }
                    navController.navigate(notesRoute(card.ownerKey, card.noteTitle))
                } finally {
                    openingNote = false
                }
            }
        },
        onEdit = { card -> editError = null; editingCard = card },
        onStar = { card ->
            if (!starSaving) scope.launch {
                starSaving = true
                runCatching { FlashcardStore.setStarred(context, card.id, !card.isStarred) }
                    .onSuccess { updated ->
                        cards = cards.map { if (it.id == updated.id) updated else it }
                        error = null
                    }
                    .onFailure { error = "Could not update card: ${it.message}" }
                starSaving = false
            }
        },
        onArchive = { card, archived ->
            scope.launch {
                runCatching { FlashcardStore.setArchived(context, card.id, archived) }
                    .onSuccess { updated ->
                        cards = cards.map { if (it.id == updated.id) updated else it }
                        error = null
                    }
                    .onFailure { error = "Could not update card: ${it.message}" }
            }
        },
        onReview = { card, rating, onSaved ->
            if (!reviewing) scope.launch {
                reviewing = true
                runCatching { FlashcardStore.review(context, card.id, rating) }
                    .onSuccess { updated ->
                        cards = cards.map { if (it.id == updated.id) updated else it }
                        error = null
                        onSaved()
                    }
                    .onFailure { error = "Could not save review: ${it.message}" }
                reviewing = false
            }
        })
    editingCard?.let { card ->
        val start = remember(card.id) { flashcardEditStart(card) }
        FlashcardCreateDialog(emptyList(), editSaving, editError,
            onDismiss = { if (!editSaving) { editingCard = null; editError = null } }, edit = start,
            onSave = { front, back, difficulty, _, frontCardStyle ->
                if (!editSaving) scope.launch {
                    editSaving = true
                    runCatching {
                        val question = flashcardSummaryBlock(front)
                        val prompt = question.text.trim().take(300)
                        FlashcardStore.update(context, card.id, flashcardSummaryBlock(back), prompt,
                            difficulty, question.takeIf { it.text.trim() == prompt }, front, back,
                            frontCardStyle)
                    }
                        .onSuccess { updated ->
                            cards = cards.map { if (it.id == updated.id) updated else it }
                            editingCard = null; editError = null
                        }
                        .onFailure { editError = "Could not save changes: ${it.message}" }
                    editSaving = false
                }
            })
    }
    }
}

/**
 * The note block a card should open at: the block it was made from, or for older cards the
 * first block holding one of its lines. A block folded away under a collapsed heading or
 * toggle opens at that heading instead.
 */
internal fun flashcardSourceBlockId(card: SavedFlashcard, blocks: List<NoteDocBlock>): String? {
    if (blocks.isEmpty()) return null
    val lines = flashcardSourceLines(card)
    val target = card.sourceBlockId?.takeIf { id -> blocks.any { it.id == id } }
        ?: lines.firstNotNullOfOrNull { line ->
            blocks.firstOrNull { it.text.contains(line, ignoreCase = true) }?.id
        }
        // A card line edited after it was made still holds its whole source line.
        ?: lines.firstNotNullOfOrNull { line ->
            blocks.firstOrNull { block ->
                val text = block.text.trim()
                text.length >= 12 && line.contains(text, ignoreCase = true)
            }?.id
        } ?: return null
    val shown = visibleNoteBlocks(blocks, foldHeadings = false).mapTo(HashSet()) { it.id }
    val at = blocks.indexOfFirst { it.id == target }
    return (at downTo 0).firstOrNull { blocks[it].id in shown }?.let { blocks[it].id }
}

private fun flashcardSourceLines(card: SavedFlashcard): List<String> =
    (card.backBlocks.orEmpty() + card.content + card.frontBlocks.orEmpty() + listOfNotNull(card.frontContent))
        .asSequence()
        .flatMap { it.text.lineSequence() }
        .map { it.trim().take(80) }
        .filter { it.length >= 4 }
        .distinct()
        .take(8)
        .toList()

/**
 * Two pages over one deck: the deck page (groups, sort, every card as a coloured tile) and the
 * practice page, where the card takes nearly the whole screen.
 */
@Composable
internal fun FlashcardStudyScreen(title: String, cards: List<SavedFlashcard>, error: String?,
    reviewing: Boolean, onBack: () -> Unit, onStar: (SavedFlashcard) -> Unit = {},
    onArchive: (SavedFlashcard, Boolean) -> Unit = { _, _ -> },
    accent: Color = DefaultDeckAccent,
    onOpenNote: ((SavedFlashcard) -> Unit)? = null,
    onEdit: ((SavedFlashcard) -> Unit)? = null,
    style: FlashcardDeckStyle = FlashcardDeckStyle(),
    onStyle: ((FlashcardDeckStyle) -> Unit)? = null,
    onReview: (SavedFlashcard, FlashcardRating, () -> Unit) -> Unit) {
    val look = remember(style, accent) { DeckLook(style, accent) }
    CompositionLocalProvider(LocalDeckLook provides look) {
        FlashcardStudyContent(title, cards, error, reviewing, onBack, onStar, onArchive, accent, onOpenNote,
            onEdit, style, onStyle, onReview)
    }
}

@Composable
private fun FlashcardStudyContent(title: String, cards: List<SavedFlashcard>, error: String?,
    reviewing: Boolean, onBack: () -> Unit, onStar: (SavedFlashcard) -> Unit,
    onArchive: (SavedFlashcard, Boolean) -> Unit, accent: Color,
    onOpenNote: ((SavedFlashcard) -> Unit)?, onEdit: ((SavedFlashcard) -> Unit)?,
    style: FlashcardDeckStyle, onStyle: ((FlashcardDeckStyle) -> Unit)?,
    onReview: (SavedFlashcard, FlashcardRating, () -> Unit) -> Unit) {
    // Saveable so a trip to the source note returns to the same group, card and page.
    var group by rememberSaveable { mutableStateOf(FlashcardGroup.DUE) }
    var initialGroupChosen by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(FlashcardSort.SCHEDULED) }
    var shuffleSeed by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var practicing by rememberSaveable { mutableStateOf(false) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var archivedNotice by remember { mutableStateOf<SavedFlashcard?>(null) }
    val counts = flashcardGroupCounts(cards, now)
    LaunchedEffect(cards.size) {
        if (!initialGroupChosen && cards.isNotEmpty()) {
            group = when {
                (counts[FlashcardGroup.DUE] ?: 0) > 0 -> FlashcardGroup.DUE
                (counts[FlashcardGroup.NEW] ?: 0) > 0 -> FlashcardGroup.NEW
                else -> FlashcardGroup.FUTURE
            }
            initialGroupChosen = true
        }
    }
    LaunchedEffect(archivedNotice) { if (archivedNotice != null) { delay(4_000); archivedNotice = null } }
    val filtered = groupedFlashcards(cards, group, sort, now, shuffleSeed).filter { flashcardMatches(it, query) }
    val chooseGroup: (FlashcardGroup) -> Unit = { group = it; initialGroupChosen = true; index = 0 }
    val chooseSort: (FlashcardSort) -> Unit = {
        if (it == FlashcardSort.SHUFFLED) shuffleSeed = kotlin.random.Random.nextInt()
        sort = it; index = 0
    }
    val archive: (SavedFlashcard) -> Unit = { card ->
        onArchive(card, !card.isArchived)
        if (!card.isArchived) archivedNotice = card
    }
    BackHandler(enabled = practicing) { practicing = false }
    Box(Modifier.fillMaxSize().background(StudyPaper)) {
        if (practicing) FlashcardPracticePage(title, group, counts, filtered, sort, index, error, reviewing, accent,
            onIndex = { index = it }, onGroup = chooseGroup, onSort = chooseSort,
            onClose = { practicing = false }, onStar = onStar, onArchive = archive, onOpenNote = onOpenNote,
            onEdit = onEdit,
            onReview = { card, rating, saved -> onReview(card, rating) { now = System.currentTimeMillis(); saved() } })
        else FlashcardDeckPage(title, cards, group, counts, filtered, sort, error, now, accent,
            query = query, onQuery = { query = it; index = 0 },
            onBack = onBack, onGroup = chooseGroup, onSort = chooseSort,
            onPractice = { start -> index = start; practicing = true },
            onShuffle = { chooseSort(FlashcardSort.SHUFFLED); practicing = true },
            onStar = onStar, onArchive = archive, style = style, onStyle = onStyle)
        AnimatedVisibility(archivedNotice != null, Modifier.align(Alignment.BottomCenter).padding(16.dp),
            enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(StudyInk)
                .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Card archived", color = Color.White, fontSize = 13.sp)
                TextButton(onClick = {
                    archivedNotice?.let { onArchive(it.copy(isArchived = true), false) }
                    archivedNotice = null
                }, modifier = Modifier.testTag("flashcard_archive_undo")) {
                    Text("Undo", color = lerp(accent, Color.White, .6f), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** The sort button; it lights up in the accent while its strip is open. */
@Composable
private fun SortToggle(open: Boolean, accent: Color, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.testTag("flashcard_sort")) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp))
            .background(if (open) accent.copy(alpha = .14f) else Color.Transparent),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.SwapVert, contentDescription = if (open) "Hide sort options" else "Sort cards",
                tint = if (open) accent else StudyInk)
        }
    }
}

private fun sortOptions(sort: FlashcardSort, onSort: (FlashcardSort) -> Unit): List<PracticeOption> =
    SortLabels.map { (value, label) ->
        PracticeOption("flashcard_option_sort_${value.name.lowercase()}", label, sortIcon(value),
            selected = value == sort) { onSort(value) }
    }

private fun frontPreview(card: SavedFlashcard): String =
    card.frontBlocks?.let { selectableNoteText(it).text.trim() }?.takeIf { it.isNotEmpty() }
        ?: card.frontContent?.text?.takeIf { it.isNotBlank() }
        ?: card.recallPrompt.ifBlank { "What do you remember from this passage?" }

internal fun flashcardStatus(card: SavedFlashcard, now: Long): String = when {
    card.isArchived -> "Archived"
    card.reviewCount == 0 -> "New"
    card.nextReviewAt <= now -> "Due"
    else -> {
        val minutes = (card.nextReviewAt - now) / 60_000L
        when {
            minutes < 60 -> "In ${minutes.coerceAtLeast(1)} min"
            minutes < 24 * 60 -> "In ${minutes / 60} h"
            else -> "In ${(minutes + 24 * 60 - 1) / (24 * 60)} d"
        }
    }
}

internal val DefaultDeckAccent = Color(0xFF087848)

/** The deck as a gallery: title, a hero in the project colour, group chips, then a masonry of cards. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FlashcardDeckPage(title: String, cards: List<SavedFlashcard>, group: FlashcardGroup,
    counts: Map<FlashcardGroup, Int>, filtered: List<SavedFlashcard>, sort: FlashcardSort, error: String?,
    now: Long, accent: Color, query: String, onQuery: (String) -> Unit,
    onBack: () -> Unit, onGroup: (FlashcardGroup) -> Unit, onSort: (FlashcardSort) -> Unit,
    onPractice: (Int) -> Unit, onShuffle: () -> Unit, onStar: (SavedFlashcard) -> Unit,
    onArchive: (SavedFlashcard) -> Unit, style: FlashcardDeckStyle = FlashcardDeckStyle(),
    onStyle: ((FlashcardDeckStyle) -> Unit)? = null) {
    var searching by remember { mutableStateOf(query.isNotEmpty()) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var styling by rememberSaveable { mutableStateOf(false) }
    if (styling && onStyle != null) FlashcardDeckStyleSheet(style, accent, title,
        samples = cards.filterNot { it.isArchived }.take(2).map { frontPreview(it) to it.colorIndex },
        onChange = onStyle, onDismiss = { styling = false })
    Column(Modifier.fillMaxSize().background(lerp(DeckPaper, accent, .035f))) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = StudyInk)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { if (searching) onQuery(""); searching = !searching },
                modifier = Modifier.testTag("flashcard_search_toggle")) {
                Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (searching) "Close search" else "Search cards", tint = StudyInk)
            }
            SortToggle(sorting, accent) { sorting = !sorting }
            if (onStyle != null) IconButton(onClick = { styling = true },
                modifier = Modifier.testTag("flashcard_style_button")) {
                Icon(Icons.Outlined.Tune, contentDescription = "Card design for this branch", tint = StudyInk)
            }
        }
        AnimatedVisibility(sorting, enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140))) {
            PracticeOptionsStrip(listOf(sortOptions(sort, onSort)), accent, onClose = { sorting = false },
                tag = "flashcard_sort_strip", modifier = Modifier.padding(bottom = 8.dp))
        }
        LazyVerticalStaggeredGrid(StaggeredGridCells.Adaptive(132.dp),
            Modifier.weight(1f).fillMaxWidth().testTag("flashcard_deck_grid"),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalItemSpacing = 12.dp) {
            item(key = "title", span = StaggeredGridItemSpan.FullLine) { DeckTitle(title, cards, counts, accent) }
            item(key = "hero", span = StaggeredGridItemSpan.FullLine) {
                DeckHero(group, cards, filtered, query, now, accent, onPractice = { onPractice(0) }, onShuffle)
            }
            item(key = "groups", span = StaggeredGridItemSpan.FullLine) { DeckGroupStrip(group, counts, accent, onGroup = onGroup) }
            if (searching) item(key = "search", span = StaggeredGridItemSpan.FullLine) {
                DeckSearchField(query, accent, onQuery)
            }
            if (error != null) item(key = "error", span = StaggeredGridItemSpan.FullLine) {
                Text(error, color = Color(0xFF9C3B34), fontSize = 12.sp, fontFamily = CardSans)
            }
            if (filtered.isEmpty()) item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                DeckEmpty(if (query.isNotBlank()) "Nothing matches \u201C${query.trim()}\u201D."
                    else emptyGroupMessage(counts, group))
            } else staggeredItemsIndexed(filtered, key = { _, card -> card.id }) { position, card ->
                DeckTile(card, position, flashcardStatus(card, now), onOpen = { onPractice(position) },
                    onStar = { onStar(card) }, onArchive = { onArchive(card) })
            }
        }
    }
}

private val DeckPaper = Color(0xFFFBFAF7)

@Composable
private fun DeckTitle(title: String, cards: List<SavedFlashcard>, counts: Map<FlashcardGroup, Int>, accent: Color) {
    val total = counts[FlashcardGroup.ALL] ?: 0
    val learned = cards.count { !it.isArchived && it.reviewCount > 0 }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp)) {
        Text("FLASHCARDS", color = accent, fontSize = 11.sp, letterSpacing = 2.2.sp,
            fontFamily = CardSans, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(title, color = StudyInk, fontFamily = CardSerif, fontWeight = FontWeight.Medium,
            fontSize = 30.sp, lineHeight = 35.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text("$total ${if (total == 1) "card" else "cards"}  ·  ${counts[FlashcardGroup.DUE] ?: 0} due  ·  " +
            "$learned learned", color = StudyMuted, fontFamily = CardSans, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun DeckHero(group: FlashcardGroup, cards: List<SavedFlashcard>, filtered: List<SavedFlashcard>,
    query: String, now: Long, accent: Color, onPractice: () -> Unit, onShuffle: () -> Unit) {
    val enabled = filtered.isNotEmpty()
    val shape = RoundedCornerShape(26.dp)
    val active = cards.filterNot { it.isArchived }
    val learned = active.count { it.reviewCount > 0 }
    val progress = if (active.isEmpty()) 0f else learned.toFloat() / active.size
    val line = when {
        query.isNotBlank() -> "Cards matching \u201C${query.trim()}\u201D."
        group == FlashcardGroup.NEW -> "Fresh cards you haven't studied yet."
        group == FlashcardGroup.DUE -> "Waiting for you right now."
        group == FlashcardGroup.FUTURE -> active.filter { it.reviewCount > 0 && it.nextReviewAt > now }
            .minByOrNull { it.nextReviewAt }
            ?.let { "Next one ${flashcardStatus(it, now).lowercase()}." } ?: "Nothing scheduled yet."
        group == FlashcardGroup.ALL -> "Every card in practice, in your chosen order."
        else -> "Kept safe, out of every practice queue."
    }
    Column(Modifier.fillMaxWidth()
        .shadow(14.dp, shape, ambientColor = accent, spotColor = accent)
        .clip(shape)
        .background(Brush.linearGradient(listOf(lerp(accent, Color.White, .10f), lerp(accent, Color.Black, .30f))))
        .drawBehind {
            drawCircle(Color.White.copy(alpha = .07f), size.height * .62f, Offset(size.width * .95f, size.height * .05f))
            drawCircle(Color.White.copy(alpha = .05f), size.height * .38f, Offset(size.width * .70f, size.height * .02f))
        }
        .padding(20.dp)) {
        Text(when (group) {
            FlashcardGroup.NEW -> "NEW CARDS"
            FlashcardGroup.DUE -> "DUE FOR REVIEW"
            FlashcardGroup.FUTURE -> "COMING UP"
            FlashcardGroup.ALL -> "WHOLE DECK"
            FlashcardGroup.ARCHIVED -> "ARCHIVE"
        }, color = Color.White.copy(alpha = .8f), fontSize = 11.sp, letterSpacing = 1.8.sp,
            fontFamily = CardSans, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${filtered.size}", color = Color.White, fontFamily = CardSerif, fontWeight = FontWeight.Medium,
                fontSize = 52.sp, lineHeight = 56.sp)
            Spacer(Modifier.width(8.dp))
            Text(if (filtered.size == 1) "card ready" else "cards ready", color = Color.White.copy(alpha = .92f),
                fontFamily = CardSans, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 11.dp))
        }
        Text(line, color = Color.White.copy(alpha = .8f), fontFamily = CardSans, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Learned", color = Color.White.copy(alpha = .78f), fontFamily = CardSans, fontSize = 11.sp,
                modifier = Modifier.weight(1f))
            Text("$learned / ${active.size}", color = Color.White, fontFamily = CardSans,
                fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f))
            .semantics { contentDescription = "$learned of ${active.size} cards learned" }) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape).background(Color.White))
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(16.dp))
                .background(if (enabled) Color.White else Color.White.copy(alpha = .18f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onPractice)
                .testTag("flashcard_start"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null,
                    tint = if (enabled) accent else Color.White.copy(alpha = .7f), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (enabled) "Start practice" else "Nothing to practise",
                    color = if (enabled) lerp(accent, Color.Black, .15f) else Color.White.copy(alpha = .75f),
                    fontFamily = CardSans, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = .16f))
                .border(1.dp, Color.White.copy(alpha = .32f), RoundedCornerShape(16.dp))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Shuffle and practise",
                    onClick = onShuffle)
                .semantics { contentDescription = "Shuffle and practise" }
                .testTag("flashcard_shuffle"), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Shuffle, contentDescription = null,
                    tint = Color.White.copy(alpha = if (enabled) 1f else .5f), modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun DeckGroupStrip(group: FlashcardGroup, counts: Map<FlashcardGroup, Int>, accent: Color,
    compact: Boolean = false, onGroup: (FlashcardGroup) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(vertical = 2.dp, horizontal = if (compact) 16.dp else 0.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        GroupOrder.forEach { value ->
            val selected = group == value
            val shape = RoundedCornerShape(19.dp)
            Row(Modifier.height(if (compact) 32.dp else 38.dp).clip(shape)
                .background(if (selected) accent else Color.White)
                .border(1.dp, if (selected) accent else StudyInk.copy(alpha = .08f), shape)
                .clickable(role = Role.Tab) { onGroup(value) }
                .testTag("flashcard_group_${value.name.lowercase()}")
                .padding(start = 12.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(groupIcon(value), contentDescription = null, modifier = Modifier.size(15.dp),
                    tint = if (selected) Color.White else StudyMuted)
                Spacer(Modifier.width(6.dp))
                Text(value.label(), color = if (selected) Color.White else StudyInk, fontFamily = CardSans,
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                Spacer(Modifier.width(7.dp))
                Box(Modifier.clip(CircleShape)
                    .background(if (selected) Color.White.copy(alpha = .24f) else accent.copy(alpha = .10f))
                    .padding(horizontal = 7.dp, vertical = 1.dp)) {
                    Text("${counts[value] ?: 0}", color = if (selected) Color.White else accent,
                        fontFamily = CardSans, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

private fun groupIcon(group: FlashcardGroup) = when (group) {
    FlashcardGroup.NEW -> Icons.Outlined.Star
    FlashcardGroup.DUE -> Icons.Outlined.CalendarToday
    FlashcardGroup.FUTURE -> Icons.Outlined.AccessTime
    FlashcardGroup.ALL -> Icons.Outlined.Layers
    FlashcardGroup.ARCHIVED -> Icons.Outlined.Archive
}

@Composable
private fun DeckSearchField(query: String, accent: Color, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(15.dp)).background(Color.White)
        .border(1.dp, accent.copy(alpha = .35f), RoundedCornerShape(15.dp)).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, contentDescription = null, tint = StudyMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search questions and answers", color = StudyMuted,
                fontFamily = CardSans, fontSize = 14.sp, maxLines = 1)
            BasicTextField(query, onQuery, singleLine = true,
                textStyle = TextStyle(color = StudyInk, fontFamily = CardSans, fontSize = 14.sp),
                cursorBrush = SolidColor(accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("flashcard_search"))
        }
        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = StudyMuted,
                modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DeckEmpty(message: String) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 120.dp, height = 96.dp), contentAlignment = Alignment.Center) {
            listOf(-10f to 4, 6f to 2, 0f to 0).forEach { (turn, colour) ->
                Box(Modifier.size(width = 74.dp, height = 90.dp).graphicsLayer { rotationZ = turn }
                    .shadow(3.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
                    .background(flashcardColor(colour)))
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(message, color = StudyInk.copy(alpha = .75f), fontFamily = CardSerif, fontWeight = FontWeight.Medium,
            fontSize = 17.sp, lineHeight = 23.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp))
    }
}

private fun emptyGroupMessage(counts: Map<FlashcardGroup, Int>, group: FlashcardGroup): String = when {
    counts.values.sum() == 0 -> "No flashcards yet. Select text in a note and tap Flashcard."
    group == FlashcardGroup.DUE -> "Nothing due right now. Your next review is on its way."
    group == FlashcardGroup.NEW -> "You've introduced all your new cards."
    group == FlashcardGroup.ARCHIVED -> "No archived cards. Archive a card to keep it out of practice."
    else -> "No cards in this group yet."
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeckTile(card: SavedFlashcard, position: Int, status: String, onOpen: () -> Unit,
    onStar: () -> Unit, onArchive: () -> Unit) {
    var menu by remember(card.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val look = LocalDeckLook.current
    val style = look.style
    val paint = remember(style, card.colorIndex, look.accent) {
        cardPaint(style, card.colorIndex, look.accent, large = false)
    }
    val color = paint.bottom
    val ink = paint.ink
    val preview = frontPreview(card)
    val shape = style.corners.tileShape()
    val scale = style.frontScale()
    val minHeight = when {
        preview.length < 28 -> 156.dp
        preview.length < 70 -> 190.dp
        else -> 226.dp
    }
    Box(if (style.scattered) Modifier.graphicsLayer { rotationZ = scatterTurn(card.id) } else Modifier) {
        Box(Modifier.fillMaxWidth().heightIn(min = minHeight)
            .shadow(6.dp, shape, ambientColor = lerp(color, Color.Black, .35f),
                spotColor = lerp(color, Color.Black, .35f))
            .clip(shape)
            .cardSurface(paint, shape, large = false, seed = card.colorIndex)
            .combinedClickable(onClickLabel = "Practise from this card", onLongClickLabel = "Card options",
                onLongClick = { menu = true }, onClick = onOpen)
            .testTag("flashcard_deck_tile_${card.id}")) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, end = 6.dp, bottom = 40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(CircleShape)
                        .background(Color.White.copy(alpha = if (paint.design.dark) .18f else .58f))
                        .padding(horizontal = 9.dp, vertical = 3.dp)) {
                        Text(status.uppercase(), color = ink.copy(alpha = .78f), fontFamily = CardSans,
                            fontWeight = FontWeight.Bold, fontSize = 9.sp, letterSpacing = 1.2.sp, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onStar, modifier = Modifier.size(40.dp)
                        .testTag("flashcard_tile_star_${card.id}")) {
                        Icon(if (card.isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (card.isStarred) "Unstar card" else "Star card",
                            tint = if (card.isStarred) Color(0xFFE69A06) else ink.copy(alpha = .4f),
                            modifier = Modifier.size(19.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(preview, color = ink, fontFamily = style.typeface.family(), fontWeight = style.typeface.weight,
                    fontSize = (17f * scale).sp, lineHeight = (23f * scale).sp, maxLines = 7,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (style.centered) TextAlign.Center else null,
                    modifier = Modifier.padding(end = 10.dp)
                        .then(if (style.centered) Modifier.fillMaxWidth() else Modifier))
            }
            if (style.showDetails) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("No. ${(position + 1).toString().padStart(2, '0')}", color = ink.copy(alpha = .5f),
                    fontFamily = CardSans, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                    letterSpacing = 1.sp, modifier = Modifier.weight(1f))
                if (card.reviewCount > 0) Text("${card.reviewCount} ${if (card.reviewCount == 1) "review" else "reviews"}",
                    color = ink.copy(alpha = .5f), fontFamily = CardSans, fontSize = 10.sp)
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (card.isStarred) "Unstar" else "Star") },
                onClick = { onStar(); menu = false })
            DropdownMenuItem(text = { Text("Copy question") },
                onClick = { clipboard.setText(AnnotatedString(preview)); menu = false })
            DropdownMenuItem(text = { Text(if (card.isArchived) "Restore to practice" else "Archive") },
                onClick = { onArchive(); menu = false })
        }
    }
}

private val DeckLift = 10.dp
private const val DeckScaleStep = .045f
private enum class LayerRole { STACK, PREVIOUS }
private class DeckLayer(val card: SavedFlashcard, val role: LayerRole, val slot: Int)
/**
 * A physical deck. Every visible card keeps its own composition (keyed by id) while it moves
 * between roles, so nothing is rebuilt mid-gesture: the top card follows the finger and flies
 * off, the cards beneath rise one step, and the previous card slides back in on top. Positions
 * are read only inside graphics layers, so a swipe never recomposes the page.
 */
@Composable
private fun FlashcardPracticePage(title: String, group: FlashcardGroup, counts: Map<FlashcardGroup, Int>,
    filtered: List<SavedFlashcard>, sort: FlashcardSort, index: Int, error: String?, reviewing: Boolean,
    accent: Color,
    onIndex: (Int) -> Unit, onGroup: (FlashcardGroup) -> Unit, onSort: (FlashcardSort) -> Unit,
    onClose: () -> Unit, onStar: (SavedFlashcard) -> Unit, onArchive: (SavedFlashcard) -> Unit,
    onOpenNote: ((SavedFlashcard) -> Unit)?,
    onEdit: ((SavedFlashcard) -> Unit)?,
    onReview: (SavedFlashcard, FlashcardRating, () -> Unit) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val count = filtered.size
    var currentId by rememberSaveable(group, sort) {
        mutableStateOf(filtered.getOrNull(index.coerceIn(0, (count - 1).coerceAtLeast(0)))?.id)
    }
    var lastPos by rememberSaveable(group, sort) { mutableIntStateOf(index) }
    val pos = filtered.indexOfFirst { it.id == currentId }.takeIf { it >= 0 }
        ?: lastPos.coerceIn(0, (count - 1).coerceAtLeast(0))
    val card = filtered.getOrNull(pos)
    LaunchedEffect(card?.id, pos) {
        if (card != null) currentId = card.id
        lastPos = pos
        if (index != pos) onIndex(pos)
    }
    var flipped by remember { mutableStateOf(emptySet<String>()) }
    var leaving by remember { mutableStateOf<SavedFlashcard?>(null) }
    var moving by remember { mutableStateOf(false) }
    var optionsOpen by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) { if (copied != null) { delay(1_600); copied = null } }
    val offset = remember { Animatable(0f) }
    // How firmly the top card is held (0..1) and which half it was grabbed by (1 top, -1 bottom);
    // both are read only inside graphics layers.
    val press = remember { Animatable(0f) }
    var grip by remember { mutableFloatStateOf(1f) }
    val flingSpeed = with(LocalDensity.current) { 420.dp.toPx() }
    var deckWidth by remember { mutableFloatStateOf(1f) }
    val canMove = count > 1
    val backward by remember { derivedStateOf { offset.value > .5f } }
    // A Previous / Next tap that lands while a card is still moving runs as soon as it settles.
    var queuedStep by remember { mutableIntStateOf(0) }
    LaunchedEffect(group, sort) {
        offset.snapTo(0f); leaving = null; moving = false; flipped = emptySet(); queuedStep = 0
    }
    val cardFlipped = card != null && card.id in flipped

    // Plain lambdas, not references to local functions: the Compose compiler memoizes
    // `::localFun` without its captures, which left the buttons acting on a stale card.
    val flip: () -> Unit = { card?.let { c -> flipped = if (c.id in flipped) flipped - c.id else flipped + c.id } }
    val flyOut: (SavedFlashcard, String?, Float) -> Unit = { from, targetId, velocity ->
        moving = true
        leaving = from
        currentId = targetId
        scope.launch {
            try {
                // Ends once the card is well off screen instead of creeping the last pixels.
                offset.animateTo(-deckWidth * 1.3f, spring(dampingRatio = 1f, stiffness = 340f,
                    visibilityThreshold = deckWidth * .02f), velocity.coerceAtMost(0f))
            } finally {
                flipped = flipped - from.id
                leaving = null
                offset.snapTo(0f)
                grip = 1f
                moving = false
            }
        }
    }
    val idle = canMove && !moving && leaving == null
    val next: (Float) -> Unit = { velocity ->
        if (idle && card != null) flyOut(card, filtered[wrappedIndex(pos, 1, count)].id, velocity)
        else if (canMove) queuedStep = 1
    }
    val previous: (Float) -> Unit = { velocity ->
        if (idle && card != null) {
            val leavingTop = card
            val target = filtered[wrappedIndex(pos, -1, count)]
            moving = true
            scope.launch {
                try {
                    offset.animateTo(deckWidth, spring(dampingRatio = 1f, stiffness = 380f,
                        visibilityThreshold = 1.5f), velocity.coerceAtLeast(0f))
                    flipped = flipped - leavingTop.id
                    currentId = target.id
                } finally {
                    offset.snapTo(0f)
                    moving = false
                }
            }
        } else if (canMove) queuedStep = -1
    }
    val latestNext by rememberUpdatedState(next)
    val latestPrevious by rememberUpdatedState(previous)
    LaunchedEffect(moving, leaving, queuedStep) {
        if (!moving && leaving == null && queuedStep != 0) {
            val step = queuedStep
            queuedStep = 0
            if (step > 0) latestNext(0f) else latestPrevious(0f)
        }
    }
    // A release commits when the card is far enough along, or would be once its momentum carries
    // it, or on a quick flick; flicking back against the drag cancels.
    val settle: (Float) -> Unit = { velocity ->
        val o = offset.value
        val projected = o + velocity * .18f
        val reach = deckWidth * .26f
        when {
            o < 0f && velocity < flingSpeed * .4f && (projected < -reach || velocity < -flingSpeed) -> next(velocity)
            o > 0f && velocity > -flingSpeed * .4f && (projected > reach || velocity > flingSpeed) -> previous(velocity)
            else -> scope.launch {
                offset.animateTo(0f, spring(dampingRatio = .7f, stiffness = 480f), velocity)
                grip = 1f
            }
        }
    }
    // A swipe that starts while a card is still moving is remembered and runs right after it.
    val queueSwipe: (Float, Float) -> Unit = { distance, velocity ->
        val travel = distance + velocity * .18f
        if (canMove && (abs(distance) > flingSpeed * .06f || abs(velocity) > flingSpeed))
            queuedStep = if (travel < 0f) 1 else -1
    }
    val rate: (FlashcardRating) -> Unit = { rating ->
        val c = card
        if (c != null && !reviewing && !moving && leaving == null) {
            val nextId = if (canMove) filtered[wrappedIndex(pos, 1, count)].id else null
            onReview(c, rating) {
                val leavesGroup = group == FlashcardGroup.NEW || group == FlashcardGroup.DUE
                if (nextId == null && !leavesGroup) flipped = flipped - c.id else flyOut(c, nextId, 0f)
            }
        }
    }
    val archiveCurrent: () -> Unit = {
        val c = card
        if (c != null && !moving && leaving == null) {
            flyOut(c, if (canMove) filtered[wrappedIndex(pos, 1, count)].id else null, 0f)
            onArchive(c)
        }
    }

    val glow = animateColorAsState(card?.let { flashcardColor(it.colorIndex) } ?: accent.copy(alpha = .2f),
        tween(520), label = "deck glow")
    val progress by animateFloatAsState(if (count == 0) 0f else (pos + 1f) / count, tween(380),
        label = "deck progress")
    Column(Modifier.fillMaxSize().background(lerp(DeckPaper, accent, .04f))) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("flashcard_back_to_deck")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to deck", tint = StudyInk)
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = StudyInk, fontFamily = CardSans, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    Text(group.label(), color = accent, fontFamily = CardSans, fontWeight = FontWeight.Bold,
                        fontSize = 11.sp, maxLines = 1)
                    Text("  ·  ${if (count == 0) 0 else pos + 1} of $count", color = StudyMuted,
                        fontFamily = CardSans, fontSize = 11.sp, maxLines = 1)
                }
            }
            if (card != null) {
                IconButton(onClick = { onStar(card) }, modifier = Modifier.testTag("flashcard_star")) {
                    Icon(if (card.isStarred) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (card.isStarred) "Unstar card" else "Star card",
                        tint = if (card.isStarred) Color(0xFFE69A06) else StudyInk.copy(alpha = .7f))
                }
                IconButton(onClick = archiveCurrent, modifier = Modifier.testTag("flashcard_archive")) {
                    Icon(if (card.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                        contentDescription = if (card.isArchived) "Restore card to practice" else "Archive card",
                        tint = StudyInk.copy(alpha = .8f))
                }
            }
            IconButton(onClick = { optionsOpen = !optionsOpen },
                modifier = Modifier.testTag("flashcard_options")) {
                OptionsGlyph(optionsOpen, accent,
                    Modifier.semantics {
                        contentDescription = if (optionsOpen) "Hide card options" else "Card options"
                    })
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(3.dp).clip(CircleShape)
            .background(accent.copy(alpha = .12f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(CircleShape).background(accent))
        }
        AnimatedVisibility(optionsOpen, enter = expandVertically(tween(220)) + fadeIn(tween(220)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140))) {
            val options = buildList {
                if (card != null) add(listOfNotNull(
                    onEdit?.let { edit ->
                        PracticeOption("flashcard_option_edit", "Edit", Icons.Outlined.Edit) {
                            optionsOpen = false; edit(card)
                        }
                    },
                    PracticeOption("flashcard_option_copy_question",
                        if (copied == "question") "Copied" else "Copy question",
                        if (copied == "question") Icons.Outlined.Check else Icons.Outlined.ContentCopy) {
                        clipboard.setText(card.frontBlocks?.let(::selectableNoteText)
                            ?: AnnotatedString(card.recallPrompt.ifBlank { "What do you remember from this passage?" }))
                        copied = "question"
                    },
                    PracticeOption("flashcard_option_copy_answer",
                        if (copied == "answer") "Copied" else "Copy answer",
                        if (copied == "answer") Icons.Outlined.Check else Icons.Outlined.ContentCopy) {
                        clipboard.setText(card.backBlocks?.let(::selectableNoteText) ?: AnnotatedString(card.content.text))
                        copied = "answer"
                    },
                    onOpenNote?.let { open ->
                        PracticeOption("flashcard_option_open_note", "Open in note",
                            Icons.AutoMirrored.Outlined.OpenInNew) { optionsOpen = false; open(card) }
                    },
                ))
                add(sortOptions(sort, onSort))
            }
            PracticeOptionsStrip(options, accent, onClose = { optionsOpen = false })
        }
        Spacer(Modifier.height(10.dp))
        DeckGroupStrip(group, counts, accent, compact = true, onGroup = onGroup)
        if (error != null) Text(error, color = Color(0xFF9C3B34), fontSize = 12.sp, fontFamily = CardSans,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
        val shown = card ?: leaving
        if (shown == null) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                DeckEmpty(emptyGroupMessage(counts, group))
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClose) {
                    Text("Back to deck", color = accent, fontFamily = CardSans, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val prevCard = if (canMove && card != null) filtered[wrappedIndex(pos, -1, count)] else null
            val layers = buildList {
                leaving?.let { add(DeckLayer(it, LayerRole.STACK, 0)) }
                if (card != null) for (k in 0 until minOf(count, 4)) {
                    val c = filtered[wrappedIndex(pos, k, count)]
                    if (c.id == leaving?.id || any { it.card.id == c.id }) continue
                    if (k > 0 && backward && c.id == prevCard?.id) continue
                    add(DeckLayer(c, LayerRole.STACK, size))
                }
                if (prevCard != null && prevCard.id != leaving?.id && none { it.card.id == prevCard.id })
                    add(DeckLayer(prevCard, LayerRole.PREVIOUS, 0))
            }
            val hasLeaving = leaving != null
            val lift = with(LocalDensity.current) { DeckLift.toPx() }
            val dragEnabled by rememberUpdatedState(canMove && !moving && leaving == null)
            val settleLatest by rememberUpdatedState(settle)
            val queueLatest by rememberUpdatedState(queueSwipe)
            Box(Modifier.weight(1f).fillMaxWidth()
                .drawBehind {
                    drawRect(Brush.radialGradient(listOf(glow.value.copy(alpha = .55f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * .45f), radius = size.maxDimension * .62f))
                }
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp)
                .onSizeChanged { deckWidth = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    var dragging = false
                    var queued = false
                    var travel = 0f
                    val release: () -> Unit = {
                        if (press.value > 0f || press.isRunning)
                            scope.launch { press.animateTo(0f, spring(dampingRatio = .8f, stiffness = 520f)) }
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { at ->
                            tracker.resetTracking()
                            travel = 0f
                            dragging = dragEnabled
                            queued = !dragging
                            if (dragging) {
                                grip = if (at.y < size.height * .55f) 1f else -1f
                                scope.launch { press.animateTo(1f, spring(dampingRatio = .8f, stiffness = 700f)) }
                            }
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().x
                            if (dragging) settleLatest(velocity) else if (queued) queueLatest(travel, velocity)
                            release()
                            dragging = false; queued = false
                        },
                        onDragCancel = {
                            if (dragging) settleLatest(0f)
                            release()
                            dragging = false; queued = false
                        },
                        onHorizontalDrag = { change, amount ->
                            if (dragging || queued) {
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                travel += amount
                            }
                            if (dragging) scope.launch(start = CoroutineStart.UNDISPATCHED) {
                                offset.snapTo((offset.value + amount).coerceIn(-deckWidth * 1.2f, deckWidth))
                            }
                        },
                    )
                }) {
                layers.forEach { layer ->
                    key(layer.card.id) {
                        val c = layer.card
                        val tilt = if ((c.id.hashCode() and 1) == 0) 1.2f else -1.2f
                        val isPrevious = layer.role == LayerRole.PREVIOUS
                        val layerModifier = Modifier.fillMaxSize().padding(bottom = DeckLift * 3)
                            .zIndex(if (isPrevious) 20f else 10f - layer.slot)
                            .graphicsLayer {
                                val w = deckWidth
                                val o = offset.value
                                transformOrigin = TransformOrigin(.5f, 1f)
                                if (isPrevious) {
                                    val b = (o / w).coerceIn(0f, 1f)
                                    translationX = -w * 1.2f * (1f - b)
                                    rotationZ = -10f * (1f - b)
                                    translationY = -lift * 1.5f * (1f - b)
                                } else if (layer.slot == 0 && (hasLeaving || o < 0f)) {
                                    // Pivots on the edge opposite the finger, as a held card does.
                                    transformOrigin = TransformOrigin(.5f, if (grip > 0f) 1f else 0f)
                                    translationX = o
                                    rotationZ = o / w * 12f * grip
                                    translationY = -abs(o) / w * lift * 1.5f
                                    val s = 1f + .03f * press.value
                                    scaleX = s
                                    scaleY = s
                                } else {
                                    val p = if (hasLeaving || o < 0f) (abs(o) / w).coerceAtMost(1f) else 0f
                                    val b = if (!hasLeaving && o > 0f) (o / w).coerceAtMost(1f) else 0f
                                    val d = (layer.slot - p + b).coerceIn(0f, 3f)
                                    val held = if (layer.slot == 0 && !hasLeaving) .03f * press.value * (1f - b) else 0f
                                    val s = (1f - DeckScaleStep * d) * (1f + held)
                                    scaleX = s
                                    scaleY = s
                                    translationY = lift * d
                                    rotationZ = tilt * d.coerceAtMost(2f)
                                }
                            }
                        // The previous card stays fully built (equation WebViews included) so a
                        // swipe back never has to create it mid-gesture.
                        if (isPrevious || layer.slot <= 2 || c.id == prevCard?.id) {
                            val active = !isPrevious && c.id == card?.id
                            StudyCard(c, flipped = c.id in flipped, active = active,
                                position = "${filtered.indexOfFirst { it.id == c.id } + 1} / $count",
                                onFlip = flip,
                                onOpenNote = if (active && onOpenNote != null) ({ onOpenNote(c) }) else null,
                                modifier = layerModifier)
                        } else DeckBackCard(c, layerModifier)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            ReviewRatings(shown, enabled = card != null && !reviewing && !moving && leaving == null, onRating = rate)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(58.dp),
                verticalAlignment = Alignment.CenterVertically) {
                NavPill("\u2039  Previous", canMove, StudyInk, Modifier.weight(1f).testTag("flashcard_previous")) { previous(0f) }
                Spacer(Modifier.width(12.dp))
                val flipTurn by animateFloatAsState(if (cardFlipped) 180f else 0f,
                    spring(dampingRatio = .8f, stiffness = 190f), label = "flip icon")
                Box(Modifier.size(56.dp).shadow(10.dp, CircleShape, ambientColor = accent, spotColor = accent)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(lerp(accent, Color.White, .12f), lerp(accent, Color.Black, .2f))))
                    .clickable(role = Role.Button, onClick = flip)
                    .semantics { contentDescription = if (cardFlipped) "Turn card back" else "Turn card over" }
                    .testTag("flashcard_reveal"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Autorenew, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = flipTurn })
                }
                Spacer(Modifier.width(12.dp))
                NavPill("Next  \u203A", canMove, accent, Modifier.weight(1f).testTag("flashcard_next")) { next(0f) }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * Card options button: three slider tracks whose knobs glide to new stops one after another as
 * the options strip opens, and glide back as it closes. Progress is read only while drawing.
 */
@Composable
private fun OptionsGlyph(open: Boolean, accent: Color, modifier: Modifier = Modifier) {
    val target = if (open) 1f else 0f
    val knobs = listOf(
        animateFloatAsState(target, spring(dampingRatio = .58f, stiffness = 520f), label = "knob 1"),
        animateFloatAsState(target, spring(dampingRatio = .58f, stiffness = 380f), label = "knob 2"),
        animateFloatAsState(target, spring(dampingRatio = .58f, stiffness = 270f), label = "knob 3"),
    )
    val tone = animateFloatAsState(target, tween(240), label = "options tone")
    Canvas(modifier.size(36.dp)) {
        val t = tone.value.coerceIn(0f, 1f)
        drawRoundRect(accent.copy(alpha = .14f * t), cornerRadius = CornerRadius(12.dp.toPx()))
        val ink = lerp(StudyInk, accent, t)
        val stroke = 1.8.dp.toPx()
        val knob = 2.7.dp.toPx()
        val left = size.width * .26f
        val right = size.width * .74f
        val stops = arrayOf(.22f to .78f, .72f to .28f, .38f to .64f)
        for (i in 0..2) {
            val y = size.height * (.32f + .18f * i)
            val (from, to) = stops[i]
            val x = left + (right - left) * (from + (to - from) * knobs[i].value)
            val gap = knob + stroke * .9f
            if (x - gap > left) drawLine(ink.copy(alpha = .55f), Offset(left, y), Offset(x - gap, y), stroke, StrokeCap.Round)
            if (x + gap < right) drawLine(ink.copy(alpha = .55f), Offset(x + gap, y), Offset(right, y), stroke, StrokeCap.Round)
            drawCircle(ink, knob, Offset(x, y))
        }
    }
}

@Composable
private fun NavPill(label: String, enabled: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(46.dp).clip(RoundedCornerShape(23.dp)).background(Color.White.copy(alpha = .92f))
        .border(1.dp, StudyInk.copy(alpha = .08f), RoundedCornerShape(23.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) color else StudyMuted.copy(alpha = .5f), fontFamily = CardSans,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A card further down the deck: only its edge shows, so it carries no content. */
@Composable
private fun DeckBackCard(card: SavedFlashcard, modifier: Modifier) {
    val look = LocalDeckLook.current
    val paint = remember(look, card.colorIndex) { cardPaint(look.style, card.colorIndex, look.accent, large = true) }
    val shape = look.style.corners.cardShape()
    Box(modifier
        .graphicsLayer { shadowElevation = 6f * density; this.shape = shape; clip = true }
        .cardSurface(paint, shape, large = true, seed = card.colorIndex)
        .clearAndSetSemantics { })
}

/**
 * One playing card. The whole card turns over in 3D (no fading, which would push equation
 * WebViews and video surfaces onto a slow offscreen path); the face swaps while the card is
 * edge-on. Only the top card takes taps; a drag or scroll never counts as a tap. Long faces
 * scroll inside the card behind soft feathered edges.
 */
@Composable
private fun StudyCard(card: SavedFlashcard, flipped: Boolean, active: Boolean, position: String,
    onFlip: () -> Unit, onOpenNote: (() -> Unit)?, modifier: Modifier = Modifier) {
    val angle = remember(card.id) { Animatable(if (flipped) 180f else 0f) }
    LaunchedEffect(flipped, active) {
        val target = if (flipped) 180f else 0f
        if (active) angle.animateTo(target, spring(dampingRatio = .8f, stiffness = 190f)) else angle.snapTo(target)
    }
    val showingAnswer by remember { derivedStateOf { angle.value > 90f } }
    val styled = remember(card) { buildAnnotatedString {
        append(card.content.text)
        card.content.runs.forEach { run ->
            if (run.start >= 0 && run.start < run.end && run.end <= card.content.text.length)
                addStyle(run.toSpanStyle(StudyGreen), run.start, run.end)
        }
    } }
    val styledFront = remember(card) { buildAnnotatedString {
        val front = card.frontContent
        append(front?.text ?: card.recallPrompt.ifBlank { "What do you remember from this passage?" })
        front?.runs?.forEach { run ->
            if (run.start >= 0 && run.start < run.end && run.end <= front.text.length)
                addStyle(run.toSpanStyle(StudyGreen), run.start, run.end)
        }
    } }
    val palette = LocalProjectPalette.current
    val look = LocalDeckLook.current
    val style = look.style
    // Dark designs keep note formatting readable: the answer, and a front that keeps its note
    // formatting, sit on the design's light partner.
    val frontPlain = card.frontBlocks == null ||
        (card.frontCardStyle && flashcardFrontFitsCardStyle(card.frontBlocks))
    val faceDesign = if (style.design.dark && (showingAnswer || !frontPlain)) style.design.readable() else style.design
    val paint = remember(style, card.colorIndex, look.accent, faceDesign) {
        cardPaint(style, card.colorIndex, look.accent, large = true, design = faceDesign)
    }
    val cardColor = paint.bottom
    val light = paint.top
    val ink = paint.ink
    val shape = style.corners.cardShape()
    val currentFlip by rememberUpdatedState(onFlip)
    Box(modifier) {
    // The shadow lies flat under the card and narrows as the card turns edge-on. A shadow cast
    // by the turning layer itself is projected by the renderer as a large grey slab.
    Box(Modifier.matchParentSize().graphicsLayer {
        val radians = angle.value * PI / 180.0
        val turn = abs(sin(radians)).toFloat()
        val lift = 1f + .04f * turn
        // Kept just inside the far edge, which perspective draws slightly narrower.
        scaleX = (abs(cos(radians)).toFloat() * lift * (1f - .1f * turn)).coerceAtLeast(.02f)
        scaleY = lift * (1f - .03f * turn)
        translationY = 6f * density * turn
        shadowElevation = (8f + 6f * turn) * density
        this.shape = shape
    })
    Box(Modifier.fillMaxSize()
        .graphicsLayer {
            val a = angle.value
            rotationY = if (a > 90f) a - 180f else a
            cameraDistance = 34f * density
            val turn = abs(sin(a * PI / 180.0)).toFloat()
            scaleX = 1f + .04f * turn
            scaleY = scaleX
            this.shape = shape
            clip = true
        }
        .cardSurface(paint, shape, large = true, seed = card.colorIndex)
        // While turning, the face dims a little and a soft sheen crosses it, as light does on
        // a real card; at rest nothing is drawn.
        .drawWithContent {
            drawContent()
            val a = angle.value
            val tilt = ((if (a > 90f) a - 180f else a) / 90f).coerceIn(-1f, 1f)
            val turn = abs(sin(a * PI / 180.0)).toFloat()
            if (turn > .01f) {
                drawRect(Color.Black.copy(alpha = .09f * turn))
                val x = size.width * (.5f - tilt * .65f)
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = .2f * turn),
                    Color.Transparent), x - size.width * .38f, x + size.width * .38f))
            }
        }
        .then(if (active) Modifier
            // The question side sees the tap before its rich text fields can take it; on the
            // answer side buttons in the content (Copy, play) keep their taps.
            .pointerInput(flipped) {
                val pass = if (flipped) PointerEventPass.Main else PointerEventPass.Initial
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (!change.pressed) {
                            if (!moved && (pass == PointerEventPass.Initial || !change.isConsumed)) currentFlip()
                            break
                        }
                    }
                }
            }
            .semantics { onClick(if (flipped) "Turn card back" else "Turn card over") { currentFlip(); true } }
            .testTag("flashcard_study_card")
        else Modifier.clearAndSetSemantics { })) {
        val faceScroll = remember(card.id, showingAnswer) { ScrollState(0) }
        val topFeather by animateFloatAsState(if (faceScroll.canScrollBackward) 1f else 0f,
            tween(180), label = "top face feather")
        val bottomFeather by animateFloatAsState(if (faceScroll.canScrollForward) 1f else 0f,
            tween(180), label = "bottom face feather")
        val corner = if (showingAnswer) "A" else "Q"
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val faceHeight = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(faceScroll, enabled = active)
                .then(if (showingAnswer && active) Modifier.testTag("flashcard_answer_scroll") else Modifier)) {
                Column(Modifier.fillMaxWidth().heightIn(min = faceHeight)
                    .padding(start = 24.dp, end = 24.dp, top = 54.dp, bottom = 56.dp),
                    verticalArrangement = if (showingAnswer) Arrangement.Top else Arrangement.Center) {
                    val richFace = if (showingAnswer) card.backBlocks else card.frontBlocks
                    val faceTag = if (!active) Modifier else Modifier.testTag(
                        if (showingAnswer) "flashcard_answer" else "flashcard_question")
                    val cardStyled = !showingAnswer && richFace != null && card.frontCardStyle &&
                        flashcardFrontFitsCardStyle(richFace)
                    val frontScale = style.frontScale()
                    if (cardStyled && richFace != null) CardStyleFront(flashcardFrontText(richFace), ink, faceTag,
                        scale = frontScale, family = style.typeface.family(), weight = style.typeface.weight,
                        centered = style.centered)
                    else if (richFace != null) NoteDocumentPreview(richFace, palette, faceTag)
                    else if (showingAnswer) SelectionContainer {
                        Text(styled, color = ink, fontSize = 19.sp, lineHeight = 28.sp,
                            fontFamily = CardSans, fontWeight = FontWeight.Medium, modifier = faceTag)
                    } else Text(styledFront, color = ink, fontSize = (26f * frontScale).sp,
                        lineHeight = (33f * frontScale).sp, fontWeight = style.typeface.weight,
                        fontFamily = style.typeface.family(),
                        textAlign = if (style.centered) TextAlign.Center else null,
                        modifier = if (style.centered) faceTag.fillMaxWidth() else faceTag)
                }
            }
            Box(Modifier.fillMaxWidth().height(64.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(light.copy(alpha = topFeather),
                    light.copy(alpha = topFeather * .85f), Color.Transparent))))
            if (bottomFeather > 0f) Box(Modifier.fillMaxWidth().height(64.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent,
                    cardColor.copy(alpha = bottomFeather * .9f), cardColor.copy(alpha = bottomFeather)))))
            Row(Modifier.fillMaxWidth().align(Alignment.TopStart).padding(start = 20.dp, end = 20.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(corner, color = ink.copy(alpha = .55f), fontFamily = CardSerif, fontWeight = FontWeight.Medium,
                    fontSize = 22.sp, lineHeight = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (showingAnswer) "ANSWER" else "QUESTION", color = ink.copy(alpha = .5f),
                    fontFamily = CardSans, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.6.sp)
                Spacer(Modifier.weight(1f))
                Text(position, color = ink.copy(alpha = .45f), fontFamily = CardSerif, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(start = 14.dp, end = 20.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (onOpenNote != null && card.noteTitle.isNotBlank()) {
                    Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .5f))
                        .clickable(role = Role.Button, onClickLabel = "Open source note", onClick = onOpenNote)
                        .testTag("flashcard_open_note")
                        .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null,
                            tint = ink.copy(alpha = .6f), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(card.noteTitle, color = ink.copy(alpha = .7f), fontFamily = CardSans,
                            fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(corner, color = ink.copy(alpha = .22f), fontFamily = CardSerif, fontWeight = FontWeight.Medium,
                    fontSize = 22.sp, lineHeight = 22.sp, modifier = Modifier.graphicsLayer { rotationZ = 180f })
            }
        }
    }
    }
}

/** Interval a rating gives [card], matching [FlashcardStore.review]. */
internal fun ratingInterval(card: SavedFlashcard, rating: FlashcardRating, long: Boolean): String {
    val fresh = card.reviewCount == 0 || card.intervalDays == 0
    fun days(n: Int) = if (long) (if (n == 1) "1 day" else "$n days") else "${n}d"
    return when (rating) {
        FlashcardRating.AGAIN -> if (long) "10 minutes" else "10m"
        FlashcardRating.HARD -> if (card.reviewCount == 0 || card.intervalDays <= 1) (if (long) "12 hours" else "12h")
            else days((card.intervalDays / 2).coerceAtLeast(1))
        FlashcardRating.GOOD -> days(if (fresh) 1 else (card.intervalDays * 2).coerceAtMost(365))
        FlashcardRating.EASY -> days(if (fresh) 3 else (card.intervalDays * 3).coerceAtMost(365))
    }
}

private val RatingSpecs = listOf(
    RatingSpec(FlashcardRating.AGAIN, "Again", Color(0xFFFFE8EA), Color(0xFFD9344B)),
    RatingSpec(FlashcardRating.HARD, "Hard", Color(0xFFFFF1DC), Color(0xFFD98200)),
    RatingSpec(FlashcardRating.GOOD, "Good", Color(0xFFE6F0FF), Color(0xFF1E63C8)),
    RatingSpec(FlashcardRating.EASY, "Easy", Color(0xFFE2F5E6), Color(0xFF138A3E)),
)

/** Compact one-line ratings: mark, word and next interval side by side. */
@Composable
private fun ReviewRatings(card: SavedFlashcard, enabled: Boolean, onRating: (FlashcardRating) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Narrow phones keep the word and interval on one line by dropping the small mark.
        val showMark = maxWidth >= 330.dp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RatingSpecs.forEach { spec ->
                RatingButton(spec, card, enabled, showMark, Modifier.weight(1f)) { onRating(spec.rating) }
            }
        }
    }
}

/**
 * One rating button. It squeezes under the finger, springs back with a small overshoot on
 * release while a wash of its colour spreads from the touch point, the outline brightens and the
 * mark nods. All motion is read inside layers and draw calls, so a tap never recomposes the row.
 */
@Composable
private fun RatingButton(spec: RatingSpec, card: SavedFlashcard, enabled: Boolean, showMark: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val source = remember { MutableInteractionSource() }
    val squeeze = remember { Animatable(1f) }
    val wash = remember { Animatable(0f) }
    val origin = remember { floatArrayOf(Float.NaN, Float.NaN) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    origin[0] = interaction.pressPosition.x
                    origin[1] = interaction.pressPosition.y
                    scope.launch { squeeze.animateTo(.92f, spring(dampingRatio = 1f, stiffness = 1500f)) }
                }
                is PressInteraction.Release -> {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scope.launch {
                        squeeze.animateTo(1f, spring(dampingRatio = .36f, stiffness = 650f), initialVelocity = 3f)
                    }
                    scope.launch {
                        wash.snapTo(0f)
                        wash.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
                    }
                }
                is PressInteraction.Cancel ->
                    scope.launch { squeeze.animateTo(1f, spring(dampingRatio = .7f, stiffness = 650f)) }
            }
        }
    }
    Row(modifier.height(42.dp)
        .graphicsLayer { scaleX = squeeze.value; scaleY = squeeze.value }
        .clip(shape)
        .background(spec.background)
        .drawBehind {
            val w = wash.value
            if (w > 0f && w < 1f) {
                val center = if (origin[0].isNaN()) Offset(size.width / 2f, size.height / 2f)
                    else Offset(origin[0], origin[1])
                drawCircle(spec.foreground.copy(alpha = .26f * (1f - w)),
                    radius = size.maxDimension * (.15f + 1.05f * w), center = center)
            }
        }
        .drawWithContent {
            drawContent()
            val w = wash.value
            val glow = if (w > 0f && w < 1f) (1f - w) else 0f
            val stroke = (1f + 1.2f * glow) * density
            drawRoundRect(spec.foreground.copy(alpha = .18f + .5f * glow),
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(14.dp.toPx() - stroke / 2f), style = Stroke(stroke))
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button,
            onClick = onClick)
        .semantics {
            contentDescription = "${spec.label}, next review in ${ratingInterval(card, spec.rating, long = true)}"
        }
        .testTag("flashcard_rate_${spec.rating.name.lowercase()}")
        .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (showMark) {
            Box(Modifier.graphicsLayer {
                val w = wash.value
                val nod = if (w > 0f && w < 1f) sin(w * PI).toFloat() else 0f
                scaleX = 1f + .3f * nod
                scaleY = 1f + .3f * nod
                rotationZ = -12f * nod
            }) { ReviewRatingMark(spec.rating, spec.foreground) }
            Spacer(Modifier.width(4.dp))
        }
        Text(spec.label, color = if (showMark) StudyInk else spec.foreground, fontFamily = CardSans,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(3.dp))
        Text(ratingInterval(card, spec.rating, long = false), color = spec.foreground.copy(alpha = .7f),
            fontFamily = CardSans, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ReviewRatingMark(rating: FlashcardRating, color: Color) {
    Canvas(Modifier.size(width = if (rating == FlashcardRating.EASY) 16.dp else 13.dp, height = 13.dp)) {
        val stroke = 2.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(color, Offset(size.width * x1, size.height * y1),
                Offset(size.width * x2, size.height * y2), stroke, StrokeCap.Round)
        }
        when (rating) {
            FlashcardRating.AGAIN -> {
                line(.22f, .22f, .78f, .78f)
                line(.78f, .22f, .22f, .78f)
            }
            FlashcardRating.EASY -> {
                line(.08f, .53f, .23f, .73f)
                line(.23f, .73f, .50f, .25f)
                line(.48f, .53f, .63f, .73f)
                line(.63f, .73f, .91f, .25f)
            }
            else -> {
                line(.16f, .53f, .39f, .76f)
                line(.39f, .76f, .84f, .23f)
            }
        }
    }
}

private class PracticeOption(val tag: String, val label: String, val icon: ImageVector,
                             val selected: Boolean = false, val onClick: () -> Unit)

private fun sortIcon(sort: FlashcardSort): ImageVector = when (sort) {
    FlashcardSort.SCHEDULED -> Icons.Outlined.Schedule
    FlashcardSort.WEAKEST -> Icons.AutoMirrored.Outlined.TrendingDown
    FlashcardSort.NEWEST -> Icons.Outlined.NewReleases
    FlashcardSort.STARRED -> Icons.Outlined.StarBorder
    FlashcardSort.SHUFFLED -> Icons.Outlined.Shuffle
}

/**
 * The card options under the three-dot button, made like the app's other tool strips: bordered
 * rounded buttons in groups, scrolling sideways under feathered edges, with Close at the right.
 */
@Composable
private fun PracticeOptionsStrip(groups: List<List<PracticeOption>>, accent: Color, onClose: () -> Unit,
                                 tag: String = "flashcard_options_strip", modifier: Modifier = Modifier) {
    BackHandler(onBack = onClose)
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp).height(52.dp)
        .clip(shape).background(Color.White).border(1.dp, StudyInk.copy(alpha = .08f), shape)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        NoteFeatheredRow(Modifier.weight(1f).fillMaxHeight(), peek = true) {
            groups.filter { it.isNotEmpty() }.forEachIndexed { index, group ->
                if (index > 0) Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(24.dp)
                    .background(StudyInk.copy(alpha = .12f)))
                group.forEachIndexed { i, option ->
                    if (i > 0) Spacer(Modifier.width(7.dp))
                    PracticeOptionChip(option, accent)
                }
            }
        }
        Box(Modifier.padding(end = 4.dp).size(40.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClose)
            .semantics { contentDescription = "Close card options" }
            .testTag("flashcard_options_close"), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, contentDescription = null, tint = StudyMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PracticeOptionChip(option: PracticeOption, accent: Color) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.height(38.dp).clip(shape)
        .background(if (option.selected) accent.copy(alpha = .12f) else Color.White)
        .border(1.dp, if (option.selected) accent.copy(alpha = .4f) else StudyInk.copy(alpha = .1f), shape)
        .clickable(role = Role.Button, onClick = option.onClick)
        .semantics { selected = option.selected }
        .testTag(option.tag)
        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(option.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(option.label, color = if (option.selected) accent else StudyInk, fontFamily = CardSans,
            fontSize = 13.sp, fontWeight = if (option.selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1)
    }
}

private data class RatingSpec(val rating: FlashcardRating, val label: String,
    val background: Color, val foreground: Color)

private val ChromaSteps = intArrayOf(48, 40, 56, 32, 64, 44, 52, 36, 60, 28)
private val BrightSteps = intArrayOf(248, 252, 245, 250, 246, 254, 244)
private const val HUE_SLOTS = 6 * 27

/** Cards that get a shade no other card has; later cards reuse the cycle. */
internal const val UNIQUE_FLASHCARD_SHADES = HUE_SLOTS * 70

/**
 * A distinct pastel for every card, built from whole 8-bit channels so no two of the first
 * [UNIQUE_FLASHCARD_SHADES] cards can round to the same colour: the hue sector fixes which
 * channel is brightest, middle and darkest (always strictly ordered), and the brightest value,
 * the chroma and the middle value then differ for every card. Consecutive cards jump about 136
 * degrees round the hue wheel, so neighbours never look alike, and every channel stays at 180
 * or above so dark text reads on all of them.
 */
internal fun flashcardColor(index: Int): Color {
    val i = index.coerceAtLeast(0) % UNIQUE_FLASHCARD_SHADES
    val slot = (i * 61) % HUE_SLOTS
    val combo = i / HUE_SLOTS
    val chroma = ChromaSteps[combo % ChromaSteps.size]
    val max = BrightSteps[combo / ChromaSteps.size]
    val min = max - chroma
    val step = (slot % 27) * (chroma - 1) / 27
    val rising = min + 1 + step
    val falling = max - 1 - step
    return when (slot / 27) {
        0 -> Color(max, rising, min)
        1 -> Color(falling, max, min)
        2 -> Color(min, max, rising)
        3 -> Color(min, falling, max)
        4 -> Color(rising, min, max)
        else -> Color(max, min, falling)
    }
}
