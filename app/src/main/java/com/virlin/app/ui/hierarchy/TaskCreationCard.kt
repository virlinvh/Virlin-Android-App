package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared text-first creation surface; the caller retains all creation semantics. */
@Composable
internal fun TaskCreationCard(parentTitle: String, heading: String, onDismiss: () -> Unit,
    onSave: (String) -> Unit, titleTag: String = AddTaskTitleTag,
    confirmTag: String = AddTaskConfirmTag, modeSelector: @Composable () -> Unit = {},
    body: (@Composable () -> Unit)? = null, options: @Composable () -> Unit = {}) {
    var name by rememberSaveable { mutableStateOf("") }
    val palette = LocalProjectPalette.current
    val accent = palette?.let { Color(it.primary) } ?: Color(0xFF65BB31)
    val ink = Color(0xFF17202C)
    val muted = Color(0xFF63717B)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(accent.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, null, tint = palette?.let { Color(it.secondary) } ?: accent, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(heading, color = Color(0xFF46515B), fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Close, "Close task creation", tint = muted, modifier = Modifier.size(18.dp))
            }
        }
        Text(parentTitle, color = muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        modeSelector()
        Spacer(Modifier.height(16.dp))
        if (body != null) {
            body()
        } else {
        Text("Task name", color = muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        TextField(name, { name = it }, placeholder = { Text("What needs to be done?", fontSize = 16.sp, color = muted) },
            textStyle = TextStyle(fontSize = 18.sp, color = ink), singleLine = true,
            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).testTag(titleTag),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFFF3F4F5), unfocusedContainerColor = Color(0xFFF3F4F5),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = accent))
        Spacer(Modifier.height(10.dp))
        options()
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Cancel", color = muted, fontSize = 14.sp) }
            Button(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.testTag(confirmTag).heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = readableOn(accent))) {
                Text(heading, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        }
    }
}
