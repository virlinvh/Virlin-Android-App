package com.virlin.app.image

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.ui.image.ImageAdjustments
import com.virlin.app.ui.image.ImageCrop
import com.virlin.app.ui.image.ImageMarkupStroke
import com.virlin.app.ui.image.ImageMarkupTool
import com.virlin.app.ui.image.ImageRenderEngine
import com.virlin.app.ui.image.ImageTransform
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageRenderEngineTest {
    @Test fun render_cropsRotatesAndLeavesSourceBytesUntouched() {
        val source = fixture("image-render-source.png")
        val original = source.readBytes()
        val rendered = ImageRenderEngine.render(
            source,
            crop = ImageCrop(0f, 0f, .5f, 1f),
            transform = ImageTransform(rotationQuarterTurns = 1),
            adjustments = ImageAdjustments(brightness = .1f, saturation = .2f),
            strokes = emptyList(),
        )
        try {
            assertEquals(80, rendered.width)
            assertEquals(60, rendered.height)
            assertTrue(original.contentEquals(source.readBytes()))
        } finally { rendered.recycle() }
    }

    @Test fun render_flattensMarkupIntoANewPng() {
        val source = fixture("image-markup-source.png")
        val rendered = ImageRenderEngine.render(
            source, ImageCrop(), ImageTransform(), ImageAdjustments(),
            listOf(
                ImageMarkupStroke(ImageMarkupTool.HIGHLIGHTER, Color.YELLOW, .08f, .4f, listOf(.1f to .2f, .9f to .2f)),
                ImageMarkupStroke(ImageMarkupTool.ARROW, Color.RED, .03f, 1f, listOf(.2f to .8f, .8f to .4f)),
                ImageMarkupStroke(ImageMarkupTool.TEXT, Color.BLACK, .02f, 1f, listOf(.1f to .6f), "Review"),
            ),
        )
        val output = File(source.parentFile, "image-markup-output.png")
        try {
            ImageRenderEngine.savePng(rendered, output)
            assertTrue(output.length() > 0L)
            assertTrue(output.readBytes().take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
        } finally { rendered.recycle(); output.delete() }
    }

    private fun fixture(name: String): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(60, 130, 190)) }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
