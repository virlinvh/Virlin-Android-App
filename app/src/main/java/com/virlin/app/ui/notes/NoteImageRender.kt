package com.virlin.app.ui.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.File
import kotlin.math.max

/** Decodes, previews and renders [NoteImageEdit]s with Android bitmaps. Call off the main thread. */
object NoteImageRender {
    /** Longest side of a saved edit; keeps memory bounded on every phone. */
    const val MAX_SIDE = 2560
    /** Longest side of the editor's live preview. */
    const val PREVIEW_SIDE = 1600
    /** Highlighter ink is laid over the picture with multiply, so text underneath stays readable. */
    const val HIGHLIGHTER_ALPHA = 0.85f

    fun decode(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: return null
        val longest = max(decoded.width, decoded.height)
        if (longest <= maxSide) return decoded
        val scale = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    fun turned(source: Bitmap, quarterTurns: Int): Bitmap {
        val q = Math.floorMod(quarterTurns, 4)
        if (q == 0) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(90f * q) }, true)
    }

    /** A whitened copy of [source] (see [NoteImageColor.whiten]). */
    fun whitened(source: Bitmap, blackAndWhite: Boolean): Bitmap {
        val w = source.width; val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        NoteImageColor.whiten(pixels, w, h, blackAndWhite)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** [source] with every step of [edit] applied: turn → crop → filter and adjustments → pen marks. */
    fun render(source: Bitmap, edit: NoteImageEdit): Bitmap {
        val turned = turned(source, edit.quarterTurns)
        val (cx, cy, cw, ch) = NoteImageGeometry.cropPixels(edit.crop, turned.width, turned.height).toList()
        var base = if (cx == 0 && cy == 0 && cw == turned.width && ch == turned.height) turned
            else Bitmap.createBitmap(turned, cx, cy, cw, ch)
        if (edit.filter == NoteImageFilter.DOCUMENT || edit.filter == NoteImageFilter.DOCUMENT_BW) {
            base = whitened(base, edit.filter == NoteImageFilter.DOCUMENT_BW)
        }
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val matrix = NoteImageColor.adjustMatrix(edit.brightness, edit.contrast, edit.saturation,
            gray = edit.filter == NoteImageFilter.GRAYSCALE)
        canvas.drawBitmap(base, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (!NoteImageColor.isIdentity(matrix)) colorFilter = ColorMatrixColorFilter(matrix)
        })
        val longSide = max(source.width, source.height).toFloat()
        edit.strokes.forEach { stroke ->
            val path = strokePath(stroke, edit.quarterTurns, turned.width.toFloat(), turned.height.toFloat(),
                cx.toFloat(), cy.toFloat(), 1f, 0f, 0f)
            canvas.drawPath(path, strokePaint(stroke, longSide))
        }
        if (base !== turned && base !== source) base.recycle()
        if (turned !== source) turned.recycle()
        return out
    }

    fun strokePaint(stroke: NoteImageStroke, longSide: Float, scale: Float = 1f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = (stroke.width * longSide * scale).coerceAtLeast(1f)
            color = stroke.color.toInt()
            if (stroke.highlighter) {
                alpha = (255 * HIGHLIGHTER_ALPHA).toInt()
                xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
        }

    /**
     * The smoothed path of [stroke] in target pixels. Points go original image → turned image
     * ([turnedW] × [turnedH] px) → minus the crop origin ([originX], [originY]) → × [scale] +
     * ([dx], [dy]). A single point becomes a dot.
     */
    fun strokePath(stroke: NoteImageStroke, quarterTurns: Int, turnedW: Float, turnedH: Float,
                   originX: Float, originY: Float, scale: Float, dx: Float, dy: Float): Path {
        val path = Path()
        if (stroke.points.isEmpty()) return path
        fun x(p: NoteImagePoint) = dx + (NoteImageGeometry.toTurned(p, quarterTurns).x * turnedW - originX) * scale
        fun y(p: NoteImagePoint) = dy + (NoteImageGeometry.toTurned(p, quarterTurns).y * turnedH - originY) * scale
        val first = stroke.points.first()
        path.moveTo(x(first), y(first))
        if (stroke.points.size == 1) { path.lineTo(x(first) + 0.1f, y(first)); return path }
        var px = x(first); var py = y(first)
        for (i in 1 until stroke.points.size) {
            val p = stroke.points[i]
            val nx = x(p); val ny = y(p)
            path.quadTo(px, py, (px + nx) / 2f, (py + ny) / 2f)
            px = nx; py = ny
        }
        path.lineTo(px, py)
        return path
    }
}
