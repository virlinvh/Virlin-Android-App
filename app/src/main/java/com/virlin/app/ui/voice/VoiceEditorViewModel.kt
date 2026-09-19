package com.virlin.app.ui.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.virlin.app.data.voice.VoiceFileStore
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.action.VirlinActions
import com.virlin.app.domain.id.IdProvider
import com.virlin.app.domain.model.CaptureType
import com.virlin.app.domain.model.VoiceClip
import com.virlin.app.domain.model.VoiceDocument
import com.virlin.app.domain.repository.WorkStreamRepository
import com.virlin.app.domain.time.VirlinClock
import com.virlin.app.domain.voice.VoiceDocumentCodec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class VoiceSaveStatus { Idle, Saving, Saved, Error }

enum class RecordPhase { Idle, Recording }

data class VoiceUiState(
    val captureId: String?,
    val voiceId: String?,
    val title: String = "",
    val clips: List<VoiceClip> = emptyList(),
    val saveStatus: VoiceSaveStatus = VoiceSaveStatus.Idle,
    val toast: String? = null,
    val contextLabel: String? = null,
    val context: CaptureContext = CaptureContext.None,
    val contextPickerOpen: Boolean = false,
    val committedToInbox: Boolean = false,
    val loading: Boolean = true,
    val inboxBanner: String? = null,
    val recordPhase: RecordPhase = RecordPhase.Idle,
    val recordingElapsedMs: Long = 0L,
    val amplitude: Float = 0f,
    val permissionNeeded: Boolean = false
) {
    val hasMeaningfulContent: Boolean
        get() = clips.any { it.durationMs > 0L && it.relativePath.isNotBlank() }
}

class VoiceEditorViewModel(
    private val initialCaptureId: String?,
    private val appContext: Context,
    private val actions: VirlinActions = VirlinGraph.actions,
    private val repository: WorkStreamRepository = VirlinGraph.repository,
    private val ids: IdProvider = VirlinGraph.ids,
    private val clock: VirlinClock = VirlinGraph.clock
) : ViewModel() {

    private val draftCaptureId = initialCaptureId ?: ids.newId("cap")
    private val draftVoiceId = ids.newId("vox")

    private var recorder: MediaRecorder? = null
    private var recordingClipId: String? = null
    private var recordingFile: File? = null
    private var recordTicker: Job? = null
    private var recordStartedAt = 0L

    private val _state = MutableStateFlow(
        VoiceUiState(
            captureId = initialCaptureId,
            voiceId = if (initialCaptureId == null) draftVoiceId else null,
            committedToInbox = initialCaptureId != null,
            loading = initialCaptureId != null
        )
    )
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    init {
        refreshContextLabel()
        if (initialCaptureId != null) {
            viewModelScope.launch { loadExisting(initialCaptureId) }
        } else {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun loadExisting(captureId: String) {
        val cap = repository.getCapture(captureId)
        if (cap == null || cap.type != CaptureType.VOICE) {
            _state.update { it.copy(loading = false, toast = "Could not open voice note") }
            return
        }
        val doc = repository.getVoiceByCaptureId(captureId)
        _state.update {
            it.copy(
                captureId = cap.id,
                voiceId = doc?.id ?: draftVoiceId,
                title = doc?.title ?: cap.title.orEmpty(),
                clips = doc?.sortedClips().orEmpty(),
                committedToInbox = true,
                loading = false,
                saveStatus = VoiceSaveStatus.Saved,
                context = CaptureContext(cap.projectId, cap.workStreamId, cap.taskId)
            )
        }
        refreshContextLabel()
    }

    fun onTitleChange(t: String) {
        _state.update { it.copy(title = t) }
        if (_state.value.committedToInbox) schedulePersist()
    }

    fun openContextPicker(open: Boolean) = _state.update { it.copy(contextPickerOpen = open) }

    fun setContext(ctx: CaptureContext) {
        _state.update { it.copy(context = ctx, contextPickerOpen = false) }
        refreshContextLabel()
        val capId = _state.value.captureId
        if (capId != null && _state.value.committedToInbox) {
            viewModelScope.launch {
                actions.attachCapture(capId, ctx)
                _state.update { it.copy(toast = "Context updated", saveStatus = VoiceSaveStatus.Saved) }
            }
        }
    }

    fun clearToast() = _state.update { it.copy(toast = null) }
    fun clearInboxBanner() = _state.update { it.copy(inboxBanner = null) }
    fun clearPermissionFlag() = _state.update { it.copy(permissionNeeded = false) }

    fun markPermissionNeeded() = _state.update { it.copy(permissionNeeded = true, toast = "Microphone permission required") }

    fun startRecording() {
        if (_state.value.recordPhase == RecordPhase.Recording) return
        val captureId = _state.value.captureId ?: draftCaptureId
        val clipId = ids.newId("clip")
        val file = VoiceFileStore.clipFile(appContext, captureId, clipId)
        try {
            val mr = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            recordingClipId = clipId
            recordingFile = file
            recordStartedAt = System.currentTimeMillis()
            _state.update {
                it.copy(
                    captureId = captureId,
                    voiceId = it.voiceId ?: draftVoiceId,
                    recordPhase = RecordPhase.Recording,
                    recordingElapsedMs = 0L,
                    amplitude = 0.2f,
                    permissionNeeded = false
                )
            }
            recordTicker = viewModelScope.launch {
                while (isActive && _state.value.recordPhase == RecordPhase.Recording) {
                    val elapsed = System.currentTimeMillis() - recordStartedAt
                    val amp = try {
                        val max = recorder?.maxAmplitude ?: 0
                        (max / 32767f).coerceIn(0.08f, 1f)
                    } catch (_: Exception) {
                        0.25f + ((elapsed / 200) % 5) * 0.08f
                    }
                    _state.update { it.copy(recordingElapsedMs = elapsed, amplitude = amp) }
                    delay(80)
                }
            }
        } catch (e: Exception) {
            runCatching { file.delete() }
            _state.update { it.copy(toast = e.message ?: "Could not start recording", recordPhase = RecordPhase.Idle) }
        }
    }

    fun stopRecording() {
        if (_state.value.recordPhase != RecordPhase.Recording) return
        recordTicker?.cancel()
        val clipId = recordingClipId ?: return
        val file = recordingFile
        val captureId = _state.value.captureId ?: draftCaptureId
        var duration = (System.currentTimeMillis() - recordStartedAt).coerceAtLeast(0L)
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // ignore stop failures after short taps
        }
        runCatching { recorder?.release() }
        recorder = null
        recordingClipId = null
        recordingFile = null
        if (file == null || !file.exists() || file.length() < 64) {
            file?.delete()
            _state.update {
                it.copy(recordPhase = RecordPhase.Idle, recordingElapsedMs = 0L, amplitude = 0f, toast = "Recording too short")
            }
            return
        }
        if (duration < 200L) duration = 200L
        val index = _state.value.clips.size + 1
        val clip = VoiceClip(
            id = clipId,
            displayName = "Voice $index",
            relativePath = VoiceFileStore.relativePath(captureId, clipId),
            durationMs = duration,
            sizeBytes = file.length(),
            sortOrder = _state.value.clips.size,
            createdAt = clock.now()
        )
        _state.update {
            it.copy(
                clips = it.clips + clip,
                recordPhase = RecordPhase.Idle,
                recordingElapsedMs = 0L,
                amplitude = 0f,
                saveStatus = VoiceSaveStatus.Saved
            )
        }
        if (_state.value.committedToInbox) schedulePersist()
    }

    fun renameClip(clipId: String, name: String) {
        val trimmed = name.trim().ifEmpty { return }
        _state.update {
            it.copy(clips = it.clips.map { c -> if (c.id == clipId) c.copy(displayName = trimmed) else c })
        }
        if (_state.value.committedToInbox) schedulePersist()
    }

    fun deleteClip(clipId: String) {
        val clip = _state.value.clips.firstOrNull { it.id == clipId } ?: return
        VoiceFileStore.deleteClip(appContext, clip.relativePath)
        _state.update {
            val remaining = it.clips.filterNot { c -> c.id == clipId }
                .mapIndexed { i, c -> c.copy(sortOrder = i) }
            it.copy(clips = remaining)
        }
        if (_state.value.committedToInbox) schedulePersist()
    }

    fun moveClip(clipId: String, up: Boolean) {
        val list = _state.value.clips.toMutableList()
        val i = list.indexOfFirst { it.id == clipId }
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        if (j !in list.indices) return
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        _state.update {
            it.copy(clips = list.mapIndexed { idx, c -> c.copy(sortOrder = idx) })
        }
        if (_state.value.committedToInbox) schedulePersist()
    }

    fun saveToInbox() {
        viewModelScope.launch {
            if (!_state.value.hasMeaningfulContent) {
                _state.update { it.copy(toast = "Record something first") }
                return@launch
            }
            commitToInbox(showBanner = "✓ In Inbox")
        }
    }

    suspend fun prepareExit(): PrepareExitResult {
        if (_state.value.recordPhase == RecordPhase.Recording) stopRecording()
        persistJob?.cancel()
        val st = _state.value
        if (!st.hasMeaningfulContent) {
            val id = st.captureId ?: draftCaptureId
            VoiceFileStore.deleteCaptureTree(appContext, id)
            return PrepareExitResult(navigate = true, showInboxFeedback = false)
        }
        if (!st.committedToInbox) {
            val ok = commitToInbox(showBanner = "Saved to Inbox ✓")
            return PrepareExitResult(navigate = ok, showInboxFeedback = ok)
        }
        persistCommitted()
        return PrepareExitResult(navigate = true, showInboxFeedback = false)
    }

    data class PrepareExitResult(val navigate: Boolean, val showInboxFeedback: Boolean)

    fun archiveAndExit(onDone: () -> Unit) {
        viewModelScope.launch {
            val exit = prepareExit()
            if (!exit.navigate) return@launch
            val id = _state.value.captureId
            if (id != null && _state.value.committedToInbox) actions.archiveCapture(id)
            onDone()
        }
    }

    private var persistJob: Job? = null
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(450)
            persistCommitted()
        }
    }

    private suspend fun commitToInbox(showBanner: String): Boolean {
        val st = _state.value
        if (!st.hasMeaningfulContent) return false
        val capId = st.captureId ?: draftCaptureId
        val voiceId = st.voiceId ?: draftVoiceId
        val result = if (!st.committedToInbox) {
            actions.createVoice(
                title = st.title.ifBlank { null },
                clips = st.clips,
                context = st.context,
                captureId = capId,
                voiceId = voiceId
            )
        } else {
            actions.saveVoice(capId, st.title.ifBlank { null }, st.clips)
        }
        return when (result) {
            is ActionResult.Success -> {
                applyDoc(result.value, showBanner)
                true
            }
            else -> {
                _state.update { it.copy(saveStatus = VoiceSaveStatus.Error, toast = "Could not save") }
                false
            }
        }
    }

    private suspend fun persistCommitted() {
        val st = _state.value
        val capId = st.captureId ?: return
        if (!st.committedToInbox || !st.hasMeaningfulContent) return
        when (actions.saveVoice(capId, st.title.ifBlank { null }, st.clips)) {
            is ActionResult.Success -> _state.update { it.copy(saveStatus = VoiceSaveStatus.Saved) }
            else -> _state.update { it.copy(saveStatus = VoiceSaveStatus.Error, toast = "Could not save") }
        }
    }

    private fun applyDoc(doc: VoiceDocument, banner: String?) {
        _state.update {
            it.copy(
                captureId = doc.captureItemId,
                voiceId = doc.id,
                title = doc.title.orEmpty(),
                clips = doc.sortedClips(),
                committedToInbox = true,
                saveStatus = VoiceSaveStatus.Saved,
                inboxBanner = banner,
                toast = null
            )
        }
    }

    private fun refreshContextLabel() {
        viewModelScope.launch {
            val ctx = _state.value.context
            val label = when {
                ctx.taskId != null -> repository.getTask(ctx.taskId)?.title
                ctx.workStreamId != null -> repository.getStream(ctx.workStreamId)?.title
                ctx.projectId != null -> repository.getProject(ctx.projectId)?.title
                else -> null
            }
            _state.update { it.copy(contextLabel = label) }
        }
    }

    override fun onCleared() {
        recordTicker?.cancel()
        runCatching { recorder?.release() }
        recorder = null
        super.onCleared()
    }

    companion object {
        fun factory(captureId: String?, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    VoiceEditorViewModel(captureId, context.applicationContext) as T
            }
    }
}
