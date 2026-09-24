package com.virlin.app.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.hierarchy.HierarchyViewModel
import com.virlin.app.ui.theme.*
import java.time.Instant
import java.time.ZoneId

/** Test hook: the Streams lazy list (lets tests scroll a row into composition). */
const val StreamsListTag = "streams_list"

/** Horizontal Streams filter rail (All · Projects · Need You · Processing · Ready · Snoozed · Blocked). */
const val StreamsFilterRailTag = "streams_filter_rail"
const val StreamsCompletedToggleTag = "streams_completed_toggle"

private val StreamsFilters = StreamsFilter.entries.map { it.label }

fun streamsFilterTag(label: String) = "streams_filter_${label.lowercase().replace(' ', '_')}"
fun streamsSectionTag(section: StreamsSection) = "streams_section_${section.name.lowercase()}"
fun streamsEmptyTag(section: StreamsSection?) = "streams_empty_${section?.name?.lowercase() ?: "all"}"

/**
 * Streams = the overview / index of all work, grouped by SECTION (= application state). Colour
 * belongs to the section, never to a project. White canvas; white cards with a 1dp semantic
 * border and a small leading accent. Now remains the detailed attention surface.
 */
@Composable
fun StreamsScreen(navController: NavController) {
    val streams by MockData.streams.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val hierarchy: HierarchyViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val snapshot by hierarchy.snapshot.collectAsState()
    val projectSummaries = remember(snapshot) { hierarchy.projectSummaries(snapshot) }
    // Domain facts a display row does not carry (real wake time, block reason, completion stamp).
    val facts = remember(snapshot) {
        snapshot.streams.associate { it.id to StreamsDomainFacts(it.snoozedUntil, it.blockerReason, it.completedAt) }
    }
    val now = remember(snapshot) { Instant.now() }

    StreamsContent(
        streams = streams, projectSummaries = projectSummaries, facts = facts, now = now,
        searchQuery = searchQuery, onSearchChange = { searchQuery = it },
        filter = filter, onFilterSelected = { filter = it },
        onOpenProject = { navController.navigate(com.virlin.app.ui.hierarchy.projectDetail(it)) },
        onOpenStream = { navController.navigate(com.virlin.app.ui.hierarchy.workStreamDetail(it)) },
        reducedMotion = rememberStreamsReducedMotion()
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StreamsContent(
    streams: List<WorkStream>,
    projectSummaries: List<HierarchyViewModel.ProjectSummary>,
    facts: Map<String, StreamsDomainFacts>,
    now: Instant,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    filter: String,
    onFilterSelected: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenStream: (String) -> Unit,
    reducedMotion: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault()
) {
    val activeFilter = StreamsFilter.byLabel(filter)
    val filtered = StreamsPresentation.search(streams, searchQuery)
    val sections = StreamsPresentation.sections(filtered, activeFilter, facts, now)
    val showProjects = activeFilter == StreamsFilter.ALL || activeFilter == StreamsFilter.PROJECTS
    var completedExpanded by rememberSaveable { mutableStateOf(false) }
    val placement: Modifier = Modifier

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Streams", style = Typography.titleLarge, color = Charcoal)
            Text("${streams.size} total", fontSize = 12.sp, color = CharcoalMuted)
        }
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 50.dp),
            placeholder = { Text("Search...", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CharcoalLight) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                containerColor = VirlinPalette.LightGray,
                unfocusedBorderColor = VirlinPalette.LightGrayDark,
                focusedBorderColor = CharcoalLight
            )
        )
        Spacer(Modifier.height(12.dp))
        StreamsFilterRail(filter = filter, onFilterSelected = onFilterSelected)
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.testTag(StreamsListTag),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            fun LazyItemScope.moving(): Modifier =
                if (reducedMotion) placement else placement.animateItemPlacement(tween(280))

            if (showProjects) {
                if (projectSummaries.isNotEmpty()) {
                    item(key = "h_projects") { StreamsSectionHeader(StreamsSection.PROJECTS, projectSummaries.size, modifier = moving()) }
                    items(projectSummaries, key = { "p_" + it.project.id }) { ps -> ProjectCard(ps, onOpenProject, moving()) }
                } else if (activeFilter == StreamsFilter.PROJECTS) {
                    item(key = "e_projects") { StreamsEmptyState(StreamsSection.PROJECTS) }
                }
            }
            sections.forEach { (section, rows) ->
                val collapsible = section == StreamsSection.COMPLETED
                item(key = "h_${section.name}") {
                    StreamsSectionHeader(
                        section, rows.size, modifier = moving(),
                        trailing = if (collapsible && rows.isNotEmpty()) {
                            { CompletedToggle(completedExpanded) { completedExpanded = !completedExpanded } }
                        } else null
                    )
                }
                if (rows.isEmpty()) {
                    item(key = "e_${section.name}") { StreamsEmptyState(section) }
                } else if (!collapsible || completedExpanded) {
                    items(rows, key = { "s_${section.name}_" + it.id }) { stream ->
                        StreamCard(stream, section, facts[stream.id], now, zone, onOpenStream, moving())
                    }
                }
            }
            if (activeFilter == StreamsFilter.ALL && projectSummaries.isEmpty() && sections.isEmpty()) {
                item(key = "e_all") { StreamsEmptyState(null) }
            }
            item(key = "bottom_space") { Spacer(Modifier.height(88.dp)) }
        }
    }
}

// ---------------------------------------------------------------- filter rail

/**
 * Streams filter rail. Chips keep intrinsic width (`maxLines = 1`, `softWrap = false`);
 * the row viewport owns horizontal scrolling — never compress labels into the screen width.
 * Inactive = white + neutral border; active = the section's pale surface + ink (All stays dark).
 * Projects live INSIDE Streams — never a fourth nav destination.
 */
@Composable
fun StreamsFilterRail(filter: String, onFilterSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag(StreamsFilterRailTag).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StreamsFilters.forEach { f ->
            val sel = f == filter
            val look = StreamsFilter.byLabel(f).section?.look
            val bg = when { !sel -> Color.White; look == null -> Charcoal; else -> look.surface }
            val fg = when { !sel -> CharcoalMuted; look == null -> Color.White; else -> look.ink }
            val edge = when { !sel -> VirlinPalette.LightGrayDark; look == null -> Charcoal; else -> look.border }
            Text(
                text = f, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp, color = fg,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                modifier = Modifier
                    .background(bg, RoundedCornerShape(50))
                    .border(1.dp, edge, RoundedCornerShape(50))
                    .testTag(streamsFilterTag(f))
                    .clickable(role = Role.Tab) { onFilterSelected(f) }
                    .semantics { contentDescription = "$f filter"; selected = sel }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- section primitives

/** `● TITLE  [n]` over a one-line meaning. Marker colour + textual title: never colour alone. */
@Composable
fun StreamsSectionHeader(
    section: StreamsSection,
    count: Int,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val look = section.look
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp)
            .testTag(streamsSectionTag(section))
            .semantics(mergeDescendants = true) { heading(); contentDescription = "${section.spoken}, $count, ${section.meaning}" }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(look.accent, CircleShape).border(1.dp, look.accentDark.copy(alpha = 0.5f), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(section.title, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp, color = Charcoal)
            Spacer(Modifier.width(8.dp))
            StreamsCountBadge(count, look)
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Text(section.meaning, fontSize = 11.sp, color = CharcoalLight, modifier = Modifier.padding(start = 18.dp, top = 1.dp))
    }
}

@Composable
private fun StreamsCountBadge(count: Int, look: StreamsSectionLook) {
    Text(
        count.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = look.badgeForeground, maxLines = 1,
        modifier = Modifier.background(look.badgeSurface, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@Composable
private fun CompletedToggle(expanded: Boolean, onToggle: () -> Unit) {
    Text(
        if (expanded) "HIDE" else "SHOW", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, color = CharcoalMuted,
        modifier = Modifier.testTag(StreamsCompletedToggleTag).clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = if (expanded) "Hide recently completed" else "Show recently completed" }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/** White card, 1dp semantic border, small leading accent edge. Same family for every card in a section. */
@Composable
fun StreamsCard(
    look: StreamsSectionLook,
    modifier: Modifier = Modifier,
    leadingAccent: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    // No `clip` modifier: it would also clip touch input at the card edge. The leading accent is
    // drawn inside the rounded outline instead, so the whole card rectangle stays tappable.
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier.fillMaxWidth()
            .background(Color.White, shape)
            .border(1.dp, look.border, shape)
            .drawBehind {
                if (leadingAccent) {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    clipPath(Path().apply { addOutline(outline) }) { drawRect(look.accent, size = Size(4.dp.toPx(), size.height)) }
                }
            }
            .padding(start = if (leadingAccent) 16.dp else 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
fun StreamsEmptyState(section: StreamsSection?) {
    Text(
        section?.emptyMessage ?: "No streams yet.",
        fontSize = 13.sp, color = CharcoalMuted,
        modifier = Modifier.fillMaxWidth().testTag(streamsEmptyTag(section)).padding(horizontal = 4.dp, vertical = 18.dp)
    )
}

// ---------------------------------------------------------------- cards

/** Projects are containers: neutral card, identity from the project icon, real progress bar when a value exists. */
@Composable
private fun ProjectCard(ps: HierarchyViewModel.ProjectSummary, onOpen: (String) -> Unit, modifier: Modifier) {
    val fraction = ps.progress.fraction
    StreamsCard(
        StreamsSection.PROJECTS.look, leadingAccent = false,
        modifier = modifier.testTag("project_row_${ps.project.id}")
            .clickable(role = Role.Button) { onOpen(ps.project.id) }
            .semantics { contentDescription = "${ps.project.title}, ${ps.streamCount} WorkStreams, ${ps.progress.text}" }
    ) {
        com.virlin.app.ui.components.ProjectIcon(project = ps.project, size = 38.dp, decorative = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ps.project.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Charcoal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(ps.progress.text, fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1,
                    color = if (fraction == null) CharcoalLight else Charcoal)
            }
            Text("${ps.streamCount} WorkStreams", fontSize = 12.sp, color = CharcoalMuted)
            if (fraction != null) {
                Spacer(Modifier.height(7.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).background(VirlinPalette.LightGrayDark, RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(CharcoalMuted, RoundedCornerShape(50)))
                }
            }
        }
    }
}

@Composable
private fun StreamCard(
    stream: WorkStream, section: StreamsSection, facts: StreamsDomainFacts?, now: Instant, zone: ZoneId,
    onOpen: (String) -> Unit, modifier: Modifier
) {
    val look = section.look
    // Trailing status = the real value for this state; nothing is invented.
    val status: String? = when (stream.state) {
        StreamState.FOCUS -> StreamsPresentation.clock(stream.focusInvestedSec) + " invested"
        StreamState.PROCESSING -> StreamsPresentation.clock(stream.processingElapsedSec) + " elapsed"
        StreamState.READY -> StreamsPresentation.estimate(stream.expectedDurationSec)
        StreamState.SNOOZED -> StreamsPresentation.wakeLabel(facts?.snoozedUntil, now, zone)
        StreamState.DONE -> StreamsPresentation.completedLabel(facts?.completedAt, now)
        else -> null
    }
    val reason = if (stream.state == StreamState.BLOCKED) (stream.blockerReason ?: facts?.blockerReason)?.takeIf { it.isNotBlank() } else null
    val spoken = listOfNotNull(stream.title, stream.subtitle.takeIf { it.isNotBlank() }, section.spoken, status, reason,
        if (stream.state == StreamState.NEEDS_YOU) "Check" else null).joinToString(", ")

    StreamsCard(
        look,
        modifier = modifier.testTag("stream_row_${stream.id}")
            .clickable(role = Role.Button) { onOpen(stream.id) }
            .semantics { contentDescription = spoken }
    ) {
        Column(Modifier.weight(1f)) {
            Text(stream.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Charcoal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (stream.subtitle.isNotBlank()) Text(stream.subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (reason != null) Text(reason, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(10.dp))
        when {
            stream.state == StreamState.NEEDS_YOU -> Box(
                modifier = Modifier.background(look.accent, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)
            ) { Text("CHECK", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, color = Charcoal) }
            status != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (stream.state == StreamState.PROCESSING) {
                    Box(Modifier.size(6.dp).background(look.accent, CircleShape)); Spacer(Modifier.width(6.dp))
                }
                Text(status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = look.ink, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun rememberStreamsReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
