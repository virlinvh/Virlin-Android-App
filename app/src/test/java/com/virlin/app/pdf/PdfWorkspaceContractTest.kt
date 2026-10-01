package com.virlin.app.pdf

import com.virlin.app.ui.pdf.PdfCrop
import com.virlin.app.ui.pdf.formatPdfPageRange
import com.virlin.app.ui.pdf.parsePdfPageRange
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfWorkspaceContractTest {
    @Test
    fun pageRange_parsesSinglesAndInclusiveRanges_withoutDuplicates() {
        val pages = parsePdfPageRange("1, 3-5, 4, 10", pageCount = 10).getOrThrow()

        assertEquals(linkedSetOf(0, 2, 3, 4, 9), pages)
        assertEquals("1, 3-5, 10", formatPdfPageRange(pages))
    }

    @Test
    fun pageRange_rejectsReverseAndOutOfBoundsRanges() {
        assertTrue(parsePdfPageRange("5-3", 10).isFailure)
        assertTrue(parsePdfPageRange("0", 10).isFailure)
        assertTrue(parsePdfPageRange("11", 10).isFailure)
        assertTrue(parsePdfPageRange("", 10).isFailure)
    }

    @Test
    fun normalizedCrop_mapsToPixelBounds() {
        val crop = PdfCrop(left = .1f, top = .2f, right = .9f, bottom = .8f)

        assertArrayEquals(intArrayOf(100, 400, 900, 1600), crop.pixelBounds(1000, 2000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun crop_rejectsEmptyWidth() {
        PdfCrop(left = .5f, right = .5f)
    }
}
