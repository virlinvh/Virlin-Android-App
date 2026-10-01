package com.virlin.app.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the Image workspace crash reproduced on the emulator on 2026-10-01.
 *
 * Importing an image succeeded, but the app disappeared to the launcher the moment the library
 * drew its first thumbnail:
 *
 *     java.lang.RuntimeException: Canvas: trying to use a recycled bitmap
 *         at androidx.compose.ui.graphics.painter.BitmapPainter.onDraw
 *
 * The cause was a `DisposableEffect { onDispose { bitmap.recycle() } }` around a bitmap that had
 * already been handed to Compose through `asImageBitmap()`. That call wraps the Bitmap without
 * copying it, and a RenderNode display list can be replayed after `onDispose` has run, so the
 * recycle freed pixels Compose still intended to draw.
 *
 * Because `minSdk` is 26, bitmap memory lives on the Java heap and is reclaimed by the garbage
 * collector, so no explicit recycle is needed in the UI layer at all. This test fails if anyone
 * reintroduces one in the screen that renders bitmaps.
 */
class ImageBitmapLifecycleTest {

    private val screen = File("src/main/java/com/virlin/app/ui/image/ImageWorkspaceScreen.kt")

    @Test
    fun imageWorkspaceScreen_neverRecyclesABitmapItHandsToCompose() {
        assertTrue("ImageWorkspaceScreen.kt not found at ${screen.absolutePath}", screen.exists())
        val source = screen.readText()
        assertTrue(
            "The screen is expected to render bitmaps through asImageBitmap().",
            source.contains("asImageBitmap()"),
        )
        val offenders = source.lineSequence()
            .withIndex()
            .filter { (_, line) -> line.contains(".recycle()") && !line.trimStart().startsWith("//") }
            .map { (index, line) -> "line ${index + 1}: ${line.trim()}" }
            .toList()
        assertTrue(
            "ImageWorkspaceScreen must not recycle a bitmap Compose may still draw: $offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * The render engine may still recycle, but only intermediates it owns and never publishes.
     * This pins the one exception so the rule above cannot be worked around by moving the recycle.
     */
    @Test
    fun renderEngine_onlyRecyclesItsOwnIntermediates() {
        val engine = File("src/main/java/com/virlin/app/ui/image/ImageRenderEngine.kt")
        assertTrue("ImageRenderEngine.kt not found", engine.exists())
        assertFalse(
            "The render engine must not pass bitmaps to Compose; it returns them to callers.",
            engine.readText().contains("asImageBitmap()"),
        )
    }
}
