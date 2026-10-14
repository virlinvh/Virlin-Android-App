package com.virlin.app.ui.hierarchy

import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.content.Context
import com.virlin.app.data.attachment.AttachmentFileStore
import com.virlin.app.domain.model.TaskPageTypeKeys
import com.virlin.app.domain.model.TaskStep
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.VirlinNoteDoc
import java.io.OutputStream

/** One read-only document assembled from the same owners and routes used by the editors. */
internal data class MasterDocumentSection(val heading: String, val entries: List<MasterDocumentEntry>)
internal data class MasterDocumentEntry(val id: String, val title: String, val kind: String,
    val body: String, val route: String, val mediaPath: String? = null, val isImage: Boolean = false)

internal fun taskPageBody(typeKey: String, taskId: String, steps: List<TaskStep>,
    note: VirlinNoteDoc?): String = when (typeKey) {
    TaskPageTypeKeys.TODO -> steps.filter { it.taskId == taskId }.sortedBy { it.order }
        .joinToString("\n") { "${if (it.done) "☑" else "☐"} ${it.text}" }
            .ifBlank { "No to-dos yet" }
    TaskPageTypeKeys.NOTE -> note?.blocks.orEmpty().joinToString("\n") { block ->
        when (block.type) {
            NoteDocBlockType.HEADING_1 -> "# ${block.text}"
            NoteDocBlockType.HEADING_2 -> "## ${block.text}"
            NoteDocBlockType.HEADING_3 -> "### ${block.text}"
            NoteDocBlockType.BULLET -> "• ${block.text}"
            NoteDocBlockType.NUMBERED -> "${block.text}"
            NoteDocBlockType.CHECKLIST -> "${if (block.checked) "☑" else "☐"} ${block.text}"
            NoteDocBlockType.QUOTE -> "“${block.text}”"
            NoteDocBlockType.CODE -> block.text
            NoteDocBlockType.DIVIDER -> "────"
            NoteDocBlockType.TABLE -> block.table?.cells?.joinToString(" · ").orEmpty()
            else -> block.text
        }
    }.ifBlank { "No note content yet" }
    else -> ""
}

/** Paginated text export. Media entries retain their title and source description. */
internal object MasterDocumentPdf {
    fun write(context: Context, title: String, sections: List<MasterDocumentSection>, output: OutputStream) {
        val pdf = PdfDocument()
        val width = 595; val height = 842; val margin = 42f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(27, 35, 48); textSize = 11f }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = margin
        fun nextPage() {
            page?.let(pdf::finishPage)
            page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, ++pageNumber).create())
            page!!.canvas.drawColor(Color.WHITE)
            y = margin
        }
        fun line(text: String, size: Float = 11f, bold: Boolean = false, gap: Float = 0f) {
            paint.textSize = size; paint.isFakeBoldText = bold
            val maxWidth = width - margin * 2
            val paragraphs = text.split('\n')
            paragraphs.forEach { paragraph ->
                val words = paragraph.split(Regex("\\s+"))
                var current = ""
                fun flush() {
                    if (y + size * 1.45f > height - margin) nextPage()
                    page!!.canvas.drawText(current, margin, y, paint)
                    y += size * 1.45f
                    current = ""
                }
                words.forEach { word ->
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (paint.measureText(candidate) > maxWidth && current.isNotEmpty()) flush()
                    current = if (current.isEmpty()) word else "$current $word"
                }
                flush()
            }
            y += gap
        }
        try {
            nextPage()
            line(title, 22f, true, 14f)
            sections.forEach { section ->
                line(section.heading, 15f, true, 6f)
                section.entries.forEach { entry ->
                    line("${entry.title} · ${entry.kind}", 12f, true, 3f)
                    line(entry.body.ifBlank { "No readable text" }, 10f, false, 10f)
                    if (entry.isImage && entry.mediaPath != null) {
                        val file = AttachmentFileStore.resolve(context, entry.mediaPath)
                        if (file.isFile) {
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(file.absolutePath, options)
                            options.inJustDecodeBounds = false
                            options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 1200).coerceAtLeast(1)
                            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                            if (bitmap != null) try {
                                val displayWidth = minOf(width - margin * 2, bitmap.width.toFloat())
                                val displayHeight = displayWidth * bitmap.height / bitmap.width
                                if (y + displayHeight > height - margin) nextPage()
                                val maxHeight = height - margin - y
                                val scale = minOf(1f, maxHeight / displayHeight)
                                val dest = android.graphics.RectF(margin, y, margin + displayWidth * scale,
                                    y + displayHeight * scale)
                                page!!.canvas.drawBitmap(bitmap, null, dest, null)
                                y += dest.height() + 12f
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
            page?.let(pdf::finishPage)
            pdf.writeTo(output)
        } finally { pdf.close() }
    }
}
