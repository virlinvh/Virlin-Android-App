package com.virlin.app.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import org.json.JSONObject
import java.io.OutputStream
import kotlin.math.min

/** Portable exports of the live map projection. None of these formats writes to the repository. */
internal object MapExportWriter {
    private const val MARGIN = 32f
    private const val MAX_BITMAP_SIDE = 8192
    private const val MAX_BITMAP_PIXELS = 24_000_000L

    fun markdown(topics: List<MapTopic>): String {
        val root = topics.single { it.parentId == null }
        val children = topics.groupBy { it.parentId }.mapValues { (_, siblings) ->
            siblings.sortedWith(compareBy<MapTopic> { it.order }.thenBy { it.id })
        }
        val out = StringBuilder("# ").append(root.title.mdText()).append("\n\n")
        val pending = ArrayDeque<Pair<MapTopic, Int>>()
        children[root.id].orEmpty().asReversed().forEach { pending.addLast(it to 0) }
        val seen = HashSet<String>()
        while (pending.isNotEmpty()) {
            val (topic, depth) = pending.removeLast()
            if (!seen.add(topic.id)) continue
            repeat(depth) { out.append("  ") }
            out.append("- ").append(topic.title.mdText()).append('\n')
            children[topic.id].orEmpty().asReversed().forEach { pending.addLast(it to depth + 1) }
        }
        return out.toString()
    }

    fun writeMarkdown(topics: List<MapTopic>, output: OutputStream) {
        output.write(markdown(topics).toByteArray(Charsets.UTF_8))
    }

    /** Readable JSON with the same versioned snapshot fields as .vmap. */
    fun writeJson(snapshot: VirlinMapSnapshot, output: OutputStream) {
        val compact = encodeVirlinMap(snapshot).toString(Charsets.UTF_8)
        output.write(JSONObject(compact).toString(2).toByteArray(Charsets.UTF_8))
    }

    fun writePng(topics: List<MapTopic>, appearance: MapAppearance, output: OutputStream) {
        val geometry = arrange(topics, appearance.layout, nodeWidths = appearance.nodeWidths)
        val width = geometry.right - geometry.left + MARGIN * 2
        val height = geometry.bottom - geometry.top + MARGIN * 2
        val scale = min(1f, min(MAX_BITMAP_SIDE / width, MAX_BITMAP_SIDE / height))
            .coerceAtMost(kotlin.math.sqrt(MAX_BITMAP_PIXELS / (width * height)).toFloat())
        val bitmap = Bitmap.createBitmap(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
        )
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            canvas.translate(MARGIN - geometry.left, MARGIN - geometry.top)
            paintMap(canvas, geometry, appearance, geometry.viewport(), null,
                showPreviewControls = false)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
        } finally { bitmap.recycle() }
    }

    /** Vector PDF. Oversized maps are tiled into readable landscape pages, not shrunk to a dot. */
    fun writePdf(topics: List<MapTopic>, appearance: MapAppearance, output: OutputStream) {
        val geometry = arrange(topics, appearance.layout, nodeWidths = appearance.nodeWidths)
        val pageWidth = 842
        val pageHeight = 595
        val contentWidth = pageWidth - 2 * MARGIN
        val contentHeight = pageHeight - 2 * MARGIN
        val mapWidth = geometry.right - geometry.left
        val mapHeight = geometry.bottom - geometry.top
        val columns = kotlin.math.ceil(mapWidth / contentWidth).toInt().coerceAtLeast(1)
        val rows = kotlin.math.ceil(mapHeight / contentHeight).toInt().coerceAtLeast(1)
        require(columns.toLong() * rows <= 100L) { "Map is too large for PDF export" }
        val pdf = PdfDocument()
        try {
            var number = 1
            repeat(rows) { row ->
                repeat(columns) { column ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, number++).create())
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    canvas.save()
                    canvas.clipRect(MARGIN, MARGIN, pageWidth - MARGIN, pageHeight - MARGIN)
                    canvas.translate(
                        MARGIN - geometry.left - column * contentWidth,
                        MARGIN - geometry.top - row * contentHeight,
                    )
                    paintMap(canvas, geometry, appearance, geometry.viewport(), null,
                        showPreviewControls = false)
                    canvas.restore()
                    pdf.finishPage(page)
                }
            }
            pdf.writeTo(output)
        } finally { pdf.close() }
    }

    private fun MapGeometry.viewport() = RectF(left - MARGIN, top - MARGIN,
        right + MARGIN, bottom + MARGIN)

    private fun String.mdText(): String = replace("\\", "\\\\")
        .replace("[", "\\[").replace("]", "\\]")
        .replace("*", "\\*").replace("_", "\\_")
        .replace("`", "\\`").replace("\n", " ")
}
