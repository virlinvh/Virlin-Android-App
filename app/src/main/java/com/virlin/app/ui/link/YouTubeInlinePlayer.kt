package com.virlin.app.ui.link

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

/**
 * A narrow WebView boundary for YouTube's official embed URL. No user HTML or JS bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun YouTubeInlinePlayer(
    videoId: String,
    startSeconds: Int,
    endSeconds: Int?,
    onReady: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Defense in depth: the domain already validates this, but HTML is never built from an
    // untrusted identifier even if a future caller bypasses LinkPresentation.
    if (!videoId.matches(Regex("[A-Za-z0-9_-]{6,20}"))) {
        onFailed()
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val fullscreen = remember { YouTubeFullscreenHost() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember(videoId, startSeconds, endSeconds) { mutableStateOf(false) }
    val embedUrl = remember(videoId, startSeconds, endSeconds) {
        youtubeEmbedUrl(videoId, startSeconds, endSeconds)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                Lifecycle.Event.ON_DESTROY -> webView?.destroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            fullscreen.hide()
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    LaunchedEffect(videoId, startSeconds, endSeconds, ready) {
        if (!ready) {
            delay(20_000)
            if (!ready) onFailed()
        }
    }

    // Hardware video surfaces may render behind a Compose rounded graphics layer. Keep the
    // player as a plain rectangular Android view; the surrounding page supplies its spacing.
    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            fullscreen.show(context, view, callback)
                        }

                        override fun onHideCustomView() {
                            fullscreen.hide()
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val host = request.url.host.orEmpty().lowercase()
                            // Keep playback in the trusted YouTube frame. Links to channels,
                            // advertisements, sign-in, or arbitrary pages stay blocked here;
                            // the page's explicit Open Link button remains the escape hatch.
                            return host != "www.youtube.com" && host != "youtube.com" &&
                                !host.endsWith(".youtube.com")
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) onFailed()
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame && response.statusCode >= 400) onFailed()
                        }
                        override fun onPageCommitVisible(view: WebView, url: String) {
                            if (!ready) { ready = true; onReady() }
                        }
                    }
                    // YouTube requires an app-identity Referer for Android WebView embeds.
                    loadUrl(embedUrl, mapOf("Referer" to "https://${context.packageName}/"))
                }
            },
        )
    }
}

/** YouTube can move video into a custom native view when fullscreen is requested. */
private class YouTubeFullscreenHost {
    private var dialog: Dialog? = null
    private var callback: WebChromeClient.CustomViewCallback? = null

    fun show(context: Context, view: View, onHidden: WebChromeClient.CustomViewCallback) {
        hide()
        callback = onHidden
        dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen).also { window ->
            window.setContentView(view)
            window.setOnDismissListener {
                if (dialog === window) {
                    dialog = null
                    callback?.onCustomViewHidden()
                    callback = null
                }
            }
            window.show()
        }
    }

    fun hide() {
        val current = dialog ?: return
        dialog = null
        val notify = callback
        callback = null
        current.dismiss()
        notify?.onCustomViewHidden()
    }
}

/** A standard HTTPS embed; time boundaries apply without scripted autoplay. */
internal fun youtubeEmbedUrl(videoId: String, startSeconds: Int, endSeconds: Int?): String {
    require(videoId.matches(Regex("[A-Za-z0-9_-]{6,20}")))
    val start = startSeconds.coerceAtLeast(0)
    val end = endSeconds?.takeIf { it > start }?.let { "&end=$it" }.orEmpty()
    return "https://www.youtube.com/embed/$videoId?playsinline=1&controls=1&start=$start$end"
}
