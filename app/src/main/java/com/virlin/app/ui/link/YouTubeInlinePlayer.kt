package com.virlin.app.ui.link

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.delay

/**
 * A narrow, trusted WebView boundary for YouTube's official IFrame Player API.
 * It never loads user-provided HTML, grants file/content access, or exposes a JS bridge.
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
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember(videoId, startSeconds, endSeconds) { mutableStateOf(false) }
    val html = remember(videoId, startSeconds, endSeconds) {
        youtubePlayerHtml(videoId, startSeconds.coerceAtLeast(0), endSeconds?.takeIf { it > startSeconds })
    }

    DisposableEffect(lifecycleOwner, webView) {
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
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.destroy()
            webView = null
        }
    }

    LaunchedEffect(videoId, startSeconds, endSeconds, ready) {
        if (!ready) {
            delay(12_000)
            if (!ready) onFailed()
        }
    }

    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(15.dp)).background(Color.Black)) {
        AndroidView(
            modifier = Modifier.matchParentSize(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    setBackgroundColor(android.graphics.Color.BLACK)
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
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (title == "virlin-player-ready" && !ready) {
                                ready = true
                                onReady()
                            }
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

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            val parsed = runCatching { Uri.parse(url) }.getOrNull()
                            val host = parsed?.host.orEmpty().lowercase()
                            if (host.isNotEmpty() && host != "www.youtube.com" && host != "youtube.com" &&
                                !host.endsWith(".youtube.com")) {
                                view.stopLoading(); onFailed()
                            }
                        }
                    }
                    loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
                }
            },
            update = { view ->
                if (view.url == null) view.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "UTF-8", null)
            },
        )
        if (!ready) CircularProgressIndicator(
            modifier = Modifier.align(Alignment.Center).size(30.dp),
            color = Color.White,
            strokeWidth = 2.dp,
        )
    }
}

/** Pure builder so the segment contract can be unit-tested without constructing a WebView. */
internal fun youtubePlayerHtml(videoId: String, startSeconds: Int, endSeconds: Int?): String {
    val end = endSeconds?.takeIf { it > startSeconds }?.let { ",endSeconds:$it" }.orEmpty()
    return """<!doctype html>
<html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>html,body,#player{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head>
<body><div id="player"></div><script src="https://www.youtube.com/iframe_api"></script>
<script>
var player;
function onYouTubeIframeAPIReady(){
  player=new YT.Player('player',{width:'100%',height:'100%',videoId:'$videoId',
    playerVars:{playsinline:1,controls:1,rel:0,fs:1,origin:'https://www.youtube.com'},
    events:{onReady:function(e){e.target.cueVideoById({videoId:'$videoId',startSeconds:$startSeconds$end});document.title='virlin-player-ready';}}
  });
}
</script></body></html>"""
}
