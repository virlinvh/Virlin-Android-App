package com.virlin.app.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapExportWriter
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapLayout
import com.virlin.app.ui.map.MapTopic
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class MapExportDeviceTest {
    @Test fun pdfAndPngRenderEveryLogicLayout() {
        val topics = listOf(
            MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            MapTopic("s", "p", "Workstream", kind = MapKind.WORKSTREAM),
            MapTopic("t", "s", "Task", kind = MapKind.TASK),
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MapLayout.entries.forEach { layout ->
            val appearance = MapAppearance(layout = layout)
            val pdf = ByteArrayOutputStream()
            MapExportWriter.writePdf(topics, appearance, pdf)
            assertTrue(pdf.toString("ISO-8859-1").startsWith("%PDF-"))
            val file = File.createTempFile("map-export-", ".pdf", context.cacheDir)
            try {
                file.writeBytes(pdf.toByteArray())
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        assertTrue(renderer.pageCount > 0)
                        renderer.openPage(0).use { page ->
                            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                            finally { bitmap.recycle() }
                        }
                    }
                }
            } finally { file.delete() }

            val png = ByteArrayOutputStream()
            MapExportWriter.writePng(topics, appearance, png)
            val bitmap = BitmapFactory.decodeByteArray(png.toByteArray(), 0, png.size())
            assertTrue("$layout PNG was unreadable", bitmap != null && bitmap.width > 0)
            bitmap?.recycle()
        }
    }
}
