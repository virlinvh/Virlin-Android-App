package com.virlin.app.ui.apps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.map.MapLayout
import com.virlin.app.ui.map.MapTopic
import com.virlin.app.ui.map.arrange
import kotlin.math.max

const val MindMapsHomeTag = "mind_maps_home"
const val MindMapsFolderTag = "mind_maps_folder"
fun mindMapProjectCardTag(id: String) = "mind_map_project_$id"

/**
 * One map the gallery can show. [topics] are the REAL projected nodes, so a cover is drawn from
 * the same hierarchy the editor draws and never from invented branches.
 */
data class MindMapEntry(
    val id: String,
    val projectId: String,
    val title: String,
    val isDefault: Boolean,
    val topics: List<MapTopic>,
    val subtitle: String = "",
)

data class MindMapProject(val id: String, val title: String, val maps: List<MindMapEntry>) {
    val defaultMap: MindMapEntry? get() = maps.firstOrNull { it.isDefault }
}

private val Ink = Color(0xFF19232D)
private val Subtle = Color(0xFF647186)
private val Outline = Color(0xFFE1E6EC)
private val Green = Color(0xFF348E35)

/**
 * The gallery. Content only: the app shell keeps the bottom navigation and the Orb, so nothing
 * here draws either, and the list leaves room for them.
 */
@Composable
fun MindMapsHomeScreen(
    projects: List<MindMapProject>,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onCreate: () -> Unit,
    onProjectMenu: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Null until the app records when a map was last opened; the link is omitted meanwhile. */
    onRecent: (() -> Unit)? = null,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var reverseSort by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(projects, query, reverseSort) {
        projects.filter { it.title.contains(query.trim(), ignoreCase = true) }
            .sortedBy { it.title.lowercase() }
            .let { if (reverseSort) it.reversed() else it }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(MindMapsHomeTag),
        // The Orb floats above the footer bottom-right; the last row clears it.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 86.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Apps")
                }
                Text("Apps", color = Ink, fontSize = 18.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                    Icon(Icons.Default.Search, "Search projects")
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Mind Maps", fontSize = 30.sp, lineHeight = 36.sp,
                        fontWeight = FontWeight.Bold, color = Ink
                    )
                    Text("See how your projects connect.", fontSize = 14.sp, color = Subtle)
                }
                Spacer(Modifier.width(12.dp))
                // The one create action on this screen; it is not repeated in the app bar.
                Surface(
                    onClick = onCreate,
                    modifier = Modifier.size(52.dp).testTag("mind_maps_create")
                        .semantics { contentDescription = "New mind map" },
                    color = Green, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
            if (searchOpen) {
                Spacer(Modifier.height(18.dp))
                // Opening search should let you type straight away, as it does on the Apps screen.
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .testTag("mind_maps_search"), singleLine = true,
                    label = { Text("Search projects") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
            }
            Spacer(Modifier.height(34.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Project maps", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(
                        "${projects.size} ${if (projects.size == 1) "project" else "projects"}",
                        fontSize = 14.sp, color = Subtle
                    )
                }
                TextButton(onClick = { reverseSort = !reverseSort }) {
                    Text(if (reverseSort) "Z–A" else "A–Z", color = Green)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    if (projects.isEmpty()) "Your project maps will appear here."
                    else "No matching projects.",
                    Modifier.padding(vertical = 32.dp), color = Subtle
                )
            }
        }
        items(filtered.chunked(2), key = { it.first().id }) { row ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { project ->
                    ProjectCard(
                        project, { onOpenProject(project.id) }, { onProjectMenu(project.id) },
                        Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        // "Recently opened" appears only when the app actually records opened times.
        if (onRecent != null) item {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = Outline)
            Row(
                Modifier.fillMaxWidth().height(54.dp)
                    .clickable(onClick = onRecent),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recently opened", Modifier.weight(1f), color = Green, fontSize = 15.sp)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Subtle)
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: MindMapProject, onOpen: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier
) {
    Surface(
        onClick = onOpen, modifier = modifier.testTag(mindMapProjectCardTag(project.id)),
        color = Color.White, border = BorderStroke(1.dp, Outline), shape = RoundedCornerShape(17.dp)
    ) {
        Column {
            MapThumbnail(
                project.defaultMap,
                project.title,
                Modifier.fillMaxWidth().height(144.dp).padding(8.dp)
            )
            Column(Modifier.padding(start = 12.dp, end = 4.dp, bottom = 7.dp)) {
                Text(
                    project.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    minLines = 2, maxLines = 2, lineHeight = 19.sp, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, null, tint = Subtle, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${project.maps.size} ${if (project.maps.size == 1) "map" else "maps"}",
                        Modifier.weight(1f), color = Subtle, fontSize = 12.sp
                    )
                    IconButton(onClick = onMenu, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.MoreVert, "Options for ${project.title}",
                            tint = Subtle, modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * One project's folder: its default map featured, any additional maps below.
 *
 * [canCreate] is false while the app has no storage for additional maps — the action is shown
 * disabled with the reason rather than pretending a feature exists.
 */
@Composable
fun MindMapProjectScreen(
    project: MindMapProject,
    onBack: () -> Unit,
    onOpenMap: (String) -> Unit,
    onCreateInProject: (String) -> Unit,
    modifier: Modifier = Modifier,
    canCreate: Boolean = true,
    createUnavailableReason: String = "",
) {
    val featured = project.defaultMap
    val others = project.maps.filterNot { it.isDefault }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(MindMapsFolderTag),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 86.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Mind Maps")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        project.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text("Mind maps", fontSize = 14.sp, color = Subtle)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (featured != null) {
            item {
                Surface(
                    color = Color.White, border = BorderStroke(1.dp, Outline),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column {
                        MapThumbnail(
                            featured, project.title,
                            Modifier.fillMaxWidth().height(190.dp).padding(12.dp)
                        )
                        Column(Modifier.padding(16.dp)) {
                            Text(featured.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink)
                            Text(
                                featured.subtitle.ifBlank { "Default project map" },
                                fontSize = 13.sp, color = Subtle
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { onOpenMap(featured.id) },
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                                    .testTag("mind_map_open"),
                                colors = ButtonDefaults.buttonColors(containerColor = Green),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("Open map") }
                        }
                    }
                }
                Spacer(Modifier.height(26.dp))
            }
        }
        item {
            Text("More maps", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(12.dp))
            if (others.isEmpty()) {
                Text(
                    "This project has only its default map.",
                    fontSize = 13.sp, color = Subtle, modifier = Modifier.padding(bottom = 12.dp)
                )
            }
        }
        items(others, key = { it.id }) { map ->
            Surface(
                onClick = { onOpenMap(map.id) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                border = BorderStroke(1.dp, Outline), color = Color.White,
                shape = RoundedCornerShape(15.dp)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    MapThumbnail(map, project.title, Modifier.width(108.dp).height(76.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(map.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        if (map.subtitle.isNotBlank()) {
                            Text(map.subtitle, fontSize = 12.sp, color = Subtle, maxLines = 2)
                        }
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Subtle)
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { onCreateInProject(project.id) },
                enabled = canCreate,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                border = BorderStroke(1.dp, Outline), shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, null, tint = if (canCreate) Green else Subtle)
                Spacer(Modifier.width(8.dp))
                Text("New mind map", color = if (canCreate) Green else Subtle)
            }
            if (!canCreate && createUnavailableReason.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(createUnavailableReason, fontSize = 12.sp, color = Subtle)
            }
        }
    }
}

/**
 * The cover, drawn from the map's REAL nodes through the same `arrange` the editor uses, so it
 * shows this project's actual shape. When a project has no nodes to draw beyond its own root
 * there is nothing to illustrate, and the cover says so rather than inventing branches.
 */
@Composable
private fun MapThumbnail(map: MindMapEntry?, projectTitle: String, modifier: Modifier = Modifier) {
    val topics = map?.topics.orEmpty()
    // Recomputed only when the hierarchy itself changes, so scrolling the gallery costs nothing
    // and a nested task edit updates the cover on the next projection.
    val geometry = remember(topics) {
        if (topics.size < 2) null else runCatching { arrange(topics, MapLayout.RIGHT_TREE) }.getOrNull()
    }
    val label = "Map preview: $projectTitle" +
        if (geometry == null) ", no tasks yet" else ", ${topics.size} nodes"
    Canvas(
        modifier.clip(RoundedCornerShape(12.dp)).semantics { contentDescription = label }
    ) {
        drawRect(Color(0xFFFAFBF9))
        val g = geometry ?: return@Canvas
        val worldWidth = max(1f, g.right - g.left)
        val worldHeight = max(1f, g.bottom - g.top)
        val pad = 10f
        val scale = minOf(
            (size.width - pad * 2) / worldWidth,
            (size.height - pad * 2) / worldHeight
        )
        val dx = pad + (size.width - pad * 2 - worldWidth * scale) / 2f - g.left * scale
        val dy = pad + (size.height - pad * 2 - worldHeight * scale) / 2f - g.top * scale
        fun px(x: Float) = x * scale + dx
        fun py(y: Float) = y * scale + dy

        g.topics.forEach { topic ->
            val parent = g.parent[topic.id]?.let(g.frames::get) ?: return@forEach
            val child = g.frames.getValue(topic.id)
            val fromX = px(parent.x + parent.w)
            val fromY = py(parent.y + parent.h / 2f)
            val toX = px(child.x)
            val toY = py(child.y + child.h / 2f)
            val curve = (toX - fromX) * .48f
            drawPath(
                Path().apply {
                    moveTo(fromX, fromY)
                    cubicTo(fromX + curve, fromY, toX - curve, toY, toX, toY)
                },
                Green.copy(alpha = .55f),
                style = Stroke(width = 1.4f)
            )
        }
        g.topics.forEach { topic ->
            val f = g.frames.getValue(topic.id)
            val w = f.w * scale
            val h = f.h * scale
            drawRoundRect(
                Green.copy(alpha = if (topic.parentId == null) .30f else .16f),
                topLeft = Offset(px(f.x), py(f.y)),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * .34f)
            )
        }
    }
}
