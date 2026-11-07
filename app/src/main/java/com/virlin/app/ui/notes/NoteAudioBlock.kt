package com.virlin.app.ui.notes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.virlin.app.data.noteaudio.NoteAudioStore
import com.virlin.app.domain.notedoc.NoteDocAudio
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import kotlin.math.sqrt

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

private val RecordRed = Color(0xFFE5484D)
private val ErrorRed = Color(0xFFC62828)
private const val LIVE_BARS = 36
private const val SAMPLE_MS = 80L
private const val MAX_RECORDING_MS = 60L * 60 * 1000

/**
 * The one player for note recordings, so starting one recording stops any other. It holds a
 * single [MediaPlayer] at a time, keyed by the recording's file name.
 */
internal object NoteAudioPlayback {
    data class Loaded(val key: String, val playing: Boolean)

    var loaded by mutableStateOf<Loaded?>(null)
        private set
    private var player: MediaPlayer? = null

    fun isPlaying(key: String): Boolean = loaded?.let { it.key == key && it.playing } == true

    fun positionMs(key: String): Long =
        if (loaded?.key == key) runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0L else 0L

    /** Plays [file], or pauses it if it is the one playing. False when it can't be played. */
    fun toggle(file: File): Boolean {
        val key = file.name
        val current = loaded
        val p = player
        if (current?.key == key && p != null) {
            runCatching { if (current.playing) p.pause() else p.start() }
            loaded = current.copy(playing = !current.playing)
            return true
        }
        if (!load(file)) return false
        runCatching { player?.start() }
        loaded = Loaded(key, true)
        return true
    }

    /** Moves [file]'s playhead to [fraction] of its length, loading it (paused) if needed. */
    fun seek(file: File, fraction: Float, durationMs: Long) {
        if (loaded?.key != file.name && !load(file)) return
        val p = player ?: return
        val total = runCatching { p.duration.toLong() }.getOrDefault(0L).takeIf { it > 0 } ?: durationMs
        runCatching { p.seekTo((fraction.coerceIn(0f, 1f) * total).toInt()) }
    }

    fun pause() {
        val current = loaded ?: return
        if (!current.playing) return
        runCatching { player?.pause() }
        loaded = current.copy(playing = false)
    }

    /** Lets go of the player; with a [key], only if that recording is the one loaded. */
    fun release(key: String? = null) {
        if (key != null && loaded?.key != key) return
        runCatching { player?.release() }
        player = null
        loaded = null
    }

    private fun load(file: File): Boolean {
        release()
        if (!file.isFile) return false
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.setOnCompletionListener { done ->
                runCatching { done.seekTo(0) }
                if (player === done) loaded = loaded?.copy(playing = false)
            }
            player = p
            loaded = Loaded(file.name, false)
            true
        } catch (_: Exception) {
            p.release()
            false
        }
    }
}

/** Stops note playback when the screen goes away, and pauses it while the app is in the background. */
@Composable
internal fun NoteAudioPlaybackHost() {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) NoteAudioPlayback.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            NoteAudioPlayback.release()
        }
    }
}

/** One microphone recording into the private note-audio store. */
private class NoteVoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var name: String? = null
    private var file: File? = null
    private var startedAt = 0L
    private val levels = ArrayList<Float>()

    val elapsedMs: Long get() = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0L

    fun start() {
        discard()
        val (newName, newFile) = NoteAudioStore.newRecording(context)
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(newFile.absolutePath)
            mr.prepare()
            mr.start()
        } catch (error: Exception) {
            mr.release()
            newFile.delete()
            throw error
        }
        recorder = mr
        name = newName
        file = newFile
        startedAt = SystemClock.elapsedRealtime()
        levels.clear()
    }

    /** The loudness since the last call, 0..1. */
    fun sample(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        val level = sqrt(amplitude / 32767f).coerceIn(0f, 1f)
        levels += level
        return level
    }

    /** Finishes the recording; null when it was too short or couldn't be saved (nothing is kept). */
    fun stop(): NoteDocAudio? {
        val mr = recorder ?: return null
        val elapsed = elapsedMs
        recorder = null
        val stopped = runCatching { mr.stop() }.isSuccess
        mr.release()
        val f = file
        val n = name
        if (!stopped || f == null || n == null || !f.isFile || f.length() < 256 || elapsed < 600) {
            f?.delete(); file = null; name = null
            return null
        }
        val measured = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally { retriever.release() }
        }.getOrNull()?.takeIf { it > 0 }
        return NoteDocAudio(n, (measured ?: elapsed).coerceIn(1, NoteDocAudio.MAX_DURATION_MS),
            NoteDocAudio.encodePeaks(levels))
    }

    /** Throws away whatever is being, or was, recorded and not kept. */
    fun discard() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        name?.let { NoteAudioPlayback.release(it) }
        file?.delete()
        file = null
        name = null
        levels.clear()
    }

    /** The recording now belongs to the note; it is no longer this recorder's to delete. */
    fun keep() {
        file = null
        name = null
    }
}

private sealed interface RecorderPhase {
    data object Ready : RecorderPhase
    data object Recording : RecorderPhase
    data class Preview(val audio: NoteDocAudio) : RecorderPhase
}

/** The voice recorder strip under the formatting rail. [onInsert] puts a finished recording in the note. */
class NoteAudioRecorderState(
    val onInsert: (NoteDocAudio) -> Boolean,
    val onClose: () -> Unit,
)

@Composable
internal fun NoteAudioRecorderPanel(state: NoteAudioRecorderState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recorder = remember { NoteVoiceRecorder(context.applicationContext) }
    var phase by remember { mutableStateOf<RecorderPhase>(RecorderPhase.Ready) }
    var problem by remember { mutableStateOf<String?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var live by remember { mutableStateOf(List(LIVE_BARS) { 0f }) }

    val begin = {
        problem = null
        try {
            recorder.start()
            elapsed = 0L
            live = List(LIVE_BARS) { 0f }
            phase = RecorderPhase.Recording
        } catch (_: Exception) {
            problem = "Couldn't start recording. Another app may be using the microphone."
        }
        Unit
    }
    val finish = {
        val audio = recorder.stop()
        phase = if (audio != null) RecorderPhase.Preview(audio) else {
            problem = "That was too short to keep. Record for at least a second."
            RecorderPhase.Ready
        }
        Unit
    }
    val close = {
        recorder.discard()
        state.onClose()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) begin() else problem = "Virlin needs the microphone to record audio."
    }
    val record = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(phase) {
        if (phase != RecorderPhase.Recording) return@LaunchedEffect
        while (isActive) {
            delay(SAMPLE_MS)
            live = live.drop(1) + recorder.sample()
            elapsed = recorder.elapsedMs
            if (elapsed >= MAX_RECORDING_MS) { finish(); break }
        }
    }
    // The microphone is only ours while the note is on screen; what was recorded so far is kept.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && phase == RecorderPhase.Recording) finish()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { recorder.discard() } }
    BackHandler { close() }

    Box(modifier.fillMaxWidth().background(Color.White).testTag("notes_audio_recorder")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            when (val p = phase) {
                RecorderPhase.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(RecordRed, "Start recording", "notes_audio_record", onClick = record) {
                        Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Record audio", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Tap the red button to start. It's added where your cursor is.",
                            color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    CloseButton("Close recorder", "notes_audio_close", close)
                }
                RecorderPhase.Recording -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RecordingDot()
                    Spacer(Modifier.width(8.dp))
                    Text(NoteDocAudio.formatDuration(elapsed), color = Ink, fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = "tnum"),
                        modifier = Modifier.width(52.dp).testTag("notes_audio_timer")
                            .semantics { contentDescription = "Recording, ${NoteDocAudio.formatDuration(elapsed)}" })
                    Waveform(live, progress = 1f, played = RecordRed, rest = RecordRed,
                        modifier = Modifier.weight(1f).height(32.dp))
                    Spacer(Modifier.width(10.dp))
                    RoundButton(RecordRed, "Stop recording", "notes_audio_stop", onClick = finish) {
                        Box(Modifier.size(16.dp).background(Color.White, RoundedCornerShape(4.dp)))
                    }
                    CloseButton("Discard recording", "notes_audio_cancel") {
                        recorder.discard()
                        phase = RecorderPhase.Ready
                    }
                }
                is RecorderPhase.Preview -> {
                    val file = remember(p.audio.file) { NoteAudioStore.resolve(context, p.audio.file) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AudioPlayerRow(p.audio, file, Modifier.weight(1f), playTag = "notes_audio_preview_play")
                        CloseButton("Discard recording and close", "notes_audio_close", close)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { recorder.discard(); phase = RecorderPhase.Ready }
                                .semantics { contentDescription = "Discard and record again" }
                                .testTag("notes_audio_rerecord")
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Refresh, null, tint = Muted, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Record again", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(Green)
                                .clickable(role = Role.Button) {
                                    NoteAudioPlayback.release(p.audio.file)
                                    if (state.onInsert(p.audio)) recorder.keep()
                                }
                                .semantics { contentDescription = "Insert recording into the note" }
                                .testTag("notes_audio_insert")
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Insert", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            problem?.let { message ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = ErrorRed, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                    if (permissionDenied) Text(
                        "Open settings", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Border))
    }
}

/**
 * A recording as it sits in the note: play, its loudness outline (tap or drag to seek) and its
 * time. [onRemove] is null where the note is read only.
 */
@Composable
internal fun NoteAudioCard(audio: NoteDocAudio?, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val context = LocalContext.current
    val file = remember(audio?.file) { audio?.let { NoteAudioStore.resolve(context, it.file) }?.takeIf { it.isFile } }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(Color.White, shape).border(1.dp, Border, shape)
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 2.dp)
            .semantics(mergeDescendants = false) {
                contentDescription = when {
                    audio == null || file == null -> "Recording that can't be played"
                    audio.title != null -> "Spoken audio: ${audio.title}, ${NoteDocAudio.formatDuration(audio.durationMs)}"
                    else -> "Voice recording, ${NoteDocAudio.formatDuration(audio.durationMs)}"
                }
            }
            .testTag("notes_audio_card"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (audio == null || file == null) {
            Box(Modifier.size(44.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, null, tint = Muted, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("This recording is missing or damaged and can't be played.", color = Muted, fontSize = 13.sp,
                lineHeight = 17.sp, modifier = Modifier.weight(1f))
        } else {
            AudioPlayerRow(audio, file, Modifier.weight(1f), playTag = "notes_audio_play", showLabel = true)
        }
        if (onRemove != null) {
            var menu by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { menu = true }
                        .semantics { contentDescription = "Recording options" }
                        .testTag("notes_audio_menu"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.MoreVert, null, tint = Muted, modifier = Modifier.size(20.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Remove recording") }, onClick = {
                        menu = false
                        audio?.let { NoteAudioPlayback.release(it.file) }
                        onRemove()
                    }, modifier = Modifier.testTag("notes_audio_remove"))
                }
            }
        }
    }
}

/** Play / pause, the seekable loudness outline and the time, for a recording on disk. */
@Composable
internal fun AudioPlayerRow(
    audio: NoteDocAudio,
    file: File?,
    modifier: Modifier,
    playTag: String,
    showLabel: Boolean = false,
) {
    val key = audio.file
    val loaded = NoteAudioPlayback.loaded
    val isLoaded = loaded?.key == key
    val playing = isLoaded && loaded?.playing == true
    var position by remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(isLoaded, playing) {
        if (!isLoaded) { position = 0L; return@LaunchedEffect }
        position = NoteAudioPlayback.positionMs(key)
        while (isActive && NoteAudioPlayback.isPlaying(key)) {
            position = NoteAudioPlayback.positionMs(key)
            delay(50)
        }
        position = NoteAudioPlayback.positionMs(key)
    }
    val levels = remember(audio.peaks) {
        NoteDocAudio.decodePeaks(audio.peaks).ifEmpty { List(NoteDocAudio.PEAK_COUNT) { .2f } }
    }
    val duration = audio.durationMs.coerceAtLeast(1)
    val progress = (position.toFloat() / duration).coerceIn(0f, 1f)
    val seek = { fraction: Float ->
        if (file != null) {
            NoteAudioPlayback.seek(file, fraction, duration)
            position = (fraction.coerceIn(0f, 1f) * duration).toLong()
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Green, if (playing) "Pause recording" else "Play recording", playTag, size = 44,
            enabled = file != null, onClick = { if (file != null) NoteAudioPlayback.toggle(file) }) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White,
                modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (showLabel) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (audio.title != null) Icons.Rounded.RecordVoiceOver else Icons.Rounded.Mic, null,
                    tint = Green, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(audio.title ?: "Voice recording", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                TimeLabel(isLoaded, position, duration)
            }
            Waveform(
                levels, progress, played = Green, rest = Border,
                modifier = Modifier.fillMaxWidth().height(if (showLabel) 30.dp else 34.dp)
                    .pointerInput(file) { detectTapGestures { seek(it.x / size.width) } }
                    .pointerInput(file) {
                        detectHorizontalDragGestures { change, _ -> change.consume(); seek(change.position.x / size.width) }
                    }
                    .semantics {
                        contentDescription = "Seek recording"
                        stateDescription = "${NoteDocAudio.formatDuration(position)} of ${NoteDocAudio.formatDuration(duration)}"
                    }
                    .testTag("notes_audio_wave"),
            )
            if (!showLabel) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TimeLabel(isLoaded, position, duration)
            }
        }
    }
}

@Composable
private fun TimeLabel(isLoaded: Boolean, position: Long, duration: Long) {
    Text(
        if (isLoaded) "${NoteDocAudio.formatDuration(position)} / ${NoteDocAudio.formatDuration(duration)}"
        else NoteDocAudio.formatDuration(duration),
        color = Muted, fontSize = 11.sp, style = TextStyle(fontFeatureSettings = "tnum"),
    )
}

/** Loudness bars; the first [progress] of them in [played], the rest in [rest]. */
@Composable
private fun Waveform(levels: List<Float>, progress: Float, played: Color, rest: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = levels.size
        if (n == 0) return@Canvas
        val gap = 2.dp.toPx()
        val bar = ((size.width - gap * (n - 1)) / n).coerceIn(1f, 4.dp.toPx())
        val step = (size.width - bar) / (n - 1).coerceAtLeast(1)
        val mid = size.height / 2f
        val tallest = size.height - bar
        levels.forEachIndexed { i, level ->
            val x = bar / 2f + i * step
            val h = ((.12f + .88f * level.coerceIn(0f, 1f)) * tallest).coerceAtLeast(1f)
            drawLine(if ((i + .5f) / n <= progress) played else rest, Offset(x, mid - h / 2f), Offset(x, mid + h / 2f),
                strokeWidth = bar, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun RecordingDot() {
    val pulse = rememberInfiniteTransition(label = "recording").animateFloat(
        1f, .25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse.value }.background(RecordRed, CircleShape))
}

@Composable
private fun RoundButton(
    color: Color,
    description: String,
    tag: String,
    size: Int = 48,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (enabled) color else color.copy(alpha = .35f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun CloseButton(description: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 4.dp).size(40.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Outlined.Close, null, tint = Muted, modifier = Modifier.size(20.dp)) }
}
