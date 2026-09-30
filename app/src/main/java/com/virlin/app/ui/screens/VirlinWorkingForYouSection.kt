package com.virlin.app.ui.screens

// Supplied drop-in section, used as-is. Virlin maps its external runs onto WorkingTaskUi.

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/** Map the existing app's background jobs into this display-only model. */
data class WorkingTaskUi(
    val id: String,                 // Stable, nonempty, unique task ID.
    val title: String,              // e.g., "Build"
    val detail: String,             // e.g., "Build · Release APK"
    val checkStartedAtEpochMillis: Long? = null,
    val nextCheckAtEpochMillis: Long? = null,
)

enum class WorkingStage(val label: String) {
    STARTING("Starting"),
    WORKING("Working now"),
    WRAPPING_UP("Wrapping up"),
}

private data class WorkingColors(val accent: Color, val dark: Color)

// Fifteen deliberately distinct accent hues. Pale surfaces are derived from the
// assigned accent; badge, rail, and equalizer always share the same hue.
private val workingPalette = listOf(
    WorkingColors(Color(0xFF7956DF), Color(0xFF47309B)), // violet
    WorkingColors(Color(0xFF159DCA), Color(0xFF075A75)), // cyan
    WorkingColors(Color(0xFF7EAD23), Color(0xFF486D0D)), // lime
    WorkingColors(Color(0xFF3766D8), Color(0xFF25438B)), // blue
    WorkingColors(Color(0xFFE18B19), Color(0xFF85520B)), // orange
    WorkingColors(Color(0xFFCE4E9A), Color(0xFF81285C)), // pink
    WorkingColors(Color(0xFF0DA88D), Color(0xFF066B58)), // teal
    WorkingColors(Color(0xFFBF9D17), Color(0xFF6E5910)), // gold
    WorkingColors(Color(0xFF6455BC), Color(0xFF3B317E)), // indigo
    WorkingColors(Color(0xFFE4695D), Color(0xFF903D35)), // coral
    WorkingColors(Color(0xFF27A55C), Color(0xFF146D39)), // green
    WorkingColors(Color(0xFFA352B6), Color(0xFF672D77)), // orchid
    WorkingColors(Color(0xFF527FA5), Color(0xFF315471)), // steel blue
    WorkingColors(Color(0xFF60A96C), Color(0xFF396E42)), // sage
    WorkingColors(Color(0xFFC26544), Color(0xFF7C3E29)), // terracotta
)

/**
 * Use inside the existing vertically scrolling screen; this section does not
 * create a second scroll container. For 1–15 jobs, visible jobs have distinct
 * accent hues. Jobs are keyed by ID so animation state follows the right card.
 */
@Composable
fun VirlinWorkingForYouSection(
    tasks: List<WorkingTaskUi>,
    nowEpochMillis: Long,
    onTaskClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    require(tasks.map { it.id }.distinct().size == tasks.size) { "Working task IDs must be unique" }
    val ids = tasks.map { it.id }.sorted()
    val colorById = remember(ids) { assignDistinctColors(ids) }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Working For You", color = Color(0xFF172321),
                fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(24.dp).clip(CircleShape)
                .background(Color(0xFFEDEAFF)), contentAlignment = Alignment.Center) {
                Text(tasks.size.toString(), color = Color(0xFF6046C7),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text("Running in background", color = Color(0xFF657077),
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        tasks.forEach { task ->
            key(task.id) {
                WorkingTaskCard(task, nowEpochMillis, colorById.getValue(task.id), animate,
                    onClick = { onTaskClick(task.id) })
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun WorkingTaskCard(
    task: WorkingTaskUi,
    now: Long,
    colors: WorkingColors,
    animate: Boolean,
    onClick: () -> Unit,
) {
    val start = task.checkStartedAtEpochMillis
    val due = task.nextCheckAtEpochMillis
    val timed = start != null && due != null && due > start
    val progress = if (timed) ((now - start!!).toDouble() / (due!! - start!!).toDouble())
        .coerceIn(0.0, 1.0).toFloat() else 0f
    val animatedProgress = key(start, due) {
        val value by animateFloatAsState(progress, tween(if (animate) 850 else 0), label = "check fill")
        value
    }
    val remaining = if (timed) (due!! - now).coerceAtLeast(0L) else 0L
    val ready = timed && remaining == 0L
    val shape = RoundedCornerShape(20.dp)
    val surface = colors.accent.copy(alpha = .065f)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.horizontalGradient(listOf(surface, Color.White, surface.copy(alpha = .35f))))
            .border(1.dp, colors.accent.copy(alpha = .22f), shape)
            .clickable(onClick = onClick),
    ) {
        if (timed) ProgressWave(animatedProgress, colors.accent, animate,
            Modifier.matchParentSize())
        Row {
        // One accent rail per task; stage is carried by text and motion.
        Box(Modifier.width(5.dp).height(104.dp).background(colors.accent))
        Row(
            Modifier.weight(1f).height(104.dp).padding(start = 11.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EqualizerIcon(colors.accent, if (ready) WorkingStage.WRAPPING_UP else WorkingStage.WORKING, animate && !ready)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(task.title, color = Color(0xFF172321), fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(task.detail, color = Color(0xFF566364), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                StageBadge(if (ready) "Ready to check" else "Working now", colors, animate && !ready)
                Spacer(Modifier.height(8.dp))
                Text(when {
                    !timed -> "No check"
                    ready -> "Check now"
                    else -> "◷  ${formatRemaining(remaining)} to check"
                }, color = Color(0xFF5E6A70),
                    fontSize = 11.sp, maxLines = 1)
            }
        }
        }
    }
}

/** The wave shows elapsed time until the next check, never task completion. */
@Composable
private fun ProgressWave(progress: Float, accent: Color, animate: Boolean, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "wave motion")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(2800), RepeatMode.Restart), label = "wave phase")
    val motion = if (animate && progress in 0.001f..0.999f) phase else 0f
    Canvas(modifier) {
        val level = size.height * (1f - progress)
        val amplitude = if (progress <= 0f || progress >= 1f) 0f else 2.dp.toPx()
        val path = Path().apply {
            moveTo(0f, size.height)
            lineTo(0f, level)
            for (i in 0..48) {
                val x = size.width * i / 48f
                lineTo(x, level + amplitude * sin((x / size.width * 2 * PI + motion).toFloat()))
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, Brush.verticalGradient(listOf(accent.copy(alpha = .08f),
            accent.copy(alpha = .20f)), startY = 0f, endY = size.height))
        if (progress in 0.025f..0.975f) {
            val x = 18.dp.toPx() + progress * (size.width - 36.dp.toPx())
            val y = level + amplitude * sin((x / size.width * 2 * PI + motion).toFloat())
            val center = androidx.compose.ui.geometry.Offset(x, y)
            drawCircle(accent.copy(alpha = .18f), 10.dp.toPx(), center)
            drawCircle(Color.White, 5.dp.toPx(), center)
            drawCircle(accent, 3.dp.toPx(), center)
        }
    }
}

private fun formatRemaining(millis: Long): String {
    val seconds = (millis + 999L) / 1000L
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs)
        else "%02d:%02d".format(minutes, secs)
}

@Composable
private fun StageBadge(label: String, colors: WorkingColors, animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "status pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.65f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1150), RepeatMode.Reverse),
        label = "status alpha",
    )
    Row(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(colors.accent.copy(alpha = .11f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).graphicsLayer {
            alpha = if (animate) pulse else 1f
        }.clip(CircleShape).background(colors.accent))
        Spacer(Modifier.width(5.dp))
        Text(label, color = colors.dark, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun EqualizerIcon(accent: Color, stage: WorkingStage, animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "working equalizer")
    val wave by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "equalizer wave",
    )
    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp))
        .background(accent.copy(alpha = .10f)), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val heights = when (stage) {
                WorkingStage.STARTING -> listOf(12f, 19f, 11f)
                WorkingStage.WORKING -> listOf(17f, 23f, 16f)
                WorkingStage.WRAPPING_UP -> listOf(10f, 17f, 12f)
            }
            heights.forEachIndexed { i, base ->
                val delta = if (animate) (if (i == 1) -5f else 4f) * wave else 0f
                Box(Modifier.width(5.dp).height((base + delta).dp)
                    .clip(RoundedCornerShape(5.dp)).background(accent))
            }
        }
    }
}

// Assign colors by stable IDs, resolving collisions within the current set.
// If the list has more than 15 items, distinct extra colors are generated.
// For permanent color identity across additions/removals, persist this map in
// the app's existing UI state instead of recalculating it from the current list.
private fun assignDistinctColors(sortedIds: List<String>): Map<String, WorkingColors> {
    val used = mutableSetOf<Int>()
    val result = mutableMapOf<String, WorkingColors>()
    sortedIds.forEach { id ->
        var slot = (id.hashCode() and Int.MAX_VALUE) % maxOf(workingPalette.size, sortedIds.size)
        while (!used.add(slot)) slot = (slot + 1) % maxOf(workingPalette.size, sortedIds.size)
        result[id] = if (slot < workingPalette.size) workingPalette[slot] else {
            val hue = (slot * 137.508f) % 360f
            WorkingColors(Color.hsl(hue, .57f, .46f), Color.hsl(hue, .57f, .29f))
        }
    }
    return result
}
