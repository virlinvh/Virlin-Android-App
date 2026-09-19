package com.virlin.app.domain.attachment

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/** Streaming CSV → rows. Does not load unbounded files into a single String. */
object CsvTableReader {

    data class Table(val rows: List<List<String>>, val truncated: Boolean)

    fun read(input: InputStream, maxRows: Int = 500, maxCols: Int = 40, charset: Charset = Charsets.UTF_8): Table {
        val rows = mutableListOf<List<String>>()
        var truncated = false
        BufferedReader(InputStreamReader(input, charset)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (rows.size >= maxRows) {
                    truncated = true
                    break
                }
                val cols = parseLine(line).take(maxCols)
                rows += cols
                if (cols.size >= maxCols) truncated = true
                line = reader.readLine()
            }
            if (reader.readLine() != null) truncated = true
        }
        return Table(rows, truncated)
    }

    /** Minimal RFC4180-ish split (quotes, commas). */
    fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        var inQuotes = false
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i += 2; continue
                    }
                    inQuotes = !inQuotes; i++
                }
                c == ',' && !inQuotes -> {
                    out += sb.toString(); sb.clear(); i++
                }
                else -> { sb.append(c); i++ }
            }
        }
        out += sb.toString()
        return out
    }
}
