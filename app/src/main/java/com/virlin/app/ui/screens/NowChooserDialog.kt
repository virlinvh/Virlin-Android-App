package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
    Dialog(onDismissRequest = vm::dismissChooser) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Pearl, RoundedCornerShape(24.dp)).padding(20.dp).testTag(NowChooserTag)
        ) {
            when (chooser) {
                is Chooser.Leave -> {
                    Title("Return in:")
                    Presets(listOf(3, 5, 10, 15), tagPrefix = "leave") { ensurePermission(); vm.leave(id, it) }
                    Secondary("CUSTOM", "chooser_custom") { vm.openCustom(id, TimedIntent.LEAVE) }
                    Secondary("LEAVE WITHOUT REMINDER", "chooser_leave_no_reminder") { vm.leave(id, null) }
                }
                is Chooser.HandOff -> {
                    Title("Check in:")
                    Presets(listOf(1, 2, 5, 10, 15), tagPrefix = "handoff") { ensurePermission(); vm.handOff(id, it) }
                    Secondary("CUSTOM", "chooser_custom") { vm.openCustom(id, TimedIntent.HAND_OFF) }
                    Secondary("NO CHECK", "chooser_no_check") { vm.handOff(id, null) }
                }
                is Chooser.CheckOutcome -> {
                    Title("What happened?")
                    Secondary("STILL RUNNING", "chooser_still_running") { vm.openStillRunning(id) }
                    Secondary("RESULT READY", "chooser_result_ready") { vm.openResultReady(id) }
                    Secondary("BLOCKED", "chooser_blocked") { vm.block(id) }
                }
                is Chooser.StillRunning -> {
                    Title("Check again:")
                    Presets(listOf(1, 2, 5, 10), tagPrefix = "still") { ensurePermission(); vm.stillRunning(id, it) }
                    Secondary("CUSTOM", "chooser_custom") { vm.openCustom(id, TimedIntent.STILL_RUNNING) }
                }
                is Chooser.ResultReady -> {
                    Primary("FOCUS NOW", "chooser_focus_now") { vm.resultReadyNow(id) }
                    Spacer(Modifier.height(12.dp))
                    Title("Remind later:")
                    Presets(listOf(3, 5, 10, 15), tagPrefix = "later") { ensurePermission(); vm.resultReadyLater(id, it) }
                    Secondary("CUSTOM", "chooser_custom") { vm.openCustom(id, TimedIntent.RESULT_READY_LATER) }
                }
                is Chooser.Defer -> {
                    Title("Back in:")
                    Presets(listOf(3, 5, 10, 15), tagPrefix = "defer") { vm.deferReturn(id, it) }
                    Secondary("CUSTOM", "chooser_custom") { vm.openCustom(id, TimedIntent.DEFER_RETURN) }
                }
                is Chooser.Custom -> {
                    Title("Minutes from now:")
                    var text by remember { mutableStateOf("") }
                    var invalid by remember { mutableStateOf(false) }
                    BasicTextField(
                        value = text, onValueChange = { text = it.filter(Char::isDigit); invalid = false },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Charcoal),
                        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp).testTag("chooser_custom_minutes")
                    )
                    if (invalid) Text("Enter a future time (minutes > 0).", fontSize = 11.sp, color = Color(0xFFB45309), modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    Primary("SET", "chooser_custom_confirm") {
                        val m = text.toLongOrNull()
                        if (m == null || !vm.customMinutes(id, chooser.intent, m)) invalid = true else ensurePermission()
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.End).testTag("chooser_cancel").clickable(onClick = vm::dismissChooser).padding(8.dp))
        }
    }
}

const val NowChooserTag = "now_chooser"

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Black, color = Charcoal)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Presets(minutes: List<Int>, tagPrefix: String, onPick: (Long) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        minutes.forEach { m ->
            Text("${m}m", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).background(Charcoal, RoundedCornerShape(50)).testTag("${tagPrefix}_${m}m")
                    .clickable(role = Role.Button) { onPick(m.toLong()) }.padding(vertical = 10.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Primary(text: String, tag: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 0.4.sp,
        modifier = Modifier.fillMaxWidth().background(Charcoal, RoundedCornerShape(16.dp)).testTag(tag)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun Secondary(text: String, tag: String, onClick: () -> Unit) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Charcoal, letterSpacing = 0.4.sp,
        modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).testTag(tag)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    Spacer(Modifier.height(8.dp))
}
