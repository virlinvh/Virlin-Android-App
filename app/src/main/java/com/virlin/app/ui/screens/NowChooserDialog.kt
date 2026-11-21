package com.virlin.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.components.SafeDialog as Dialog
import com.virlin.app.ui.screens.NowViewModel.Chooser
import com.virlin.app.ui.screens.NowViewModel.TimedIntent
import com.virlin.app.ui.theme.Charcoal
import com.virlin.app.ui.theme.Pearl

/**
 * The single lightweight chooser for Now's finalized attention exits. Presets execute
 * immediately (no Save); CUSTOM is a compact minutes field, future only. No reminder form.
 */
@Composable
fun NowChooserDialog(chooser: Chooser, vm: AttentionIntents) {
    val id = chooser.streamId
    var leaveCustom by remember(chooser) { mutableStateOf(false) }
    com.virlin.app.ui.hierarchy.HideProjectOrb()
    // Contextual permission: the first time the user schedules a timed return/check, ask to
    // post notifications so the wake-up can reach them. Never nags; denial changes nothing else.
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    val ensurePermission: () -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !com.virlin.app.platform.AttentionNotifications.canPost(context)) {
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Dialog(onDismissRequest = vm::dismissChooser, scrollContent = true) {
        val cardShape = RoundedCornerShape(32.dp)
        Column(
            modifier = Modifier.fillMaxWidth()
                .shadow(16.dp, cardShape, ambientColor = Color(0x553B1660), spotColor = Color(0x553B1660))
                .background(Color.White, cardShape)
                .padding(start = 22.dp, end = 18.dp, top = 20.dp, bottom = 20.dp)
                .testTag(NowChooserTag)
        ) {
            when (chooser) {
                is Chooser.Leave -> {
                    Header("Leave this session?", "Your focus time stays saved. You can come back and continue.", vm::dismissChooser)
                    Title("Return in:")
                    Presets(listOf(3, 5, 10, 15), tagPrefix = "leave") { ensurePermission(); vm.leave(id, it) }
                    if (leaveCustom) InlineLeaveMinutes { minutes ->
                        val accepted = vm.customMinutes(id, TimedIntent.LEAVE, minutes)
                        if (accepted) ensurePermission()
                        accepted
                    } else Secondary("CUSTOM", "chooser_custom") { leaveCustom = true }
                    Spacer(Modifier.height(4.dp))
                    Primary("LEAVE WITHOUT REMINDER", "chooser_leave_no_reminder") { vm.leave(id, null) }
                    Secondary("PAUSE", "chooser_pause") { vm.pauseFocus(id) }
                }
                is Chooser.HandOff -> {
                    Header("Hand off this session?", "Your focus time stays saved. Choose when to check on it.", vm::dismissChooser)
                    Title("Check in:")
                    Presets(listOf(1, 2, 5, 10, 15), tagPrefix = "handoff") { ensurePermission(); vm.handOff(id, it) }
                    Secondary("CUSTOM", "chooser_custom") { vm.openCustom(id, TimedIntent.HAND_OFF) }
                    Spacer(Modifier.height(4.dp))
                    Primary("NO CHECK", "chooser_no_check") { vm.handOff(id, null) }
                }
                is Chooser.CheckOutcome -> {
                    Header("What happened?", null, vm::dismissChooser)
                    Secondary("STILL RUNNING", "chooser_still_running") { vm.openStillRunning(id) }
                    Secondary("RESULT READY", "chooser_result_ready") { vm.openResultReady(id) }
                    Secondary("BLOCKED", "chooser_blocked") { vm.block(id) }
                }
                is Chooser.StillRunning -> {
                    // CHECK AGAIN: the approved quick durations (Phase 05) + CUSTOM. Sets a new
                    // `dueAt = now + N`; priority rank and any priority preference are untouched.
                    Header("Still running", null, vm::dismissChooser)
                    Title("Check again:")
                    Presets(com.virlin.app.domain.attention.AttentionTiming.checkAgainPresets.map { it.toInt() }, tagPrefix = "still") { ensurePermission(); vm.stillRunning(id, it) }
                    Secondary("CUSTOM", "chooser_custom") { vm.openCustom(id, TimedIntent.STILL_RUNNING) }
                }
                is Chooser.ResultReady -> {
                    Header("Result ready", null, vm::dismissChooser)
                    Primary("FOCUS NOW", "chooser_focus_now") { vm.resultReadyNow(id) }
                    Spacer(Modifier.height(10.dp))
                    Title("Remind later:")
                    Presets(listOf(3, 5, 10, 15), tagPrefix = "later") { ensurePermission(); vm.resultReadyLater(id, it) }
                    Secondary("CUSTOM", "chooser_custom") { vm.openCustom(id, TimedIntent.RESULT_READY_LATER) }
                }
                is Chooser.Defer -> {
                    Header("Come back later", null, vm::dismissChooser)
                    Title("Back in:")
                    Presets(listOf(3, 5, 10, 15), tagPrefix = "defer") { vm.deferReturn(id, it) }
                    Secondary("CUSTOM", "chooser_custom") { vm.openCustom(id, TimedIntent.DEFER_RETURN) }
                }
                is Chooser.Custom -> {
                    Header("Custom time", null, vm::dismissChooser)
                    Title("Minutes from now:")
                    var text by remember { mutableStateOf("") }
                    var invalid by remember { mutableStateOf(false) }
                    BasicTextField(
                        value = text, onValueChange = { text = it.filter(Char::isDigit); invalid = false },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ChooserInk, fontFamily = NowApprovedFont),
                        modifier = Modifier.fillMaxWidth().background(ChooserLilacWash, RoundedCornerShape(16.dp))
                            .border(1.5.dp, ChooserLilacEdge, RoundedCornerShape(16.dp)).padding(14.dp).testTag("chooser_custom_minutes")
                    )
                    if (invalid) Text("Enter a future time (minutes > 0).", fontSize = 12.sp, color = Color(0xFFD0245E),
                        fontFamily = NowApprovedFont, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(12.dp))
                    Primary("SET", "chooser_custom_confirm") {
                        val m = text.toLongOrNull()
                        if (m == null || !vm.customMinutes(id, chooser.intent, m)) invalid = true else ensurePermission()
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // Dismissing never changes anything; for LEAVE that simply means staying in Focus.
            Secondary(if (chooser is Chooser.Leave) "KEEP FOCUSING" else "CANCEL", "chooser_cancel", vm::dismissChooser)
        }
    }
}

private val ChooserInk = Color(0xFF2A0F45)
private val ChooserPlum = Color(0xFF5B2A86)
private val ChooserMuted = Color(0xFF6E5384)
private val ChooserLilacWash = Color(0xFFF8F2FF)
private val ChooserLilacEdge = Color(0xFFDCC6F8)

@Composable
private fun Header(title: String, subtitle: String?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 6.dp, end = 8.dp)) {
            Text(title, fontFamily = NowApprovedFont, fontSize = 22.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.Bold, color = ChooserInk)
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(subtitle, fontFamily = NowApprovedFont, fontSize = 14.5.sp, lineHeight = 21.sp, color = ChooserMuted)
            }
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFF3E8FD))
                .testTag("chooser_close")
                .clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose)
                .semantics { contentDescription = "Close" },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = ChooserPlum, modifier = Modifier.size(20.dp))
        }
    }
    Spacer(Modifier.height(18.dp))
}

const val NowChooserTag = "now_chooser"

/** Leave's custom duration edits in place; the existing intent owns validation and scheduling. */
@Composable
private fun InlineLeaveMinutes(onSave: (Long) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var rejected by remember { mutableStateOf(false) }
    val minutes = text.toLongOrNull()?.takeIf { it > 0 }
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().background(ChooserLilacWash, shape).border(1.dp, ChooserLilacEdge, shape).padding(14.dp)) {
        Text("Custom reminder", color = ChooserInk, fontFamily = NowApprovedFont, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(10); rejected = false },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ChooserInk, fontFamily = NowApprovedFont),
                modifier = Modifier.width(72.dp).background(Color.White, RoundedCornerShape(14.dp))
                    .border(1.5.dp, ChooserLilacEdge, RoundedCornerShape(14.dp))
                    .padding(12.dp).testTag("chooser_custom_minutes")
                    .then(Modifier.semantics { contentDescription = "Minutes from now" })
            )
            Text("min", fontFamily = NowApprovedFont, fontSize = 14.sp, color = ChooserMuted)
            Spacer(Modifier.weight(1f))
            ChooserButton("SAVE", "chooser_custom_confirm", primary = true, modifier = Modifier.width(96.dp),
                height = 48.dp, enabled = minutes != null) { minutes?.let { rejected = !onSave(it) } }
        }
        Text(if (rejected) "Enter a valid future time." else "Minutes from now",
            color = if (rejected) Color(0xFFD0245E) else ChooserMuted, fontFamily = NowApprovedFont,
            fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Title(text: String) {
    Text(text, fontFamily = NowApprovedFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ChooserPlum)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Presets(minutes: List<Int>, tagPrefix: String, onPick: (Long) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = if (minutes.size > 4) 6.dp else 8.dp
        val chip = (maxWidth - gap * (minutes.size - 1)) / minutes.size
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        // "15m" must stay whole even with five chips on a narrow phone.
        val chipSp = (chip.value * .85f / (3 * .8f) / fontScale).coerceIn(10f, 15f).sp
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            minutes.forEach { m ->
                ChooserButton("${m}m", "${tagPrefix}_${m}m", primary = false, modifier = Modifier.weight(1f),
                    height = 48.dp, fontSize = chipSp, textPadding = 0.dp) { onPick(m.toLong()) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Primary(text: String, tag: String, onClick: () -> Unit) {
    ChooserButton(text, tag, primary = true, onClick = onClick)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Secondary(text: String, tag: String, onClick: () -> Unit) {
    ChooserButton(text, tag, primary = false, onClick = onClick)
    Spacer(Modifier.height(10.dp))
}

/** The Now card's raised buttons: coral for the main exit, lilac for everything else. */
@Composable
private fun ChooserButton(
    text: String, tag: String, primary: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(), height: Dp = 54.dp,
    fontSize: TextUnit = if (text.length > 16) 14.sp else 15.sp,
    enabled: Boolean = true, textPadding: Dp = 6.dp, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed && android.animation.ValueAnimator.areAnimatorsEnabled()) .97f else 1f, tween(120), label = "chooserPress")
    val glow = if (primary) Color(0x66FF3779) else Color(0x55A141EF)
    Box(
        modifier.height(height)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = if (enabled) 1f else .45f }
            .shadow(5.dp, shape, ambientColor = glow, spotColor = glow)
            .background(if (primary) Color(0xFFC7104C) else Color(0xFFA855F7), shape)
            .padding(bottom = 5.dp)
            .clip(shape)
            .background(
                if (primary) Brush.horizontalGradient(listOf(Color(0xFFFF8199), Color(0xFFFF4F80), Color(0xFFFF3779)))
                else Brush.verticalGradient(listOf(Color.White, Color(0xFFEFE4FD)))
            )
            .border(1.dp, if (primary) Color(0xFFFFB0CA) else Color(0xFFD3B3FA), shape)
            .testTag(tag)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (primary) Color.White else ChooserInk, fontFamily = NowApprovedFont,
            fontSize = fontSize, fontWeight = FontWeight.Black, letterSpacing = .3.sp, maxLines = 1,
            softWrap = false, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = textPadding))
    }
}

const val CompletedFocusTag = "completed_focus"
const val FocusNextTag = "focus_next"
const val DoneForNowTag = "done_for_now"

/**
 * PHASE 09 — the moment after finishing the focused work: what was completed, what comes next, and
 * two equal-weight choices. Nothing starts on its own.
 */
@Composable
fun CompletedFocusDialog(completedTitle: String, nextTitle: String?, onFocusNext: () -> Unit, onDone: () -> Unit) {
    Dialog(onDismissRequest = onDone, scrollContent = true) {
        Column(
            Modifier.fillMaxWidth().background(Pearl, RoundedCornerShape(24.dp)).padding(20.dp).testTag(CompletedFocusTag)
        ) {
            Text("COMPLETED", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = CharcoalMuted)
            Text(completedTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Charcoal, maxLines = 2)
            if (nextTitle != null) {
                Spacer(Modifier.height(12.dp))
                Text("NEXT", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = CharcoalMuted)
                Text(nextTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Charcoal, maxLines = 2)
            } else {
                Spacer(Modifier.height(10.dp))
                Text("Nothing else is open here.", fontSize = 12.5.sp, color = CharcoalMuted)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "DONE FOR NOW", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp, color = Charcoal,
                    modifier = Modifier.background(Color.White, RoundedCornerShape(50))
                        .testTag(DoneForNowTag).clickable(role = Role.Button, onClick = onDone)
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                )
                if (nextTitle != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "FOCUS NEXT", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp, color = Color.White,
                        modifier = Modifier.background(Charcoal, RoundedCornerShape(50))
                            .testTag(FocusNextTag).clickable(role = Role.Button, onClick = onFocusNext)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}
