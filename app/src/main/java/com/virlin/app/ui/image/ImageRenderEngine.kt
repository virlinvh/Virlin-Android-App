package com.virlin.app.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Local-only, raster and non-destructive image operations. The source file is never overwritten. */
object ImageRenderEngine {
    const val MAX_DECODE_EDGE = 4096
    const val PREVIEW_EDGE = 1600

    fun decode(source: File, maxEdge: Int = MAX_DECODE_EDGE): Bitmap {
        require(source.isFile && source.length() > 0L) { "Image file is missing" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or damaged image" }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxEdge * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return requireNotNull(BitmapFactory.decodeFile(source.absolutePath, options)) {
            "The image could not be decoded"
        }
    }

    fun render(
        source: File,
        crop: ImageCrop,
        transform: ImageTransform,
        adjustments: ImageAdjustments,
        strokes: List<ImageMarkupStroke>,
        maxEdge: Int = MAX_DECODE_EDGE,
    ): Bitmap {
        var current = decode(source, maxEdge)
        val bounds = crop.pixelBounds(current.width, current.height)
        current = replace(current, Bitmap.createBitmap(
            current, bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1]
        ))

        val tx = transform.normalized()
        if (tx.rotationQuarterTurns != 0 || tx.flipHorizontal || tx.flipVertical || tx.straightenDegrees != 0f) {
            val matrix = Matrix().apply {
                postScale(if (tx.flipHorizontal) -1f else 1f, if (tx.flipVertical) -1f else 1f)
                postRotate(tx.rotationQuarterTurns * 90f + tx.straightenDegrees)
            }
            current = replace(current, Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true))
        }

        val output = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val a = adjustments.normalized()
        val saturation = ColorMatrix().apply { setSaturation(1f + a.saturation) }
        val contrast = 1f + a.contrast
        val translate = a.brightness * 255f + (1f - contrast) * 128f
        val tune = ColorMatrix(floatArrayOf(
            contrast + a.warmth * .10f, 0f, 0f, 0f, translate + a.warmth * 18f,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast - a.warmth * .10f, 0f, translate - a.warmth * 18f,
            0f, 0f, 0f, 1f, 0f,
        ))
        saturation.postConcat(tune)
        canvas.drawBitmap(current, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(saturation)
        })
        drawMarkup(canvas, output.width, output.height, strokes)
        current.recycle()
        return output
    }

    fun savePng(bitmap: Bitmap, output: File) {
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            stream.fd.sync()
        }
    }

    private fun drawMarkup(canvas: Canvas, width: Int, height: Int, strokes: List<ImageMarkupStroke>) {
        strokes.forEach { stroke ->
            val points = stroke.points.map { (x, y) -> x.coerceIn(0f, 1f) * width to y.coerceIn(0f, 1f) * height }
            if (points.isEmpty()) return@forEach
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.colorArgb
                alpha = (stroke.opacity.coerceIn(0f, 1f) * 255).toInt()
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = stroke.widthFraction.coerceIn(.001f, .15f) * width
            }
            when (stroke.tool) {
                ImageMarkupTool.TEXT -> {
                    paint.style = Paint.Style.FILL
                    paint.textSize = max(22f, stroke.widthFraction * width * 5f)
                    canvas.drawText(stroke.text.orEmpty().ifBlank { "Note" }, points.first().first, points.first().second, paint)
                }
                ImageMarkupTool.ARROW -> if (points.size >= 2) {
                    val start = points.first(); val end = points.last()
                    canvas.drawLine(start.first, start.second, end.first, end.second, paint)
                    val angle = atan2(end.second - start.second, end.first - start.first)
                    val head = paint.strokeWidth * 3.2f
                    canvas.drawLine(end.first, end.second, end.first - head * cos(angle - .55f), end.second - head * sin(angle - .55f), paint)
                    canvas.drawLine(end.first, end.second, end.first - head * cos(angle + .55f), end.second - head * sin(angle + .55f), paint)
                }
                ImageMarkupTool.SHAPE -> if (points.size >= 2) {
                    val start = points.first(); val end = points.last()
                    canvas.drawRect(
                        minOf(start.first, end.first), minOf(start.second, end.second),
                        maxOf(start.first, end.first), maxOf(start.second, end.second), paint
                    )
                }
                ImageMarkupTool.ERASER -> Unit // Eraser is handled by removing intersected strokes in the editor state.
                else -> {
                    val path = Path().apply {
                        moveTo(points.first().first, points.first().second)
                        points.drop(1).forEach { lineTo(it.first, it.second) }
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    private fun replace(old: Bitmap, next: Bitmap): Bitmap {
        if (old !== next) old.recycle()
        return next
    }
}
