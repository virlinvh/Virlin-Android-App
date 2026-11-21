package com.virlin.app.ui.notes

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.virlin.app.data.noteaudio.NoteAudioStore
import com.virlin.app.domain.notedoc.NoteDocAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Mint: Color @Composable get() = NotesWash
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

private val ErrorRed = Color(0xFFC62828)
internal const val MAX_SPEECH_TEXT = 20_000

/**
 * Splits [text] into pieces of at most [max] characters for the speech engine, breaking after a
 * sentence or line where possible, then at a space, and only mid-word as a last resort.
 */
internal fun speechChunks(text: String, max: Int): List<String> {
    val chunks = ArrayList<String>()
    var rest = text.trim()
    while (rest.length > max) {
        val window = rest.substring(0, max)
        var cut = window.lastIndexOfAny(listOf(". ", "! ", "? ", "\n")).let { if (it >= 0) it + 1 else -1 }
        if (cut < max / 3) cut = window.lastIndexOf(' ').takeIf { it >= max / 3 } ?: max
        chunks += rest.substring(0, cut).trim()
        rest = rest.substring(cut).trim()
    }
    if (rest.isNotEmpty()) chunks += rest
    return chunks.filter { it.isNotBlank() }
}

/** The phone's own text-to-speech engine, writing speech to files. Nothing is downloaded or bundled. */
private class NoteSpeech(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { utteranceId?.let { pending.remove(it)?.complete(false) } }
        })
    }

    suspend fun awaitReady(): Boolean = withTimeoutOrNull(10_000) { ready.await() } ?: false

    val maxInput: Int get() = runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000)

    /** Speaks [text] into [file] (a WAV). False when the engine reported an error or took too long. */
    suspend fun toFile(text: String, rate: Float, file: File): Boolean {
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        pending[id] = done
        tts.setSpeechRate(rate)
        if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        val ok = withTimeoutOrNull(180_000) { done.await() }
        if (ok == null) pending.remove(id)
        return ok == true && file.length() > 44
    }

    fun shutdown() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}

private class WavPart(val file: File, val sampleRate: Int, val channels: Int, val dataStart: Long, val dataLength: Long)

/** The 16-bit PCM inside a WAV file, or null for anything else. */
private fun readWav(file: File): WavPart? = runCatching {
    RandomAccessFile(file, "r").use { raf ->
        val tag = ByteArray(4)
        fun le32(): Long = Integer.reverseBytes(raf.readInt()).toLong() and 0xffffffffL
        fun le16(): Int = java.lang.Short.reverseBytes(raf.readShort()).toInt() and 0xffff
        if (raf.length() < 44) return@use null
        raf.readFully(tag); if (String(tag, Charsets.US_ASCII) != "RIFF") return@use null
        raf.skipBytes(4)
        raf.readFully(tag); if (String(tag, Charsets.US_ASCII) != "WAVE") return@use null
        var rate = 0
        var channels = 0
        while (raf.filePointer + 8 <= raf.length()) {
            raf.readFully(tag)
            val size = le32()
            when (String(tag, Charsets.US_ASCII)) {
                "fmt " -> {
                    if (size < 16) return@use null
                    val format = le16(); channels = le16(); rate = le32().toInt(); raf.skipBytes(6)
                    val bits = le16()
                    if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0) return@use null
                    raf.seek(raf.filePointer + (size - 16) + (size and 1))
                }
                "data" -> {
                    if (rate == 0) return@use null
                    val start = raf.filePointer
                    val left = raf.length() - start
                    // Streaming writers can leave the size unset; the rest of the file is the audio.
                    val length = if (size == 0L || size > left) left else size
                    return@use WavPart(file, rate, channels, start, length)
                }
                else -> raf.seek(raf.filePointer + size + (size and 1))
            }
        }
        null
    }
}.getOrNull()

/**
 * Encodes [parts] back to back as one AAC .m4a in [out]. Returns the length in ms and a loudness
 * level (0..1) for every 50 ms, or null when the parts can't be joined or nothing was written.
 */
private suspend fun encodeM4a(parts: List<WavPart>, out: File): Pair<Long, List<Float>>? {
    if (parts.isEmpty()) return null
    val rate = parts[0].sampleRate
    val channels = parts[0].channels
    if (parts.any { it.sampleRate != rate || it.channels != channels }) return null
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    var muxer: MediaMuxer? = null
    var reader: RandomAccessFile? = null
    try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
        val frameBytes = 2 * channels
        val bytes = ByteArray(8192 / frameBytes * frameBytes)
        val levels = ArrayList<Float>()
        val window = (rate / 20) * channels
        var inWindow = 0
        var peak = 0
        var frames = 0L
        var partIndex = -1
        var left = 0L
        val info = MediaCodec.BufferInfo()
        var track = -1
        var inputDone = false
        var written = 0

        fun readPcm(max: Int): Int {
            while (left <= 0L) {
                reader?.close(); reader = null
                partIndex++
                if (partIndex >= parts.size) return -1
                val part = parts[partIndex]
                reader = RandomAccessFile(part.file, "r").also { it.seek(part.dataStart) }
                left = part.dataLength / frameBytes * frameBytes
            }
            val want = minOf(max.toLong(), left).toInt() / frameBytes * frameBytes
            val n = reader!!.read(bytes, 0, want)
            if (n <= 0) { left = 0; return readPcm(max) }
            val whole = n / frameBytes * frameBytes
            left -= n
            return whole
        }

        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: error("No encoder input")
                    buffer.clear()
                    val n = readPcm(minOf(buffer.capacity(), bytes.size))
                    val pts = frames * 1_000_000L / rate
                    if (n <= 0) {
                        codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        buffer.put(bytes, 0, n)
                        codec.queueInputBuffer(index, 0, n, pts, 0)
                        frames += n / frameBytes
                        var i = 0
                        while (i < n) {
                            val sample = abs(((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt())
                            if (sample > peak) peak = sample
                            if (++inWindow >= window) {
                                levels += sqrt(peak / 32768f).coerceIn(0f, 1f)
                                inWindow = 0; peak = 0
                            }
                            i += 2
                        }
                    }
                }
            }
            val index = codec.dequeueOutputBuffer(info, 10_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(codec.outputFormat)
                mux.start()
            } else if (index >= 0) {
                val buffer = codec.getOutputBuffer(index)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                if (info.size > 0 && track >= 0 && buffer != null) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    mux.writeSampleData(track, buffer, info)
                    written++
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        }
        if (written == 0) return null
        mux.stop()
        return frames * 1000 / rate to levels
    } finally {
        runCatching { reader?.close() }
        runCatching { codec.stop() }
        codec.release()
        runCatching { muxer?.release() }
    }
}

private sealed interface SpeechPhase {
    data object Writing : SpeechPhase
    data class Generating(val part: Int, val parts: Int) : SpeechPhase
    data class Ready(val audio: NoteDocAudio) : SpeechPhase
}

/** The text-to-audio popup. [onInsert] puts the generated audio in the note. */
class NoteTextToAudioState(
    val onInsert: (NoteDocAudio) -> Boolean,
    val onClose: () -> Unit,
)

private val speeds = listOf("Slower" to .8f, "Normal" to 1f, "Faster" to 1.25f)

@Composable
internal fun NoteTextToAudioDialog(state: NoteTextToAudioState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speech = remember { NoteSpeech(context) }
    var text by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf(1f) }
    var phase by remember { mutableStateOf<SpeechPhase>(SpeechPhase.Writing) }
    var problem by remember { mutableStateOf<String?>(null) }
    var engineMissing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var kept by remember { mutableStateOf(false) }

    // A generated file that wasn't inserted is the popup's to delete.
    fun discardGenerated() {
        (phase as? SpeechPhase.Ready)?.audio?.let { audio ->
            NoteAudioPlayback.release(audio.file)
            if (!kept) NoteAudioStore.resolve(context, audio.file)?.delete()
        }
    }
    fun backToWriting() {
        job?.cancel(); job = null
        discardGenerated()
        phase = SpeechPhase.Writing
    }
    val close = {
        backToWriting()
        state.onClose()
    }
    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            discardGenerated()
            speech.shutdown()
        }
    }

    val generate: () -> Unit = generate@{
        val source = text.trim()
        if (source.isEmpty() || job != null) return@generate
        problem = null
        engineMissing = false
        job = scope.launch {
            val work = File(context.cacheDir, "note_tts").also { it.mkdirs() }
            val wavs = ArrayList<File>()
            var output: File? = null
            try {
                phase = SpeechPhase.Generating(0, 1)
                if (!speech.awaitReady()) {
                    engineMissing = true
                    problem = "Text-to-speech isn't available on this phone. Turn on a speech engine in Settings."
                    phase = SpeechPhase.Writing
                    return@launch
                }
                val chunks = speechChunks(source, (speech.maxInput - 100).coerceIn(500, 3900))
                val parts = ArrayList<WavPart>()
                chunks.forEachIndexed { i, chunk ->
                    phase = SpeechPhase.Generating(i + 1, chunks.size)
                    val wav = File(work, "${UUID.randomUUID()}.wav").also { wavs += it }
                    if (!speech.toFile(chunk, speed, wav)) {
                        engineMissing = true
                        problem = "Couldn't generate the audio. Check that a voice for this language is downloaded in your text-to-speech settings."
                        phase = SpeechPhase.Writing
                        return@launch
                    }
                    parts += withContext(Dispatchers.IO) { readWav(wav) } ?: run {
                        problem = "The speech engine produced audio this app can't read."
                        phase = SpeechPhase.Writing
                        return@launch
                    }
                }
                val (name, file) = NoteAudioStore.newRecording(context)
                output = file
                val encoded = withContext(Dispatchers.Default) { runCatching { encodeM4a(parts, file) }.getOrNull() }
                if (encoded == null || !file.isFile || encoded.first <= 0) {
                    problem = "Couldn't save the generated audio."
                    phase = SpeechPhase.Writing
                    return@launch
                }
                val audio = NoteDocAudio(name, encoded.first.coerceAtMost(NoteDocAudio.MAX_DURATION_MS),
                    NoteDocAudio.encodePeaks(encoded.second), NoteDocAudio.titleFor(source))
                output = null
                phase = SpeechPhase.Ready(audio)
            } finally {
                output?.delete()
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { wavs.forEach { it.delete() } }
                job = null
            }
        }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth()) {
            Column(Modifier.padding(18.dp).testTag("notes_tts_dialog")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Mint, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.RecordVoiceOver, null, tint = Green, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Text to audio", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Read aloud by your phone's own voice. Works offline.", color = Muted, fontSize = 12.sp,
                            lineHeight = 16.sp)
                    }
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button, onClick = close)
                            .semantics { contentDescription = "Close text to audio" }
                            .testTag("notes_tts_close"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Close, null, tint = Muted, modifier = Modifier.size(20.dp)) }
                }
                Spacer(Modifier.height(14.dp))
                val busy = phase is SpeechPhase.Generating
                val fieldShape = RoundedCornerShape(14.dp)
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp).clip(fieldShape)
                        .background(Color(0xFFFAFBFA)).border(1.dp, Border, fieldShape)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { next ->
                            if (busy) return@BasicTextField
                            val clipped = next.take(MAX_SPEECH_TEXT)
                            if (clipped != text && phase is SpeechPhase.Ready) backToWriting()
                            text = clipped
                        },
                        textStyle = TextStyle(color = Ink, fontSize = 14.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(Green),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text("Paste or type the text to turn into audio", color = Muted,
                                    fontSize = 14.sp, lineHeight = 20.sp)
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp)
                            .semantics { contentDescription = "Text to turn into audio" }
                            .testTag("notes_tts_text"),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallAction(if (text.isEmpty()) "Paste" else "Clear", "notes_tts_paste", enabled = !busy) {
                        if (text.isEmpty()) {
                            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                            if (!clip.isNullOrBlank()) text = clip.take(MAX_SPEECH_TEXT)
                        } else { backToWriting(); text = "" }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${text.length} / $MAX_SPEECH_TEXT", color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Speed", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                    speeds.forEach { (label, value) ->
                        val selected = speed == value
                        val chip = RoundedCornerShape(50)
                        Text(
                            label, color = if (selected) Green else Ink, fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clip(chip).background(if (selected) Mint else Color.White)
                                .border(1.dp, if (selected) Green.copy(alpha = .4f) else Border, chip)
                                .clickable(enabled = !busy, role = Role.RadioButton) {
                                    if (speed != value) { speed = value; if (phase is SpeechPhase.Ready) backToWriting() }
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                                .testTag("notes_tts_speed_${label.lowercase()}"),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                when (val p = phase) {
                    is SpeechPhase.Generating -> Row(Modifier.fillMaxWidth().height(44.dp).testTag("notes_tts_progress"),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Green, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(if (p.parts > 1 && p.part > 0) "Generating audio… part ${p.part} of ${p.parts}" else "Generating audio…",
                            color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        SmallAction("Cancel", "notes_tts_cancel") { backToWriting() }
                    }
                    is SpeechPhase.Ready -> {
                        val file = remember(p.audio.file) { NoteAudioStore.resolve(context, p.audio.file) }
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Border, RoundedCornerShape(14.dp))
                            .padding(10.dp)) {
                            AudioPlayerRow(p.audio, file, Modifier.fillMaxWidth(), playTag = "notes_tts_play")
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SmallAction("Generate again", "notes_tts_regenerate") { backToWriting(); generate() }
                            Spacer(Modifier.weight(1f))
                            PrimaryButton("Insert", "Insert audio into the note", "notes_tts_insert", enabled = true) {
                                NoteAudioPlayback.release(p.audio.file)
                                if (state.onInsert(p.audio)) kept = true
                            }
                        }
                    }
                    SpeechPhase.Writing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        PrimaryButton("Generate audio", "Generate audio", "notes_tts_generate",
                            enabled = text.isNotBlank(), onClick = generate)
                    }
                }
                problem?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, color = ErrorRed, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f)
                            .testTag("notes_tts_problem"))
                        if (engineMissing) SmallAction("Speech settings", "notes_tts_settings") {
                            runCatching {
                                context.startActivity(Intent("com.android.settings.TTS_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) Green else Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp).testTag(tag),
    )
}

@Composable
private fun PrimaryButton(label: String, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).background(if (enabled) Green else Border)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}
