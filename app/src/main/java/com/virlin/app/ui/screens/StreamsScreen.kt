package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.theme.*

/** Test hook: the Streams lazy list (lets tests scroll a row into composition). */
const val StreamsListTag = "streams_list"

/** Horizontal Streams filter rail (All · Projects · Need You · Processing · Ready). */
const val StreamsFilterRailTag = "streams_filter_rail"

private val StreamsFilters = listOf("All", "Projects", "Need You", "Processing", "Ready")

fun streamsFilterTag(label: String) =
    "streams_filter_${label.lowercase().replace(' ', '_')}"


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamsScreen(navController: NavController) {
    val streams by MockData.streams.collectAsState()
    
    var searchQuery by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val hierarchy: com.virlin.app.ui.hierarchy.HierarchyViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val snapshot by hierarchy.snapshot.collectAsState()
    val projectSummaries = remember(snapshot) { hierarchy.projectSummaries(snapshot) }
    
    val filtered = streams.filter {
        searchQuery.isEmpty() || 
        it.title.contains(searchQuery, ignoreCase = true) || 
        it.subtitle.contains(searchQuery, ignoreCase = true)
    }
    
    val focus = filtered.filter { it.state == StreamState.FOCUS }
    val needsYou = filtered.filter { it.state == StreamState.NEEDS_YOU }
    val processing = filtered.filter { it.state == StreamState.PROCESSING }
    val ready = filtered.filter { it.state == StreamState.READY }
    val snoozed = filtered.filter { it.state == StreamState.SNOOZED }
    val blocked = filtered.filter { it.state == StreamState.BLOCKED }
    val paused = filtered.filter { it.state == StreamState.PAUSED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pearl)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Streams", style = Typography.titleLarge, color = Charcoal)
                Text("${streams.size} total", fontSize = 12.sp, color = CharcoalMuted)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(50.dp),
            placeholder = { Text("Search...", fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                containerColor = Color.White,
                unfocusedBorderColor = Color.Transparent
            )
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal filter rail: chips own content width; viewport scrolls — never compress labels.
        StreamsFilterRail(
            filter = filter,
            onFilterSelected = { filter = it }
        )

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.testTag(StreamsListTag),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if ((filter == "All" || filter == "Projects") && projectSummaries.isNotEmpty()) {
                item { GroupHeader("PROJECTS", projectSummaries.size, FocusGreen) }
                items(projectSummaries, key = { "p_" + it.project.id }) { ps ->
                    Row(
                        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp))
                            .testTag("project_row_${ps.project.id}")
                            .clickable { navController.navigate(com.virlin.app.ui.hierarchy.projectDetail(ps.project.id)) }
                            .semantics { contentDescription = "${ps.project.title}, ${ps.streamCount} WorkStreams, ${ps.progress.text}" }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ps.project.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Charcoal)
                            Text("${ps.streamCount} WorkStreams", fontSize = 12.sp, color = CharcoalMuted)
                        }
                        Text(ps.progress.text, fontSize = 13.sp, fontWeight = FontWeight.Black,
                            color = if (ps.progress.fraction == null) CharcoalLight else Charcoal)
                    }
                }
            }
            val showStreams = filter != "Projects"
            if (showStreams && filter == "All" && focus.isNotEmpty()) {
                item { GroupHeader("FOCUS", focus.size, FocusGreen) }
                items(focus) { stream -> StreamRow(stream, navController) }
            }
            if (showStreams && (filter == "All" || filter == "Need You") && needsYou.isNotEmpty()) {
                item { GroupHeader("NEEDS YOU", needsYou.size, NeedsYouYellow) }
                items(needsYou) { stream -> StreamRow(stream, navController) }
            }
            if (showStreams && (filter == "All" || filter == "Processing") && processing.isNotEmpty()) {
                item { GroupHeader("PROCESSING", processing.size, ProcessingLavender) }
                items(processing) { stream -> StreamRow(stream, navController) }
            }
            if (showStreams && (filter == "All" || filter == "Ready") && ready.isNotEmpty()) {
                item { GroupHeader("READY", ready.size, FreeMint) }
                items(ready) { stream -> StreamRow(stream, navController) }
            }
            if (showStreams && filter == "All" && snoozed.isNotEmpty()) {
                item { GroupHeader("SNOOZED", snoozed.size, Color.LightGray) }
                items(snoozed) { stream -> StreamRow(stream, navController) }
            }
            if (showStreams && filter == "All" && blocked.isNotEmpty()) {
                item { GroupHeader("BLOCKED", blocked.size, NeedsYouCoral) }
                items(blocked) { stream -> StreamRow(stream, navController) }
            }
            item { Spacer(modifier = Modifier.height(88.dp)) }
        }
    }
}

/**
 * Streams filter rail. Chips keep intrinsic width (`maxLines = 1`, `softWrap = false`);
 * the row viewport owns horizontal scrolling — never compress labels into the screen width.
 * Projects live INSIDE Streams — never a fourth nav destination.
 */
@Composable
fun StreamsFilterRail(
    filter: String,
    onFilterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag(StreamsFilterRailTag)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StreamsFilters.forEach { f ->
            val sel = f == filter
            Text(
                text = f,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.4.sp,
                color = if (sel) Color.White else CharcoalMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .background(if (sel) Charcoal else Color.White, RoundedCornerShape(50))
                    .testTag(streamsFilterTag(f))
                    .clickable { onFilterSelected(f) }
                    .semantics { contentDescription = "$f filter"; selected = sel }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
fun GroupHeader(title: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Black, color = CharcoalMuted)
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.size(18.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            Text(count.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Charcoal)
        }
    }
}

@Composable
fun StreamRow(stream: WorkStream, navController: NavController) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(16.dp))
            .testTag("stream_row_${stream.id}")
            .clickable { navController.navigate(com.virlin.app.ui.hierarchy.workStreamDetail(stream.id)) }
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stream.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Charcoal)
            Text(stream.subtitle, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CharcoalMuted)
        }
        
        when (stream.state) {
            StreamState.FOCUS -> {
                val m = (stream.focusInvestedSec / 60).toString().padStart(2, '0')
                val s = (stream.focusInvestedSec % 60).toString().padStart(2, '0')
                Text("${m}:${s} invested", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Emerald500)
            }
            StreamState.NEEDS_YOU -> {
                Box(modifier = Modifier.background(Charcoal, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("CHECK", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            StreamState.PROCESSING -> {
                val m = (stream.processingElapsedSec / 60).toString().padStart(2, '0')
                val s = (stream.processingElapsedSec % 60).toString().padStart(2, '0')
                Text("${m}:${s} elapsed", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Violet600)
            }
            StreamState.READY -> {
                Text("~5m", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted)
            }
            else -> {}
        }
    }
}
