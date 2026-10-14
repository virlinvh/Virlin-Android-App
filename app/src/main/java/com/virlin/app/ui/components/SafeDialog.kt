package com.virlin.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Dialog windows have their own insets; activity Scaffold padding cannot protect them. */
@Composable
fun SafeDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    scrollContent: Boolean = true,
    fullScreen: Boolean = false,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = true,
            // Compose 1.6's non-default-width dialog measures from configuration screen size.
            // Let insets reach Compose instead of relying on decor fitting to reduce that size.
            decorFitsSystemWindows = false,
        ),
    ) {
        var cardBounds by remember { mutableStateOf(Rect.Zero) }
        val view = LocalView.current
        val context = LocalContext.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val previous = window?.attributes?.softInputMode
            // One owner for keyboard avoidance: this full-height window stays stable while
            // Compose reserves the IME below. ADJUST_RESIZE plus IME padding subtracts the
            // keyboard twice on OEM devices and can collapse a form to its header.
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            onDispose { previous?.let { window?.setSoftInputMode(it) } }
        }
        // Older Compose Dialog windows sometimes report zero Compose insets on OEM/overridden
        // displays. Preserve the owner's native system-bar inset as a fallback, not a fixed gap.
        val ownerView = remember(context) { context.findActivity()?.window?.decorView }
        fun readInsets(): Insets {
            val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            val dialogRoot = ViewCompat.getRootWindowInsets(view)
            val ownerRoot = ownerView?.let { ViewCompat.getRootWindowInsets(it) }
            val dialog = dialogRoot?.getInsets(types)
            val owner = ownerRoot?.getInsets(types)
            val ime = maxOf(dialogRoot?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0,
                ownerRoot?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
            val activity = context.findActivity()
            val displayHeight = if (android.os.Build.VERSION.SDK_INT >= 30) {
                activity?.windowManager?.currentWindowMetrics?.bounds?.height() ?: view.rootView.height
            } else {
                @Suppress("DEPRECATION")
                val metrics = android.util.DisplayMetrics().also { activity?.windowManager?.defaultDisplay?.getRealMetrics(it) }
                metrics.heightPixels.takeIf { it > 0 } ?: view.rootView.height
            }
            // Android 15/OEMs may inset the parent frame by IME regardless of softInputMode.
            // Reserve only the remainder; root height is the actual native viewport.
            val remainingIme = remainingDialogIme(ime, displayHeight, view.rootView.height)
            return Insets.of(
                maxOf(dialog?.left ?: 0, owner?.left ?: 0),
                maxOf(dialog?.top ?: 0, owner?.top ?: 0),
                maxOf(dialog?.right ?: 0, owner?.right ?: 0),
                maxOf(dialog?.bottom ?: 0, owner?.bottom ?: 0, remainingIme),
            )
        }
        var nativeInsets by remember(view, ownerView) { mutableStateOf(readInsets()) }
        DisposableEffect(view, ownerView) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                // DialogWrapper.updateParameters can restore ADJUST_RESIZE on Android < 12
                // after our initial effect. Enforce the invariant after native layout too.
                if (window != null && window.attributes.softInputMode and
                    WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) {
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                }
                nativeInsets = readInsets()
            }
            view.viewTreeObserver.addOnGlobalLayoutListener(listener)
            ownerView?.viewTreeObserver?.addOnGlobalLayoutListener(listener)
            onDispose {
                view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                ownerView?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
            }
        }
        // Reserve cutouts, both navigation modes and the keyboard in THIS window.
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets(nativeInsets.left, nativeInsets.top, nativeInsets.right, nativeInsets.bottom)
            ).padding(if (fullScreen) 0.dp else 16.dp)
                .pointerInput(onDismissRequest, properties.dismissOnClickOutside) {
                    detectTapGestures { position ->
                        if (properties.dismissOnClickOutside && !cardBounds.contains(position)) onDismissRequest()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                (if (fullScreen) Modifier.fillMaxSize() else Modifier.widthIn(max = 560.dp).heightIn(max = maxHeight))
                    .onGloballyPositioned { cardBounds = it.boundsInParent() }
                    .then(if (scrollContent && !fullScreen)
                        Modifier.clip(RoundedCornerShape(24.dp)).verticalScroll(rememberScrollState()) else Modifier),
            ) { content() }
        }
    }
}

internal fun remainingDialogIme(ime: Int, displayHeight: Int, viewportHeight: Int): Int {
    val excluded = if (ime > 0 && viewportHeight > 0)
        (displayHeight - viewportHeight).coerceAtLeast(0) else 0
    return (ime - excluded).coerceAtLeast(0)
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.findActivity()
    else -> null
}
