package com.virlin.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapBoxFinish
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapLayout
import com.virlin.app.ui.map.MapTopic
import com.virlin.app.ui.map.arrange
import com.virlin.app.ui.map.paintMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapBoxColorRenderTest {
    @Test fun boxOverrideChangesFillButNotItsConnector() {
        val topics = listOf(
            MapTopic("project", null, "Project", kind = MapKind.PROJECT),
            MapTopic("task", "project", "Task", kind = MapKind.TASK),
        )
        val geometry = arrange(topics, MapLayout.RIGHT_TREE)
        val parent = geometry.frames.getValue("project")
        val task = geometry.frames.getValue("task")
        fun render(appearance: MapAppearance): Bitmap =
            Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.translate(100f, 100f)
                paintMap(canvas, geometry, appearance, RectF(-100f, -100f, 1100f, 500f), null)
            }
        val connectorX = (100f + parent.x + parent.w + 5f).toInt()
        val connectorY = (100f + parent.y + parent.h / 2f).toInt()
        val fillX = (100f + task.x + 12f).toInt()
        val fillY = (100f + task.y + 12f).toInt()
        for (finish in listOf(MapBoxFinish.SMOOTH, MapBoxFinish.HAND_DRAWN,
            MapBoxFinish.PENCIL_SKETCH, MapBoxFinish.DOUBLE_INK, MapBoxFinish.MARKER_EDGE)) {
            val original = render(MapAppearance(boxFinish = finish))
            val colored = render(MapAppearance(boxFinish = finish,
                nodeColors = mapOf("task" to 0xFF7582D1)))
            assertEquals("$finish connector", original.getPixel(connectorX, connectorY),
                colored.getPixel(connectorX, connectorY))
            assertNotEquals("$finish box fill", original.getPixel(fillX, fillY),
                colored.getPixel(fillX, fillY))
            original.recycle()
            colored.recycle()
        }
    }
}
