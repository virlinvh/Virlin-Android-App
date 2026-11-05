package com.virlin.app.ui.screens
import com.virlin.app.ui.components.avoidOrbCircle
import com.virlin.app.ui.components.circularOrbCutout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.components.ProjectIcon

private val resultInk = Color(0xFF172119)
private val resultMuted = Color(0xFF63717B)

@Composable
fun DirectoryViewSwitch(tasks: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 6.dp)
        .clip(RoundedCornerShape(28.dp)).background(Color(0xFFEFF0EF))) {
        listOf(false to "Projects", true to "Tasks").forEach { (value, label) ->
            Box(Modifier.weight(1f).defaultMinSize(minHeight = 44.dp).clip(RoundedCornerShape(28.dp))
                .background(if (tasks == value) Color(0xFFD9F2E6) else Color.Transparent)
                .clickable(role = Role.Tab) { onChange(value) }.semantics { selected = tasks == value }
                .testTag("directory_view_${label.lowercase()}"), contentAlignment = Alignment.Center) {
                Text(label, color = if (tasks == value) Color(0xFF007B58) else resultMuted,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Navigation-only results: containers are headings, not execution targets. */
@Composable
fun FilteredProjectTasks(results: List<DirectoryWorkItem>, filter: ProjectDirectoryFilter,
                         onOpen: (DirectoryWorkItem) -> Unit) {
    var collapsed by rememberSaveable(filter.name) { mutableStateOf(emptyList<String>()) }
    val groups = results.groupBy { it.project?.id }
    LazyColumn(Modifier.fillMaxSize().testTag("directory_task_results"),
        contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 78.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("${results.size} ${filter.label.lowercase()} items · ${groups.keys.count { it != null }} projects",
            color = resultMuted, fontSize = 13.sp) }
        if (results.isEmpty()) item { Text("No ${filter.label.lowercase()} tasks match this search or filter.", color = resultMuted, fontSize = 14.sp) }
        groups.forEach { (projectId, rows) ->
            val key = projectId ?: "projectless"
            item(key = "group:$key") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White)) {
                Row(Modifier.fillMaxWidth().circularOrbCutout().clip(RoundedCornerShape(20.dp)).background(Color.White).avoidOrbCircle().defaultMinSize(minHeight = 64.dp)
                    .clickable { collapsed = if (key in collapsed) collapsed - key else collapsed + key }
                    .semantics { stateDescription = if (key in collapsed) "Collapsed" else "Expanded" }
                    .testTag("directory_group_$key").padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    rows.first().project?.let { ProjectIcon(project = it, size = 40.dp, decorative = true) }
                    Spacer(Modifier.width(10.dp))
                    Text(rows.first().project?.title ?: "Without a project", modifier = Modifier.weight(1f),
                        color = resultInk, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${rows.size}", color = resultMuted, fontSize = 13.sp)
                    Spacer(Modifier.width(12.dp))
                    Icon(if (key in collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                        contentDescription = null, tint = resultMuted, modifier = Modifier.size(24.dp))
                }
                if (key !in collapsed) DirectoryTaskRow(rows.first(), onOpen)
                }
            }
            if (key !in collapsed) items(rows.drop(1), key = { "${if (it.task == null) "stream" else "task"}:${it.work.id}" }) { item ->
                DirectoryTaskRow(item, onOpen)
            }
        }
        if (results.isNotEmpty()) item {
            Text("Showing executable tasks at every depth", color = resultMuted.copy(alpha = 0.8f), fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

@Composable
private fun DirectoryTaskRow(item: DirectoryWorkItem, onOpen: (DirectoryWorkItem) -> Unit) {
                Column(Modifier.fillMaxWidth().circularOrbCutout().clip(RoundedCornerShape(20.dp)).background(Color.White)
                    .clickable(role = Role.Button) { onOpen(item) }.testTag("directory_work_${item.work.id}")) {
                    Row(Modifier.fillMaxWidth().avoidOrbCircle().padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.work.title, color = resultInk, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(9.dp))
                            DirectoryStatusBadge(item)
                            }
                            if (item.hierarchy.isNotBlank()) Text(item.hierarchy, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = resultMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            Text(item.timing, color = resultMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Open ${item.work.title}", tint = resultMuted,
                            modifier = Modifier.size(24.dp))
                    }
                }
}

@Composable
private fun DirectoryStatusBadge(item: DirectoryWorkItem) {
    val state = item.work.state
    val running = !item.work.externalTrackingPaused && (state == com.virlin.app.domain.model.WorkStreamState.PROCESSING ||
        (state == com.virlin.app.domain.model.WorkStreamState.CHECK && item.work.processingStartedAt != null))
    val (label, ink, wash) = when {
        running -> Triple("RUNNING", Color(0xFF2864F0), Color(0xFFE8F0FF))
        item.work.externalTrackingPaused || state == com.virlin.app.domain.model.WorkStreamState.PAUSED -> Triple("PAUSED", resultMuted, Color(0xFFF0F2F3))
        state == com.virlin.app.domain.model.WorkStreamState.FOCUS -> Triple("FOCUS", Color(0xFF009F73), Color(0xFFE3F6EE))
        state == com.virlin.app.domain.model.WorkStreamState.CHECK -> Triple("REVIEW", Color(0xFF986800), Color(0xFFFFF3D5))
        state == com.virlin.app.domain.model.WorkStreamState.BLOCKED -> Triple("BLOCKED", Color(0xFFB33939), Color(0xFFFFEAEA))
        state == com.virlin.app.domain.model.WorkStreamState.SNOOZED -> Triple("SCHEDULED", resultMuted, Color(0xFFF0F2F3))
        else -> Triple(state.name, resultMuted, Color(0xFFF0F2F3))
    }
    Text(label, color = ink, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(wash).padding(horizontal = 9.dp, vertical = 4.dp))
}
