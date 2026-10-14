package com.virlin.app.map

import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapExportWriter
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapTopic
import com.virlin.app.ui.map.VirlinMapSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapExportWriterTest {
    private val topics = listOf(
        MapTopic("project", null, "My Project", kind = MapKind.PROJECT),
        MapTopic("stream", "project", "Design", kind = MapKind.WORKSTREAM),
        MapTopic("task", "stream", "Sketch *ideas*", kind = MapKind.TASK),
    )

    @Test fun markdownKeepsHierarchyAndEscapesFormatting() {
        assertEquals(
            "# My Project\n\n- Design\n  - Sketch \\*ideas\\*\n",
            MapExportWriter.markdown(topics),
        )
    }

    @Test fun jsonIsReadableVersionedSnapshot() {
        val output = ByteArrayOutputStream()
        MapExportWriter.writeJson(
            VirlinMapSnapshot("project", "My Project", topics, MapAppearance()), output,
        )
        val text = output.toString("UTF-8")
        assertTrue(text.contains("\n"))
        val json = JSONObject(text)
        assertEquals("virlin-map", json.getString("format"))
        assertEquals(3, json.getJSONArray("topics").length())
    }

    @Test fun pngHasImageSignature() {
        val output = ByteArrayOutputStream()
        MapExportWriter.writePng(topics, MapAppearance(), output)
        assertTrue(output.size() > 100)
        assertEquals("89504e470d0a1a0a", output.toByteArray().take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

}
