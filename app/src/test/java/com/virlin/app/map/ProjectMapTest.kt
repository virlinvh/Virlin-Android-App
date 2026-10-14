package com.virlin.app.map

import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.ui.map.MapKind
import com.virlin.app.ui.map.MapStatus
import com.virlin.app.ui.map.XmindWriter
import com.virlin.app.ui.map.decodeVirlinMap
import com.virlin.app.ui.map.encodeVirlinMap
import com.virlin.app.ui.map.MapAppearance
import com.virlin.app.ui.map.MapLayout
import com.virlin.app.ui.map.VirlinMapSnapshot
import com.virlin.app.ui.map.projectMapTopics
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipInputStream

/**
 * The map is a projection of the real records. These tests hold it to that: nothing invented,
 * nothing dropped, identity by id rather than title, and an empty branch that never reads as a
 * finished one.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectMapTest {
    @Test fun wideningABoxReflowsItsTextAndEveryLaterColumn() {
        val nodes = listOf(
            com.virlin.app.ui.map.MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            com.virlin.app.ui.map.MapTopic("a", "p",
                "A moderately long task title that should wrap across multiple lines",
                kind = MapKind.TASK),
            com.virlin.app.ui.map.MapTopic("b", "a", "Next step", kind = MapKind.TASK),
        )
        MapLayout.entries.forEach { layout ->
            val original = com.virlin.app.ui.map.arrange(nodes, layout)
            val widened = com.virlin.app.ui.map.arrange(nodes, layout,
                nodeWidths = mapOf("a" to 500f))
            val card = widened.frames.getValue("a")
            val child = widened.frames.getValue("b")
            assertEquals(500f, card.w, .01f)
            assertTrue("$layout did not reflow the text", card.h <= original.frames.getValue("a").h)
            assertTrue("$layout overlaps the widened card", child.x >= card.x + card.w + 28f)
            assertTrue("$layout did not move the next column", child.x > original.frames.getValue("b").x)
        }
    }

    @Test fun expandedPreviewGrowsDownwardWithoutCoveringSiblingCards() {
        val nodes = listOf(
            com.virlin.app.ui.map.MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            com.virlin.app.ui.map.MapTopic("a", "p", "Voice", kind = MapKind.TASK),
            com.virlin.app.ui.map.MapTopic("b", "p", "File", kind = MapKind.TASK),
            com.virlin.app.ui.map.MapTopic("c", "p", "Image", kind = MapKind.TASK),
        )
        val closed = com.virlin.app.ui.map.arrange(nodes, MapLayout.RIGHT_TREE)
        for (id in listOf("b", "c")) {
            val open = com.virlin.app.ui.map.arrange(nodes, MapLayout.RIGHT_TREE, id to 220f)
            assertEquals(closed.frames.getValue(id).y, open.frames.getValue(id).y, .01f)
            val siblings = listOf("a", "b", "c").map { open.frames.getValue(it) }
            siblings.zipWithNext().forEach { (above, below) ->
                assertTrue(above.y + above.h <= below.y)
            }
        }
    }

    @Test fun everyAvailableLayoutKeepsTheHierarchyReadableFromLeftToRight() {
        val nodes = listOf(
            com.virlin.app.ui.map.MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            com.virlin.app.ui.map.MapTopic("a", "p", "Plan", kind = MapKind.WORKSTREAM),
            com.virlin.app.ui.map.MapTopic("b", "p", "Build", kind = MapKind.WORKSTREAM),
            com.virlin.app.ui.map.MapTopic("c", "a", "Draft", kind = MapKind.TASK),
            com.virlin.app.ui.map.MapTopic("d", "a", "Review", kind = MapKind.TASK),
            com.virlin.app.ui.map.MapTopic("e", "b", "Release", kind = MapKind.TASK),
        )
        assertEquals(13, MapLayout.entries.size)
        assertEquals(MapLayout.entries.size,
            MapLayout.entries.map { Triple(it.fork, it.node, it.endpoint) }.distinct().size)
        MapLayout.entries.forEach { layout ->
            val geometry = com.virlin.app.ui.map.arrange(nodes, layout)
            assertEquals(nodes.size, geometry.frames.size)
            nodes.filter { it.parentId != null }.forEach { child ->
                val parent = geometry.frames.getValue(child.parentId!!)
                val frame = geometry.frames.getValue(child.id)
                assertTrue("$layout must flow right at ${child.id}", frame.x > parent.x + parent.w)
            }
            val leaves = listOf("c", "d", "e").map { geometry.frames.getValue(it) }
            leaves.zipWithNext().forEach { (above, below) ->
                assertTrue("$layout overlaps sibling rows", above.y + above.h <= below.y)
            }
        }
    }

    private val now = Instant.parse("2026-09-27T09:00:00Z")
    private val project = Project(id = "p1", title = "Psychology", createdAt = now, updatedAt = now)

    private fun stream(id: String, title: String, state: WorkStreamState = WorkStreamState.READY) =
        WorkStream(
            id = id, title = title, projectId = "p1", state = state,
            createdAt = now, updatedAt = now
        )

    private fun task(
        id: String, title: String, stream: String?, parent: String? = null,
        status: TaskStatus = TaskStatus.TODO, order: Int = 0, projectId: String? = "p1"
    ) = Task(
        id = id, title = title, projectId = projectId, workStreamId = stream,
        parentTaskId = parent, status = status, order = order, createdAt = now, updatedAt = now
    )

    // ------------------------------------------------------------------ projection

    @Test fun everyWorkstreamAppears_includingOneWithNoTasks() {
        val streams = listOf(stream("s1", "Unit 22"), stream("s2", "Unit 23"))
        val topics = projectMapTopics(project, streams, tasks = emptyList())
        assertEquals(setOf("p1", "s1", "s2"), topics.map { it.id }.toSet())
        val empty = topics.single { it.id == "s2" }
        // "No tasks yet" — never 0/0, and never anything that looks complete.
        assertEquals("No tasks yet", empty.detail)
        assertNotEquals(MapStatus.DONE, empty.status)
    }

    @Test fun standaloneProjectTasksBranchFromTheRoot() {
        val topics = projectMapTopics(
            project, streams = emptyList(),
            tasks = listOf(task("t1", "Read the brief", stream = null))
        )
        assertEquals("p1", topics.single { it.id == "t1" }.parentId)
    }

    @Test fun subtasksNestToTheirRealDepth() {
        val streams = listOf(stream("s1", "Unit 22"))
        // A 60-deep chain: the traversal must be iterative, not recursive.
        val chain = (0 until 60).map { i ->
            task("t$i", "Step $i", stream = "s1", parent = if (i == 0) null else "t${i - 1}")
        }
        val topics = projectMapTopics(project, streams, chain)
        assertEquals("s1", topics.single { it.id == "t0" }.parentId)
        assertEquals("t58", topics.single { it.id == "t59" }.parentId)
        assertEquals(62, topics.size)
    }

    @Test fun duplicateTitlesStayDistinctBecauseIdentityIsTheId() {
        val streams = listOf(stream("s1", "Revision"), stream("s2", "Revision"))
        val topics = projectMapTopics(project, streams, emptyList())
        assertEquals(2, topics.count { it.title == "Revision" })
        assertEquals(2, topics.filter { it.title == "Revision" }.map { it.id }.distinct().size)
    }

    @Test fun anotherProjectsWorkIsNotPulledIn() {
        val streams = listOf(stream("s1", "Mine"), stream("s9", "Theirs").copy(projectId = "p2"))
        val tasks = listOf(task("t1", "Mine", "s1"), task("t9", "Theirs", "s9", projectId = "p2"))
        val topics = projectMapTopics(project, streams, tasks)
        assertNull(topics.firstOrNull { it.id == "s9" })
        assertNull(topics.firstOrNull { it.id == "t9" })
    }

    @Test fun cancelledWorkNeverReadsAsDone() {
        val streams = listOf(stream("s1", "Unit 22"))
        val topics = projectMapTopics(
            project, streams,
            listOf(task("t1", "Dropped", "s1", status = TaskStatus.CANCELLED))
        )
        assertNotEquals(MapStatus.DONE, topics.single { it.id == "t1" }.status)
    }

    @Test fun theProjectionIsPureAndRepeatable() {
        val streams = listOf(stream("s1", "Unit 22"))
        val tasks = listOf(task("t1", "A", "s1"), task("t2", "B", "s1", parent = "t1"))
        assertEquals(
            projectMapTopics(project, streams, tasks),
            projectMapTopics(project, streams, tasks)
        )
    }

    // ------------------------------------------------------------------ snapshot codec

    @Test fun aSnapshotSurvivesARoundTrip() {
        val streams = listOf(stream("s1", "Unit 22"))
        val topics = projectMapTopics(project, streams, listOf(task("t1", "A", "s1")))
        val appearance = MapAppearance(
            layout = MapLayout.LOGIC_BRACKETS, paletteIndex = 2,
            nodeColors = mapOf("t1" to 0xFF52A887L), branchColors = mapOf("s1" to 0xFF438FADL),
            nodeWidths = mapOf("t1" to 360f),
        )
        val decoded = decodeVirlinMap(
            encodeVirlinMap(VirlinMapSnapshot("p1", "Psychology", topics, appearance))
        )
        assertEquals(topics, decoded.topics)
        assertEquals(appearance, decoded.appearance)
    }

    @Test fun everyLayoutSurvivesExportAndCanRender() {
        val topics = listOf(
            com.virlin.app.ui.map.MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            com.virlin.app.ui.map.MapTopic("a", "p", "Plan", kind = MapKind.WORKSTREAM),
            com.virlin.app.ui.map.MapTopic("b", "p", "Build", kind = MapKind.WORKSTREAM),
            com.virlin.app.ui.map.MapTopic("c", "a", "Brief", kind = MapKind.TASK),
            com.virlin.app.ui.map.MapTopic("d", "b", "Ship", kind = MapKind.TASK),
        )
        MapLayout.entries.forEach { layout ->
            val appearance = MapAppearance(layout = layout)
            val decoded = decodeVirlinMap(
                encodeVirlinMap(VirlinMapSnapshot("p", "Project", topics, appearance))
            )
            assertEquals(layout, decoded.appearance.layout)
            val geometry = com.virlin.app.ui.map.arrange(decoded.topics, layout)
            val bitmap = android.graphics.Bitmap.createBitmap(1200, 900,
                android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.translate(40f, 120f)
            com.virlin.app.ui.map.paintMap(canvas, geometry,
                appearance, android.graphics.RectF(-40f, -120f, 1160f, 780f), null)
            bitmap.recycle()
        }
    }

    @Test fun layoutPreviewPreservesTheRealProjectBranch() {
        val topics = listOf(
            com.virlin.app.ui.map.MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            com.virlin.app.ui.map.MapTopic("a", "p", "Plan", kind = MapKind.WORKSTREAM),
            com.virlin.app.ui.map.MapTopic("b", "p", "Build", kind = MapKind.WORKSTREAM),
            com.virlin.app.ui.map.MapTopic("c", "a", "Brief", kind = MapKind.TASK),
            com.virlin.app.ui.map.MapTopic("d", "b", "Test", kind = MapKind.TASK),
        )
        val preview = com.virlin.app.ui.map.layoutPreviewTopics(topics)
        assertEquals(topics.map { it.id }.toSet(), preview.map { it.id }.toSet())
        assertEquals(5, com.virlin.app.ui.map.layoutPreviewTopics(topics.take(1)).size)
    }

    @Test fun previewPrefersDistinctBranchNamesWhenProjectHasDuplicates() {
        val topics = listOf(
            com.virlin.app.ui.map.MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            com.virlin.app.ui.map.MapTopic("a", "p", "Claude", kind = MapKind.WORKSTREAM, order = 0),
            com.virlin.app.ui.map.MapTopic("b", "p", "Claude", kind = MapKind.WORKSTREAM, order = 1),
            com.virlin.app.ui.map.MapTopic("c", "p", "Release", kind = MapKind.WORKSTREAM, order = 2),
            com.virlin.app.ui.map.MapTopic("task", "b", "Build", kind = MapKind.TASK),
        )
        val preview = com.virlin.app.ui.map.layoutPreviewTopics(topics)
        assertEquals(listOf("p", "b", "task", "c"), preview.map { it.id })
    }

    @Test fun aSnapshotWithRetiredLayoutFallsBackToRightTreeWithoutLosingTopics() {
        val topics = listOf(
            com.virlin.app.ui.map.MapTopic("p1", null, "Psychology", kind = MapKind.PROJECT),
            com.virlin.app.ui.map.MapTopic("t1", "p1", "Review", kind = MapKind.TASK),
        )
        val bytes = encodeVirlinMap(VirlinMapSnapshot("p1", "Psychology", topics, MapAppearance()))
        val old = bytes.toString(Charsets.UTF_8).replace("RIGHT_TREE", "RADIAL")
        val decoded = decodeVirlinMap(old.toByteArray(Charsets.UTF_8))
        assertEquals(MapLayout.RIGHT_TREE, decoded.appearance.layout)
        assertEquals(topics, decoded.topics)
    }

    @Test fun mapStudioColoursAndFinishesSurvivePortableRoundTrip() {
        val topics = listOf(
            com.virlin.app.ui.map.MapTopic("p", null, "Project", kind = MapKind.PROJECT),
            com.virlin.app.ui.map.MapTopic("t", "p", "Task", kind = MapKind.TASK),
        )
        val appearance = MapAppearance(
            customPalette = listOf(0xFF123456, 0xFFABCDEF),
            savedColors = listOf(0xFF765432),
            boxFinish = com.virlin.app.ui.map.MapBoxFinish.LINEN,
            nodeFinishes = mapOf("t" to com.virlin.app.ui.map.MapBoxFinish.GLASS),
            nodeColors = mapOf("t" to 0xFF765432),
        )
        val decoded = decodeVirlinMap(encodeVirlinMap(VirlinMapSnapshot("p", "Project", topics, appearance)))
        assertEquals(appearance, decoded.appearance)
    }

    @Test fun aSnapshotWithACycleIsRejected() {
        val bytes = encodeVirlinMap(
            VirlinMapSnapshot(
                "p1", "P",
                listOf(
                    com.virlin.app.ui.map.MapTopic("p1", null, "P", kind = MapKind.PROJECT),
                    com.virlin.app.ui.map.MapTopic("a", "b", "A", kind = MapKind.TASK),
                    com.virlin.app.ui.map.MapTopic("b", "a", "B", kind = MapKind.TASK),
                ),
                MapAppearance()
            )
        )
        val failure = runCatching { decodeVirlinMap(bytes) }.exceptionOrNull()
        assertTrue("a cycle must be refused, got $failure", failure is IllegalArgumentException)
    }

    @Test fun aFileThatIsNotAVirlinMapIsRejected() {
        val failure = runCatching { decodeVirlinMap("""{"format":"other"}""".toByteArray()) }
        assertTrue(failure.isFailure)
    }

    // ------------------------------------------------------------------ .xmind

    @Test fun theXmindFileIsARealWorkbookZip() {
        val streams = listOf(stream("s1", "Unit 22"))
        val topics = projectMapTopics(project, streams, listOf(task("t1", "Aå漢字", "s1")))
        val bytes = ByteArrayOutputStream().also { XmindWriter.write(topics, it) }.toByteArray()
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("content.json", "metadata.json", "manifest.json"), entries.keys)
        val sheet = org.json.JSONArray(entries.getValue("content.json")).getJSONObject(0)
        assertEquals("Psychology", sheet.getString("title"))
        val root = sheet.getJSONObject("rootTopic")
        assertEquals("Psychology", root.getString("title"))
        val stream = root.getJSONObject("children").getJSONArray("attached").getJSONObject(0)
        assertEquals("Unit 22", stream.getString("title"))
        // Unicode is preserved exactly.
        val leaf = stream.getJSONObject("children").getJSONArray("attached").getJSONObject(0)
        assertEquals("Aå漢字", leaf.getString("title"))
        // Status travels as a note, since XMind has no Virlin status of its own.
        assertTrue(leaf.getJSONObject("notes").getJSONObject("plain")
            .getString("content").contains("To do"))
    }

    @Test fun duplicateTitlesExportAsSeparateTopics() {
        val streams = listOf(stream("s1", "Revision"), stream("s2", "Revision"))
        val content = XmindWriter.buildContent(projectMapTopics(project, streams, emptyList()))
        val attached = content.getJSONObject(0).getJSONObject("rootTopic")
            .getJSONObject("children").getJSONArray("attached")
        assertEquals(2, attached.length())
        assertNotEquals(
            attached.getJSONObject(0).getString("id"),
            attached.getJSONObject(1).getString("id")
        )
    }

    @Test fun anEmptyBranchExportsWithNoChildrenRatherThanBeingDropped() {
        val content = XmindWriter.buildContent(
            projectMapTopics(project, listOf(stream("s1", "Empty")), emptyList())
        )
        val stream: JSONObject = content.getJSONObject(0).getJSONObject("rootTopic")
            .getJSONObject("children").getJSONArray("attached").getJSONObject(0)
        assertTrue(stream.isNull("children"))
        assertTrue(stream.getJSONObject("notes").getJSONObject("plain")
            .getString("content").contains("No tasks yet"))
    }

    @Test fun exportRefusesAMapWithoutExactlyOneRoot() {
        val failure = runCatching { XmindWriter.buildContent(emptyList()) }.exceptionOrNull()
        assertTrue(failure is XmindWriter.ExportException)
    }

    @Test fun deepChainsExportWithoutRecursion() {
        val chain = (0 until 500).map { i ->
            task("t$i", "Step $i", stream = "s1", parent = if (i == 0) null else "t${i - 1}")
        }
        val topics = projectMapTopics(project, listOf(stream("s1", "Long")), chain)
        // No StackOverflowError, and every node is present.
        val content = XmindWriter.buildContent(topics)
        var node = content.getJSONObject(0).getJSONObject("rootTopic")
        var depth = 0
        while (!node.isNull("children")) {
            node = node.getJSONObject("children").getJSONArray("attached").getJSONObject(0)
            depth++
        }
        assertEquals(501, depth)
    }
}
