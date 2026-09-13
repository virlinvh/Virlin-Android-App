package com.virlin.app.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.model.CaptureType
import com.virlin.app.ui.agent.*
import com.virlin.app.ui.orb.AgentMode
import com.virlin.app.ui.orb.VirlinOrbInteractionState
import com.virlin.app.ui.theme.VirlinColors

/** Stable test identities for the Agent shell (§44). */
const val AgentShellTestTag = "virlin_agent_shell"
const val AgentCloseTestTag = "virlin_agent_close"
const val AgentBackTestTag = "virlin_agent_back"
const val AgentStatusTestTag = "virlin_agent_status"
const val AgentOrbSlotTestTag = "virlin_agent_orb_slot"
fun agentModeTag(mode: AgentMode) = "agent_mode_${mode.name.lowercase()}"
fun createTypeTag(type: CreateType) = "create_type_${type.name.lowercase()}"
fun controlContextTag(id: String) = "control_context_$id"
fun createDestinationTag(id: String) = "create_destination_$id"
const val ClarificationTestTag = "agent_clarification"
const val AgentPinnedRegionTestTag = "agent_pinned_region"

private val Hairline = VirlinColors.TextPrimary.copy(alpha = 0.08f)
private val ScrollEdgeFadeHeight = 28.dp

/**
 * Fades the bottom edge of a scroll viewport toward [background] while more content lies
 * below, so a clipped element reads as "scrollable" rather than "hidden underneath the
 * pinned region". Reads scroll state at draw time only — no recomposition per frame.
 */
private fun Modifier.scrollEdgeFade(scrollState: ScrollState, background: Color): Modifier =
    drawWithContent {
        drawContent()
        if (scrollState.canScrollForward) {
            val fade = ScrollEdgeFadeHeight.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, background),
                    startY = size.height - fade,
                    endY = size.height
                ),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade)
            )
        }
    }

/**
 * The Agent WORKSPACE SHELL — a focused command workspace, not a chat page and not a
 * second dashboard. Content only; the sheet's rise/fall, scrim and the travelling Orb are
 * owned by `VirlinApp` so they share one coordinate space.
 *
 * Two steps in ONE sheet (2026-09-13 entry refinement, Stitch reference):
 *  1. ENTRY (`workspace.modeChosen == false`): handle · close · compact Orb slot ·
 *     "How can I help?" · "Turn your thoughts into action." · Control / Create / Capture cards ·
 *     "Ask anything…" composer.
 *  2. WORKSPACE: ← back · Orb slot · close, then mode icon + title + subtitle, then ONLY that
 *     mode's content; the mode tabs are never shown again. Back returns to ENTRY; × closes the Agent.
 * The bottom regions (clarification / receipt · composer) are shared. All state comes from
 * [com.virlin.app.ui.orb.VirlinAgentViewModel]; the content lambdas are the existing workspaces.
 */
@Composable
fun AgentShell(
    state: VirlinOrbInteractionState,
    workspace: AgentWorkspaceUiState,
    composerText: String,
    clarificationPrompt: String?,
    controlContexts: List<ControlContext>,
    destinations: List<Destination>,
    onModeSelected: (AgentMode) -> Unit,
    onControlContextSelected: (String) -> Unit,
    onCreateTypeSelected: (CreateType) -> Unit,
    onCreateDestinationSelected: (String) -> Unit,
    onComposerTextChanged: (String) -> Unit,
    onToggleAttachmentMenu: () -> Unit,
    onAddAttachment: (CaptureType) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSubmit: () -> Unit,
    onClarificationAnswered: (String) -> Unit,
    onReceiptAction: (String) -> Unit,
    onDismiss: () -> Unit,
    onOrbSlotPositioned: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    /** ← on a workspace: back to the entry selector (the Agent stays open). */
    onBackToEntry: () -> Unit = {},
    /** Real CONTROL content (Pass 8). Null renders the original display-only context strip. */
    controlContent: (@Composable () -> Unit)? = null,
    /** Real CREATE content (Pass 9). Null renders the original display-only create strip. */
    createContent: (@Composable () -> Unit)? = null,
    /** Real CAPTURE content (Pass 10). Null renders the original demo capture tray. */
    captureContent: (@Composable () -> Unit)? = null
) {
    // Clear text focus cleanly as the Agent closes (§33).
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state) {
        if (state == VirlinOrbInteractionState.Closing) focusManager.clearFocus(force = true)
    }

    val mode = workspace.mode
    val entry = !workspace.modeChosen
    val interactive = state.isAgentInteractive
    val selectedContext = controlContexts.firstOrNull { it.id == workspace.selectedControlContextId }
    val selectedDestination = destinations.firstOrNull { it.id == workspace.createDestinationId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(AgentShellTestTag)
    ) {
        // =====================================================================
        // LAYOUT CONTRACT — three regions, each owning its own space:
        //   1. scrollable mode content   (weight 1f: ONLY the remaining viewport)
        //   2. pinned transient region   (clarification / receipt; reserves its height)
        //   3. composer region           (always reachable)
        // The scroll region clips to its bounds and fades its bottom edge while more
        // content is available, so nothing ever appears to sit underneath a pinned surface.
        // =====================================================================
        val scrollState = rememberScrollState()

        // ---------- 1. Scrollable mode content (identity, modes, context)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .scrollEdgeFade(scrollState, if (entry) Color.White else VirlinColors.Background)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(10.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.align(Alignment.Center).width(if (entry) 44.dp else 36.dp).height(if (entry) 6.dp else 4.dp)
                        .background(if (entry) Color(0xFFD6D3D1) else VirlinColors.TextPrimary.copy(alpha = 0.12f), RoundedCornerShape(if (entry) 3.dp else 2.dp))
                )
                if (!entry) Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .testTag(AgentBackTestTag)
                        .clickable(interactionSource = MutableInteractionSource(), indication = null, role = Role.Button, onClick = onBackToEntry)
                        .semantics { contentDescription = "Back to Agent entry" },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = VirlinColors.TextSecondary, modifier = Modifier.size(20.dp)) }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(44.dp)
                        .testTag(AgentCloseTestTag)
                        .clickable(interactionSource = MutableInteractionSource(), indication = null, role = Role.Button, onClick = onDismiss)
                        .semantics { contentDescription = "Close Virlin Agent" },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.Close, contentDescription = null, tint = VirlinColors.TextSecondary, modifier = Modifier.size(18.dp)) }
            }

            // Identity: the compact Orb. Drawn by VirlinApp at this slot — an anchor, never a second Orb.
            // The slot sits at the same place on both steps so the Orb never jumps between them.
            // No "Virlin" / "Ready" text: the status is exposed as a non-motion semantics channel (Rule 8).
            val controlWorkspace = !entry   // Stitch workspaces (Control · Create · Capture): no Orb, centred identity
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!controlWorkspace) Box(
                    modifier = Modifier
                        .size(64.dp)
                        .drawBehind {
                            if (entry) drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color(0xD9D7ECC7), Color(0x8CC7E8B8), Color(0x00D7ECC7)),
                                    center = center, radius = size.minDimension * 0.82f
                                ),
                                radius = size.minDimension * 0.82f
                            )
                        }
                        .testTag(AgentOrbSlotTestTag)
                        .onGloballyPositioned { onOrbSlotPositioned(it.positionInRoot()) }
                )
                Box(Modifier.testTag(AgentStatusTestTag).semantics { contentDescription = state.statusText; stateDescription = state.statusText })
            }

            Spacer(Modifier.height(12.dp))

            if (entry) {
                // Agent entry: "How can I help?" — one card per mode; choosing one transforms the sheet.
                AgentEntryPicker(onModeSelected = onModeSelected, enabled = interactive)
            } else {
            WorkspaceHeader(mode, centered = controlWorkspace)

            Spacer(Modifier.height(if (controlWorkspace) 20.dp else 14.dp))

            when (mode) {
                AgentMode.CONTROL -> if (controlContent != null) controlContent() else ControlContextArea(controlContexts, workspace.selectedControlContextId, onControlContextSelected)
                AgentMode.CREATE -> if (createContent != null) createContent() else CreateContextArea(workspace.createType, destinations, workspace.createDestinationId, onCreateTypeSelected, onCreateDestinationSelected)
                AgentMode.CAPTURE -> if (captureContent != null) captureContent() else CaptureTray(workspace.attachments, onRemove = onRemoveAttachment)
            }
            }

            // End-of-content inset so the last actionable element can always be scrolled fully
            // clear of the edge fade and the pinned composer (Pass 9 correction).
            Spacer(Modifier.height(72.dp))
        }

        // ---------- 2. Pinned transient region. Reserves its own height (the scroll region
        // above shrinks accordingly) and resizes smoothly as a receipt or clarification
        // appears or disappears. Always visible next to the action it relates to — even
        // with the keyboard open. A receipt the user cannot see is not a confirmation.
        val showClarification = state == VirlinOrbInteractionState.Clarification && clarificationPrompt != null
        val receipt = workspace.receipt
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .testTag(AgentPinnedRegionTestTag)
        ) {
            if (showClarification || receipt != null) {
                // Designed boundary between scrolling content and pinned surfaces.
                Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                Spacer(Modifier.height(10.dp))
                if (showClarification) {
                    ClarificationCard(clarificationPrompt!!, onClarificationAnswered,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    if (receipt != null) Spacer(Modifier.height(8.dp))
                }
                receipt?.let {
                    AgentReceipt(it, onAction = onReceiptAction, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }

        // ---------- 3. Composer region.

        // ---------- Universal composer, pinned. Stays reachable with the keyboard open.
        // Entry step: the Stitch "Or just tell me…" pill input; submit picks the workspace (VirlinApp).
        if (entry) EntryComposer(
            text = composerText, enabled = interactive, onTextChanged = onComposerTextChanged, onSubmit = onSubmit,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp)
        ) else if (mode == AgentMode.CAPTURE) EntryComposer(
            // Stitch Capture composer: the same pill; → is the existing SAVE TO INBOX path (raw text, never interpreted).
            text = composerText, enabled = interactive, onTextChanged = onComposerTextChanged, onSubmit = onSubmit,
            placeholder = "What would you like to capture…", label = null, submitTag = CaptureSaveInboxTestTag, submitDescription = "Save to Inbox",
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp)
        ) else UniversalComposer(
            mode = mode,
            text = composerText,
            enabled = interactive,
            attachments = workspace.attachments,
            showAttachmentsInline = mode != AgentMode.CAPTURE,
            contextLine = when (mode) {
                AgentMode.CONTROL -> if (selectedContext != null) "CONTROL · ${selectedContext.title} — ${selectedContext.subtitle}" else "CONTROL"
                AgentMode.CREATE -> if (createContent != null) "CREATE" else listOfNotNull("CREATE", workspace.createType.label.uppercase(), selectedDestination?.name).joinToString(" · ")
                AgentMode.CAPTURE -> "CAPTURE · INBOX"
            },
            menuOpen = workspace.attachmentMenuOpen,
            onTextChanged = onComposerTextChanged,
            onToggleMenu = onToggleAttachmentMenu,
            onAddAttachment = onAddAttachment,
            onRemoveAttachment = onRemoveAttachment,
            onSubmit = onSubmit,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

// ---------------------------------------------------------------- Agent entry ("How can I help?")

const val AgentEntryTestTag = "virlin_agent_entry"

private data class EntryRow(val mode: AgentMode, val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val tint: Color, val badge: Color)

// Stitch entry palette: mint badges for Control / Create, a soft peach badge for Capture.
private val EntryRows = listOf(
    EntryRow(AgentMode.CONTROL, "Control", "Work with what you have", Icons.Rounded.Layers, Color(0xFF006039), Color(0xFFDDF8E8)),
    EntryRow(AgentMode.CREATE, "Create", "Build new structure", Icons.Rounded.Eco, Color(0xFF00875A), Color(0xFFDDF8E8)),
    EntryRow(AgentMode.CAPTURE, "Capture", "Save information", Icons.Rounded.MoveToInbox, Color(0xFF5C4410), Color(0xFFFCEFD7))
)
private val Slate100 = Color(0xFFF1F5F9)
private val Slate400 = Color(0xFF94A3B8)
private val Slate500 = Color(0xFF64748B)
private val Slate900 = Color(0xFF0F172A)

/** Stitch entry composer: label · pill input ("Ask anything…") · round arrow button. Same test tags as the composer. */
@Composable
private fun EntryComposer(
    text: String, enabled: Boolean, onTextChanged: (String) -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "Ask anything…", label: String? = "Or just tell me…", submitTag: String = AgentSubmitTestTag, submitDescription: String = "Send to Virlin"
) {
    val canSubmit = enabled && text.isNotBlank()
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Slate500, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFFF8F9FA), RoundedCornerShape(50))
                .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(50))
                .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = text, onValueChange = onTextChanged, enabled = enabled, singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF1E293B)),
                cursorBrush = SolidColor(VirlinColors.Emerald),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSubmit) onSubmit() }),
                modifier = Modifier.fillMaxWidth().padding(end = 48.dp, top = 8.dp, bottom = 8.dp)
                    .testTag(AgentComposerTestTag).semantics { contentDescription = "Message to Virlin" },
                decorationBox = { inner ->
                    Box { if (text.isEmpty()) Text(placeholder, fontSize = 14.sp, color = Slate400); inner() }
                }
            )
            Box(
                modifier = Modifier.align(Alignment.CenterEnd).size(36.dp)
                    .background(if (canSubmit) VirlinColors.Emerald else Color(0xFFB8BEBA), CircleShape)
                    .testTag(submitTag)
                    .clickable(enabled = canSubmit, role = Role.Button) { onSubmit() }
                    .semantics { contentDescription = submitDescription },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

/** Workspace step header: mode icon · title · subtitle. The mode tabs are never shown here. */
@Composable
private fun WorkspaceHeader(mode: AgentMode, centered: Boolean = false) {
    val r = EntryRows.first { it.mode == mode }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        modifier = Modifier.fillMaxWidth()
            .testTag(agentModeTag(mode))
            .semantics { contentDescription = "${r.title} mode, ${r.subtitle}"; selected = true }
    ) {
        Box(modifier = Modifier.size(36.dp).background(r.badge, CircleShape), contentAlignment = Alignment.Center) {
            Icon(r.icon, contentDescription = null, tint = r.tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(r.title, fontSize = 17.sp, fontWeight = FontWeight.Black, color = VirlinColors.TextPrimary)
            Text(r.subtitle, fontSize = 12.sp, color = VirlinColors.TextSecondary)
        }
    }
}

/**
 * Compact entry sheet content: heading + one row per mode (icon · title · subtitle · chevron).
 * Rows carry the SAME `agentModeTag(mode)` as the mode tabs and call the SAME [onModeSelected],
 * so this is only a first step into the existing workspaces — never a second Agent.
 */
@Composable
private fun AgentEntryPicker(onModeSelected: (AgentMode) -> Unit, enabled: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().testTag(AgentEntryTestTag), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("How can I help?", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Slate900, letterSpacing = (-0.3).sp,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text("Turn your thoughts into action.", fontSize = 13.5.sp, color = Slate500,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        EntryRows.forEach { r ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .border(1.dp, Slate100, RoundedCornerShape(16.dp))
                    .testTag(agentModeTag(r.mode))
                    .clickable(enabled = enabled, role = Role.Button) { onModeSelected(r.mode) }
                    .semantics { contentDescription = "${r.title}, ${r.subtitle}" }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Box(modifier = Modifier.size(44.dp).background(r.badge, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(r.icon, contentDescription = null, tint = r.tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Slate900)
                    Text(r.subtitle, fontSize = 12.sp, color = Slate500)
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Slate400, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- CONTROL

@Composable
private fun ControlContextArea(contexts: List<ControlContext>, selectedId: String?, onSelect: (String) -> Unit) {
    val needs = contexts.count { it.stateLabel == "Check due" }
    val working = contexts.count { it.stateLabel == "Processing" }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE CONTEXT", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary)
            Spacer(Modifier.width(8.dp))
            Text("$needs need you · $working working", fontSize = 11.sp, color = VirlinColors.TextTertiary)
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            contexts.forEach { ctx ->
                val selected = ctx.id == selectedId
                Column(
                    modifier = Modifier
                        .background(if (selected) VirlinColors.FocusSurface else Color.White, RoundedCornerShape(14.dp))
                        .border(1.dp, if (selected) VirlinColors.Emerald.copy(alpha = 0.5f) else Hairline, RoundedCornerShape(14.dp))
                        .testTag(controlContextTag(ctx.id))
                        .clickable(role = Role.RadioButton) { onSelect(ctx.id) }
                        .semantics { contentDescription = "${ctx.title}, ${ctx.stateLabel}"; this.selected = selected }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .widthIn(min = 96.dp)
                ) {
                    Text(ctx.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ctx.stateLabel, fontSize = 11.sp, color = VirlinColors.TextSecondary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "“Give Claude another five minutes.”  ·  “Check Antigravity.”  ·  “Resume Psychology.”",
            fontSize = 11.sp, color = VirlinColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------- CREATE

@Composable
private fun CreateContextArea(
    type: CreateType, destinations: List<Destination>, selectedDestinationId: String?,
    onTypeSelected: (CreateType) -> Unit, onDestinationSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CreateType.values().forEach { t ->
                val selected = t == type
                Text(
                    t.label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp,
                    color = if (selected) Color.White else VirlinColors.TextSecondary,
                    modifier = Modifier
                        .background(if (selected) VirlinColors.TextPrimary else Color.White, RoundedCornerShape(50))
                        .border(1.dp, if (selected) Color.Transparent else Hairline, RoundedCornerShape(50))
                        .testTag(createTypeTag(t))
                        .clickable(role = Role.RadioButton) { onTypeSelected(t) }
                        .semantics { contentDescription = t.label; this.selected = selected }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("DESTINATION · optional", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = VirlinColors.TextTertiary)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            destinations.forEach { d ->
                val selected = d.id == selectedDestinationId
                Text(
                    d.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.TextPrimary,
                    modifier = Modifier
                        .background(if (selected) VirlinColors.FocusSurface else Color.White, RoundedCornerShape(50))
                        .border(1.dp, if (selected) VirlinColors.Emerald.copy(alpha = 0.5f) else Hairline, RoundedCornerShape(50))
                        .testTag(createDestinationTag(d.id))
                        .clickable(role = Role.RadioButton) { onDestinationSelected(d.id) }
                        .semantics { contentDescription = "Destination ${d.name}"; this.selected = selected }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when (type) {
                CreateType.TASK -> "“Under Virlin Development, add a task to test the Orb animation.”"
                CreateType.WORKSTREAM -> "“Create a WorkStream for Virlin Agent development.”"
                CreateType.REMINDER -> "“Remind me in 20 minutes to check Claude.”"
            },
            fontSize = 11.sp, color = VirlinColors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

// ---------------------------------------------------------------- Clarification

@Composable
private fun ClarificationCard(prompt: String, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(VirlinColors.NeedsYouDue, RoundedCornerShape(14.dp))
            .border(1.dp, VirlinColors.NeedsYouDueBorder, RoundedCornerShape(14.dp))
            .testTag(ClarificationTestTag)
            .padding(12.dp)
    ) {
        Text(prompt, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("5 min", "10 min", "30 min", "Custom").forEach { opt ->
                Text(
                    opt, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VirlinColors.TextPrimary,
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(50))
                        .clickable(role = Role.Button) { onAnswer(opt) }
                        .semantics { contentDescription = "Remind in $opt" }
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
    }
}
