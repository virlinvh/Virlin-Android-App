package com.virlin.app.ui.pulse

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

private val momentumPaper = Color(0xFFF9F8FC)
private val momentumInk = Color(0xFF20243B)
private val momentumMuted = Color(0xFF646B80)
private val momentumLine = Color(0xFFE9E8F1)
private val momentumPurple = Color(0xFF6250CF)
private val momentumTeal = Color(0xFF259FA5)
private val momentumCoral = Color(0xFFED705E)
private val momentumGold = Color(0xFFD4942A)
private val momentumViolet = Color(0xFF9960DC)

internal data class MomentumFacts(
    val activeDays: Int,
    val availableDays: Int,
    val completedTasks: Int,
    val advancedWorkstreams: Int,
    val capturedItems: Int
)

internal fun momentumFacts(input: PulseInput, report: PulseReport, zone: ZoneId, now: Instant): MomentumFacts {
    val end = minOf(report.to, now)
    val completed = input.tasks.filter { it.completedAt?.let { at -> at >= report.from && at < end } == true }
    val active = mutableSetOf<LocalDate>()
    val advanced = mutableSetOf<String>()
    input.intervals.filter { !it.paused && it.kind == PulseKind.FOCUS }.forEach { interval ->
        val start = maxOf(interval.start, report.from)
        val stop = minOf(if (interval.open) now else interval.end, end)
        if (stop > start) {
            interval.workstreamId?.let(advanced::add)
            var day = start.atZone(zone).toLocalDate()
            val last = stop.minusNanos(1).atZone(zone).toLocalDate()
            while (!day.isAfter(last)) { active += day; day = day.plusDays(1) }
        }
    }
    completed.forEach { task ->
        task.completedAt?.atZone(zone)?.toLocalDate()?.let(active::add)
        task.workstreamId?.let(advanced::add)
    }
    val elapsedInPeriod = max(1, java.time.temporal.ChronoUnit.DAYS.between(
        report.from.atZone(zone).toLocalDate(),
        minOf(now.atZone(zone).toLocalDate(), report.to.atZone(zone).toLocalDate().minusDays(1))).toInt() + 1)
    val availableDays = when (report.period) {
        PulsePeriod.TODAY -> 1
        PulsePeriod.WEEK, PulsePeriod.MONTH -> elapsedInPeriod
        PulsePeriod.ALL -> {
            val first = (input.intervals.map { it.start } + input.tasks.mapNotNull { it.completedAt } +
                input.captures.map { it.createdAt }).minOrNull() ?: end
            max(1, java.time.temporal.ChronoUnit.DAYS.between(
                first.atZone(zone).toLocalDate(), end.atZone(zone).toLocalDate()).toInt() + 1)
        }
    }
    return MomentumFacts(
        activeDays = active.size,
        availableDays = availableDays,
        completedTasks = completed.size,
        advancedWorkstreams = advanced.size,
        capturedItems = input.captures.count { it.createdAt >= report.from && it.createdAt < end }
    )
}

@Composable
internal fun MomentumScreen(
    input: PulseInput,
    onProject: (String, PulsePeriod, LocalDate) -> Unit,
    onInfoSaved: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone)
) {
    var period by rememberSaveable { mutableStateOf(PulsePeriod.WEEK) }
    var anchorString by rememberSaveable { mutableStateOf(today.toString()) }
    val anchor = remember(anchorString) { LocalDate.parse(anchorString) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = Instant.now()
        }
    }
    val report = remember(input, period, anchor, zone, now) { calculatePulse(input, period, anchor, zone, now) }
    val facts = remember(input, report, zone, now) { momentumFacts(input, report, zone, now) }
    val prior = remember(input, period, anchor, zone, now) {
        if (period == PulsePeriod.ALL) null else calculatePulse(input, period, when (period) {
            PulsePeriod.TODAY -> anchor.minusDays(1)
            PulsePeriod.WEEK -> anchor.minusWeeks(1)
            PulsePeriod.MONTH -> anchor.minusMonths(1)
            PulsePeriod.ALL -> anchor
        }, zone, now)
    }
    val delta = prior?.let { report.focusMs - it.focusMs }
    val leader = report.projectTotals.firstOrNull { it.focusMs > 0L }
    val completedByProject = input.tasks.groupBy { task ->
        task.projectId ?: input.workstreams.firstOrNull { it.id == task.workstreamId }?.projectId
    }
    LazyColumn(
        modifier.fillMaxSize().background(momentumPaper),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR PROGRESS", color = momentumPurple, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Text("Momentum ✦", color = momentumInk, fontSize = 34.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    Text("A clearer picture of what you built.", color = momentumMuted, fontSize = 13.sp)
                }
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFF0ECFF))
                    .clickable(onClick = onInfoSaved), contentAlignment = Alignment.Center) {
                    Text("ⓘ", color = momentumPurple, fontSize = 23.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PulsePeriod.WEEK, PulsePeriod.MONTH, PulsePeriod.ALL).forEach { option ->
                    val label = when (option) { PulsePeriod.WEEK -> "Week"; PulsePeriod.MONTH -> "Month"; else -> "All time" }
                    Box(Modifier.clip(CircleShape)
                        .background(if (period == option) momentumInk else Color.Transparent)
                        .clickable { period = option; anchorString = today.toString() }
                        .defaultMinSize(minHeight = 46.dp).padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center) {
                        Text(label, color = if (period == option) Color.White else momentumMuted,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                if (period != PulsePeriod.ALL) {
                    StepArrow("‹", "Previous period") { anchorString = momentumShift(anchor, period, -1).toString() }
                } else Spacer(Modifier.width(48.dp))
                Text(momentumDateLabel(report, period, zone), color = momentumMuted, fontSize = 13.sp)
                if (period != PulsePeriod.ALL) {
                    StepArrow("›", "Next period") { anchorString = momentumShift(anchor, period, 1).toString() }
                } else Spacer(Modifier.width(48.dp))
            }
        }
        item { MomentumHero(report, facts, delta) }
        item {
            if (leader != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFFF4DA)),
                        contentAlignment = Alignment.Center) { Text("✦", color = momentumGold, fontSize = 20.sp) }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Steady progress looks good on you.", fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, color = momentumInk)
                        Text("${leader.project.title} led this period.", fontSize = 12.sp, color = momentumMuted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item { MomentumWeekChart(report) }
        item { MomentumOutcomes(facts) }
        item { MomentumProjects(report, completedByProject) { id -> onProject(id, period, anchor) } }
        item { MomentumSaved(report.savedMs, onInfoSaved) }
        if (report.concurrentFocusMs > 0L) item {
            Text("${momentumDuration(report.concurrentFocusMs)} of concurrent focus records was counted once.",
                color = momentumMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

private fun momentumShift(date: LocalDate, period: PulsePeriod, amount: Long): LocalDate = when (period) {
    PulsePeriod.WEEK -> date.plusWeeks(amount)
    PulsePeriod.MONTH -> date.plusMonths(amount)
    PulsePeriod.TODAY -> date.plusDays(amount)
    PulsePeriod.ALL -> date
}

private fun momentumDateLabel(report: PulseReport, period: PulsePeriod, zone: ZoneId): String = when (period) {
    PulsePeriod.ALL -> "Since you started"
    PulsePeriod.MONTH -> report.from.atZone(zone).format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    else -> {
        val start = report.from.atZone(zone).toLocalDate()
        val end = report.to.atZone(zone).toLocalDate().minusDays(1)
        if (start == end) start.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
        else "${start.format(DateTimeFormatter.ofPattern("d"))}–${end.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
    }
}

private fun momentumDuration(ms: Long): String {
    if (ms <= 0) return "0m"
    if (ms < 60_000L) return "<1m"
    val minutes = (ms / 60_000.0).roundToInt().coerceAtLeast(1)
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours == 0) "${rest}m" else "${hours}h ${rest}m"
}

@Composable
private fun StepArrow(text: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).semantics { contentDescription = description }
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = momentumInk, fontSize = 25.sp)
    }
}

@Composable
private fun MomentumHero(report: PulseReport, facts: MomentumFacts, delta: Long?) {
    val label = when (report.period) { PulsePeriod.WEEK -> "THIS WEEK’S MOMENTUM"; PulsePeriod.MONTH -> "THIS MONTH’S MOMENTUM"; PulsePeriod.ALL -> "ALL-TIME MOMENTUM"; PulsePeriod.TODAY -> "TODAY’S MOMENTUM" }
    Row(Modifier.fillMaxWidth().heightIn(min = 178.dp)
        .clip(RoundedCornerShape(26.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF5141BA), Color(0xFF7D55CB), Color(0xFFCA69B6))))
        .padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color(0xFFF1E9FF), fontSize = 10.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
            Spacer(Modifier.height(12.dp))
            Text(momentumDuration(report.focusMs), color = Color.White, fontSize = 35.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, maxLines = 1)
            Text("useful focus", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(15.dp))
            Text(when {
                delta == null -> "Recorded since you started"
                delta > 0L -> "↗ ${momentumDuration(delta)} more than before"
                delta < 0L -> "${momentumDuration(-delta)} less than before"
                else -> "Same as the previous period"
            }, color = Color.White, fontSize = 11.sp, maxLines = 2)
        }
        Spacer(Modifier.width(6.dp))
        MomentumRing(facts.activeDays, facts.availableDays, report.period == PulsePeriod.ALL)
    }
}

@Composable
private fun MomentumRing(active: Int, available: Int, allTime: Boolean) {
    Box(Modifier.size(94.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 13.dp.toPx()
            drawArc(Color.White.copy(alpha = .27f), -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Butt))
            drawArc(Color(0xFFFFD47C), -90f, 360f * (active.toFloat() / available.coerceAtLeast(1)).coerceIn(0f, 1f),
                false, style = Stroke(stroke, cap = StrokeCap.Butt))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(active.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            Text(if (allTime) "active days" else "/$available days", color = Color.White,
                fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MomentumPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White)
        .padding(16.dp), content = content)
}

@Composable
private fun MomentumWeekChart(report: PulseReport) {
    val chartTitle = when (report.period) { PulsePeriod.WEEK -> "Your week at a glance"; PulsePeriod.MONTH -> "Your month at a glance"; PulsePeriod.ALL -> "Your journey at a glance"; PulsePeriod.TODAY -> "Today at a glance" }
    val maxTotal = report.buckets.maxOfOrNull { it.focusMs + max(0L, it.externalMs - it.overlapMs) }
        ?.coerceAtLeast(1L) ?: 1L
    MomentumPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(chartTitle, color = momentumInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Focus + external", color = momentumMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(20.dp))
        val chartDescription = report.buckets.joinToString("; ") {
            "${it.label}: ${momentumDuration(it.focusMs)} focus, ${momentumDuration(it.externalMs)} external"
        }
        if (report.buckets.all { it.focusMs == 0L && it.externalMs == 0L }) {
            Box(Modifier.fillMaxWidth().height(124.dp), contentAlignment = Alignment.Center) {
                Text("No timed work recorded for this period yet", color = momentumMuted, fontSize = 12.sp)
            }
        } else {
            Row(Modifier.fillMaxWidth().height(124.dp).semantics { contentDescription = chartDescription },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom) {
                report.buckets.forEach { bucket ->
                    val externalOnly = max(0L, bucket.externalMs - bucket.overlapMs)
                    val focusHeight = 94f * bucket.focusMs / maxTotal
                    val externalHeight = 94f * externalOnly / maxTotal
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom) {
                        Column(Modifier.widthIn(max = 29.dp).fillMaxWidth().height(94.dp),
                            verticalArrangement = Arrangement.Bottom) {
                            if (externalOnly > 0L) Box(Modifier.fillMaxWidth().height(externalHeight.dp)
                                .background(momentumTeal, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)))
                            if (bucket.focusMs > 0L) Box(Modifier.fillMaxWidth().height(focusHeight.dp)
                                .background(momentumPurple, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(if (report.period == PulsePeriod.ALL) bucket.label.take(5) else bucket.label.take(3),
                            color = momentumMuted, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
        if (report.buckets.any { it.focusMs > 0L || it.externalMs > 0L }) {
            Spacer(Modifier.height(11.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MomentumLegend("Focus", momentumPurple)
                MomentumLegend("External only", momentumTeal)
            }
        }
        if (report.buckets.any { it.overlapMs > 0 }) {
            Text("Overlapping time is counted once in the chart.", color = momentumMuted,
                fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun MomentumLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = momentumMuted, fontSize = 11.sp)
    }
}

@Composable
private fun MomentumOutcomes(facts: MomentumFacts) {
    MomentumPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("What moved forward", color = momentumInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Measured outcomes", color = momentumMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutcomeTile("✓", facts.completedTasks.toString(), "tasks done", Color(0xFFFFF0EA), momentumCoral, Modifier.weight(1f))
            OutcomeTile("↗", facts.advancedWorkstreams.toString(), "workstreams", Color(0xFFFFF4DA), momentumGold, Modifier.weight(1f))
            OutcomeTile("✎", facts.capturedItems.toString(), "captures saved", Color(0xFFF4EBFF), momentumViolet, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OutcomeTile(symbol: String, value: String, caption: String, wash: Color, accent: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(15.dp)).background(wash).padding(10.dp)) {
        Text(symbol, color = accent, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = accent, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text(caption, color = momentumMuted, fontSize = 10.sp, maxLines = 2)
    }
}

@Composable
private fun MomentumProjects(report: PulseReport, tasks: Map<String?, List<PulseTask>>, onProject: (String) -> Unit) {
    val projects = report.projectTotals.filter { it.focusMs > 0L || it.completed > 0 }
    MomentumPanel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Project impact", color = momentumInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("This period", color = momentumMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))
        if (projects.isEmpty()) {
            Text("No project activity recorded for this period.", color = momentumMuted, fontSize = 13.sp)
        } else {
            projects.forEachIndexed { index, project ->
                val tint = listOf(momentumPurple, momentumCoral, momentumTeal, momentumViolet)[index % 4]
                val allTasks = tasks[project.project.id].orEmpty()
                val done = allTasks.count { it.completedAt != null }
                val fraction = if (allTasks.isEmpty()) 0f else done.toFloat() / allTasks.size
                Row(Modifier.fillMaxWidth().clickable { onProject(project.project.id) }
                    .padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                        .background(tint.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                        Text("◇", color = tint, fontSize = 21.sp)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(project.project.title, color = momentumInk, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${project.completed} tasks finished · ${momentumDuration(project.focusMs)} focus",
                            color = momentumMuted, fontSize = 10.sp, maxLines = 1)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(tint.copy(alpha = .13f))) {
                            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(tint))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (allTasks.isEmpty()) "—" else "${(fraction * 100).roundToInt()}%",
                        color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MomentumSaved(savedMs: Long, onInfoSaved: () -> Unit) {
    MomentumPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⌁", color = momentumTeal, fontSize = 21.sp)
            Spacer(Modifier.width(7.dp))
            Text("Parallel work", color = momentumInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("Estimate", color = momentumTeal, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        Text(momentumDuration(savedMs), color = momentumTeal, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Focus on one task while another ran externally. This time is already included in both totals.",
            color = momentumMuted, fontSize = 11.sp)
        Text("How it’s calculated →", color = momentumPurple, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onInfoSaved).padding(top = 9.dp, bottom = 5.dp))
    }
}
