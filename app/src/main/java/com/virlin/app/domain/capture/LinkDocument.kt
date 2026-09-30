package com.virlin.app.domain.capture

import com.virlin.app.domain.note.JsonObj
import java.net.URI

/** Extra Link-workspace state stored inside CaptureItem.content; legacy plain notes still decode. */
data class LinkDocument(
    val note: String = "",
    val showPreview: Boolean = true,
    val playbackEnabled: Boolean = false,
    val startSeconds: Int? = null,
    val endSeconds: Int? = null,
)

object LinkDocumentCodec {
    fun encode(value: LinkDocument): String = buildString {
        append("{\"linkV\":1,\"note\":")
        appendJson(value.note)
        append(",\"showPreview\":").append(value.showPreview)
        append(",\"playbackEnabled\":").append(value.playbackEnabled)
        append(",\"startSeconds\":").append(value.startSeconds ?: -1)
        append(",\"endSeconds\":").append(value.endSeconds ?: -1)
        append('}')
    }

    fun decode(raw: String): LinkDocument {
        val root = runCatching { JsonObj.parse(raw) }.getOrNull()
        if (root?.int("linkV") != 1) return LinkDocument(note = raw)
        return LinkDocument(
            note = root.strOrNull("note").orEmpty(),
            showPreview = root.bool("showPreview"),
            playbackEnabled = root.bool("playbackEnabled"),
            startSeconds = root.int("startSeconds").takeIf { it >= 0 },
            endSeconds = root.int("endSeconds").takeIf { it >= 0 },
        )
    }

    private fun StringBuilder.appendJson(value: String) {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

enum class LinkProvider { YOUTUBE, INSTAGRAM, VIDEO, WEBSITE }

data class LinkPreview(
    val provider: LinkProvider,
    val host: String,
    val thumbnailUrl: String? = null,
    val suggestedTitle: String? = null,
)

/** Deterministic provider/thumbnail and playback URL rules. No network and no hidden redirects. */
object LinkPresentation {
    fun preview(canonicalUrl: String): LinkPreview {
        val uri = URI(canonicalUrl)
        val host = uri.host.orEmpty().removePrefix("www.").lowercase()
        val youtubeId = youtubeVideoId(uri)
        return when {
            youtubeId != null -> LinkPreview(
                LinkProvider.YOUTUBE, host,
                thumbnailUrl = "https://i.ytimg.com/vi/$youtubeId/hqdefault.jpg",
                suggestedTitle = "YouTube video",
            )
            host == "instagram.com" || host.endsWith(".instagram.com") ->
                LinkPreview(LinkProvider.INSTAGRAM, host, suggestedTitle = "Instagram video")
            uri.path.orEmpty().lowercase().substringAfterLast('.', "") in setOf("mp4", "webm", "mov", "m4v") ->
                LinkPreview(LinkProvider.VIDEO, host, suggestedTitle = "Video link")
            else -> LinkPreview(LinkProvider.WEBSITE, host)
        }
    }

    fun supportsPlayback(canonicalUrl: String): Boolean = preview(canonicalUrl).provider != LinkProvider.WEBSITE

    /** Validated ID used by the trusted YouTube embed; null for every non-YouTube URL. */
    fun youtubeVideoId(canonicalUrl: String): String? =
        runCatching { youtubeVideoId(URI(canonicalUrl)) }.getOrNull()

    /** YouTube supports a query start. Direct videos use a media fragment. Instagram keeps its URL unchanged. */
    fun playableUrl(canonicalUrl: String, document: LinkDocument): String {
        if (!document.playbackEnabled) return canonicalUrl
        val start = document.startSeconds ?: 0
        val end = document.endSeconds
        return when (preview(canonicalUrl).provider) {
            LinkProvider.YOUTUBE -> replaceQueryParameter(canonicalUrl, "t", "${start}s")
            LinkProvider.VIDEO -> canonicalUrl.substringBefore('#') + "#t=$start" + (end?.let { ",$it" } ?: "")
            LinkProvider.INSTAGRAM, LinkProvider.WEBSITE -> canonicalUrl
        }
    }

    fun formatTime(seconds: Int?): String {
        val total = seconds ?: 0
        return "%02d:%02d".format(total / 60, total % 60)
    }

    fun parseTime(value: String): Int? {
        val parts = value.trim().split(':')
        if (parts.size !in 1..2 || parts.any { it.isBlank() || it.any { c -> !c.isDigit() } }) return null
        val seconds = if (parts.size == 1) parts[0].toIntOrNull()
        else parts[0].toIntOrNull()?.let { m -> parts[1].toIntOrNull()?.takeIf { it in 0..59 }?.let { m * 60 + it } }
        return seconds?.takeIf { it >= 0 }
    }

    private fun youtubeVideoId(uri: URI): String? {
        val host = uri.host.orEmpty().removePrefix("www.").lowercase()
        val candidate = when {
            host == "youtu.be" -> uri.path.orEmpty().trim('/').substringBefore('/')
            host == "youtube.com" || host.endsWith(".youtube.com") -> when {
                uri.path == "/watch" -> queryPairs(uri.rawQuery)["v"]
                uri.path.orEmpty().startsWith("/shorts/") -> uri.path.split('/').getOrNull(2)
                uri.path.orEmpty().startsWith("/embed/") -> uri.path.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,20}")) }
    }

    private fun replaceQueryParameter(url: String, key: String, value: String): String {
        val uri = URI(url)
        val pairs = queryPairs(uri.rawQuery).toMutableMap().apply { put(key, value) }
        val query = pairs.entries.joinToString("&") { "${it.key}=${it.value}" }
        return URI(uri.scheme, uri.authority, uri.path, query, uri.fragment).toASCIIString()
    }

    private fun queryPairs(query: String?): Map<String, String> = query.orEmpty().split('&')
        .filter { it.isNotBlank() }
        .associate { part -> part.substringBefore('=') to part.substringAfter('=', "") }
}
