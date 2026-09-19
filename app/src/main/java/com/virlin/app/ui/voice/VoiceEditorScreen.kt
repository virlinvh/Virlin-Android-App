package com.virlin.app.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.virlin.app.data.voice.VoiceFileStore
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.action.CaptureContext
import com.virlin.app.domain.model.VoiceClip
import com.virlin.app.domain.voice.VoiceDocumentCodec
import com.virlin.app.ui.theme.VirlinColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val VoiceEditorRoute = "voice_editor"
fun voiceEditorRoute(captureId: String?) =
    if (captureId.isNullOrBlank()) "voice_editor" else "voice_editor/$captureId"

const val VoiceScreenTag = "voice_editor_screen"
const val VoiceTitleTag = "voice_title"
const val VoiceStopTag = "voice_stop_recording"
const val VoiceRecordAnotherTag = "voice_record_another"
const val VoiceSaveStatusTag = "voice_save_status"

private val Paper = Color(0xFFFAF8F5)
private val Forest = Color(0xFF162016)
private val Hairline = Color(0x1F162016)
private val Blue = Color(0xFF1D63ED)
private val BlueSoft = Color(0xFFE9F2FE)
private val RecRed = Color(0xFFE11D48)

@Composable
fun VoiceEditorScreen(
    navController: NavController,
    captureId: String?,
    vm: VoiceEditorViewModel = viewModel(
        factory = VoiceEditorViewModel.factory(captureId, LocalContext.current)
    )
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var playingId by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startRecording() else vm.markPermissionNeeded()
    }

    fun ensureMicThen(start: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun goBack() {
        scope.launch {
            val result = vm.prepareExit()
            if (result.showInboxFeedback) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                delay(550)
                vm.clearInboxBanner()
            }
            if (result.navigate) navController.popBackStack()
        }
    }
    BackHandler { goBack() }

    LaunchedEffect(state.toast) {
        if (state.toast != null) {
            delay(1600)
            vm.clearToast()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .statusBarsPadding()
            .navigationBarsPadding()
            .testTag(VoiceScreenTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = { goBack() }, modifier = Modifier.semantics { contentDescription = "Back" }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Forest)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Voice", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Forest)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (state.saveStatus) {
                            VoiceSaveStatus.Saving -> "Saving…"
                            VoiceSaveStatus.Saved -> "Saved ✓"
                            VoiceSaveStatus.Error -> "Save error"
                            VoiceSaveStatus.Idle -> if (state.hasMeaningfulContent) "Ready" else "No recording"
                        },
                        fontSize = 11.sp,
                        color = if (state.saveStatus == VoiceSaveStatus.Saved) Blue else VirlinColors.TextSecondary,
                        modifier = Modifier.testTag(VoiceSaveStatusTag)
                    )
                    if (state.committedToInbox) {
                        Text("  ·  In Inbox ✓", fontSize = 11.sp, color = Blue)
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = Forest)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Save to Inbox") },
                        onClick = { menuOpen = false; vm.saveToInbox() }
                    )
                    DropdownMenuItem(
                        text = { Text("Choose Context") },
                        onClick = { menuOpen = false; vm.openContextPicker(true) }
                    )
                    DropdownMenuItem(
                        text = { Text("Archive") },
                        onClick = {
                            menuOpen = false
                            vm.archiveAndExit { navController.popBackStack() }
                        }
                    )
                }
            }
        }

        Text(
            if (state.contextLabel != null) "Attached to · ${state.contextLabel}" else "Global · Inbox",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (state.contextLabel != null) VirlinColors.Emerald else VirlinColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (state.contextPickerOpen) {
            VoiceContextPicker(onPick = vm::setContext, onClose = { vm.openContextPicker(false) })
        }

        state.inboxBanner?.let {
            Text(it, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Blue, modifier = Modifier.padding(16.dp, 8.dp))
        }
        state.toast?.let {
            Text(it, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = VirlinColors.Amber, modifier = Modifier.padding(16.dp, 4.dp))
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "VOICE NOTE TITLE",
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
            color = VirlinColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        BasicTextField(
            value = state.title,
            onValueChange = vm::onTitleChange,
            textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Forest),
            cursorBrush = SolidColor(Blue),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag(VoiceTitleTag),
            decorationBox = { inner ->
                Box {
                    if (state.title.isEmpty()) {
                        Text("Meeting Ideas", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB0B7B0))
                    }
                    inner()
                }
            }
        )

        Spacer(Modifier.height(8.dp))

        // Recording panel
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(16.dp))
                .border(1.dp, Hairline, RoundedCornerShape(16.dp))
                .padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedWaveform(
                active = state.recordPhase == RecordPhase.Recording,
                amplitude = state.amplitude,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                VoiceDocumentCodec.formatDuration(
                    if (state.recordPhase == RecordPhase.Recording) state.recordingElapsedMs else 0L
                ),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Forest
            )
            Spacer(Modifier.height(10.dp))
            if (state.recordPhase == RecordPhase.Recording) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(RecRed, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text("RECORDING", fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = RecRed)
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BlueSoft, RoundedCornerShape(12.dp))
                        .testTag(VoiceStopTag)
                        .clickable { vm.stopRecording() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("STOP RECORDING", fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.6.sp, color = Blue)
                }
            } else {
                Text(
                    if (state.clips.isEmpty()) "Tap below to start" else "Ready",
                    fontSize = 12.sp,
                    color = VirlinColors.TextSecondary
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "RECORDINGS · ${state.clips.size}",
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
            color = VirlinColors.TextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(state.clips, key = { _, c -> c.id }) { index, clip ->
                ClipRow(
                    clip = clip,
                    isPlaying = playingId == clip.id,
                    onPlayToggle = { playing ->
                        playingId = if (playing) clip.id else null
                    },
                    onRename = { vm.renameClip(clip.id, it) },
                    onDelete = {
                        if (playingId == clip.id) playingId = null
                        vm.deleteClip(clip.id)
                    },
                    onMoveUp = { vm.moveClip(clip.id, up = true) },
                    onMoveDown = { vm.moveClip(clip.id, up = false) },
                    canMoveUp = index > 0,
                    canMoveDown = index < state.clips.lastIndex
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .border(1.dp, Hairline, RoundedCornerShape(12.dp))
                        .testTag(VoiceRecordAnotherTag)
                        .clickable(enabled = state.recordPhase != RecordPhase.Recording) {
                            ensureMicThen {
                                if (state.clips.isEmpty() || state.recordPhase == RecordPhase.Idle) {
                                    vm.startRecording()
                                }
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (state.clips.isEmpty()) "+ START RECORDING" else "+ RECORD ANOTHER",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        letterSpacing = 0.5.sp,
                        color = Blue
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ClipRow(
    clip: VoiceClip,
    isPlaying: Boolean,
    onPlayToggle: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember(clip.id) { mutableStateOf(clip.displayName) }
    var position by remember(clip.id) { mutableLongStateOf(0L) }
    var duration by remember(clip.id) { mutableLongStateOf(clip.durationMs) }
    val player = remember(clip.id, clip.relativePath) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(VoiceFileStore.resolve(context, clip.relativePath).absolutePath)
                prepare()
                duration = this.duration.toLong().coerceAtLeast(clip.durationMs)
            }
        }.getOrNull()
    }
    DisposableEffect(player) {
        onDispose {
            runCatching { player?.stop() }
            player?.release()
        }
    }
    LaunchedEffect(isPlaying, player) {
        if (player == null) return@LaunchedEffect
        if (isPlaying) {
            player.start()
            while (isActive && player.isPlaying) {
                position = player.currentPosition.toLong()
                delay(100)
            }
            if (!player.isPlaying) {
                position = 0
                onPlayToggle(false)
            }
        } else if (player.isPlaying) {
            player.pause()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.DragHandle, contentDescription = "Reorder", tint = Color(0xFF9CA3AF), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (renameOpen) {
                    BasicTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Forest),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Done",
                        fontSize = 11.sp,
                        color = Blue,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            onRename(renameText)
                            renameOpen = false
                        }.padding(top = 4.dp)
                    )
                } else {
                    Text(clip.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Forest)
                    Text(VoiceDocumentCodec.formatDuration(clip.durationMs), fontSize = 11.sp, color = VirlinColors.TextSecondary)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Clip menu", tint = Forest)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renameOpen = true })
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        enabled = canMoveUp,
                        onClick = { menuOpen = false; onMoveUp() }
                    )
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        enabled = canMoveDown,
                        onClick = { menuOpen = false; onMoveDown() }
                    )
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (player == null) return@IconButton
                    onPlayToggle(!isPlaying)
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Blue
                )
            }
            Slider(
                value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { frac ->
                    val seek = (frac * duration).toLong()
                    player?.seekTo(seek.toInt())
                    position = seek
                },
                modifier = Modifier.weight(1f)
            )
        }
        if (isPlaying) {
            AnimatedWaveform(active = true, amplitude = 0.45f, modifier = Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp))
        }
    }
}

@Composable
private fun AnimatedWaveform(active: Boolean, amplitude: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    Canvas(modifier = modifier) {
        val bars = 28
        val gap = 4.dp.toPx()
        val barW = ((size.width - gap * (bars - 1)) / bars).coerceAtLeast(2f)
        for (i in 0 until bars) {
            val n = if (active) {
                val wobble = kotlin.math.sin((phase * Math.PI * 2) + i * 0.55).toFloat()
                (0.25f + amplitude * 0.75f * ((wobble + 1f) / 2f)).coerceIn(0.12f, 1f)
            } else {
                0.18f + (i % 5) * 0.04f
            }
            val h = size.height * n
            val x = i * (barW + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = if (active) Blue else Color(0xFFC5CDD8),
                topLeft = Offset(x, y),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f, barW / 2f)
            )
        }
    }
}

@Composable
private fun VoiceContextPicker(onPick: (CaptureContext) -> Unit, onClose: () -> Unit) {
    val streams by VirlinGraph.repository.streams.collectAsState()
    val projects by VirlinGraph.repository.projects.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, Hairline, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text("Choose context", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Forest)
        Spacer(Modifier.height(8.dp))
        Text(
            "Global (Inbox only)",
            fontSize = 12.sp,
            color = Blue,
            modifier = Modifier.fillMaxWidth().clickable { onPick(CaptureContext.None); onClose() }.padding(vertical = 6.dp)
        )
        streams.take(8).forEach { ws ->
            Text(
                ws.title,
                fontSize = 12.sp,
                color = Forest,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(CaptureContext(projectId = ws.projectId, workStreamId = ws.id)); onClose() }
                    .padding(vertical = 6.dp)
            )
        }
        projects.take(6).forEach { p ->
            Text(
                "Project · ${p.title}",
                fontSize = 12.sp,
                color = VirlinColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(CaptureContext(projectId = p.id)); onClose() }
                    .padding(vertical = 6.dp)
            )
        }
    }
}
