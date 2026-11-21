package com.virlin.app.ui.notes

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Saved edits on real bitmaps: turn, crop, filter and pen marks land where the editor showed them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteImageRenderTest {

    /** 200 × 100: left half red, right half blue. */
    private fun source(): Bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until 200) for (y in 0 until 100) setPixel(x, y, if (x < 100) Color.RED else Color.BLUE)
    }

    @Test fun cropKeepsOnlyTheChosenPart() {
        val out = NoteImageRender.render(source(), NoteImageEdit(crop = NoteImageCrop(0.5f, 0f, 1f, 1f)))
        assertEquals(100, out.width)
        assertEquals(100, out.height)
        assertEquals(Color.BLUE, out.getPixel(50, 50))
    }

    @Test fun aClockwiseTurnPutsTheLeftHalfOnTop() {
        val out = NoteImageRender.render(source(), NoteImageEdit(quarterTurns = 1))
        assertEquals(100, out.width)
        assertEquals(200, out.height)
        assertEquals(Color.RED, out.getPixel(50, 20))
        assertEquals(Color.BLUE, out.getPixel(50, 180))
    }

    @Test fun greyFilterAndPenMarksAreDrawnIntoTheSavedPicture() {
        val black = 0xFF000000L
        val mark = NoteImageStroke(listOf(NoteImagePoint(0.1f, 0.5f), NoteImagePoint(0.9f, 0.5f)), black, width = 0.05f)
        val out = NoteImageRender.render(source(), NoteImageEdit(filter = NoteImageFilter.GRAYSCALE, strokes = listOf(mark)))
        val p = out.getPixel(30, 10)
        assertEquals(Color.red(p), Color.green(p))
        assertEquals(Color.green(p), Color.blue(p))
        val onLine = out.getPixel(100, 50)
        assertTrue("pen mark is drawn", Color.red(onLine) < 40 && Color.blue(onLine) < 40)
    }
}
