package com.virlin.app.ui.note

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.virlin.app.domain.model.NoteDocument
import com.virlin.app.domain.note.NotePdfLine
import com.virlin.app.domain.note.NotePdfModelBuilder
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Native [PdfDocument] exporter (A4). No third-party PDF library.
 */
object NotePdfExporter {
    private const val PAGE_W = 595 // A4 points
    private const val PAGE_H = 842
    private const val MARGIN = 48f

    fun exportAndShare(
        context: Context,
        doc: NoteDocument,
        includeTitle: Boolean = true,
        includeDate: Boolean = true
    ): Uri {
        val dateLabel = if (includeDate) {
            DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(doc.updatedAt)
        } else null
        val model = NotePdfModelBuilder.build(doc, includeTitle, includeDate, dateLabel)
        val pdf = PdfDocument()
        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
        var canvas = page.canvas
        var y = MARGIN

        fun newPage() {
            pdf.finishPage(page)
            pageNum++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
            canvas = page.canvas
            y = MARGIN
        }

        fun ensure(space: Float) {
            if (y + space > PAGE_H - MARGIN) newPage()
        }

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f; isFakeBoldText = true; color = 0xFF162016.toInt()
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f; color = 0xFF162016.toInt()
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f; color = 0xFF6B7280.toInt()
        }
        val codePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f; color = 0xFF162016.toInt(); typeface = android.graphics.Typeface.MONOSPACE
        }

        model.title?.let {
            ensure(28f)
            canvas.drawText(it, MARGIN, y, titlePaint)
            y += 28f
        }
        if (model.includeDate && model.dateLabel != null) {
            ensure(18f)
            canvas.drawText(model.dateLabel, MARGIN, y, mutedPaint)
            y += 22f
        }

        val maxWidth = PAGE_W - 2 * MARGIN
        for (line in model.lines) {
            when (line) {
                is NotePdfLine.Heading -> {
                    val p = Paint(titlePaint).apply { textSize = when (line.level) { 1 -> 18f; 2 -> 15f; else -> 13f } }
                    ensure(p.textSize + 10f)
                    wrapDraw(canvas, line.text, MARGIN, y, maxWidth, p) { dy -> y += dy }
                    y += 8f
                }
                is NotePdfLine.Body -> {
                    ensure(16f)
                    wrapDraw(canvas, line.text, MARGIN, y, maxWidth, bodyPaint) { dy -> y += dy }
                    y += 6f
                }
                is NotePdfLine.Bullet -> {
                    ensure(16f)
                    wrapDraw(canvas, "• ${line.text}", MARGIN, y, maxWidth, bodyPaint) { dy -> y += dy }
                    y += 4f
                }
                is NotePdfLine.Numbered -> {
                    ensure(16f)
                    wrapDraw(canvas, "${line.index}. ${line.text}", MARGIN, y, maxWidth, bodyPaint) { dy -> y += dy }
                    y += 4f
                }
                is NotePdfLine.Checkbox -> {
                    ensure(16f)
                    val mark = if (line.checked) "✓" else "☐"
                    wrapDraw(canvas, "$mark ${line.text}", MARGIN, y, maxWidth, bodyPaint) { dy -> y += dy }
                    y += 4f
                }
                is NotePdfLine.Quote, is NotePdfLine.Callout -> {
                    val text = when (line) {
                        is NotePdfLine.Quote -> line.text
                        is NotePdfLine.Callout -> line.text
                        else -> ""
                    }
                    ensure(16f)
                    wrapDraw(canvas, "“$text”", MARGIN + 12f, y, maxWidth - 12f, bodyPaint) { dy -> y += dy }
                    y += 6f
                }
                is NotePdfLine.Code -> {
                    line.text.lineSequence().forEach { row ->
                        ensure(14f)
                        canvas.drawText(row.take(90), MARGIN, y, codePaint)
                        y += 14f
                    }
                    y += 6f
                }
                NotePdfLine.Divider -> {
                    ensure(16f)
                    canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, mutedPaint)
                    y += 16f
                }
                is NotePdfLine.ToggleTitle -> {
                    ensure(16f)
                    wrapDraw(canvas, "▸ ${line.text}", MARGIN, y, maxWidth, bodyPaint) { dy -> y += dy }
                    y += 4f
                }
            }
        }

        pdf.finishPage(page)
        val dir = File(context.cacheDir, "notes").apply { mkdirs() }
        val safe = (doc.title ?: "text-note").replace(Regex("[^a-zA-Z0-9-_ ]"), "").take(40).ifBlank { "text-note" }
        val file = File(dir, "$safe.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "Export Text Note"))
        return uri
    }

    private fun wrapDraw(
        canvas: android.graphics.Canvas,
        text: String,
        x: Float,
        startY: Float,
        maxWidth: Float,
        paint: Paint,
        advance: (Float) -> Unit
    ) {
        var y = startY
        var remaining = text
        while (remaining.isNotEmpty()) {
            val count = paint.breakText(remaining, true, maxWidth, null)
            val chunk = remaining.take(count.coerceAtLeast(1))
            canvas.drawText(chunk, x, y, paint)
            y += paint.textSize + 4f
            advance(paint.textSize + 4f)
            remaining = remaining.drop(chunk.length)
        }
    }
}
