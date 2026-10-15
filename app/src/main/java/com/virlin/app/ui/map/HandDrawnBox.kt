package com.virlin.app.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

internal fun MapBoxFinish.isHandDrawn() = this == MapBoxFinish.HAND_DRAWN ||
    this == MapBoxFinish.PENCIL_SKETCH || this == MapBoxFinish.DOUBLE_INK ||
    this == MapBoxFinish.MARKER_EDGE

/** Repeatable vector strokes: the same finish is used by the picker and every map node. */
internal fun drawHandDrawnBox(canvas: Canvas, rect: RectF, paint: Paint,
                              finish: MapBoxFinish, selected: Boolean = false) {
    val radius = min(18f, rect.height() * .27f)
    val path = Path().apply {
        moveTo(rect.left + radius, rect.top + 1f)
        lineTo(rect.right - radius * .8f, rect.top)
        quadTo(rect.right + 1.5f, rect.top + 2f, rect.right, rect.top + radius)
        lineTo(rect.right + 1f, rect.bottom - radius * .9f)
        quadTo(rect.right - 1f, rect.bottom + 1f, rect.right - radius, rect.bottom)
        lineTo(rect.left + radius * .9f, rect.bottom + 1f)
        quadTo(rect.left - 1f, rect.bottom - 2f, rect.left, rect.bottom - radius)
        lineTo(rect.left - 1f, rect.top + radius * 1.1f)
        quadTo(rect.left + 1f, rect.top, rect.left + radius, rect.top + 1f)
        close()
    }
    paint.shader = null
    paint.style = Paint.Style.FILL
    paint.color = when (finish) {
        MapBoxFinish.PENCIL_SKETCH -> Color.rgb(247, 245, 239)
        MapBoxFinish.DOUBLE_INK -> Color.rgb(251, 250, 247)
        MapBoxFinish.MARKER_EDGE -> Color.rgb(232, 234, 231)
        else -> Color.rgb(239, 239, 237)
    }
    paint.alpha = 255
    canvas.drawPath(path, paint)
    canvas.save()
    canvas.clipPath(path)
    paint.color = if (finish == MapBoxFinish.PENCIL_SKETCH) Color.DKGRAY else Color.WHITE
    paint.alpha = if (finish == MapBoxFinish.PENCIL_SKETCH) 35 else 180
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.5f
    var x = rect.left - rect.height()
    while (x < rect.right) {
        canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, paint)
        x += if (finish == MapBoxFinish.PENCIL_SKETCH) 9f else 12f
    }
    canvas.restore()
    paint.color = if (finish == MapBoxFinish.PENCIL_SKETCH) Color.rgb(82, 82, 79)
                  else Color.rgb(24, 24, 24)
    paint.alpha = 255
    paint.strokeWidth = when (finish) {
        MapBoxFinish.PENCIL_SKETCH -> if (selected) 4f else 2.5f
        MapBoxFinish.MARKER_EDGE -> if (selected) 8f else 6.5f
        else -> if (selected) 6f else 4.5f
    }
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeCap = Paint.Cap.ROUND
    canvas.drawPath(path, paint)
    when (finish) {
        MapBoxFinish.PENCIL_SKETCH -> {
            paint.alpha = 75
            paint.strokeWidth = 1.3f
            canvas.save()
            canvas.translate(2.5f, -2f)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        MapBoxFinish.DOUBLE_INK -> {
            paint.alpha = 175
            paint.strokeWidth = 1.6f
            val inset = 5f
            if (rect.width() > inset * 4 && rect.height() > inset * 4) {
                canvas.drawRoundRect(RectF(rect.left + inset, rect.top + inset,
                    rect.right - inset, rect.bottom - inset),
                    (radius - inset).coerceAtLeast(2f), (radius - inset).coerceAtLeast(2f), paint)
            }
        }
        MapBoxFinish.MARKER_EDGE -> {
            paint.alpha = 65
            paint.strokeWidth = 2.5f
            canvas.save()
            canvas.translate(-2f, 3f)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        else -> Unit
    }
    paint.alpha = 255
    paint.style = Paint.Style.FILL
}
