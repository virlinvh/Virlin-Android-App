package com.virlin.app.project

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.projecticon.ProjectIconStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Managed project-icon storage: import (PNG / JPEG / WebP), validation, downsampled decode,
 * missing / corrupt handling, replacement and removal. Runs on device (real BitmapFactory).
 */
class ProjectIconStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scratch = File(context.cacheDir, "icon-test").also { it.mkdirs() }

    @Before fun clean() { ProjectIconStore.delete(context, "pX"); scratch.deleteRecursively(); scratch.mkdirs() }
    @After fun tearDown() { ProjectIconStore.delete(context, "pX"); scratch.deleteRecursively() }

    private fun image(name: String, format: Bitmap.CompressFormat, size: Int = 256): Uri {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(200, 30, 120, 90)) }
        val f = File(scratch, name); f.outputStream().use { bmp.compress(format, 92, it) }
        return Uri.fromFile(f)
    }

    @Test fun importPng_storesManagedCopy_andDecodesDownsampled() {
        val rel = ProjectIconStore.importFromUri(context, "pX", image("a.png", Bitmap.CompressFormat.PNG, 512))
        assertTrue(rel.startsWith("pX/")); assertTrue(rel.endsWith(".png"))
        assertTrue(ProjectIconStore.exists(context, rel))
        val bmp = ProjectIconStore.decodeForDisplay(context, rel, targetPx = 64)
        assertNotNull(bmp); assertTrue("downsampled (${bmp!!.width})", bmp.width in 64..128)
        // original bytes untouched (no re-encode): identical size to the source file
        assertEquals(File(scratch, "a.png").length(), ProjectIconStore.resolve(context, rel).length())
    }

    @Test fun importJpegAndWebp_supported() {
        val j = ProjectIconStore.importFromUri(context, "pX", image("b.jpg", Bitmap.CompressFormat.JPEG))
        assertTrue(j.endsWith(".jpg"))
        val w = ProjectIconStore.importFromUri(context, "pX", image("c.webp", Bitmap.CompressFormat.WEBP))
        assertTrue(w.endsWith(".webp"))
        // replacing removed the previous file — exactly one icon per project
        assertEquals(1, File(ProjectIconStore.root(context), "pX").listFiles()!!.size)
        assertFalse(ProjectIconStore.exists(context, j))
    }

    @Test fun importNonImage_isRejected_andWritesNothing() {
        val f = File(scratch, "notes.txt").apply { writeText("hello") }
        try { ProjectIconStore.importFromUri(context, "pX", Uri.fromFile(f)); throw AssertionError("expected rejection") }
        catch (e: ProjectIconStore.UnsupportedImage) { /* expected */ }
        assertFalse(File(ProjectIconStore.root(context), "pX").exists())
    }

    @Test fun missingOrCorruptReference_decodesToNull_neverThrows() {
        assertNull(ProjectIconStore.decodeForDisplay(context, "pX/does-not-exist.png", 64))
        assertFalse(ProjectIconStore.exists(context, "pX/does-not-exist.png"))
        val corrupt = ProjectIconStore.resolve(context, "pX/icon-0.png").also { it.parentFile!!.mkdirs(); it.writeBytes(ByteArray(300) { 7 }) }
        assertTrue(corrupt.isFile)
        assertNull(ProjectIconStore.decodeForDisplay(context, "pX/icon-0.png", 64))
    }

    @Test fun delete_removesEverything() {
        val rel = ProjectIconStore.importFromUri(context, "pX", image("d.png", Bitmap.CompressFormat.PNG))
        assertTrue(ProjectIconStore.exists(context, rel))
        ProjectIconStore.delete(context, "pX")
        assertFalse(ProjectIconStore.exists(context, rel))
        ProjectIconStore.delete(context, "pX")   // idempotent
    }
}
