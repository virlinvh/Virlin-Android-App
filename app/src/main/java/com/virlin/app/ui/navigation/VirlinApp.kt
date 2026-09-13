package com.virlin.app.ui.navigation

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.virlin.app.ui.components.VirlinOrb
import com.virlin.app.ui.orb.VirlinAgentViewModel
import com.virlin.app.ui.orb.VirlinOrbInteractionState
import com.virlin.app.ui.orb.toOrbParameters
import com.virlin.app.ui.screens.*
import com.virlin.app.ui.hierarchy.*
import kotlin.math.roundToInt

/** Test identities for the Agent overlay. */
const val AgentScrimTestTag = "virlin_agent_scrim"

/**
 * Vertical zone above the navigation bar that contains the Orb's approved slot.
 * Previously the Orb was `offset(y = -90.dp)` OUTSIDE the bottomBar's bounds — drawn there,
 * but unreachable by pointer input. The zone has the same 90dp so the Orb's coordinates are
 * identical; the difference is that the slot is now inside its parent's layout bounds.
 */
/** Orb V2: the visible liquid-glass sphere is 52dp (the accessible target is the same node). */
private val OrbSize = 52.dp
private val AgentOrbSlotSize = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirlinApp(agentViewModel: VirlinAgentViewModel = viewModel()) {
    val navController = rememberNavController()
    val controlViewModel: com.virlin.app.ui.agent.control.AgentControlViewModel = viewModel()
    val createViewModel: com.virlin.app.ui.agent.create.AgentCreateViewModel = viewModel()
    val captureViewModel: com.virlin.app.ui.agent.capture.AgentCaptureViewModel = viewModel()
    val commandViewModel: com.virlin.app.ui.agent.command.AgentCommandViewModel = viewModel()
    // Control 1: "open X" / "show X" resolves to a detail surface; the Agent closes and the app navigates. Focus is untouched.
    val commandNavigation by commandViewModel.navigation.collectAsState()
    androidx.compose.runtime.LaunchedEffect(commandNavigation) {
        val dest = commandNavigation ?: return@LaunchedEffect
        commandViewModel.navigated()
        agentViewModel.dismiss()
        navController.navigate(when (dest.kind) {
            com.virlin.app.domain.command.EntityKind.PROJECT -> com.virlin.app.ui.hierarchy.projectDetail(dest.id)
            com.virlin.app.domain.command.EntityKind.WORKSTREAM -> com.virlin.app.ui.hierarchy.workStreamDetail(dest.id)
            com.virlin.app.domain.command.EntityKind.TASK -> com.virlin.app.ui.hierarchy.taskDetail(dest.id)
        })
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "now"
    val orbOnThisRoute = currentRoute in RootDestination.routes
    // Live Inbox count for the bottom-nav badge: the same capture projection the Inbox shows.
    val captures by com.virlin.app.domain.VirlinGraph.repository.captures.collectAsState()
    val inboxCount = captures.count { it.status == com.virlin.app.domain.model.CaptureStatus.INBOX }

    // ---- The one state owner ----
    val orbState by agentViewModel.orbState.collectAsState()
    val workspace by agentViewModel.workspace.collectAsState()
    val composerText by agentViewModel.composerText.collectAsState()
    val clarificationPrompt by agentViewModel.clarificationPrompt.collectAsState()
    val oneShotNonce by agentViewModel.oneShotNonce.collectAsState()

    val reducedMotion = rememberReducedMotion()
    val orbParams = remember(orbState, reducedMotion, oneShotNonce) {
        orbState.toOrbParameters(reducedMotion, oneShotNonce)
    }

    val agentVisible = orbState.isAgentSurfaceVisible
    val agentOpen = agentVisible && orbState != VirlinOrbInteractionState.Closing

    // ---- ONE animation drives scrim, sheet rise and Orb travel, so they are always in sync.
    // It starts the moment state becomes Opening — the Agent rises AS the Orb responds.
    val openProgress = remember { Animatable(0f) }
    LaunchedEffect(agentOpen) {
        openProgress.animateTo(
            targetValue = if (agentOpen) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (agentOpen) 300 else 340,
                easing = FastOutSlowInEasing
            )
        )
    }

    // Root-relative anchors for the single travelling Orb.
    // The Now slot is captured with onPlaced into a plain holder (NOT Compose state) so the
    // root layout below can read it in the SAME placement pass — the Orb is positioned on
    // the very first frame with no recomposition round-trip.
    val nowSlot = remember { SlotHolder() }
    var agentSlot by remember { mutableStateOf<Offset?>(null) }

    // Android Back and gesture dismissal use exactly the same closing path as the X and scrim.
    BackHandler(enabled = agentVisible) { agentViewModel.dismiss() }

    OrbTravelLayout(
        nowSlot = nowSlot,
        agentSlot = { agentSlot },
        progress = { openProgress.value },
        orb = {
            if (orbOnThisRoute) {
                // =============================================================
                // THE ONE LIVING ORB. Placed by OrbTravelLayout above the scrim and sheet,
                // in root coordinates: at its approved Now slot when closed, travelling into
                // the Agent's slot as the sheet rises. Same composable, same liquid, same
                // phase — never recreated, never duplicated.
                // =============================================================
                // The Control workspace has no Orb (Stitch Control UI): the one Orb fades out there and returns on ← / close.
                val orbHidden = agentVisible && workspace.modeChosen
                val orbAlpha by androidx.compose.animation.core.animateFloatAsState(if (orbHidden) 0f else 1f, tween(200), label = "orbAlpha")
                VirlinOrb(
                    modifier = Modifier.graphicsLayer { alpha = orbAlpha },
                    size = OrbSize,
                    params = orbParams,
                    onPress = agentViewModel::onOrbPressed,
                    onRelease = agentViewModel::onOrbReleased,
                    // Inside the open Agent the Orb is identity, not a button.
                    interactive = !agentVisible
                )
            }
        }
    ) {
        // Stitch: the app recedes behind the open Agent with a light blur + dim. The blur is a constant
        // 2dp only while the Agent is visible (never animated per frame; API 31+ RenderEffect, no-op below).
        Box(Modifier.fillMaxSize().then(if (agentVisible) Modifier.blur(2.dp) else Modifier)) {
        Scaffold(
            containerColor = Color(0xFFF8F7F4),
            // Bottom-attached root navigation (Now · Streams · Pulse · Inbox). The bar owns the
            // navigation-bar inset itself; nothing else pads the bottom, so there is no gap.
            bottomBar = { if (orbOnThisRoute) VirlinBottomNav(currentRoute, inboxCount, navController) }
        ) { innerPadding ->
            // Content ends above the bar (innerPadding); the Orb slot is an overlay in the
            // bottom-right of that content area, so it floats above the bar and never over Inbox.
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (orbOnThisRoute) Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 18.dp, bottom = 12.dp)
                    .size(OrbSize)
                    .onPlaced { nowSlot.position = it.positionInRoot() }
            )
            NavHost(
                navController = navController,
                startDestination = "now",
                modifier = Modifier.fillMaxSize()
            ) {
                composable("now") { NowScreen(navController) }
                composable("streams") { StreamsScreen(navController) }
                composable("pulse") { PulseScreen() }
                composable("stream_detail/{id}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id")
                    StreamDetailScreen(id, navController)
                }
                // Root Inbox tab: the SAME capture Inbox (view / organize / archive / convert) as the Agent's CAPTURE area,
                // over the same capture domain — its own ViewModel instance so a selection here never redirects the Agent.
                composable("inbox") { InboxScreen(viewModel(key = "inbox_tab")) }

            // Hierarchy surfaces (Pass 2): Streams -> Project -> WorkStream -> Task (any depth).
            composable(ProjectDetailRoute) { e -> ProjectDetailScreen(e.arguments?.getString("id"), navController) }
            composable(WorkStreamDetailRoute) { e -> WorkStreamDetailScreen(e.arguments?.getString("id"), navController) }
            composable(TaskDetailRoute) { e -> TaskDetailScreen(e.arguments?.getString("id"), navController) }

                // Fullscreen landscape Focus Clock. Not a bottom-navigation destination; it is
                // another presentation of the SAME running FocusSession shown on Now.
                composable(
                    route = FocusClockRoute,
                    enterTransition = {
                        fadeIn(animationSpec = tween(220)) +
                            scaleIn(initialScale = 0.98f, animationSpec = tween(220))
                    },
                    exitTransition = { fadeOut(animationSpec = tween(180)) },
                    popEnterTransition = { fadeIn(animationSpec = tween(180)) },
                    popExitTransition = { fadeOut(animationSpec = tween(180)) }
                ) { FocusClockScreen(navController) }
            }
            }
        }
        }

        // =====================================================================
        // AGENT OVERLAY — in the SAME window and coordinate space as the Orb.
        // (A ModalBottomSheet lives in its own window, which made Orb continuity impossible.)
        // Composed while rising, open, or descending, until the animation has fully settled.
        // =====================================================================
        if (agentVisible || openProgress.value > 0f) {
            // Now subtly recedes. No blur.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(AgentScrimTestTag)
                    .graphicsLayer { alpha = openProgress.value * 0.32f }
                    .background(Color.Black)
                    .then(
                        if (agentVisible) Modifier.pointerInput(Unit) {
                            detectTapGestures { agentViewModel.dismiss() }
                        } else Modifier
                    )
            )

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // ~60% of the screen normally. When the keyboard resizes the window, keep enough
                // height for the pinned composer plus context; content above it scrolls.
                // The entry selector is compact; a chosen workspace gets a taller, adaptive sheet.
                val sheetHeight = maxOf(maxHeight * (if (workspace.modeChosen) 0.86f else 0.68f), 440.dp).coerceAtMost(maxHeight)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(sheetHeight)
                        .graphicsLayer {
                            translationY = (1f - openProgress.value) * sheetHeight.toPx()
                        }
                        .background(if (workspace.modeChosen) Pearl else Color.White, RoundedCornerShape(topStart = if (workspace.modeChosen) 28.dp else 34.dp, topEnd = if (workspace.modeChosen) 28.dp else 34.dp))
                        // Absorb taps on the sheet body so they never reach the scrim/dismiss.
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    AgentShell(
                        state = orbState,
                        workspace = workspace,
                        composerText = composerText,
                        clarificationPrompt = clarificationPrompt,
                        controlContexts = agentViewModel.controlContexts,
                        destinations = agentViewModel.destinations,
                        onModeSelected = agentViewModel::onModeSelected,
                        onControlContextSelected = agentViewModel::onControlContextSelected,
                        onCreateTypeSelected = agentViewModel::onCreateTypeSelected,
                        onCreateDestinationSelected = agentViewModel::onCreateDestinationSelected,
                        onComposerTextChanged = agentViewModel::onComposerTextChanged,
                        onToggleAttachmentMenu = agentViewModel::onAttachmentMenuToggle,
                        onAddAttachment = agentViewModel::onAddDemoAttachment,
                        onRemoveAttachment = agentViewModel::onRemoveAttachment,
                        // CAPTURE is real (Pass 10): in CAPTURE mode only, the composer's SAVE TO INBOX persists
                        // the text verbatim through VirlinActions and clears the field on success. CONTROL /
                        // CREATE keep the non-executing demo pipeline: no free text runs commands.
                        // CONTROL / CREATE (Pass 12): text → TextCommandInterpreter → CommandEngine.resolve →
                        // clarification / confirmation / preview / result in the command panel. Only typed
                        // VirlinCommands reach the engine; a recognised command clears the composer.
                        // "this" = the WorkStream the Control task picker has selected, if any.
                        // Entry step ("Ask anything…"): the SAME deterministic interpreter decides which existing
                        // workspace the sheet becomes (Capture / Create / otherwise Control); the text then follows
                        // that workspace's existing submit path. No new interpretation.
                        onSubmit = {
                            if (workspace.modeChosen && workspace.mode == com.virlin.app.ui.orb.AgentMode.CAPTURE) captureViewModel.save(composerText) { agentViewModel.onComposerTextChanged("") }
                            else {
                                if (!workspace.modeChosen) agentViewModel.onModeSelected(
                                    when ((com.virlin.app.domain.VirlinGraph.interpreter.interpret(composerText) as? com.virlin.app.domain.command.text.TextInterpretation.Parsed)?.command) {
                                        is com.virlin.app.domain.command.VirlinCommand.Capture -> com.virlin.app.ui.orb.AgentMode.CAPTURE
                                        is com.virlin.app.domain.command.VirlinCommand.Create -> com.virlin.app.ui.orb.AgentMode.CREATE
                                        else -> com.virlin.app.ui.orb.AgentMode.CONTROL
                                    }
                                )
                                commandViewModel.context = com.virlin.app.domain.command.CommandContext(selectedStreamId = controlViewModel.selection.value.selectedStreamId, selectedTaskId = controlViewModel.selection.value.selectedTaskId)
                                commandViewModel.submit(composerText) { agentViewModel.onComposerTextChanged("") }
                            }
                        },
                        onClarificationAnswered = agentViewModel::onClarificationAnswered,
                        // Receipt actions are UI-only for now: every action clears the receipt.
                        onReceiptAction = { agentViewModel.onUndoReceipt() },
                        onDismiss = agentViewModel::dismiss,
                        onBackToEntry = agentViewModel::returnToEntry,
                        onOrbSlotPositioned = { agentSlot = it },
                        // CONTROL is real (Pass 8): structured controls over persisted state via VirlinActions.
                        controlContent = { androidx.compose.foundation.layout.Column { com.virlin.app.ui.agent.command.AgentCommandPanel(vm = commandViewModel); com.virlin.app.ui.agent.control.AgentControlArea(vm = controlViewModel, onCommand = { cmd ->
                            // Quick Action without a target → the SAME typed contract the composer uses (clarification in the command panel).
                            commandViewModel.context = com.virlin.app.domain.command.CommandContext(selectedStreamId = controlViewModel.selection.value.expandedItemId ?: controlViewModel.selection.value.selectedStreamId, selectedTaskId = controlViewModel.selection.value.selectedTaskId)
                            commandViewModel.run(cmd)
                        }) } },
                        // CREATE is real (Pass 9): structured Project / WorkStream / Task creation via VirlinActions.
                        createContent = { androidx.compose.foundation.layout.Column { com.virlin.app.ui.agent.command.AgentCommandPanel(vm = commandViewModel); com.virlin.app.ui.agent.create.AgentCreateArea(vm = createViewModel) } },
                        captureContent = { com.virlin.app.ui.agent.capture.AgentCaptureArea(vm = captureViewModel) }
                    )
                }
            }
        }

    }
}

/** Plain (non-state) holder for a layout anchor read within the same placement pass. */
private class SlotHolder {
    var position: Offset? = null
}

/**
 * Root layout that places [content] (the Scaffold and Agent overlay) and then the single
 * [orb] above it — in ONE placement pass. Placing the content fires the Now slot's
 * `onPlaced`, so the Orb can be positioned immediately from that anchor, interpolated
 * toward the Agent slot by [progress]. Reading [progress]/[agentSlot] here is placement-
 * scoped, so animation frames re-place the Orb without re-measuring or recomposing.
 */
@Composable
private fun OrbTravelLayout(
    nowSlot: SlotHolder,
    agentSlot: () -> Offset?,
    progress: () -> Float,
    orb: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Layout(contents = listOf(content, orb)) { (contentMeasurables, orbMeasurables), constraints ->
        val contentPlaceables = contentMeasurables.map { it.measure(constraints) }
        val orbPlaceables = orbMeasurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Content first: this is what populates nowSlot via onPlaced.
            contentPlaceables.forEach { it.place(0, 0) }

            val idle = nowSlot.position ?: return@layout
            val p = progress()
            // The Agent's identity slot is 64dp (shell unchanged); the 52dp Orb V2 lands centred in it.
            val inset = ((AgentOrbSlotSize - OrbSize) / 2).toPx()
            val target = agentSlot()?.let { Offset(it.x + inset, it.y + inset) }
            val pos = if (target != null && p > 0f) {
                Offset(idle.x + (target.x - idle.x) * p, idle.y + (target.y - idle.y) * p)
            } else idle
            orbPlaceables.forEach { it.place(pos.x.roundToInt(), pos.y.roundToInt()) }
        }
    }
}

/**
 * Reduced motion: honour the system animator duration scale (0 = animations off).
 * Read once; the mapper keeps every state legible while suppressing amplitude.
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
}
