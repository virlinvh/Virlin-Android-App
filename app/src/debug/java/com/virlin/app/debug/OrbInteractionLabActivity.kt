package com.virlin.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.virlin.app.ui.components.VirlinOrb
import com.virlin.app.ui.orb.VirlinAgentViewModel
import com.virlin.app.ui.orb.VirlinOrbInteractionState as S
import com.virlin.app.ui.orb.toOrbParameters
import com.virlin.app.ui.theme.VirlinTheme

/**
 * DEVELOPMENT-ONLY Orb Interaction Lab. Debug source set; not reachable from the app.
 *
 * Exercises every semantic state and the five approved sequences against the REAL
 * `VirlinAgentViewModel` and the REAL `VirlinOrb` renderer, so what you see here is what
 * ships. Launch: `adb shell am start -n com.virlin.app/.debug.OrbInteractionLabActivity`
 */
class OrbInteractionLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VirlinTheme { OrbInteractionLab() } }
    }
}

private val AllStates = listOf(
    S.Idle, S.Pressed, S.Opening, S.Ready, S.Receiving, S.ReadyWithInput, S.Understanding,
    S.Clarification, S.Acting, S.Success, S.CaptureSuccess, S.Speaking, S.Error, S.Closing
)

private val SequenceA = listOf(S.Idle, S.Pressed, S.Opening, S.Ready, S.Receiving, S.ReadyWithInput,
    S.Understanding, S.Acting, S.Success, S.Ready)
private val SequenceB = listOf(S.Ready, S.Receiving, S.Understanding, S.Speaking, S.Ready)
private val SequenceC = listOf(S.Ready, S.Receiving, S.ReadyWithInput, S.Understanding, S.Acting,
    S.CaptureSuccess, S.Ready)
private val SequenceD = listOf(S.Ready, S.Receiving, S.Understanding, S.Clarification, S.Receiving,
    S.Understanding, S.Acting, S.Success, S.Ready)
private val SequenceE = listOf(S.Ready, S.Understanding, S.Acting, S.Error, S.Ready)

@Composable
fun OrbInteractionLab(vm: VirlinAgentViewModel = viewModel()) {
    val state by vm.orbState.collectAsState()
    val nonce by vm.oneShotNonce.collectAsState()
    var reducedMotion by remember { mutableStateOf(false) }
    var dwellMs by remember { mutableStateOf(1_100L) }
    val params = remember(state, reducedMotion, nonce) { state.toOrbParameters(reducedMotion, nonce) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F7F4))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ORB INTERACTION LAB", fontSize = 11.sp, fontWeight = FontWeight.Black,
            letterSpacing = 2.sp, color = Color(0xFF859088))
        Spacer(Modifier.height(24.dp))

        // The real Orb, the real renderer, the real parameter mapping.
        VirlinOrb(
            size = 96.dp,
            params = params,
            onPress = vm::onOrbPressed,
            onRelease = vm::onOrbReleased
        )

        Spacer(Modifier.height(16.dp))
        Text(state::class.simpleName ?: "", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color(0xFF162016))
        Text(state.statusText, fontSize = 12.sp, color = Color(0xFF525B54))

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabChip("Reduced motion: ${if (reducedMotion) "ON" else "OFF"}") { reducedMotion = !reducedMotion }
            LabChip("Dwell: ${dwellMs}ms") { dwellMs = if (dwellMs >= 2_000L) 600L else dwellMs + 500L }
        }

        Spacer(Modifier.height(20.dp))
        Text("STATES", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = Color(0xFF859088))
        Spacer(Modifier.height(8.dp))
        AllStates.chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { s ->
                    Button(
                        onClick = { vm.debugForceState(s) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (s == state) Color(0xFF162016) else Color.White,
                            contentColor = if (s == state) Color.White else Color(0xFF162016)
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) { Text(s::class.simpleName ?: "", fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(14.dp))
        Text("SEQUENCES", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = Color(0xFF859088))
        Spacer(Modifier.height(8.dp))
        listOf(
            "A · Normal Control" to SequenceA,
            "B · Voice response" to SequenceB,
            "C · Capture" to SequenceC,
            "D · Clarification" to SequenceD,
            "E · Failure" to SequenceE
        ).forEach { (label, seq) ->
            Button(
                onClick = { vm.debugRunSequence(seq, dwellMs) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDF4C7), contentColor = Color(0xFF162016))
            ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun LabChip(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF162016)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

@Preview(showBackground = true, widthDp = 411, heightDp = 900)
@Composable
private fun OrbInteractionLabPreview() {
    VirlinTheme { OrbInteractionLab() }
}
