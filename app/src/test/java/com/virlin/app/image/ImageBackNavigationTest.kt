package com.virlin.app.image

import com.virlin.app.ui.image.ImageBackAction
import com.virlin.app.ui.image.ImageWorkspaceMode
import com.virlin.app.ui.image.imageBackAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression guard for the back-navigation trap found on the emulator on 2026-10-01.
 *
 * Opening an IMAGE block from the Task Page routes to `image_workspace/capture/{captureId}`, which
 * starts in EDIT because it addresses one capture directly. The original `back()` read:
 *
 *     if (mode == LIBRARY) popBackStack() else setMode(if (captureId != null) EDIT else LIBRARY)
 *
 * so in that route back set the mode to EDIT while already in EDIT — a self-loop. `popBackStack()`
 * was never reached, and since the same handler served both the top-bar arrow and `BackHandler`,
 * neither the arrow, the system Back button nor the edge-swipe gesture could leave the editor. Four
 * consecutive back presses were observed with no change.
 */
class ImageBackNavigationTest {

    @Test
    fun editOpenedFromAPageBlock_exitsTheScreen() {
        assertEquals(
            ImageBackAction.EXIT,
            imageBackAction(ImageWorkspaceMode.EDIT, hasCaptureOwner = true),
        )
    }

    @Test
    fun editOpenedFromTheLibrary_returnsToTheLibrary() {
        assertEquals(
            ImageBackAction.TO_LIBRARY,
            imageBackAction(ImageWorkspaceMode.EDIT, hasCaptureOwner = false),
        )
    }

    @Test
    fun cropAndMarkupAreSubScreensOfTheEditor() {
        for (owner in listOf(true, false)) {
            assertEquals(
                ImageBackAction.TO_EDIT,
                imageBackAction(ImageWorkspaceMode.CROP, hasCaptureOwner = owner),
            )
            assertEquals(
                ImageBackAction.TO_EDIT,
                imageBackAction(ImageWorkspaceMode.MARKUP, hasCaptureOwner = owner),
            )
        }
    }

    @Test
    fun libraryAlwaysExits() {
        for (owner in listOf(true, false)) {
            assertEquals(
                ImageBackAction.EXIT,
                imageBackAction(ImageWorkspaceMode.LIBRARY, hasCaptureOwner = owner),
            )
        }
    }

    /** No mode may resolve to the mode it is already in, or back becomes a self-loop. */
    @Test
    fun noModeResolvesToItself() {
        for (mode in ImageWorkspaceMode.entries) {
            for (owner in listOf(true, false)) {
                val action = imageBackAction(mode, hasCaptureOwner = owner)
                val sameMode = when (mode) {
                    ImageWorkspaceMode.EDIT -> ImageBackAction.TO_EDIT
                    ImageWorkspaceMode.LIBRARY -> ImageBackAction.TO_LIBRARY
                    else -> null
                }
                if (sameMode != null) assertNotEquals("$mode with owner=$owner", sameMode, action)
            }
        }
    }
}
