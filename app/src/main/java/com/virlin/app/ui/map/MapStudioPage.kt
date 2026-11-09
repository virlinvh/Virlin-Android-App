package com.virlin.app.ui.map

import android.graphics.RectF
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** One open colour choice: what it is for, where it starts and how it changes the appearance. */
private class ColourRequest(
    val title: String,
    val initial: Long,
    val apply: (MapAppearance, Long) -> MapAppearance,
    val secondaryLabel: String? = null,
    val secondary: ((MapAppearance) -> MapAppearance)? = null,
)

private val backgroundSuggestions = listOf(
    0xFFFFFFFF, 0xFFF7F3EA, 0xFFF1F7F2, 0xFFEEF3FA, 0xFFF4F0FA, 0xFFFBF0F0, 0xFFF2F2F0,
    0xFF1F2329, 0xFF15202B, 0xFF1D2A22,
)

/**
 * The map colour studio. Every control edits [appearance] in place and the live preview paints
 * the result through [paintMap], the same renderer as the canvas and exports, so what the
 * preview shows is what the map shows after "Save colours".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MapStudioPage(
    projectTitle: String, topics: List<MapTopic>, appearance: MapAppearance, hasChanges: Boolean,
    targetId: String?, search: String, pickedColor: Long?, accent: Color, textColor: Color,
    surface: Color, onSearch: (String) -> Unit, onTarget: (String) -> Unit,
    onColor: (Long) -> Unit, onAppearance: (MapAppearance) -> Unit,
    onBack: () -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<ColourRequest?>(null) }
    val palette = appearance.activePalette()
    val root = remember(topics) { topics.firstOrNull { it.parentId == null } }
    val workstreams = remember(topics, root) {
        root?.let { r -> topics.filter { it.parentId == r.id }.sortedWith(compareBy<MapTopic> { it.order }.thenBy { it.id }) }
            .orEmpty()
    }
    fun ask(
        title: String, initial: Long, apply: (MapAppearance, Long) -> MapAppearance,
        secondaryLabel: String? = null, secondary: ((MapAppearance) -> MapAppearance)? = null,
    ) { request = ColourRequest(title, initial, apply, secondaryLabel, secondary) }

    BoxWithConstraints(modifier.fillMaxSize().background(surface)) {
    // The preview stays visible while editing, so it gives up height first on short screens.
    val previewHeight = (maxHeight * .2f).coerceIn(100.dp, 170.dp)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to map", tint = textColor) }
            Column(Modifier.weight(1f)) {
                Text("Map colours", color = textColor, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text(projectTitle, color = textColor.copy(alpha = .58f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val defaults = appearance.withDefaultColours()
            TextButton(onClick = { onAppearance(defaults) }, enabled = defaults != appearance) {
                Text("Reset", color = if (defaults != appearance) accent else textColor.copy(alpha = .3f))
            }
        }
        StudioPreview(topics, appearance, targetId.takeIf { tab == 3 }, surface, textColor, previewHeight)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .background(textColor.copy(alpha = .05f), RoundedCornerShape(14.dp)).padding(3.dp)) {
            listOf("Palette", "Branches", "Boxes", "Items").forEachIndexed { index, label ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (tab == index) Color.White else Color.Transparent)
                    .clickable { tab = index }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = if (tab == index) accent else textColor.copy(alpha = .6f))
                }
            }
        }
        key(tab) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> PaletteTab(topics, workstreams, appearance, palette, accent, textColor, onAppearance, ::ask)
                    1 -> BranchesTab(appearance, palette, workstreams, accent, textColor, onAppearance, ::ask)
                    2 -> BoxesTab(appearance, palette, surface, accent, textColor, onAppearance, ::ask)
                    else -> ItemsTab(topics, appearance, targetId, search, accent, textColor,
                        onSearch, onTarget, onAppearance, ::ask)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        AnimatedVisibility(hasChanges) {
            Surface(shadowElevation = 8.dp, color = Color.White) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = onSave, modifier = Modifier.weight(1.5f),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Save colours") }
                }
            }
        }
    }
    }
    request?.let { r ->
        MapColourPicker(
            title = r.title, initial = r.initial, accent = accent, textColor = textColor,
            palette = palette, savedColors = appearance.savedColors,
            onSaveColour = { c -> onAppearance(appearance.copy(savedColors = (appearance.savedColors - c + c).takeLast(24))) },
            onDismiss = { request = null },
            onPick = { c -> onColor(c); onAppearance(r.apply(appearance, c)); request = null },
            secondaryLabel = r.secondaryLabel,
            onSecondary = r.secondary?.let { s -> { onAppearance(s(appearance)); request = null } },
        )
    }
}

// ------------------------------------------------------------------------------------ preview

/** A small but representative slice of the real map, keeping each workstream's real colour index. */
internal fun studioPreviewTopics(topics: List<MapTopic>, targetId: String?): Pair<List<MapTopic>, Map<String, Int>> {
    val root = topics.firstOrNull { it.parentId == null } ?: return layoutPreviewExample to emptyMap()
    val children = topics.groupBy { it.parentId }.mapValues { (_, v) ->
        v.sortedWith(compareBy<MapTopic> { it.order }.thenBy { it.id })
    }
    val top = children[root.id].orEmpty()
    if (top.isEmpty()) return layoutPreviewExample to emptyMap()
    val byId = topics.associateBy { it.id }
    val chain = generateSequence(targetId?.let(byId::get)) { t -> t.parentId?.let(byId::get) }.take(64).toList()
    val targetTop = chain.firstOrNull { it.parentId == root.id }
    val shown = top.take(4).let { first -> if (targetTop != null && targetTop !in first) first.take(3) + targetTop else first }
    val keep = LinkedHashSet<String>().apply { add(root.id) }
    shown.forEachIndexed { index, workstream ->
        keep += workstream.id
        val kids = children[workstream.id].orEmpty().take(if (index == 0) 2 else 1)
        kids.forEach { keep += it.id }
        if (index == 0) kids.firstOrNull()?.let { k -> children[k.id]?.firstOrNull()?.let { keep += it.id } }
    }
    if (chain.lastOrNull()?.id == root.id) chain.forEach { keep += it.id }
    return topics.filter { it.id in keep } to top.withIndex().associate { (i, t) -> t.id to i }
}

@Composable
private fun StudioPreview(
    topics: List<MapTopic>, appearance: MapAppearance, targetId: String?, surface: Color, textColor: Color,
    height: androidx.compose.ui.unit.Dp,
) {
    val (previewTopics, indices) = remember(topics, targetId) { studioPreviewTopics(topics, targetId) }
    val geometry = remember(previewTopics, indices, appearance.layout, appearance.nodeWidths) {
        arrange(previewTopics, appearance.layout, nodeWidths = appearance.nodeWidths, rootBranchIndices = indices)
    }
    val background = appearance.backgroundColor?.let { Color(it.toInt()) } ?: surface
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(height)
        .clip(RoundedCornerShape(20.dp)).background(background)
        .border(1.dp, textColor.copy(alpha = .1f), RoundedCornerShape(20.dp))
        .semantics { contentDescription = "Live map preview" }) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val graphWidth = (geometry.right - geometry.left).coerceAtLeast(1f)
            val graphHeight = (geometry.bottom - geometry.top).coerceAtLeast(1f)
            val scale = min(size.width / graphWidth, size.height / graphHeight).coerceIn(.01f, 1.1f)
            drawIntoCanvas { composeCanvas ->
                val native = composeCanvas.nativeCanvas
                native.save()
                native.translate((size.width - graphWidth * scale) / 2f, (size.height - graphHeight * scale) / 2f)
                native.scale(scale, scale)
                native.translate(-geometry.left, -geometry.top)
                paintMap(native, geometry, appearance,
                    RectF(geometry.left - 20f, geometry.top - 20f, geometry.right + 20f, geometry.bottom + 20f),
                    selectedId = targetId?.takeIf { it in geometry.frames }, showPreviewControls = false)
                native.restore()
            }
        }
        val dark = appearance.backgroundColor?.let { luminance(it.toInt()) < .4 } == true
        Text("LIVE PREVIEW", Modifier.align(Alignment.TopStart).padding(10.dp),
            color = if (dark) Color.White.copy(alpha = .7f) else textColor.copy(alpha = .45f),
            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

// ------------------------------------------------------------------------------------ palette tab

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteTab(
    topics: List<MapTopic>, workstreams: List<MapTopic>,
    appearance: MapAppearance, palette: List<Long>, accent: Color, textColor: Color,
    onAppearance: (MapAppearance) -> Unit,
    ask: (String, Long, (MapAppearance, Long) -> MapAppearance, String?, ((MapAppearance) -> MapAppearance)?) -> Unit,
) {
    val custom = appearance.customPalette.isNotEmpty()
    StudioCard("Your palette", "These colours paint the map's boxes. Tap one to change it.", textColor) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            palette.forEachIndexed { index, c ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StudioSwatch(c, 42.dp, false, textColor, "Palette colour ${index + 1}") {
                        ask("Palette colour ${index + 1}", c, { a, n ->
                            a.copy(customPalette = a.activePalette().toMutableList().also { list ->
                                if (index in list.indices) list[index] = n
                            })
                        }, if (palette.size > MAP_PALETTE_MIN) "Remove" else null, { a ->
                            a.copy(customPalette = a.activePalette().toMutableList().also { list ->
                                if (index in list.indices && list.size > MAP_PALETTE_MIN) list.removeAt(index)
                            })
                        })
                    }
                    Text("${index + 1}", fontSize = 10.sp, color = textColor.copy(alpha = .5f))
                }
            }
            if (palette.size < MAP_PALETTE_MAX) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(42.dp).clip(CircleShape).border(1.5.dp, textColor.copy(alpha = .25f), CircleShape)
                    .semantics { contentDescription = "Add a colour to the palette" }
                    .clickable {
                        val last = argbToHsv(palette.last())
                        ask("Add a colour", hsvToArgb(last[0] + 47f, last[1].coerceAtLeast(.45f), last[2].coerceAtLeast(.6f)),
                            { a, n -> a.copy(customPalette = (a.activePalette() + n).take(MAP_PALETTE_MAX)) }, null, null)
                    }, contentAlignment = Alignment.Center) {
                    Text("+", fontSize = 20.sp, color = textColor.copy(alpha = .55f))
                }
                Text("Add", fontSize = 10.sp, color = textColor.copy(alpha = .5f))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioChip("Shuffle", false, accent, textColor) {
                var next = palette.shuffled()
                if (palette.distinct().size > 1) while (next == palette) next = palette.shuffled()
                onAppearance(appearance.copy(customPalette = next))
            }
            StudioChip("Reverse", false, accent, textColor) { onAppearance(appearance.copy(customPalette = palette.reversed())) }
            val saved = appearance.savedPalettes.any { it.colors == palette }
            StudioChip(if (saved) "Saved" else "Save to My palettes", saved, accent, textColor) {
                if (!saved) onAppearance(appearance.copy(savedPalettes = (appearance.savedPalettes +
                    MapSavedPalette("My palette ${appearance.savedPalettes.size + 1}", palette)).takeLast(24)))
            }
        }
    }
    BoxPaletteCards(topics, workstreams, appearance, palette, accent, textColor, onAppearance, ask)
    StudioCard("Presets", null, textColor) {
        mapPresetPalettes.withIndex().chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (index, preset) ->
                    val chosen = !custom && appearance.paletteIndex.mod(mapPresetPalettes.size) == index
                    PaletteTile(preset.name, preset.colors, chosen, accent, textColor, Modifier.weight(1f)) {
                        onAppearance(appearance.copy(paletteIndex = index, customPalette = emptyList()))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (appearance.savedPalettes.isNotEmpty()) StudioCard("My palettes", "Saved with this map.", textColor) {
        appearance.savedPalettes.forEachIndexed { index, saved ->
            val chosen = custom && appearance.customPalette == saved.colors
            Row(verticalAlignment = Alignment.CenterVertically) {
                PaletteTile(saved.name, saved.colors, chosen, accent, textColor, Modifier.weight(1f)) {
                    onAppearance(appearance.copy(customPalette = saved.colors))
                }
                IconButton(onClick = {
                    onAppearance(appearance.copy(savedPalettes = appearance.savedPalettes.filterIndexed { i, _ -> i != index }))
                }) { Icon(Icons.Filled.Close, "Delete " + saved.name, tint = textColor.copy(alpha = .5f)) }
            }
        }
    }
    var base by remember { mutableLongStateOf(palette.first()) }
    StudioCard("Generate from one colour", "Pick a starting colour and choose a harmony.", textColor) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudioSwatch(base, 40.dp, false, textColor, "Starting colour") {
                ask("Starting colour", base, { a, n -> base = n; a }, null, null)
            }
            Text("Starting colour  #" + hexOf(base), Modifier.padding(start = 12.dp), color = textColor, fontSize = 13.sp)
        }
        MapHarmony.entries.forEach { harmony ->
            val colours = harmonyPalette(base, harmony)
            PaletteTile(harmony.label, colours, custom && appearance.customPalette == colours, accent, textColor) {
                onAppearance(appearance.copy(customPalette = colours))
            }
        }
    }
    var pasted by rememberSaveable { mutableStateOf("") }
    val imported = remember(pasted) { parsePaletteText(pasted) }
    StudioCard("Import a palette",
        "Paste hex codes, or a link from Coolors or Color Hunt. Only the text is read; nothing is downloaded.", textColor) {
        OutlinedTextField(pasted, { pasted = it.take(2000) }, Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. coolors.co/264653-2a9d8f-e9c46a-f4a261-e76f51", fontSize = 12.sp) },
            maxLines = 3)
        when {
            imported.size >= MAP_PALETTE_MIN -> {
                Text("Found ${imported.size} colours", color = textColor.copy(alpha = .6f), fontSize = 12.sp)
                PaletteStrip(imported, Modifier.fillMaxWidth().height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAppearance(appearance.copy(customPalette = imported)) }, Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text("Use palette", maxLines = 1) }
                    OutlinedButton(onClick = {
                        onAppearance(appearance.copy(customPalette = imported, savedPalettes = (appearance.savedPalettes +
                            MapSavedPalette("Imported ${appearance.savedPalettes.size + 1}", imported)).takeLast(24)))
                        pasted = ""
                    }, Modifier.weight(1f)) { Text("Use & save", maxLines = 1) }
                }
            }
            pasted.isNotBlank() -> Text(
                if (imported.size == 1) "Found one colour. A palette needs at least two." else "No colours found in that text yet.",
                color = textColor.copy(alpha = .6f), fontSize = 12.sp)
        }
    }
}

/** How the palette reaches the boxes, and boxes (with their branch) that take a colour of their own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxPaletteCards(
    topics: List<MapTopic>, workstreams: List<MapTopic>,
    appearance: MapAppearance, palette: List<Long>, accent: Color, textColor: Color,
    onAppearance: (MapAppearance) -> Unit,
    ask: (String, Long, (MapAppearance, Long) -> MapAppearance, String?, ((MapAppearance) -> MapAppearance)?) -> Unit,
) {
    fun ownBranch(id: String, title: String, current: Long) = ask(title, current,
        { a, n -> a.copy(boxBranchColors = a.boxBranchColors + (id to n)) }, null, null)
    val mode = appearance.boxPaletteMode
    StudioCard("How boxes use this palette",
        "Branch lines have their own colours in Branches. A highlight from Organize stays on its one box.", textColor) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapBoxPaletteMode.entries.forEach { m ->
                StudioChip(m.label, mode == m, accent, textColor) { onAppearance(appearance.copy(boxPaletteMode = m)) }
            }
        }
        Text(when (mode) {
            MapBoxPaletteMode.WORKSTREAM -> "Each workstream box takes the next palette colour; its tasks and subtasks follow it."
            MapBoxPaletteMode.LEVEL -> "The project, workstreams, tasks and subtasks each take their own colour."
            MapBoxPaletteMode.SINGLE -> "Every box shares one colour."
            MapBoxPaletteMode.EVERY -> "Boxes step through the palette one after another."
            MapBoxPaletteMode.BRANCHES -> "Each box takes the colour of the branch line leading into it."
        }, color = textColor.copy(alpha = .62f), fontSize = 12.sp)
        when (mode) {
            MapBoxPaletteMode.WORKSTREAM -> {
                val project = appearance.boxLevelColors[0]
                ColourRow("Project box", if (project != null) "Own colour" else "Palette colour 1", appearance.boxLevelColor(0),
                    project != null, textColor, onReset = { onAppearance(appearance.copy(boxLevelColors = appearance.boxLevelColors - 0)) }) {
                    ask("Project box", appearance.boxLevelColor(0), { a, n -> a.copy(boxLevelColors = a.boxLevelColors + (0 to n)) }, null, null)
                }
                workstreams.forEachIndexed { index, workstream ->
                    val own = appearance.boxBranchColors[workstream.id]
                    val current = own ?: palette[index % palette.size]
                    ColourRow(workstream.title, if (own != null) "Own colour · whole branch" else "Palette colour ${index % palette.size + 1}",
                        current, own != null, textColor,
                        onReset = { onAppearance(appearance.copy(boxBranchColors = appearance.boxBranchColors - workstream.id)) }) {
                        ownBranch(workstream.id, workstream.title, current)
                    }
                }
            }
            MapBoxPaletteMode.LEVEL -> mapBoxLevelNames.forEachIndexed { level, name ->
                val own = appearance.boxLevelColors[level]
                ColourRow(name, if (own != null) "Own colour" else "Palette colour ${level % palette.size + 1}",
                    appearance.boxLevelColor(level), own != null, textColor,
                    onReset = { onAppearance(appearance.copy(boxLevelColors = appearance.boxLevelColors - level)) }) {
                    ask(name, appearance.boxLevelColor(level), { a, n -> a.copy(boxLevelColors = a.boxLevelColors + (level to n)) }, null, null)
                }
            }
            MapBoxPaletteMode.SINGLE -> ColourRow("Box colour", null, appearance.boxSingle(), appearance.boxSingleColor != null,
                textColor, onReset = { onAppearance(appearance.copy(boxSingleColor = null)) }) {
                ask("Box colour", appearance.boxSingle(), { a, n -> a.copy(boxSingleColor = n) }, null, null)
            }
            else -> {}
        }
        Text("Colour strength", color = textColor.copy(alpha = .6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapBoxStrength.entries.forEach { s ->
                StudioChip(s.label, appearance.boxStrength == s, accent, textColor) { onAppearance(appearance.copy(boxStrength = s)) }
            }
        }
        if (appearance.boxFill != MapFillMode.SOFT) Text(
            "Boxes ▸ Fill is \"${appearance.boxFill.label}\", which sets how much colour boxes show.",
            color = textColor.copy(alpha = .55f), fontSize = 11.sp)
    }

    var find by rememberSaveable { mutableStateOf("") }
    val byId = remember(topics) { topics.associateBy { it.id } }
    fun depthOf(t: MapTopic): Int = generateSequence(t) { it.parentId?.let(byId::get) }.take(64).count() - 1
    val owned = topics.filter { it.id in appearance.boxBranchColors }
    val shown = remember(topics, find, appearance.boxBranchColors) {
        val matches = if (find.isBlank()) owned else topics.filter { it.title.contains(find, true) }
        matches.take(8)
    }
    StudioCard("Colour a branch",
        "Give a workstream, task or subtask box its own colour. Everything under it follows.", textColor,
        trailing = {
            if (owned.isNotEmpty()) TextButton(onClick = { onAppearance(appearance.copy(boxBranchColors = emptyMap())) }) {
                Text("Reset all", color = accent, fontSize = 12.sp)
            }
        }) {
        OutlinedTextField(find, { find = it.take(80) }, Modifier.fillMaxWidth(),
            placeholder = { Text("Find a box to colour", fontSize = 13.sp) }, singleLine = true)
        shown.forEach { topic ->
            val own = appearance.boxBranchColors[topic.id]
            val depth = depthOf(topic)
            val kind = if (depth >= 3) "Subtask" else topic.kind.name.lowercase().replaceFirstChar { it.uppercase() }
            Box(Modifier.padding(start = (depth.coerceAtMost(4) * 10).dp)) {
                ColourRow(topic.title, if (own != null) "$kind · own colour · whole branch" else "$kind · tap to colour its branch",
                    own ?: 0xFFFFFFFF, own != null, textColor, emptyLabel = own == null,
                    onReset = { onAppearance(appearance.copy(boxBranchColors = appearance.boxBranchColors - topic.id)) }) {
                    ownBranch(topic.id, topic.title, own ?: palette.first())
                }
            }
        }
        if (find.isNotBlank() && shown.isEmpty()) Text("No boxes match.", color = textColor.copy(alpha = .55f), fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------------------------ branches tab

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchesTab(
    appearance: MapAppearance, palette: List<Long>, workstreams: List<MapTopic>, accent: Color, textColor: Color,
    onAppearance: (MapAppearance) -> Unit,
    ask: (String, Long, (MapAppearance, Long) -> MapAppearance, String?, ((MapAppearance) -> MapAppearance)?) -> Unit,
) {
    StudioCard("How branches are coloured", null, textColor) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MapBranchMode.entries.forEach { mode ->
                val chosen = appearance.branchMode == mode
                Surface(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .clickable { onAppearance(appearance.copy(branchMode = mode)) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (chosen) accent.copy(alpha = .1f) else Color.White,
                    border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) accent else textColor.copy(alpha = .12f))) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val sample = when (mode) {
                            MapBranchMode.MULTI -> appearance.branchLinePalette().take(3)
                            MapBranchMode.SINGLE -> List(3) { appearance.singleColor() }
                            MapBranchMode.LEVELS -> (1..3).map { appearance.levelColor(it) }
                        }
                        BranchGlyph(sample, mode)
                        Text(mode.label, Modifier.padding(top = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = if (chosen) accent else textColor)
                    }
                }
            }
        }
        Text(when (appearance.branchMode) {
            MapBranchMode.MULTI -> "Every workstream line gets the next colour below. Its tasks and subtasks follow it."
            MapBranchMode.SINGLE -> "All branches share one colour."
            MapBranchMode.LEVELS -> "Each depth has its own colour, across every workstream."
        }, color = textColor.copy(alpha = .62f), fontSize = 12.sp)
        when (appearance.branchMode) {
            MapBranchMode.SINGLE -> ColourRow("Branch colour", null, appearance.singleColor(), appearance.singleBranchColor != null,
                textColor, onReset = { onAppearance(appearance.copy(singleBranchColor = null)) }) {
                ask("Branch colour", appearance.singleColor(), { a, n -> a.copy(singleBranchColor = n) }, null, null)
            }
            MapBranchMode.LEVELS -> mapLevelNames.forEachIndexed { index, name ->
                val current = appearance.levelColor(index + 1)
                ColourRow(name, if (index == mapLevelNames.lastIndex) "Everything below subtasks" else null, current,
                    appearance.levelColors.getOrNull(index) != null && appearance.levelColors[index] != palette[index % palette.size],
                    textColor, onReset = { onAppearance(appearance.copy(levelColors = appearance.withLevel(index, null))) }) {
                    ask(name, current, { a, n -> a.copy(levelColors = a.withLevel(index, n)) }, null, null)
                }
            }
            MapBranchMode.MULTI -> {
                val lines = appearance.branchLinePalette()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    lines.forEachIndexed { index, c ->
                        StudioSwatch(c, 34.dp, false, textColor, "Line colour ${index + 1}") {
                            ask("Line colour ${index + 1}", c, { a, n ->
                                a.copy(branchPalette = a.branchLinePalette().toMutableList().also { list ->
                                    if (index in list.indices) list[index] = n
                                })
                            }, null, null)
                        }
                    }
                }
                if (appearance.branchPalette.isEmpty()) Text("Following your palette. Tap a colour to give lines their own.",
                    color = textColor.copy(alpha = .55f), fontSize = 11.sp)
                else StudioChip("Follow the palette again", false, accent, textColor) {
                    onAppearance(appearance.copy(branchPalette = emptyList()))
                }
            }
        }
    }
    val ids = workstreams.map { it.id }.toSet()
    val anyOverride = appearance.branchColors.keys.any { it in ids }
    StudioCard("Workstream colours",
        "Give any workstream its own colour. It wins over the setting above for that whole branch.", textColor,
        trailing = {
            if (anyOverride) TextButton(onClick = { onAppearance(appearance.copy(branchColors = appearance.branchColors - ids)) }) {
                Text("Reset all", color = accent, fontSize = 12.sp)
            }
        }) {
        if (workstreams.isEmpty()) Text("This project has no workstreams yet.", color = textColor.copy(alpha = .55f), fontSize = 12.sp)
        workstreams.forEachIndexed { index, workstream ->
            val override = appearance.branchColors[workstream.id]
            val current = override ?: when (appearance.branchMode) {
                MapBranchMode.MULTI -> appearance.branchLinePalette().let { it[index % it.size] }
                MapBranchMode.SINGLE -> appearance.singleColor()
                MapBranchMode.LEVELS -> appearance.levelColor(1)
            }
            ColourRow(workstream.title, if (override != null) "Own colour" else "Follows " + appearance.branchMode.label.lowercase(),
                current, override != null, textColor,
                onReset = { onAppearance(appearance.copy(branchColors = appearance.branchColors - workstream.id)) }) {
                ask(workstream.title, current, { a, n -> a.copy(branchColors = a.branchColors + (workstream.id to n)) }, null, null)
            }
        }
    }
}

private fun MapAppearance.withLevel(index: Int, colour: Long?): List<Long> {
    val filled = (0 until maxOf(levelColors.size, index + 1)).map { levelColors.getOrNull(it) ?: levelColor(it + 1) }
        .toMutableList()
    if (colour != null) filled[index] = colour
    else { val p = activePalette(); filled[index] = p[index % p.size] }
    return filled.take(mapLevelNames.size)
}

@Composable
private fun BranchGlyph(colours: List<Long>, mode: MapBranchMode) {
    Canvas(Modifier.size(width = 54.dp, height = 34.dp)) {
        val stroke = 2.5.dp.toPx()
        val start = Offset(4.dp.toPx(), size.height / 2f)
        val ends = listOf(.15f, .5f, .85f).map { Offset(size.width * .55f, size.height * it) }
        ends.forEachIndexed { i, end ->
            val c = Color(colours[i % colours.size].toInt())
            drawLine(if (mode == MapBranchMode.LEVELS) Color(colours[0].toInt()) else c, start, end, stroke)
            val leaf = Offset(size.width - 4.dp.toPx(), end.y)
            drawLine(if (mode == MapBranchMode.LEVELS) Color(colours[1 % colours.size].toInt()) else c, end, leaf, stroke)
            drawCircle(c, 3.dp.toPx(), leaf)
        }
    }
}

// ------------------------------------------------------------------------------------ boxes tab

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxesTab(
    appearance: MapAppearance, palette: List<Long>, surface: Color, accent: Color, textColor: Color,
    onAppearance: (MapAppearance) -> Unit,
    ask: (String, Long, (MapAppearance, Long) -> MapAppearance, String?, ((MapAppearance) -> MapAppearance)?) -> Unit,
) {
    val tint = palette.first().toInt()
    StudioCard("Box fill", "How strongly boxes take their branch colour.", textColor) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapFillMode.entries.forEach { mode ->
                val chosen = appearance.boxFill == mode
                OptionTile(mode.label, chosen, accent, textColor, preview = {
                    MiniBox(boxColors(appearance.copy(boxFill = mode), tint, false, false, false, false))
                }) {
                    if (mode == MapFillMode.CUSTOM) ask("Box fill", appearance.boxFillColor ?: 0xFFFFF4D6,
                        { a, n -> a.copy(boxFill = MapFillMode.CUSTOM, boxFillColor = n) }, null, null)
                    else onAppearance(appearance.copy(boxFill = mode))
                }
            }
        }
    }
    StudioCard("Box border", null, textColor) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapBorderMode.entries.forEach { mode ->
                OptionTile(mode.label, appearance.boxBorder == mode, accent, textColor, preview = {
                    MiniBox(boxColors(appearance.copy(boxBorder = mode), tint, false, false, false, false))
                }) {
                    if (mode == MapBorderMode.CUSTOM) ask("Border colour", appearance.boxBorderColor ?: 0xFF33404C,
                        { a, n -> a.copy(boxBorder = MapBorderMode.CUSTOM, boxBorderColor = n) }, null, null)
                    else onAppearance(appearance.copy(boxBorder = mode))
                }
            }
        }
    }
    StudioCard("Text", "Automatic text stays readable on light and dark boxes.", textColor) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioChip("Automatic", appearance.boxTextColor == null, accent, textColor) {
                onAppearance(appearance.copy(boxTextColor = null))
            }
            StudioChip(appearance.boxTextColor?.let { "Custom #" + hexOf(it) } ?: "Custom colour",
                appearance.boxTextColor != null, accent, textColor) {
                ask("Text colour", appearance.boxTextColor ?: 0xFF192229, { a, n -> a.copy(boxTextColor = n) }, null, null)
            }
        }
    }
    StudioCard("Map background", "Also used for PNG and PDF exports.", textColor) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(surface)
                    .border(if (appearance.backgroundColor == null) 3.dp else 1.dp,
                        if (appearance.backgroundColor == null) accent else textColor.copy(alpha = .15f), CircleShape)
                    .semantics { contentDescription = "Default background" }
                    .clickable { onAppearance(appearance.copy(backgroundColor = null)) })
                Text("Default", fontSize = 10.sp, color = textColor.copy(alpha = .55f))
            }
            backgroundSuggestions.forEach { c ->
                StudioSwatch(c, 38.dp, appearance.backgroundColor == c, accent, "Background") {
                    onAppearance(appearance.copy(backgroundColor = c))
                }
            }
            Box(Modifier.size(38.dp).clip(CircleShape).border(1.5.dp, textColor.copy(alpha = .25f), CircleShape)
                .semantics { contentDescription = "Custom background colour" }
                .clickable {
                    ask("Map background", appearance.backgroundColor ?: 0xFFF7F3EA,
                        { a, n -> a.copy(backgroundColor = n) }, null, null)
                }, contentAlignment = Alignment.Center) {
                Text("+", fontSize = 18.sp, color = textColor.copy(alpha = .55f))
            }
        }
    }
    StudioCard("Box finish", "The surface style of every box.", textColor) {
        MapBoxFinishGroup.entries.forEach { group ->
        Column(Modifier.padding(top = if (group.ordinal == 0) 0.dp else 8.dp)) {
            Text(group.label, color = textColor.copy(alpha = .75f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(group.detail, color = textColor.copy(alpha = .5f), fontSize = 11.sp)
        }
        MapBoxFinish.entries.filter { it.group == group }.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { finish ->
                    FinishTile(finish, appearance.boxFinish == finish, accent, textColor, Modifier.weight(1f)) {
                        onAppearance(appearance.copy(boxFinish = finish))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        }
    }
}

@Composable
private fun MiniBox(colours: MapBoxColors) {
    Canvas(Modifier.size(width = 44.dp, height = 26.dp)) {
        val radius = CornerRadius(7.dp.toPx())
        drawRoundRect(Brush.linearGradient(colours.fill.map { Color(it) }), cornerRadius = radius)
        colours.stroke?.let { s ->
            drawRoundRect(Color(s).copy(alpha = colours.strokeAlpha / 255f), cornerRadius = radius, style = Stroke(1.5.dp.toPx()))
        }
        drawLine(Color(colours.title).copy(alpha = .7f), Offset(8.dp.toPx(), size.height / 2f),
            Offset(size.width * .65f, size.height / 2f), 2.5.dp.toPx())
    }
}

@Composable
private fun FinishTile(finish: MapBoxFinish, chosen: Boolean, accent: Color, textColor: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), shape = RoundedCornerShape(14.dp),
        color = if (chosen) accent.copy(alpha = .1f) else Color.White,
        border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) accent else textColor.copy(alpha = .12f))) {
        Column(Modifier.padding(8.dp)) {
            val gradient = when (finish) {
                MapBoxFinish.GLASS -> listOf(Color.White, Color(0xFF9BD8B8), Color(0xFFE6F8E9))
                MapBoxFinish.PAPER -> listOf(Color(0xFFF7F3E7), Color(0xFFDFE9D7))
                MapBoxFinish.EMBOSSED -> listOf(Color.White, Color(0xFFB9D8C7))
                MapBoxFinish.FROSTED -> listOf(Color.White, Color(0xFFD9E7F4))
                MapBoxFinish.OUTLINE -> listOf(Color.White, Color.White)
                else -> if (finish.isHandDrawn()) listOf(Color.White, Color.White) else listOf(Color(0xFFE7F5EA), Color(0xFFCDE8D6))
            }
            Box(Modifier.fillMaxWidth().height(40.dp)
                .background(Brush.linearGradient(gradient), RoundedCornerShape(9.dp))
                .border(if (finish.isHandDrawn()) 0.dp else if (finish == MapBoxFinish.OUTLINE) 2.dp else 1.dp,
                    accent.copy(alpha = .55f), RoundedCornerShape(9.dp))) {
                if (finish.isHandDrawn()) Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas {
                        drawHandDrawnBox(it.nativeCanvas, RectF(3f, 3f, size.width - 3f, size.height - 3f),
                            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), finish,
                            paletteTint = accent.toArgb())
                    }
                }
                if (finish.isMaterial()) Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas {
                        val box = RectF(0f, 0f, size.width, size.height)
                        it.nativeCanvas.save()
                        it.nativeCanvas.clipPath(android.graphics.Path().apply {
                            addRoundRect(box, 9.dp.toPx(), 9.dp.toPx(), android.graphics.Path.Direction.CW)
                        })
                        drawMaterialTexture(it.nativeCanvas, box,
                            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), finish, accent.toArgb())
                        it.nativeCanvas.restore()
                    }
                }
                if (finish == MapBoxFinish.PENCIL_HATCH) Canvas(Modifier.fillMaxSize()) {
                    val spacing = 7.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(accent.copy(alpha = .24f), Offset(x, size.height), Offset(x + size.height, 0f), 1.dp.toPx())
                        x += spacing
                    }
                }
                if (finish == MapBoxFinish.LINEN || finish == MapBoxFinish.GRAIN || finish == MapBoxFinish.PAPER)
                    Canvas(Modifier.fillMaxSize()) {
                        val step = if (finish == MapBoxFinish.GRAIN) 8.dp.toPx() else 6.dp.toPx()
                        var x = step
                        while (x < size.width) {
                            var y = step
                            while (y < size.height) {
                                if (finish == MapBoxFinish.GRAIN) drawCircle(accent.copy(alpha = .22f), 1.dp.toPx(), Offset(x, y))
                                else drawLine(accent.copy(alpha = .16f), Offset(x, y), Offset(x + 2.dp.toPx(), y), 1.dp.toPx())
                                y += step
                            }
                            if (finish == MapBoxFinish.LINEN)
                                drawLine(accent.copy(alpha = .1f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                            x += step
                        }
                    }
            }
            Text(finish.label, Modifier.padding(top = 6.dp), color = if (chosen) accent else textColor,
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------------------------ items tab

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemsTab(
    topics: List<MapTopic>, appearance: MapAppearance, targetId: String?, search: String,
    accent: Color, textColor: Color, onSearch: (String) -> Unit, onTarget: (String) -> Unit,
    onAppearance: (MapAppearance) -> Unit,
    ask: (String, Long, (MapAppearance, Long) -> MapAppearance, String?, ((MapAppearance) -> MapAppearance)?) -> Unit,
) {
    val target = topics.firstOrNull { it.id == targetId }
    fun styled(t: MapTopic) = t.id in appearance.nodeColors || t.id in appearance.branchColors || t.id in appearance.nodeFinishes
    val visible = remember(topics, search, targetId, appearance.nodeColors, appearance.branchColors, appearance.nodeFinishes) {
        val matches = if (search.isBlank()) topics.filter(::styled) + topics else topics.filter { it.title.contains(search, true) }
        (listOfNotNull(target) + matches).distinctBy { it.id }.take(10)
    }
    StudioCard("Style one item", "Pick a project, workstream, task or subtask, then colour just that box or its whole branch.", textColor) {
        OutlinedTextField(search, onSearch, Modifier.fillMaxWidth(),
            placeholder = { Text("Search items", fontSize = 13.sp) }, singleLine = true)
        visible.forEach { topic ->
            val chosen = topic.id == targetId
            Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onTarget(topic.id) },
                shape = RoundedCornerShape(12.dp),
                color = if (chosen) accent.copy(alpha = .1f) else Color.White,
                border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) accent else textColor.copy(alpha = .1f))) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(topic.title, color = textColor, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(topic.kind.name.lowercase().replaceFirstChar { it.uppercase() } +
                            if (styled(topic)) " · styled" else "", fontSize = 11.sp, color = textColor.copy(alpha = .55f))
                    }
                    appearance.branchColors[topic.id]?.let { StudioSwatch(it, 18.dp, false, textColor, "Branch colour", null) }
                    appearance.nodeColors[topic.id]?.let {
                        Spacer(Modifier.width(4.dp)); StudioSwatch(it, 18.dp, false, textColor, "Box colour", null)
                    }
                }
            }
        }
        if (search.isNotBlank() && visible.isEmpty()) Text("No items match.", color = textColor.copy(alpha = .55f), fontSize = 12.sp)
    }
    if (target != null) StudioCard(target.title, null, textColor) {
        val box = appearance.nodeColors[target.id]
        ColourRow("Box colour", "Only this box", box ?: 0xFFFFFFFF, box != null, textColor,
            onReset = { onAppearance(appearance.copy(nodeColors = appearance.nodeColors - target.id)) }, emptyLabel = box == null) {
            ask("Box colour", box ?: appearance.activePalette().first(),
                { a, n -> a.copy(nodeColors = a.nodeColors + (target.id to n)) }, null, null)
        }
        val branch = appearance.branchColors[target.id]
        ColourRow("Branch colour", "This item and everything under it", branch ?: 0xFFFFFFFF, branch != null, textColor,
            onReset = { onAppearance(appearance.copy(branchColors = appearance.branchColors - target.id)) }, emptyLabel = branch == null) {
            ask("Branch colour", branch ?: appearance.activePalette().first(),
                { a, n -> a.copy(branchColors = a.branchColors + (target.id to n)) }, null, null)
        }
        Text("Finish", color = textColor.copy(alpha = .6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioChip("Map default", target.id !in appearance.nodeFinishes, accent, textColor) {
                onAppearance(appearance.copy(nodeFinishes = appearance.nodeFinishes - target.id))
            }
            MapBoxFinish.entries.forEach { finish ->
                StudioChip(finish.label, appearance.nodeFinishes[target.id] == finish, accent, textColor) {
                    onAppearance(appearance.copy(nodeFinishes = appearance.nodeFinishes + (target.id to finish)))
                }
            }
        }
        if (styled(target)) TextButton(onClick = {
            onAppearance(appearance.copy(nodeColors = appearance.nodeColors - target.id,
                branchColors = appearance.branchColors - target.id, nodeFinishes = appearance.nodeFinishes - target.id))
        }) { Text("Clear this item's styling", color = Color(0xFFB4483C)) }
    }
}

// ------------------------------------------------------------------------------------ shared pieces

@Composable
private fun StudioCard(
    title: String, detail: String?, textColor: Color,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White,
        border = BorderStroke(1.dp, textColor.copy(alpha = .07f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    detail?.let { Text(it, color = textColor.copy(alpha = .6f), fontSize = 12.sp) }
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
private fun StudioSwatch(argb: Long, size: androidx.compose.ui.unit.Dp, selected: Boolean, textColor: Color,
                         label: String, onClick: (() -> Unit)?) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(argb.toInt()))
        .border(if (selected) 3.dp else 1.dp, if (selected) textColor else Color.Black.copy(alpha = .12f), CircleShape)
        .semantics { contentDescription = label + " #" + hexOf(argb) }
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier))
}

@Composable
private fun StudioChip(label: String, selected: Boolean, accent: Color, textColor: Color, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(12.dp))
        .background(if (selected) accent.copy(alpha = .12f) else textColor.copy(alpha = .04f))
        .border(1.dp, if (selected) accent else textColor.copy(alpha = .1f), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) accent else textColor)
    }
}

@Composable
private fun OptionTile(label: String, selected: Boolean, accent: Color, textColor: Color,
                       preview: @Composable () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(92.dp).clip(RoundedCornerShape(14.dp))
        .background(if (selected) accent.copy(alpha = .1f) else textColor.copy(alpha = .03f))
        .border(if (selected) 2.dp else 1.dp, if (selected) accent else textColor.copy(alpha = .1f), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        preview()
        Text(label, Modifier.padding(top = 6.dp), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) accent else textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PaletteTile(name: String, colours: List<Long>, chosen: Boolean, accent: Color, textColor: Color,
                        modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (chosen) accent.copy(alpha = .1f) else Color.White)
        .border(if (chosen) 2.dp else 1.dp, if (chosen) accent else textColor.copy(alpha = .1f), RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(10.dp)) {
        PaletteStrip(colours, Modifier.fillMaxWidth().height(22.dp))
        Text(name, Modifier.padding(top = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (chosen) accent else textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PaletteStrip(colours: List<Long>, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(8.dp))) {
        colours.forEach { c -> Box(Modifier.weight(1f).fillMaxSize().background(Color(c.toInt()))) }
    }
}

@Composable
private fun ColourRow(
    title: String, detail: String?, colour: Long, overridden: Boolean, textColor: Color,
    onReset: () -> Unit, emptyLabel: Boolean = false, onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (emptyLabel) Box(Modifier.size(34.dp).clip(CircleShape)
            .border(1.5.dp, textColor.copy(alpha = .25f), CircleShape), contentAlignment = Alignment.Center) {
            Text("+", color = textColor.copy(alpha = .5f))
        } else StudioSwatch(colour, 34.dp, false, textColor, title, null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail ?: if (emptyLabel) "Automatic" else "#" + hexOf(colour),
                color = textColor.copy(alpha = .55f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (overridden) IconButton(onClick = onReset) {
            Icon(Icons.Filled.Close, "Reset " + title, tint = textColor.copy(alpha = .5f))
        }
    }
}
