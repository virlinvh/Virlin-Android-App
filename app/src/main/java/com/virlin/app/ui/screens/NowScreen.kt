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
    val attention by nowViewModel.attention.collectAsState()
    val waitingSince by nowViewModel.waitingSince.collectAsState()
    val dueAt by nowViewModel.attentionDueAt.collectAsState()
    val needsYouQueue by nowViewModel.needsYouQueue.collectAsState()
    val projects by nowViewModel.projects.collectAsState()
    val chooser by nowViewModel.chooser.collectAsState()

    val focusStream = streams.find { it.state == StreamState.FOCUS }
    // Needs You order comes from the domain (`NeedsYouOrder`): explicit user rank first, then the
    // item waiting LONGEST first; it depends only on persisted fields, never on the ticking clock,
    // so the list never re-sorts on a tick. Display items unknown to the domain keep their
    // incoming order after the domain-ordered ones (stable).
    // Order AND rank come from the domain queue projection; the display list is only looked up by id.
    val needsYouStreams = streams.filter { it.state == StreamState.NEEDS_YOU }
        .sortedBy { needsYouQueue.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    val processingStreams = streams.filter { it.state == StreamState.PROCESSING }
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

        // 1. TOP HEADER
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
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
                            .border(2.dp, Color(0xFFD1FAE5).copy(alpha=0.9f), CircleShape)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text("Good evening, Maya", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted)
            }

            // Progress Pill
            Row(
                modifier = Modifier
                    .background(Color.White.copy(alpha=0.95f), RoundedCornerShape(24.dp))
                    .border(1.dp, Color.Black.copy(alpha=0.05f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { 0.8f },
                        color = Color(0xFF34C759),
                        trackColor = Color(0xFFEAECE8),
                        strokeWidth = 3.dp,
                        modifier = Modifier.fillMaxSize()
                    )
                    Text("8", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("8 advanced", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal)
                    Text("today", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = CharcoalLight)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

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

        // 3. NEEDS YOU SECTION
        if (needsYouStreams.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Needs You", fontSize = 15.sp, fontWeight = FontWeight.Black, color = Charcoal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color(0xFFFEF3C7), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(needsYouStreams.size.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                    }
                }
                Text("Your attention required", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted)
            }
            Spacer(modifier = Modifier.height(10.dp))
            // ONE per-second time source for every card's live timer (lifecycle-aware, drift-free).
            // Only the timer texts read it, so the rest of Now never recomposes on a tick.
            val nowTick = rememberSecondTicker()
            // Queue-position selector (Phase 2): one sheet for the section, opened from a card's badge.
            var positionPicker by remember { mutableStateOf<String?>(null) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                needsYouStreams.forEachIndexed { index, stream ->
                    // Keyed by id so a card keeps its own animation state when it moves in the queue.
                    key(stream.id) {
                        NeedsYouCard(
                            stream = stream, index = index,
                            kind = attention[stream.id],
                            waitingSince = waitingSince[stream.id],
                            dueAt = dueAt[stream.id],
                            now = nowTick,
                            // Identity is resolved through the project (Project.iconPath), never stored on the stream.
                            project = com.virlin.app.domain.model.ProjectIdentity.resolve(stream.projectId, projects),
                            onFocus = nowViewModel::focus,
                            onCheck = nowViewModel::openCheck,
                            onDefer = { id -> nowViewModel.deferReturn(id, 5) },
                            // Effective rank = position in the domain queue (never the display index).
                            position = needsYouQueue.indexOf(stream.id).let { if (it < 0) null else it + 1 },
                            total = needsYouStreams.size,
                            onChangePosition = { id -> positionPicker = id }
                        )
                    }
                }
            }
            // Priority editor (Phase 04): preview state inside the sheet; the queue changes on SAVE only.
            positionPicker?.let { id ->
                val edited = needsYouStreams.firstOrNull { it.id == id }
                NeedsYouPriorityEditor(
                    stream = edited,
                    currentPosition = needsYouQueue.indexOf(id).let { if (it < 0) null else it + 1 },
                    queueSize = needsYouQueue.size,
                    existing = remember(id) { nowViewModel.priorityPreference(id) },
                    onSave = { pos, scope -> positionPicker = null; nowViewModel.setNeedsYouPriority(id, pos, scope) },
                    onDismiss = { positionPicker = null }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 4. WORKING FOR YOU SECTION
        if (processingStreams.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Working For You", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal)
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color(0xFFEDE9FE), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(processingStreams.size.toString(), fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color(0xFF5B21B6))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val infiniteTransition = rememberInfiniteTransition(label = "")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.55f, targetValue = 0.55f,
                        animationSpec = infiniteRepeatable(
                            animation = keyframes {
                                durationMillis = 2800
                                0.55f at 0
                                1.0f at 1400
                                0.55f at 2800
                            },
                            repeatMode = RepeatMode.Restart
                        ), label = ""
                    )
                    Box(modifier = Modifier.size(6.dp).background(Color(0xFF10B981).copy(alpha = alpha), CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Autonomous background", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF047857))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WorkingContainerBg, RoundedCornerShape(16.dp))
                    .border(1.dp, WorkingContainerBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                processingStreams.forEachIndexed { index, stream ->
                    ProcessingRow(stream, index)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 5. WHEN YOU'RE FREE
        if (readyStreams.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("When you're free", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal)
                Text("Next recommended", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF047857))
            }
            Spacer(modifier = Modifier.height(10.dp))
            ReadyRecommendationCard(readyStreams.first(), onFocus = nowViewModel::focus)
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
    // Finalized labels. LEAVE = human attention exit (READY or timed return); the right-hand
    // action is HAND OFF only when the domain says the stream is EXTERNAL, else COMPLETE.
    val external = hierarchy?.isExternal == true
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(FocusHeroGreen, RoundedCornerShape(30.dp))
            .border(1.dp, Color.White.copy(alpha=0.7f), RoundedCornerShape(30.dp))
            .padding(20.dp)
    ) {
        Column {
            // Badges + cumulative invested (live = prior closed sessions + current session).
            // Split-flap stays current-session only; this metric never feeds the flap.
            val totalInvestedSec = FocusInvestment.liveTotalSeconds(
                stream.priorFocusInvestedSec.toLong(),
                stream.focusInvestedSec
            )
            val showInvested = !external && hierarchy?.activeTaskId != null
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.background(Color.Black.copy(alpha=0.15f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "")
                    val alpha by infiniteTransition.animateFloat(initialValue = 0.4f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label="")
                    Box(modifier = Modifier.size(6.dp).background(Color.White.copy(alpha=alpha), CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CURRENT FOCUS", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp, color = Color.White)
                }

                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        modifier = Modifier.background(Color.White.copy(alpha=0.4f), RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha=0.4f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "")
                        val alpha by infiniteTransition.animateFloat(initialValue = 0.4f, targetValue = 0.9f, animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label="")
                        val scale by infiniteTransition.animateFloat(initialValue = 1.0f, targetValue = 1.15f, animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label="")
                        Box(modifier = Modifier.size(8.dp), contentAlignment = Alignment.Center) {
                            Box(modifier = Modifier.size(8.dp).scale(scale).background(Charcoal.copy(alpha=alpha), CircleShape))
                            Box(modifier = Modifier.size(8.dp).background(Charcoal, CircleShape))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("FOCUS ACTIVE", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp, color = Charcoal)
                    }
                    if (showInvested) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            FocusInvestment.formatInvested(totalInvestedSec),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Charcoal.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(FocusInvestedTag)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Project (optional) / WorkStream / deepest active Task — from the domain projection.
            // Rows collapse when absent; no placeholders. Tapping opens the WorkStream Detail.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FocusContextTag)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "Open WorkStream") {
                        navController.navigate(com.virlin.app.ui.hierarchy.workStreamDetail(stream.id))
                    }
            ) {
                hierarchy?.projectTitle?.let {
                    Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, color = Charcoal.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(FocusProjectTag))
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(hierarchy?.workStreamTitle ?: stream.title, fontSize = 31.sp, fontWeight = FontWeight.Black, color = Charcoal, lineHeight = 32.sp)
                hierarchy?.activeTaskTitle?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Charcoal.copy(alpha = 0.8f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(FocusTaskTag))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Timer Centered
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // Tapping the timer opens the fullscreen landscape Focus Clock.
                // Same FocusSession, different presentation — no timer state is created here.
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
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(Color(0xFF047857).copy(alpha=0.6f), CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CURRENT SESSION", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = Charcoal.copy(alpha=0.85f))
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.size(6.dp).background(Color(0xFF047857).copy(alpha=0.6f), CircleShape))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Next Card — WorkStream.nextHumanAction only (never nextTaskCandidate); omitted when absent.
            val nextAction = hierarchy?.nextHumanAction ?: stream.nextAction
            if (nextAction != null) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha=0.45f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha=0.6f), RoundedCornerShape(16.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NEXT", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.background(Charcoal, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(nextAction, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal)
                }
                Text("~2 min est.", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal.copy(alpha=0.8f))
            }

            if (nextAction != null) Spacer(modifier = Modifier.height(16.dp))

            // Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var btn1Pressed by remember { mutableStateOf(false) }
                val scale1 by animateFloatAsState(if(btn1Pressed) 0.97f else 1f, tween(150), label="")
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .scale(scale1)
                        .background(Color.White.copy(alpha=0.7f), RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha=0.75f), RoundedCornerShape(16.dp))
                        .pointerInput(stream.id, external) {
                            detectTapGestures(
                                onPress = {
                                    btn1Pressed = true
                                    tryAwaitRelease()
                                    btn1Pressed = false
                                },
                                onTap = { onLeave(stream.id) }
                            )
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("LEAVE", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal, modifier = Modifier.testTag(FocusLeaveTag))
                }

                var btn2Pressed by remember { mutableStateOf(false) }
                val scale2 by animateFloatAsState(if(btn2Pressed) 0.97f else 1f, tween(150), label="")
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .scale(scale2)
                        .background(Charcoal, RoundedCornerShape(16.dp))
                        .pointerInput(stream.id, external) {
                            detectTapGestures(
                                onPress = {
                                    btn2Pressed = true
                                    tryAwaitRelease()
                                    btn2Pressed = false
                                },
                                onTap = { if (external) onHandOff(stream.id) else onComplete(stream.id) }
                            )
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(if (external) "HAND OFF" else "COMPLETE", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                        modifier = Modifier.testTag(if (external) FocusHandOffTag else FocusCompleteTag))
                }
            }
        }
    }
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
