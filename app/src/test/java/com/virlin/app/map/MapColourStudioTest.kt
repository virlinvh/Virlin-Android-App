package com.virlin.app.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapAppearanceStore
import com.virlin.app.ui.map.MapBorderMode
import com.virlin.app.ui.map.MapBranchMode
import com.virlin.app.ui.map.MapBoxPaletteMode
import com.virlin.app.ui.map.MapBoxStrength
import com.virlin.app.ui.map.luminance
import com.virlin.app.ui.map.mapBoxTints
import com.virlin.app.ui.map.MapFillMode
import com.virlin.app.ui.map.MapHarmony
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapLayout
import com.virlin.app.ui.map.MapSavedPalette
import com.virlin.app.ui.map.MapTopic
import com.virlin.app.ui.map.VirlinMapSnapshot
import com.virlin.app.ui.map.activePalette
import com.virlin.app.ui.map.arrange
import com.virlin.app.ui.map.boxColors
import com.virlin.app.ui.map.blendArgb
import com.virlin.app.ui.map.decodeVirlinMap
import com.virlin.app.ui.map.encodeVirlinMap
import com.virlin.app.ui.map.harmonyPalette
import com.virlin.app.ui.map.levelColor
import com.virlin.app.ui.map.mapBranchTints
import com.virlin.app.ui.map.mapPresetPalettes
import com.virlin.app.ui.map.paintMap
import com.virlin.app.ui.map.parseHexColor
import com.virlin.app.ui.map.parsePaletteText
import com.virlin.app.ui.map.studioPreviewTopics
import com.virlin.app.ui.map.withDefaultColours
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapColourStudioTest {
    private val topics = listOf(
        MapTopic("p", null, "Project", kind = MapKind.PROJECT),
        MapTopic("a", "p", "Alpha", kind = MapKind.WORKSTREAM, order = 0),
        MapTopic("b", "p", "Beta", kind = MapKind.WORKSTREAM, order = 1),
        MapTopic("c", "p", "Gamma", kind = MapKind.WORKSTREAM, order = 2),
        MapTopic("a1", "a", "Alpha task", kind = MapKind.TASK),
        MapTopic("a1x", "a1", "Alpha subtask", kind = MapKind.TASK),
        MapTopic("b1", "b", "Beta task", kind = MapKind.TASK),
    )
    private val geometry = arrange(topics, MapLayout.RIGHT_TREE)

    @Test fun multicolourGivesEveryWorkstreamItsOwnPaletteColour() {
        val palette = MapAppearance().activePalette()
        val tints = mapBranchTints(geometry, MapAppearance())
        assertEquals(palette[0], tints["a"])
        assertEquals(palette[1], tints["b"])
        assertEquals(palette[2], tints["c"])
        assertEquals("tasks follow their workstream", palette[0], tints["a1x"])
        assertEquals(palette[1], tints["b1"])
    }

    @Test fun oneColourAndByLevelModesPaintAsChosen() {
        val single = mapBranchTints(geometry, MapAppearance(branchMode = MapBranchMode.SINGLE, singleBranchColor = 0xFF112233))
        assertEquals(setOf(0xFF112233), (single - "p").values.toSet())
        val levels = MapAppearance(branchMode = MapBranchMode.LEVELS, levelColors = listOf(0xFF000001, 0xFF000002, 0xFF000003))
        val tints = mapBranchTints(geometry, levels)
        assertEquals(0xFF000001, tints["a"]); assertEquals(0xFF000001, tints["c"])
        assertEquals(0xFF000002, tints["a1"]); assertEquals(0xFF000002, tints["b1"])
        assertEquals(0xFF000003, tints["a1x"])
        val deep = MapAppearance(levelColors = listOf(1L, 2L, 3L, 0xFF000004))
        assertEquals("deeper levels share the last row", 0xFF000004, deep.levelColor(7))
    }

    @Test fun branchOverridePaintsItsWholeSubtreeOnly() {
        val tints = mapBranchTints(geometry, MapAppearance(branchColors = mapOf("a" to 0xFFABCDEF)))
        assertEquals(0xFFABCDEF, tints["a"]); assertEquals(0xFFABCDEF, tints["a1x"])
        assertEquals(MapAppearance().activePalette()[1], tints["b"])
        val levelOverride = mapBranchTints(geometry, MapAppearance(branchMode = MapBranchMode.LEVELS,
            branchColors = mapOf("b" to 0xFF445566)))
        assertEquals(0xFF445566, levelOverride["b1"])
    }

    @Test fun lightStrengthKeepsThePreviousLookAndDefaultShowsMoreColour() {
        val tint = 0xFF24A46B.toInt()
        val light = MapAppearance(boxStrength = MapBoxStrength.LIGHT)
        val colours = boxColors(light, tint, customBox = false, selected = false, dimmed = false, handDrawn = false)
        val white = 0xFFFFFFFF.toInt()
        assertArrayEquals(intArrayOf(0xFFFDFFFC.toInt(), blendArgb(white, tint, .05f), blendArgb(white, tint, .14f)), colours.fill)
        assertEquals(tint, colours.stroke); assertEquals(130, colours.strokeAlpha)
        assertNull(colours.handFill); assertNull(colours.handStroke); assertNull(colours.detail)
        val medium = boxColors(MapAppearance(), tint, false, false, false, false)
        assertTrue("default shows the palette more", luminance(medium.fill[1]) < luminance(colours.fill[1]))
        assertTrue("hand-drawn paper takes the palette", medium.handFill != null)
        assertNull(boxColors(MapAppearance(boxBorder = MapBorderMode.NONE), tint, false, false, false, false).stroke)
        val bold = boxColors(MapAppearance(boxFill = MapFillMode.BOLD), 0xFF1D3557.toInt(), false, false, false, false)
        assertTrue("dark boxes get light text", bold.title == 0xFFFAFAFA.toInt())
    }

    @Test fun palettesImportFromPastedTextAndLinks() {
        assertEquals(listOf(0xFF264653, 0xFF2A9D8F, 0xFFE9C46A),
            parsePaletteText("https://coolors.co/264653-2a9d8f-e9c46a"))
        assertEquals(listOf(0xFFF9ED69, 0xFFF08A5D, 0xFFB83B5E, 0xFF6A2C70),
            parsePaletteText("https://colorhunt.co/palette/f9ed69f08a5db83b5e6a2c70"))
        assertEquals(listOf(0xFFFFFFFF, 0xFFAABBCC), parsePaletteText("#FFFFFF, #abc"))
        assertTrue(parsePaletteText("nothing to see here").isEmpty())
        assertEquals(0xFFAABBCC, parseHexColor("#abc"))
        assertNull(parseHexColor("12345"))
    }

    @Test fun harmoniesAreSixDeterministicColours() {
        MapHarmony.entries.forEach { harmony ->
            val first = harmonyPalette(0xFF2A9D8F, harmony)
            assertEquals(6, first.size)
            assertEquals(first, harmonyPalette(0xFF2A9D8F, harmony))
        }
        assertEquals(6, harmonyPalette(0xFFE76F51, MapHarmony.RAINBOW).distinct().size)
    }

    @Test fun previewKeepsRealWorkstreamColourIndices() {
        val many = listOf(MapTopic("p", null, "P", kind = MapKind.PROJECT)) +
            (0 until 7).map { MapTopic("w$it", "p", "W$it", kind = MapKind.WORKSTREAM, order = it) }
        val (shown, indices) = studioPreviewTopics(many, targetId = "w6")
        assertTrue(shown.any { it.id == "w6" })
        assertEquals(6, indices["w6"])
        val g = arrange(shown, MapLayout.RIGHT_TREE, rootBranchIndices = indices)
        assertEquals(MapAppearance().activePalette()[0], mapBranchTints(g, MapAppearance())["w6"])
    }

    private val rich = MapAppearance(
        paletteIndex = 5, customPalette = listOf(0xFF111111, 0xFF222222),
        branchMode = MapBranchMode.LEVELS, singleBranchColor = 0xFF333333, levelColors = listOf(0xFF444444, 0xFF555555),
        boxFill = MapFillMode.CUSTOM, boxFillColor = 0xFF666666, boxBorder = MapBorderMode.CUSTOM,
        boxBorderColor = 0xFF777777, boxTextColor = 0xFF888888, backgroundColor = 0xFF999999,
        savedPalettes = listOf(MapSavedPalette("Mine", listOf(0xFFAAAAAA, 0xFFBBBBBB, 0xFFFFFFFF))),
        boxPaletteMode = MapBoxPaletteMode.LEVEL, boxStrength = MapBoxStrength.STRONG,
        boxLevelColors = mapOf(0 to 0xFF101010, 3 to 0xFF303030), boxSingleColor = 0xFF404040,
        boxBranchColors = mapOf("a" to 0xFF505050), branchPalette = listOf(0xFF606060, 0xFF707070),
    )

    @Test fun thePaletteColoursTheBoxesAndLinesCanStaySeparate() {
        val ocean = MapAppearance(paletteIndex = 5)
        val boxes = mapBoxTints(geometry, ocean)
        val palette = ocean.activePalette()
        assertEquals(palette[0], boxes["a"]); assertEquals(palette[1], boxes["b"]); assertEquals(palette[0], boxes["a1x"])
        val ownLines = ocean.copy(branchPalette = listOf(0xFF010101, 0xFF020202))
        assertEquals("boxes keep the palette", boxes, mapBoxTints(geometry, ownLines))
        assertEquals("lines use their own colours", 0xFF020202, mapBranchTints(geometry, ownLines)["b"])
    }

    @Test fun boxesCanBeColouredByLevelOneColourOrAnyBranch() {
        val levels = MapAppearance(boxPaletteMode = MapBoxPaletteMode.LEVEL, boxLevelColors = mapOf(2 to 0xFF00AA00))
        val tints = mapBoxTints(geometry, levels)
        assertEquals(levels.activePalette()[1], tints["a"])
        assertEquals(0xFF00AA00, tints["a1"]); assertEquals(0xFF00AA00, tints["b1"])
        assertEquals(levels.activePalette()[3], tints["a1x"])
        val single = mapBoxTints(geometry, MapAppearance(boxPaletteMode = MapBoxPaletteMode.SINGLE, boxSingleColor = 0xFF123456))
        assertTrue(single.values.all { it == 0xFF123456 })
        val branch = mapBoxTints(geometry, MapAppearance(boxBranchColors = mapOf("a1" to 0xFFFF0000)))
        assertEquals(0xFFFF0000, branch["a1"]); assertEquals(0xFFFF0000, branch["a1x"])
        assertEquals(MapAppearance().activePalette()[0], branch["a"])
    }

    @Test fun aHighlightStaysOnItsOneBoxAndPaletteChangesShowOnBoxes() {
        val g = arrange(topics.take(2), MapLayout.RIGHT_TREE)
        val box = g.frames.getValue("a")
        fun pixel(appearance: MapAppearance): Int {
            val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE); canvas.translate(100f, 100f)
            paintMap(canvas, g, appearance, RectF(-100f, -100f, 1100f, 500f), null)
            return bitmap.getPixel((100f + box.x + box.w - 14f).toInt(), (100f + box.y + box.h - 10f).toInt())
        }
        assertNotEquals("a new palette repaints the box", pixel(MapAppearance()), pixel(MapAppearance(paletteIndex = 5)))
        assertEquals("highlight ignores the palette",
            pixel(MapAppearance(nodeColors = mapOf("a" to 0xFFE6C26C))),
            pixel(MapAppearance(paletteIndex = 5, nodeColors = mapOf("a" to 0xFFE6C26C))))
        assertNotEquals("the palette tint never becomes the highlight", 0xFFE6C26C,
            mapBoxTints(g, MapAppearance(nodeColors = mapOf("a" to 0xFFE6C26C)))["a"])
    }

    @Test fun colourSettingsSurviveVmapFiles() {
        val restored = decodeVirlinMap(encodeVirlinMap(VirlinMapSnapshot("p", "Project", topics, rich))).appearance
        assertEquals(rich, restored)
        val plain = decodeVirlinMap(encodeVirlinMap(VirlinMapSnapshot("p", "Project", topics, MapAppearance()))).appearance
        assertEquals(MapAppearance(), plain)
    }

    @Test fun colourSettingsSurviveTheAppearanceStore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        MapAppearanceStore(context).update("colour-project") { rich }
        assertEquals(rich, MapAppearanceStore(context).snapshot("colour-project"))
        assertEquals("reset keeps saved palettes", MapAppearance(savedPalettes = rich.savedPalettes), rich.withDefaultColours())
    }

    @Test fun fillBorderAndBackgroundChoicesChangeThePaintedMap() {
        val g = arrange(topics.take(2), MapLayout.RIGHT_TREE)
        val box = g.frames.getValue("a")
        fun render(appearance: MapAppearance): Bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(appearance.backgroundColor?.toInt() ?: android.graphics.Color.WHITE)
            canvas.translate(100f, 100f)
            paintMap(canvas, g, appearance, RectF(-100f, -100f, 1100f, 500f), null)
        }
        val fillX = (100f + box.x + box.w - 14f).toInt(); val fillY = (100f + box.y + box.h - 10f).toInt()
        val edgeX = (100f + box.x + box.w / 2f).toInt(); val edgeY = (100f + box.y).toInt()
        val base = render(MapAppearance())
        assertNotEquals("bold fill", base.getPixel(fillX, fillY), render(MapAppearance(boxFill = MapFillMode.BOLD)).getPixel(fillX, fillY))
        assertNotEquals("custom fill", base.getPixel(fillX, fillY),
            render(MapAppearance(boxFill = MapFillMode.CUSTOM, boxFillColor = 0xFFFF0000)).getPixel(fillX, fillY))
        val border = render(MapAppearance(boxBorder = MapBorderMode.CUSTOM, boxBorderColor = 0xFF0000FF))
        assertNotEquals("custom border", (0 until 4).map { base.getPixel(edgeX, edgeY + it) },
            (0 until 4).map { border.getPixel(edgeX, edgeY + it) })
        assertEquals("background", 0xFF15202B.toInt(), render(MapAppearance(backgroundColor = 0xFF15202B)).getPixel(5, 5))
        assertEquals(13, mapPresetPalettes.size)
    }
}
