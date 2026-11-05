package com.virlin.app.pdf

import com.virlin.app.ui.pdf.PdfAnnotation
import com.virlin.app.ui.pdf.PdfAnnotationPoint
import com.virlin.app.ui.pdf.PdfAnnotationStore
import com.virlin.app.ui.pdf.PdfAnnotationTool
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfAnnotationStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun sidecarRoundTripsWithoutChangingThePdf() {
        val pdf = File(folder.root, "study.pdf")
        val source = "original pdf bytes".toByteArray()
        pdf.writeBytes(source)
        val annotations = listOf(
            PdfAnnotation(0, PdfAnnotationTool.HIGHLIGHT,
                listOf(PdfAnnotationPoint(.2f, .3f), PdfAnnotationPoint(.6f, .3f))),
            PdfAnnotation(2, PdfAnnotationTool.NOTE,
                listOf(PdfAnnotationPoint(.4f, .5f)), "Review this"),
        )
        PdfAnnotationStore.write(pdf, annotations)

        assertEquals(annotations, PdfAnnotationStore.read(pdf))
        assertEquals(source.toList(), pdf.readBytes().toList())
    }
}
