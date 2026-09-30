package com.virlin.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.navigation.NavController
import com.virlin.app.debug.VirlinStartup
import com.virlin.app.domain.StartupReadiness
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.mock.MockData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.virlin.app.domain.model.FocusInvestment
import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.components.ProcessingWave
import com.virlin.app.ui.components.SplitFlapTimer

// Global Visual Tokens
val Pearl = Color(0xFFF8F7F4)
val Charcoal = Color(0xFF162016)
val CharcoalDark = Color(0xFF111711)
val CharcoalMuted = Color(0xFF525B54)
val CharcoalLight = Color(0xFF859088)

val FocusHeroGreen = Color(0xFFDDF4C7)

val NeedsYouYellowBg = Color(0xFFFFFDF4)
val NeedsYouYellowBorder = Color(0xFFFFE29A)

val NeedsYouCoralBg = Color(0xFFFFF7F2)
val NeedsYouCoralBorder = Color(0xFFFFD0BB)

val WorkingContainerBg = Color(0xFFF2F1F8)
val WorkingContainerBorder = Color(0xFFE2DFED)
val WorkingRowBg = Color.White.copy(alpha=0.9f)
val WorkingRowBorder = Color(0xFFE8E6F0)

val MintFreeBg = Color(0xFFE8F6EE)
val MintFreeBorder = Color(0xFFCEEBD9)

/** Calm first-frame shell while Room hydrates — no fake Focus / Needs You from MockData. */
@Composable
private fun NowInitializingShell() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pearl)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Virlin", fontSize = 24.sp, fontWeight = FontWeight.Black, color = Charcoal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF34C759), CircleShape)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text("Preparing your attention…", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted)
            }
        }
    }
}

@Composable
fun NowScreen(navController: NavController, nowViewModel: NowViewModel = viewModel()) {
    VirlinStartup.markOnceNow()
    val readiness by VirlinGraph.startupReadiness.collectAsState()
    // Until Room is READY, do not render MockData demo fixtures as if they were persisted user state.
    if (readiness !is StartupReadiness.Ready) {
        NowInitializingShell()
        return
    }
    val streams by MockData.streams.collectAsState()
    // Hierarchy (Project / WorkStream / active Task) comes from the domain, never from MockData.
    val currentFocus by nowViewModel.currentFocus.collectAsState()
    val pendingCompletion by nowViewModel.pendingWorkStreamCompletion.collectAsState()
    val completedFocus by nowViewModel.completedFocus.collectAsState()
    val attention by nowViewModel.attention.collectAsState()
    val waitingSince by nowViewModel.waitingSince.collectAsState()
    val dueAt by nowViewModel.attentionDueAt.collectAsState()
    val needsYouQueue by nowViewModel.needsYouQueue.collectAsState()          // canonical: index + 1 = rank
    val needsYouDisplay by nowViewModel.needsYouDisplay.collectAsState()      // display order only (Phase 06)
    val needsYouSort by nowViewModel.needsYouSort.collectAsState()
    val projects by nowViewModel.projects.collectAsState()
    val chooser by nowViewModel.chooser.collectAsState()

    val focusStream = streams.find { it.state == StreamState.FOCUS }
    // Needs You order comes from the domain (`NeedsYouOrder`): explicit user rank first, then the
    // item waiting LONGEST first; it depends only on persisted fields, never on the ticking clock,
    // so the list never re-sorts on a tick. Display items unknown to the domain keep their
    // incoming order after the domain-ordered ones (stable).
    // Rank comes from the canonical queue; the ORDER ON SCREEN comes from the display projection.
    val needsYouStreams = streams.filter { it.state == StreamState.NEEDS_YOU }
        .sortedBy { needsYouDisplay.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    val processingStreams = streams.filter { it.state == StreamState.PROCESSING }
    // WORKING FOR YOU (Phase 10) is a domain projection: real actor, work item, stage and check
    // time. ONE clock value feeds every row's countdown — no per-row ticker, no stored countdown.
    val externalWork by nowViewModel.externalWork.collectAsState()
    val externalNow by produceState(com.virlin.app.domain.VirlinGraph.clock.now()) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            value = com.virlin.app.domain.VirlinGraph.clock.now()
        }
    }
    var openExternal by remember { mutableStateOf<String?>(null) }
    openExternal?.let { id ->
        externalWork.firstOrNull { it.id == id }?.let { item ->
            ExternalWorkDetailSheet(item, externalNow, nowViewModel, onDismiss = { openExternal = null })
        } ?: run { openExternal = null }
    }
    val readyStreams = streams.filter { it.state == StreamState.READY }

    // Notification routing (navigation only): body tap → WorkStream Detail; CHECK → the
    // existing "What happened?" chooser for that stream. Consumed once.
    val notificationTarget by com.virlin.app.platform.NotificationNavigation.pending.collectAsState()
    LaunchedEffect(notificationTarget) {
        val t = com.virlin.app.platform.NotificationNavigation.consume() ?: return@LaunchedEffect
        when (t.target) {
            com.virlin.app.platform.NotificationTarget.CHECK_FLOW -> nowViewModel.openCheck(t.streamId)
            com.virlin.app.platform.NotificationTarget.WORKSTREAM_DETAIL -> navController.navigate(com.virlin.app.ui.hierarchy.workStreamDetail(t.streamId))
        }
    }

    // One lightweight chooser at a time (Leave / Hand off / Check outcome / Custom minutes).
    chooser?.let { NowChooserDialog(it, nowViewModel) }

    // COMPLETE with no active Task means ending the whole WorkStream — never silently.
    // Phase 09: after COMPLETE, a quiet continuation. FOCUS NEXT needs intent; DONE FOR NOW just closes.
    completedFocus?.let { done ->
        CompletedFocusDialog(
            completedTitle = done.completedTitle, nextTitle = done.nextTitle,
            onFocusNext = nowViewModel::focusNextAfterCompletion,
            onDone = nowViewModel::dismissCompletedFocus
        )
    }
    pendingCompletion?.let { id ->
        val title = currentFocus?.takeIf { it.streamId == id }?.workStreamTitle
            ?: streams.firstOrNull { it.id == id }?.title ?: "this WorkStream"
        CompleteWorkStreamDialog(title, onConfirm = nowViewModel::confirmCompleteWorkStream, onDismiss = nowViewModel::dismissWorkStreamCompletion)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pearl)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // 1. GREETING + TODAY'S SUMMARY (the old Virlin/progress-pill header was removed)
        // Presentation only — see NowDaySummaryValues.
        NowDaySummary()

        Spacer(modifier = Modifier.height(20.dp))

        // 2. FOCUS HERO CARD
        if (focusStream != null) {
            FocusHeroCard(
                focusStream, navController,
                hierarchy = currentFocus?.takeIf { it.streamId == focusStream.id },
                onLeave = nowViewModel::openLeave, onHandOff = nowViewModel::openHandOff, onComplete = nowViewModel::complete
            )
        } else {
            Text("Your attention is free.", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 3. NEEDS YOU SECTION — `VirlinNeedsYouSection`, fed by the existing projections.
        // Order, ranks, colours-by-rank, timers and actions all still come from the domain; this
        // block only maps them onto the section's display model.
        if (needsYouStreams.isNotEmpty()) {
            // ONE per-second time source for every card's live timer (lifecycle-aware, drift-free).
            // Only the timer texts read it, so the rest of Now never recomposes on a tick.
            val nowTick = rememberSecondTicker()
            // Queue-position selector (Phase 04 policies): one sheet for the section.
            var positionPicker by remember { mutableStateOf<String?>(null) }
            // Which item's control sheet is open, and which tab it opened on. Transient UI state.
            var control by remember { mutableStateOf<Pair<String, NeedsYouControlTab>?>(null) }
            var sortOpen by remember { mutableStateOf(false) }

            VirlinNeedsYouSection(
                tasks = needsYouStreams.map { stream ->
                    val project = com.virlin.app.domain.model.ProjectIdentity.resolve(stream.projectId, projects)
                    val kind = attention[stream.id]
                    val reason = when (kind) {
                        AttentionKind.RETURN_DUE -> "Ready to continue"
                        AttentionKind.RESULT_READY -> "Result ready"
                        else -> "Check due"
                    }
                    // Existing UI state decides what the one capsule shows: the live overdue time
                    // while the item HAS a target to count from, the action word when it has none.
                    val target = dueAt[stream.id] ?: waitingSince[stream.id]
                    NeedsYouTaskUi(
                        id = stream.id,
                        title = stream.subtitle.ifBlank { stream.title },
                        sourceAndContext = stream.title,
                        statusLabel = reason,
                        // `dueAt` is the temporal truth; the card renders `dueAt − now`, nothing else.
                        checkDueAtEpochMillis = (target ?: nowTick.value).toEpochMilli(),
                        showCheckLabel = target == null,
                        checkLabel = when (kind) {
                            AttentionKind.RETURN_DUE -> "RESUME  →"
                            AttentionKind.RESULT_READY -> "FOCUS NOW  →"
                            else -> "CHECK  →"
                        },
                        iconContent = {
                            com.virlin.app.ui.components.ProjectIcon(
                                projectId = project?.id ?: stream.id,
                                name = project?.title ?: stream.title,
                                iconPath = project?.iconPath,
                                iconId = project?.iconId,
                                size = 26.dp,
                                decorative = true
                            )
                        }
                    )
                },
                nowEpochMillis = nowTick.value.toEpochMilli(),
                // Unchanged behaviour: the action opens the unified sheet on its tab.
                onCheck = { id -> control = id to NeedsYouControlTab.CHECK },
                onRank = { id -> control = id to NeedsYouControlTab.PRIORITY },
                onFilterClick = { sortOpen = true },
                // The app's real sort control, with its own dropdown anchor.
                filterContent = {
                    NeedsYouSortControl(
                        mode = needsYouSort, expanded = sortOpen,
                        onExpandedChange = { sortOpen = it },
                        onSelect = nowViewModel::setNeedsYouSort
                    )
                }
            )

            // The unified control sheet (Phase 2): one surface for position and check decisions.
            // It owns no domain logic — every row below calls an existing intent.
            control?.let { (id, initialTab) ->
                val item = needsYouStreams.firstOrNull { it.id == id }
                val rank = needsYouQueue.indexOf(id).let { if (it < 0) null else it + 1 }
                NeedsYouControlSheet(
                    stream = item,
                    project = item?.let { com.virlin.app.domain.model.ProjectIdentity.resolve(it.projectId, projects) },
                    kind = attention[id],
                    position = rank,
                    total = needsYouQueue.size,
                    dueAt = dueAt[id] ?: waitingSince[id],
                    now = nowTick,
                    initialTab = initialTab,
                    // Existing queue path; the sheet stays open so one visit can do both things.
                    onMoveToPosition = { pos -> nowViewModel.reorderNeedsYou(id, pos) },
                    onOpenPriorityPolicy = { control = null; positionPicker = id },
                    onAction = { action ->
                        // Every branch is an EXISTING transition out of CHECK. Actions that take the
                        // item out of Needs You close the sheet; the list updates from the domain.
                        when (action) {
                            is NeedsYouControlAction.FocusNow ->
                                if (attention[id] == AttentionKind.CHECK_DUE) nowViewModel.resultReadyNow(id) else nowViewModel.focus(id)
                            is NeedsYouControlAction.StillRunning -> nowViewModel.stillRunning(id, action.minutes)
                            is NeedsYouControlAction.RemindLater ->
                                if (attention[id] == AttentionKind.RESULT_READY) nowViewModel.resultReadyLater(id, action.minutes)
                                else nowViewModel.deferReturn(id, action.minutes)
                            is NeedsYouControlAction.NotNow -> nowViewModel.markReady(id)
                            is NeedsYouControlAction.Blocked -> nowViewModel.block(id)
                        }
                        control = null
                    },
                    onDismiss = { control = null }
                )
            }
            // Priority editor (Phase 04): still the home of durable policies, opened from the sheet.
            positionPicker?.let { id ->
                val edited = needsYouStreams.firstOrNull { it.id == id }
                NeedsYouPriorityEditor(
                    stream = edited,
                    currentPosition = needsYouQueue.indexOf(id).let { if (it < 0) null else it + 1 },
                    queueSize = needsYouQueue.size,
                    // Read the durable policy off the main thread; null until it arrives.
                    existing = androidx.compose.runtime.produceState<com.virlin.app.domain.attention.PriorityPreference?>(null, id) {
                        value = nowViewModel.priorityPreference(id)
                    }.value,
                    onRemoveSaved = { nowViewModel.clearPriorityPreference(id) },
                    onSave = { pos, scope -> positionPicker = null; nowViewModel.setNeedsYouPriority(id, pos, scope) },
                    onDismiss = { positionPicker = null }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. WORKING FOR YOU SECTION — `VirlinWorkingForYouSection`, fed by the same projection.
        // The wave spans the CURRENT CHECK WINDOW, not the run: its start is the moment this check
        // was scheduled (`updatedAt` for a PROCESSING stream — `startExternalWork`,
        // `startNextExternalStage` and `continueProcessing` all stamp it), falling back to the run
        // start. Scheduling a new check therefore moves the start and the wave begins again.
        if (externalWork.isNotEmpty()) {
            VirlinWorkingForYouSection(
                tasks = externalWork.map { item ->
                    val due = item.checkAt
                    val windowStart = due?.let { deadline ->
                        val started = item.stream.processingStartedAt ?: item.stream.updatedAt
                        maxOf(started, item.stream.updatedAt).coerceAtMost(deadline)
                    }
                    WorkingTaskUi(
                        id = item.id,
                        title = item.actorName,
                        detail = listOfNotNull(
                            item.workItemTitle ?: item.stream.title,
                            item.stageLabel() ?: item.instruction
                        ).joinToString(" · "),
                        checkStartedAtEpochMillis = windowStart?.toEpochMilli(),
                        nextCheckAtEpochMillis = due?.toEpochMilli()
                    )
                },
                // The same one-per-section ticker the countdown already used.
                nowEpochMillis = externalNow.toEpochMilli(),
                onTaskClick = { openExternal = it }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 5. WHEN YOU'RE FREE
        if (readyStreams.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Same tokens as before; the widths are simply bounded so the two labels
                // cannot overlap each other at large font scales.
                Text("When you're free", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(modifier = Modifier.width(8.dp))
                // The same muted grey the Needs You and Working For You labels use.
                Text("Ready to focus", fontSize = 11.sp, color = Color(0xFF657077),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.height(10.dp))
            // Same list, same order, same filter: only the cards' appearance changed. Each card
            // carries the stream's own id, title, subtitle, its recorded estimate (or no pill at
            // all) and the project icon resolved the way every other Now card resolves it.
            VirlinWhenFreeSection(
                items = readyStreams.map { stream ->
                    val project = com.virlin.app.domain.model.ProjectIdentity
                        .resolve(stream.projectId, projects)
                    WhenFreeCardUi(
                        id = stream.id,
                        title = stream.title,
                        detail = stream.subtitle,
                        durationLabel = stream.expectedDurationSec?.let { "~${it / 60} min" },
                        projectIcon = project?.let {
                            { com.virlin.app.ui.components.ProjectIcon(
                                project = it, size = 30.dp, decorative = true) }
                        }
                    )
                },
                onFocus = nowViewModel::focus,
                onOpen = { navController.navigate(com.virlin.app.ui.hierarchy.workStreamDetail(it)) }
            )
        }

        Spacer(modifier = Modifier.height(88.dp))   // clearance for the floating Orb; the bar itself is handled by Scaffold insets
    }
}

@Composable
fun FocusHeroCard(
    stream: WorkStream,
    navController: NavController,
    hierarchy: CurrentFocusProjection? = null,
    onLeave: (String) -> Unit = {},
    onHandOff: (String) -> Unit = {},
    onComplete: (String) -> Unit = {}
) {
    // The card is `VirlinCurrentFocusCard`; this function stays the one place that maps domain
    // state onto it. LEAVE = human attention exit; the right-hand action is HAND OFF only when
    // the domain says the stream is EXTERNAL, else COMPLETE.
    val external = hierarchy?.isExternal == true
    // Cumulative invested (prior closed sessions + the current one). The split-flap stays
    // current-session only; this metric never feeds the flap.
    val totalInvestedSec = FocusInvestment.liveTotalSeconds(
        stream.priorFocusInvestedSec.toLong(),
        stream.focusInvestedSec
    )
    val showInvested = !external && hierarchy?.activeTaskId != null

    VirlinCurrentFocusCard(
        projectName = hierarchy?.projectTitle.orEmpty(),
        headline = hierarchy?.workStreamTitle ?: stream.title,
        taskName = hierarchy?.activeTaskTitle.orEmpty(),
        elapsedTime = "",                                   // the real timer comes through the slot
        investedTime = if (showInvested) FocusInvestment.formatInvested(totalInvestedSec) else "",
        nextAction = (hierarchy?.nextHumanAction ?: stream.nextAction).orEmpty(),
        nextEstimate = "~2 min est.",
        onLeave = { onLeave(stream.id) },
        onComplete = { if (external) onHandOff(stream.id) else onComplete(stream.id) },
        primaryLabel = if (external) "HAND OFF" else "COMPLETE",
        onContextClick = { navController.navigate(com.virlin.app.ui.hierarchy.workStreamDetail(stream.id)) },
        timerContent = {
            // THE existing animated split-flap, unchanged: same FocusSession, same per-digit
            // animation, and the same tap into the fullscreen landscape Focus Clock.
            val timerInteractionSource = remember { MutableInteractionSource() }
            val timerPressed by timerInteractionSource.collectIsPressedAsState()
            val timerPressScale by animateFloatAsState(
                targetValue = if (timerPressed) 0.98f else 1f,
                animationSpec = tween(120),
                label = "focusTimerPress"
            )
            Box(
                modifier = Modifier
                    .scale(timerPressScale)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(
                        interactionSource = timerInteractionSource,
                        indication = null,
                        onClickLabel = "Open fullscreen focus clock"
                    ) {
                        navController.navigate(FocusClockRoute) { launchSingleTop = true }
                    }
            ) {
                // Key by WorkStream id so flap state never leaks across focus identity.
                key(stream.id) {
                    SplitFlapTimer(stream.focusInvestedSec)
                }
            }
        }
    )
}


@Composable
fun ProcessingRow(stream: WorkStream, index: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WorkingRowBg, RoundedCornerShape(12.dp))
            .border(1.dp, WorkingRowBorder, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isBuild = stream.title.contains("Build")
        val tileBg = if (isBuild) Color(0xFFECFDF5) else Color(0xFFF5F3FF)
        val tileBorder = if (isBuild) Color(0xFFD1FAE5) else Color(0xFFEDE9FE)
        val barColor = if (isBuild) Color(0xFF059669) else Color(0xFF7C3AED)

        // Icon Tile
        Row(
            modifier = Modifier
                .size(36.dp)
                .background(tileBg, RoundedCornerShape(12.dp))
                .border(1.dp, tileBorder, RoundedCornerShape(12.dp)),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "proc")
            // 3-capsule wave, exactly 1.35s duration, 220ms stagger
            // 0ms
            val s1 by infiniteTransition.animateFloat(initialValue = 0.7f, targetValue = 0.7f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes { durationMillis = 1350; 0.7f at 0; 1.22f at 675; 0.7f at 1350 },
                    repeatMode = RepeatMode.Restart
                ), label = "")
            val a1 by infiniteTransition.animateFloat(initialValue = 0.28f, targetValue = 0.28f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes { durationMillis = 1350; 0.28f at 0; 1f at 675; 0.28f at 1350 },
                    repeatMode = RepeatMode.Restart
                ), label = "")

            // 220ms
            val s2 by infiniteTransition.animateFloat(initialValue = 0.7f, targetValue = 0.7f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes { durationMillis = 1350; 0.7f at 0; 1.22f at 675; 0.7f at 1350 },
                    repeatMode = RepeatMode.Restart, initialStartOffset = StartOffset(220)
                ), label = "")
            val a2 by infiniteTransition.animateFloat(initialValue = 0.28f, targetValue = 0.28f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes { durationMillis = 1350; 0.28f at 0; 1f at 675; 0.28f at 1350 },
                    repeatMode = RepeatMode.Restart, initialStartOffset = StartOffset(220)
                ), label = "")

            // 440ms
            val s3 by infiniteTransition.animateFloat(initialValue = 0.7f, targetValue = 0.7f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes { durationMillis = 1350; 0.7f at 0; 1.22f at 675; 0.7f at 1350 },
                    repeatMode = RepeatMode.Restart, initialStartOffset = StartOffset(440)
                ), label = "")
            val a3 by infiniteTransition.animateFloat(initialValue = 0.28f, targetValue = 0.28f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes { durationMillis = 1350; 0.28f at 0; 1f at 675; 0.28f at 1350 },
                    repeatMode = RepeatMode.Restart, initialStartOffset = StartOffset(440)
                ), label = "")

            // The capsule heights and orders match the Stitch variations per row
            val c1Height = if (index == 0) 12.dp else if (index == 1) 10.dp else 14.dp
            val c2Height = if (index == 0) 16.dp else if (index == 1) 16.dp else 8.dp
            val c3Height = if (index == 0) 8.dp else if (index == 1) 12.dp else 16.dp

            Box(modifier = Modifier.width(4.dp).height(c1Height).scale(1f, s1).background(barColor.copy(alpha=a1), CircleShape))
            Spacer(modifier = Modifier.width(4.dp))
            Box(modifier = Modifier.width(4.dp).height(c2Height).scale(1f, s2).background(barColor.copy(alpha=a2), CircleShape))
            Spacer(modifier = Modifier.width(4.dp))
            Box(modifier = Modifier.width(4.dp).height(c3Height).scale(1f, s3).background(barColor.copy(alpha=a3), CircleShape))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(stream.title, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stream.subtitle, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            val m = (stream.processingElapsedSec / 60).toString().padStart(2, '0')
            val s = (stream.processingElapsedSec % 60).toString().padStart(2, '0')
            Text("${m}:${s}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal, fontFamily = FontFamily.Monospace)

            if (stream.checkInRemainingSec != null) {
                val checkM = (stream.checkInRemainingSec / 60).toString().padStart(2, '0')
                val checkS = (stream.checkInRemainingSec % 60).toString().padStart(2, '0')
                Row {
                    Text("Check in ", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = CharcoalLight)
                    Text("${checkM}:${checkS}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Charcoal, fontFamily = FontFamily.Monospace)
                }
            } else {
                Text("No check", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted, modifier = Modifier.background(Color(0xFFF1F5F9), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
fun ReadyRecommendationCard(stream: WorkStream, onFocus: (String) -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MintFreeBg, RoundedCornerShape(16.dp))
            .border(1.dp, MintFreeBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📝", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stream.title, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal)
                Spacer(modifier = Modifier.width(8.dp))
                Text("~5 min", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF065F46), modifier = Modifier.background(Color(0xFFD1FAE5), RoundedCornerShape(4.dp)).padding(horizontal=6.dp, vertical=2.dp))
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(stream.subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted, modifier = Modifier.padding(start = 28.dp))
        }

        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(targetValue = if (pressed) 0.95f else 1f, tween(150), label="")

        Row(
            modifier = Modifier
                .scale(scale)
                .background(Charcoal, RoundedCornerShape(12.dp))
                .pointerInput(stream.id) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { onFocus(stream.id) }
                    )
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("FOCUS", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(modifier = Modifier.width(6.dp))
            Text("→", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        }
    }
}

const val FocusContextTag = "focus_context"
const val FocusLeaveTag = "focus_leave"
const val FocusHandOffTag = "focus_hand_off"
const val FocusProjectTag = "focus_project"
const val FocusTaskTag = "focus_task"
const val FocusCompleteTag = "focus_complete"
const val FocusInvestedTag = "focus_invested"
const val CompleteWorkStreamConfirmTag = "complete_workstream_confirm"
const val CompleteWorkStreamCancelTag = "complete_workstream_cancel"

/** Explicit confirmation before a whole WorkStream is completed from the Now card. */
@Composable
fun CompleteWorkStreamDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Pearl, RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Text("Complete $title?", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Charcoal)
            Spacer(modifier = Modifier.height(8.dp))
            Text("This will complete the entire WorkStream.", fontSize = 13.sp, color = Charcoal.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("Cancel", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Charcoal.copy(alpha = 0.7f),
                    modifier = Modifier.testTag(CompleteWorkStreamCancelTag).clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 12.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("COMPLETE WORKSTREAM", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 0.4.sp,
                    modifier = Modifier.background(Charcoal, RoundedCornerShape(50)).testTag(CompleteWorkStreamConfirmTag)
                        .clickable(onClick = onConfirm).padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}
