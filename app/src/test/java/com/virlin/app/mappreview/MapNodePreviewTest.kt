package com.virlin.app.mappreview

import com.virlin.app.domain.SequentialIdProvider
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DefaultVirlinActions
import com.virlin.app.domain.action.DomainError
import com.virlin.app.domain.mappreview.FormatMark
import com.virlin.app.domain.mappreview.FormatRun
import com.virlin.app.domain.mappreview.FormattedText
import com.virlin.app.domain.mappreview.FormattedTextEditor
import com.virlin.app.domain.mappreview.MapNodePreview
import com.virlin.app.domain.mappreview.MapPreviewCodec
import com.virlin.app.domain.mappreview.MapPreviewImage
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The mind-map node preview contract.
 *
 * These are the rules that must not depend on the editor: the caps, the ownership key, the
 * emptiness rule and the behaviour of bold/italic runs under ordinary editing. The formatting cases
 * exist because run arithmetic is where this kind of feature silently rots — a bold range that
 * drifts by one character after a backspace is invisible in review and obvious to a user.
 */
class MapNodePreviewTest {

    private val clock = object : VirlinClock {
        override fun now(): Instant = Instant.parse("2026-10-01T09:00:00Z")
    }
    private val repo = InMemoryWorkStreamRepository()
    private val actions = DefaultVirlinActions(repo, clock, SequentialIdProvider())

    private fun image(id: String, order: Int = 0) =
        MapPreviewImage(id = id, relativePath = "node-1/$id.png", width = 100, height = 80, sizeBytes = 900L + order)

    private fun bold(text: String, start: Int, end: Int) =
        FormattedText(text, listOf(FormatRun(start, end, setOf(FormatMark.BOLD))))

    // 1 ------------------------------------------------------------------ stable id ownership

    @Test
    fun preview_isKeyedByTheNodesStableId() = runBlocking {
        val saved = actions.saveMapNodePreview("task-1", FormattedText.plain("Hello"), emptyList())
        assertTrue(saved is ActionResult.Success)
        assertEquals("task-1", (saved as ActionResult.Success).value!!.ownerId)
        assertNotNull(actions.loadMapNodePreview("task-1"))
        // A title is never the key: a different node with the same words has no preview.
        assertNull(actions.loadMapNodePreview("Hello"))
    }

    @Test
    fun blankOwnerId_isRejected() = runBlocking {
        val result = actions.saveMapNodePreview("  ", FormattedText.plain("x"), emptyList())
        assertTrue(result is ActionResult.Rejected)
        assertEquals(DomainError.InvalidPreview, (result as ActionResult.Rejected).reason)
    }

    // 16 ----------------------------------------------------------------- ownership isolation

    @Test
    fun previewOwnership_cannotCrossNodes() = runBlocking {
        actions.saveMapNodePreview("task-a", FormattedText.plain("A"), listOf(image("i1")))
        actions.saveMapNodePreview("task-b", FormattedText.plain("B"), listOf(image("i2")))
        assertEquals("A", actions.loadMapNodePreview("task-a")!!.description.text)
        assertEquals("B", actions.loadMapNodePreview("task-b")!!.description.text)
        // Clearing one leaves the other completely intact.
        actions.clearMapNodePreview("task-a")
        assertNull(actions.loadMapNodePreview("task-a"))
        assertEquals("B", actions.loadMapNodePreview("task-b")!!.description.text)
    }

    // 2, 15 --------------------------------------------------------------- emptiness

    @Test
    fun emptyPreview_isNotStored() = runBlocking {
        val result = actions.saveMapNodePreview("task-1", FormattedText.EMPTY, emptyList())
        assertTrue(result is ActionResult.Success)
        assertNull((result as ActionResult.Success).value)
        assertNull(actions.loadMapNodePreview("task-1"))
    }

    @Test
    fun savingAnEmptyPreview_removesAPreviousOne() = runBlocking {
        actions.saveMapNodePreview("task-1", FormattedText.plain("something"), listOf(image("i1")))
        assertNotNull(actions.loadMapNodePreview("task-1"))
        actions.saveMapNodePreview("task-1", FormattedText.EMPTY, emptyList())
        assertNull(actions.loadMapNodePreview("task-1"))
    }

    @Test
    fun descriptionOnly_andImagesOnly_areBothValid() = runBlocking {
        actions.saveMapNodePreview("a", FormattedText.plain("text"), emptyList())
        actions.saveMapNodePreview("b", FormattedText.EMPTY, listOf(image("i1")))
        assertEquals("text", actions.loadMapNodePreview("a")!!.description.text)
        assertEquals(1, actions.loadMapNodePreview("b")!!.images.size)
    }

    // 3 ------------------------------------------------------------------- length cap

    @Test
    fun descriptionLength_isCappedByTheDomain() = runBlocking {
        val long = "x".repeat(400)
        actions.saveMapNodePreview("task-1", FormattedText.plain(long), emptyList())
        assertEquals(
            MapNodePreview.MAX_DESCRIPTION,
            actions.loadMapNodePreview("task-1")!!.description.text.length,
        )
    }

    @Test
    fun editorRefusesToGrowBeyondTheCap() {
        val full = FormattedText.plain("y".repeat(MapNodePreview.MAX_DESCRIPTION))
        val after = FormattedTextEditor.replace(full, full.text.length, full.text.length, "more")
        assertEquals(MapNodePreview.MAX_DESCRIPTION, after.text.length)
    }

    // 4, 5, 6 ------------------------------------------------------------- run survival

    @Test
    fun boldSurvivesInsertionInsideTheRun() {
        // "abcdef" with "cd" bold; typing "XY" at index 4 lands inside the bold run.
        val source = bold("abcdef", 2, 4)
        val after = FormattedTextEditor.replace(source, 3, 3, "XY")
        assertEquals("abcXYdef", after.text)
        // The inserted text inherits bold from the character to its left, so the run grows.
        assertEquals(setOf(FormatMark.BOLD), after.marksAt(3))
        assertEquals(setOf(FormatMark.BOLD), after.marksAt(4))
        // Text outside is untouched.
        assertTrue(after.marksAt(0).isEmpty())
        assertTrue(after.marksAt(7).isEmpty())
    }

    @Test
    fun boldSurvivesDeletionBeforeTheRun() {
        val source = bold("abcdef", 4, 6)   // "ef" bold
        val after = FormattedTextEditor.replace(source, 0, 2, "")
        assertEquals("cdef", after.text)
        // The run shifted left by exactly the deleted length.
        assertEquals(setOf(FormatMark.BOLD), after.marksAt(2))
        assertEquals(setOf(FormatMark.BOLD), after.marksAt(3))
        assertTrue(after.marksAt(1).isEmpty())
    }

    @Test
    fun deletingAcrossARun_keepsOnlyTheSurvivingPart() {
        val source = bold("abcdef", 2, 5)   // "cde" bold
        val after = FormattedTextEditor.replace(source, 3, 6, "")
        assertEquals("abc", after.text)
        assertEquals(setOf(FormatMark.BOLD), after.marksAt(2))
        assertEquals(3, after.text.length)
    }

    @Test
    fun italicSurvivesReplacement() {
        val source = FormattedText("hello world", listOf(FormatRun(6, 11, setOf(FormatMark.ITALIC))))
        val after = FormattedTextEditor.replace(source, 0, 5, "goodbye")
        assertEquals("goodbye world", after.text)
        assertEquals(setOf(FormatMark.ITALIC), after.marksAt(8))
        assertTrue(after.marksAt(0).isEmpty())
    }

    @Test
    fun boldAndItalicOverlapIsDeterministic() {
        var text = FormattedText.plain("abcdef")
        text = FormattedTextEditor.applyMark(text, 0, 4, FormatMark.BOLD)
        text = FormattedTextEditor.applyMark(text, 2, 6, FormatMark.ITALIC)
        assertEquals(setOf(FormatMark.BOLD), text.marksAt(0))
        assertEquals(setOf(FormatMark.BOLD, FormatMark.ITALIC), text.marksAt(2))
        assertEquals(setOf(FormatMark.BOLD, FormatMark.ITALIC), text.marksAt(3))
        assertEquals(setOf(FormatMark.ITALIC), text.marksAt(4))
        // Applying the marks in the opposite order must give exactly the same result.
        var other = FormattedText.plain("abcdef")
        other = FormattedTextEditor.applyMark(other, 2, 6, FormatMark.ITALIC)
        other = FormattedTextEditor.applyMark(other, 0, 4, FormatMark.BOLD)
        assertEquals(text.normalized().runs, other.normalized().runs)
    }

    @Test
    fun togglingAMixedSelection_turnsItAllOn_thenAllOff() {
        var text = FormattedTextEditor.applyMark(FormattedText.plain("abcdef"), 0, 3, FormatMark.BOLD)
        text = FormattedTextEditor.toggleMark(text, 0, 6, FormatMark.BOLD)
        assertTrue((0 until 6).all { FormatMark.BOLD in text.marksAt(it) })
        text = FormattedTextEditor.toggleMark(text, 0, 6, FormatMark.BOLD)
        assertTrue((0 until 6).none { FormatMark.BOLD in text.marksAt(it) })
    }

    @Test
    fun runsAreNormalisedAndMerged() {
        val messy = FormattedText(
            "abcdef",
            listOf(
                FormatRun(0, 2, setOf(FormatMark.BOLD)),
                FormatRun(2, 4, setOf(FormatMark.BOLD)),   // adjacent, identical marks
                FormatRun(9, 12, setOf(FormatMark.BOLD)),  // out of bounds
                FormatRun(1, 1, setOf(FormatMark.ITALIC)), // empty
            ),
        ).normalized()
        assertEquals(1, messy.runs.size)
        assertEquals(FormatRun(0, 4, setOf(FormatMark.BOLD)), messy.runs.single())
    }

    // 7, 8 ---------------------------------------------------------------- image cap

    @Test
    fun sevenImages_areAccepted() = runBlocking {
        val seven = (1..7).map { image("i$it", it) }
        val result = actions.saveMapNodePreview("task-1", FormattedText.EMPTY, seven)
        assertTrue(result is ActionResult.Success)
        assertEquals(7, actions.loadMapNodePreview("task-1")!!.images.size)
    }

    @Test
    fun eighthImage_isRejectedByTheActionLayer() = runBlocking {
        val eight = (1..8).map { image("i$it", it) }
        val result = actions.saveMapNodePreview("task-1", FormattedText.EMPTY, eight)
        assertTrue(result is ActionResult.Rejected)
        assertEquals(DomainError.InvalidPreview, (result as ActionResult.Rejected).reason)
        // The rejection is total: nothing partial was written.
        assertNull(actions.loadMapNodePreview("task-1"))
    }

    @Test
    fun duplicateImageIds_areRejected() = runBlocking {
        val result = actions.saveMapNodePreview(
            "task-1", FormattedText.EMPTY, listOf(image("same"), image("same")),
        )
        assertTrue(result is ActionResult.Rejected)
    }

    @Test
    fun imageWithNoManagedPath_isRejected() = runBlocking {
        val result = actions.saveMapNodePreview(
            "task-1", FormattedText.EMPTY, listOf(image("i1").copy(relativePath = "")),
        )
        assertTrue(result is ActionResult.Rejected)
    }

    // 9, 10, 11 ------------------------------------------------------------ order and lifecycle

    @Test
    fun imageOrderIsStableAcrossSaveAndLoad() = runBlocking {
        val ordered = listOf(image("c"), image("a"), image("b"))
        actions.saveMapNodePreview("task-1", FormattedText.EMPTY, ordered)
        assertEquals(listOf("c", "a", "b"), actions.loadMapNodePreview("task-1")!!.images.map { it.id })
    }

    @Test
    fun replacingAnImage_preservesItsPosition() = runBlocking {
        actions.saveMapNodePreview("task-1", FormattedText.EMPTY, listOf(image("a"), image("b"), image("c")))
        val current = actions.loadMapNodePreview("task-1")!!.images
        val replaced = current.toMutableList().apply {
            this[1] = MapPreviewImage("b2", "node-1/b2.png", 50, 50, 500L)
        }
        actions.saveMapNodePreview("task-1", FormattedText.EMPTY, replaced)
        assertEquals(listOf("a", "b2", "c"), actions.loadMapNodePreview("task-1")!!.images.map { it.id })
    }

    @Test
    fun deletingAnImage_removesOnlyThatEntry() = runBlocking {
        actions.saveMapNodePreview("task-1", FormattedText.EMPTY, listOf(image("a"), image("b"), image("c")))
        val kept = actions.loadMapNodePreview("task-1")!!.images.filterNot { it.id == "b" }
        actions.saveMapNodePreview("task-1", FormattedText.EMPTY, kept)
        val after = actions.loadMapNodePreview("task-1")!!.images
        assertEquals(listOf("a", "c"), after.map { it.id })
        assertTrue(after.none { it.relativePath.contains("/b.png") })
    }

    // 12 ------------------------------------------------------------------- clear

    @Test
    fun clearingRemovesDescriptionAndEveryImage() = runBlocking {
        actions.saveMapNodePreview("task-1", FormattedText.plain("words"), listOf(image("a"), image("b")))
        val result = actions.clearMapNodePreview("task-1")
        assertTrue(result is ActionResult.Success)
        assertNull(actions.loadMapNodePreview("task-1"))
    }

    // 14 ------------------------------------------------------------------- atomicity

    @Test
    fun saveCommitsDescriptionAndImagesTogether() = runBlocking {
        actions.saveMapNodePreview("task-1", FormattedText.plain("v1"), listOf(image("a")))
        actions.saveMapNodePreview("task-1", FormattedText.plain("v2"), listOf(image("x"), image("y")))
        val loaded = actions.loadMapNodePreview("task-1")!!
        // Never a mix of the old description with the new images.
        assertEquals("v2", loaded.description.text)
        assertEquals(listOf("x", "y"), loaded.images.map { it.id })
    }

    // 17 ------------------------------------------------------------------- codec robustness

    @Test
    fun codecRoundTripsTextRunsAndImages() {
        val preview = MapNodePreview(
            ownerId = "task-1",
            description = FormattedText(
                "bold and italic",
                listOf(
                    FormatRun(0, 4, setOf(FormatMark.BOLD)),
                    FormatRun(9, 15, setOf(FormatMark.ITALIC)),
                ),
            ),
            images = listOf(image("a"), image("b")),
            expanded = true,
        )
        val back = MapPreviewCodec.decode("task-1", MapPreviewCodec.encode(preview))
        assertEquals(preview.description.text, back.description.text)
        assertEquals(preview.description.normalized().runs, back.description.runs)
        assertEquals(listOf("a", "b"), back.images.map { it.id })
        assertTrue(back.expanded)
    }

    @Test
    fun malformedPayloadDecodesToAnEmptyPreview() {
        val back = MapPreviewCodec.decode("task-1", "{not valid json")
        assertTrue(back.isEmpty)
        assertEquals("task-1", back.ownerId)
    }

    @Test
    fun codecDropsImageEntriesWithNoPath() {
        val json = """{"version":1,"text":"","runs":[],"images":[{"id":"a","path":""},{"id":"b","path":"n/b.png"}]}"""
        val back = MapPreviewCodec.decode("task-1", json)
        assertEquals(listOf("b"), back.images.map { it.id })
    }

    @Test
    fun codecReappliesTheImageCapOnRead() {
        val images = (1..12).joinToString(",") { """{"id":"i$it","path":"n/i$it.png"}""" }
        val back = MapPreviewCodec.decode("task-1", """{"version":1,"text":"","images":[$images]}""")
        assertEquals(MapNodePreview.MAX_IMAGES, back.images.size)
    }

    // ---------------------------------------------------------------------- expanded flag

    @Test
    fun expandedPreferenceIsDurableAndDoesNotTouchContent() = runBlocking {
        actions.saveMapNodePreview("task-1", FormattedText.plain("keep"), listOf(image("a")))
        actions.setMapNodePreviewExpanded("task-1", true)
        val loaded = actions.loadMapNodePreview("task-1")!!
        assertTrue(loaded.expanded)
        assertEquals("keep", loaded.description.text)
        assertEquals(1, loaded.images.size)
    }

    @Test
    fun expandingANodeWithNoPreview_isRejected() = runBlocking {
        assertTrue(actions.setMapNodePreviewExpanded("nothing-here", true) is ActionResult.Rejected)
    }

    @Test
    fun isEmpty_reflectsBothFields() {
        assertTrue(MapNodePreview("n").isEmpty)
        assertTrue(MapNodePreview("n", description = FormattedText.plain("   ")).isEmpty)
        assertFalse(MapNodePreview("n", description = FormattedText.plain("a")).isEmpty)
        assertFalse(MapNodePreview("n", images = listOf(image("a"))).isEmpty)
    }
}
