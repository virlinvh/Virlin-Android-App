package com.virlin.app.ui.hierarchy

import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.*
import org.json.JSONObject

/** Display-only palette; execution/status tokens deliberately do not consult this object. */
data class ProjectPalette(val main: Int, val primary: Int, val secondary: Int, val tertiary: Int,
    val surface: Int = -1, val text: Int = 0xFF17202C.toInt(), val muted: Int = 0xFF526171.toInt()) {
    fun encode() = JSONObject().apply {
        put("main", main); put("primary", primary); put("secondary", secondary); put("tertiary", tertiary)
        put("surface", surface); put("text", text); put("muted", muted)
    }.toString()
    companion object {
        fun decode(raw: String?): ProjectPalette? = runCatching {
            val j = JSONObject(raw ?: return null)
            ProjectPalette(j.getInt("main"), j.getInt("primary"), j.getInt("secondary"), j.getInt("tertiary"),
                j.getInt("surface"), j.getInt("text"), j.getInt("muted"))
        }.getOrNull()
        fun from(color: Color): ProjectPalette {
            fun blend(other: Color, amount: Float) = lerp(color, other, amount).toArgb()
            return ProjectPalette(color.toArgb(), color.toArgb(), blend(Color.Black, .40f), blend(Color.White, .88f))
        }
    }
}

val LocalProjectPalette = staticCompositionLocalOf<ProjectPalette?> { null }
@Composable internal fun rememberProjectPalette(id: String?): ProjectPalette? {
    val prefs = LocalContext.current.getSharedPreferences("project_appearance", 0)
    var raw by remember(id) { mutableStateOf(id?.let { prefs.getString(it, null) }) }
    DisposableEffect(id, prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { store, key -> if (key == id) raw = store.getString(key, null) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(raw) { ProjectPalette.decode(raw) }
}

internal val projectColorChoices = listOf(
    "Leaf" to 0xFF65BB31, "Forest" to 0xFF166534, "Emerald" to 0xFF059669, "Mint" to 0xFF34D399,
    "Teal" to 0xFF0F766E, "Cyan" to 0xFF0891B2, "Sky" to 0xFF0284C7, "Ocean" to 0xFF2563EB,
    "Navy" to 0xFF1E3A8A, "Indigo" to 0xFF4F46E5, "Violet" to 0xFF7C3AED, "Plum" to 0xFF9333EA,
    "Orchid" to 0xFFC026D3, "Rose" to 0xFFDB2777, "Berry" to 0xFF9F1239, "Coral" to 0xFFF97360,
    "Terracotta" to 0xFFC2410C, "Amber" to 0xFFD97706, "Gold" to 0xFFEAB308, "Olive" to 0xFF65752F,
    "Cocoa" to 0xFF795548, "Slate" to 0xFF475569, "Charcoal" to 0xFF27272A, "Sand" to 0xFFC2A878)

internal fun readableOn(color: Color): Color = if (color.luminance() > .179f) Color.Black else Color.White

internal fun readablePalette(p: ProjectPalette): Boolean {
    fun contrast(a: Color,b: Color)=(max(a.luminance(),b.luminance())+.05f)/(min(a.luminance(),b.luminance())+.05f)
    val surfaces=listOf(Color(p.surface),Color.White,Color(0xFFEAF8F1),Color(0xFFF1EDFF),Color(0xFFFFF5DB))
    return listOf(Color(p.text),Color(p.muted)).all { text -> surfaces.all {contrast(text,it)>=4.5f} } &&
        contrast(Color(p.secondary),Color.White)>=4.5f && contrast(Color(p.secondary),Color(p.surface))>=4.5f
}

@Composable internal fun ProjectColorsEditor(projectId: String, onDone: () -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("project_appearance", 0)
    var draft by remember(projectId) { mutableStateOf(ProjectPalette.decode(prefs.getString(projectId, null))) }
    var role by remember { mutableIntStateOf(0) }
    var customExpanded by remember(projectId) { mutableStateOf(false) }
    val roleValues = draft?.let { listOf(it.main,it.primary,it.secondary,it.tertiary,it.surface,it.text,it.muted) }
    var hex by remember(role, draft) { mutableStateOf(roleValues?.get(role)?.let { "#%06X".format(it and 0xFFFFFF) } ?: "") }
    val names = listOf("Main", "Primary", "Secondary", "Tertiary", "Surface", "Heading text", "Secondary text")
    val p = draft ?: ProjectPalette.from(Color(0xFF65BB31))
    fun setColor(color: Color) {
        val p = draft ?: ProjectPalette.from(Color(0xFF65BB31))
        val value = color.toArgb()
        draft = when(role) { 0 -> p.copy(main=value); 1 -> p.copy(primary=value); 2 -> p.copy(secondary=value);
            3 -> p.copy(tertiary=value); 4 -> p.copy(surface=value); 5 -> p.copy(text=value); else -> p.copy(muted=value) }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Choose your project palette", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("Start with a preset, then fine-tune the shades below.", color = Color(0xFF526171), style = MaterialTheme.typography.bodyMedium)
            Text("PRESET COLORS", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            projectColorChoices.chunked(4).forEach { row -> Row(Modifier.fillMaxWidth()) {
                row.forEach { (name, value) ->
                    val chosen = p.main == value.toInt()
                    Column(Modifier.weight(1f).padding(3.dp)
                        .background(if (chosen) Color(p.tertiary) else Color.White, RoundedCornerShape(14.dp))
                        .border(if (chosen) 2.dp else 1.dp, if (chosen) Color(p.secondary) else Color(0xFFE2E8E5), RoundedCornerShape(14.dp))
                        .testTag("project_color_$name").semantics { selected = chosen }
                        .clickable { draft = ProjectPalette.from(Color(value)) }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(28.dp).background(Color(value), CircleShape))
                        Text(name, style = MaterialTheme.typography.labelSmall, color = Color(0xFF17202C), maxLines = 1)
                    }
                }
            } }
            Surface(color=Color(p.surface), tonalElevation=0.dp, shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8E5)), modifier = Modifier.padding(vertical = 8.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("Project preview", color=Color(p.text))
                    Text("Progress · selected tab · Add task", color=Color(p.muted))
                    LinearProgressIndicator(progress={.5f}, color=Color(p.main), trackColor=Color(p.tertiary), modifier=Modifier.fillMaxWidth())
                    Button(onClick={}, colors=ButtonDefaults.buttonColors(containerColor=Color(p.primary), contentColor=readableOn(Color(p.primary)))) { Text("Add task") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFFE2E8E5), RoundedCornerShape(16.dp))
                .testTag("project_custom_palette_toggle")
                .semantics { stateDescription = if (customExpanded) "Expanded" else "Collapsed" }
                .clickable { customExpanded = !customExpanded }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Customize your palette", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Custom roles, shades and hex colors", style = MaterialTheme.typography.bodySmall, color = Color(0xFF526171))
                }
                Icon(if (customExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, tint = Color(p.secondary))
            }
            if (customExpanded) {
            Text("Choose the part of the interface you want to change.", color = Color(0xFF526171), style = MaterialTheme.typography.bodySmall)
            val values = listOf(p.main, p.primary, p.secondary, p.tertiary, p.surface, p.text, p.muted)
            names.indices.toList().chunked(2).forEach { indices -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                indices.forEach { i ->
                    Row(Modifier.weight(1f).heightIn(min = 56.dp)
                        .background(if (role == i) Color(p.tertiary) else Color.White, RoundedCornerShape(14.dp))
                        .border(if (role == i) 2.dp else 1.dp, if (role == i) Color(p.secondary) else Color(0xFFE2E8E5), RoundedCornerShape(14.dp))
                        .testTag("project_color_role_$i").semantics { selected = role == i }
                        .clickable { role = i }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).background(Color(values[i]), CircleShape).border(1.dp, Color(0xFFCDD6D0), CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(names[i], style = MaterialTheme.typography.labelMedium, color = Color(0xFF17202C))
                    }
                }
                if (indices.size == 1) Spacer(Modifier.weight(1f))
            } }
            Surface(shape = RoundedCornerShape(18.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8E5))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Editing ${names[role]}", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp))
                    key(role) { ColorWheel(onColor=::setColor) }
                }
            }
            Text("Shades for ${names[role]}")
            Row { listOf(-.4f,-.2f,.2f,.4f,.65f,.85f).forEach { shade ->
                val color=lerp(Color(values[role]),if(shade<0) Color.Black else Color.White,abs(shade))
                Box(Modifier.padding(4.dp).size(36.dp).background(color,CircleShape).clickable { setColor(color) })
            } }
            OutlinedTextField(hex, {hex=it}, label={Text("Hex · #RRGGBB")}, modifier=Modifier.fillMaxWidth())
            TextButton(onClick={ if(hex.matches(Regex("#?[0-9a-fA-F]{6}"))) setColor(Color((0xFF000000L or hex.removePrefix("#").toLong(16)).toInt())) }, enabled=hex.matches(Regex("#?[0-9a-fA-F]{6}"))) { Text("Use hex color") }
            }
            if(!readablePalette(p)) Text("Text or secondary-link contrast is too low. Choose darker text/secondary colors or a lighter surface.")
            Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFF3F6F4)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("About project colors", fontWeight = FontWeight.SemiBold)
                    Text("Colors apply to project accents and appearance. Focus, Running and Review keep their status colors. Changes are only saved when you select Save palette.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF526171))
                }
            }
            TextButton(onClick={draft=null}) { Text("Reset to original") }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            val accent = LocalProjectPalette.current?.let { Color(it.secondary) } ?: Color(0xFF087848)
            OutlinedButton(onClick=onDone,modifier=Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent), contentPadding = PaddingValues(8.dp)) { Text("Discard preview") }
            Button(onClick={ val edit=prefs.edit(); if(draft==null) edit.remove(projectId) else edit.putString(projectId,draft!!.encode()); edit.apply(); onDone() }, enabled=draft==null || readablePalette(p), modifier=Modifier.weight(1f).heightIn(min = 48.dp).testTag("save_project_colors"), colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = readableOn(accent)), contentPadding = PaddingValues(8.dp)) { Text("Save palette") }
        }
    }
}

@Composable private fun ColorWheel(onColor: (Color)->Unit) {
    var brightness by remember { mutableFloatStateOf(.8f) }
    var selectedHue by remember { mutableFloatStateOf(210f) }
    var selectedSaturation by remember { mutableFloatStateOf(.7f) }
    fun pick(position: androidx.compose.ui.geometry.Offset, size: androidx.compose.ui.unit.IntSize) {
        val x=position.x-size.width/2f; val y=position.y-size.height/2f
        val hue=((atan2(y,x)*180f/PI.toFloat())+360f)%360f
        val saturation=(sqrt(x*x+y*y)/(size.width/2f)).coerceIn(0f,1f)
        selectedHue=hue; selectedSaturation=saturation
        onColor(Color.hsv(hue,saturation,brightness))
    }
    Canvas(Modifier.widthIn(max = 220.dp).fillMaxWidth().aspectRatio(1f).pointerInput(brightness) { detectTapGestures {pick(it,size)} }
        .pointerInput(brightness) { detectDragGestures { change,_ -> change.consume();pick(change.position,size) } }) {
        drawCircle(Brush.sweepGradient((0..12).map {Color.hsv((it*30f)%360f,1f,brightness)}))
        drawCircle(Brush.radialGradient(listOf(Color.hsv(0f,0f,brightness),Color.Transparent)))
        val radians=selectedHue*PI.toFloat()/180f
        val marker= center + androidx.compose.ui.geometry.Offset(cos(radians),sin(radians))*(size.width/2f*selectedSaturation)
        drawCircle(Color.Black, 8.dp.toPx(), marker, style=androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
        drawCircle(Color.White, 8.dp.toPx(), marker, style=androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
    }
    Text("Brightness")
    Slider(brightness,{brightness=it; onColor(Color.hsv(selectedHue,selectedSaturation,it))},valueRange=.1f..1f)
}
