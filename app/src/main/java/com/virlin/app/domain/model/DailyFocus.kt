package com.virlin.app.domain.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Read-only human-attention total. Roll-ups and external executions are not inputs. */
object DailyFocus {
    fun seconds(sessions: Collection<FocusSession>, now: Instant, zone: ZoneId): Long {
        val date = now.atZone(zone).toLocalDate()
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        return sessions.distinctBy { it.id }.sumOf { session ->
            val start = maxOf(session.startedAt, dayStart)
            val end = minOf(session.endedAt ?: now, now, dayEnd)
            if (end <= start) 0L else Duration.between(start, end).seconds
        }
    }

    fun label(seconds: Long): String = if (seconds <= 0) "0m" else FocusInvestment.formatCompact(seconds)
}
