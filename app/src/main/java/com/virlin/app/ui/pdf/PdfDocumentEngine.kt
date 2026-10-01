package com.virlin.app.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Native, local-only PDF operations. Exports are intentionally rasterized because Android's
 * platform APIs can render and create PDFs but cannot copy arbitrary source PDF objects safely.
 */
object PdfDocumentEngine {
    const val DEFAULT_EXPORT_WIDTH = 1440

    fun pageCount(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use(PdfRenderer::getPageCount)
        }

    fun renderPage(
        file: File,
        pageIndex: Int,
        targetWidth: Int,
        crop: PdfCrop = PdfCrop()
    ): Bitmap = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(pageIndex in 0 until renderer.pageCount) { "Page ${pageIndex + 1} is unavailable" }
            renderer.openPage(pageIndex).use { page ->
                val width = targetWidth.coerceAtLeast(1)
                val height = (page.height * (width.toFloat() / page.width)).toInt().coerceAtLeast(1)
                val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                full.eraseColor(Color.WHITE)
                page.render(full, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val bounds = crop.pixelBounds(full.width, full.height)
                if (bounds.contentEquals(intArrayOf(0, 0, full.width, full.height))) return@use full
                Bitmap.createBitmap(
                    full,
                    bounds[0],
                    bounds[1],
                    bounds[2] - bounds[0],
                    bounds[3] - bounds[1]
                ).also { if (it !== full) full.recycle() }
            }
        }
    }

    fun exportPdf(
        source: File,
        destination: File,
        pageIndexes: List<Int>,
        crops: Map<Int, PdfCrop> = emptyMap()
    ): File {
        require(pageIndexes.isNotEmpty()) { "Select at least one page" }
        destination.parentFile?.mkdirs()
        val document = PdfDocument()
        try {
            pageIndexes.forEachIndexed { outputIndex, sourceIndex ->
                val bitmap = renderPage(source, sourceIndex, DEFAULT_EXPORT_WIDTH, crops[sourceIndex] ?: PdfCrop())
                try {
                    val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, outputIndex + 1).create()
                    val page = document.startPage(info)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(
                        bitmap,
                        null,
                        Rect(0, 0, info.pageWidth, info.pageHeight),
                        null
                    )
                    document.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }
            FileOutputStream(destination).use(document::writeTo)
        } catch (error: Throwable) {
            destination.delete()
            throw error
        } finally {
            document.close()
        }
        return destination
    }

    fun exportPngs(
        source: File,
        destinationDirectory: File,
        pageIndexes: List<Int>,
        crops: Map<Int, PdfCrop> = emptyMap()
    ): List<File> {
        require(pageIndexes.isNotEmpty()) { "Select at least one page" }
        destinationDirectory.mkdirs()
        val written = mutableListOf<File>()
        try {
            pageIndexes.forEach { sourceIndex ->
                val bitmap = renderPage(source, sourceIndex, DEFAULT_EXPORT_WIDTH, crops[sourceIndex] ?: PdfCrop())
                val output = File(destinationDirectory, "page-${sourceIndex + 1}.png")
                try {
                    FileOutputStream(output).use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "Could not encode page" }
                        stream.fd.sync()
                    }
                    written += output
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (error: Throwable) {
            written.forEach(File::delete)
            throw error
        }
        return written
    }
}
