package com.virlin.app.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The image editor's pure maths: turning, cropping, colour and whitening. */
class NoteImageEditTest {

    @Test fun turningMapsPointsAndBackForEveryQuarter() {
        val p = NoteImagePoint(0.2f, 0.7f)
        for (q in 0..3) {
            val back = NoteImageGeometry.fromTurned(NoteImageGeometry.toTurned(p, q), q)
            assertEquals(p.x, back.x, 1e-6f)
            assertEquals(p.y, back.y, 1e-6f)
        }
        // One clockwise turn: the top-left corner goes to the top-right.
        assertEquals(NoteImagePoint(1f, 0f), NoteImageGeometry.toTurned(NoteImagePoint(0f, 0f), 1))
    }

    @Test fun theCropTurnsWithThePictureAndFourTurnsAreTheStart() {
        val edit = NoteImageEdit(crop = NoteImageCrop(0.1f, 0.2f, 0.5f, 0.9f))
        val once = edit.turned(clockwise = true)
        assertEquals(1, once.quarterTurns)
        // After a clockwise turn the crop's bottom-left corner (0.1, 0.9) becomes its top-left.
        val corner = NoteImageGeometry.toTurned(NoteImagePoint(0.1f, 0.9f), 1)
        assertEquals(corner.x, once.crop.left, 1e-6f)
        assertEquals(corner.y, once.crop.top, 1e-6f)
        var e = edit
        repeat(4) { e = e.turned(clockwise = true) }
        assertEquals(edit.quarterTurns, e.quarterTurns)
        assertEquals(edit.crop.left, e.crop.left, 1e-6f)
        assertEquals(edit.crop.bottom, e.crop.bottom, 1e-6f)
        assertEquals(edit.crop, edit.turned(true).turned(false).crop.let {
            NoteImageCrop(round(it.left), round(it.top), round(it.right), round(it.bottom))
        })
    }

    private fun round(v: Float) = Math.round(v * 1000f) / 1000f

    @Test fun ratioCropsHaveThatShapeInPixelsAndStayInside() {
        val crop = NoteImageGeometry.cropForRatio(NoteImageCrop.FULL, 1f, 400, 200)
        assertEquals(1f, crop.width * 400 / (crop.height * 200), 1e-4f)
        assertTrue(crop.left >= 0f && crop.right <= 1f && crop.top >= 0f && crop.bottom <= 1f)
        val wide = NoteImageGeometry.cropForRatio(NoteImageCrop.FULL, 16f / 9f, 300, 400)
        assertEquals(16f / 9f, wide.width * 300 / (wide.height * 400), 1e-4f)
    }

    @Test fun resizingNeverCollapsesOrLeavesThePicture() {
        val start = NoteImageCrop(0.2f, 0.2f, 0.8f, 0.8f)
        val squeezed = NoteImageGeometry.resized(start, 2f, 0f, left = true, top = false, right = false, bottom = false)
        assertEquals(NoteImageCrop.MIN_SIDE, squeezed.width, 1e-6f)
        val out = NoteImageGeometry.resized(start, -5f, -5f, left = true, top = true, right = false, bottom = false)
        assertEquals(0f, out.left, 0f)
        assertEquals(0f, out.top, 0f)
        val moved = NoteImageGeometry.moved(start, 1f, -1f)
        assertEquals(1f, moved.right, 1e-6f)
        assertEquals(0f, moved.top, 1e-6f)
        assertEquals(start.width, moved.width, 1e-6f)
        val locked = NoteImageGeometry.resized(start, 0.1f, 0f, left = false, top = false, right = true, bottom = true, ratio = 2f)
        assertEquals(2f, locked.width / locked.height, 1e-4f)
    }

    @Test fun cropPixelsAreNeverEmpty() {
        val px = NoteImageGeometry.cropPixels(NoteImageCrop(0.999f, 0.999f, 1f, 1f), 100, 50)
        assertTrue(px[2] >= 1 && px[3] >= 1)
        assertTrue(px[0] + px[2] <= 100 && px[1] + px[3] <= 50)
    }

    @Test fun zeroSlidersAreTheIdentityMatrix() {
        val m = NoteImageColor.adjustMatrix(0f, 0f, 0f)
        assertTrue(NoteImageColor.isIdentity(m))
        assertEquals(1f, m[0], 1e-6f); assertEquals(0f, m[4], 1e-6f)
        val brighter = NoteImageColor.adjustMatrix(0.5f, 0f, 0f)
        assertTrue(brighter[4] > 0f)
        val gray = NoteImageColor.adjustMatrix(0f, 0f, 0f, gray = true)
        assertEquals(gray[0], gray[5], 1e-6f) // every channel reads the same luminance
    }

    @Test fun whiteningTurnsShadowedPaperWhiteAndKeepsInkDark() {
        val w = 120; val h = 80
        // Paper darkening from left (210) to right (140), like a shadow across a page, with ink strokes.
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val paper = 210 - x * 70 / w
            val ink = (y in 30..33 && x in 10..110) || (x in 58..61 && y in 10..70)
            val v = if (ink) 35 else paper
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        NoteImageColor.whiten(pixels, w, h, blackAndWhite = false)
        fun lum(x: Int, y: Int) = pixels[y * w + x] and 0xFF
        assertTrue("left paper is white", lum(5, 5) >= 245)
        assertTrue("shadowed right paper is white", lum(115, 75) >= 245)
        assertTrue("ink stays dark", lum(30, 31) <= 70)
        assertTrue("ink in the shadow stays dark", lum(100, 32) <= 90)
        assertEquals(0xFF, (pixels[0] ushr 24) and 0xFF)
    }

    @Test fun sparseBrightColourNoiseDoesNotTintTheWhitenedPaper() {
        val w = 480; val h = 320
        // Warm paper with a few bright magenta specks, like JPEG ringing beside ink.
        val pixels = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val speck = x % 26 == 0 && y % 26 == 0
            if (speck) (0xFF shl 24) or (250 shl 16) or (225 shl 8) or 250
            else (0xFF shl 24) or (200 shl 16) or (190 shl 8) or 165
        }
        NoteImageColor.whiten(pixels, w, h, blackAndWhite = false)
        for ((x, y) in listOf(5 to 5, 101 to 77, 240 to 160, 471 to 313)) {
            val p = pixels[y * w + x]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            assertTrue("paper at $x,$y is white, was $r,$g,$b", minOf(r, g, b) >= 240)
            assertTrue("paper at $x,$y is neutral, was $r,$g,$b", maxOf(r, g, b) - minOf(r, g, b) <= 8)
        }
    }
}
