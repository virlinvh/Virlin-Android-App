package com.virlin.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.runtime.State
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Now-only, static artwork. Geometry follows the measured content, including large text. */
internal val NowFocusOutline = GenericShape { size, _ ->
    addPath(nowFocusPath(size.width, size.height))
}

/** Start point, then one (c1, c2, end) triple of fractional x/y pairs per cubic. */
private val OutlineStart = floatArrayOf(.018f, .18f)
private val OutlineCubics = arrayOf(
    floatArrayOf(.02f, .06f, .15f, -.015f, .29f, .025f),
    floatArrayOf(.52f, .105f, .65f, .005f, .82f, .025f),
    floatArrayOf(.99f, .03f, 1.01f, .14f, .975f, .29f),
    floatArrayOf(.93f, .45f, .95f, .5f, .98f, .65f),
    floatArrayOf(1.025f, .87f, .965f, .965f, .84f, .98f),
    floatArrayOf(.66f, 1.025f, .47f, .945f, .28f, .975f),
    floatArrayOf(.08f, 1.015f, -.01f, .91f, .025f, .78f),
    floatArrayOf(.07f, .63f, -.005f, .58f, .025f, .45f),
    floatArrayOf(.065f, .29f, .005f, .27f, .018f, .18f),
)

private fun nowFocusPath(w: Float, h: Float) = Path().apply {
    moveTo(w * OutlineStart[0], h * OutlineStart[1])
    OutlineCubics.forEach { c -> cubicTo(w * c[0], h * c[1], w * c[2], h * c[3], w * c[4], h * c[5]) }
    close()
}

/** The outline sampled as x0, y0, x1, y1, … in px for a card of [w] × [h]. */
internal fun outlinePoints(w: Float, h: Float): FloatArray {
    val steps = 24
    val out = FloatArray(OutlineCubics.size * (steps + 1) * 2)
    var n = 0
    var x0 = OutlineStart[0]; var y0 = OutlineStart[1]
    OutlineCubics.forEach { c ->
        for (i in 0..steps) {
            val t = i / steps.toFloat(); val u = 1 - t
            val a = u * u * u; val b = 3 * u * u * t; val d = 3 * u * t * t; val e = t * t * t
            out[n++] = (a * x0 + b * c[0] + d * c[2] + e * c[4]) * w
            out[n++] = (a * y0 + b * c[1] + d * c[3] + e * c[5]) * h
        }
        x0 = c[4]; y0 = c[5]
    }
    return out
}

/**
 * Where a capsule of length [len] (between its two end centres), radius [r], centred on [cy],
 * may start (its outer end centre, px) — moved inward from [from] in the direction [dir] (+1 from
 * the left edge, -1 from the right) until the border beyond its outer end is [edgeGap] away.
 * Border over its body or inner end must already be [bodyGap] away (only lowering the row can
 * fix that), otherwise NaN.
 */
private fun clearCapsule(
    pts: FloatArray, w: Float, from: Float, dir: Int, len: Float, r: Float, cy: Float, edgeGap: Float, bodyGap: Float,
): Float {
    var a = from
    repeat(12) {
        var moved = false
        var i = 0
        while (i < pts.size) {
            val px = pts[i]; val py = pts[i + 1]; i += 2
            if (if (dir > 0) px > w * .7f else px < w * .3f) continue
            val dy = kotlin.math.abs(py - cy)
            val outer = if (dir > 0) px < a else px > a
            val reach = r + if (outer) edgeGap else bodyGap
            if (dy >= reach) continue
            val sx = kotlin.math.sqrt(reach * reach - dy * dy)
            if (outer) {
                val need = px + dir * sx
                if ((need - a) * dir > 0f) { a = need; moved = true }
            } else {
                val inner = a + dir * len
                if ((px - inner) * dir < sx) return Float.NaN
            }
        }
        if (!moved) return a
    }
    return a
}

/**
 * The status-pill row of the Now card: [start] pill on the left, optional [end] pill on the
 * right, always one line. Each pill is kept clear of the wavy border — moved in from the
 * corners or lowered a little, whichever disturbs the card least. [cardSize] is the card's
 * measured size (read during layout only); [originX]/[originY] is where this row starts in it.
 */
@Composable
internal fun NowFocusPillRow(
    cardSize: State<IntSize>,
    originX: Dp,
    originY: Dp,
    modifier: Modifier = Modifier,
    /** How much more lowering the row costs than moving a pill in (raise it when height is scarce). */
    lowerCost: Float = 2f,
    /** Empty space under the row that lowered pills may hang into instead of growing the card. */
    belowRoom: Dp = 0.dp,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit = {},
) {
    // Height this row last added to the card; taken back off the card height before fitting so
    // the fit never feeds on its own result (no relayout loop).
    val lastExtra = androidx.compose.runtime.remember { IntArray(1) }
    Layout(contents = listOf(start, end), modifier = modifier) { (startM, endM), constraints ->
        val ox = originX.toPx(); val oy = originY.toPx()
        val gap = 8.dp.toPx(); val bodyGap = 4.dp.toPx(); val spacing = 6.dp.toPx()
        val step = 1.dp.toPx(); val maxDown = 40.dp.toPx()
        val maxW = constraints.maxWidth
        val known = cardSize.value
        val w = if (known.width > 0) known.width.toFloat() else maxW + 2 * ox
        val baseH = if (known.height > 0) known.height - lastExtra[0] else (w * 1.6f).toInt()
        val room = belowRoom.roundToPx()
        fun extraFor(down: Float) = (down.toInt() - room).coerceAtLeast(0)
        val outlines = HashMap<Int, FloatArray>()
        fun ptsFor(down: Float) = outlines.getOrPut(extraFor(down)) { outlinePoints(w, (baseH + extraFor(down)).toFloat()) }
        val loose = Constraints(maxWidth = maxW)
        val s = startM.map { it.measure(loose) }
        val wl = s.maxOfOrNull { it.width } ?: 0
        val hl = s.maxOfOrNull { it.height } ?: 0
        val hasEnd = endM.isNotEmpty()

        // Lowering the row costs timer height, so it weighs more than an inward nudge.
        class Fit(val down: Float, val left: Float, val right: Float) {
            val cost get() = maxOf(left, right) + lowerCost * down
        }
        fun fit(down: Float, height: Int, wr: Int): Fit? {
            val r = height / 2f; val cy = oy + down + r
            val pts = ptsFor(down)
            val a = clearCapsule(pts, w, ox + r, 1, (wl - 2 * r).coerceAtLeast(0f), r, cy, gap, bodyGap)
            val b = if (hasEnd) clearCapsule(pts, w, w - ox - r, -1, (wr - 2 * r).coerceAtLeast(0f), r, cy, gap, bodyGap)
                else w - ox - r
            if (a.isNaN() || b.isNaN()) return null
            return Fit(down, a - r - ox, w - ox - r - b)
        }
        fun best(height: Int, wr: Int, budget: Float): Fit? {
            var pick: Fit? = null
            var d = 0f
            while (d <= maxDown) {
                val f = fit(d, height, wr)
                if (f != null && f.left + f.right <= budget && (pick == null || f.cost < pick.cost)) pick = f
                d += step
            }
            return pick
        }

        // Corners first (end pill as a bare cap), then the end pill gets the width that leaves.
        val endMin = if (hasEnd) 56.dp.toPx() else 0f
        val first = best(hl, 0, maxW - wl - spacing - endMin) ?: Fit(0f, 0f, 0f)
        val endMax = (maxW - first.left - first.right - wl - spacing).toInt().coerceAtLeast(0)
        val e = endM.map { it.measure(Constraints(maxWidth = endMax)) }
        val wr = e.maxOfOrNull { it.width } ?: 0
        val height = maxOf(hl, e.maxOfOrNull { it.height } ?: 0)
        val chosen = best(height, wr, first.left + first.right)
            ?: best(height, wr, maxW - wl - spacing - wr) ?: first
        val down = chosen.down.toInt()
        val extra = extraFor(chosen.down)
        lastExtra[0] = extra
        layout(maxW, height + extra) {
            s.forEach { it.place(chosen.left.toInt(), down + (height - it.height) / 2) }
            e.forEach { it.place(maxW - chosen.right.toInt() - it.width, down + (height - it.height) / 2) }
        }
    }
}

/** Capsule with the card's hand-drawn wobble: gently uneven top and bottom edges. */
internal val NowPillShape = GenericShape { size, _ ->
    val w = size.width; val h = size.height
    val r = h / 2; val k = r * .5523f
    moveTo(r, h * .08f)
    cubicTo(w * .34f, h * -.02f, w * .6f, h * .14f, w - r, h * .03f)
    cubicTo(w - r + k, h * .03f, w, r - k, w, r * 1.02f)
    cubicTo(w, r + k, w - r + k, h * .97f, w - r, h * .97f)
    cubicTo(w * .66f, h * .87f, w * .4f, h * 1.03f, r, h * .93f)
    cubicTo(r - k, h * .93f, 0f, r + k, 0f, r * .98f)
    cubicTo(0f, r - k, r - k, h * .08f, r, h * .08f)
    close()
}

/**
 * A tilted pebble for the Focus card's buttons — deliberately not the pills' wavy capsule: the
 * top and bottom edges slope against each other, each corner has its own radius and the long
 * edges swing in a soft S. [wobble] 0 is nearly regular, 1.6 at most; [mirror] flips
 * it so neighbouring buttons never match. Every point stays inside the bounds.
 */
internal fun nowButtonShape(wobble: Float, mirror: Boolean = false) = GenericShape { size, _ ->
    val w = size.width; val h = size.height; val t = wobble
    fun p(x: Float, y: Float) = Offset(if (mirror) w - x else x, y)
    val tl = p(0f, h * .10f * t); val tr = p(w, 0f); val br = p(w, h * (1 - .10f * t)); val bl = p(0f, h)
    fun along(a: Offset, b: Offset, d: Float): Offset {
        val v = b - a; val len = v.getDistance(); return if (len == 0f) a else a + v * (d / len)
    }
    fun corner(c: Offset, from: Offset, to: Offset, r: Float) {
        val s = along(c, from, r); val e = along(c, to, r)
        lineTo(s.x, s.y)
        cubicTo(s.x + (c.x - s.x) * .55f, s.y + (c.y - s.y) * .55f, e.x + (c.x - e.x) * .55f, e.y + (c.y - e.y) * .55f, e.x, e.y)
    }
    fun swing(a: Offset, b: Offset, first: Float, second: Float) {
        val c1 = a + (b - a) / 3f; val c2 = a + (b - a) * (2f / 3f)
        cubicTo(c1.x, c1.y + first, c2.x, c2.y + second, b.x, b.y)
    }
    val rTl = h * (.5f - .05f * t); val rTr = h * (.5f - .16f * t); val rBr = h * (.5f - .1f * t); val rBl = h * (.5f - .07f * t)
    val top0 = along(tl, tr, rTl); val top1 = along(tr, tl, rTr)
    val bot0 = along(br, bl, rBr); val bot1 = along(bl, br, rBl)
    moveTo(top0.x, top0.y)
    swing(top0, top1, h * .07f * t, -h * .03f * t)
    corner(tr, top1, along(tr, br, rTr), rTr)
    corner(br, tr, bot0, rBr)
    swing(bot0, bot1, h * .03f * t, -h * .07f * t)
    corner(bl, bot1, along(bl, tl, rBl), rBl)
    corner(tl, bl, top0, rTl)
    close()
}

internal val NowChooseButtonShape = nowButtonShape(1.6f)
internal val NowLeaveButtonShape = nowButtonShape(.4f)
internal val NowCompleteButtonShape = nowButtonShape(.45f, mirror = true)

@Composable
internal fun NowFocusSurface(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val outer = nowFocusPath(size.width, size.height)
        drawPath(outer, Brush.linearGradient(listOf(Color(0xFF30EBDD), Color(0xFFB0FFF2), Color(0xFF00D9CE))))
        inset(3.dp.toPx(), 5.dp.toPx()) {
            val w = size.width
            val h = size.height
            val inner = nowFocusPath(w, h)
            clipPath(inner) {
                drawRect(Brush.linearGradient(
                    listOf(Color(0xFFFAFFFF), Color(0xFFDFFFF7), Color(0xFF94F8E8)),
                    Offset.Zero, Offset(w, h)))
                drawRect(Brush.radialGradient(listOf(Color(0xCCFFFFFF), Color.Transparent),
                    Offset(w * .12f, h * .38f), w * .9f))
                val wave = Path().apply {
                    moveTo(-w * .1f, h * .84f)
                    cubicTo(w * .12f, h * .61f, w * .13f, h * .8f, w * .29f, h * .76f)
                    cubicTo(w * .47f, h * .7f, w * .53f, h * .9f, w * .72f, h * .76f)
                    cubicTo(w * .83f, h * .68f, w * .86f, h * .8f, w * .96f, h * .66f)
                    cubicTo(w * 1.02f, h * .62f, w * 1.09f, h * .7f, w * 1.1f, h * .75f)
                    lineTo(w * 1.1f, h * 1.1f); lineTo(-w * .1f, h * 1.1f); close()
                }
                drawPath(wave, Brush.linearGradient(listOf(Color(0xFFB1FFF0), Color(0xFF63F3DE)), Offset.Zero, Offset(w, h)))
                val bottom = Path().apply {
                    moveTo(w * .47f, h * 1.08f)
                    cubicTo(w * .76f, h * .89f, w * .73f, h * .96f, w * 1.1f, h * 1.02f)
                    lineTo(w * 1.1f, h * 1.1f); close()
                }
                drawPath(bottom, Brush.horizontalGradient(listOf(Color(0xFF00E6D4), Color(0xFF00CBD2))))
            }
            drawPath(inner, Color.White.copy(alpha = .85f), style = Stroke(1.dp.toPx()))
        }
    }
}
