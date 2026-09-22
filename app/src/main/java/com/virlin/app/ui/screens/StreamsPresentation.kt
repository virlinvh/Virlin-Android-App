package com.virlin.app.ui.screens

import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.theme.StreamsSectionColors
import com.virlin.app.ui.theme.StreamsSectionLook
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Streams sections — ONE semantic identity per existing application state. Every section maps
 * to a real `StreamState`; nothing here is invented for visual variety (there is no failed /
 * error state in the domain, so there is no Grapefruit section).
 */
enum class StreamsSection(
    val title: String,
    /** Concise meaning shown under the title (never a fabricated statistic). */
    val meaning: String,
    /** Calm message when a filter shows this section with nothing in it. */
    val emptyMessage: String,
    val state: StreamState?
) {
    PROJECTS("PROJECTS", "Containers of work", "No projects yet.", null),
    FOCUS("FOCUS", "What you're doing now", "Nothing in focus.", StreamState.FOCUS),
    NEEDS_YOU("NEEDS YOU", "Attention required", "Nothing needs your attention.", StreamState.NEEDS_YOU),
    PROCESSING("PROCESSING", "Working without you", "Nothing is running right now.", StreamState.PROCESSING),
    READY("READY", "Can be started now", "Nothing is ready to start.", StreamState.READY),
    SNOOZED("SNOOZED", "Deferred on purpose", "Nothing is snoozed.", StreamState.SNOOZED),
    BLOCKED("BLOCKED", "Can't proceed yet", "No blocked work.", StreamState.BLOCKED),
    COMPLETED("RECENTLY COMPLETED", "Closed in the last 24 hours", "Nothing completed recently.", StreamState.DONE);

    val look: StreamsSectionLook
        get() = when (this) {
            PROJECTS -> StreamsSectionColors.Projects
            FOCUS -> StreamsSectionColors.Focus
            NEEDS_YOU -> StreamsSectionColors.NeedsYou
            PROCESSING -> StreamsSectionColors.Processing
            READY -> StreamsSectionColors.Ready
            SNOOZED -> StreamsSectionColors.Snoozed
            BLOCKED -> StreamsSectionColors.Blocked
            COMPLETED -> StreamsSectionColors.Completed
        }

    /** Spoken state for screen readers (colour is never the only indicator). */
    val spoken: String get() = title.lowercase().replaceFirstChar { it.uppercase() }
}

/** The filter rail. Labels are the existing ones; Snoozed / Blocked only narrow what is shown. */
enum class StreamsFilter(val label: String, val section: StreamsSection?) {
    ALL("All", null),
    PROJECTS("Projects", StreamsSection.PROJECTS),
    NEED_YOU("Need You", StreamsSection.NEEDS_YOU),
    PROCESSING("Processing", StreamsSection.PROCESSING),
    READY("Ready", StreamsSection.READY),
    SNOOZED("Snoozed", StreamsSection.SNOOZED),
    BLOCKED("Blocked", StreamsSection.BLOCKED);

    companion object {
        fun byLabel(label: String): StreamsFilter = entries.firstOrNull { it.label == label } ?: ALL
    }
}

/** Domain facts a display row does not carry, keyed by stream id (read from the repository snapshot). */
data class StreamsDomainFacts(
    val snoozedUntil: Instant? = null,
    val blockerReason: String? = null,
    val completedAt: Instant? = null
)

/** One rendered section: which sections appear, in this order, is decided HERE (pure, testable). */
data class StreamsSectionItems(val section: StreamsSection, val streams: List<WorkStream>)

object StreamsPresentation {

    /** How long a completion stays in RECENTLY COMPLETED. */
    val recentWindow: Duration = Duration.ofHours(24)
    const val maxRecentlyCompleted = 5

    /** Search = existing semantics: title or subtitle contains the query (case-insensitive). */
    fun search(streams: List<WorkStream>, query: String): List<WorkStream> =
        if (query.isEmpty()) streams
        else streams.filter { it.title.contains(query, ignoreCase = true) || it.subtitle.contains(query, ignoreCase = true) }

    /**
     * The stream sections to render for [filter], in the approved order. Under `All` a section
     * with no items is omitted (the screen shows nothing for it); under a specific filter the
     * section is always returned so the UI can show its empty state. Rows keep [streams]' order —
     * the ordering of every state (incl. Needs You priority) is decided upstream, never here.
     */
    fun sections(
        streams: List<WorkStream>,
        filter: StreamsFilter,
        facts: Map<String, StreamsDomainFacts>,
        now: Instant
    ): List<StreamsSectionItems> {
        fun of(section: StreamsSection): List<WorkStream> = when (section) {
            StreamsSection.COMPLETED -> recentlyCompleted(streams, facts, now)
            else -> streams.filter { it.state == section.state }
        }.distinctBy { it.id }
        val ordered = listOf(
            StreamsSection.FOCUS, StreamsSection.NEEDS_YOU, StreamsSection.PROCESSING, StreamsSection.READY,
            StreamsSection.SNOOZED, StreamsSection.BLOCKED, StreamsSection.COMPLETED
        )
        return when (filter) {
            StreamsFilter.ALL -> ordered.map { StreamsSectionItems(it, of(it)) }.filter { it.streams.isNotEmpty() }
            StreamsFilter.PROJECTS -> emptyList()
            else -> listOf(StreamsSectionItems(filter.section!!, of(filter.section)))
        }
    }

    /** DONE streams with a real `completedAt` inside [recentWindow], newest first, capped. */
    fun recentlyCompleted(streams: List<WorkStream>, facts: Map<String, StreamsDomainFacts>, now: Instant): List<WorkStream> =
        streams.filter { it.state == StreamState.DONE }
            .mapNotNull { s -> facts[s.id]?.completedAt?.takeIf { !it.isBefore(now.minus(recentWindow)) && !it.isAfter(now) }?.let { s to it } }
            .sortedByDescending { it.second }
            .take(maxRecentlyCompleted)
            .map { it.first }

    /** `mm:ss` below one hour, `h:mm:ss` from one hour — timers never clip to a wrong value. */
    fun clock(totalSeconds: Int): String {
        val t = totalSeconds.coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** `~5m`, `~1h 30m` from a real estimate; null when the stream carries none (nothing is invented). */
    fun estimate(expectedDurationSec: Int?): String? {
        val sec = expectedDurationSec ?: return null
        if (sec <= 0) return null
        val m = (sec + 59) / 60
        return if (m >= 60) "~${m / 60}h" + (if (m % 60 > 0) " ${m % 60}m" else "") else "~${m}m"
    }

    /** "until 3:05 PM" today, otherwise "until Tue 9:00 AM" — device zone and locale. */
    fun wakeLabel(until: Instant?, now: Instant, zone: ZoneId): String? {
        until ?: return null
        val u = until.atZone(zone); val n = now.atZone(zone)
        val time = u.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        return if (u.toLocalDate() == n.toLocalDate()) "until $time"
        else "until " + u.format(DateTimeFormatter.ofPattern("EEE")) + " " + time
    }

    /** "2 min ago" / "3 h ago" for a completion stamp. */
    fun completedLabel(completedAt: Instant?, now: Instant): String? {
        completedAt ?: return null
        val min = Duration.between(completedAt, now).toMinutes().coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            else -> "${min / 60} h ago"
        }
    }
}
