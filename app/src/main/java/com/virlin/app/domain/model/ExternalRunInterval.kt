package com.virlin.app.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.Duration

data class ExternalRunInterval(val ownerId: String, val taskId: String?, val start: Instant, val end: Instant)
data class DailyExecutionTotals(val externalSeconds: Long, val freedSeconds: Long)

object DailyExecution {
    fun calculate(runs: List<ExternalRunInterval>, sessions: List<FocusSession>, now: Instant, zone: ZoneId): DailyExecutionTotals {
        val day = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val unattended = mutableListOf<Pair<Instant, Instant>>()
        var external = 0L
        runs.distinct().forEach { run ->
            val start = maxOf(day, run.start); val end = minOf(now, run.end)
            if (end <= start) return@forEach
            external += Duration.between(start, end).seconds
            var pieces = listOf(start to end)
            sessions.distinctBy { it.id }.filter {
                if (run.taskId != null) it.taskId == run.taskId else it.workStreamId == run.ownerId
            }.forEach { focus ->
                val a = focus.startedAt; val b = focus.endedAt ?: now
                pieces = pieces.flatMap { (x, y) ->
                    if (b <= x || a >= y) listOf(x to y)
                    else listOfNotNull((x to minOf(y, a)).takeIf { it.second > it.first },
                        (maxOf(x, b) to y).takeIf { it.second > it.first })
                }
            }
            unattended += pieces
        }
        var merged = 0L; var from: Instant? = null; var to: Instant? = null
        unattended.sortedBy { it.first }.forEach { (a, b) ->
            if (from == null) { from = a; to = b }
            else if (a <= to!!) to = maxOf(to!!, b)
            else { merged += Duration.between(from, to).seconds; from = a; to = b }
        }
        if (from != null) merged += Duration.between(from, to).seconds
        return DailyExecutionTotals(external, merged)
    }
}
