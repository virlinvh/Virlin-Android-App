package com.virlin.app.ui.pulse

// Supplied drop-in Pulse. Adapted where the app's own records decide the meaning:
//  - FOCUS intervals are the app's FocusSession rows and EXTERNAL intervals are a Cycle's
//    hand-off window, so every number here comes from timestamps the domain already persists,
//  - an open interval (a running timer) is clipped to "now", never to the end of the period,
//  - simultaneous focus records are a corruption, not a doubling: the sweep counts one and the
//    screen says so rather than inflating the total,
//  - the app-wide footer and Orb stay outside this screen, drawn once by the shell.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/** UI + pure analytics. The app's repository supplies intervals and task metadata. */
enum class PulseKind { FOCUS, EXTERNAL, BLOCKED }
enum class PulsePeriod { TODAY, WEEK, MONTH }

data class PulseInterval(
    val id: String,
    val start: Instant,
    val end: Instant,
    val kind: PulseKind,
    val projectId: String? = null,
    val workstreamId: String? = null,
    val taskId: String? = null,
    val paused: Boolean = false,
    /** True while the timer is still running: [end] is a placeholder until it stops. */
    val open: Boolean = false
)

data class PulseTask(val id: String, val title: String, val workstreamId: String?,
                     val completedAt: Instant?, val projectId: String? = null)
data class PulseWorkstream(val id: String, val projectId: String, val title: String)
data class PulseProject(val id: String, val title: String)
data class PulseInput(
    val intervals: List<PulseInterval>,
    val projects: List<PulseProject>,
    val workstreams: List<PulseWorkstream>,
    val tasks: List<PulseTask>
)

data class PulseBucket(val label: String, val focusMs: Long, val externalMs: Long, val overlapMs: Long)
data class PulseTaskTotal(val task: PulseTask, val focusMs: Long)
data class PulseWorkstreamTotal(val workstream: PulseWorkstream, val focusMs: Long, val tasks: List<PulseTaskTotal>)
data class PulseProjectTotal(val project: PulseProject, val focusMs: Long, val completed: Int,
                             val workstreams: List<PulseWorkstreamTotal>, val unassignedMs: Long,
                             val blockedMs: Long)
data class PulseReport(
    val period: PulsePeriod,
    val from: Instant,
    val to: Instant,
    val buckets: List<PulseBucket>,
    val focusMs: Long,
    val externalMs: Long,
    val savedMs: Long,
    val blockedMs: Long,
    val sessionDurationsMs: List<Long>,
    val projectTotals: List<PulseProjectTotal>,
    /**
     * Milliseconds where more than one focus record claimed the same moment. Virlin allows one
     * human focus at a time, so this is a recording fault; it is counted ONCE in the totals and
     * surfaced here rather than silently doubling them.
     */
    val concurrentFocusMs: Long = 0L
)

/** Half-open [from,to). All calendar boundaries use the viewer's zone. */
fun pulseWindow(period: PulsePeriod, date: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
    val startDate = when (period) {
        PulsePeriod.TODAY -> date
        PulsePeriod.WEEK -> date.with(java.time.DayOfWeek.MONDAY)
        PulsePeriod.MONTH -> date.withDayOfMonth(1)
    }
    val endDate = when (period) {
        PulsePeriod.TODAY -> startDate.plusDays(1)
        PulsePeriod.WEEK -> startDate.plusWeeks(1)
        PulsePeriod.MONTH -> startDate.plusMonths(1)
    }
    return startDate.atStartOfDay(zone).toInstant() to endDate.atStartOfDay(zone).toInstant()
}

private fun clippedMs(i: PulseInterval, from: Instant, to: Instant): Long =
    max(0L, minOf(i.end, to).toEpochMilli() - maxOf(i.start, from).toEpochMilli())

/**
 * Sweep disjoint time slices. Concurrent focus timers are counted once, and an
 * active focus slice is attributed to one project/task only. The saved estimate
 * is the union of slices with focus on a different task while external work ran.
 */
fun calculatePulse(
    input: PulseInput,
    period: PulsePeriod,
    date: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
    /** A running timer has no end yet; it is measured up to here, never to the period's end. */
    now: Instant = Instant.now()
): PulseReport {
    val (from, to) = pulseWindow(period, date, zone)
    val horizon = minOf(to, maxOf(now, from))
    val live = input.intervals
        .map { if (it.open) it.copy(end = horizon) else it }
        .filter { !it.paused && it.end > it.start && it.start < to && it.end > from }
    val boundaries = buildSet {
        add(from.toEpochMilli()); add(to.toEpochMilli())
        live.forEach { add(maxOf(it.start, from).toEpochMilli()); add(minOf(it.end, to).toEpochMilli()) }
    }.sorted()
    val byProject = mutableMapOf<String, Long>()
    val byWorkstream = mutableMapOf<String, Long>()
    val byTask = mutableMapOf<String, Long>()
    val blockedByProject = mutableMapOf<String, Long>()
    var focus = 0L; var external = 0L; var saved = 0L; var blocked = 0L; var concurrent = 0L
    var unassigned = 0L
    val bucketRanges: List<Triple<String, Instant, Instant>> = when (period) {
        PulsePeriod.TODAY -> (0..5).map { n ->
            val a = date.atStartOfDay(zone).plusHours(n * 4L).toInstant()
            Triple("${n * 4}:00", a, date.atStartOfDay(zone).plusHours((n + 1) * 4L).toInstant())
        }
        PulsePeriod.WEEK -> (0..6).map { n ->
            val day = date.with(java.time.DayOfWeek.MONDAY).plusDays(n.toLong())
            Triple(day.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() },
                day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())
        }
        PulsePeriod.MONTH -> {
            val first = date.withDayOfMonth(1)
            (0..4).mapNotNull { n ->
                val a = first.plusDays(n * 7L)
                if (a.month != first.month) null else Triple("${a.dayOfMonth}",
                    a.atStartOfDay(zone).toInstant(), minOf(a.plusDays(7), first.plusMonths(1)).atStartOfDay(zone).toInstant())
            }
        }
    }
    val bucketValues = Array(bucketRanges.size) { LongArray(3) }
    for (j in 0 until boundaries.lastIndex) {
        val a = boundaries[j]; val b = boundaries[j + 1]
        if (b <= a) continue
        val active = live.filter { it.start.toEpochMilli() < b && it.end.toEpochMilli() > a }
        val focusHere = active.filter { it.kind == PulseKind.FOCUS }
        // One human focus at a time: if two records claim this slice, the earliest id wins and
        // the clash is reported. The slice is never counted twice.
        val f = focusHere.minByOrNull { it.id }
        if (focusHere.size > 1) concurrent += b - a
        val externals = active.filter { it.kind == PulseKind.EXTERNAL }
        val e = externals.isNotEmpty()
        val overlap = f?.taskId != null && externals.any { x -> x.taskId != null && x.taskId != f.taskId }
        val ms = b - a
        if (f != null) {
            focus += ms
            if (f.projectId == null) unassigned += ms else byProject.merge(f.projectId, ms, Long::plus)
            f.workstreamId?.let { byWorkstream.merge(it, ms, Long::plus) }
            f.taskId?.let { byTask.merge(it, ms, Long::plus) }
        }
        if (e) external += ms
        if (overlap) saved += ms
        val blocking = active.filter { it.kind == PulseKind.BLOCKED }
        if (blocking.isNotEmpty()) blocked += ms
        blocking.mapNotNull { it.projectId }.distinct().forEach { id -> blockedByProject.merge(id, ms, Long::plus) }
        bucketRanges.forEachIndexed { index, (_, start, end) ->
            val piece = max(0L, minOf(b, end.toEpochMilli()) - maxOf(a, start.toEpochMilli()))
            if (f != null) bucketValues[index][0] += piece
            if (e) bucketValues[index][1] += piece
            if (overlap) bucketValues[index][2] += piece
        }
    }
    val taskById = input.tasks.associateBy { it.id }
    val projects = input.projects.map { p ->
        val streams = input.workstreams.filter { it.projectId == p.id }.map { w ->
            PulseWorkstreamTotal(w, byWorkstream[w.id] ?: 0L,
                byTask.filterKeys { taskById[it]?.workstreamId == w.id }.mapNotNull { (id, ms) ->
                    taskById[id]?.let { PulseTaskTotal(it, ms) }
                }.sortedByDescending { it.focusMs })
        }.sortedByDescending { it.focusMs }
        val completed = input.tasks.count { t ->
            t.completedAt?.let { it >= from && it < to } == true &&
                (t.projectId == p.id || input.workstreams.any { it.id == t.workstreamId && it.projectId == p.id })
        }
        val assigned = streams.sumOf { it.focusMs }
        PulseProjectTotal(p, byProject[p.id] ?: 0L, completed, streams,
            max(0L, (byProject[p.id] ?: 0L) - assigned), blockedByProject[p.id] ?: 0L)
    }.sortedByDescending { it.focusMs }
    return PulseReport(period, from, to, bucketRanges.mapIndexed { n, range ->
        PulseBucket(range.first, bucketValues[n][0], bucketValues[n][1], bucketValues[n][2])
    }, focus, external, saved, blocked,
        live.filter { it.kind == PulseKind.FOCUS }.map { clippedMs(it, from, to) }.filter { it > 0 },
        projects, concurrent)
}

private val bg = Color(0xFFF7F6F4)
private val ink = Color(0xFF182219)
private val muted = Color(0xFF68716C)
private val focusGreen = Color(0xFF23774C)
private val overlapGreen = Color(0xFF83D743)
private val externalGreen = Color(0xFFC7E6CE)
private val mint = Color(0xFFDEF3E6)
private val line = Color(0xFFE8ECEA)
private val cardShape = RoundedCornerShape(22.dp)

private fun Long.asDuration(): String {
    // Recorded time never reads as zero: a session shorter than a minute is "<1m", which is
    // what the rest of the app says, not "0m" as if nothing had happened.
    if (this <= 0L) return "0m"
    if (this < 60_000L) return "<1m"
    val mins = (this / 60000.0).roundToInt().coerceAtLeast(1)
    val h = mins / 60; val m = mins % 60
    return if (h == 0) "${m}m" else if (m == 0) "${h}h" else "${h}h ${m}m"
}

/** Host app should keep its existing bottom navigation and ORB outside this screen. */
@Composable
fun VirlinPulseScreen(
    input: PulseInput,
    onProject: (String) -> Unit,
    onInfoSaved: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone)
) {
    var selected by rememberSaveable { mutableStateOf(PulsePeriod.WEEK) }
    var anchor by rememberSaveable { mutableStateOf(today.toString()) }
    val date = remember(anchor) { LocalDate.parse(anchor) }
    val report = remember(input, selected, date, zone) { calculatePulse(input, selected, date, zone) }
    LazyColumn(modifier.fillMaxSize().background(bg), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Pulse", color = ink, fontSize = 34.sp, fontWeight = FontWeight.Bold) }
        item { PeriodPicker(selected, onSelect = { selected = it }) }
        item { DateStepper(report, zone, onPrev = { anchor = shift(date, selected, -1).toString() },
            onNext = { anchor = shift(date, selected, 1).toString() }) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("Useful Focus", report.focusMs.asDuration(), Modifier.weight(1f))
                MetricCard("External", report.externalMs.asDuration(), Modifier.weight(1f))
                MetricCard("Estimated time saved", report.savedMs.asDuration(), Modifier.weight(1f))
            }
        }
        item {
            AllocationChart(
                report.buckets,
                when (selected) {
                    PulsePeriod.TODAY -> "Where today went"
                    PulsePeriod.WEEK -> "Where your week went"
                    PulsePeriod.MONTH -> "Where your month went"
                }
            )
        }
        item { ProjectTreemap(report.projectTotals, onProject) }
        item { SessionMetrics(report.sessionDurationsMs) }
        if (report.concurrentFocusMs > 0L) item {
            Text(
                "${report.concurrentFocusMs.asDuration()} of this period has more than one focus " +
                    "record. Virlin counts it once; the overlap is a recording fault, not extra time.",
                color = muted, fontSize = 12.sp
            )
        }
        item {
            TextButton(onClick = onInfoSaved) {
                Text("How is saved time calculated?", color = focusGreen)
            }
        }
    }
}

private fun shift(date: LocalDate, period: PulsePeriod, amount: Long): LocalDate = when (period) {
    PulsePeriod.TODAY -> date.plusDays(amount)
    PulsePeriod.WEEK -> date.plusWeeks(amount)
    PulsePeriod.MONTH -> date.plusMonths(amount)
}

@Composable
private fun PeriodPicker(value: PulsePeriod, onSelect: (PulsePeriod) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsePeriod.entries.forEach { p ->
            val active = value == p
            Box(Modifier.padding(end = 12.dp).background(if (active) ink else Color.Transparent, CircleShape)
                .clickable { onSelect(p) }.defaultMinSize(minWidth = 64.dp, minHeight = 46.dp).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center) {
                Text(p.name.lowercase().replaceFirstChar { it.uppercase() },
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (active) Color.White else muted)
            }
        }
    }
}

@Composable
private fun DateStepper(report: PulseReport, zone: ZoneId, onPrev: () -> Unit, onNext: () -> Unit) {
    val a = report.from.atZone(zone).toLocalDate()
    val b = report.to.atZone(zone).toLocalDate().minusDays(1)
    val label = if (a == b) a.format(DateTimeFormatter.ofPattern("d MMM yyyy")) else
        "${a.format(DateTimeFormatter.ofPattern("d MMM"))} – ${b.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrev) { Text("‹", fontSize = 23.sp, color = ink) }
        Text(label, Modifier.weight(1f), color = muted, fontSize = 13.sp)
        TextButton(onClick = onNext) { Text("›", fontSize = 23.sp, color = ink) }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Color.White, RoundedCornerShape(17.dp)).padding(horizontal = 11.dp, vertical = 13.dp)) {
        Text(label, color = muted, fontSize = 11.sp, maxLines = 2, minLines = 2)
        Spacer(Modifier.height(5.dp))
        Text(value, color = ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun AllocationChart(buckets: List<PulseBucket>, title: String) {
    var chosen by remember(buckets) { mutableIntStateOf(0) }
    val selected = buckets.getOrNull(chosen)
    Column(Modifier.fillMaxWidth().background(Color.White, cardShape).padding(16.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ink)
        Text("Focus, external processing and overlap", fontSize = 12.sp, color = muted)
        Spacer(Modifier.height(12.dp))
        selected?.let {
            Text(
                "${it.label}: ${it.focusMs.asDuration()} focus · ${it.externalMs.asDuration()} external" +
                    " · ${it.overlapMs.asDuration()} overlap",
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ink
            )
        }
        Spacer(Modifier.height(8.dp))
        val largest = (buckets.maxOfOrNull { max(0L, it.focusMs + it.externalMs - it.overlapMs) } ?: 1L).coerceAtLeast(1L)
        Row(Modifier.fillMaxWidth().height(168.dp), horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.Bottom) {
            buckets.forEachIndexed { index, b ->
                val total = b.focusMs + b.externalMs - b.overlapMs
                Column(Modifier.weight(1f).fillMaxHeight().clickable { chosen = index },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    val chartHeight = 126.dp
                    val focusOnly = max(0L, b.focusMs - b.overlapMs)
                    val externalOnly = max(0L, b.externalMs - b.overlapMs)
                    val units = listOf(externalOnly to externalGreen, b.overlapMs to overlapGreen, focusOnly to focusGreen)
                    Column(Modifier.fillMaxWidth(0.8f).height(chartHeight), verticalArrangement = Arrangement.Bottom) {
                        // Height is proportional to the union; overlap is drawn once.
                        units.forEach { (duration, color) ->
                            if (duration > 0) Box(Modifier.fillMaxWidth().height((chartHeight.value * duration / largest).dp)
                                .background(color, RoundedCornerShape(4.dp)))
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(b.label, fontSize = 10.sp, color = if (chosen == index) ink else muted,
                        maxLines = 1, fontWeight = if (chosen == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Focus", focusGreen); Legend("External", externalGreen); Legend("Overlap", overlapGreen)
        }
        Text("Overlap is already included in focus and external totals.", color = muted, fontSize = 11.sp,
            modifier = Modifier.padding(top = 9.dp))
    }
}

@Composable private fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp)); Text(text, fontSize = 10.sp, color = muted)
    }
}

@Composable
private fun ProjectTreemap(projects: List<PulseProjectTotal>, onProject: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White, cardShape).padding(16.dp)) {
        Text("By project", fontSize = 18.sp, color = ink, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (projects.isEmpty() || projects.all { it.focusMs == 0L }) {
            Text("No project focus recorded for this period", color = muted, fontSize = 14.sp)
        } else {
            val active = projects.filter { it.focusMs > 0L }
            val colors = listOf(Color(0xFF9CDDAD), Color(0xFFC4EDCC), Color(0xFFDEF4E1))
            // Top three get the visual treemap; every project remains accessible below it.
            val top = active.take(3)
            val first = top.first()
            Row(Modifier.fillMaxWidth().height(138.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ProjectTile(first, colors[0], Modifier.weight(first.focusMs.toFloat().coerceAtLeast(1f)).fillMaxHeight(), onProject)
                if (top.size > 1) {
                    Column(Modifier.weight(top.drop(1).sumOf { it.focusMs }.toFloat().coerceAtLeast(1f)),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        top.drop(1).forEachIndexed { idx, p ->
                            ProjectTile(p, colors[(idx + 1) % colors.size],
                                Modifier.weight(p.focusMs.toFloat().coerceAtLeast(1f)).fillMaxWidth(), onProject)
                        }
                    }
                }
            }
            active.drop(3).forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { onProject(p.project.id) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(p.project.title, color = ink, fontSize = 14.sp, maxLines = 1)
                    Text(p.focusMs.asDuration(), color = focusGreen, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ProjectTile(p: PulseProjectTotal, color: Color, modifier: Modifier, onProject: (String) -> Unit) {
    Column(modifier.background(color, RoundedCornerShape(10.dp)).clickable { onProject(p.project.id) }
        .padding(12.dp), verticalArrangement = Arrangement.Bottom) {
        Text(p.project.title, color = ink, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(p.focusMs.asDuration(), color = ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

@Composable
private fun SessionMetrics(durations: List<Long>) {
    Column(Modifier.fillMaxWidth().background(Color.White, cardShape).padding(16.dp)) {
        Text("Focus sessions", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = ink)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("Sessions" to durations.size.toString(),
                "Average" to if (durations.isEmpty()) "—" else (durations.sum() / durations.size).asDuration(),
                "Shortest" to (durations.minOrNull()?.asDuration() ?: "—"),
                "Longest" to (durations.maxOrNull()?.asDuration() ?: "—")).forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(value, color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(label, color = muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun VirlinPulseProjectScreen(input: PulseInput, report: PulseReport, projectId: String,
                             onBack: () -> Unit, onTask: (String) -> Unit,
                             modifier: Modifier = Modifier, zone: ZoneId = ZoneId.systemDefault()) {
    val p = report.projectTotals.firstOrNull { it.project.id == projectId }
    var expanded by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    LazyColumn(modifier.fillMaxSize().background(bg), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹", fontSize = 25.sp, color = ink) }
                Text(p?.project?.title ?: "Project", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (p == null) {
            item { Text("Project not found", color = muted) }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("Focused", p.focusMs.asDuration(), Modifier.weight(1f))
                    MetricCard("Tasks done", p.completed.toString(), Modifier.weight(1f))
                }
            }
            item { Text("WORKSTREAMS", color = muted, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
            item {
                Column(Modifier.fillMaxWidth().background(Color.White, cardShape).padding(16.dp)) {
                    p.workstreams.forEach { w ->
                        Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == w.workstream.id) null else w.workstream.id }
                            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (expanded == w.workstream.id) "⌄" else "›", color = muted, fontSize = 23.sp)
                            Spacer(Modifier.width(12.dp))
                            Text(w.workstream.title, Modifier.weight(1f), color = ink, fontWeight = FontWeight.SemiBold)
                            Text(w.focusMs.asDuration(), color = ink)
                        }
                        if (expanded == w.workstream.id) {
                            w.tasks.forEach { t ->
                                Row(Modifier.fillMaxWidth().clickable { onTask(t.task.id) }
                                    .padding(start = 28.dp, top = 10.dp, bottom = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(t.task.title, Modifier.weight(1f), color = muted, fontSize = 14.sp)
                                    Text(t.focusMs.asDuration(), color = muted, fontSize = 14.sp)
                                    Text("  ›", color = muted)
                                }
                            }
                            val assigned = w.tasks.sumOf { it.focusMs }
                            if (w.focusMs > assigned) Text("Unassigned · ${(w.focusMs - assigned).asDuration()}",
                                Modifier.padding(start = 28.dp, bottom = 10.dp), color = muted, fontSize = 13.sp)
                        }
                        HorizontalDivider(color = line)
                    }
                    if (p.unassignedMs > 0) Text("Outside workstreams · ${p.unassignedMs.asDuration()}",
                        Modifier.padding(top = 12.dp), color = muted, fontSize = 13.sp)
                }
            }
            item { ProjectHourlyGraph(input, report, projectId, zone) }
            if (p.blockedMs > 0) item { Text("Recorded blocked time · ${p.blockedMs.asDuration()}", color = muted, fontSize = 13.sp) }
        }
    }
}

private fun hourlyFocus(input: PulseInput, report: PulseReport, projectId: String, zone: ZoneId): List<Long> {
    val bins = LongArray(16) // 06:00–22:00. Other hours remain included in project totals.
    var day = report.from.atZone(zone).toLocalDate()
    val last = report.to.atZone(zone).toLocalDate()
    while (day < last) {
        for (h in 6..21) {
            val a = day.atTime(h, 0).atZone(zone).toInstant()
            val b = day.atTime(h + 1, 0).atZone(zone).toInstant()
            val intervals = input.intervals.asSequence().filter {
                !it.paused && it.kind == PulseKind.FOCUS && it.projectId == projectId
            }.mapNotNull {
                val start = maxOf(it.start, a, report.from).toEpochMilli()
                val end = minOf(it.end, b, report.to).toEpochMilli()
                if (end > start) start to end else null
            }.sortedBy { it.first }.toList()
            var covered = 0L; var start = -1L; var end = -1L
            intervals.forEach { (x, y) ->
                if (start < 0) { start = x; end = y }
                else if (x <= end) end = maxOf(end, y)
                else { covered += end - start; start = x; end = y }
            }
            if (start >= 0) covered += end - start
            bins[h - 6] += covered
        }
        day = day.plusDays(1)
    }
    return bins.toList()
}

@Composable
private fun ProjectHourlyGraph(input: PulseInput, report: PulseReport, projectId: String, zone: ZoneId) {
    val bins = remember(input, report, projectId, zone) { hourlyFocus(input, report, projectId, zone) }
    val peak = bins.indices.maxByOrNull { bins[it] }?.takeIf { bins[it] > 0 }
    Column(Modifier.fillMaxWidth().background(Color.White, cardShape).padding(16.dp)) {
        Text("Focus by hour", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = ink)
        Spacer(Modifier.height(4.dp))
        Text(if (peak == null) "No recorded focus" else "Peak ${peak + 6}:00–${peak + 7}:00",
            color = muted, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val maxValue = bins.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            val dx = size.width / bins.size
            val path = Path().apply {
                moveTo(0f, size.height)
                bins.forEachIndexed { n, value ->
                    val y = size.height - (value.toFloat() / maxValue) * size.height * 0.9f
                    lineTo(n * dx, y); lineTo((n + 1) * dx, y)
                }
                lineTo(size.width, size.height); close()
            }
            drawPath(path, overlapGreen.copy(alpha = 0.28f))
            val outline = Path().apply {
                bins.forEachIndexed { n, value ->
                    val y = size.height - (value.toFloat() / maxValue) * size.height * 0.9f
                    if (n == 0) moveTo(0f, y) else lineTo(n * dx, y)
                    lineTo((n + 1) * dx, y)
                }
            }
            drawPath(outline, focusGreen, style = Stroke(width = 2.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("6am", "9am", "noon", "3pm", "6pm", "9pm").forEach {
                Text(it, fontSize = 10.sp, color = muted)
            }
        }
    }
}
