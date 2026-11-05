package com.virlin.app.ui.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.structure.HierarchyNode
import com.virlin.app.domain.structure.NodeKind
import com.virlin.app.ui.hierarchy.LocalProjectPalette
import com.virlin.app.ui.hierarchy.readableOn

/** The map's move picker mirrors the task page's guided destination sheet. It never writes data. */
@Composable
fun MapMoveDestinationSheet(
    source: MapTopic,
    affectedCount: Int,
    projectTitle: String,
    nodes: List<HierarchyNode>,
    titles: Map<String, String>,
    destinations: List<MapDestination>,
    onDismiss: () -> Unit,
    onChoose: (MapDestination) -> Unit,
) {
    val root = nodes.firstOrNull { it.kind == NodeKind.PROJECT && it.parentId == null }
        ?: return
    val byId = remember(nodes) { nodes.associateBy { it.id } }
    val children = remember(nodes) {
        nodes.groupBy { it.parentId }.mapValues { (_, values) ->
            values.sortedWith(compareBy<HierarchyNode> { it.order }.thenBy { it.id })
        }
    }
    val legal = remember(destinations) { destinations.associateBy { it.node.id } }
    val excluded = remember(nodes, source.id) {
        val hidden = mutableSetOf(source.id)
        val pending = ArrayDeque<String>()
        pending.add(source.id)
        while (pending.isNotEmpty()) {
            children[pending.removeLast()].orEmpty().forEach { child ->
                if (hidden.add(child.id)) pending.add(child.id)
            }
        }
        hidden
    }
    var browsingId by remember(source.id) { mutableStateOf(root.id) }
    var selectedId by remember(source.id) {
        mutableStateOf<String?>(legal[root.id]?.node?.id)
    }
    var committing by remember(source.id) { mutableStateOf(false) }
    fun label(node: HierarchyNode): String = if (node.kind == NodeKind.PROJECT) projectTitle
        else titles[node.id] ?: node.id
    fun hasLegalDescendant(id: String): Boolean {
        val pending = ArrayDeque<String>()
        pending.add(id)
        while (pending.isNotEmpty()) {
            children[pending.removeLast()].orEmpty().forEach { child ->
                if (child.id in legal) return true
                pending.add(child.id)
            }
        }
        return false
    }
    val browsed = byId[browsingId] ?: root
    val choices = children[browsed.id].orEmpty().filter { node ->
        node.id !in excluded && (node.id in legal || hasLegalDescendant(node.id))
    }
    val selected = selectedId?.let(legal::get)
    val palette = LocalProjectPalette.current
    val accent = palette?.let { Color(it.main) } ?: MapGreen
    val ink = palette?.let { Color(it.text) } ?: MapInk
    val muted = palette?.let { Color(it.muted) } ?: MapMuted
    val wash = Color(androidx.core.graphics.ColorUtils.blendARGB(
        android.graphics.Color.WHITE, accent.toArgb(), .10f))
    val line = accent.copy(alpha = .25f)
    val path = generateSequence(browsed) { it.parentId?.let(byId::get) }
        .toList().asReversed().joinToString("  ›  ") { label(it) }
    BackHandler {
        if (browsed.id == root.id) onDismiss()
        else browsingId = browsed.parentId ?: root.id
    }
    Box(Modifier.fillMaxSize().testTag(MapMoveSheetTag)) {
        Box(Modifier.fillMaxSize().background(Color(0x660F241B)).clickable(onClick = onDismiss))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.79f)
            .background(Color.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 15.dp)
                .size(width = 36.dp, height = 4.dp)
                .background(line, RoundedCornerShape(4.dp)))
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    val noun = if (affectedCount == 1) {
                        if (source.kind == MapKind.WORKSTREAM) "WORKSTREAM" else "TASK"
                    } else "ITEMS"
                    Text("MOVE $affectedCount $noun", color = accent, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                    Text("Choose a destination", color = ink, fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("move_destination_close")) {
                    Icon(Icons.Default.Close, contentDescription = "Close move destinations", tint = muted)
                }
            }
            Text(path, color = muted, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                    .background(wash, RoundedCornerShape(12.dp)).padding(12.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)) {
                if (browsed.id != root.id) {
                    TextButton(onClick = { browsingId = browsed.parentId ?: root.id }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                            modifier = Modifier.size(18.dp), tint = accent)
                        Spacer(Modifier.width(8.dp))
                        Text("Back one level", color = accent)
                    }
                }
                if (destinations.isEmpty()) {
                    Text("There is nowhere else this branch can go.", color = muted,
                        modifier = Modifier.padding(12.dp))
                } else {
                    MapMoveDestinationRow(
                        title = if (browsed.id == root.id) "Project root" else label(browsed),
                        subtitle = if (browsed.id == root.id) "Keep as a top-level task"
                            else "Place inside this ${browsed.kind.name.lowercase()}",
                        selected = selectedId == browsed.id,
                        enabled = browsed.id in legal,
                        accent = accent, ink = ink, muted = muted, wash = wash,
                        onSelect = { selectedId = browsed.id },
                        modifier = Modifier.testTag("move_destination_current"),
                    )
                    choices.forEach { candidate ->
                        val childCount = children[candidate.id].orEmpty().size
                        MapMoveDestinationRow(
                            title = label(candidate),
                            subtitle = "$childCount ${if (childCount == 1) "child" else "children"}" +
                                if (childCount > 0) " · Tap to explore" else "",
                            selected = selectedId == candidate.id,
                            enabled = candidate.id in legal,
                            accent = accent, ink = ink, muted = muted, wash = wash,
                            onSelect = { selectedId = candidate.id },
                            onExplore = if (childCount > 0) ({ browsingId = candidate.id }) else null,
                            modifier = Modifier.testTag(mapDestinationTag(candidate.id)),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().border(1.dp, line)
                .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DESTINATION", color = muted, fontSize = 10.sp, letterSpacing = 1.sp)
                    Text(selected?.title ?: "Choose a location", color = ink,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = {
                    if (!committing) selected?.let { committing = true; onChoose(it) }
                }, enabled = selected != null && !committing,
                    modifier = Modifier.testTag("move_destination_confirm"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent, contentColor = readableOn(accent)),
                    shape = RoundedCornerShape(12.dp)) { Text("Move here") }
            }
        }
    }
}

@Composable
private fun MapMoveDestinationRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    ink: Color,
    muted: Color,
    wash: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    onExplore: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(bottom = 5.dp)
        .background(if (selected) wash else Color.White, RoundedCornerShape(13.dp))
        .border(if (selected) 1.dp else 0.dp,
            if (selected) accent else Color.Transparent, RoundedCornerShape(13.dp))
        .clickable(enabled = enabled, onClick = onSelect)
        .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Folder, null, tint = accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) ink else muted, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = muted, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = "Selected destination",
            tint = accent, modifier = Modifier.size(20.dp))
        if (onExplore != null) IconButton(onClick = onExplore,
            modifier = Modifier.size(40.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Explore $title", tint = accent)
        }
    }
}
