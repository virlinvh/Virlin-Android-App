package com.virlin.app.image

import com.virlin.app.ui.image.ImageAdjustments
import com.virlin.app.ui.image.ImageCrop
import com.virlin.app.ui.image.ImageMarkupStroke
import com.virlin.app.ui.image.ImageMarkupTool
import com.virlin.app.ui.image.ImageTransform
import com.virlin.app.ui.image.imageWorkspaceForCapture
import com.virlin.app.ui.image.imageWorkspaceForTask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageWorkspaceContractTest {
    @Test fun taskRoute_requiresStableId() {
        assertEquals("image_workspace/new/task/task-1", imageWorkspaceForTask("task-1"))
        assertThrows(IllegalArgumentException::class.java) { imageWorkspaceForTask("") }
    }

    @Test fun captureRoute_requiresStableId() {
        assertEquals("image_workspace/capture/cap-1", imageWorkspaceForCapture("cap-1"))
        assertThrows(IllegalArgumentException::class.java) { imageWorkspaceForCapture(" ") }
    }

    @Test fun crop_convertsNormalizedCoordinatesToPixels() {
        assertArrayEquals(intArrayOf(100, 400, 900, 1600), ImageCrop(.1f, .2f, .9f, .8f).pixelBounds(1000, 2000))
    }

    @Test fun crop_rejectsEmptyArea() {
        assertThrows(IllegalArgumentException::class.java) { ImageCrop(.5f, 0f, .5f, 1f) }
        assertThrows(IllegalArgumentException::class.java) { ImageCrop(0f, .8f, 1f, .2f) }
    }

    @Test fun adjustments_areBounded() {
        assertEquals(ImageAdjustments(1f, -1f, 1f, -1f), ImageAdjustments(4f, -3f, 2f, -2f).normalized())
    }

    @Test fun transform_normalizesRotationAndStraighten() {
        assertEquals(ImageTransform(1, straightenDegrees = 15f), ImageTransform(5, straightenDegrees = 90f).normalized())
        assertEquals(3, ImageTransform(-1).normalized().rotationQuarterTurns)
    }

    @Test fun markup_preservesNormalizedPathAndText() {
        val stroke = ImageMarkupStroke(ImageMarkupTool.TEXT, 0xff00ff00.toInt(), .02f, .4f, listOf(.2f to .3f), "Review")
        assertEquals("Review", stroke.text)
        assertEquals(.2f to .3f, stroke.points.single())
    }
}
