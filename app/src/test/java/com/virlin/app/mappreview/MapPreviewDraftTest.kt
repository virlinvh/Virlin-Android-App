package com.virlin.app.mappreview

import com.virlin.app.domain.mappreview.FormatMark
import com.virlin.app.domain.mappreview.MapNodePreview
import com.virlin.app.domain.mappreview.MapPreviewImage
import com.virlin.app.ui.map.preview.MapPreviewDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's working copy.
 *
 * The draft is where "Cancel leaves nothing behind" and "Replace keeps its position" are actually
 * decided, so they are tested here rather than inferred from the popup.
 */
class MapPreviewDraftTest {

    private fun image(id: String) =
        MapPreviewImage(id = id, relativePath = "owner/$id.png", width = 10, height = 10, sizeBytes = 100)

    private fun draftWith(vararg ids: String) =
        MapPreviewDraft(ownerId = "owner", images = ids.map(::image))

    @Test
    fun addingImages_appendsInOrder_andSelectsTheNewOne() {
        val draft = MapPreviewDraft("owner").addImage(image("a")).addImage(image("b"))
        assertEquals(listOf("a", "b"), draft.images.map { it.id })
        assertEquals(1, draft.activeImage)
        assertEquals("2 / 7", draft.counter)
    }

    @Test
    fun addingStopsAtSeven() {
        var draft = MapPreviewDraft("owner")
        repeat(MapNodePreview.MAX_IMAGES) { draft = draft.addImage(image("i$it")) }
        assertFalse(draft.canAddImage)
        val refused = draft.addImage(image("eighth"))
        assertEquals(MapNodePreview.MAX_IMAGES, refused.images.size)
        assertTrue(refused.images.none { it.id == "eighth" })
    }

    @Test
    fun replacingActive_keepsPosition_andQueuesTheOldFile() {
        val draft = draftWith("a", "b", "c").select(1).replaceActive(image("b2"))
        assertEquals(listOf("a", "b2", "c"), draft.images.map { it.id })
        // The old bytes are only QUEUED; nothing is deleted until the save commits.
        assertEquals(listOf("owner/b.png"), draft.removedPaths)
    }

    @Test
    fun deletingActive_selectsTheNearestRemaining() {
        val draft = draftWith("a", "b", "c").select(2).deleteActive()
        assertEquals(listOf("a", "b"), draft.images.map { it.id })
        assertEquals(1, draft.activeImage)
        assertEquals(listOf("owner/c.png"), draft.removedPaths)
    }

    @Test
    fun deletingTheOnlyImage_leavesAValidSelection() {
        val draft = draftWith("a").deleteActive()
        assertTrue(draft.images.isEmpty())
        assertEquals(0, draft.activeImage)
        assertEquals(null, draft.active)
    }

    @Test
    fun deletingAnImageAddedThisSession_removesItFromTheCleanupList() {
        // It was never committed, so cancelling later must not try to delete it twice.
        val draft = MapPreviewDraft("owner").addImage(image("fresh")).deleteActive()
        assertTrue(draft.addedImages.isEmpty())
    }

    @Test
    fun clearingEmptiesEverythingAndQueuesEveryFile() {
        val draft = draftWith("a", "b").withText("words").cleared()
        assertTrue(draft.isEmpty)
        assertTrue(draft.images.isEmpty())
        assertEquals(listOf("owner/a.png", "owner/b.png"), draft.removedPaths)
    }

    @Test
    fun cancelCleanupListContainsExactlyTheSessionsImports() {
        // Loaded images are NOT in addedImages, so cancelling never deletes previously saved bytes.
        val saved = MapNodePreview("owner", images = listOf(image("old")))
        val draft = MapPreviewDraft.of("owner", saved).addImage(image("new"))
        assertEquals(listOf("new"), draft.addedImages.map { it.id })
    }

    @Test
    fun typingAfterABoldRun_continuesBold() {
        // Inheriting from the character to the LEFT is what every editor does: continuing to type
        // at the end of a bold word stays bold until the user turns it off.
        var draft = MapPreviewDraft("owner").withText("hello")
        draft = draft.toggleMark(FormatMark.BOLD, 0, 5)
        draft = draft.withText("hello world")
        assertTrue((0 until 11).all { FormatMark.BOLD in draft.description.marksAt(it) })
    }

    @Test
    fun typingBeforeARun_doesNotInheritIt() {
        // There is nothing to the left of position 0, so the text starts plain and the original
        // run is pushed along rather than swallowing the new characters.
        var draft = MapPreviewDraft("owner").withText("bold")
        draft = draft.toggleMark(FormatMark.BOLD, 0, 4)
        draft = draft.withText("not bold")
        assertTrue(draft.description.marksAt(0).isEmpty())
        assertTrue(FormatMark.BOLD in draft.description.marksAt(7))
    }

    @Test
    fun editingInTheMiddle_leavesTextOnBothSidesIntact() {
        var draft = MapPreviewDraft("owner").withText("abcdef")
        draft = draft.toggleMark(FormatMark.BOLD, 2, 4)      // "cd" bold
        draft = draft.withText("abXcdef")                     // insert before the run
        assertEquals("abXcdef", draft.description.text)
        assertTrue(draft.description.marksAt(2).isEmpty())    // the inserted char is plain
        assertTrue(FormatMark.BOLD in draft.description.marksAt(3))
        assertTrue(FormatMark.BOLD in draft.description.marksAt(4))
        assertTrue(draft.description.marksAt(5).isEmpty())
    }

    @Test
    fun armingAMarkWithNoSelection_appliesItToTextTypedNext() {
        var draft = MapPreviewDraft("owner").withText("ab")
        draft = draft.toggleMark(FormatMark.BOLD, 2, 2)   // caret, no selection
        assertTrue(FormatMark.BOLD in draft.pendingMarks)
        draft = draft.withText("abcd")
        assertTrue(FormatMark.BOLD in draft.description.marksAt(2))
        assertTrue(draft.description.marksAt(0).isEmpty())
    }

    @Test
    fun toolbarStateFollowsTheSelection() {
        var draft = MapPreviewDraft("owner").withText("abcdef")
        draft = draft.toggleMark(FormatMark.BOLD, 0, 3)
        assertTrue(draft.isActive(FormatMark.BOLD, 0, 3))
        assertFalse(draft.isActive(FormatMark.BOLD, 3, 6))
        // A selection that is only partly bold does not read as bold.
        assertFalse(draft.isActive(FormatMark.BOLD, 0, 6))
    }

    @Test
    fun characterCounterReflectsTheCap() {
        val draft = MapPreviewDraft("owner").withText("abc")
        assertEquals("3 / 180", draft.characterCounter)
    }

    @Test
    fun theDraftNeverExceedsTheCharacterCap() {
        val draft = MapPreviewDraft("owner").withText("z".repeat(500))
        assertEquals(MapNodePreview.MAX_DESCRIPTION, draft.description.text.length)
    }

    @Test
    fun loadingFromAnAbsentPreviewGivesAnEmptyDraft() {
        val draft = MapPreviewDraft.of("owner", null)
        assertTrue(draft.isEmpty)
        assertEquals("0 / 7", draft.counter)
        assertTrue(draft.canAddImage)
    }
}
