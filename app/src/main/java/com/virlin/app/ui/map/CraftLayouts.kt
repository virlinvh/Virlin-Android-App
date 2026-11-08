package com.virlin.app.ui.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val CRAFT_LAYOUTS = setOf(
    MapLayout.SKETCH_TREE, MapLayout.WHITEBOARD, MapLayout.DOODLE_BUBBLES, MapLayout.STICKY_WALL,
    MapLayout.BRUSH_INK, MapLayout.DRAFTING, MapLayout.PAPER_CRAFT, MapLayout.METRO_LINES,
)

/** Layouts whose connectors, box edges and entry marks are painted here rather than in paintMap. */
internal fun MapLayout.isCraft() = this in CRAFT_LAYOUTS

private val GRAPHITE = Color.rgb(64, 64, 68)

private fun layoutSeed(r: RectF) = r.left.toInt() * 29 + r.top.toInt() * 13

/** Sticky notes are a little taller and squarer; doodle bubbles need room for their curves. */
internal fun craftCardHeight(layout: MapLayout, height: Float) = when (layout) {
    MapLayout.STICKY_WALL -> max(height, 86f)
    MapLayout.DOODLE_BUBBLES -> height + 10f
    else -> height
}

internal fun craftNodeRadius(layout: MapLayout, height: Float) = when (layout) {
    MapLayout.SKETCH_TREE -> 12f
    MapLayout.WHITEBOARD -> 6f
    MapLayout.DOODLE_BUBBLES, MapLayout.METRO_LINES -> min(26f, height / 2f)
    MapLayout.STICKY_WALL -> 2f
    MapLayout.BRUSH_INK -> 10f
    MapLayout.PAPER_CRAFT -> 4f
    else -> 0f
}

private fun stickyFold(r: RectF) = min(14f, r.height() * .22f)

/** The box outline. Seeded by the frame, so fill and edge trace the same hand-drawn line. */
internal fun craftNodePath(r: RectF, layout: MapLayout, radius: Float): Path? {
    val seed = layoutSeed(r)
    return when (layout) {
        MapLayout.SKETCH_TREE -> wobblyPath(r, seed, 1.2f, radius, Path())
        MapLayout.WHITEBOARD -> wobblyPath(r, seed, .8f, radius, Path())
        MapLayout.DOODLE_BUBBLES -> wobblyPath(r, seed, 1.6f, radius, Path())
        MapLayout.BRUSH_INK -> wobblyPath(r, seed, 1.4f, radius, Path())
        MapLayout.PAPER_CRAFT -> wobblyPath(r, seed, 1f, radius, Path())
        MapLayout.STICKY_WALL -> Path().apply {
            val fold = stickyFold(r)
            moveTo(r.left, r.top)
            lineTo(r.right, r.top)
            lineTo(r.right, r.bottom - fold)
            lineTo(r.right - fold, r.bottom)
            lineTo(r.left, r.bottom)
            close()
        }
        else -> null
    }
}

private fun clipNode(canvas: Canvas, r: RectF, layout: MapLayout, radius: Float) {
    canvas.clipPath(craftNodePath(r, layout, radius)
        ?: Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
}

private fun Paint.ink(color: Int, width: Float, alpha: Int) {
    shader = null; pathEffect = null; style = Paint.Style.STROKE
    this.color = color; this.alpha = alpha.coerceIn(0, 255); strokeWidth = width
    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
}

private fun Paint.flat(color: Int, alpha: Int) {
    shader = null; pathEffect = null; style = Paint.Style.FILL
    this.color = color; this.alpha = alpha.coerceIn(0, 255)
}

/** A ruled line traced by hand: small sideways wobbles, ends exactly on its points. */
private fun Path.sketchTo(x0: Float, y0: Float, x1: Float, y1: Float, seed: Int, amp: Float) {
    val dx = x1 - x0
    val dy = y1 - y0
    val length = max(hypot(dx, dy), 1f)
    val steps = max(1, (length / 14f).toInt())
    val nx = -dy / length
    val ny = dx / length
    for (i in 1..steps) {
        val t = i.toFloat() / steps
        val j = if (i == steps) 0f else (mapNoise(i, seed, 41) - .5f) * 2f * amp
        lineTo(x0 + dx * t + nx * j, y0 + dy * t + ny * j)
    }
}

private fun bezier(t: Float, a: Float, b: Float, c: Float, d: Float): Float {
    val u = 1f - t
    return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c + t * t * t * d
}

/** Drawn under the box fill: shadows and the second sheet of paper. */
internal fun drawCraftUnderlay(canvas: Canvas, r: RectF, layout: MapLayout, radius: Float, paint: Paint, tint: Int) {
    when (layout) {
        MapLayout.STICKY_WALL -> {
            paint.flat(Color.BLACK, 32)
            canvas.save(); canvas.translate(2f, 3.5f)
            canvas.drawPath(craftNodePath(r, layout, radius)!!, paint)
            canvas.restore()
        }
        MapLayout.PAPER_CRAFT -> {
            paint.flat(ColorUtils.blendARGB(tint, Color.BLACK, .12f), 255)
            canvas.save(); canvas.translate(3.5f, 3.5f)
            canvas.drawPath(wobblyPath(r, layoutSeed(r) + 9, 1.1f, radius, Path()), paint)
            canvas.restore()
        }
        else -> Unit
    }
    paint.flat(Color.BLACK, 255)
}

/** The layout's own surface detail, only when the map keeps the default Smooth finish. */
internal fun drawCraftFill(canvas: Canvas, r: RectF, layout: MapLayout, radius: Float, paint: Paint, tint: Int) {
    canvas.save()
    clipNode(canvas, r, layout, radius)
    when (layout) {
        MapLayout.SKETCH_TREE -> {
            // Pencil shading tucked into the lower right, as if the box were shaded by hand.
            canvas.clipRect(r.left + r.width() * .64f, r.top + r.height() * .35f, r.right, r.bottom)
            paint.ink(GRAPHITE, .9f, 42)
            var x = r.right - r.height() * 1.4f
            while (x < r.right) { canvas.drawLine(x, r.bottom, x + r.height(), r.top, paint); x += 4.5f }
        }
        MapLayout.WHITEBOARD -> {
            paint.flat(Color.WHITE, 140); canvas.drawRect(r, paint)
            // A ghost of something wiped off the board.
            paint.ink(tint, 9f, 22)
            canvas.drawLine(r.left + 10f, r.top + 10f, r.left + r.width() * .45f, r.top + 6f, paint)
        }
        MapLayout.DOODLE_BUBBLES -> {
            paint.ink(Color.WHITE, 2.4f, 170)
            val shine = min(28f, r.width() * .3f)
            canvas.drawArc(RectF(r.left + 6f, r.top + 5f, r.left + 6f + shine, r.top + 5f + r.height() * .5f),
                190f, 60f, false, paint)
            paint.flat(tint, 70)
            canvas.drawCircle(r.left + 14f, r.bottom - 9f, 2.6f, paint)
            canvas.drawCircle(r.left + 21f, r.bottom - 6f, 1.6f, paint)
        }
        MapLayout.STICKY_WALL -> {
            paint.flat(tint, 34)
            canvas.drawRect(r.left, r.top, r.right, r.top + r.height() * .16f, paint)
        }
        MapLayout.BRUSH_INK -> {
            val seed = layoutSeed(r)
            paint.flat(tint, 32)
            for (k in 0 until 3) {
                canvas.drawCircle(r.left + r.width() * (k + mapNoise(k, 1, seed)) / 3f,
                    r.top + r.height() * (.35f + .4f * mapNoise(k, 2, seed)),
                    r.height() * (.4f + .25f * mapNoise(k, 3, seed)), paint)
            }
        }
        MapLayout.DRAFTING -> {
            paint.ink(ColorUtils.blendARGB(tint, Color.BLACK, .4f), .7f, 22)
            var x = r.left + 8f
            while (x < r.right) { canvas.drawLine(x, r.top, x, r.bottom, paint); x += 8f }
            var y = r.top + 8f
            while (y < r.bottom) { canvas.drawLine(r.left, y, r.right, y, paint); y += 8f }
        }
        MapLayout.METRO_LINES -> { paint.flat(Color.WHITE, 110); canvas.drawRect(r, paint) }
        else -> Unit
    }
    canvas.restore()
    paint.flat(Color.BLACK, 255)
}

/**
 * The box edge and its fixed details. [stroke] null means the map hides edges; a selected box
 * always passes one, so selection stays visible.
 */
internal fun drawCraftOutline(
    canvas: Canvas, r: RectF, layout: MapLayout, radius: Float, paint: Paint,
    stroke: Int?, strokeAlpha: Int, selected: Boolean,
) {
    val cap = paint.strokeCap
    val join = paint.strokeJoin
    val seed = layoutSeed(r)
    when (layout) {
        MapLayout.STICKY_WALL -> {
            val fold = stickyFold(r)
            val flap = Path().apply {
                moveTo(r.right, r.bottom - fold); lineTo(r.right - fold, r.bottom)
                lineTo(r.right - fold, r.bottom - fold); close()
            }
            paint.flat(Color.BLACK, 30); canvas.drawPath(flap, paint)
            paint.flat(Color.WHITE, 90); canvas.drawPath(flap, paint)
            paint.ink(Color.BLACK, .8f, 45); canvas.drawPath(flap, paint)
        }
        MapLayout.PAPER_CRAFT -> {
            canvas.save()
            canvas.clipRect(r.left - 3f, r.top - 3f, r.right, r.top + r.height() * .5f)
            canvas.translate(.7f, .7f)
            paint.ink(Color.WHITE, 1.3f, 160); canvas.drawPath(craftNodePath(r, layout, radius)!!, paint)
            canvas.restore()
        }
        else -> Unit
    }
    if (stroke != null) {
        val alpha = if (strokeAlpha <= 60) strokeAlpha else max(strokeAlpha, 200)
        val extra = if (selected) 1.2f else 0f
        val path = craftNodePath(r, layout, radius)
            ?: Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) }
        when (layout) {
            MapLayout.SKETCH_TREE -> {
                val ink = ColorUtils.blendARGB(stroke, Color.BLACK, .55f)
                paint.ink(ink, 1.8f + extra, alpha); canvas.drawPath(path, paint)
                paint.ink(ink, 1f, alpha / 2)
                canvas.save(); canvas.translate(1.2f, -.9f)
                canvas.drawPath(wobblyPath(r, seed + 5, 1.6f, radius, Path()), paint)
                canvas.restore()
            }
            MapLayout.WHITEBOARD -> {
                paint.ink(stroke, 3f + extra, alpha); canvas.drawPath(path, paint)
                canvas.drawLine(r.left + radius - 2f, r.top - 2f, r.left + radius + 12f, r.top + .5f, paint)
            }
            MapLayout.DOODLE_BUBBLES -> {
                val ink = ColorUtils.blendARGB(stroke, Color.BLACK, .45f)
                paint.ink(ink, 1.8f + extra, alpha); canvas.drawPath(path, paint)
                paint.ink(ink, 1f, alpha * 2 / 5)
                canvas.drawPath(wobblyPath(r, seed + 3, 2f, radius, Path()), paint)
            }
            MapLayout.STICKY_WALL -> {
                if (selected) paint.ink(stroke, 2f, 230) else paint.ink(Color.BLACK, .8f, min(alpha, 50))
                canvas.drawPath(path, paint)
            }
            MapLayout.BRUSH_INK -> {
                val ink = ColorUtils.blendARGB(stroke, Color.BLACK, .6f)
                paint.ink(ink, 1.6f + extra, alpha); canvas.drawPath(path, paint)
                // More pressure on the down-strokes: the lower and right edges thicken.
                canvas.save()
                canvas.clipRect(r.left + r.width() * .35f, r.top + r.height() * .4f, r.right + 8f, r.bottom + 8f)
                canvas.translate(1.3f, 1.5f)
                paint.strokeWidth = 3f + extra
                canvas.drawPath(path, paint)
                canvas.restore()
            }
            MapLayout.DRAFTING -> {
                val ink = ColorUtils.blendARGB(stroke, Color.BLACK, .5f)
                paint.ink(ink, 1.2f + extra, alpha); canvas.drawRect(r, paint)
                // Construction lines run past every corner, as a draughtsman's ruler leaves them.
                paint.ink(ink, .8f, alpha / 2)
                val o = 6f
                canvas.drawLine(r.left - o, r.top, r.left, r.top, paint)
                canvas.drawLine(r.left, r.top - o, r.left, r.top, paint)
                canvas.drawLine(r.right, r.top, r.right + o, r.top, paint)
                canvas.drawLine(r.right, r.top - o, r.right, r.top, paint)
                canvas.drawLine(r.left - o, r.bottom, r.left, r.bottom, paint)
                canvas.drawLine(r.left, r.bottom, r.left, r.bottom + o, paint)
                canvas.drawLine(r.right, r.bottom, r.right + o, r.bottom, paint)
                canvas.drawLine(r.right, r.bottom, r.right, r.bottom + o, paint)
            }
            MapLayout.PAPER_CRAFT -> {
                if (selected) paint.ink(stroke, 2f, 230)
                else paint.ink(ColorUtils.blendARGB(stroke, Color.BLACK, .3f), .8f, min(alpha, 45))
                canvas.drawPath(path, paint)
            }
            MapLayout.METRO_LINES -> { paint.ink(stroke, 3.2f + extra, alpha); canvas.drawPath(path, paint) }
            else -> Unit
        }
    }
    paint.flat(Color.BLACK, 255)
    paint.strokeCap = cap
    paint.strokeJoin = join
}

/** One parent-to-child connection, from the parent's right edge to the child's entry. */
internal fun drawCraftConnector(
    canvas: Canvas, paint: Paint, layout: MapLayout, parent: Frame, child: Frame, color: Int, alpha: Int,
) {
    val cap = paint.strokeCap
    val join = paint.strokeJoin
    val fromX = parent.x + parent.w
    val fromY = parent.y + parent.h / 2f
    val toX = child.x
    val toY = child.y + child.h / 2f
    val gap = toX - fromX
    val seed = child.x.toInt() * 7 + child.y.toInt() * 3
    when (layout) {
        MapLayout.SKETCH_TREE -> {
            val spineX = fromX + 18f
            val graphite = ColorUtils.blendARGB(color, GRAPHITE, .45f)
            for (pass in 0..1) {
                val path = Path().apply {
                    moveTo(fromX, fromY)
                    sketchTo(fromX, fromY, spineX, fromY, seed + pass, 1f)
                    sketchTo(spineX, fromY, spineX, toY, seed + pass + 7, 1f)
                    sketchTo(spineX, toY, toX, toY, seed + pass + 13, 1f)
                }
                paint.ink(graphite, if (pass == 0) 1.6f else 1f, if (pass == 0) alpha else alpha * 9 / 20)
                canvas.save(); if (pass == 1) canvas.translate(.8f, -.6f)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        }
        MapLayout.WHITEBOARD -> {
            paint.ink(color, 3.2f, alpha)
            canvas.drawPath(Path().apply {
                moveTo(fromX, fromY)
                cubicTo(fromX + gap * .45f, fromY + (toY - fromY) * .1f, toX - gap * .35f, toY, toX - 3f, toY)
            }, paint)
        }
        MapLayout.DOODLE_BUBBLES -> {
            paint.ink(color, 2f, alpha)
            val path = Path()
            path.moveTo(fromX, fromY)
            val steps = 16
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val j = if (i == steps) 0f else (mapNoise(i, seed, 5) - .5f) * 2.8f
                path.lineTo(bezier(t, fromX, fromX + gap * .5f, toX - gap * .5f, toX),
                    bezier(t, fromY, fromY, toY, toY) + j)
            }
            canvas.drawPath(path, paint)
        }
        MapLayout.STICKY_WALL -> {
            // A string pinned to the next note, sagging under its own weight.
            val sx = fromX - 4f
            val ex = child.x + 14f
            val ey = child.y + 10f
            val string = Path().apply {
                moveTo(sx, fromY)
                quadTo((sx + ex) / 2f, max(fromY, ey) + 22f, ex, ey)
            }
            paint.ink(Color.BLACK, 1.3f, alpha / 5)
            canvas.save(); canvas.translate(1.5f, 2.5f); canvas.drawPath(string, paint); canvas.restore()
            paint.ink(ColorUtils.blendARGB(color, Color.BLACK, .35f), 1.3f, alpha)
            canvas.drawPath(string, paint)
        }
        MapLayout.BRUSH_INK -> {
            // A tapered stroke: the brush lands heavy at the parent and lifts as it reaches the child.
            val steps = 16
            val left = Path()
            val right = FloatArray((steps + 1) * 2)
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                val x = bezier(t, fromX, fromX + gap * .5f, toX - gap * .5f, toX)
                val y = bezier(t, fromY, fromY, toY, toY)
                val t0 = max(0f, t - .02f)
                val t1 = min(1f, t + .02f)
                val dx = bezier(t1, fromX, fromX + gap * .5f, toX - gap * .5f, toX) -
                    bezier(t0, fromX, fromX + gap * .5f, toX - gap * .5f, toX)
                val dy = bezier(t1, fromY, fromY, toY, toY) - bezier(t0, fromY, fromY, toY, toY)
                val length = max(hypot(dx, dy), .001f)
                val half = 3.4f * (1f - t) + .7f + (mapNoise(i, seed, 9) - .5f) * .6f
                val nx = -dy / length * half
                val ny = dx / length * half
                if (i == 0) left.moveTo(x + nx, y + ny) else left.lineTo(x + nx, y + ny)
                right[i * 2] = x - nx; right[i * 2 + 1] = y - ny
            }
            for (i in steps downTo 0) left.lineTo(right[i * 2], right[i * 2 + 1])
            left.close()
            paint.flat(ColorUtils.blendARGB(color, Color.BLACK, .3f), alpha)
            canvas.drawPath(left, paint)
        }
        MapLayout.DRAFTING -> {
            val midX = fromX + gap * .5f
            val ink = ColorUtils.blendARGB(color, Color.BLACK, .4f)
            paint.ink(ink, 1.1f, alpha)
            canvas.drawLine(fromX, fromY, midX, fromY, paint)
            canvas.drawLine(midX, fromY, midX, toY, paint)
            canvas.drawLine(midX, toY, toX, toY, paint)
            if (abs(toY - fromY) > 1f) {
                paint.ink(ink, .7f, alpha / 3)
                val dir = if (toY > fromY) 1f else -1f
                canvas.drawLine(midX, fromY - dir * 6f, midX, fromY, paint)
                canvas.drawLine(midX, toY, midX, toY + dir * 6f, paint)
            }
        }
        MapLayout.PAPER_CRAFT -> {
            val ribbon = Path().apply {
                moveTo(fromX, fromY)
                cubicTo(fromX + gap * .45f, fromY, toX - gap * .45f, toY, toX, toY)
            }
            paint.ink(Color.BLACK, 7f, alpha / 8)
            paint.strokeCap = Paint.Cap.BUTT
            canvas.save(); canvas.translate(0f, 2.5f); canvas.drawPath(ribbon, paint); canvas.restore()
            paint.ink(color, 7f, alpha * 3 / 5)
            paint.strokeCap = Paint.Cap.BUTT
            canvas.drawPath(ribbon, paint)
            paint.ink(Color.WHITE, .8f, 120)
            canvas.drawPath(ribbon, paint)
        }
        MapLayout.METRO_LINES -> {
            // Octilinear, like a transit map: straight runs joined by 45-degree chamfers.
            val x1 = fromX + 16f
            val dy = toY - fromY
            val dir = if (dy >= 0f) 1f else -1f
            val c = minOf(12f, abs(dy) / 2f, max(0f, (toX - x1 - 10f) / 2f))
            paint.ink(color, 5f, alpha)
            canvas.drawPath(Path().apply {
                moveTo(fromX, fromY)
                lineTo(x1, fromY)
                lineTo(x1 + c, fromY + dir * c)
                lineTo(x1 + c, toY - dir * c)
                lineTo(x1 + 2f * c, toY)
                lineTo(toX, toY)
            }, paint)
        }
        else -> Unit
    }
    paint.flat(Color.BLACK, 255)
    paint.strokeCap = cap
    paint.strokeJoin = join
}

/** The mark where a connection arrives at a child box. */
internal fun drawCraftEndpoint(canvas: Canvas, paint: Paint, layout: MapLayout, f: Frame, color: Int, alpha: Int) {
    val cap = paint.strokeCap
    val join = paint.strokeJoin
    val x = f.x
    val y = f.y + f.h / 2f
    when (layout) {
        MapLayout.SKETCH_TREE -> {
            paint.flat(ColorUtils.blendARGB(color, GRAPHITE, .45f), alpha)
            canvas.drawCircle(x, y, 3.2f, paint)
        }
        MapLayout.WHITEBOARD -> {
            paint.ink(color, 3f, alpha)
            canvas.drawLine(x - 1f, y, x - 11f, y - 6f, paint)
            canvas.drawLine(x - 1f, y, x - 11f, y + 6f, paint)
        }
        MapLayout.DOODLE_BUBBLES -> {
            val cx = x - 5f
            paint.ink(ColorUtils.blendARGB(color, Color.BLACK, .35f), 1.3f, alpha)
            canvas.drawLine(cx - 4f, y, cx + 4f, y, paint)
            canvas.drawLine(cx, y - 4f, cx, y + 4f, paint)
            canvas.drawLine(cx - 2.6f, y - 2.6f, cx + 2.6f, y + 2.6f, paint)
            canvas.drawLine(cx - 2.6f, y + 2.6f, cx + 2.6f, y - 2.6f, paint)
        }
        MapLayout.STICKY_WALL -> {
            val px = f.x + 14f
            val py = f.y + 10f
            paint.flat(Color.BLACK, alpha / 4)
            canvas.drawCircle(px + 1.5f, py + 2.5f, 4.5f, paint)
            paint.flat(ColorUtils.blendARGB(color, Color.BLACK, .15f), alpha)
            canvas.drawCircle(px, py, 4.5f, paint)
            paint.flat(Color.WHITE, alpha * 4 / 5)
            canvas.drawCircle(px - 1.4f, py - 1.4f, 1.4f, paint)
        }
        MapLayout.BRUSH_INK -> {
            paint.flat(ColorUtils.blendARGB(color, Color.BLACK, .3f), alpha)
            canvas.drawPath(Path().apply {
                moveTo(x - 10f, y - 4f); lineTo(x, y - 1f); lineTo(x, y + 1.5f); close()
            }, paint)
        }
        MapLayout.DRAFTING -> {
            paint.ink(ColorUtils.blendARGB(color, Color.BLACK, .4f), 1.8f, alpha)
            canvas.drawLine(x - 4f, y + 4f, x + 4f, y - 4f, paint)
        }
        MapLayout.PAPER_CRAFT -> {
            paint.flat(ColorUtils.blendARGB(color, Color.BLACK, .15f), alpha)
            canvas.drawPath(Path().apply {
                moveTo(x - 1f, y - 6f); lineTo(x + 7f, y); lineTo(x - 1f, y + 6f); close()
            }, paint)
        }
        MapLayout.METRO_LINES -> {
            paint.flat(Color.WHITE, alpha)
            canvas.drawCircle(x, y, 5.5f, paint)
            paint.ink(color, 2.6f, alpha)
            canvas.drawCircle(x, y, 5.5f, paint)
        }
        else -> Unit
    }
    paint.flat(Color.BLACK, 255)
    paint.strokeCap = cap
    paint.strokeJoin = join
}
