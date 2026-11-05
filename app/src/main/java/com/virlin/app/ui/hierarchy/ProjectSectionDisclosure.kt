package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.components.avoidOrbCircle
import com.virlin.app.ui.components.circularOrbCutout

internal fun projectSectionWash(accent: Color, strength: Float) = lerp(Color.White, accent, strength)

/** Add and disclosure are separate actions; never turn Add into an expand/collapse click. */
@Composable
internal fun ProjectSectionDisclosure(title: String, icon: ImageVector, expanded: Boolean,
    tag: String, onToggle: () -> Unit, onAdd: () -> Unit, addTag: String? = null) {
    val palette = LocalProjectPalette.current
    val accent = palette?.let { Color(it.main) } ?: Color(0xFFE9B200)
    val ink = palette?.let { Color(it.text) } ?: Color(0xFF17202C)
    val accentInk = palette?.let { Color(it.secondary) } ?: Color(0xFF806000)
    Surface(color = projectSectionWash(accent, 0.055f), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().circularOrbCutout()) {
        Row(Modifier.fillMaxWidth().avoidOrbCircle().padding(start = 10.dp, end = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(projectSectionWash(accent, 0.17f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accentInk, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onAdd, modifier = if (addTag != null) Modifier.testTag(addTag) else Modifier) {
                Text("+ Add", color = accentInk)
            }
            IconButton(onClick = onToggle, modifier = Modifier.testTag(tag).semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title", tint = ink)
            }
        }
    }
}
