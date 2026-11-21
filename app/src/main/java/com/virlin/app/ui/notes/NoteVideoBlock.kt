package com.virlin.app.ui.notes

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.virlin.app.domain.notedoc.NoteDocVideo
import com.virlin.app.domain.notedoc.NoteYouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private val Ink: Color @Composable get() = NotesInk
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

private val YouTubeRed = Color(0xFFFF0033)

/** Toolbar glyph: YouTube's rounded play badge, drawn in one colour so the rail can tint it. */
internal val NoteYouTubeGlyph: ImageVector by lazy {
    ImageVector.Builder("YouTube", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(6.5f, 5f); lineTo(17.5f, 5f)
            arcTo(4.5f, 4.5f, 0f, false, true, 22f, 9.5f); lineTo(22f, 14.5f)
            arcTo(4.5f, 4.5f, 0f, false, true, 17.5f, 19f); lineTo(6.5f, 19f)
            arcTo(4.5f, 4.5f, 0f, false, true, 2f, 14.5f); lineTo(2f, 9.5f)
            arcTo(4.5f, 4.5f, 0f, false, true, 6.5f, 5f); close()
            moveTo(10f, 8.8f); lineTo(15.6f, 12f); lineTo(10f, 15.2f); close()
        }
    }.build()
}

/** The red YouTube play badge, used on the card and in the embed panel. */
@Composable
internal fun YouTubeBadge(modifier: Modifier = Modifier, color: Color = YouTubeRed) {
    Canvas(modifier) {
        val r = size.height * 0.28f
        drawRoundRect(color, cornerRadius = CornerRadius(r, r))
        val h = size.height * 0.42f
        val cx = size.width / 2f + h * 0.12f
        val cy = size.height / 2f
        drawPath(Path().apply {
            moveTo(cx - h * 0.45f, cy - h / 2f)
            lineTo(cx + h * 0.55f, cy)
            lineTo(cx - h * 0.45f, cy + h / 2f)
            close()
        }, Color.White)
    }
}

/** Video thumbnails from YouTube's image host, kept in memory while the app runs. */
internal object NoteVideoThumbnails {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(videoId: String): Bitmap? = cache.get(videoId)

    suspend fun load(videoId: String): Bitmap? {
        if (!NoteDocVideo.VIDEO_ID.matches(videoId)) return null
        cache.get(videoId)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL("https://i.ytimg.com/vi/$videoId/hqdefault.jpg").openConnection() as HttpURLConnection
                connection.connectTimeout = 6_000; connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "Virlin/1.0")
                try {
                    if (connection.responseCode != 200) return@runCatching null
                    val bytes = connection.inputStream.use {
                        com.virlin.app.domain.attachment.readBoundedImport(it, 2L * 1024 * 1024)
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                } finally { connection.disconnect() }
            }.getOrNull()?.also { cache.put(videoId, it) }
        }
    }
}

@Composable
private fun VideoThumbnail(videoId: String, modifier: Modifier) {
    var bitmap by remember(videoId) { mutableStateOf(NoteVideoThumbnails.cached(videoId)) }
    LaunchedEffect(videoId) { if (bitmap == null) bitmap = NoteVideoThumbnails.load(videoId) }
    Box(modifier.background(Color(0xFF15171A))) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/**
 * An embedded YouTube clip as it sits in the note: the thumbnail with its clip times and a play
 * button, then the title and channel. Play opens the player in place, on the clip.
 * [onEdit] and [onRemove] are null where the note is read only.
 */
@Composable
internal fun NoteVideoCard(
    video: NoteDocVideo?,
    playing: Boolean,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    val title = video?.title?.takeIf { it.isNotBlank() } ?: "YouTube video"
    // No clip on the card: a hardware video surface can draw behind a clipped Compose layer, so the
    // rounded edge is painted, and the player keeps a plain rectangle inside the padding.
    Column(
        modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(Color.White, shape).border(1.dp, Border, shape).padding(5.dp)
            .semantics {
                contentDescription = if (video == null) "YouTube video that can't be played"
                else "YouTube video: $title, ${NoteYouTube.rangeLabel(video)}"
            }
            .testTag("notes_video_card"),
    ) {
        if (video == null) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF15171A)), contentAlignment = Alignment.Center) {
                Text("This video link is damaged and can't be played", color = Color.White.copy(alpha = .75f),
                    fontSize = 13.sp)
            }
        } else if (playing) {
            NoteVideoPlayer(video, Modifier.fillMaxWidth())
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "Play clip", role = Role.Button, onClick = onPlay)
                    .testTag("notes_video_play"),
            ) {
                VideoThumbnail(video.videoId, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .28f), .35f to Color.Transparent,
                    .7f to Color.Transparent, 1f to Color.Black.copy(alpha = .35f))))
                YouTubeBadge(Modifier.align(Alignment.Center).width(64.dp).height(45.dp))
                Row(
                    Modifier.align(Alignment.TopStart).padding(10.dp)
                        .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(NoteYouTube.rangeLabel(video), color = Color.White, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                clipLength(video)?.let { length ->
                    Text(length, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                            .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 3.dp, top = 10.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            YouTubeBadge(Modifier.width(24.dp).height(17.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(video?.channel?.takeIf { it.isNotBlank() },
                    video?.let { NoteYouTube.rangeLabel(it) }).joinToString("  ·  ")
                if (details.isNotEmpty()) Text(details, color = Muted, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            if (playing) Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onStop)
                    .semantics { contentDescription = "Close player" }
                    .testTag("notes_video_stop"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, null, tint = Muted, modifier = Modifier.size(20.dp)) }
            if (video != null || onRemove != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { menu = true }
                            .semantics { contentDescription = "Video options" }
                            .testTag("notes_video_menu"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.MoreVert, null, tint = Muted, modifier = Modifier.size(20.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (video != null && onEdit != null) DropdownMenuItem(text = { Text("Edit clip times") },
                            onClick = { menu = false; onEdit() }, modifier = Modifier.testTag("notes_video_edit"))
                        if (video != null) DropdownMenuItem(text = { Text("Open in YouTube") }, onClick = {
                            menu = false; openInYouTube(context, video)
                        })
                        if (onRemove != null) DropdownMenuItem(text = { Text("Remove video") },
                            onClick = { menu = false; onRemove() }, modifier = Modifier.testTag("notes_video_remove"))
                    }
                }
            }
        }
    }
}

private fun clipLength(video: NoteDocVideo): String? =
    video.endSeconds?.let { "${NoteYouTube.formatTime(it - video.startSeconds)} clip" }

private fun openInYouTube(context: Context, video: NoteDocVideo) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.watchUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * YouTube's own embed player for [video], inside the note. It opens on the clip's start and
 * YouTube stops it at the end; a small script inside the YouTube page also keeps playback within
 * the clip, so dragging the timeline outside it comes back to the start, and reaching the end
 * rewinds to the start, paused, ready to replay. The page has no bridge to the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun NoteVideoPlayer(video: NoteDocVideo, modifier: Modifier = Modifier) {
    if (!video.isValid) return
    val lifecycleOwner = LocalLifecycleOwner.current
    val fullscreen = remember { VideoFullscreenHost() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var failed by remember(video) { mutableStateOf(false) }
    var status by remember(video) { mutableStateOf<NoteVideoStatus?>(null) }
    val embedUrl = remember(video) { embedUrl(video) }
    val clipScript = remember(video) { clipScript(video) }

    // The control row follows the player by asking it, four times a second, where it is.
    LaunchedEffect(webView, video) {
        while (true) {
            val view = webView ?: break
            view.evaluateJavascript(STATUS_SCRIPT) { raw -> parseVideoStatus(raw)?.let { status = it } }
            delay(250)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
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

    val corner = 10.dp
    val cornerPx = with(LocalDensity.current) { corner.toPx() }
    Column(modifier) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black, RoundedCornerShape(corner))
        .testTag("notes_video_player")) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    // Rounded on the view's own outline, which also clips the hardware video layer.
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, cornerPx)
                        }
                    }
                    clipToOutline = true
                    // A wrap-content WebView lays the page out at zero height, so YouTube sizes its
                    // video to 0px: sound plays with no picture.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setSupportMultipleWindows(false)
                    // The user just pressed Play on the card, so the clip may start on its own.
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            fullscreen.show(context, view, callback)
                        }

                        override fun onHideCustomView() { fullscreen.hide() }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val url = request.url
                            val host = url.host.orEmpty().lowercase()
                            val youtube = host == "youtube.com" || host.endsWith(".youtube.com")
                            if (youtube && url.path.orEmpty().startsWith("/embed/")) return false
                            // The player's title or logo leads to YouTube itself: open it there,
                            // never inside the note. Every other page stays blocked.
                            if (youtube || host == "youtu.be") runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            return true
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(clipScript, null)
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) failed = true
                        }
                    }
                    // YouTube requires an app-identity Referer for Android WebView embeds.
                    loadUrl(embedUrl, mapOf("Referer" to "https://${context.packageName}/"))
                }
            },
        )
        if (failed) Column(
            Modifier.fillMaxSize().background(Color(0xFF15171A), RoundedCornerShape(corner)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("The player couldn't load. Check your connection.", color = Color.White.copy(alpha = .8f), fontSize = 13.sp)
        }
    }
    if (!failed) NoteVideoControls(
        video = video,
        status = status,
        onPlay = {
            val view = webView
            if (view != null) {
                // A finished video starts the clip again rather than doing nothing.
                if (status?.state == 0) view.evaluateJavascript(seekCall(video.startSeconds.toDouble()), null)
                view.evaluateJavascript(PLAY, null)
            }
        },
        onPause = { webView?.evaluateJavascript(PAUSE, null) },
        onSeek = { seconds -> webView?.evaluateJavascript(seekCall(seconds), null) },
    )
    }
}

/**
 * The player's only controls: play/pause, the clip's own timeline (the bar is the clip, not the
 * whole video) and its time. Drawn below the video so YouTube's player is never covered; seeking
 * is off while YouTube shows an advert.
 */
@Composable
private fun NoteVideoControls(
    video: NoteDocVideo,
    status: NoteVideoStatus?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Double) -> Unit,
) {
    val accent = NotesAccent
    val start = video.startSeconds.toDouble()
    val clipEnd = video.endSeconds?.toDouble() ?: status?.duration?.takeIf { it > start }
    val length = clipEnd?.let { it - start }?.takeIf { it > 0 }
    val elapsed = status?.time?.let { (it - start).coerceIn(0.0, length ?: it) } ?: 0.0
    var dragging by remember(video) { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: length?.let { (elapsed / it).toFloat().coerceIn(0f, 1f) } ?: 0f
    val shownElapsed = dragging?.let { f -> length?.let { f * it } } ?: elapsed
    val ad = status?.ad == true
    val playing = status?.playing == true
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 8.dp).testTag("notes_video_controls"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(accent)
                .clickable(enabled = status != null, role = Role.Button) { if (playing) onPause() else onPlay() }
                .semantics { contentDescription = if (playing) "Pause clip" else "Play clip" }
                .testTag("notes_video_toggle"),
            contentAlignment = Alignment.Center,
        ) {
            when {
                status == null || status.buffering ->
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                playing -> Icon(Icons.Rounded.Pause, null, tint = Color.White, modifier = Modifier.size(22.dp))
                else -> Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                val f = dragging
                if (f != null && length != null) onSeek(start + f * length)
                dragging = null
            },
            enabled = length != null && !ad,
            colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Border),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Clip position" }
                .testTag("notes_video_seek"),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (ad) "Ad" else "${NoteYouTube.formatTime(shownElapsed.toInt())} / ${length?.let { NoteYouTube.formatTime(it.toInt()) } ?: "–:––"}",
            color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier.testTag("notes_video_time"),
        )
    }
}

/**
 * The official embed URL for the clip. Only a validated id and two numbers go into it. YouTube's
 * own control bar is off (controls=0, a documented player parameter); the note draws a small
 * control row below the video instead, so nothing ever covers YouTube's player.
 */
internal fun embedUrl(video: NoteDocVideo): String {
    require(video.isValid)
    val end = video.endSeconds?.let { "&end=$it" }.orEmpty()
    return "https://www.youtube.com/embed/${video.videoId}?playsinline=1&controls=0&rel=0&iv_load_policy=3" +
        "&autoplay=1&start=${video.startSeconds}$end"
}

/** What the embedded player reports: YouTube's state code, position and length in seconds. */
internal data class NoteVideoStatus(val state: Int, val time: Double, val duration: Double, val ad: Boolean) {
    val playing get() = state == 1 || state == 3
    val buffering get() = state == 3
}

/** Reads YouTube's own player (or the plain video element) as "state,time,duration,ad". */
internal const val STATUS_SCRIPT = "(function(){var ad=document.querySelector('.ad-showing')?1:0;" +
    "var p=document.getElementById('movie_player');" +
    "if(p&&p.getPlayerState&&p.getCurrentTime)return p.getPlayerState()+','+p.getCurrentTime()+','+(p.getDuration()||0)+','+ad;" +
    "var v=document.querySelector('video');" +
    "if(v)return (v.ended?0:(v.paused?2:1))+','+v.currentTime+','+(v.duration||0)+','+ad;return '';})()"

/** [raw] is evaluateJavascript's JSON result, e.g. "\"1,12.5,300,0\"". Null when unreadable. */
internal fun parseVideoStatus(raw: String?): NoteVideoStatus? {
    val parts = raw?.trim()?.removeSurrounding("\"")?.split(',') ?: return null
    if (parts.size != 4) return null
    val state = parts[0].toIntOrNull() ?: return null
    val time = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val duration = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    return NoteVideoStatus(state, time.coerceAtLeast(0.0), duration.coerceAtLeast(0.0), parts[3] == "1")
}

/** A player command. Only fixed calls and a formatted number ever reach the page. */
private fun playerCall(player: String, video: String) =
    "(function(){var p=document.getElementById('movie_player');var v=document.querySelector('video');" +
        "if(p&&p.playVideo){$player}else if(v){$video}})()"

private val PLAY = playerCall("p.playVideo();", "v.play();")
private val PAUSE = playerCall("p.pauseVideo();", "v.pause();")
private fun seekCall(seconds: Double): String {
    val t = "%.2f".format(java.util.Locale.US, seconds.coerceAtLeast(0.0))
    return playerCall("p.seekTo($t,true);", "v.currentTime=$t;")
}

/**
 * Keeps YouTube's player inside the clip. Runs in the embed page only; reads YouTube's own player
 * (or the plain video element) and leaves adverts alone.
 */
internal fun clipScript(video: NoteDocVideo): String {
    val start = video.startSeconds
    val end = video.endSeconds ?: -1
    return """
        (function(){
          if (window.__virlinClip) return; window.__virlinClip = true;
          var S = $start, E = $end, resetAt = 0;
          setInterval(function(){
            var root = document.querySelector('.html5-video-player');
            if (root && root.classList.contains('ad-showing')) return;
            var p = document.getElementById('movie_player');
            if (!(p && p.getCurrentTime && p.getPlayerState)) p = null;
            var v = document.querySelector('video');
            if (!p && !v) return;
            var t = p ? p.getCurrentTime() : v.currentTime;
            var st = p ? p.getPlayerState() : (v.ended ? 0 : (v.paused ? 2 : 1));
            if (st === 1 && Date.now() - resetAt < 700) { p ? p.pauseVideo() : v.pause(); return; }
            if (st !== 0 && st !== 1 && st !== 2) return;
            if (E > 0 && (st === 0 || t >= E - 0.15)) {
              if (Math.abs(t - S) < 0.5 && st === 2) return;
              resetAt = Date.now();
              if (p) { p.seekTo(S, true); p.pauseVideo(); } else { v.currentTime = S; v.pause(); }
            } else if (st !== 0 && t < S - 1.5) {
              if (p) p.seekTo(S, true); else v.currentTime = S;
            }
          }, 200);
        })();
    """.trimIndent()
}

/** YouTube can move the video into a native view for fullscreen. */
private class VideoFullscreenHost {
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
