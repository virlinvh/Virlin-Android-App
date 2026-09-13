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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.navigation.NavController
import com.virlin.app.mock.MockData
import androidx.lifecycle.viewmodel.compose.viewModel
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

@Composable
fun NowScreen(navController: NavController, nowViewModel: NowViewModel = viewModel()) {
    val streams by MockData.streams.collectAsState()
    // Hierarchy (Project / WorkStream / active Task) comes from the domain, never from MockData.
    val currentFocus by nowViewModel.currentFocus.collectAsState()
    val pendingCompletion by nowViewModel.pendingWorkStreamCompletion.collectAsState()
    val attention by nowViewModel.attention.collectAsState()
    val chooser by nowViewModel.chooser.collectAsState()

    val focusStream = streams.find { it.state == StreamState.FOCUS }
    val needsYouStreams = streams.filter { it.state == StreamState.NEEDS_YOU }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                needsYouStreams.forEachIndexed { index, stream ->
                    NeedsYouCard(
                        stream = stream, index = index,
                        kind = attention[stream.id],
                        onFocus = nowViewModel::focus,
                        onCheck = nowViewModel::openCheck,
                        onDefer = { id -> nowViewModel.deferReturn(id, 5) }
                    )
                }
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
            // Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
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
                    SplitFlapTimer(stream.focusInvestedSec)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(Color(0xFF047857).copy(alpha=0.6f), CircleShape))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("FOCUS INVESTED", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = Charcoal.copy(alpha=0.85f))
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

// ── Helper: smoothstep for organic easing ──────────────────────────
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpColor(a: Color, b: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = a.alpha + (b.alpha - a.alpha) * f
    )
}

@Composable
fun NeedsYouCard(
    stream: WorkStream,
    index: Int,
    kind: AttentionKind? = null,
    onFocus: (String) -> Unit = {},
    onCheck: (String) -> Unit = onFocus,
    onDefer: (String) -> Unit = {}
) {
    val isDueNow = index == 0

    // ── Color tokens (unchanged) ────────────────────────────────────
    val baseBg       = if (isDueNow) Color(0xFFFFFDF4) else Color(0xFFFFF7F2)
    val attentionBg  = if (isDueNow) Color(0xFFFEF6C8) else Color(0xFFFFE8DA)

    val restBorder   = if (isDueNow) Color(0xFFFFE29A) else Color(0xFFFFD0BB)
    val peakBorder   = if (isDueNow) Color(0xFFFACC15) else Color(0xFFFB923C)

    val badgeBg      = if (isDueNow) Color(0xFFFEF3C7) else Color(0xFFFFEDD5)
    val badgeFg      = if (isDueNow) Color(0xFF92400E) else Color(0xFF9A3412)
    val badgeText    = if (isDueNow) "DUE NOW" else "1M OVERDUE"
    val badgeBorder  = if (isDueNow) Color(0xFFFDE68A) else Color(0xFFFED7AA)

    val beaconCoreColor  = if (isDueNow) Color(0xFFFFC928) else Color(0xFFFF7A45)
    val beaconRingColor  = if (isDueNow) Color(0xFFFFF8DE) else Color(0xFFFFF1EB)
    val beaconRingBorder = if (isDueNow) Color(0xFFFFAA22) else Color(0xFFFF9A55)
    val beaconOuterColor = if (isDueNow) Color(0xFFFFE278) else Color(0xFFFFC09C)

    // ── Single master progress 0→1 ──────────────────────────────────
    // Due Now:  7000ms cycle,  no offset
    // Overdue:  6800ms cycle,  1500ms phase offset
    val cycleDur = if (isDueNow) 7000 else 6800
    val offsetMs = if (isDueNow) 0 else 1500

    val infiniteTransition = rememberInfiniteTransition(label = "needs_you_$index")
    val attentionProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = cycleDur, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(offsetMs, StartOffsetType.FastForward)
        ),
        label = "attention_progress"
    )

    // ── Derive all visual properties from the single progress ───────
    //
    // Timeline (normalised 0–1):
    //
    //  0.00–0.14  REST
    //  0.14–0.22  beacon arrives
    //  0.17–0.30  border slowly brightens
    //  0.22–0.32  surface slowly warms
    //  0.32–0.50  HOLD (full attention)
    //  0.50–0.62  surface fades first
    //  0.55–0.68  border softens
    //  0.68–1.00  REST
    //
    val p = attentionProgress

    // Surface color: slow single warm pulse
    val surfaceIntensity = when {
        p < 0.22f -> smoothstep(0.22f, 0.14f, p) * 0f           // rest → 0
        p < 0.32f -> smoothstep(0.22f, 0.32f, p)                 // arrive
        p < 0.50f -> 1f                                           // hold
        p < 0.62f -> 1f - smoothstep(0.50f, 0.62f, p)            // depart
        else -> 0f                                                // rest
    }
    val bgColor = lerpColor(baseBg, attentionBg, surfaceIntensity)

    // Border: leads surface slightly, lingers slightly longer
    val borderIntensity = when {
        p < 0.17f -> 0f
        p < 0.30f -> smoothstep(0.17f, 0.30f, p)
        p < 0.50f -> 1f
        p < 0.68f -> 1f - smoothstep(0.55f, 0.68f, p)
        else -> 0f
    }
    val borderColor = lerpColor(restBorder, peakBorder, borderIntensity)
    val borderWidthFloat = 1f + 0.4f * borderIntensity     // 1dp → 1.4dp
    val borderAlpha = 0.65f + 0.35f * borderIntensity       // 0.65 → 1.0

    // Beacon core: leads everything
    val beaconPhase = when {
        p < 0.14f -> 0f
        p < 0.22f -> smoothstep(0.14f, 0.22f, p)
        p < 0.36f -> 1f - smoothstep(0.22f, 0.36f, p)
        else -> 0f
    }
    val maxCoreScale = if (isDueNow) 1.12f else 1.15f
    val beaconCoreScale = 1f + (maxCoreScale - 1f) * beaconPhase

    // Beacon outer ring: expands and fades
    val maxOuterScale = if (isDueNow) 1.45f else 1.50f
    val maxOuterAlpha = if (isDueNow) 0.26f else 0.30f
    val outerPhase = when {
        p < 0.14f -> 0f
        p < 0.38f -> smoothstep(0.14f, 0.38f, p)
        else -> 1f
    }
    val beaconOuterScale = 0.95f + (maxOuterScale - 0.95f) * outerPhase
    val beaconOuterAlpha = when {
        p < 0.14f -> maxOuterAlpha
        p < 0.38f -> maxOuterAlpha * (1f - smoothstep(0.14f, 0.38f, p))
        p < 0.68f -> 0f  // stays invisible during rest
        else -> maxOuterAlpha * smoothstep(0.68f, 1.0f, p)  // reset for next cycle
    }

    // ── Card rendering (layout unchanged) ───────────────────────────
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor, RoundedCornerShape(16.dp))
            .border(borderWidthFloat.dp, borderColor.copy(alpha = borderAlpha), RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Beacon
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            // Outer pulse ring
            if (beaconOuterAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .size((28 * beaconOuterScale).dp)
                        .background(beaconOuterColor.copy(alpha = beaconOuterAlpha), CircleShape)
                )
            }
            // Static ring
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(beaconRingColor, CircleShape)
                    .border(1.dp, beaconRingBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Pulsing core
                Box(
                    modifier = Modifier
                        .size((10 * beaconCoreScale).dp)
                        .background(beaconCoreColor, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Charcoal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stream.subtitle,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Charcoal.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Human return vs external check vs result ready are worded differently on purpose.
            val sub2 = when (kind) {
                AttentionKind.RETURN_DUE -> "Ready to continue"
                AttentionKind.RESULT_READY -> "Result ready"
                AttentionKind.CHECK_DUE -> "Check due"
                null -> if (isDueNow) "Authentication redirect" else "Route structure decision"
            }
            Text(
                text = sub2,
                modifier = Modifier.testTag("needs_you_kind_${stream.id}"),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Normal,
                color = Charcoal.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = badgeText,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = badgeFg,
                modifier = Modifier
                    .background(badgeBg, RoundedCornerShape(12.dp))
                    .border(1.dp, badgeBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            var pressed by remember { mutableStateOf(false) }
            val scale by animateFloatAsState(
                targetValue = if (pressed) 0.96f else 1f,
                animationSpec = tween(140),
                label = "check_scale"
            )

            val primary = when (kind) {
                AttentionKind.RETURN_DUE -> "RESUME"
                AttentionKind.RESULT_READY -> "FOCUS NOW"
                else -> "CHECK"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (kind == AttentionKind.RETURN_DUE || kind == AttentionKind.RESULT_READY) {
                    // Defer the return without changing why it exists.
                    Text("+5m", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Charcoal.copy(alpha = 0.75f),
                        modifier = Modifier.testTag("needs_you_defer_${stream.id}")
                            .clickable(role = Role.Button) { onDefer(stream.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Row(
                    modifier = Modifier
                        .scale(scale)
                        .background(Charcoal, RoundedCornerShape(16.dp))
                        .testTag("needs_you_primary_${stream.id}")
                        .pointerInput(stream.id, kind) {
                            detectTapGestures(
                                onPress = {
                                    pressed = true
                                    tryAwaitRelease()
                                    pressed = false
                                },
                                onTap = { if (kind == AttentionKind.CHECK_DUE || kind == null) onCheck(stream.id) else onFocus(stream.id) }
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("→", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
