package com.virlin.app.ui.notes

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A point in the original image, each axis 0..1. Strokes live here, so later crops and turns keep them in place. */
data class NoteImagePoint(val x: Float, val y: Float)

/** A pen or highlighter mark. [width] is a fraction of the original image's longer side. */
data class NoteImageStroke(
    val points: List<NoteImagePoint>,
    val color: Long,
    val width: Float,
    val highlighter: Boolean = false,
)

/** A crop rectangle in the turned image, each edge 0..1. */
data class NoteImageCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    companion object {
        /** Smallest crop side, as a fraction of the image, so a crop can never collapse to nothing. */
        const val MIN_SIDE = 0.08f
        val FULL = NoteImageCrop()
    }
}

enum class NoteImageFilter(val label: String) {
    ORIGINAL("Original"),
    /** Paper turns white and ink stays, keeping its colour: a phone "scan". */
    DOCUMENT("Whiten"),
    /** The same, in crisp black and white. */
    DOCUMENT_BW("B&W scan"),
    GRAYSCALE("Grey"),
}

/**
 * Every change the image editor makes, applied in this order when the image is saved:
 * turn → crop → filter and adjustments → pen marks. Nothing touches the stored file until then.
 */
data class NoteImageEdit(
    /** Clockwise quarter turns, 0..3. */
    val quarterTurns: Int = 0,
    val crop: NoteImageCrop = NoteImageCrop.FULL,
    val filter: NoteImageFilter = NoteImageFilter.ORIGINAL,
    /** -1..1 each; 0 leaves the image as it is. */
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val strokes: List<NoteImageStroke> = emptyList(),
) {
    val isIdentity: Boolean get() = this == NoteImageEdit()
    val hasAdjustments: Boolean get() = brightness != 0f || contrast != 0f || saturation != 0f

    /** Turns once more ([clockwise] or not); the crop turns with the picture. */
    fun turned(clockwise: Boolean): NoteImageEdit {
        val c = crop
        val nextCrop = if (clockwise) NoteImageCrop(1f - c.bottom, c.left, 1f - c.top, c.right)
        else NoteImageCrop(c.top, 1f - c.right, c.bottom, 1f - c.left)
        return copy(quarterTurns = Math.floorMod(quarterTurns + if (clockwise) 1 else -1, 4), crop = nextCrop)
    }
}

object NoteImageGeometry {
    /** Original-image point → the same point in the turned image. */
    fun toTurned(p: NoteImagePoint, quarterTurns: Int): NoteImagePoint = when (Math.floorMod(quarterTurns, 4)) {
        1 -> NoteImagePoint(1f - p.y, p.x)
        2 -> NoteImagePoint(1f - p.x, 1f - p.y)
        3 -> NoteImagePoint(p.y, 1f - p.x)
        else -> p
    }

    /** Turned-image point → the original image. */
    fun fromTurned(p: NoteImagePoint, quarterTurns: Int): NoteImagePoint = when (Math.floorMod(quarterTurns, 4)) {
        1 -> NoteImagePoint(p.y, 1f - p.x)
        2 -> NoteImagePoint(1f - p.x, 1f - p.y)
        3 -> NoteImagePoint(1f - p.y, p.x)
        else -> p
    }

    /** Size of the image after [quarterTurns]. */
    fun turnedSize(width: Int, height: Int, quarterTurns: Int): Pair<Int, Int> =
        if (Math.floorMod(quarterTurns, 2) == 1) height to width else width to height

    /** The crop in whole pixels of a [width] × [height] turned image, never empty or outside it. */
    fun cropPixels(crop: NoteImageCrop, width: Int, height: Int): IntArray {
        val l = (crop.left.coerceIn(0f, 1f) * width).roundToInt().coerceIn(0, width - 1)
        val t = (crop.top.coerceIn(0f, 1f) * height).roundToInt().coerceIn(0, height - 1)
        val r = (crop.right.coerceIn(0f, 1f) * width).roundToInt().coerceIn(l + 1, width)
        val b = (crop.bottom.coerceIn(0f, 1f) * height).roundToInt().coerceIn(t + 1, height)
        return intArrayOf(l, t, r - l, b - t)
    }

    /**
     * The largest crop of pixel aspect [ratio] (width / height) centred on [current], for a
     * turned image of [width] × [height] pixels. A null ratio keeps [current].
     */
    fun cropForRatio(current: NoteImageCrop, ratio: Float?, width: Int, height: Int): NoteImageCrop {
        if (ratio == null || width <= 0 || height <= 0) return current
        val normalized = ratio * height / width
        var w = 1f
        var h = w / normalized
        if (h > 1f) { h = 1f; w = h * normalized }
        val cx = ((current.left + current.right) / 2f).coerceIn(w / 2f, 1f - w / 2f)
        val cy = ((current.top + current.bottom) / 2f).coerceIn(h / 2f, 1f - h / 2f)
        return NoteImageCrop(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** A crop moved by ([dx], [dy]) without leaving the image. */
    fun moved(crop: NoteImageCrop, dx: Float, dy: Float): NoteImageCrop {
        val x = dx.coerceIn(-crop.left, 1f - crop.right)
        val y = dy.coerceIn(-crop.top, 1f - crop.bottom)
        return NoteImageCrop(crop.left + x, crop.top + y, crop.right + x, crop.bottom + y)
    }

    /**
     * A crop whose edges are dragged by ([dx], [dy]). [left]/[top]/[right]/[bottom] say which
     * edges move. With [ratio] (normalized width / height) a corner keeps the shape.
     */
    fun resized(crop: NoteImageCrop, dx: Float, dy: Float, left: Boolean, top: Boolean,
                right: Boolean, bottom: Boolean, ratio: Float? = null): NoteImageCrop {
        val m = NoteImageCrop.MIN_SIDE
        var l = crop.left; var t = crop.top; var r = crop.right; var b = crop.bottom
        if (left) l = (l + dx).coerceIn(0f, r - m)
        if (right) r = (r + dx).coerceIn(l + m, 1f)
        if (top) t = (t + dy).coerceIn(0f, b - m)
        if (bottom) b = (b + dy).coerceIn(t + m, 1f)
        if (ratio != null && (left || right) && (top || bottom)) {
            // The width leads; the height follows and both shrink back if that leaves the image.
            var w = r - l
            var h = w / ratio
            val maxH = if (top) b else 1f - t
            if (h > maxH) { h = maxH; w = h * ratio }
            if (left) l = r - w else r = l + w
            if (top) t = b - h else b = t + h
        }
        return NoteImageCrop(l, t, r, b)
    }
}

object NoteImageColor {
    /**
     * A 4×5 colour matrix (row-major, offsets in 0..255) for the brightness, contrast and
     * saturation sliders. Android's ColorMatrixColorFilter and Compose's ColorMatrix both read it.
     */
    fun adjustMatrix(brightness: Float, contrast: Float, saturation: Float, gray: Boolean = false): FloatArray {
        val s = if (gray) 0f else 1f + saturation.coerceIn(-1f, 1f)
        val c = 1f + contrast.coerceIn(-1f, 1f) * if (contrast > 0) 1.0f else 0.7f
        val offset = 128f * (1f - c) + brightness.coerceIn(-1f, 1f) * 96f
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val sr = (1 - s) * lr; val sg = (1 - s) * lg; val sb = (1 - s) * lb
        return floatArrayOf(
            c * (sr + s), c * sg, c * sb, 0f, offset,
            c * sr, c * (sg + s), c * sb, 0f, offset,
            c * sr, c * sg, c * (sb + s), 0f, offset,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun isIdentity(matrix: FloatArray): Boolean = matrix.contentEquals(adjustMatrix(0f, 0f, 0f))

    /**
     * Whitens the paper and keeps the ink, in place, for ARGB [pixels] of a [width] × [height]
     * image. The paper's brightness is estimated per area (the brightest pixels of each block,
     * smoothed), so uneven light and shadows across a page fade too. [blackAndWhite] also drops
     * colour and sharpens the ink.
     */
    fun whiten(pixels: IntArray, width: Int, height: Int, blackAndWhite: Boolean) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        val block = max(8, min(width, height) / 24)
        val gw = (width + block - 1) / block
        val gh = (height + block - 1) / block
        val bgR = FloatArray(gw * gh); val bgG = FloatArray(gw * gh); val bgB = FloatArray(gw * gh)
        // Paper estimate per block: the mean colour of its bright samples, which are paper unless ink
        // covers the block. The brightest twentieth is skipped and a fifth averaged, so JPEG colour
        // noise beside ink can't tint the estimate (a single brightest pixel did, as halos).
        val count = IntArray(256)
        val sumR = LongArray(256); val sumG = LongArray(256); val sumB = LongArray(256)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val y1 = min(height, (gy + 1) * block); val x1 = min(width, (gx + 1) * block)
            count.fill(0); sumR.fill(0); sumG.fill(0); sumB.fill(0)
            var samples = 0
            var y = gy * block
            while (y < y1) {
                var x = gx * block
                while (x < x1) {
                    val p = pixels[y * width + x]
                    val bin = luminanceBin(p)
                    count[bin]++
                    sumR[bin] += (p shr 16) and 0xFF; sumG[bin] += (p shr 8) and 0xFF; sumB[bin] += p and 0xFF
                    samples++
                    x += 2
                }
                y += 2
            }
            val skip = samples / 20
            val end = skip + max(1, samples / 5)
            var rank = 0; var weight = 0f; var r = 0f; var g = 0f; var b = 0f
            var bin = 255
            while (bin >= 0 && rank < end) {
                val c = count[bin]
                if (c > 0) {
                    val taken = (min(end, rank + c) - max(skip, rank)).coerceAtLeast(0)
                    if (taken > 0) {
                        val share = taken.toFloat() / c
                        r += sumR[bin] * share; g += sumG[bin] * share; b += sumB[bin] * share
                        weight += taken
                    }
                    rank += c
                }
                bin--
            }
            val i = gy * gw + gx
            if (weight == 0f) { bgR[i] = 255f; bgG[i] = 255f; bgB[i] = 255f; continue }
            bgR[i] = r / weight; bgG[i] = g / weight; bgB[i] = b / weight
        }
        // Blocks fully covered by ink would read as dark paper: take the brightest neighbour, then smooth.
        val r2 = dilate(bgR, gw, gh); val g2 = dilate(bgG, gw, gh); val b2 = dilate(bgB, gw, gh)
        val r3 = boxBlur(r2, gw, gh); val g3 = boxBlur(g2, gw, gh); val b3 = boxBlur(b2, gw, gh)
        val curve = IntArray(256) { v ->
            val n = ((v / 255f) - 0.10f) / 0.82f
            (n.coerceIn(0f, 1f).pow(if (blackAndWhite) 2.2f else 1.5f) * 255f).roundToInt()
        }
        for (y in 0 until height) {
            val fy = (y + 0.5f) / block - 0.5f
            val y0 = fy.toInt().coerceIn(0, gh - 1); val y1 = min(gh - 1, y0 + 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            for (x in 0 until width) {
                val fx = (x + 0.5f) / block - 0.5f
                val x0 = fx.toInt().coerceIn(0, gw - 1); val x1 = min(gw - 1, x0 + 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val i00 = y0 * gw + x0; val i01 = y0 * gw + x1; val i10 = y1 * gw + x0; val i11 = y1 * gw + x1
                val idx = y * width + x
                val p = pixels[idx]
                var r = (((p shr 16) and 0xFF) * 255f / bilinear(r3, i00, i01, i10, i11, tx, ty)).toInt().coerceIn(0, 255)
                var g = (((p shr 8) and 0xFF) * 255f / bilinear(g3, i00, i01, i10, i11, tx, ty)).toInt().coerceIn(0, 255)
                var b = ((p and 0xFF) * 255f / bilinear(b3, i00, i01, i10, i11, tx, ty)).toInt().coerceIn(0, 255)
                if (blackAndWhite) {
                    val l = (r * 0.2126f + g * 0.7152f + b * 0.0722f).roundToInt()
                    r = l; g = l; b = l
                }
                pixels[idx] = (p and 0xFF000000.toInt()) or (curve[r] shl 16) or (curve[g] shl 8) or curve[b]
            }
        }
    }

    private fun luminanceBin(p: Int): Int =
        (((p shr 16) and 0xFF) * 2 + ((p shr 8) and 0xFF) * 5 + (p and 0xFF)) shr 3

    private fun bilinear(a: FloatArray, i00: Int, i01: Int, i10: Int, i11: Int, tx: Float, ty: Float): Float {
        val top = a[i00] + (a[i01] - a[i00]) * tx
        val bottom = a[i10] + (a[i11] - a[i10]) * tx
        return max(24f, top + (bottom - top) * ty)
    }

    private fun dilate(src: FloatArray, w: Int, h: Int): FloatArray = FloatArray(src.size) { i ->
        val x = i % w; val y = i / w
        var m = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until w && ny in 0 until h) m = max(m, src[ny * w + nx])
        }
        m
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int): FloatArray = FloatArray(src.size) { i ->
        val x = i % w; val y = i / w
        var sum = 0f; var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until w && ny in 0 until h) { sum += src[ny * w + nx]; n++ }
        }
        sum / n
    }
}
