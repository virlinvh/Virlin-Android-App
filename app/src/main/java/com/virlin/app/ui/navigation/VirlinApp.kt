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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.virlin.app.ui.components.VirlinOrb
import com.virlin.app.ui.note.TextNoteEditorScreen
import com.virlin.app.ui.note.textNoteRoute
import com.virlin.app.ui.prompt.PromptEditorScreen
import com.virlin.app.ui.prompt.promptEditorRoute
import com.virlin.app.ui.link.LinkEditorScreen
import com.virlin.app.ui.link.linkEditorRoute
import com.virlin.app.ui.file.FileViewerScreen
import com.virlin.app.ui.file.fileViewerRoute
import com.virlin.app.ui.voice.VoiceEditorScreen
import com.virlin.app.ui.voice.voiceEditorRoute
import com.virlin.app.ui.orb.VirlinAgentViewModel
import com.virlin.app.ui.orb.VirlinOrbInteractionState
import com.virlin.app.ui.orb.toOrbParameters
import com.virlin.app.ui.screens.*
import com.virlin.app.ui.hierarchy.*
import com.virlin.app.debug.VirlinStartup
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Test identities for the Agent overlay. */
const val AgentScrimTestTag = "virlin_agent_scrim"

/**
 * Vertical zone above the navigation bar that contains the Orb's approved slot.
 * Previously the Orb was `offset(y = -90.dp)` OUTSIDE the bottomBar's bounds — drawn there,
 * but unreachable by pointer input. The zone has the same 90dp so the Orb's coordinates are
 * identical; the difference is that the slot is now inside its parent's layout bounds.
 */
private val OrbAboveNavZone = 90.dp

/** Orb V2: the visible liquid-glass sphere is 52dp (the accessible target is the same node). */
private val OrbSize = 52.dp

/**
 * The vertical band the floating Orb occupies at the bottom of the content area: its own height
 * plus the margin it sits on. A page that puts a control in its own bottom-right corner reserves
 * this much so the two cannot land on top of each other. Derived from the Orb's real values, so
 * moving or resizing the Orb moves the reservation with it.
 */
val OrbReservedSpace = OrbSize + 12.dp
private val AgentOrbSlotSize = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VirlinApp(agentViewModel: VirlinAgentViewModel = viewModel()) {
    VirlinStartup.markOnceVirlinApp()
    val readiness by com.virlin.app.domain.VirlinGraph.startupReadiness.collectAsState()
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
    val noteScope = rememberCoroutineScope()
    val noteRepository = com.virlin.app.domain.VirlinGraph.repository
    val noteTasks by noteRepository.tasks.collectAsState()
    val noteProjects by noteRepository.projects.collectAsState()
    val routeTaskId = when (currentRoute) {
        TaskDetailRoute, com.virlin.app.ui.todo.TaskTodoRoute -> backStackEntry?.arguments?.getString("id")
        com.virlin.app.ui.page.TaskPageRoute -> backStackEntry?.arguments?.getString("taskId")
        else -> null
    }
    val routeProjectId = when (currentRoute) {
        ProjectDetailRoute, ProjectTaskIndexRoute, com.virlin.app.ui.map.ProjectMapRoute ->
            backStackEntry?.arguments?.getString("id")
        else -> routeTaskId?.let { id -> noteTasks.firstOrNull { it.id == id }?.projectId }
    }
    /** All NEW notes use the redesigned owner-scoped workspace. Legacy capture ids stay legacy. */
    val openRedesignedNotes: (String?, String?) -> Unit = { explicitTaskId, explicitProjectId ->
        val taskId = explicitTaskId ?: routeTaskId
        val projectId = explicitProjectId ?: routeProjectId
        when {
            taskId != null -> {
                val title = noteTasks.firstOrNull { it.id == taskId }?.title ?: "Notes"
                noteScope.launch {
                    com.virlin.app.domain.action.TaskPageActions(
                        noteRepository, com.virlin.app.domain.VirlinGraph.clock,
                        com.virlin.app.domain.VirlinGraph.ids
                    ).ensure(taskId, com.virlin.app.domain.model.TaskPageTypeKeys.NOTE,
                        com.virlin.app.domain.model.TaskPageTypeKeys.noteOwner(taskId))
                    navController.navigate(com.virlin.app.ui.notes.notesForTask(taskId, title)) {
                        launchSingleTop = true
                    }
                }
            }
            projectId != null -> {
                val title = noteProjects.firstOrNull { it.id == projectId }?.title?.let { "$it Notes" }
                    ?: "Project Notes"
                navController.navigate(com.virlin.app.ui.notes.notesForProject(projectId, title)) {
                    launchSingleTop = true
                }
            }
            else -> navController.navigate(com.virlin.app.ui.notes.globalNotes()) { launchSingleTop = true }
        }
    }
    /**
     * A project, its workstreams and its tasks are all reached FROM Streams, so as far as the
     * app shell is concerned they are Streams: the footer and the Orb stay, and Streams stays
     * the selected tab. Opening one never selects Pulse and never resets the Streams stack.
     *
     * The capture editors (text note, prompt, link, file, voice) and the fullscreen focus clock
     * are deliberately NOT here: each owns its own bottom actions or is immersive by design, so
     * the shared footer would sit on top of their controls.
     */
    val streamsSubRoute = STREAMS_SUB_ROUTES.any { currentRoute.startsWith(it) }
    // The capture Inbox and the map picker are now reached FROM Apps, so the shell treats them
    // as Apps: the footer and Orb stay and the Apps tab stays selected.
    val appsSubRoute = currentRoute == "inbox" ||
        currentRoute.startsWith("apps_mind_maps")
    // A project's Pulse drilldown is still Pulse.
    val pulseSubRoute = currentRoute.startsWith("pulse_project/")
    // One predicate for both the bar and the Orb: they appear and disappear together, so a
    // screen can never show one without the other.
    val orbOnThisRoute = currentRoute in RootDestination.routes || streamsSubRoute ||
        pulseSubRoute || appsSubRoute
    val barOnThisRoute = orbOnThisRoute
    val barRoute = when {
        streamsSubRoute -> RootDestination.STREAMS.route
        pulseSubRoute -> RootDestination.PULSE.route
        appsSubRoute -> RootDestination.INBOX.route
        else -> currentRoute
    }
    // Live Inbox count for the bottom-nav badge: the same capture projection the Inbox shows.
    // Safe before READY (bootstrap emits emptyList).
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

    // ---- ONE animation drives scrim, sheet rise and the Orb riding the sheet, so they are
    // always in sync. It starts the moment state becomes Opening — the Agent rises AS the Orb responds.
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
    var agentOrbSlotSize by remember { mutableStateOf(AgentOrbSlotSize) }
    // The sheet's laid-out height in px, used to offset the Orb by the sheet's current
    // translation so it stays fixed inside the rising sheet (no root→Agent traversal).
    var sheetHeightPx by remember { mutableFloatStateOf(0f) }

    // Android Back and gesture dismissal use exactly the same closing path as the X and scrim.
    BackHandler(enabled = agentVisible) { agentViewModel.dismiss() }

    val agentOrbDrawSize = minOf(OrbSize, agentOrbSlotSize)

    Box(Modifier.fillMaxSize()) {
    OrbTravelLayout(
        nowSlot = nowSlot,
        agentSlot = { agentSlot },
        agentOrbSlotSize = { agentOrbSlotSize },
        agentOrbDrawSize = { agentOrbDrawSize },
        sheetTravelPx = { sheetHeightPx },
        progress = { openProgress.value },
        orb = {
            if (orbOnThisRoute) {
                // =============================================================
                // THE ONE LIVING ORB. Placed by OrbTravelLayout above the scrim and sheet,
                // in root coordinates: at its approved Now slot when closed; while the Agent
                // surface is opening/open it rides WITH the rising sheet, fixed in the header
                // slot — it never traverses the screen from the root slot. Same composable,
                // same liquid, same phase — never recreated, never duplicated.
                // =============================================================
                // The Control workspace has no Orb (Stitch Control UI): the one Orb fades out there and returns on ← / close.
                val orbHidden = agentVisible && workspace.modeChosen
                val orbAlpha by androidx.compose.animation.core.animateFloatAsState(if (orbHidden) 0f else 1f, tween(200), label = "orbAlpha")
                VirlinOrb(
                    modifier = Modifier.graphicsLayer { alpha = orbAlpha },
                    size = if (agentVisible || openProgress.value > 0f) agentOrbDrawSize else OrbSize,
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
            // The Inbox is a pure-white surface by design; every other route keeps the app's
            // warm off-white, so the container follows the route rather than being tinted for all.
            containerColor = if (currentRoute == "inbox") Color.White else Color(0xFFF8F7F4),
            // Bottom-attached root navigation (Now · Streams · Pulse · Inbox). The bar owns the
            // navigation-bar inset itself; nothing else pads the bottom, so there is no gap.
            bottomBar = { if (barOnThisRoute) VirlinBottomNav(barRoute, inboxCount, navController) }
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
                composable("pulse") { com.virlin.app.ui.pulse.PulseScreen(navController) }
                composable(com.virlin.app.ui.pulse.PulseProjectRoute) { e ->
                    com.virlin.app.ui.pulse.PulseProjectScreen(e.arguments?.getString("id"), navController)
                }
                composable("stream_detail/{id}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id")
                    StreamDetailScreen(id, navController)
                }
                // Root Inbox tab: capture review/manage (organize / archive / convert).
                // Agent CAPTURE is creation-only (five cards + composer); review lives here —
                // own ViewModel instance so a selection here never redirects the Agent.
                composable("apps") {
                    com.virlin.app.ui.apps.VirlinAppsScreen(
                        onOpenInbox = {
                            navController.navigate("inbox") { launchSingleTop = true }
                        },
                        // No "all maps" screen exists; a map belongs to a project, so this
                        // opens the smallest honest thing: pick the project, open ITS map.
                        onOpenMindMaps = {
                            navController.navigate("apps_mind_maps") { launchSingleTop = true }
                        },
                        // Pages is not built. This opens the existing note editor, which is the
                        // nearest real screen — it is not a Pages home and is not called one.
                        onOpenPages = { openRedesignedNotes(null, null) },
                        // Flashcards has no feature behind it, so the tile stays disabled.
                        flashcardsAvailable = false,
                        animateIcons = !reducedMotion,
                    )
                }
                composable(com.virlin.app.ui.apps.MindMapsHomeRoute) {
                    com.virlin.app.ui.apps.MindMapsHomeHost(navController)
                }
                composable(com.virlin.app.ui.apps.MindMapsFolderRoute) { e ->
                    com.virlin.app.ui.apps.MindMapsFolderHost(
                        e.arguments?.getString("id"), navController
                    )
                }
                composable("inbox") {
                    InboxScreen(
                        viewModel(key = "inbox_tab"),
                        onBack = { navController.popBackStack() },
                        onOpenTextNote = { id ->
                            if (id == null) openRedesignedNotes(null, null)
                            else navController.navigate(textNoteRoute(id)) { launchSingleTop = true }
                        },
                        onOpenPrompt = { id ->
                            navController.navigate(promptEditorRoute(id)) { launchSingleTop = true }
                        },
                        onOpenLink = { id ->
                            navController.navigate(linkEditorRoute(id)) { launchSingleTop = true }
                        },
                        onOpenFile = { id ->
                            navController.navigate(fileViewerRoute(id)) { launchSingleTop = true }
                        },
                        onOpenVoice = { id ->
                            navController.navigate(voiceEditorRoute(id)) { launchSingleTop = true }
                        }
                    )
                }

            // Hierarchy surfaces (Pass 2): Streams -> Project -> WorkStream -> Task (any depth).
            composable(ProjectDetailRoute) { e -> ProjectDetailScreen(e.arguments?.getString("id"), navController) }
            composable(WorkStreamDetailRoute) { e ->
                // `path` carries the ancestor task ids to open at, so a deep link and a process
                // restart rebuild the same level from stable ids rather than a live breadcrumb.
                WorkStreamDetailScreen(
                    e.arguments?.getString("id"),
                    navController,
                    initialPath = e.arguments?.getString("path")
                        ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
                )
            }
            composable(com.virlin.app.ui.map.ProjectMapRoute) { e ->
                com.virlin.app.ui.map.ProjectMapScreen(e.arguments?.getString("id"), navController)
            }
            composable(ProjectTaskIndexRoute) { e ->
                ProjectTaskIndexScreen(e.arguments?.getString("id"), navController)
            }
            composable(TaskDetailRoute) { e ->
                TaskHierarchyRedirectScreen(e.arguments?.getString("id"), navController)
            }
            composable(com.virlin.app.ui.page.TaskPageRoute) { e ->
                com.virlin.app.ui.page.TaskPageScreen(e.arguments?.getString("taskId"), navController)
            }
            // A task's Note as its OWN page - not a sheet, not a dialog, not a map node - so it
            // gets the shared footer and Orb and the same Back behaviour as every other page.
            // A task's To-do list as its own page: the task's real `task_steps`, nothing new.
            composable(com.virlin.app.ui.todo.TaskTodoRoute) { e ->
                com.virlin.app.ui.todo.TaskTodoScreen(
                    e.arguments?.getString("id"), navController
                )
            }

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

                // Full-screen Capture Text Note editor (not a bottom-nav destination).
                composable(
                    route = "text_note/{captureId}",
                    arguments = listOf(navArgument("captureId") { type = NavType.StringType })
                ) { entry ->
                    TextNoteEditorScreen(navController, captureId = entry.arguments?.getString("captureId"))
                }

                // Full-screen Capture Prompt editor (not a bottom-nav destination).
                composable("prompt_editor") {
                    PromptEditorScreen(navController, captureId = null)
                }
                composable(
                    route = "prompt_editor/{captureId}",
                    arguments = listOf(navArgument("captureId") { type = NavType.StringType })
                ) { entry ->
                    PromptEditorScreen(navController, captureId = entry.arguments?.getString("captureId"))
                }
                composable(
                    route = "prompt_editor/new/{taskId}",
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType })
                ) { entry ->
                    PromptEditorScreen(
                        navController,
                        captureId = null,
                        initialContext = com.virlin.app.domain.action.CaptureContext(
                            taskId = entry.arguments?.getString("taskId")
                        )
                    )
                }

                // Full-screen Capture Link editor (not a bottom-nav destination).
                composable(
                    route = com.virlin.app.ui.notes.NOTES_ROUTE,
                    arguments = listOf(
                        navArgument("ownerKey") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    com.virlin.app.ui.notes.NotesRoute(
                        navController = navController,
                        ownerKey = entry.arguments?.getString("ownerKey").orEmpty(),
                        title = entry.arguments?.getString("title").orEmpty(),
                    )
                }
                composable("link_editor") {
                    LinkEditorScreen(navController, captureId = null)
                }
                composable(
                    route = "link_editor/{captureId}",
                    arguments = listOf(navArgument("captureId") { type = NavType.StringType })
                ) { entry ->
                    LinkEditorScreen(navController, captureId = entry.arguments?.getString("captureId"))
                }
                composable(
                    route = "link_editor/new/project/{projectId}",
                    arguments = listOf(navArgument("projectId") { type = NavType.StringType })
                ) { entry ->
                    LinkEditorScreen(
                        navController,
                        captureId = null,
                        initialContext = com.virlin.app.domain.action.CaptureContext(
                            projectId = entry.arguments?.getString("projectId")
                        )
                    )
                }
                composable(
                    route = "link_editor/new/task/{taskId}",
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType })
                ) { entry ->
                    LinkEditorScreen(
                        navController,
                        captureId = null,
                        initialContext = com.virlin.app.domain.action.CaptureContext(
                            taskId = entry.arguments?.getString("taskId")
                        )
                    )
                }

                // Full-screen Capture File / Image viewer (single screen; not a bottom-nav destination).
                composable("file_viewer") {
                    FileViewerScreen(navController, captureId = null)
                }
                composable(
                    route = "file_viewer/{captureId}",
                    arguments = listOf(navArgument("captureId") { type = NavType.StringType })
                ) { entry ->
                    FileViewerScreen(navController, captureId = entry.arguments?.getString("captureId"))
                }

                composable(
                    route = "pdf_workspace/new/task/{taskId}",
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType })
                ) { entry ->
                    com.virlin.app.ui.pdf.PdfWorkspaceScreen(
                        navController = navController,
                        captureId = null,
                        taskId = entry.arguments?.getString("taskId")
                    )
                }
                composable(
                    route = "pdf_workspace/capture/{captureId}",
                    arguments = listOf(navArgument("captureId") { type = NavType.StringType })
                ) { entry ->
                    com.virlin.app.ui.pdf.PdfWorkspaceScreen(
                        navController = navController,
                        captureId = entry.arguments?.getString("captureId"),
                        taskId = null
                    )
                }

                // Full-screen Capture Voice editor (single screen; not a bottom-nav destination).
                composable("voice_editor") {
                    VoiceEditorScreen(navController, captureId = null)
                }
                composable(
                    route = "voice_editor/{captureId}",
                    arguments = listOf(navArgument("captureId") { type = NavType.StringType })
                ) { entry ->
                    VoiceEditorScreen(navController, captureId = entry.arguments?.getString("captureId"))
                }
                // Audio v1: a NEW recording owned by an explicit task. Only the task id travels;
                // CaptureActions derives project and WorkStream from that task, so ownership has
                // exactly one source of truth.
                composable(
                    route = com.virlin.app.ui.voice.VoiceEditorTaskRoute,
                    arguments = listOf(navArgument("taskId") { type = NavType.StringType })
                ) { entry ->
                    VoiceEditorScreen(
                        navController,
                        captureId = null,
                        initialContext = com.virlin.app.domain.action.CaptureContext(
                            taskId = entry.arguments?.getString("taskId")
                        )
                    )
                }
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
                // The ENTRY launcher is a compact command surface: it WRAPS its content
                // (Orb · identity · 3 mode cards · composer) and never takes a screen
                // percentage — only a max bound so a short device can still scroll inside it.
                // Capture keeps 0.90 / 520 (5 action cards); Control/Create keep 0.86 / 440.
                val entrySheet = !workspace.modeChosen
                val sheetHeight = when {
                    workspace.mode == com.virlin.app.ui.orb.AgentMode.CAPTURE && !entrySheet ->
                        maxOf(maxHeight * 0.90f, 520.dp).coerceAtMost(maxHeight)
                    else ->
                        maxOf(maxHeight * 0.86f, 440.dp).coerceAtMost(maxHeight)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .then(if (entrySheet) Modifier.heightIn(max = maxHeight) else Modifier.height(sheetHeight))
                        .graphicsLayer {
                            // Content-driven sheets rise by their MEASURED height (sheetHeightPx).
                            translationY = (1f - openProgress.value) *
                                (if (entrySheet) sheetHeightPx else sheetHeight.toPx())
                        }
                        .onSizeChanged { sheetHeightPx = it.height.toFloat() }
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
                                commandViewModel.context = com.virlin.app.domain.command.CommandContext(selectedStreamId = controlViewModel.selection.value.selectedTargetId ?: controlViewModel.selection.value.selectedStreamId, selectedTaskId = controlViewModel.selection.value.selectedTaskId)
                                commandViewModel.submit(composerText) { agentViewModel.onComposerTextChanged("") }
                            }
                        },
                        onClarificationAnswered = agentViewModel::onClarificationAnswered,
                        // Receipt actions are UI-only for now: every action clears the receipt.
                        onReceiptAction = { agentViewModel.onUndoReceipt() },
                        onDismiss = agentViewModel::dismiss,
                        onBackToEntry = agentViewModel::returnToEntry,
                        onOrbSlotPositioned = { agentSlot = it },
                        onEntryOrbSlotSize = { agentOrbSlotSize = it },
                        // CONTROL is real (Pass 8): structured controls over persisted state via VirlinActions.
                        controlContent = { androidx.compose.foundation.layout.Column { com.virlin.app.ui.agent.command.AgentCommandPanel(vm = commandViewModel); com.virlin.app.ui.agent.control.AgentControlArea(vm = controlViewModel, onCommand = { cmd ->
                            // Quick Action without a target → the SAME typed contract the composer uses (clarification in the command panel).
                            commandViewModel.context = com.virlin.app.domain.command.CommandContext(selectedStreamId = controlViewModel.selection.value.selectedTargetId ?: controlViewModel.selection.value.selectedStreamId, selectedTaskId = controlViewModel.selection.value.selectedTaskId)
                            commandViewModel.run(cmd)
                        }) } },
                        // CREATE is real (Pass 9): structured Project / WorkStream / Task creation via VirlinActions.
                        createContent = { androidx.compose.foundation.layout.Column { com.virlin.app.ui.agent.command.AgentCommandPanel(vm = commandViewModel); com.virlin.app.ui.agent.create.AgentCreateArea(vm = createViewModel) } },
                        captureContent = {
                            com.virlin.app.ui.agent.capture.AgentCaptureArea(
                                vm = captureViewModel,
                                onOpenTextNote = { id ->
                                    agentViewModel.dismiss()
                                    if (id == null) openRedesignedNotes(null, null)
                                    else navController.navigate(textNoteRoute(id)) { launchSingleTop = true }
                                },
                                onOpenPrompt = { id ->
                                    agentViewModel.dismiss()
                                    navController.navigate(promptEditorRoute(id)) { launchSingleTop = true }
                                },
                                onOpenLink = { id ->
                                    agentViewModel.dismiss()
                                    navController.navigate(linkEditorRoute(id)) { launchSingleTop = true }
                                },
                                onOpenFile = { id ->
                                    agentViewModel.dismiss()
                                    navController.navigate(fileViewerRoute(id)) { launchSingleTop = true }
                                },
                                onOpenVoice = { id ->
                                    agentViewModel.dismiss()
                                    navController.navigate(voiceEditorRoute(id)) { launchSingleTop = true }
                                }
                            )
                        }
                    )
                }
            }
        }

    }

    // Genuine Room/init failure only — not shown during normal Initializing.
    val err = readiness as? com.virlin.app.domain.StartupReadiness.Error
    if (err != null) {
        StartupErrorOverlay(
            message = err.message,
            onRetry = { com.virlin.app.domain.VirlinGraph.retryStartup() }
        )
    }
    } // Box
}

/** Controlled startup failure — pearl canvas, no black screen. */
@Composable
private fun StartupErrorOverlay(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F7F4))
            .testTag("startup_error"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("Virlin", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFF162016))
            Spacer(Modifier.height(12.dp))
            Text(
                "Unable to load local data",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF525B54)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                fontSize = 13.sp,
                color = Color(0xFF859088),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("startup_retry")
            ) {
                Text("Retry")
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
 * `onPlaced`, so the Orb can be positioned immediately from that anchor. Reading
 * [progress]/[agentSlot]/[sheetTravelPx] here is placement-scoped, so animation frames
 * re-place the Orb without re-measuring or recomposing.
 *
 * Placement rule (2026-09-13, travel removed): the Orb sits at the Now slot when the Agent
 * is closed. While the Agent surface is opening/open it is placed at the Agent header slot
 * OFFSET by the sheet's current translation — i.e. it is fixed inside the rising sheet and
 * enters from below the screen edge with it. The Orb never interpolates between the root
 * slot and the Agent slot, so there is no visible root→Agent traversal.
 */
@Composable
private fun OrbTravelLayout(
    nowSlot: SlotHolder,
    agentSlot: () -> Offset?,
    agentOrbSlotSize: () -> Dp = { AgentOrbSlotSize },
    agentOrbDrawSize: () -> Dp = { OrbSize },
    sheetTravelPx: () -> Float,
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
            // Centre the drawn Orb inside whatever entry slot size the shell reported.
            val inset = ((agentOrbSlotSize().toPx() - agentOrbDrawSize().toPx()) / 2f).coerceAtLeast(0f)
            val slot = agentSlot()?.let { Offset(it.x + inset, it.y + inset) }
            val pos = if (slot != null && p > 0f) {
                // Ride the sheet: same translation the sheet itself has, so the Orb stays
                // fixed relative to it (at p == 1 it rests exactly in the header slot).
                Offset(slot.x, slot.y + (1f - p) * sheetTravelPx())
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

/**
 * Routes that live under Streams: the project pages, the project's task index, a WorkStream and
 * any task level inside it, and a task's own detail. They keep the shared footer and Orb.
 */
private val STREAMS_SUB_ROUTES = listOf(
    "project_detail/",
    "project_map/",
    "project_task_index/",
    "workstream_detail/",
    "task_detail/",
    // The Note page belongs to the Projects branch, so the footer keeps Projects selected.
    "task_todo/",
    "notes/"
)
