package com.virlin.app.ui.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.core.graphics.ColorUtils
import com.virlin.app.domain.notedoc.NoteDocAlign
import com.virlin.app.domain.notedoc.NoteDocAudio
import com.virlin.app.domain.notedoc.NoteDocBlock
import com.virlin.app.domain.notedoc.NoteDocBlockType
import com.virlin.app.domain.notedoc.NoteDocMark
import com.virlin.app.domain.notedoc.NoteDocRun
import com.virlin.app.domain.notedoc.NoteListStyles
import com.virlin.app.domain.notedoc.NoteMath
import com.virlin.app.ui.hierarchy.ProjectPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

enum class NotePaper(val label: String, val widthPt: Int, val heightPt: Int) {
    A4("A4", 595, 842), LETTER("Letter", 612, 792), LEGAL("Legal", 612, 1008), A5("A5", 420, 595),
    A3("A3", 842, 1191);

    val media: PrintAttributes.MediaSize get() = when (this) {
        A4 -> PrintAttributes.MediaSize.ISO_A4
        LETTER -> PrintAttributes.MediaSize.NA_LETTER
        LEGAL -> PrintAttributes.MediaSize.NA_LEGAL
        A5 -> PrintAttributes.MediaSize.ISO_A5
        A3 -> PrintAttributes.MediaSize.ISO_A3
    }

    companion object {
        /** The order papers are offered in. */
        val offered = listOf(A4, A3, A5, LETTER, LEGAL)
    }
}

enum class NoteMargins(val label: String, val points: Float) {
    NARROW("Narrow", 36f), NORMAL("Normal", 54f), WIDE("Wide", 72f)
}

enum class NotePageNumbers(val label: String) { OFF("Off"), TOP("Top"), BOTTOM("Bottom") }

enum class NoteNumberAlign(val label: String) { LEFT("Left"), CENTER("Centre"), RIGHT("Right") }

/**
 * How a note is laid out on paper. The page view, printing and export share it. A note's chosen
 * formatting is kept beside the note ([NotePageSetupStore]), never inside its document.
 */
data class NotePrintSettings(
    val paper: NotePaper = NotePaper.A4,
    val landscape: Boolean = false,
    val margins: NoteMargins = NoteMargins.NORMAL,
    val pageNumbers: NotePageNumbers = NotePageNumbers.BOTTOM,
    val numberAlign: NoteNumberAlign = NoteNumberAlign.CENTER,
    val includeTitle: Boolean = true,
    /**
     * Formatted pages: a block that fits on one page starts on a new page rather than splitting,
     * so the page view in the editor and the printed or exported pages break in the same places.
     */
    val keepBlocksWhole: Boolean = false,
    /**
     * Auto-fit: content runs on continuously and a block that reaches the end of a page carries on
     * at the top of the next, split between lines, so pages have no empty space at the bottom.
     * Only images, video, audio and table rows stay whole; a heading stays with what follows it.
     */
    val autoFit: Boolean = false,
) {
    val pageWidthPt: Float get() = (if (landscape) paper.heightPt else paper.widthPt).toFloat()
    val pageHeightPt: Float get() = (if (landscape) paper.widthPt else paper.heightPt).toFloat()
    val summary: String get() = "${paper.label} · ${if (landscape) "Landscape" else "Portrait"}"
}

/**
 * Where a formatted note's pages break: the page each block starts and ends on, and how far down
 * its last page each page's content reaches (points from the top of the page).
 */
class NotePagination(
    val pageCount: Int,
    val startPage: Map<String, Int>,
    val endPage: Map<String, Int>,
    val contentBottom: List<Float>,
    /** Where each block's own content ends on [endPage], before the space after it. */
    val blockBottom: Map<String, Float>,
)

/** The note's own colours, kept dark enough to read on white paper. */
internal class NotePrintColors(val ink: Int, val accent: Int, val wash: Int, val muted: Int, val border: Int) {
    companion object {
        private const val INK = 0xFF17221D.toInt()
        private const val MUTED = 0xFF66758A.toInt()

        fun of(palette: ProjectPalette?): NotePrintColors {
            if (palette == null) return NotePrintColors(INK, 0xFF087848.toInt(), 0xFFE2F5EA.toInt(), MUTED,
                0xFFD5DDD8.toInt())
            fun dark(color: Int, fallback: Int) = if (android.graphics.Color.luminance(color) > .45f) fallback else color
            return NotePrintColors(
                ink = dark(palette.text, INK),
                accent = dark(palette.secondary, 0xFF087848.toInt()),
                wash = palette.tertiary,
                muted = dark(palette.muted, MUTED),
                border = ColorUtils.setAlphaComponent(palette.primary, 64),
            )
        }
    }
}

/** Everything the renderer reads. [imageFile] resolves an image block's stored name. */
internal class NotePrintSource(
    val title: String?,
    val blocks: List<NoteDocBlock>,
    val colors: NotePrintColors,
    val typefaces: Map<String, Typeface> = emptyMap(),
    val imageFile: (String) -> File? = { null },
    /** Pixels per inch images are drawn at; lower makes smaller files. */
    val imageDpi: Int = 216,
    /** A video's thumbnail when one has been loaded; the card draws a dark frame otherwise. */
    val thumbnail: (String) -> Bitmap? = { null },
)

/**
 * Draws a note onto paper pages with the editor's own sizes, rhythm, colours and fonts. Text
 * flows across pages line by line; tables split between rows; images, video and audio cards stay
 * whole. Collapsed toggles and headings print expanded, so nothing in the note is left out.
 */
internal object NotePrintRenderer {
    /** Editor sp to paper points: 16 sp body text prints at 11.5 pt. */
    const val SP = .72f

    class Page { val ops = ArrayList<(Canvas) -> Unit>() }

    fun layout(source: NotePrintSource, settings: NotePrintSettings,
               widthPt: Float = settings.pageWidthPt, heightPt: Float = settings.pageHeightPt): List<Page> =
        flow(source, settings, widthPt, heightPt).pages

    /** The page breaks [layout] makes, for showing the pages while editing. */
    fun paginate(source: NotePrintSource, settings: NotePrintSettings): NotePagination {
        val start = HashMap<String, Int>()
        val end = HashMap<String, Int>()
        val bottom = HashMap<String, Float>()
        val flow = flow(source, settings, settings.pageWidthPt, settings.pageHeightPt) { id, first, last, y ->
            start[id] = first; end[id] = last; bottom[id] = y
        }
        val contentBottom = MutableList(flow.pages.size) { flow.top }
        source.blocks.forEach { b ->
            val page = end[b.id] ?: return@forEach
            contentBottom[page] = maxOf(contentBottom[page], bottom[b.id] ?: flow.top)
        }
        return NotePagination(flow.pages.size, start, end, contentBottom, bottom)
    }

    /** The whole note on one endless page; returns it with the height the content needs. */
    fun layoutLong(source: NotePrintSource, settings: NotePrintSettings,
                   widthPt: Float = settings.pageWidthPt): Pair<Page, Float> {
        val flow = flow(source, settings, widthPt, LONG_PAGE)
        return flow.pages.first() to (flow.y + settings.margins.points)
    }

    private const val LONG_PAGE = 1_000_000f

    /** With auto-fit, a heading moves to the next page unless about two lines of what follows fit under it. */
    private const val KEEP_WITH_HEADING = 40f

    /** "Page 2 of 5" where the settings put it. */
    fun drawPageNumber(canvas: Canvas, index: Int, total: Int, settings: NotePrintSettings, colors: NotePrintColors,
                       widthPt: Float, heightPt: Float) {
        if (settings.pageNumbers == NotePageNumbers.OFF) return
        val paint = textPaint(8.5f, colors.muted, Typeface.SANS_SERIF)
        val margin = settings.margins.points
        val label = "Page ${index + 1} of $total"
        val y = if (settings.pageNumbers == NotePageNumbers.TOP) margin / 2 + 3f else heightPt - margin / 2 + 3f
        when (settings.numberAlign) {
            NoteNumberAlign.LEFT -> { paint.textAlign = Paint.Align.LEFT; canvas.drawText(label, margin, y, paint) }
            NoteNumberAlign.CENTER -> { paint.textAlign = Paint.Align.CENTER; canvas.drawText(label, widthPt / 2, y, paint) }
            NoteNumberAlign.RIGHT -> { paint.textAlign = Paint.Align.RIGHT; canvas.drawText(label, widthPt - margin, y, paint) }
        }
    }

    private fun flow(source: NotePrintSource, settings: NotePrintSettings, widthPt: Float, heightPt: Float,
                     placed: ((String, Int, Int, Float) -> Unit)? = null): Flow {
        val flow = Flow(widthPt, heightPt, settings.margins.points)
        val c = source.colors
        val title = source.title?.trim().orEmpty()
        if (settings.includeTitle && title.isNotEmpty()) {
            val paint = textPaint(27 * SP, c.ink, semiBold())
            flow.text(staticLayout(title, paint, flow.width, Layout.Alignment.ALIGN_NORMAL, 1.1f), flow.left)
            flow.gap(22 * SP)
        }
        val numbers = HashMap<String, Int>()
        var previous: NoteDocBlock? = null
        var number = 0
        source.blocks.forEach { b ->
            number = if (b.type == NoteDocBlockType.NUMBERED) {
                if (previous?.type == NoteDocBlockType.NUMBERED && previous?.indent == b.indent) number + 1 else 1
            } else 0
            if (b.type == NoteDocBlockType.NUMBERED) numbers[b.id] = number
            previous = b
        }
        source.blocks.forEachIndexed { i, block ->
            flow.gap(NoteDocumentLayout.beforeDp(block, i) * SP)
            fun height(b: NoteDocBlock): Float {
                val probe = Flow(widthPt, LONG_PAGE, settings.margins.points)
                block(probe, source, b, numbers[b.id] ?: 1)
                return probe.y - probe.top
            }
            if (settings.autoFit) {
                val next = source.blocks.getOrNull(i + 1)
                if (block.type.headingLevel > 0 && next != null && !flow.atPageTop) {
                    val need = height(block) + (NoteDocumentLayout.afterDp(block, next) +
                        NoteDocumentLayout.beforeDp(next, i + 1)) * SP + minOf(height(next), KEEP_WITH_HEADING)
                    if (need <= flow.bottom - flow.top && flow.y + need > flow.bottom) flow.breakPage()
                }
            } else if (settings.keepBlocksWhole && !flow.atPageTop) {
                val height = height(block)
                if (height <= flow.bottom - flow.top && flow.y + height > flow.bottom) flow.breakPage()
            }
            val first = flow.pages.size - 1
            block(flow, source, block, numbers[block.id] ?: 1)
            placed?.invoke(block.id, first, flow.pages.size - 1, flow.y)
            flow.gap(NoteDocumentLayout.afterDp(block, source.blocks.getOrNull(i + 1)) * SP)
        }
        return flow
    }

    /** Writes [pages] as a PDF with the chosen page numbers. */
    fun write(pages: List<Page>, settings: NotePrintSettings, colors: NotePrintColors, out: OutputStream,
              widthPt: Float = settings.pageWidthPt, heightPt: Float = settings.pageHeightPt) {
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val p = pdf.startPage(PdfDocument.PageInfo.Builder(widthPt.toInt(), heightPt.toInt(), index + 1).create())
                page.ops.forEach { it(p.canvas) }
                drawPageNumber(p.canvas, index, pages.size, settings, colors, widthPt, heightPt)
                pdf.finishPage(p)
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    // ------------------------------------------------------------------ blocks

    private fun block(flow: Flow, src: NotePrintSource, b: NoteDocBlock, number: Int) {
        val c = src.colors
        val indent = b.indent * 18 * SP
        val x = flow.left + indent
        val width = flow.width - indent
        when (b.type) {
            NoteDocBlockType.DIVIDER -> flow.atomic(21 * SP) { canvas, top ->
                canvas.drawRect(flow.left, top + 10 * SP, flow.left + flow.width, top + 10 * SP + .75f, fill(c.border))
            }
            NoteDocBlockType.TABLE -> table(flow, src, b, x, width)
            NoteDocBlockType.IMAGE -> image(flow, src, b, x, width)
            NoteDocBlockType.VIDEO -> video(flow, src, b, x, width)
            NoteDocBlockType.AUDIO -> audio(flow, src, b, x, width)
            NoteDocBlockType.QUOTE -> {
                val pad = Box(fill = ColorUtils.setAlphaComponent(c.accent, 12), padX = 17 * SP, padY = 15 * SP,
                    radiusLeft = 0f, radiusRight = 12 * SP, bar = ColorUtils.setAlphaComponent(c.accent, 166),
                    barWidth = 3 * SP, x = x, width = width)
                flow.text(blockLayout(src, b, (width - 2 * pad.padX)), x + pad.padX, pad)
            }
            NoteDocBlockType.CODE -> {
                val equation = NoteMath.isEquation(b)
                val syntax = if (equation) null else CodeHighlighter.highlight(b.text)
                val label = if (equation) "Equation" else syntax?.language?.label ?: "Code"
                val box = Box(fill = ColorUtils.setAlphaComponent(c.wash, 140), padX = 12 * SP, padY = 12 * SP,
                    radiusLeft = 10 * SP, radiusRight = 10 * SP, x = x, width = width)
                val text = labelled(label, 11 * SP, c.muted, Typeface.SANS_SERIF, false,
                    styled(b.text, b.runs, src, syntax))
                flow.text(staticLayout(text, basePaint(b, c), width - 2 * box.padX, Layout.Alignment.ALIGN_NORMAL,
                    lineMultiplier(b)), x + box.padX, box)
            }
            NoteDocBlockType.PROMPT -> {
                val box = Box(fill = c.wash, padX = 12 * SP, padY = 12 * SP, radiusLeft = 12 * SP, radiusRight = 12 * SP,
                    x = x, width = width)
                val body = styled(b.text, b.runs, src)
                val title = b.promptTitle?.trim().orEmpty()
                val text = if (title.isEmpty()) body else labelled(title, 13 * SP, c.accent, semiBold(), true, body)
                flow.text(staticLayout(text, basePaint(b, c), width - 2 * box.padX, align(b.align), lineMultiplier(b)),
                    x + box.padX, box)
            }
            NoteDocBlockType.CHECKLIST -> {
                val size = 20 * SP
                val gap = 10 * SP
                val layout = blockLayout(src, b, width - size - gap)
                flow.text(layout, x + size + gap) { canvas, top ->
                    val boxTop = top + ((layout.getLineBottom(0) - layout.getLineTop(0)) - size) / 2
                    val rect = RectF(x, boxTop, x + size, boxTop + size)
                    if (b.checked) {
                        canvas.drawRoundRect(rect, 5 * SP, 5 * SP, fill(c.accent))
                        val tick = textPaint(13 * SP, android.graphics.Color.WHITE, Typeface.SANS_SERIF).apply {
                            textAlign = Paint.Align.CENTER
                        }
                        canvas.drawText("✓", rect.centerX(), rect.centerY() + tick.textSize / 3, tick)
                    } else {
                        canvas.drawRoundRect(rect, 5 * SP, 5 * SP, stroke(c.border, .75f))
                    }
                }
            }
            NoteDocBlockType.BULLET, NoteDocBlockType.NUMBERED -> {
                val marker = NoteListStyles.marker(b.type, b.listStyle, number)
                val markerPaint = textPaint((if (b.type == NoteDocBlockType.BULLET && marker != "•") 14 else
                    if (b.type == NoteDocBlockType.BULLET) 16 else 15) * SP, c.ink, Typeface.SANS_SERIF)
                val gap = (if (b.type == NoteDocBlockType.BULLET) 10 else 8) * SP
                val markerWidth = markerPaint.measureText(marker)
                val layout = blockLayout(src, b, width - markerWidth - gap)
                flow.text(layout, x + markerWidth + gap) { canvas, top ->
                    canvas.drawText(marker, x, top + layout.getLineBaseline(0), markerPaint)
                }
            }
            NoteDocBlockType.TOGGLE -> arrowed(flow, src, b, x, width)
            else -> if (b.type.headingLevel > 0 && b.toggleHeading) arrowed(flow, src, b, x, width)
                else flow.text(blockLayout(src, b, width), x)
        }
    }

    private fun arrowed(flow: Flow, src: NotePrintSource, b: NoteDocBlock, x: Float, width: Float) {
        val size = NotesViewModel.defaultSizeFor(b.type) * SP
        val w = size * .5f
        val h = w * .62f
        val arrowWidth = w + 8 * SP
        val layout = blockLayout(src, b, width - arrowWidth)
        flow.text(layout, x + arrowWidth) { canvas, top ->
            val cy = top + layout.getLineBaseline(0) - size * .36f
            val arrow = Path().apply {
                moveTo(x + 1f, cy - h / 2)
                lineTo(x + 1f + w, cy - h / 2)
                lineTo(x + 1f + w / 2, cy + h / 2)
                close()
            }
            canvas.drawPath(arrow, fill(src.colors.ink))
        }
    }

    private fun table(flow: Flow, src: NotePrintSource, b: NoteDocBlock, x: Float, avail: Float) {
        val t = b.table ?: return
        val c = src.colors
        val explicit = (0 until t.cols).mapNotNull { t.widthAt(it) }
        val autoCount = t.cols - explicit.size
        val autoWidth = if (autoCount == 0) 0f
            else ((avail - explicit.sum() * SP) / autoCount).coerceAtLeast(112 * SP)
        var widths = (0 until t.cols).map { col -> t.widthAt(col)?.let { it * SP } ?: autoWidth }
        val total = widths.sum()
        if (total > avail) widths = widths.map { it * avail / total }
        val pad = 8 * SP
        val line = if (t.gridLines) c.border else ColorUtils.setAlphaComponent(c.border, 24)
        repeat(t.rows) { r ->
            val layouts = (0 until t.cols).map { col ->
                val header = t.isHeader(r, col)
                val paint = textPaint(TABLE_CELL_TEXT_SP * SP, c.ink, if (header) semiBold() else Typeface.SANS_SERIF)
                staticLayout(styled(t.cell(r, col), t.runsAt(r, col), src), paint, widths[col] - 2 * pad,
                    Layout.Alignment.ALIGN_NORMAL, 1.15f)
            }
            val rowHeight = layouts.maxOf { it.height } + 2 * pad
            flow.atomic(rowHeight) { canvas, top ->
                var cx = x
                for (col in 0 until t.cols) {
                    val header = t.isHeader(r, col)
                    val fillColor = t.fillAt(r, col)?.toInt() ?: if (header) c.wash else android.graphics.Color.WHITE
                    val rect = RectF(cx, top, cx + widths[col], top + rowHeight)
                    canvas.drawRect(rect, fill(fillColor))
                    canvas.drawRect(rect, stroke(line, .75f))
                    canvas.save()
                    canvas.translate(cx + pad, top + pad)
                    layouts[col].draw(canvas)
                    canvas.restore()
                    cx += widths[col]
                }
            }
        }
    }

    private fun image(flow: Flow, src: NotePrintSource, b: NoteDocBlock, x: Float, avail: Float) {
        val file = b.imagePath?.let(src.imageFile)?.takeIf { it.isFile }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file?.let { BitmapFactory.decodeFile(it.absolutePath, bounds) }
        if (file == null || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            flow.text(staticLayout("Image unavailable", textPaint(13 * SP, src.colors.muted, Typeface.SANS_SERIF),
                avail, Layout.Alignment.ALIGN_NORMAL, 1.2f), x)
            return
        }
        val pad = 4 * SP
        var w = avail
        var h = w * bounds.outHeight / bounds.outWidth
        val maxHeight = flow.bottom - flow.top - 2 * pad
        if (h > maxHeight) { h = maxHeight; w = h * bounds.outWidth / bounds.outHeight }
        val path = file.absolutePath
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        val dpi = src.imageDpi
        flow.atomic(h + 2 * pad) { canvas, top ->
            val bitmap = NoteExportImages.decodeFor(path, sourceWidth, sourceHeight, (w / 72f * dpi).toInt())
                ?: return@atomic
            canvas.drawBitmap(bitmap, null, RectF(x, top + pad, x + w, top + pad + h),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            bitmap.recycle()
        }
    }

    /** The clip as the editor shows it: the 16:9 thumbnail with a play badge and clip times, then title and channel. */
    private fun video(flow: Flow, src: NotePrintSource, b: NoteDocBlock, x: Float, width: Float) {
        val c = src.colors
        val v = b.video
        val pad = 5 * SP
        val thumbWidth = width - 2 * pad
        val thumbHeight = thumbWidth * 9f / 16f
        val textX = x + pad + 7 * SP + 24 * SP + 10 * SP
        val textWidth = (x + width - pad - 3 * SP - textX).coerceAtLeast(20f)
        val titleText = v?.title?.takeIf { it.isNotBlank() } ?: "YouTube video"
        val titleLayout = StaticLayout.Builder.obtain(titleText, 0, titleText.length,
            textPaint(14 * SP, c.ink, semiBold()), textWidth.toInt().coerceAtLeast(1))
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 19f / 14f / 1.17f).setIncludePad(false).build()
        val details = listOfNotNull(v?.channel?.takeIf { it.isNotBlank() },
            v?.let { com.virlin.app.domain.notedoc.NoteYouTube.rangeLabel(it) }).joinToString("  ·  ")
        val detailsPaint = textPaint(12 * SP, c.muted, Typeface.SANS_SERIF)
        val detailsHeight = if (details.isEmpty()) 0f else 2 * SP + 16 * SP
        val rowHeight = maxOf(titleLayout.height + detailsHeight, 17 * SP)
        val cardHeight = pad + thumbHeight + 10 * SP + rowHeight + 5 * SP + pad
        flow.atomic(cardHeight + 8 * SP) { canvas, top0 ->
            val top = top0 + 4 * SP
            val card = RectF(x, top, x + width, top + cardHeight)
            canvas.drawRoundRect(card, 16 * SP, 16 * SP, fill(android.graphics.Color.WHITE))
            canvas.drawRoundRect(card, 16 * SP, 16 * SP, stroke(c.border, .75f))
            val thumb = RectF(x + pad, top + pad, x + pad + thumbWidth, top + pad + thumbHeight)
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(thumb, 10 * SP, 10 * SP, Path.Direction.CW) })
            canvas.drawRect(thumb, fill(0xFF15171A.toInt()))
            v?.let { src.thumbnail(it.videoId) }?.let { bitmap ->
                val scale = maxOf(thumb.width() / bitmap.width, thumb.height() / bitmap.height)
                val w = bitmap.width * scale
                val h = bitmap.height * scale
                canvas.drawBitmap(bitmap, null,
                    RectF(thumb.centerX() - w / 2, thumb.centerY() - h / 2, thumb.centerX() + w / 2, thumb.centerY() + h / 2),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            canvas.restore()
            youTubeBadge(canvas, thumb.centerX(), thumb.centerY(), 64 * SP, 45 * SP)
            v?.let {
                val label = com.virlin.app.domain.notedoc.NoteYouTube.rangeLabel(it)
                val pillPaint = textPaint(11 * SP, android.graphics.Color.WHITE, semiBold())
                val pill = RectF(thumb.left + 10 * SP, thumb.top + 10 * SP,
                    thumb.left + 10 * SP + pillPaint.measureText(label) + 20 * SP, thumb.top + 10 * SP + 22 * SP)
                canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, fill(0x9E000000.toInt()))
                canvas.drawText(label, pill.left + 10 * SP, pill.centerY() + pillPaint.textSize / 3, pillPaint)
            }
            val rowTop = thumb.bottom + 10 * SP
            youTubeBadge(canvas, x + pad + 7 * SP + 12 * SP, rowTop + rowHeight / 2, 24 * SP, 17 * SP)
            canvas.save()
            canvas.translate(textX, rowTop)
            titleLayout.draw(canvas)
            canvas.restore()
            if (details.isNotEmpty()) canvas.drawText(ellipsize(details, detailsPaint, textWidth), textX,
                rowTop + titleLayout.height + 2 * SP + 12 * SP, detailsPaint)
        }
    }

    private fun youTubeBadge(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        val badge = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        canvas.drawRoundRect(badge, h * .28f, h * .28f, fill(0xFFFF0000.toInt()))
        val t = h * .26f
        canvas.drawPath(Path().apply {
            moveTo(cx - t * .8f, cy - t); lineTo(cx + t * 1.1f, cy); lineTo(cx - t * .8f, cy + t); close()
        }, fill(android.graphics.Color.WHITE))
    }
    private fun audio(flow: Flow, src: NotePrintSource, b: NoteDocBlock, x: Float, width: Float) {
        val c = src.colors
        val a = b.audio
        val height = 46f
        flow.atomic(height + 8 * SP) { canvas, top0 ->
            val top = top0 + 4 * SP
            val card = RectF(x, top, x + width, top + height)
            canvas.drawRoundRect(card, 9f, 9f, fill(ColorUtils.setAlphaComponent(c.wash, 200)))
            canvas.drawCircle(x + 23f, top + height / 2, 11f, fill(c.accent))
            val play = Path().apply {
                moveTo(x + 20f, top + height / 2 - 5f)
                lineTo(x + 28f, top + height / 2)
                lineTo(x + 20f, top + height / 2 + 5f)
                close()
            }
            canvas.drawPath(play, fill(android.graphics.Color.WHITE))
            val textX = x + 44f
            val titlePaint = textPaint(10f, c.ink, semiBold())
            val smallPaint = textPaint(8.5f, c.muted, Typeface.SANS_SERIF)
            val waveLeft = x + width * .55f
            val title = a?.title ?: "Voice recording"
            canvas.drawText(ellipsize(title, titlePaint, waveLeft - textX - 8f), textX, top + 20f, titlePaint)
            canvas.drawText(a?.let { NoteDocAudio.formatDuration(it.durationMs) } ?: "Audio", textX, top + 33f, smallPaint)
            val peaks = a?.let { NoteDocAudio.decodePeaks(it.peaks) }.orEmpty()
            if (peaks.isNotEmpty()) {
                val waveRight = card.right - 12f
                val step = (waveRight - waveLeft) / peaks.size
                val bar = fill(ColorUtils.setAlphaComponent(c.accent, 150))
                peaks.forEachIndexed { i, level ->
                    val h = 3f + level * (height - 18f)
                    val bx = waveLeft + i * step
                    canvas.drawRoundRect(RectF(bx, top + (height - h) / 2, bx + step * .6f, top + (height + h) / 2), 1f, 1f, bar)
                }
            }
        }
    }

    // ------------------------------------------------------------------ text

    private fun blockLayout(src: NotePrintSource, b: NoteDocBlock, width: Float): StaticLayout =
        staticLayout(styled(b.text, b.runs, src), basePaint(b, src.colors), width, align(b.align), lineMultiplier(b))

    private fun basePaint(b: NoteDocBlock, c: NotePrintColors): TextPaint = textPaint(
        NotesViewModel.defaultSizeFor(b.type) * SP,
        if (b.type == NoteDocBlockType.QUOTE) c.muted else c.ink,
        when {
            b.type.headingLevel > 0 -> semiBold()
            b.type == NoteDocBlockType.CODE -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        },
    )

    /** The editor's line height over the font's natural one (about 1.17 × size). */
    private fun lineMultiplier(b: NoteDocBlock): Float {
        val base = NotesViewModel.defaultSizeFor(b.type).toFloat()
        val largest = maxOf(base, b.runs.mapNotNull { it.fontSizeSp }.maxOrNull()?.toFloat() ?: base)
        return NoteDocumentLayout.lineHeightSp(b) / largest / 1.17f
    }

    private fun align(a: NoteDocAlign) = when (a) {
        NoteDocAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        NoteDocAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        NoteDocAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
    }

    private fun styled(text: String, runs: List<NoteDocRun>, src: NotePrintSource,
                       syntax: CodeHighlight? = null): SpannableStringBuilder {
        val out = SpannableStringBuilder(text)
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        syntax?.tokens?.forEach { token ->
            val s = token.start.coerceIn(0, text.length)
            val e = token.end.coerceIn(s, text.length)
            if (e > s) {
                out.setSpan(ForegroundColorSpan(SYNTAX_COLORS[token.kind.ordinal]), s, e, flag)
                if (token.kind == CodeTokenKind.COMMENT) out.setSpan(StyleSpan(Typeface.ITALIC), s, e, flag)
            }
        }
        runs.forEach { r ->
            val s = r.start.coerceIn(0, text.length)
            val e = r.end.coerceIn(s, text.length)
            if (e <= s) return@forEach
            val face = if (NoteDocMark.CODE in r.marks) Typeface.MONOSPACE else typefaceFor(r.fontFamily, src)
            face?.let { out.setSpan(FaceSpan(it), s, e, flag) }
            r.fontSizeSp?.let { out.setSpan(SizeSpan(it * SP), s, e, flag) }
            val style = (if (NoteDocMark.BOLD in r.marks) Typeface.BOLD else 0) or
                (if (NoteDocMark.ITALIC in r.marks) Typeface.ITALIC else 0)
            if (style != 0) out.setSpan(StyleSpan(style), s, e, flag)
            if (NoteDocMark.UNDERLINE in r.marks || r.linkUrl != null) out.setSpan(UnderlineSpan(), s, e, flag)
            if (NoteDocMark.STRIKE in r.marks) out.setSpan(StrikethroughSpan(), s, e, flag)
            when {
                r.linkUrl != null -> out.setSpan(ForegroundColorSpan(src.colors.accent), s, e, flag)
                r.color != null -> out.setSpan(ForegroundColorSpan(r.color.toInt()), s, e, flag)
            }
            r.highlight?.let { out.setSpan(BackgroundColorSpan(it.toInt()), s, e, flag) }
        }
        return out
    }

    /** [label] on its own line above [body], in its own size, colour and face. */
    private fun labelled(label: String, size: Float, color: Int, face: Typeface, bold: Boolean,
                         body: CharSequence): SpannableStringBuilder {
        val out = SpannableStringBuilder(label).append("\n").append(body)
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        out.setSpan(FaceSpan(face), 0, label.length, flag)
        out.setSpan(SizeSpan(size), 0, label.length, flag)
        out.setSpan(ForegroundColorSpan(color), 0, label.length, flag)
        if (bold) out.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, flag)
        return out
    }

    private fun typefaceFor(name: String?, src: NotePrintSource): Typeface? = when (name) {
        null -> null
        "Inter" -> Typeface.SANS_SERIF
        "Serif" -> Typeface.SERIF
        "Mono" -> Typeface.MONOSPACE
        else -> src.typefaces[name]
    }

    private fun staticLayout(text: CharSequence, paint: TextPaint, width: Float, align: Layout.Alignment,
                             multiplier: Float): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(align)
            .setLineSpacing(0f, multiplier.coerceAtLeast(1f))
            .setIncludePad(false)
            .build()

    private fun textPaint(size: Float, color: Int, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = face
    }

    private fun semiBold(): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, 600, false) else Typeface.DEFAULT_BOLD

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = width
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float) =
        TextUtils.ellipsize(text, paint, width.coerceAtLeast(1f), TextUtils.TruncateAt.END).toString()

    private fun clock(seconds: Int) = NoteDocAudio.formatDuration(seconds * 1000L)

    /** The editor's syntax colours, indexed by [CodeTokenKind.ordinal]. */
    internal val SYNTAX_COLORS: IntArray = CodeTokenKind.entries.map { kind ->
        when (kind) {
            CodeTokenKind.KEYWORD -> 0xFFCF222E
            CodeTokenKind.STRING -> 0xFF0A3069
            CodeTokenKind.COMMENT -> 0xFF6E7781
            CodeTokenKind.NUMBER, CodeTokenKind.CONSTANT, CodeTokenKind.PROPERTY -> 0xFF0550AE
            CodeTokenKind.FUNCTION -> 0xFF8250DF
            CodeTokenKind.TAG -> 0xFF116329
            CodeTokenKind.DECORATOR -> 0xFF953800
        }.toInt()
    }.toIntArray()

    private class FaceSpan(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) = apply(paint)
        override fun updateMeasureState(paint: TextPaint) = apply(paint)
        private fun apply(paint: TextPaint) {
            paint.typeface = Typeface.create(face, paint.typeface?.style ?: Typeface.NORMAL)
        }
    }

    private class SizeSpan(private val size: Float) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) { paint.textSize = size }
        override fun updateMeasureState(paint: TextPaint) { paint.textSize = size }
    }

    /** A card behind text: drawn per page slice, rounded only where the card really starts and ends. */
    private class Box(
        val fill: Int, val padX: Float, val padY: Float, val radiusLeft: Float, val radiusRight: Float,
        val x: Float, val width: Float, val bar: Int? = null, val barWidth: Float = 0f,
    )

    /** Places content top to bottom, starting a new page when the next piece does not fit. */
    private class Flow(pageWidth: Float, pageHeight: Float, margin: Float) {
        val left = margin
        val top = margin
        val bottom = pageHeight - margin
        val width = pageWidth - 2 * margin
        val pages = arrayListOf(Page())
        var y = top
            private set

        private fun newPage() { pages += Page(); y = top }

        val atPageTop: Boolean get() = y <= top

        fun breakPage() { if (y > top) newPage() }

        /** Space between pieces; never carried to the top of a new page. */
        fun gap(height: Float) {
            if (y <= top) return
            if (y + height >= bottom) newPage() else y += height
        }

        /** Content that must stay on one page. */
        fun atomic(height: Float, draw: (Canvas, Float) -> Unit) {
            if (y + height > bottom && y > top) newPage()
            val at = y
            pages.last().ops += { draw(it, at) }
            y += height
        }

        /** Text that may continue on the next page, split between lines. */
        fun text(layout: StaticLayout, x: Float, box: Box? = null, first: ((Canvas, Float) -> Unit)? = null) {
            val lines = layout.lineCount
            var line = 0
            var firstSlice = true
            while (line < lines) {
                val padTop = if (firstSlice) box?.padY ?: 0f else 0f
                val avail = bottom - y - padTop
                val sliceTop = layout.getLineTop(line).toFloat()
                var end = line
                while (end < lines) {
                    val need = layout.getLineBottom(end) - sliceTop + if (end == lines - 1) box?.padY ?: 0f else 0f
                    if (need > avail) break
                    end++
                }
                if (end == line) {
                    if (y > top) { newPage(); continue }
                    end = line + 1
                }
                val last = end == lines
                val sliceBottom = layout.getLineBottom(end - 1).toFloat()
                val height = sliceBottom - sliceTop
                val padBottom = if (last) box?.padY ?: 0f else 0f
                val at = y
                val isFirst = firstSlice
                pages.last().ops += { canvas ->
                    if (box != null) {
                        val rect = RectF(box.x, at, box.x + box.width, at + padTop + height + padBottom)
                        val tl = if (isFirst) box.radiusLeft else 0f
                        val tr = if (isFirst) box.radiusRight else 0f
                        val bl = if (last) box.radiusLeft else 0f
                        val br = if (last) box.radiusRight else 0f
                        val path = Path().apply {
                            addRoundRect(rect, floatArrayOf(tl, tl, tr, tr, br, br, bl, bl), Path.Direction.CW)
                        }
                        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = box.fill })
                        box.bar?.let { bar ->
                            canvas.drawRect(box.x, rect.top, box.x + box.barWidth, rect.bottom,
                                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bar })
                        }
                    }
                    if (isFirst) first?.invoke(canvas, at + padTop)
                    canvas.save()
                    canvas.clipRect(x - 4f, at + padTop, x + layout.width + 4f, at + padTop + height)
                    canvas.translate(x, at + padTop - sliceTop)
                    layout.draw(canvas)
                    canvas.restore()
                }
                y += padTop + height + padBottom
                line = end
                firstSlice = false
            }
        }
    }
}

/** Android printing and Save PDF for one note. */
internal object NotePrinting {

    /** Opens the system print dialog; its "Save as PDF" printer works too. */
    fun print(context: Context, jobName: String, source: NotePrintSource, settings: NotePrintSettings) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val media = if (settings.landscape) settings.paper.media.asLandscape() else settings.paper.media.asPortrait()
        val attributes = PrintAttributes.Builder()
            .setMediaSize(media)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()
        manager.print(jobName, NotePrintAdapter(jobName, source, settings), attributes)
    }

    /** Writes the note to [uri] as a PDF; returns the page count. */
    suspend fun savePdf(context: Context, uri: Uri, source: NotePrintSource, settings: NotePrintSettings): Int =
        withContext(Dispatchers.IO) {
            val pages = NotePrintRenderer.layout(source, settings)
            val raw = java.io.ByteArrayOutputStream().also { NotePrintRenderer.write(pages, settings, source.colors, it) }
            val bytes = NotePdfSlimmer.slim(raw.toByteArray(), NoteImageQuality.SHARP.jpeg)
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file")
            out.use { it.write(bytes) }
            pages.size
        }

    /** Fonts the note uses, ready for drawing. A font that cannot load prints in the default face. */
    suspend fun loadTypefaces(resolver: FontFamily.Resolver, blocks: List<NoteDocBlock>): Map<String, Typeface> {
        val names = blocks.flatMap { b ->
            b.runs.mapNotNull { it.fontFamily } + b.table?.cellRuns.orEmpty().flatten().mapNotNull { it.fontFamily }
        }.toSet() - setOf("Inter", "Serif", "Mono")
        val out = HashMap<String, Typeface>()
        names.forEach { name ->
            val family = NoteFonts.familyFor(name) ?: return@forEach
            runCatching { withTimeoutOrNull(4_000) { resolver.preload(family) } }
            runCatching { resolver.resolveAsTypeface(family).value }.getOrNull()?.let { out[name] = it }
        }
        return out
    }

    fun fileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifBlank { "Note" } + ".pdf"
}

/** Lays the note out for whatever paper the print dialog picks, keeping the chosen margins and numbers. */
private class NotePrintAdapter(
    private val jobName: String,
    private val source: NotePrintSource,
    private val settings: NotePrintSettings,
) : PrintDocumentAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var pages: List<NotePrintRenderer.Page> = emptyList()
    private var widthPt = settings.pageWidthPt
    private var heightPt = settings.pageHeightPt

    override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes,
                          cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        job?.cancel()
        if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
        val media = newAttributes.mediaSize
        val width = media?.let { it.widthMils * 72f / 1000f } ?: settings.pageWidthPt
        val height = media?.let { it.heightMils * 72f / 1000f } ?: settings.pageHeightPt
        val changed = width != widthPt || height != heightPt || pages.isEmpty()
        job = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) { NotePrintRenderer.layout(source, settings, width, height) }
            }
            if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return@launch }
            result.onSuccess {
                pages = it; widthPt = width; heightPt = height
                callback.onLayoutFinished(PrintDocumentInfo.Builder(jobName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(it.size)
                    .build(), changed)
            }.onFailure { callback.onLayoutFailed(it.message ?: "Could not lay out the note") }
        }
        cancellationSignal.setOnCancelListener { job?.cancel() }
    }

    override fun onWrite(ranges: Array<out PageRange>, destination: ParcelFileDescriptor,
                         cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
        val laidOut = pages
        job = scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        NotePrintRenderer.write(laidOut, settings, source.colors, out, widthPt, heightPt)
                    }
                }
            }
            if (cancellationSignal.isCanceled) { callback.onWriteCancelled(); return@launch }
            result.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                .onFailure { callback.onWriteFailed(it.message ?: "Could not write the note") }
        }
        cancellationSignal.setOnCancelListener { job?.cancel() }
    }

    override fun onFinish() { scope.cancel() }
}
