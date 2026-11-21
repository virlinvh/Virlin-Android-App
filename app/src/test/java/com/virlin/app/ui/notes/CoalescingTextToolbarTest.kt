package com.virlin.app.ui.notes

import android.os.Looper
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CoalescingTextToolbarTest {
    private class FakeToolbar : TextToolbar {
        var shows = 0
        var hides = 0
        override var status = TextToolbarStatus.Hidden
        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) { shows++; status = TextToolbarStatus.Shown }
        override fun hide() { hides++; status = TextToolbarStatus.Hidden }
    }

    private val shared = FakeToolbar()
    private val toolbar = CoalescingTextToolbar(shared)

    private fun show() = toolbar.showMenu(Rect.Zero, {}, null, null, null)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun hidesFromOtherFieldsFollowedByAShowNeverCloseTheToolbar() {
        show()
        repeat(5) { toolbar.hide() }
        show()
        idle()
        assertEquals(0, shared.hides)
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
    }

    @Test
    fun aHideWithNoLaterShowStillClosesTheToolbarOnce() {
        show()
        toolbar.hide()
        toolbar.hide()
        assertEquals(TextToolbarStatus.Shown, toolbar.status)
        idle()
        assertEquals(1, shared.hides)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    @Test
    fun flushRunsAPendingHideImmediatelyAndOnlyOnce() {
        show()
        toolbar.hide()
        toolbar.flush()
        assertEquals(1, shared.hides)
        idle()
        assertEquals(1, shared.hides)
    }
}
