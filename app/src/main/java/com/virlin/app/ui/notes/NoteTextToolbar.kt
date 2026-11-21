package com.virlin.app.ui.notes

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * Every line of a note is its own text field, while Compose 1.6.8 shares one floating
 * Copy / Paste toolbar per window: each unfocused field hides it whenever it recomposes and the
 * focused field reopens it as a new window, which blocks the main thread on window creation.
 *
 * A hide is deferred to the next main-looper message and cancelled by a show in the meantime:
 * the last request still wins, but a hide/show pair inside one frame never touches the window.
 */
internal class CoalescingTextToolbar(private val shared: TextToolbar) : TextToolbar {
    private val handler = Handler(Looper.getMainLooper())
    private var hidePending = false
    private val pendingHide = Runnable {
        hidePending = false
        shared.hide()
    }

    override val status: TextToolbarStatus get() = shared.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        cancelHide()
        shared.showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        if (hidePending) return
        hidePending = true
        handler.post(pendingHide)
    }

    /** Runs a still-pending hide now, so it can never close a toolbar opened after the editor. */
    fun flush() {
        if (!hidePending) return
        cancelHide()
        shared.hide()
    }

    private fun cancelHide() {
        if (!hidePending) return
        hidePending = false
        handler.removeCallbacks(pendingHide)
    }
}

@Composable
internal fun rememberCoalescingTextToolbar(): CoalescingTextToolbar {
    val shared = LocalTextToolbar.current
    val toolbar = remember(shared) { CoalescingTextToolbar(shared) }
    DisposableEffect(toolbar) { onDispose { toolbar.flush() } }
    return toolbar
}
