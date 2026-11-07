package com.virlin.app.ui.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import com.virlin.app.data.noteimage.NoteImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class NoteExportFormat(val label: String, val caption: String) {
    PDF("PDF", "Small, sharp, fixed pages"),
    WORD("Word", "Editable · Google Docs"),
    MARKDOWN("Markdown", "Plain text .md"),
    IMAGE("Image", "JPEG or PNG"),
}

/** Image detail inside PDF and Word files: resolution on paper and JPEG quality. */
enum class NoteImageQuality(val label: String, val dpi: Int, val jpeg: Int) {
    COMPACT("Compact", 110, 70), BALANCED("Balanced", 160, 80), SHARP("Sharp", 240, 88)
}

enum class NoteImageOutput(val label: String) { LONG("Long image"), PAGES("Pages (.zip)") }

enum class NoteImageType(val label: String) { JPEG("JPEG"), PNG("PNG") }

/**
 * Choices that belong to one file type. Paper, margins, page numbers and the title come from the
 * note's page formatting ([NotePrintSettings]). Nothing here is stored in the note.
 */
data class NoteExportSettings(
    val format: NoteExportFormat? = null,
    val imageQuality: NoteImageQuality = NoteImageQuality.BALANCED,
    /** Markdown: put the note's pictures beside it in a .zip. */
    val markdownImages: Boolean = false,
    val imageOutput: NoteImageOutput = NoteImageOutput.LONG,
    val imageType: NoteImageType = NoteImageType.JPEG,
    val sharpImage: Boolean = false,
) {
    /** Whether the file has pages, so page formatting shapes it. */
    val usesPages: Boolean get() = format != null && format != NoteExportFormat.MARKDOWN

    val zipped: Boolean get() = when (format) {
        NoteExportFormat.MARKDOWN -> markdownImages
        NoteExportFormat.IMAGE -> imageOutput == NoteImageOutput.PAGES
        else -> false
    }

    val extension: String get() = when {
        zipped -> "zip"
        format == NoteExportFormat.PDF -> "pdf"
        format == NoteExportFormat.WORD -> "docx"
        format == NoteExportFormat.MARKDOWN -> "md"
        imageType == NoteImageType.PNG -> "png"
        else -> "jpg"
    }

    val mimeType: String get() = when (extension) {
        "zip" -> "application/zip"
        "pdf" -> "application/pdf"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "md" -> "text/markdown"
        "png" -> "image/png"
        else -> "image/jpeg"
    }
}

/** Asks Android where to save a new file of a given type. Input: file name to MIME type. */
internal class CreateExportDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Pictures prepared for files: never larger than they will be seen, JPEG unless they need transparency. */
internal object NoteExportImages {
    class Encoded(val bytes: ByteArray, val extension: String, val widthPx: Int, val heightPx: Int)

    /** Decodes [path] at about [targetWidth] pixels wide (never upscaled). */
    fun decodeFor(path: String, sourceWidth: Int, sourceHeight: Int, targetWidth: Int): Bitmap? {
        val target = targetWidth.coerceAtLeast(16)
        var sample = 1
        while (sourceWidth / (sample * 2) >= target && sourceHeight / (sample * 2) >= 1) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (decoded.width <= target) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, target,
            (decoded.height.toLong() * target / decoded.width).toInt().coerceAtLeast(1), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** The picture for a document [displayWidthPt] wide at [quality]; null when it can't be read. */
    fun encode(file: File, displayWidthPt: Float, quality: NoteImageQuality): Encoded? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val bitmap = decodeFor(file.absolutePath, bounds.outWidth, bounds.outHeight,
            (displayWidthPt / 72f * quality.dpi).toInt()) ?: return null
        return try {
            val png = NoteImageStore.keepsTransparency(file) && bitmap.hasAlpha() && hasTransparentPixels(bitmap)
            val out = ByteArrayOutputStream()
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                if (png) 100 else quality.jpeg, out)
            Encoded(out.toByteArray(), if (png) "png" else "jpeg", bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** True when some pixel is not fully opaque; many PNGs carry an alpha channel they never use. */
    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { (it ushr 24) != 0xFF }) return true
        }
        return false
    }
}

private fun NotePrintSource.withImageDpi(dpi: Int) =
    NotePrintSource(title, blocks, colors, typefaces, imageFile, dpi, thumbnail)

/** Writes a note in the chosen format. Reads the note only; returns the bytes written. */
internal object NoteExporter {
    /** Long images taller than this are drawn narrower so they stay within memory. */
    private const val MAX_LONG_PIXELS = 24_000_000L

    suspend fun export(context: Context, uri: Uri, settings: NoteExportSettings, page: NotePrintSettings,
                       source: NotePrintSource): Long =
        withContext(Dispatchers.IO) {
            val bytes = bytes(context, settings, page, source)
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file")
            out.use { it.write(bytes) }
            bytes.size.toLong()
        }

    fun bytes(context: Context, settings: NoteExportSettings, page: NotePrintSettings,
              source: NotePrintSource): ByteArray = when (settings.format) {
        NoteExportFormat.PDF -> pdf(settings, page, source)
        NoteExportFormat.WORD -> ByteArrayOutputStream().also { out ->
            NoteDocxWriter.write(out, if (page.includeTitle) source.title else null, source.blocks, page,
                source.colors) { name, widthPt ->
                source.imageFile(name)?.takeIf { it.isFile }?.let { NoteExportImages.encode(it, widthPt, settings.imageQuality) }
            }
        }.toByteArray()
        NoteExportFormat.MARKDOWN -> markdown(settings, page, source)
        NoteExportFormat.IMAGE -> image(settings, page, source)
        null -> error("Choose a format first")
    }

    private fun pdf(settings: NoteExportSettings, print: NotePrintSettings, source: NotePrintSource): ByteArray {
        val sized = source.withImageDpi(settings.imageQuality.dpi)
        val raw = ByteArrayOutputStream().also {
            NotePrintRenderer.write(NotePrintRenderer.layout(sized, print), print, source.colors, it)
        }.toByteArray()
        return NotePdfSlimmer.slim(raw, settings.imageQuality.jpeg)
    }

    private fun markdown(settings: NoteExportSettings, page: NotePrintSettings, source: NotePrintSource): ByteArray {
        val title = if (page.includeTitle) source.title else null
        if (!settings.markdownImages) {
            return NoteMarkdownWriter.write(title, source.blocks) { null }.toByteArray(Charsets.UTF_8)
        }
        val files = LinkedHashMap<String, NoteExportImages.Encoded>()
        val text = NoteMarkdownWriter.write(title, source.blocks) { name ->
            val file = source.imageFile(name)?.takeIf { it.isFile } ?: return@write null
            val encoded = NoteExportImages.encode(file, 480f, NoteImageQuality.SHARP) ?: return@write null
            val path = "images/image-${files.size + 1}.${if (encoded.extension == "png") "png" else "jpg"}"
            files[path] = encoded
            path
        }
        return zip { put ->
            put("${baseName(source.title)}.md", text.toByteArray(Charsets.UTF_8))
            files.forEach { (path, image) -> put(path, image.bytes) }
        }
    }

    private fun image(settings: NoteExportSettings, print: NotePrintSettings, source: NotePrintSource): ByteArray {
        val widthPx = if (settings.sharpImage) 2160 else 1240
        val sized = source.withImageDpi(if (settings.sharpImage) 300 else 170)
        val png = settings.imageType == NoteImageType.PNG
        fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, if (png) 100 else 86, it)
        }.toByteArray()
        fun render(page: NotePrintRenderer.Page, heightPt: Float, scale: Float, number: (Canvas) -> Unit): ByteArray {
            val bitmap = Bitmap.createBitmap((print.pageWidthPt * scale).toInt().coerceAtLeast(1),
                (heightPt * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.scale(scale, scale)
                page.ops.forEach { it(canvas) }
                number(canvas)
                return encode(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        if (settings.imageOutput == NoteImageOutput.LONG) {
            val (page, height) = NotePrintRenderer.layoutLong(sized, print.copy(pageNumbers = NotePageNumbers.OFF))
            var scale = widthPx / print.pageWidthPt
            val pixels = (print.pageWidthPt * scale).toLong() * (height * scale).toLong()
            if (pixels > MAX_LONG_PIXELS) scale *= kotlin.math.sqrt(MAX_LONG_PIXELS.toDouble() / pixels).toFloat()
            require(print.pageWidthPt * scale >= 480f) { "This note is too long for one image. Choose Pages (.zip) instead." }
            return render(page, height, scale) {}
        }
        val pages = NotePrintRenderer.layout(sized, print)
        val scale = widthPx / print.pageWidthPt
        val ext = if (png) "png" else "jpg"
        return zip { put ->
            pages.forEachIndexed { i, page ->
                val bytes = render(page, print.pageHeightPt, scale) { canvas ->
                    NotePrintRenderer.drawPageNumber(canvas, i, pages.size, print, source.colors,
                        print.pageWidthPt, print.pageHeightPt)
                }
                put("${baseName(source.title)} - page ${(i + 1).toString().padStart(2, '0')}.$ext", bytes)
            }
        }
    }

    private fun zip(fill: ((String, ByteArray) -> Unit) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            fill { name, data ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    fun baseName(title: String?): String =
        title.orEmpty().replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifBlank { "Note" }

    fun fileName(title: String?, settings: NoteExportSettings) = "${baseName(title)}.${settings.extension}"

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
    }
}
