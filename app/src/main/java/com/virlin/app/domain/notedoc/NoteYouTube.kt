package com.virlin.app.domain.notedoc

import java.net.URI

/**
 * Reads YouTube links and clip times for video blocks. Pure and offline: nothing here fetches,
 * and a video id only comes out of it after matching [NoteDocVideo.VIDEO_ID].
 */
object NoteYouTube {

    /** A recognised link: the video and, when the link carries one, where it starts. */
    data class Link(val videoId: String, val startSeconds: Int? = null)

    private val URL_IN_TEXT = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val BARE_HOST = Regex("^(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/\\S*",
        RegexOption.IGNORE_CASE)
    private val UNITS = Regex("^(?:(\\d{1,3})h)?(?:(\\d{1,4})m)?(?:(\\d{1,6})s?)?$", RegexOption.IGNORE_CASE)

    /**
     * The video in [text]: a youtu.be, watch, shorts, live or embed link (also inside other text,
     * or without https://), or a bare 11-character id. Null when there is no YouTube video in it.
     */
    fun parse(text: String): Link? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 4000) return null
        if (trimmed.length == 11 && NoteDocVideo.VIDEO_ID.matches(trimmed)) return Link(trimmed)
        val candidates = URL_IN_TEXT.findAll(trimmed).map { it.value }.toMutableList()
        if (candidates.isEmpty() && BARE_HOST.containsMatchIn(trimmed)) candidates += "https://" + trimmed.substringBefore(' ')
        return candidates.firstNotNullOfOrNull(::fromUrl)
    }

    private fun fromUrl(raw: String): Link? {
        val uri = runCatching { URI(raw.trimEnd('.', ',', ')', ']', '>', '"', '\'')) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = queryPairs(uri.rawQuery)
        val id = when (host) {
            "youtu.be" -> segments.firstOrNull()
            "youtube.com", "youtube-nocookie.com" -> when (segments.firstOrNull()) {
                "watch" -> query["v"]
                "shorts", "live", "embed", "v", "e" -> segments.getOrNull(1)
                else -> null
            }
            else -> null
        } ?: return null
        if (!NoteDocVideo.VIDEO_ID.matches(id)) return null
        val fragmentTime = uri.rawFragment?.let { f -> queryPairs(f)["t"] }
        val start = (query["t"] ?: query["start"] ?: fragmentTime)?.let(::parseTime)
        return Link(id, start)
    }

    /**
     * Seconds from "90", "1:30", "1:02:03", "1h2m3s", "2m" or "45s". Null for anything else,
     * including minutes or seconds past 59 in a colon time.
     */
    fun parseTime(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 12) return null
        if (':' in t) {
            val parts = t.split(':')
            if (parts.size !in 2..3 || parts.any { p -> p.isEmpty() || p.length > 4 || !p.all(Char::isDigit) }) return null
            val numbers = parts.map { it.toInt() }
            if (numbers.drop(1).any { it > 59 }) return null
            return if (numbers.size == 2) numbers[0] * 60 + numbers[1]
            else numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        }
        val m = UNITS.matchEntire(t) ?: return null
        if (m.groupValues.drop(1).all { it.isEmpty() }) return null
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val s = m.groupValues[3].toIntOrNull() ?: 0
        return h * 3600 + min * 60 + s
    }

    /** "0:05", "12:30", "1:02:03". */
    fun formatTime(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /**
     * The digits of a clip time typed on a number pad, filled from the right like a timer:
     * "130" is 1:30 and "10230" is 1:02:30. Anything else in [text] is dropped; at most six digits.
     */
    fun clockDigits(text: String): String = text.filter { it in '0'..'9' }.trimStart('0').take(6)

    /** "" stays empty; otherwise "0:05", "1:30", "10:05", "1:02:30". */
    fun formatClockDigits(digits: String): String {
        if (digits.isEmpty()) return ""
        val padded = digits.padStart(3, '0')
        val seconds = padded.takeLast(2)
        val rest = padded.dropLast(2)
        if (rest.length <= 2) return "${rest.toInt()}:$seconds"
        return "${rest.dropLast(2).toInt()}:${rest.takeLast(2)}:$seconds"
    }

    /** Seconds in typed digits; minutes or seconds past 59 simply carry over ("90" is 1:30). */
    fun clockDigitsSeconds(digits: String): Int {
        if (digits.isEmpty()) return 0
        val padded = digits.padStart(6, '0')
        return padded.take(2).toInt() * 3600 + padded.substring(2, 4).toInt() * 60 + padded.takeLast(2).toInt()
    }

    /** The digits that show [seconds] the canonical way: 90 becomes "130". */
    fun secondsToClockDigits(seconds: Int): String = clockDigits(formatTime(seconds))

    /** "1:30 – 2:45", "From 1:30" or "Whole video". */
    fun rangeLabel(video: NoteDocVideo): String = when {
        video.endSeconds != null -> "${formatTime(video.startSeconds)} – ${formatTime(video.endSeconds)}"
        video.startSeconds > 0 -> "From ${formatTime(video.startSeconds)}"
        else -> "Whole video"
    }

    /** The plain-text stand-in used wherever a note is read as text (copy, export, previews). */
    fun plainText(video: NoteDocVideo?): String {
        if (video == null) return "[Video]"
        val title = video.title?.takeIf { it.isNotBlank() } ?: "YouTube video"
        return "[Video: $title · ${rangeLabel(video)}] ${video.watchUrl}"
    }

    private fun queryPairs(query: String?): Map<String, String> = query.orEmpty().split('&')
        .filter { it.isNotBlank() }
        .associate { part -> part.substringBefore('=') to part.substringAfter('=', "") }
}
