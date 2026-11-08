package com.virlin.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapBoxFinish
import com.virlin.app.ui.map.MapBoxFinishGroup
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapLayout
import com.virlin.app.ui.map.MapTopic
import com.virlin.app.ui.map.arrange
import com.virlin.app.ui.map.paintMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test fun everyFinishPaintsItsBoxFollowsTheColourAndLeavesLinesAlone() {
        val topics = listOf(
            MapTopic("project", null, "Project", kind = MapKind.PROJECT),
            MapTopic("task", "project", "Task", kind = MapKind.TASK),
        )
        val geometry = arrange(topics, MapLayout.RIGHT_TREE)
        val parent = geometry.frames.getValue("project")
        val task = geometry.frames.getValue("task")
        fun render(appearance: MapAppearance, selected: String? = null, g: com.virlin.app.ui.map.MapGeometry = geometry): Bitmap =
            Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.translate(100f, 100f)
                paintMap(canvas, g, appearance, RectF(-100f, -100f, 1100f, 500f), selected)
            }
        val connectorX = (100f + parent.x + parent.w + 5f).toInt()
        val connectorY = (100f + parent.y + parent.h / 2f).toInt()
        val samples = (1..4).flatMap { i -> (1..2).map { k ->
            (100f + task.x + task.w * i / 5f).toInt() to (100f + task.y + task.h * k / 3f).toInt()
        } }
        val blank = render(MapAppearance(), g = arrange(topics.take(1), MapLayout.RIGHT_TREE))
        for (finish in MapBoxFinish.entries) {
            val original = render(MapAppearance(boxFinish = finish))
            val colored = render(MapAppearance(boxFinish = finish, nodeColors = mapOf("task" to 0xFF7582D1)))
            val selected = render(MapAppearance(boxFinish = finish), selected = "task")
            assertEquals("$finish connector", original.getPixel(connectorX, connectorY),
                colored.getPixel(connectorX, connectorY))
            assertTrue("$finish paints the box", samples.any { (x, y) -> original.getPixel(x, y) != blank.getPixel(x, y) })
            assertTrue("$finish follows the box colour", samples.any { (x, y) -> original.getPixel(x, y) != colored.getPixel(x, y) })
            assertTrue("$finish renders without throwing when selected", selected.width == 1200)
            original.recycle(); colored.recycle(); selected.recycle()
        }
        blank.recycle()
    }

    @Test fun everyLayoutDrawsItsBoxesConnectorsAndKeepsTheBoxColour() {
        val topics = listOf(
            MapTopic("project", null, "Project", kind = MapKind.PROJECT),
            MapTopic("a", "project", "Plan", kind = MapKind.WORKSTREAM, order = 0),
            MapTopic("b", "project", "Build", kind = MapKind.WORKSTREAM, order = 1),
            MapTopic("task", "a", "Task", kind = MapKind.TASK),
        )
        for (layout in MapLayout.entries) {
            val geometry = arrange(topics, layout)
            fun render(appearance: MapAppearance, selected: String? = null): Bitmap =
                Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    canvas.translate(100f - geometry.left, 100f - geometry.top)
                    paintMap(canvas, geometry, appearance, RectF(geometry.left - 100f, geometry.top - 100f,
                        geometry.right + 100f, geometry.bottom + 100f), selected)
                }
            val base = MapAppearance(layout = layout)
            val plain = render(base)
            val colored = render(base.copy(nodeColors = mapOf("task" to 0xFF7582D1)))
            val task = geometry.frames.getValue("task")
            val parent = geometry.frames.getValue("a")
            fun px(x: Float, y: Float) = (100f - geometry.left + x).toInt() to (100f - geometry.top + y).toInt()
            val inside = (1..4).flatMap { i -> (1..2).map { k -> px(task.x + task.w * i / 5f, task.y + task.h * k / 3f) } }
            assertTrue("$layout follows the box colour",
                inside.any { (x, y) -> plain.getPixel(x, y) != colored.getPixel(x, y) })
            val midX = (parent.x + parent.w + task.x) / 2f
            val column = (geometry.top.toInt()..geometry.bottom.toInt() step 2).map { y -> px(midX, y.toFloat()) }
            assertTrue("$layout draws a connector between the columns",
                column.any { (x, y) -> plain.getPixel(x, y) != android.graphics.Color.WHITE })
            listOf(render(base, selected = "task"),
                render(base.copy(boxFinish = MapBoxFinish.CRAYON)),
                render(base.copy(boxFinish = MapBoxFinish.KRAFT, boxBorder = com.virlin.app.ui.map.MapBorderMode.NONE)))
                .forEach { it.recycle() }
            plain.recycle(); colored.recycle()
        }
    }

    @Test fun thereAreAtLeastTwentyNewFinishesMostlyHandmade() {
        val handmade = MapBoxFinish.entries.filter { it.group == MapBoxFinishGroup.HANDMADE }
        val material = MapBoxFinish.entries.filter { it.group == MapBoxFinishGroup.MATERIAL }
        assertTrue(handmade.size - 4 >= 16)
        assertTrue(handmade.size - 4 + material.size >= 20)
        assertTrue(handmade.size > material.size)
        assertEquals(MapBoxFinish.entries.size, MapBoxFinish.entries.map { it.label }.toSet().size)
    }
}
