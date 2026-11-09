package com.virlin.app.ui.notes

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.notedoc.NoteDocVideo
import com.virlin.app.domain.notedoc.NoteYouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

private val ErrorRed = Color(0xFFC62828)

/** The YouTube embed panel under the formatting rail. [editing] is the clip being changed, or null. */
class NoteVideoEntryState(
    val key: String?,
    val editing: NoteDocVideo?,
    val onEmbed: (NoteDocVideo) -> Unit,
    val onClose: () -> Unit,
)

/** What YouTube says about a video. Only its public oEmbed page is asked; no account or key. */
internal object NoteYouTubeLookup {
    sealed interface Result {
        data class Found(val title: String?, val channel: String?) : Result
        /** Private, or its owner turned off playing it outside YouTube. */
        data object NotEmbeddable : Result
        data object NotFound : Result
        /** YouTube could not be reached; the clip can still be embedded, without its details. */
        data object Offline : Result
    }

    suspend fun lookup(videoId: String): Result {
        if (!NoteDocVideo.VIDEO_ID.matches(videoId)) return Result.NotFound
        return withContext(Dispatchers.IO) {
            runCatching {
                val target = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
                val connection = URL("https://www.youtube.com/oembed?format=json&url=$target").openConnection() as HttpURLConnection
                connection.connectTimeout = 6_000; connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "Virlin/1.0")
                try {
                    when (connection.responseCode) {
                        200 -> {
                            val body = connection.inputStream.use {
                                String(com.virlin.app.domain.attachment.readBoundedImport(it, 64L * 1024), Charsets.UTF_8)
                            }
                            val json = JSONObject(body)
                            Result.Found(
                                json.optString("title").trim().take(NoteDocVideo.MAX_TEXT).ifEmpty { null },
                                json.optString("author_name").trim().take(NoteDocVideo.MAX_TEXT).ifEmpty { null },
                            )
                        }
                        401, 403 -> Result.NotEmbeddable
                        400, 404 -> Result.NotFound
                        else -> Result.Offline
                    }
                } finally { connection.disconnect() }
            }.getOrElse { Result.Offline }
        }
    }
}

/** A YouTube link waiting on the clipboard, so the panel can open with it already filled in. */
private fun clipboardYouTubeLink(context: Context): String? = runCatching {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    (0 until clip.itemCount).firstNotNullOfOrNull { i ->
        clip.getItemAt(i).text?.toString()?.trim()?.takeIf { NoteYouTube.parse(it) != null }
    }
}.getOrNull()

@Composable
internal fun NoteVideoEntryPanel(state: NoteVideoEntryState, modifier: Modifier = Modifier) {
    key(state.key) { EntryPanel(state, modifier) }
}

@Composable
private fun EntryPanel(state: NoteVideoEntryState, modifier: Modifier) {
    val context = LocalContext.current
    val editing = state.editing
    var link by remember { mutableStateOf(editing?.watchUrl ?: clipboardYouTubeLink(context).orEmpty()) }
    // Start and end hold typed digits only; the fields show them as clock times.
    val linkStart = { text: String -> NoteYouTube.parse(text)?.startSeconds?.takeIf { it > 0 }?.let(NoteYouTube::secondsToClockDigits) }
    var autoStart by remember { mutableStateOf(if (editing == null) linkStart(link) else null) }
    var startText by remember {
        mutableStateOf(editing?.startSeconds?.takeIf { it > 0 }?.let(NoteYouTube::secondsToClockDigits) ?: autoStart.orEmpty())
    }
    // The start follows the link's ?t= until the user types their own start.
    val setLink = { text: String ->
        val next = linkStart(text)
        if (startText.isBlank() || startText == autoStart) startText = next.orEmpty()
        autoStart = next
        link = text
    }
    var endText by remember { mutableStateOf(editing?.endSeconds?.let(NoteYouTube::secondsToClockDigits).orEmpty()) }
    val parsed = remember(link) { NoteYouTube.parse(link) }
    var lookup by remember { mutableStateOf<NoteYouTubeLookup.Result?>(null) }
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(parsed?.videoId) {
        lookup = null; thumbnail = null
        val id = parsed?.videoId ?: return@LaunchedEffect
        if (editing != null && editing.videoId == id && editing.title != null) {
            lookup = NoteYouTubeLookup.Result.Found(editing.title, editing.channel)
        } else {
            delay(300)
            lookup = NoteYouTubeLookup.lookup(id)
        }
        if (lookup is NoteYouTubeLookup.Result.Found) thumbnail = NoteVideoThumbnails.load(id)
    }

    val start = NoteYouTube.clockDigitsSeconds(startText)
    val end = if (endText.isEmpty()) null else NoteYouTube.clockDigitsSeconds(endText)
    val timeError = if (end != null && end <= start) "The end must be after the start" else null
    val found = lookup as? NoteYouTubeLookup.Result.Found
    val canEmbed = parsed != null && timeError == null &&
        (found != null || lookup == NoteYouTubeLookup.Result.Offline)
    val embed = {
        if (canEmbed && parsed != null) state.onEmbed(NoteDocVideo(parsed.videoId, start, end,
            found?.title ?: editing?.title?.takeIf { editing.videoId == parsed.videoId },
            found?.channel ?: editing?.channel?.takeIf { editing.videoId == parsed.videoId }))
    }
    BackHandler { state.onClose() }

    Box(modifier.fillMaxWidth().background(Color.White).testTag("notes_video_entry")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                YouTubeBadge(Modifier.width(28.dp).height(20.dp))
                Spacer(Modifier.width(10.dp))
                LinkField(link, onChange = { setLink(it) }, onPaste = {
                    clipboardYouTubeLink(context)?.let { setLink(it) }
                }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = state.onClose)
                        .semantics { contentDescription = "Close video panel" }
                        .testTag("notes_video_entry_close"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Close, null, tint = Muted, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            LookupRow(link, parsed, lookup, thumbnail)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Start", startText, "0:00", ImeAction.Next, {}, { startText = it }, "notes_video_start")
                Text("–", color = Muted, fontSize = 16.sp)
                TimeField("End", endText, "End", ImeAction.Done, embed, { endText = it }, "notes_video_end")
                Spacer(Modifier.weight(1f))
                val pill = RoundedCornerShape(12.dp)
                Row(
                    Modifier.height(40.dp).clip(pill)
                        .background(if (canEmbed) Green else Border)
                        .clickable(enabled = canEmbed, role = Role.Button, onClick = embed)
                        .semantics { contentDescription = if (editing != null) "Update video clip" else "Embed video" }
                        .testTag("notes_video_embed")
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (editing != null) "Update" else "Embed", color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                when {
                    timeError != null -> timeError
                    end != null -> "Plays ${NoteYouTube.formatTime(start)} – ${NoteYouTube.formatTime(end)} " +
                        "· ${NoteYouTube.formatTime(end - start)} clip"
                    start > 0 -> "Plays from ${NoteYouTube.formatTime(start)} to the end"
                    else -> "Whole video. Type numbers only: 130 is 1:30, 10230 is 1:02:30."
                },
                color = if (timeError != null) ErrorRed else Muted, fontSize = 11.sp, lineHeight = 14.sp,
                maxLines = 2, modifier = Modifier.padding(top = 6.dp, start = 2.dp).testTag("notes_video_clip_label"),
            )
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Border))
    }
}

@Composable
private fun LinkField(value: String, onChange: (String) -> Unit, onPaste: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.height(44.dp).clip(shape)
            .background(if (focused) Mint else Color.White)
            .border(1.dp, if (focused) Green.copy(alpha = .6f) else Border, shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(4000)) },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 14.sp),
            cursorBrush = SolidColor(Green),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text("Paste a YouTube link", color = Muted, fontSize = 14.sp, maxLines = 1)
                    inner()
                }
            },
            modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = "YouTube link" }
                .testTag("notes_video_link"),
        )
        if (value.isEmpty()) Text(
            "Paste", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onPaste)
                .padding(horizontal = 10.dp, vertical = 8.dp).testTag("notes_video_paste"),
        ) else Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { onChange("") }
                .semantics { contentDescription = "Clear link" },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Close, null, tint = Muted, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun LookupRow(link: String, parsed: NoteYouTube.Link?, lookup: NoteYouTubeLookup.Result?, thumbnail: Bitmap?) {
    if (link.isBlank()) return
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("notes_video_status"),
        verticalAlignment = Alignment.CenterVertically) {
        when {
            parsed == null -> Problem("That isn't a YouTube video link.")
            lookup == null -> {
                CircularProgressIndicator(color = Green, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Checking the video…", color = Muted, fontSize = 13.sp)
            }
            lookup is NoteYouTubeLookup.Result.Found -> {
                Box(Modifier.width(88.dp).height(50.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF15171A))) {
                    thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(lookup.title ?: "YouTube video", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("notes_video_title"))
                    lookup.channel?.let { Text(it, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Icon(Icons.Outlined.CheckCircle, "Video found", tint = Green, modifier = Modifier.size(20.dp))
            }
            lookup == NoteYouTubeLookup.Result.NotEmbeddable ->
                Problem("This video is private, or its owner doesn't allow playing it outside YouTube.")
            lookup == NoteYouTubeLookup.Result.NotFound ->
                Problem("Video not found. It may have been removed, or the link is incomplete.")
            else -> Hint("Couldn't reach YouTube to check this video. You can still embed it; its title will be missing.")
        }
    }
}

@Composable private fun RowScope.Hint(text: String) {
    Text(text, color = Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
}

@Composable private fun RowScope.Problem(text: String) {
    Icon(Icons.Outlined.ErrorOutline, null, tint = ErrorRed, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
    Text(text, color = ErrorRed, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
}

@Composable
private fun TimeField(
    label: String,
    value: String,
    placeholder: String,
    imeAction: ImeAction,
    onAction: () -> Unit,
    onChange: (String) -> Unit,
    tag: String,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val shown = NoteYouTube.formatClockDigits(value)
    Column(
        Modifier.width(84.dp).height(44.dp).clip(shape)
            .background(if (focused) Mint else Color.White)
            .border(1.dp, if (focused) Green.copy(alpha = .6f) else Border, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label.uppercase(), color = Muted, fontSize = 8.sp, lineHeight = 10.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = .6.sp, maxLines = 1)
        // Digits fill from the right and the caret stays at the end, so typing 1 3 0 reads 1:30.
        BasicTextField(
            value = TextFieldValue(shown, TextRange(shown.length)),
            onValueChange = { next -> onChange(NoteYouTube.clockDigits(next.text)) },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Green),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
            keyboardActions = KeyboardActions(onNext = { onAction() }, onDone = { onAction() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) Text(placeholder, color = Muted.copy(alpha = .7f), fontSize = 15.sp,
                        textAlign = TextAlign.Center)
                    inner()
                }
            },
            modifier = Modifier.width(76.dp).onFocusChanged {
                // Leaving the field tidies carried-over times: 0:90 becomes 1:30.
                if (focused && !it.isFocused && value.isNotEmpty()) {
                    onChange(NoteYouTube.secondsToClockDigits(NoteYouTube.clockDigitsSeconds(value)))
                }
                focused = it.isFocused
            }
                .semantics { contentDescription = "$label time" }
                .testTag(tag),
        )
    }
}
