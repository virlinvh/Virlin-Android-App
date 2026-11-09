package com.virlin.app.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val LEGACY_HAND_DRAWN = setOf(
    MapBoxFinish.HAND_DRAWN, MapBoxFinish.PENCIL_SKETCH, MapBoxFinish.DOUBLE_INK, MapBoxFinish.MARKER_EDGE,
)

/** Hand-drawn finishes painted here; the original four keep their own painter in HandDrawnBox.kt. */
internal fun MapBoxFinish.isCrafted() = isHandDrawn() && this !in LEGACY_HAND_DRAWN

internal fun MapBoxFinish.isMaterial() = group == MapBoxFinishGroup.MATERIAL

// Map and picker painting both run on the main thread; these are reused, never nested.
private val edgePath = Path()
private val backPath = Path()
private val scratchPath = Path()
private val scratchRect = RectF()

private val CRAYON_DASH = DashPathEffect(floatArrayOf(18f, 2.5f, 7f, 1.5f), 0f)
private val CRAYON_DASH_2 = DashPathEffect(floatArrayOf(11f, 3f, 5f, 2f), 4f)
private val STITCH_DASH = DashPathEffect(floatArrayOf(6f, 4f), 0f)
private val BRISTLES = arrayOf(
    DashPathEffect(floatArrayOf(22f, 3f, 9f, 5f), 0f),
    DashPathEffect(floatArrayOf(14f, 6f, 30f, 2f), 7f),
    DashPathEffect(floatArrayOf(40f, 2f), 3f),
    DashPathEffect(floatArrayOf(12f, 4f, 18f, 7f), 11f),
    DashPathEffect(floatArrayOf(26f, 8f, 6f, 3f), 5f),
)

/** Deterministic 0..1 noise: the same box draws the same marks on every frame. */
internal fun mapNoise(a: Int, b: Int, seed: Int): Float {
    var h = (a * 73856093) xor (b * 19349663) xor (seed * 83492791)
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65535f
}

private fun seedOf(r: RectF) = r.left.toInt() * 31 + r.top.toInt() * 17 + r.width().toInt()

private fun Paint.solid(color: Int, alpha: Int = 255) {
    shader = null; pathEffect = null; style = Paint.Style.FILL
    this.color = color; this.alpha = alpha
}

private fun Paint.pen(color: Int, width: Float, alpha: Int = 255) {
    shader = null; pathEffect = null; style = Paint.Style.STROKE
    this.color = color; this.alpha = alpha; strokeWidth = width
    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
}

/** A rounded box traced by hand: every edge wanders by up to [wobble] in small steps. */
internal fun wobblyPath(r: RectF, seed: Int, wobble: Float, radius: Float, path: Path): Path {
    fun j(i: Int, k: Int) = (mapNoise(i, k, seed) - .5f) * 2f * wobble
    val step = 16f
    var i = 1
    path.reset()
    path.moveTo(r.left + radius, r.top + j(0, 0))
    var x = r.left + radius + step
    while (x < r.right - radius) { path.lineTo(x, r.top + j(i++, 0)); x += step }
    path.quadTo(r.right, r.top, r.right + j(i, 1), r.top + radius)
    var y = r.top + radius + step
    while (y < r.bottom - radius) { path.lineTo(r.right + j(i++, 1), y); y += step }
    path.quadTo(r.right, r.bottom, r.right - radius, r.bottom + j(i, 2))
    x = r.right - radius - step
    while (x > r.left + radius) { path.lineTo(x, r.bottom + j(i++, 2)); x -= step }
    path.quadTo(r.left, r.bottom, r.left + j(i, 3), r.bottom - radius)
    y = r.bottom - radius - step
    while (y > r.top + radius) { path.lineTo(r.left + j(i++, 3), y); y -= step }
    path.quadTo(r.left, r.top, r.left + radius, r.top + j(0, 0))
    path.close()
    return path
}

private fun drawTape(canvas: Canvas, paint: Paint, cx: Float, cy: Float, angle: Float) {
    canvas.save()
    canvas.rotate(angle, cx, cy)
    canvas.drawRect(cx - 14f, cy - 5f, cx + 14f, cy + 5f, paint)
    canvas.restore()
}

/**
 * The handmade finishes. Paper comes from [fillColor] (the palette-tinted paper from
 * [boxColors]) so the map's palette still colours every box; [paletteTint] drives pigment
 * marks, and [strokeColor] / [boxColor] replace the default ink exactly as the original
 * hand-drawn finishes do.
 */
internal fun drawCraftedBox(
    canvas: Canvas, rect: RectF, paint: Paint, finish: MapBoxFinish, selected: Boolean,
    boxColor: Int?, fillColor: Int?, strokeColor: Int?, drawStroke: Boolean, paletteTint: Int?,
) {
    val seed = seedOf(rect)
    val radius = min(14f, rect.height() * .24f)
    val paper = if (finish == MapBoxFinish.STICKY_NOTE) Color.rgb(253, 239, 160) else Color.rgb(250, 249, 245)
    val fill = fillColor ?: boxColor?.let { ColorUtils.blendARGB(paper, it, .42f) } ?: paper
    val pigment = paletteTint ?: boxColor ?: Color.rgb(96, 128, 112)
    val ink = strokeColor ?: boxColor ?: when (finish) {
        MapBoxFinish.CHARCOAL -> Color.rgb(46, 46, 46)
        MapBoxFinish.FELT_TIP -> Color.rgb(32, 40, 52)
        else -> Color.rgb(28, 28, 30)
    }
    val extra = if (selected) 1.5f else 0f
    val outline = drawStroke || selected
    val cap = paint.strokeCap
    val join = paint.strokeJoin
    val width = paint.strokeWidth
    when (finish) {
        MapBoxFinish.CRAYON -> {
            val p = wobblyPath(rect, seed, 1.2f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            canvas.save(); canvas.clipPath(p)
            paint.pen(pigment, 3f, 42)
            val h = rect.height()
            var x = rect.left - h
            var i = 0
            while (x < rect.right) {
                // Waxy strokes break where the crayon skipped.
                val skip = mapNoise(i++, 5, seed)
                val a = skip * .3f
                val b = .7f + skip * .3f
                canvas.drawLine(x + h * a, rect.bottom - h * a, x + h * b, rect.bottom - h * b, paint)
                x += 6f
            }
            canvas.restore()
            if (outline) {
                paint.pen(ink, 3.4f + extra, 210); paint.pathEffect = CRAYON_DASH
                canvas.drawPath(p, paint)
                canvas.save(); canvas.translate(.9f, .8f)
                paint.strokeWidth = 2f + extra; paint.alpha = 120; paint.pathEffect = CRAYON_DASH_2
                canvas.drawPath(p, paint)
                canvas.restore()
            }
        }
        MapBoxFinish.CHARCOAL -> {
            val p = wobblyPath(rect, seed, 1f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            canvas.save(); canvas.clipPath(p)
            // Smudge pooled towards the bottom where a hand rested on it.
            var y = rect.top + rect.height() * .55f
            var i = 0
            while (y < rect.bottom) {
                val t = (y - rect.top) / rect.height()
                paint.pen(ink, 2.5f, (10 + 34 * t).toInt())
                canvas.drawLine(rect.left + mapNoise(i, 6, seed) * 24f, y,
                    rect.right - mapNoise(i, 7, seed) * 24f, y, paint)
                y += 2.5f; i++
            }
            canvas.restore()
            if (outline) {
                paint.pen(ink, 3.2f, 85)
                canvas.save(); canvas.translate(-.8f, .5f); canvas.drawPath(p, paint); canvas.restore()
                paint.strokeWidth = 2.2f; paint.alpha = 70
                canvas.save(); canvas.translate(.7f, -.6f); canvas.drawPath(p, paint); canvas.restore()
                paint.strokeWidth = 1.6f + extra; paint.alpha = 160
                canvas.drawPath(p, paint)
            }
        }
        MapBoxFinish.WATERCOLOUR -> {
            val p = wobblyPath(rect, seed, 1.6f, radius, edgePath)
            paint.solid(ColorUtils.blendARGB(fill, Color.WHITE, .35f)); canvas.drawPath(p, paint)
            canvas.save(); canvas.clipPath(p)
            paint.solid(pigment, 30)
            for (k in 0 until 5) {
                val cx = rect.left + rect.width() * (k + mapNoise(k, 1, seed)) / 5f
                val cy = rect.top + rect.height() * (.3f + .5f * mapNoise(k, 2, seed))
                canvas.drawCircle(cx, cy, rect.height() * (.45f + .3f * mapNoise(k, 3, seed)), paint)
            }
            canvas.restore()
            // Pigment pools at the edge as the wash dries.
            paint.pen(pigment, 4.5f, 55); canvas.drawPath(p, paint)
            if (outline) {
                paint.pen(strokeColor ?: ColorUtils.blendARGB(pigment, Color.BLACK, .2f), 1.1f + extra,
                    if (selected || strokeColor != null) 230 else 120)
                canvas.drawPath(p, paint)
            }
        }
        MapBoxFinish.BRUSH_PEN -> {
            val p = wobblyPath(rect, seed, .8f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            if (outline) {
                paint.pen(ink, 1.8f + extra, 235); canvas.drawPath(p, paint)
                // More pressure on the down-strokes: the lower and right edges thicken.
                canvas.save()
                canvas.clipRect(rect.left + rect.width() * .35f, rect.top + rect.height() * .4f,
                    rect.right + 8f, rect.bottom + 8f)
                canvas.translate(1.4f, 1.6f)
                paint.strokeWidth = 3.2f + extra; paint.alpha = 225
                canvas.drawPath(p, paint)
                canvas.restore()
                paint.strokeWidth = 1f
                canvas.drawLine(rect.left + radius - 5f, rect.top + 2f, rect.left + radius + 4f, rect.top, paint)
            }
        }
        MapBoxFinish.FELT_TIP -> {
            val p = wobblyPath(rect, seed, .6f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            if (outline) {
                paint.pen(ink, 2.6f + extra, 230); canvas.drawPath(p, paint)
                // The pen lifted late: a short overlap and a small blot where the line began.
                canvas.drawLine(rect.left + radius - 2f, rect.top - 2.2f, rect.left + radius + 12f, rect.top + .6f, paint)
                paint.solid(ink, 200)
                canvas.drawCircle(rect.left + radius, rect.top, 2f, paint)
            }
        }
        MapBoxFinish.SCRIBBLE -> {
            val p = wobblyPath(rect, seed, 1f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            canvas.save(); canvas.clipPath(p)
            paint.pen(pigment, 1.4f, 58)
            scratchPath.reset()
            var x = rect.left + 4f
            var up = true
            var i = 0
            scratchPath.moveTo(x, rect.top + 6f)
            while (x < rect.right) {
                x += 5f + mapNoise(i, 7, seed) * 3f
                val y = if (up) rect.bottom - 5f - mapNoise(i, 8, seed) * 6f else rect.top + 5f + mapNoise(i, 9, seed) * 6f
                scratchPath.lineTo(x, y)
                up = !up; i++
            }
            canvas.drawPath(scratchPath, paint)
            canvas.restore()
            if (outline) { paint.pen(ink, 2f + extra, 220); canvas.drawPath(p, paint) }
        }
        MapBoxFinish.CROSS_HATCH -> {
            val p = wobblyPath(rect, seed, .8f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            canvas.save(); canvas.clipPath(p)
            paint.pen(ink, .9f, 38)
            val h = rect.height()
            var x = rect.left - h
            while (x < rect.right) {
                canvas.drawLine(x, rect.bottom, x + h, rect.top, paint)
                canvas.drawLine(x, rect.top, x + h, rect.bottom, paint)
                x += 8f
            }
            canvas.restore()
            if (outline) { paint.pen(ink, 2f + extra, 220); canvas.drawPath(p, paint) }
        }
        MapBoxFinish.STIPPLE -> {
            val p = wobblyPath(rect, seed, .6f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            canvas.save(); canvas.clipPath(p)
            paint.solid(ink, 80)
            var gy = 0
            var y = rect.top + 2f
            while (y < rect.bottom) {
                val t = (y - rect.top) / rect.height()
                var gx = 0
                var x = rect.left + 2f + (gy % 2) * 2.5f
                while (x < rect.right) {
                    if (mapNoise(gx, gy, seed) < .08f + t * t * .7f) canvas.drawCircle(x, y, .9f, paint)
                    x += 5f; gx++
                }
                y += 4.5f; gy++
            }
            canvas.restore()
            if (outline) { paint.pen(ink, 1.6f + extra, 200); canvas.drawPath(p, paint) }
        }
        MapBoxFinish.ROUGH_SKETCH -> {
            val p = wobblyPath(rect, seed, 1.4f, 3f, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            if (outline) {
                // Three loose passes, each line running a little past its corner.
                val o = 5f
                for (pass in 0..2) {
                    fun j(k: Int) = (mapNoise(pass, k, seed) - .5f) * 4f
                    paint.pen(ink, (if (pass == 0) 1.7f + extra else if (pass == 1) 1.2f else 1f),
                        if (pass == 0) 190 else if (pass == 1) 120 else 80)
                    canvas.drawLine(rect.left - o + j(0), rect.top + j(1), rect.right + o + j(2), rect.top + j(3), paint)
                    canvas.drawLine(rect.right + j(4), rect.top - o + j(5), rect.right + j(6), rect.bottom + o + j(7), paint)
                    canvas.drawLine(rect.right + o + j(8), rect.bottom + j(9), rect.left - o + j(10), rect.bottom + j(11), paint)
                    canvas.drawLine(rect.left + j(12), rect.bottom + o + j(13), rect.left + j(14), rect.top - o + j(15), paint)
                }
            }
        }
        MapBoxFinish.HIGHLIGHTER -> {
            val p = wobblyPath(rect, seed, .5f, radius, edgePath)
            paint.solid(ColorUtils.blendARGB(fill, Color.WHITE, .5f)); canvas.drawPath(p, paint)
            canvas.save(); canvas.clipPath(p)
            val top = rect.top + rect.height() * .22f
            val bottom = rect.bottom - rect.height() * .2f
            paint.solid(pigment, 85)
            scratchPath.reset()
            scratchPath.moveTo(rect.left + 5f, top + 2f)
            scratchPath.lineTo(rect.right - 3f, top - 1f)
            scratchPath.lineTo(rect.right - 6f, bottom - 1f)
            scratchPath.lineTo(rect.left + 2f, bottom + 1.5f)
            scratchPath.close()
            canvas.drawPath(scratchPath, paint)
            // Where the highlighter went over twice.
            paint.alpha = 40
            canvas.drawRect(rect.left + 2f, top + 1f, rect.left + rect.width() * .3f, bottom, paint)
            canvas.restore()
            if (outline) { paint.pen(ink, 1.2f + extra, if (selected) 230 else 150); canvas.drawPath(p, paint) }
        }
        MapBoxFinish.DRY_BRUSH -> {
            val p = wobblyPath(rect, seed, 1f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            if (outline) {
                // Separate bristles leave parallel streaks with their own gaps.
                for (k in -2..2) {
                    paint.pen(ink, 1f, 170 - abs(k) * 30)
                    paint.pathEffect = BRISTLES[k + 2]
                    canvas.save(); canvas.translate(k * .9f, -k * .7f); canvas.drawPath(p, paint); canvas.restore()
                }
                if (selected) { paint.pen(ink, 2.4f, 230); canvas.drawPath(p, paint) }
            }
        }
        MapBoxFinish.STITCHED -> {
            val p = wobblyPath(rect, seed, .5f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            canvas.save(); canvas.clipPath(p)
            paint.pen(pigment, .8f, 18)
            var y = rect.top + 2f
            while (y < rect.bottom) { canvas.drawLine(rect.left, y, rect.right, y, paint); y += 4f }
            var x = rect.left + 2f
            while (x < rect.right) { canvas.drawLine(x, rect.top, x, rect.bottom, paint); x += 4f }
            canvas.restore()
            if (outline) { paint.pen(ink, 1.4f + extra, if (selected) 230 else 110); canvas.drawPath(p, paint) }
            if (rect.width() > 24f && rect.height() > 18f) {
                // A running stitch, inset from the edge like a sewn label.
                paint.pen(ColorUtils.blendARGB(pigment, Color.BLACK, .35f), 1.6f, 230)
                paint.pathEffect = STITCH_DASH
                scratchRect.set(rect.left + 5f, rect.top + 5f, rect.right - 5f, rect.bottom - 5f)
                canvas.drawRoundRect(scratchRect, (radius - 4f).coerceAtLeast(2f), (radius - 4f).coerceAtLeast(2f), paint)
            }
        }
        MapBoxFinish.TAPED_NOTE -> {
            paint.solid(Color.BLACK, 28)
            canvas.drawRect(rect.left + 2f, rect.top + 3f, rect.right + 2f, rect.bottom + 3f, paint)
            val p = wobblyPath(rect, seed, .4f, 2f, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            if (outline) { paint.pen(ink, 1f + extra, if (selected) 220 else 60); canvas.drawPath(p, paint) }
            paint.solid(Color.rgb(236, 226, 196), 185)
            drawTape(canvas, paint, rect.left + 10f, rect.top + 1f, -14f)
            drawTape(canvas, paint, rect.right - 10f, rect.top + 1f, 14f)
        }
        MapBoxFinish.STICKY_NOTE -> {
            val fold = min(14f, rect.height() * .32f)
            paint.solid(Color.BLACK, 26)
            canvas.drawRect(rect.left + 1.5f, rect.top + 3f, rect.right + 1.5f, rect.bottom + 2.5f, paint)
            scratchPath.reset()
            scratchPath.moveTo(rect.left, rect.top)
            scratchPath.lineTo(rect.right, rect.top)
            scratchPath.lineTo(rect.right, rect.bottom - fold)
            scratchPath.lineTo(rect.right - fold, rect.bottom)
            scratchPath.lineTo(rect.left, rect.bottom)
            scratchPath.close()
            paint.solid(fill); canvas.drawPath(scratchPath, paint)
            // The adhesive strip along the top reads slightly deeper.
            paint.solid(pigment, 22)
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * .18f, paint)
            if (outline && (selected || strokeColor != null)) {
                paint.pen(strokeColor ?: ink, 1.2f + extra, 220); canvas.drawPath(scratchPath, paint)
            }
            scratchPath.reset()
            scratchPath.moveTo(rect.right, rect.bottom - fold)
            scratchPath.lineTo(rect.right - fold, rect.bottom)
            scratchPath.lineTo(rect.right - fold, rect.bottom - fold)
            scratchPath.close()
            paint.solid(ColorUtils.blendARGB(fill, Color.BLACK, .12f)); canvas.drawPath(scratchPath, paint)
            paint.pen(Color.BLACK, .8f, 40); canvas.drawPath(scratchPath, paint)
        }
        MapBoxFinish.TORN_PAPER -> {
            val torn = scratchPath
            torn.reset()
            torn.moveTo(rect.left, rect.top + 2f)
            torn.quadTo(rect.left, rect.top, rect.left + 2f, rect.top)
            torn.lineTo(rect.right - 2f, rect.top)
            torn.quadTo(rect.right, rect.top, rect.right, rect.top + 2f)
            var i = 0
            var y = rect.top + 4f
            while (y < rect.bottom) { torn.lineTo(rect.right - mapNoise(i++, 11, seed) * 2f, y); y += 4f }
            var x = rect.right
            while (x > rect.left) { torn.lineTo(x, rect.bottom - mapNoise(i++, 12, seed) * 4.5f); x -= 4f }
            torn.lineTo(rect.left, rect.bottom - 2f)
            torn.close()
            paint.solid(Color.BLACK, 26)
            canvas.save(); canvas.translate(1.5f, 2f); canvas.drawPath(torn, paint); canvas.restore()
            paint.solid(fill); canvas.drawPath(torn, paint)
            // The paper's white core shows along the tear.
            canvas.save()
            canvas.clipRect(rect.left, rect.bottom - 7f, rect.right + 3f, rect.bottom + 3f)
            canvas.translate(0f, 1.2f)
            paint.pen(Color.WHITE, 1.6f, 210); canvas.drawPath(torn, paint)
            canvas.restore()
            if (outline) { paint.pen(ink, .9f + extra, if (selected) 220 else 55); canvas.drawPath(torn, paint) }
        }
        MapBoxFinish.PAPER_CUTOUT -> {
            // A second sheet of coloured paper peeks out behind the cut-out.
            val back = wobblyPath(rect, seed + 7, 1.4f, radius, backPath)
            paint.solid(ColorUtils.blendARGB(pigment, Color.BLACK, .1f))
            canvas.save(); canvas.translate(2.6f, 2.8f); canvas.drawPath(back, paint); canvas.restore()
            val p = wobblyPath(rect, seed, 1.2f, radius, edgePath)
            paint.solid(fill); canvas.drawPath(p, paint)
            canvas.save()
            canvas.clipRect(rect.left - 3f, rect.top - 3f, rect.right, rect.top + rect.height() * .5f)
            canvas.translate(.6f, .6f)
            paint.pen(Color.WHITE, 1.4f, 170); canvas.drawPath(p, paint)
            canvas.restore()
            if (outline) { paint.pen(ink, .9f + extra, if (selected) 220 else 45); canvas.drawPath(p, paint) }
        }
        else -> Unit
    }
    paint.shader = null
    paint.pathEffect = null
    paint.alpha = 255
    paint.style = Paint.Style.FILL
    paint.strokeCap = cap
    paint.strokeJoin = join
    paint.strokeWidth = width
}

/**
 * Material and stationery textures, laid over the palette fill that is already drawn. The caller
 * clips to the box; washes stay translucent so the palette colour and the title remain readable.
 */
internal fun drawMaterialTexture(canvas: Canvas, r: RectF, paint: Paint, finish: MapBoxFinish, tint: Int) {
    val seed = seedOf(r)
    val deep = ColorUtils.blendARGB(tint, Color.BLACK, .35f)
    val width = paint.strokeWidth
    val cap = paint.strokeCap
    val join = paint.strokeJoin
    when (finish) {
        MapBoxFinish.KRAFT -> {
            paint.solid(Color.rgb(196, 160, 112), 72); canvas.drawRect(r, paint)
            paint.pen(Color.rgb(120, 88, 48), 1f, 55)
            var gy = 0
            var y = r.top + 2f
            while (y < r.bottom) {
                var gx = 0
                var x = r.left + 2f
                while (x < r.right) {
                    val angle = mapNoise(gx, gy, seed) * 6.28f
                    val length = 2f + mapNoise(gy, gx, seed) * 4f
                    val px = x + mapNoise(gx, gy, seed + 1) * 6f
                    val py = y + mapNoise(gx, gy, seed + 2) * 6f
                    canvas.drawLine(px, py, px + cos(angle) * length, py + sin(angle) * length, paint)
                    x += 9f; gx++
                }
                y += 8f; gy++
            }
        }
        MapBoxFinish.CANVAS -> {
            paint.solid(Color.WHITE, 26); canvas.drawRect(r, paint)
            paint.pen(deep, .8f, 24)
            var y = r.top + 1.5f
            while (y < r.bottom) { canvas.drawLine(r.left, y, r.right, y, paint); y += 3f }
            paint.alpha = 16
            var x = r.left + 1.5f
            while (x < r.right) { canvas.drawLine(x, r.top, x, r.bottom, paint); x += 3f }
        }
        MapBoxFinish.CORK -> {
            paint.solid(Color.rgb(201, 156, 100), 84); canvas.drawRect(r, paint)
            var gy = 0
            var y = r.top + 2f
            while (y < r.bottom) {
                var gx = 0
                var x = r.left + 2f
                while (x < r.right) {
                    val n = mapNoise(gx, gy, seed)
                    if (n < .5f) {
                        paint.solid(if (n < .25f) Color.rgb(130, 88, 46) else Color.rgb(238, 210, 168),
                            if (n < .25f) 100 else 140)
                        canvas.drawCircle(x + n * 3f, y, .5f + mapNoise(gy, gx, seed) * 1.1f, paint)
                    }
                    x += 4.5f; gx++
                }
                y += 4.5f; gy++
            }
        }
        MapBoxFinish.WOOD -> {
            paint.solid(Color.rgb(214, 177, 122), 58); canvas.drawRect(r, paint)
            paint.pen(Color.rgb(140, 96, 54), .9f, 60)
            var y = r.top + 2f
            var k = 0
            while (y < r.bottom + 4f) {
                val phase = mapNoise(k, 3, seed) * 6.28f
                val amp = 1f + mapNoise(k, 4, seed) * 2f
                scratchPath.reset()
                scratchPath.moveTo(r.left, y + sin(r.left * .045f + phase) * amp)
                var x = r.left
                while (x < r.right) { x += 6f; scratchPath.lineTo(x, y + sin(x * .045f + phase) * amp) }
                canvas.drawPath(scratchPath, paint)
                y += 3.5f + mapNoise(k, 5, seed) * 2.5f; k++
            }
            val kx = r.left + r.width() * (.55f + .35f * mapNoise(1, 6, seed))
            val ky = r.top + r.height() * (.3f + .4f * mapNoise(2, 6, seed))
            paint.alpha = 70
            scratchRect.set(kx - 6f, ky - 2.5f, kx + 6f, ky + 2.5f); canvas.drawOval(scratchRect, paint)
            scratchRect.inset(2.5f, 1f); canvas.drawOval(scratchRect, paint)
        }
        MapBoxFinish.MARBLE -> {
            paint.solid(Color.WHITE, 115); canvas.drawRect(r, paint)
            for (v in 0..2) {
                scratchPath.reset()
                var x = r.left - 4f
                var y = r.top + r.height() * mapNoise(v, 1, seed)
                scratchPath.moveTo(x, y)
                var i = 0
                while (x < r.right + 4f) {
                    x += 10f
                    y = (y + (mapNoise(v, i++, seed + 3) - .5f) * 9f).coerceIn(r.top - 4f, r.bottom + 4f)
                    scratchPath.lineTo(x, y)
                }
                if (v == 0) paint.pen(deep, 1.2f, 60) else paint.pen(Color.rgb(140, 140, 146), .8f, 75)
                canvas.drawPath(scratchPath, paint)
            }
        }
        MapBoxFinish.CONCRETE -> {
            paint.solid(Color.rgb(192, 192, 188), 58); canvas.drawRect(r, paint)
            var gy = 0
            var y = r.top + 1.5f
            while (y < r.bottom) {
                var gx = 0
                var x = r.left + 1.5f
                while (x < r.right) {
                    val n = mapNoise(gx, gy, seed)
                    if (n < .3f) { paint.solid(Color.rgb(90, 90, 88), 70); canvas.drawCircle(x, y, .55f, paint) }
                    else if (n < .38f) { paint.solid(Color.WHITE, 90); canvas.drawCircle(x, y, .6f, paint) }
                    x += 3.5f; gx++
                }
                y += 3.5f; gy++
            }
            paint.solid(Color.rgb(80, 80, 78), 45)
            for (k in 0 until 5) {
                canvas.drawCircle(r.left + r.width() * mapNoise(k, 8, seed), r.top + r.height() * mapNoise(k, 9, seed),
                    1.2f + mapNoise(k, 10, seed), paint)
            }
        }
        MapBoxFinish.BRUSHED_METAL -> {
            paint.solid(Color.WHITE)
            paint.shader = LinearGradient(0f, r.top, 0f, r.bottom,
                intArrayOf(Color.argb(95, 238, 241, 244), Color.argb(80, 168, 176, 186), Color.argb(70, 236, 239, 242)),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(r, paint)
            var y = r.top + 1f
            var row = 0
            while (y < r.bottom) {
                paint.pen(if (row % 2 == 0) Color.WHITE else Color.rgb(120, 128, 136), .7f, 40)
                canvas.drawLine(r.left + mapNoise(row, 1, seed) * 30f, y, r.right - mapNoise(row, 2, seed) * 30f, y, paint)
                y += 1.8f; row++
            }
        }
        MapBoxFinish.SOFT_CLAY -> {
            paint.solid(Color.WHITE)
            paint.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(34, 0, 0, 0)),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(r, paint)
            paint.pen(Color.WHITE, 1.4f, 150)
            canvas.save()
            canvas.clipRect(r.left, r.top, r.right, r.centerY())
            scratchRect.set(r); scratchRect.inset(1.5f, 1.5f)
            canvas.drawRoundRect(scratchRect, 12f, 12f, paint)
            canvas.restore()
        }
        MapBoxFinish.TERRAZZO -> {
            val chips = intArrayOf(deep, tint, Color.rgb(70, 72, 74), Color.rgb(226, 182, 140), Color.WHITE)
            var gy = 0
            var y = r.top + 3f
            while (y < r.bottom) {
                var gx = 0
                var x = r.left + 3f
                while (x < r.right) {
                    if (mapNoise(gx, gy, seed) < .7f) {
                        val cx = x + mapNoise(gx, gy, seed + 1) * 7f
                        val cy = y + mapNoise(gx, gy, seed + 2) * 6f
                        val size = 1.4f + mapNoise(gy, gx, seed) * 2.4f
                        val turn = mapNoise(gx, gy, seed + 3) * 6.28f
                        scratchPath.reset()
                        for (v in 0 until 4) {
                            val a = turn + v * 1.57f + (mapNoise(v, gx, seed + gy) - .5f) * .8f
                            val d = size * (.6f + .4f * mapNoise(gx, v, seed + gy))
                            if (v == 0) scratchPath.moveTo(cx + cos(a) * d, cy + sin(a) * d)
                            else scratchPath.lineTo(cx + cos(a) * d, cy + sin(a) * d)
                        }
                        scratchPath.close()
                        paint.solid(chips[(gx * 3 + gy * 7 + seed).mod(chips.size)], 170)
                        canvas.drawPath(scratchPath, paint)
                    }
                    x += 11f; gx++
                }
                y += 9f; gy++
            }
        }
        MapBoxFinish.DOT_GRID -> {
            paint.solid(deep, 85)
            var y = r.top + 4f
            while (y < r.bottom) {
                var x = r.left + 4f
                while (x < r.right) { canvas.drawCircle(x, y, 1.1f, paint); x += 8f }
                y += 8f
            }
        }
        MapBoxFinish.GRAPH_PAPER -> {
            var k = 0
            var y = r.top + 3f
            while (y < r.bottom) { paint.pen(deep, .8f, if (k++ % 4 == 0) 52 else 24); canvas.drawLine(r.left, y, r.right, y, paint); y += 6f }
            k = 0
            var x = r.left + 3f
            while (x < r.right) { paint.pen(deep, .8f, if (k++ % 4 == 0) 52 else 24); canvas.drawLine(x, r.top, x, r.bottom, paint); x += 6f }
        }
        MapBoxFinish.NOTEBOOK -> {
            paint.solid(Color.WHITE, 55); canvas.drawRect(r, paint)
            paint.pen(Color.rgb(105, 148, 212), .9f, 95)
            var y = r.top + 9f
            while (y < r.bottom) { canvas.drawLine(r.left, y, r.right, y, paint); y += 9f }
            paint.pen(Color.rgb(222, 104, 104), 1f, 150)
            canvas.drawLine(r.left + 10f, r.top, r.left + 10f, r.bottom, paint)
        }
        MapBoxFinish.HALFTONE -> {
            paint.solid(deep, 70)
            var row = 0
            var y = r.top + 3f
            while (y < r.bottom) {
                var x = r.left + 3f + (row % 2) * 3f
                while (x < r.right) {
                    val t = (x - r.left) / r.width() * .6f + (y - r.top) / r.height() * .4f
                    canvas.drawCircle(x, y, .3f + 2.1f * t, paint)
                    x += 6f
                }
                y += 6f; row++
            }
        }
        else -> Unit
    }
    paint.shader = null
    paint.pathEffect = null
    paint.style = Paint.Style.FILL
    paint.alpha = 255
    paint.strokeWidth = width
    paint.strokeCap = cap
    paint.strokeJoin = join
}
