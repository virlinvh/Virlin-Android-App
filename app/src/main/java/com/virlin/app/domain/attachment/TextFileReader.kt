package com.virlin.app.domain.attachment

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/** Streaming text load with a hard character cap — never slurps unbounded files. */
object TextFileReader {

    data class Result(val text: String, val truncated: Boolean, val monospaced: Boolean)

    fun read(
        input: InputStream,
        displayName: String,
        mimeType: String,
        maxChars: Int = 512_000,
        charset: Charset = Charsets.UTF_8
    ): Result {
        val sb = StringBuilder()
        var truncated = false
        BufferedReader(InputStreamReader(input, charset)).use { reader ->
            val buf = CharArray(8_192)
            while (true) {
                val n = reader.read(buf)
                if (n <= 0) break
                val room = maxChars - sb.length
                if (room <= 0) {
                    truncated = true
                    break
                }
                if (n <= room) sb.append(buf, 0, n)
                else {
                    sb.append(buf, 0, room)
                    truncated = true
                    break
                }
            }
        }
        return Result(sb.toString(), truncated, isMonospaced(displayName, mimeType))
    }

    fun isMonospaced(displayName: String, mimeType: String): Boolean {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        return ext in monoExt ||
            mime.contains("json") ||
            mime.contains("xml") ||
            mime == "text/x-log" ||
            mime.endsWith("+json") ||
            mime.endsWith("+xml")
    }

    private val monoExt = setOf(
        "json", "xml", "html", "htm", "css", "js", "ts", "kt", "java", "py", "log",
        "yml", "yaml", "toml", "ini", "sh", "bat", "c", "cpp", "h", "rs", "go"
    )
}
