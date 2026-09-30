package com.virlin.app.ui.hierarchy

// Supplied drop-in Activity tab. Adapted where the app already owns what is being drawn:
//  - the record model gained NOTE and LINK kinds, a media path and a `hasSnapshot` flag,
//    because Virlin captures links and notes and because some historical rows genuinely
//    cannot be shown as they were (see ProjectActivity / TaskEventDetail),
//  - the illustrative waveform is replaced by the app's real MediaPlayer transport
//    (play/pause + seek) over VoiceFileStore, as used by the Voice editor,
//  - image previews decode from managed storage off the main thread; the empty default
//    lambda is never used by the production call site,
//  - prompt bodies are selectable and Copy puts the exact stored text on the clipboard,
//  - Android Back closes the detail and returns to the same filtered timeline,
//  - the host embeds this in a BOUNDED height, never inside a parent verticalScroll.

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.data.voice.VoiceFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Feed data comes from the app; never derive timestamps from list position. */
enum class ActivityKind { PROMPT, NOTE, LINK, IMAGE, AUDIO, FILE, TASK, CHECKLIST, EVENT }
data class ActivityRecord(
    val id: String,
    val projectId: String,
    val occurredAtMillis: Long,
    val kind: ActivityKind,
    val title: String,
    val description: String = "",
    val workstreamId: String? = null,
    val workstreamName: String? = null,
    val taskId: String? = null,
    val taskName: String? = null,
    val content: String? = null,
    val knowledgeId: String? = null,
    val assetId: String? = null,
    /** The capture this row came from, when it came from one. */
    val captureId: String? = null,
    val fileName: String? = null,
    val audioDurationSeconds: Int? = null,
    val completedSteps: Int? = null,
    val totalSteps: Int? = null,
    val oldStatus: String? = null,
    val newStatus: String? = null,
    val creatorLabel: String? = null,
    val sourceLabel: String? = null,
    /** Managed-storage path of the image / audio this row refers to. */
    val mediaPath: String? = null,
    /** False when the row can only be described from today's state, and says so. */
    val hasSnapshot: Boolean = true
)
data class ActivityWorkstream(val id: String, val name: String)
data class ActivityActions(
    val onBack: () -> Unit,
    val onOpenTask: (String) -> Unit,
    val onOpenKnowledge: (String) -> Unit,
    val onOpenAsset: (String) -> Unit,
    val onCopyPrompt: (String) -> Unit
)

private object AColor {
    val ink = Color(0xFF17202C)
    val muted = Color(0xFF647087)
    val line = Color(0xFFE6EAF0)
    val green = Color(0xFF54AF26)
    val greenWash = Color(0xFFF2FAEC)
    val amber = Color(0xFFBD7900)
    val amberWash = Color(0xFFFFF9EB)
    val coral = Color(0xFFD84B4B)
    val coralWash = Color(0xFFFFF1F1)
    val blue = Color(0xFF3569A5)
}

private fun ActivityKind.color() = when (this) {
    ActivityKind.PROMPT, ActivityKind.NOTE -> AColor.amber
    ActivityKind.LINK -> AColor.blue
    ActivityKind.IMAGE, ActivityKind.FILE -> AColor.muted
    ActivityKind.AUDIO -> AColor.blue
    ActivityKind.TASK -> AColor.green
    ActivityKind.CHECKLIST -> AColor.amber
    ActivityKind.EVENT -> AColor.coral
}
private fun ActivityKind.symbol() = when (this) {
    ActivityKind.PROMPT -> "▤"
    ActivityKind.NOTE -> "✎"
    ActivityKind.LINK -> "⚭"
    ActivityKind.IMAGE -> "▧"
    ActivityKind.AUDIO -> "♪"
    ActivityKind.FILE -> "▣"
    ActivityKind.TASK -> "✓"
    ActivityKind.CHECKLIST -> "☷"
    ActivityKind.EVENT -> "!"
}

private val dateText = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val timeText = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private fun ActivityRecord.date(zone: ZoneId) = java.time.Instant.ofEpochMilli(occurredAtMillis).atZone(zone).toLocalDate()
private fun ActivityRecord.time(zone: ZoneId) = java.time.Instant.ofEpochMilli(occurredAtMillis).atZone(zone).format(timeText)

/** Embed this under the host project's Activity tab; host owns project header and navigation. */
@Composable
fun VirlinProjectActivity(
    projectId: String,
    records: List<ActivityRecord>,
    workstreams: List<ActivityWorkstream>,
    actions: ActivityActions,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    loading: Boolean = false,
    errorMessage: String? = null,
    /** More history exists than this page holds. */
    hasMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    /** Real thumbnails by default — never an empty lambda in the production call site. */
    imagePreview: @Composable (String, Modifier) -> Unit = { path, m -> ActivityImage(path, m) }
) {
    val scoped = remember(projectId, records) { records.filter { it.projectId == projectId } }
    // The selection survives leaving this screen — opening a capture and coming back must not
    // silently drop the filters the viewer chose — so it is saved as plain strings rather than
    // held in composition only.
    var kindsCsv by rememberSaveable(projectId) { mutableStateOf("") }
    var selectedWorkstream by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    var dateCode by rememberSaveable(projectId) { mutableStateOf(DateSelection.All.encode()) }
    var newestFirst by rememberSaveable(projectId) { mutableStateOf(true) }
    val selectedKinds = remember(kindsCsv) { decodeKinds(kindsCsv) }
    val dateSelection = remember(dateCode) { decodeDate(dateCode) }
    var filterOpen by remember { mutableStateOf(false) }
    var calendarOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var selectedRecord by remember(projectId) { mutableStateOf<ActivityRecord?>(null) }
    // Hoisted above the detail branch: the list leaves composition while a record is open, and
    // a state created inside that branch would send the viewer back to the top on return.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // ...but a NEW selection is a new list: keeping the old offset would land the viewer in the
    // middle of results they have not seen. Changing filters scrolls back to the newest row.
    LaunchedEffect(selectedKinds, selectedWorkstream, dateSelection, newestFirst) {
        listState.scrollToItem(0)
    }

    val visible = remember(scoped, selectedKinds, selectedWorkstream, dateSelection, newestFirst, zone, today) {
        applyFilters(scoped, selectedKinds, selectedWorkstream, dateSelection, newestFirst, zone, today)
    }
    val openRecord = selectedRecord
    if (openRecord != null) {
        ActivityDetail(openRecord, zone, actions, imagePreview) { selectedRecord = null }
    } else {
        Column(modifier.fillMaxSize().background(Color.White)) {
            Text("Project activity", fontWeight = FontWeight.Bold, fontSize = 20.sp,
                color = AColor.ink, modifier = Modifier.padding(start = 20.dp, top = 16.dp))
            Text("Everything that happened in this project.", color = AColor.muted, fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, top = 3.dp, bottom = 12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallOutlineButton("☷  Filter", onClick = { filterOpen = true })
                Spacer(Modifier.width(7.dp))
                IconButton(onClick = { calendarOpen = true }) {
                    Icon(Icons.Default.CalendarMonth, "Choose day or month", tint = AColor.ink)
                }
                Spacer(Modifier.weight(1f))
                Box {
                    SmallOutlineButton(if (newestFirst) "Newest first ⌄" else "Oldest first ⌄", onClick = { sortOpen = true })
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        listOf(true to "Newest first", false to "Oldest first").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { newestFirst = value; sortOpen = false })
                        }
                    }
                }
            }
            val activeCount = selectedKinds.size + (if (selectedWorkstream == null) 0 else 1) +
                (if (dateSelection == DateSelection.All) 0 else 1)
            if (activeCount > 0) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$activeCount active filters", color = AColor.muted, fontSize = 12.sp)
                    Text("Clear all", color = AColor.green, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            kindsCsv = ""; selectedWorkstream = null; dateCode = DateSelection.All.encode()
                        })
                }
            }
            if (loading && scoped.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AColor.green, strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp))
                }
            } else if (errorMessage != null) {
                Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                    Text(errorMessage, color = AColor.coral)
                }
            } else if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (scoped.isEmpty()) "Nothing has happened in this project yet"
                        else "No activity for this selection",
                        color = AColor.muted
                    )
                }
            } else {
                val groups = visible.groupBy { it.date(zone) }
                LazyColumn(Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(bottom = OrbClearance)) {
                    groups.forEach { (day, dayRecords) ->
                        item(key = "day-$day") {
                            Text(when (day) {
                                today -> "Today · ${day.format(dateText)}"
                                today.minusDays(1) -> "Yesterday · ${day.format(dateText)}"
                                else -> day.format(dateText)
                            }, fontWeight = FontWeight.SemiBold, color = AColor.ink, fontSize = 14.sp,
                                modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
                        }
                        items(dayRecords, key = { it.id }) { record ->
                            ActivityRow(record, zone, imagePreview) { selectedRecord = record }
                        }
                    }
                    if (hasMore) {
                        item(key = "load-more") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                contentAlignment = Alignment.Center
                            ) { SmallOutlineButton("Load older activity", onClick = onLoadMore) }
                        }
                    }
                }
            }
        }
    }
    if (filterOpen) {
        ActivityFilterSheet(selectedKinds, selectedWorkstream, dateSelection, newestFirst,
            workstreams,
            countResults = { kinds, workstream, date ->
                applyFilters(scoped, kinds, workstream, date, true, zone, today).size
            },
            onDismiss = { filterOpen = false },
            onApply = { kinds, workstream, date, newest ->
                kindsCsv = kinds.joinToString(",") { it.name }
                selectedWorkstream = workstream
                dateCode = date.encode(); newestFirst = newest; filterOpen = false
            },
            onChooseDates = { filterOpen = false; calendarOpen = true })
    }
    if (calendarOpen) {
        ActivityCalendarSheet(
            today = today,
            initialDate = (dateSelection as? DateSelection.Day)?.date ?: today,
            activityDays = scoped.map { it.date(zone) }.toSet(),
            onDismiss = { calendarOpen = false },
            onDay = { dateCode = DateSelection.Day(it).encode(); calendarOpen = false },
            onMonth = { dateCode = DateSelection.Month(it).encode(); calendarOpen = false }
        )
    }
}

/** One place where the chips, the list and the sheet's result count all agree. */
private fun applyFilters(
    records: List<ActivityRecord>,
    kinds: Set<ActivityKind>,
    workstreamId: String?,
    date: DateSelection,
    newestFirst: Boolean,
    zone: ZoneId,
    today: LocalDate
): List<ActivityRecord> = records.asSequence()
    .filter { kinds.isEmpty() || it.kind in kinds }
    .filter { workstreamId == null || it.workstreamId == workstreamId }
    .filter { date.includes(it.date(zone), today) }
    .sortedWith(
        if (newestFirst) compareByDescending<ActivityRecord> { it.occurredAtMillis }.thenBy { it.id }
        else compareBy<ActivityRecord> { it.occurredAtMillis }.thenBy { it.id }
    )
    .toList()

private fun decodeKinds(csv: String): Set<ActivityKind> =
    csv.split(',').mapNotNull { name -> ActivityKind.entries.firstOrNull { it.name == name } }.toSet()

private fun DateSelection.encode(): String = when (this) {
    DateSelection.All -> "ALL"
    DateSelection.Today -> "TODAY"
    DateSelection.Week -> "WEEK"
    is DateSelection.Day -> "DAY:" + date
    is DateSelection.Month -> "MONTH:" + month
}

private fun decodeDate(code: String): DateSelection = when {
    code.startsWith("DAY:") -> runCatching { DateSelection.Day(LocalDate.parse(code.removePrefix("DAY:"))) }
        .getOrDefault(DateSelection.All)
    code.startsWith("MONTH:") -> runCatching { DateSelection.Month(YearMonth.parse(code.removePrefix("MONTH:"))) }
        .getOrDefault(DateSelection.All)
    code == "TODAY" -> DateSelection.Today
    code == "WEEK" -> DateSelection.Week
    else -> DateSelection.All
}

private sealed interface DateSelection {
    data object All : DateSelection
    data object Today : DateSelection
    data object Week : DateSelection
    data class Day(val date: LocalDate) : DateSelection
    data class Month(val month: YearMonth) : DateSelection
    fun includes(date: LocalDate, today: LocalDate) = when (this) {
        All -> true
        Today -> date == today
        Week -> !date.isAfter(today) && ChronoUnit.DAYS.between(date, today) in 0..6
        is Day -> date == this.date
        is Month -> YearMonth.from(date) == month
    }
}

@Composable
private fun SmallOutlineButton(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(11.dp), color = Color.White,
        border = BorderStroke(1.dp, AColor.line)) {
        Box(Modifier.defaultMinSize(minHeight = 44.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, color = AColor.ink, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ActivityRow(record: ActivityRecord, zone: ZoneId,
    imagePreview: @Composable (String, Modifier) -> Unit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).defaultMinSize(minHeight = 72.dp)
        .padding(start = 20.dp, end = 14.dp), verticalAlignment = Alignment.Top) {
        Text(record.time(zone), modifier = Modifier.width(49.dp).padding(top = 14.dp),
            fontSize = 12.sp, color = AColor.muted)
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(16.dp).heightIn(min = 72.dp)) {
            Box(Modifier.padding(top = 18.dp).size(9.dp).background(record.kind.color(), CircleShape))
            Spacer(Modifier.width(1.dp).height(59.dp).background(AColor.line))
        }
        Spacer(Modifier.width(9.dp))
        Box(Modifier.padding(top = 8.dp).size(34.dp)
            .background(record.kind.color().copy(alpha = 0.10f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center) {
            Text(record.kind.symbol(), fontWeight = FontWeight.Bold, color = record.kind.color(), fontSize = 17.sp)
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f).padding(top = 9.dp, bottom = 10.dp)) {
            Text(record.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                color = AColor.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val context = listOfNotNull(record.workstreamName, record.taskName).joinToString(" · ")
            if (context.isNotEmpty()) Text(context, fontSize = 11.sp, color = AColor.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (record.kind) {
                ActivityKind.IMAGE -> record.mediaPath?.let {
                    imagePreview(it, Modifier.padding(top = 6.dp).fillMaxWidth().height(70.dp))
                }
                ActivityKind.AUDIO -> Text(
                    "Recording · " + formatDuration(record.audioDurationSeconds),
                    fontSize = 12.sp, color = AColor.blue
                )
                ActivityKind.CHECKLIST -> Text("${record.completedSteps ?: 0} of ${record.totalSteps ?: 0} steps completed",
                    fontSize = 11.sp, color = AColor.muted)
                ActivityKind.TASK -> {
                    if (record.oldStatus != null && record.newStatus != null) {
                        Text("${record.oldStatus} → ${record.newStatus}", fontSize = 11.sp, color = AColor.green)
                    } else if (record.description.isNotEmpty()) {
                        Text(record.description, fontSize = 11.sp, color = AColor.muted,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                else -> if (record.description.isNotEmpty()) {
                    Text(record.description, fontSize = 11.sp, color = AColor.muted,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open ${record.title}", tint = AColor.ink,
            modifier = Modifier.padding(top = 15.dp).size(20.dp))
    }
    HorizontalDivider(Modifier.padding(start = 94.dp, end = 20.dp), color = AColor.line)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ActivityFilterSheet(kinds: Set<ActivityKind>, workstreamId: String?, date: DateSelection,
    newest: Boolean, workstreams: List<ActivityWorkstream>,
    countResults: (Set<ActivityKind>, String?, DateSelection) -> Int,
    onDismiss: () -> Unit,
    onApply: (Set<ActivityKind>, String?, DateSelection, Boolean) -> Unit,
    onChooseDates: () -> Unit) {
    var draftKinds by remember(kinds) { mutableStateOf(kinds) }
    var draftWorkstream by remember(workstreamId) { mutableStateOf(workstreamId) }
    var draftDate by remember(date) { mutableStateOf(date) }
    var draftNewest by remember(newest) { mutableStateOf(newest) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Filter activity", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AColor.ink)
            Spacer(Modifier.height(14.dp))
            SheetTitle("Type")
            ChoiceGrid {
                Choice("All", draftKinds.isEmpty()) { draftKinds = emptySet() }
                ActivityKind.entries.forEach { kind ->
                    Choice(kind.name.lowercase().replaceFirstChar { it.uppercase() }, kind in draftKinds) {
                        draftKinds = if (kind in draftKinds) draftKinds - kind else draftKinds + kind
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SheetTitle("Workstream")
            ChoiceGrid {
                Choice("All workstreams", draftWorkstream == null) { draftWorkstream = null }
                workstreams.forEach { item ->
                    Choice(item.name, item.id == draftWorkstream) { draftWorkstream = item.id }
                }
            }
            Spacer(Modifier.height(12.dp))
            SheetTitle("Time")
            ChoiceGrid {
                Choice("Any time", draftDate == DateSelection.All) { draftDate = DateSelection.All }
                Choice("Today", draftDate == DateSelection.Today) { draftDate = DateSelection.Today }
                Choice("7 days", draftDate == DateSelection.Week) { draftDate = DateSelection.Week }
                Choice("Choose dates", draftDate is DateSelection.Day || draftDate is DateSelection.Month) { onChooseDates() }
            }
            Spacer(Modifier.height(12.dp))
            SheetTitle("Order")
            ChoiceGrid {
                Choice("Newest first", draftNewest) { draftNewest = true }
                Choice("Oldest first", !draftNewest) { draftNewest = false }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    draftKinds = emptySet(); draftWorkstream = null
                    draftDate = DateSelection.All; draftNewest = true
                }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Reset") }
                val count = countResults(draftKinds, draftWorkstream, draftDate)
                Button(onClick = { onApply(draftKinds, draftWorkstream, draftDate, draftNewest) },
                    colors = ButtonDefaults.buttonColors(containerColor = AColor.green),
                    modifier = Modifier.weight(2f).height(48.dp)) {
                    Text(if (count == 1) "Show 1 result" else "Show $count results")
                }
            }
        }
    }
}

@Composable private fun SheetTitle(title: String) {
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AColor.ink)
    Spacer(Modifier.height(6.dp))
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ChoiceGrid(content: @Composable FlowRowScope.() -> Unit) {
    // FlowRow wraps choices on narrower devices and with longer workstream names.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content
    )
}
@Composable private fun Choice(label: String, chosen: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp),
        color = if (chosen) AColor.greenWash else Color.White,
        border = BorderStroke(1.dp, if (chosen) AColor.green else AColor.line)) {
        Box(Modifier.defaultMinSize(minHeight = 40.dp).padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, color = AColor.ink)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityCalendarSheet(today: LocalDate, initialDate: LocalDate, activityDays: Set<LocalDate>,
    onDismiss: () -> Unit, onDay: (LocalDate) -> Unit, onMonth: (YearMonth) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(initialDate)) }
    var selected by remember { mutableStateOf(initialDate) }
    var yearMenu by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Select date", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AColor.ink,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.Default.KeyboardArrowLeft, "Previous month")
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { yearMenu = true }) {
                        Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)) + " ⌄",
                            color = AColor.ink, fontWeight = FontWeight.SemiBold)
                    }
                    DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                        (-2..2).forEach { offset ->
                            val year = month.year + offset
                            DropdownMenuItem(text = { Text("$year") }, onClick = {
                                month = YearMonth.of(year, month.month); yearMenu = false
                            })
                        }
                    }
                }
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.Default.KeyboardArrowRight, "Next month")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
                    Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) {
                        Text(it, color = AColor.muted, fontSize = 11.sp)
                    }
                }
            }
            val startOffset = month.atDay(1).dayOfWeek.value - 1
            val cells = ((startOffset + month.lengthOfMonth() + 6) / 7) * 7
            repeat(cells / 7) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { weekday ->
                        val dayNumber = week * 7 + weekday - startOffset + 1
                        val day = if (dayNumber in 1..month.lengthOfMonth()) month.atDay(dayNumber) else null
                        Column(Modifier.weight(1f).height(47.dp)
                            .then(if (day != null) Modifier.clickable { selected = day } else Modifier),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            Box(Modifier.size(30.dp).background(
                                if (day == selected) AColor.green else Color.Transparent, CircleShape),
                                contentAlignment = Alignment.Center) {
                                Text(day?.dayOfMonth?.toString() ?: "", fontSize = 13.sp,
                                    color = if (day == selected) Color.White else AColor.ink)
                            }
                            Box(Modifier.size(4.dp).background(
                                if (day != null && day in activityDays) AColor.green else Color.Transparent, CircleShape))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Dots indicate days with activity", color = AColor.muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onMonth(month) }, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text("Entire month")
                }
                Button(onClick = { onDay(selected) }, modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AColor.green)) {
                    Text("Jump to day")
                }
            }
        }
    }
}

@Composable
private fun ActivityDetail(record: ActivityRecord, zone: ZoneId, actions: ActivityActions,
    imagePreview: @Composable (String, Modifier) -> Unit, onBack: () -> Unit) {
    // Back returns to the timeline with its filters and scroll position intact, because the
    // timeline is never torn down: the detail is a state of the same tab.
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to activity") }
            Text("Activity", color = AColor.ink, fontSize = 15.sp)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(record.title, color = AColor.ink, fontWeight = FontWeight.Bold, fontSize = 23.sp,
                lineHeight = 29.sp)
            Spacer(Modifier.height(10.dp))
            Text("${record.kind.name} · ${record.date(zone).format(dateText)}, ${record.time(zone)}",
                fontSize = 12.sp, color = AColor.muted)
            Spacer(Modifier.height(22.dp))
            Text(listOfNotNull(record.workstreamName, record.taskName).joinToString(" / "),
                color = AColor.muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = AColor.line)
            Spacer(Modifier.height(20.dp))
            when (record.kind) {
                ActivityKind.PROMPT, ActivityKind.NOTE, ActivityKind.LINK -> {
                    Surface(shape = RoundedCornerShape(14.dp), color = AColor.amberWash) {
                        Column(Modifier.fillMaxWidth().padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(record.kind.name, fontSize = 11.sp, color = AColor.amber, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = { record.content?.let(actions.onCopyPrompt) }) {
                                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp)); Text("Copy")
                                }
                            }
                            // Selectable and verbatim: line breaks, lists and code fences intact.
                            SelectionContainer {
                                Text(record.content ?: record.description, fontSize = 16.sp,
                                    lineHeight = 25.sp, color = AColor.ink)
                            }
                        }
                    }
                }
                ActivityKind.IMAGE -> {
                    record.mediaPath?.let { path ->
                        // Tap opens the capture's own full-screen viewer.
                        Surface(
                            onClick = { record.captureId?.let(actions.onOpenAsset) },
                            shape = RoundedCornerShape(14.dp), color = Color(0xFFF6F7F9)
                        ) { imagePreview(path, Modifier.fillMaxWidth().height(250.dp)) }
                    }
                    Text(record.fileName ?: record.description, color = AColor.muted,
                        modifier = Modifier.padding(top = 12.dp))
                }
                ActivityKind.AUDIO -> {
                    if (record.mediaPath != null) ActivityAudioPlayer(record)
                    else Text("The recording for this entry is no longer available.", color = AColor.muted)
                    record.content?.takeIf { it.isNotBlank() }?.let {
                        Text(it, modifier = Modifier.padding(top = 15.dp), color = AColor.ink)
                    }
                }
                ActivityKind.CHECKLIST -> {
                    Text("${record.completedSteps ?: 0} of ${record.totalSteps ?: 0} steps completed",
                        fontWeight = FontWeight.SemiBold, color = AColor.ink)
                    Text("Current saved state — Virlin keeps no per-step history.",
                        fontSize = 12.sp, color = AColor.muted, modifier = Modifier.padding(top = 4.dp))
                    record.content?.let { Text(it, modifier = Modifier.padding(top = 12.dp), color = AColor.ink) }
                }
                ActivityKind.TASK, ActivityKind.EVENT -> {
                    Text(listOfNotNull(record.oldStatus, record.newStatus).joinToString("  →  "),
                        fontWeight = FontWeight.SemiBold, color = record.kind.color(), fontSize = 18.sp)
                    Text(record.content ?: record.description, color = AColor.ink,
                        modifier = Modifier.padding(top = 14.dp))
                }
                ActivityKind.FILE -> {
                    record.captureId?.let { id ->
                        SmallOutlineButton(record.fileName ?: "Open file") { actions.onOpenAsset(id) }
                    }
                    Text(record.description, modifier = Modifier.padding(top = 12.dp), color = AColor.muted)
                }
            }
            Spacer(Modifier.height(26.dp))
            HorizontalDivider(color = AColor.line)
            if (record.knowledgeId != null) {
                DetailLink("Open in Knowledge") { actions.onOpenKnowledge(record.knowledgeId) }
            }
            if (!record.hasSnapshot) {
                Text(
                    "Recorded before Virlin stored a snapshot of this entry, so only what it " +
                        "still references can be shown.",
                    fontSize = 12.sp, color = AColor.muted, modifier = Modifier.padding(top = 14.dp)
                )
            }
            record.taskId?.let { id -> DetailLink("Open task") { actions.onOpenTask(id) } }
            Spacer(Modifier.height(14.dp))
            Text(listOfNotNull(record.creatorLabel, record.sourceLabel).joinToString(" · "),
                color = AColor.muted, fontSize = 12.sp)
        }
    }
}

@Composable private fun DetailLink(text: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(55.dp).clickable(onClick = action),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), color = AColor.ink, fontSize = 14.sp)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = AColor.muted)
    }
    HorizontalDivider(color = AColor.line)
}


// ---------------------------------------------------------------- real media

private fun formatDuration(seconds: Int?): String {
    val total = (seconds ?: 0).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Thumbnail straight from Virlin-managed storage. Decoding happens off the main thread and is
 * sub-sampled, so a long timeline does not decode full-size bitmaps while it scrolls.
 */
@Composable
fun ActivityImage(relativePath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(relativePath) { mutableStateOf<Bitmap?>(null) }
    var loaded by remember(relativePath) { mutableStateOf(false) }
    LaunchedEffect(relativePath) {
        loaded = false
        bitmap = withContext(Dispatchers.IO) {
            val file = resolveMedia(context, relativePath) ?: return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1080) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
        loaded = true
    }
    val image = bitmap
    if (image == null) {
        // Loading, then a neutral placeholder if the file is gone: never a coloured block
        // pretending to be a picture.
        Box(
            modifier.background(Color(0xFFF1F3F6), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (loaded) Text("▧", fontSize = 22.sp, color = Color(0xFF9AA4B2))
            else CircularProgressIndicator(
                strokeWidth = 2.dp, color = Color(0xFFBFC7D2),
                modifier = Modifier.size(18.dp)
            )
        }
    } else {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        )
    }
}

/** A recording lives under the voice store; an imported audio file under the attachment store. */
private fun resolveMedia(context: android.content.Context, relativePath: String): File? {
    val voice = VoiceFileStore.resolve(context, relativePath)
    if (voice.exists()) return voice
    val attachment = AttachmentFileStore.resolve(context, relativePath)
    return attachment.takeIf { it.exists() }
}

/**
 * The same MediaPlayer transport the Voice editor uses — play/pause and seek over the real
 * file, with the real duration. No illustrative waveform.
 */
@Composable
private fun ActivityAudioPlayer(record: ActivityRecord) {
    VirlinAudioTransport(
        relativePath = record.mediaPath ?: return,
        fallbackDurationMillis = (record.audioDurationSeconds ?: 0) * 1000L,
        label = record.title
    )
}

/**
 * The app's audio transport: play/pause and seek over a file in managed storage, using the same
 * MediaPlayer the Voice editor uses. Shared by Activity and by the Knowledge library so a
 * recording behaves identically wherever it is shown.
 */
@Composable
internal fun VirlinAudioTransport(
    relativePath: String,
    fallbackDurationMillis: Long,
    label: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val path = relativePath
    var playing by remember(path) { mutableStateOf(false) }
    var position by remember(path) { mutableStateOf(0) }
    var duration by remember(path) { mutableStateOf(fallbackDurationMillis.toInt()) }
    val player = remember(path) {
        runCatching {
            val file = resolveMedia(context, path) ?: return@runCatching null
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                duration = this.duration.coerceAtLeast(duration)
            }
        }.getOrNull()
    }
    DisposableEffect(player) {
        onDispose { runCatching { player?.stop() }; player?.release() }
    }
    LaunchedEffect(playing, player) {
        val active = player ?: return@LaunchedEffect
        if (playing) {
            active.start()
            while (isActive && active.isPlaying) {
                position = active.currentPosition
                delay(200)
            }
            if (!active.isPlaying) { position = 0; playing = false }
        } else if (active.isPlaying) {
            active.pause()
        }
    }
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = Color(0xFFF2F6FB)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { playing = !playing },
                    enabled = player != null,
                    modifier = Modifier.semantics {
                        contentDescription = if (playing) "Pause $label" else "Play $label"
                    }
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null, tint = AColor.blue
                    )
                }
                Slider(
                    value = position.toFloat(),
                    onValueChange = { value ->
                        position = value.toInt()
                        player?.seekTo(position)
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(formatDuration(duration / 1000), color = AColor.ink, fontSize = 12.sp)
            }
            if (player == null) {
                Text("This recording could not be opened.", color = AColor.muted, fontSize = 12.sp)
            }
        }
    }
}
