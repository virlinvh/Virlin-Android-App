package com.virlin.app.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.virlin.app.R
import com.virlin.app.data.flashcard.FlashcardRating
import com.virlin.app.data.flashcard.FlashcardStore
import com.virlin.app.data.flashcard.SavedFlashcard
import com.virlin.app.domain.VirlinGraph
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI

const val FLASHCARDS_ROUTE = "flashcards/{kind}/{id}"
fun flashcardsRoute(kind: String, id: String): String =
    "flashcards/${android.net.Uri.encode(kind)}/${android.net.Uri.encode(id)}"

private val StudyInk = Color(0xFF17251D)
private val StudyMuted = Color(0xFF69796E)
private val StudyGreen = Color(0xFF087454)
private val StudyLine = Color(0xFFDCE8DC)
private val CardSerif = FontFamily(
    Font(R.font.fraunces_variable, weight = FontWeight.Medium),
    Font(R.font.fraunces_italic_variable, weight = FontWeight.Medium,
        style = FontStyle.Italic),
)
private val CardSans = FontFamily(
    Font(R.font.dm_sans_variable, weight = FontWeight.Medium),
    Font(R.font.dm_sans_variable, weight = FontWeight.SemiBold),
    Font(R.font.dm_sans_variable, weight = FontWeight.Bold),
)

internal enum class FlashcardGroup { DUE, NEW, FUTURE, ALL }
internal enum class FlashcardSort { SCHEDULED, WEAKEST, NEWEST }

internal fun groupedFlashcards(cards: List<SavedFlashcard>, group: FlashcardGroup,
    sort: FlashcardSort, now: Long): List<SavedFlashcard> {
    val selected = cards.filter { card -> when (group) {
        FlashcardGroup.DUE -> card.reviewCount > 0 && card.nextReviewAt <= now
        FlashcardGroup.NEW -> card.reviewCount == 0
        FlashcardGroup.FUTURE -> card.reviewCount > 0 && card.nextReviewAt > now
        FlashcardGroup.ALL -> true
    } }
    return when (sort) {
        FlashcardSort.SCHEDULED -> selected.sortedWith(compareBy<SavedFlashcard> {
            if (it.reviewCount == 0) Long.MIN_VALUE else it.nextReviewAt
        }.thenBy { it.createdAt })
        FlashcardSort.WEAKEST -> selected.sortedWith(compareByDescending<SavedFlashcard> {
            when (it.difficulty) { "HARD" -> 2; "NORMAL" -> 1; else -> 0 }
        }.thenBy { it.nextReviewAt })
        FlashcardSort.NEWEST -> selected.sortedByDescending { it.createdAt }
    }
}

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
    BackHandler { navController.popBackStack() }
    FlashcardStudyScreen(title, cards, error, reviewing, onBack = { navController.popBackStack() },
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
}

@Composable
internal fun FlashcardStudyScreen(title: String, cards: List<SavedFlashcard>, error: String?,
    reviewing: Boolean, onBack: () -> Unit, onStar: (SavedFlashcard) -> Unit = {},
    onReview: (SavedFlashcard, FlashcardRating, () -> Unit) -> Unit) {
    var group by remember { mutableStateOf(FlashcardGroup.DUE) }
    var initialGroupChosen by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(FlashcardSort.SCHEDULED) }
    var sortOpen by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var flippedId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var deckWidth by remember { mutableFloatStateOf(1f) }
    var swiping by remember { mutableStateOf(false) }
    val swipeScope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val dueCount = cards.count { it.reviewCount > 0 && it.nextReviewAt <= now }
    val newCount = cards.count { it.reviewCount == 0 }
    val futureCount = cards.count { it.reviewCount > 0 && it.nextReviewAt > now }
    LaunchedEffect(cards.size) {
        if (!initialGroupChosen && cards.isNotEmpty()) {
            group = if (dueCount > 0) FlashcardGroup.DUE else if (newCount > 0)
                FlashcardGroup.NEW else FlashcardGroup.FUTURE
            initialGroupChosen = true
        }
    }
    val filtered = groupedFlashcards(cards, group, sort, now)
    val safeIndex = index.coerceIn(0, (filtered.size - 1).coerceAtLeast(0))
    val card = filtered.getOrNull(safeIndex)
    fun move(delta: Int) {
        if (swiping || safeIndex + delta !in filtered.indices) return
        swiping = true
        swipeScope.launch {
            val target = if (delta > 0) -deckWidth else deckWidth
            Animatable(dragOffset).animateTo(target, tween(290)) { dragOffset = value }
            index = safeIndex + delta
            flippedId = null
            dragOffset = 0f
            swiping = false
        }
    }
    fun settleSwipe() {
        val delta = if (dragOffset < 0f) 1 else -1
        if (abs(dragOffset) > deckWidth * .22f && safeIndex + delta in filtered.indices) {
            move(delta)
        } else {
            swipeScope.launch {
                Animatable(dragOffset).animateTo(0f, tween(220)) { dragOffset = value }
            }
        }
    }
    fun rateCurrent(rating: FlashcardRating) {
        val activeCard = card ?: return
        if (flippedId != activeCard.id || reviewing) return
        onReview(activeCard, rating) {
            // Reviewed New/Due cards leave their queue; advancing would skip a card.
            if (group == FlashcardGroup.NEW || group == FlashcardGroup.DUE) flippedId = null
            else if (safeIndex + 1 < filtered.size) move(1)
            else flippedId = null
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(group, sort) { index = 0; flippedId = null; dragOffset = 0f }
    Column(Modifier.fillMaxSize().background(Color(0xFFFCFDFB))) {
        Row(Modifier.fillMaxWidth().padding(start = 15.dp, end = 15.dp, top = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, color = StudyInk, fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${group.name.lowercase().replaceFirstChar { it.uppercase() }} Cards  •  " +
                    "${if (filtered.isEmpty()) 0 else safeIndex + 1} of ${filtered.size}",
                    color = StudyMuted, fontSize = 12.sp, maxLines = 1)
            }
            Box {
                IconButton(onClick = { sortOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Sort cards", tint = StudyInk)
                }
                DropdownMenu(sortOpen, onDismissRequest = { sortOpen = false }) {
                    listOf(FlashcardSort.SCHEDULED to "Scheduled", FlashcardSort.WEAKEST to "Weakest first",
                        FlashcardSort.NEWEST to "Newest first").forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { sort = value; sortOpen = false })
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 21.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Triple(FlashcardGroup.NEW, Icons.Outlined.Star, newCount),
                Triple(FlashcardGroup.DUE, Icons.Outlined.CalendarToday, dueCount),
                Triple(FlashcardGroup.FUTURE, Icons.Outlined.AccessTime, futureCount),
                Triple(FlashcardGroup.ALL, Icons.Outlined.Layers, cards.size)).forEach { (value, icon, count) ->
                val selected = group == value
                val label = value.name.lowercase().replaceFirstChar { it.uppercase() }
                Column(Modifier.weight(1f).height(63.dp)
                    .background(if (selected) Color(0xFFDCEFE1) else Color.White,
                        RoundedCornerShape(15.dp))
                    .border(1.dp, if (selected) Color.Transparent else StudyLine,
                        RoundedCornerShape(15.dp))
                    .clickable { group = value; initialGroupChosen = true }
                    .testTag("flashcard_group_${value.name.lowercase()}")
                    .padding(horizontal = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null,
                            tint = if (selected) StudyGreen else StudyInk,
                            modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(label, color = StudyInk, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                    Text("$count", color = StudyMuted, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (error != null) Text(error, color = Color(0xFF9C3B34), fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 23.dp))
        if (card == null) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(25.dp),
                contentAlignment = Alignment.Center) {
                Text(when {
                    cards.isEmpty() -> "No flashcards yet. Select text in a note and tap Flashcard."
                    group == FlashcardGroup.DUE -> "Nothing due right now. Your next review is on its way."
                    group == FlashcardGroup.NEW -> "You've introduced all your new cards."
                    else -> "No cards in this group yet."
                }, color = StudyMuted, textAlign = TextAlign.Center, fontSize = 15.sp)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 29.dp, vertical = 2.dp)
                .onSizeChanged { deckWidth = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(card.id, filtered.size, safeIndex) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            if (!swiping) {
                                val next = dragOffset + amount
                                val allowed = if (next < 0f) safeIndex < filtered.lastIndex
                                    else safeIndex > 0
                                if (allowed) dragOffset = next.coerceIn(-deckWidth, deckWidth)
                            }
                            change.consume()
                        },
                        onDragEnd = { settleSwipe() },
                        onDragCancel = { settleSwipe() },
                    )
                }) {
                val nextColor = filtered.getOrNull(safeIndex + 1)
                    ?.let { flashcardColor(it.colorIndex) } ?: Color(0xFFDCE7EF)
                val followingColor = filtered.getOrNull(safeIndex + 2)
                    ?.let { flashcardColor(it.colorIndex) } ?: Color(0xFFF1DFE1)
                Box(Modifier.fillMaxSize().padding(start = 14.dp, top = 17.dp, bottom = 1.dp)
                    .shadow(10.dp, RoundedCornerShape(16.dp))
                    .background(followingColor, RoundedCornerShape(16.dp)))
                Box(Modifier.fillMaxSize().padding(end = 7.dp, top = 9.dp, bottom = 8.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .background(nextColor, RoundedCornerShape(16.dp)))
                val incoming = filtered.getOrNull(safeIndex + if (dragOffset < 0f) 1 else -1)
                if (incoming != null && abs(dragOffset) > 1f) {
                    val side = if (dragOffset < 0f) 1f else -1f
                    StudyCard(incoming, false, onFlip = {}, onStar = {},
                        modifier = Modifier.fillMaxSize()
                            .padding(start = 4.dp, end = 10.dp, bottom = 14.dp)
                            .graphicsLayer {
                                translationX = side * (deckWidth - abs(dragOffset))
                                rotationZ = side * 2f * (1f - abs(dragOffset) / deckWidth)
                            })
                }
                StudyCard(card, flippedId == card.id,
                    onFlip = { flippedId = if (flippedId == card.id) null else card.id },
                    onStar = { onStar(card) },
                    modifier = Modifier.fillMaxSize()
                        .padding(start = 4.dp, end = 10.dp, bottom = 14.dp)
                        .graphicsLayer {
                            translationX = dragOffset
                            rotationZ = (dragOffset / deckWidth) * 4f
                        })
            }
            Spacer(Modifier.height(20.dp))
            ReviewRatings(card, enabled = flippedId == card.id && !reviewing,
                onRating = ::rateCurrent)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().height(55.dp)
                .padding(horizontal = 29.dp)
                .background(Color.White, RoundedCornerShape(28.dp))
                .border(1.dp, StudyLine, RoundedCornerShape(28.dp))
                .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { move(-1) }, enabled = safeIndex > 0,
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    modifier = Modifier.weight(1f).testTag("flashcard_previous")) {
                    Text("‹  Previous", color = StudyInk, fontSize = 12.sp, maxLines = 1)
                }
                Text("··  Swipe  ··", color = StudyMuted, fontSize = 11.sp,
                    maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton(onClick = { move(1) }, enabled = safeIndex < filtered.lastIndex,
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    modifier = Modifier.weight(1f).testTag("flashcard_next")) {
                    Text("Next  ›", color = StudyGreen, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            Spacer(Modifier.height(17.dp))
        }
    }
}

@Composable
private fun StudyCard(card: SavedFlashcard, flipped: Boolean,
    onFlip: () -> Unit, onStar: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var cardMenuOpen by remember(card.id) { mutableStateOf(false) }
    val styled = remember(card) { buildAnnotatedString {
        append(card.content.text)
        card.content.runs.forEach { run ->
            if (run.start >= 0 && run.start < run.end && run.end <= card.content.text.length)
                addStyle(run.toSpanStyle(StudyGreen), run.start, run.end)
        }
    } }
    val styledFront = remember(card) { buildAnnotatedString {
        val front = card.frontContent
        append(front?.text ?: card.recallPrompt.ifBlank {
            "What do you remember from this passage?"
        })
        front?.runs?.forEach { run ->
            if (run.start >= 0 && run.start < run.end && run.end <= front.text.length)
                addStyle(run.toSpanStyle(StudyGreen), run.start, run.end)
        }
    } }
    val cardColor = flashcardColor(card.colorIndex)
    val flipAngle by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(460, easing = FastOutSlowInEasing),
        label = "flashcard content flip")
    val showingAnswer = flipAngle > 90f
    val faceRotation = if (showingAnswer) flipAngle - 180f else flipAngle
    val faceOpacity = abs(cos(flipAngle * PI / 180.0)).toFloat()
    Column(modifier.shadow(13.dp, RoundedCornerShape(16.dp))
        .clip(RoundedCornerShape(16.dp))
        .background(Brush.verticalGradient(listOf(
            lerp(cardColor, Color.White, .48f),
            lerp(cardColor, Color.White, .23f),
            lerp(cardColor, Color.White, .10f))))
        .border(1.dp, Color.White.copy(alpha = .55f), RoundedCornerShape(16.dp))
        .padding(24.dp).testTag("flashcard_study_card")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("From Notes", color = Color(0xFF66776B), fontSize = 18.sp,
                fontFamily = CardSerif, fontStyle = FontStyle.Italic)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (card.isStarred) "★" else "☆", color = Color(0xFFE69A06),
                    fontSize = 26.sp, modifier = Modifier.clickable(onClick = onStar)
                        .padding(horizontal = 5.dp).testTag("flashcard_star"))
                Box {
                    IconButton(onClick = { cardMenuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Card options",
                            tint = StudyInk, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(cardMenuOpen, onDismissRequest = { cardMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("Copy question") }, onClick = {
                            clipboard.setText(AnnotatedString(card.recallPrompt.ifBlank {
                                "What do you remember from this passage?"
                            }))
                            cardMenuOpen = false
                        })
                        DropdownMenuItem(text = { Text("Copy answer") }, onClick = {
                            clipboard.setText(AnnotatedString(card.content.text))
                            cardMenuOpen = false
                        })
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val answerScroll = remember(card.id, showingAnswer) { ScrollState(0) }
            val topFeather by animateFloatAsState(
                if (showingAnswer && answerScroll.canScrollBackward) 1f else 0f,
                tween(180), label = "top answer feather")
            val bottomFeather by animateFloatAsState(
                if (showingAnswer && answerScroll.canScrollForward) 1f else 0f,
                tween(180), label = "bottom answer feather")
            Box(Modifier.fillMaxSize().graphicsLayer {
                rotationY = faceRotation
                alpha = faceOpacity
                cameraDistance = 24f * density
            }) {
                Column(Modifier.fillMaxSize().verticalScroll(answerScroll)
                    .padding(horizontal = 4.dp)
                    .then(if (showingAnswer) Modifier.testTag("flashcard_answer_scroll")
                        else Modifier),
                    verticalArrangement = if (showingAnswer) Arrangement.Top else Arrangement.Center) {
                    Text(if (showingAnswer) "READ & REMEMBER" else "A MOMENT TO REMEMBER",
                        color = Color(0xFF6B786D), fontSize = 12.sp, letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(22.dp))
                    if (showingAnswer) SelectionContainer {
                        Text(styled, color = StudyInk, fontSize = 20.sp, lineHeight = 29.sp,
                            fontFamily = CardSans, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("flashcard_answer"))
                    } else Text(styledFront, color = StudyInk, fontSize = 31.sp, lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold, fontFamily = CardSans,
                        modifier = Modifier.testTag("flashcard_question"))
                }
                if (topFeather > 0f) Box(Modifier.fillMaxWidth().height(27.dp)
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(
                        cardColor.copy(alpha = topFeather),
                        cardColor.copy(alpha = 0f)))))
                if (bottomFeather > 0f) Box(Modifier.fillMaxWidth().height(35.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(
                        cardColor.copy(alpha = 0f),
                        cardColor.copy(alpha = bottomFeather)))))
            }
        }
        AnimatedVisibility(visible = !flipped,
            enter = fadeIn(tween(220)) + expandVertically(tween(220)),
            exit = fadeOut(tween(220)) + shrinkVertically(tween(220))) {
            Column {
            Text("Recalling is kind of remembering.",
                color = Color(0xFF66776B), fontSize = 12.sp, lineHeight = 16.sp,
                fontFamily = CardSerif, fontStyle = FontStyle.Italic,
                maxLines = 1, softWrap = false,
                modifier = Modifier.testTag("flashcard_recall_quote"))
            Spacer(Modifier.height(15.dp))
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = .61f))
            .border(1.dp, Color(0xFF879B88), RoundedCornerShape(9.dp))
            .clickable(onClick = onFlip).testTag("flashcard_reveal")
            .padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            Text("⟳", color = StudyInk, fontSize = 23.sp)
            Spacer(Modifier.width(12.dp))
            Text(if (flipped) "Turn card back" else "Turn card over", color = StudyInk,
                fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ReviewRatings(card: SavedFlashcard, enabled: Boolean,
    onRating: (FlashcardRating) -> Unit) {
    val goodDays = if (card.reviewCount == 0 || card.intervalDays == 0) 1
        else (card.intervalDays * 2).coerceAtMost(365)
    val easyDays = if (card.reviewCount == 0 || card.intervalDays == 0) 3
        else (card.intervalDays * 3).coerceAtMost(365)
    val hardLabel = if (card.reviewCount == 0 || card.intervalDays <= 1) "12 h"
        else "${(card.intervalDays / 2).coerceAtLeast(1)} d"
    Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        listOf(
            RatingSpec(FlashcardRating.AGAIN, "Again", "10 min", Color(0xFFFFE9EB), Color(0xFFE52F49)),
            RatingSpec(FlashcardRating.HARD, "Hard", hardLabel, Color(0xFFFFF0DA), Color(0xFFE89000)),
            RatingSpec(FlashcardRating.GOOD, "Good", "$goodDays d", Color(0xFFE8F1FF), Color(0xFF1164D5)),
            RatingSpec(FlashcardRating.EASY, "Easy", "$easyDays d", Color(0xFFE5F7E5), Color(0xFF078C35)),
        ).forEach { spec ->
            Column(Modifier.weight(1f).clickable(enabled = enabled) { onRating(spec.rating) }
                .testTag("flashcard_rate_${spec.rating.name.lowercase()}"),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(40.dp)
                    .background(spec.background,
                        RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    ReviewRatingMark(spec.rating, spec.foreground)
                }
                Spacer(Modifier.height(6.dp))
                Text(spec.label, color = StudyInk, fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold)
                Text(spec.interval, color = StudyMuted, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReviewRatingMark(rating: FlashcardRating, color: Color) {
    Canvas(Modifier.size(width = if (rating == FlashcardRating.EASY) 27.dp else 22.dp,
        height = 22.dp)) {
        val stroke = 2.5.dp.toPx()
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

private data class RatingSpec(val rating: FlashcardRating, val label: String,
    val interval: String, val background: Color, val foreground: Color)

/** Stable paper colors matching the study deck, with a distinct shade for each card. */
internal fun flashcardColor(index: Int): Color {
    val papers = intArrayOf(0xDDEEDD, 0xDCE7EF, 0xF1D9CD, 0xF1DFE1, 0xF2E9CC)
    val safe = index.coerceAtLeast(0)
    val cycle = safe / papers.size
    val base = papers[safe % papers.size]
    val red = ((base shr 16) and 0xFF) - (cycle and 0xF)
    val green = ((base shr 8) and 0xFF) - ((cycle shr 4) and 0xF)
    val blue = (base and 0xFF) - ((cycle shr 8) and 0xF)
    return Color(android.graphics.Color.rgb(red, green, blue))
}
