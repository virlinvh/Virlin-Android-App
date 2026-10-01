package com.virlin.app.ui.pdf

import kotlin.math.roundToInt

const val PdfWorkspaceRoute = "pdf_workspace"
const val PdfWorkspaceCaptureArg = "captureId"
const val PdfWorkspaceTaskArg = "taskId"

fun pdfWorkspaceForTask(taskId: String): String =
    "$PdfWorkspaceRoute/new/task/${encodePdfRoutePart(taskId)}"

fun pdfWorkspaceForCapture(captureId: String): String =
    "$PdfWorkspaceRoute/capture/${encodePdfRoutePart(captureId)}"

private fun encodePdfRoutePart(value: String): String = android.net.Uri.encode(value)

enum class PdfWorkspaceMode {
    HOME,
    PREVIEW,
    ANNOTATE,
    ORGANIZE,
    CROP,
    OCR,
    EXPORT
}

enum class PdfExportFormat { PDF, PNG }

/** Normalized, page-independent crop. Values are always in the inclusive 0..1 range. */
data class PdfCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }

    fun pixelBounds(width: Int, height: Int): IntArray = intArrayOf(
        (left * width).roundToInt().coerceIn(0, width - 1),
        (top * height).roundToInt().coerceIn(0, height - 1),
        (right * width).roundToInt().coerceIn(1, width),
        (bottom * height).roundToInt().coerceIn(1, height)
    )
}

fun parsePdfPageRange(input: String, pageCount: Int): Result<Set<Int>> = runCatching {
    require(pageCount > 0) { "The PDF has no pages" }
    val result = linkedSetOf<Int>()
    input.split(',').map(String::trim).filter(String::isNotBlank).forEach { token ->
        val range = token.split('-', limit = 2).map(String::trim)
        val start = range[0].toInt()
        val end = if (range.size == 2) range[1].toInt() else start
        require(start in 1..pageCount && end in 1..pageCount && end >= start) {
            "Pages must be between 1 and $pageCount"
        }
        (start..end).forEach { result += it - 1 }
    }
    require(result.isNotEmpty()) { "Select at least one page" }
    result
}

fun formatPdfPageRange(zeroBasedPages: Set<Int>): String {
    val pages = zeroBasedPages.map { it + 1 }.sorted()
    if (pages.isEmpty()) return ""
    val chunks = mutableListOf<String>()
    var start = pages.first()
    var end = start
    for (page in pages.drop(1)) {
        if (page == end + 1) end = page else {
            chunks += if (start == end) "$start" else "$start-$end"
            start = page
            end = page
        }
    }
    chunks += if (start == end) "$start" else "$start-$end"
    return chunks.joinToString(", ")
}
