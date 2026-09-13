package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.virlin.app.mock.MockData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.virlin.app.model.StreamState
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamDetailScreen(streamId: String?, navController: NavController, intents: NowViewModel = viewModel()) {
    val streams by MockData.streams.collectAsState()
    val stream = streams.find { it.id == streamId }
    
    if (stream == null) {
        Text("Stream not found")
        return
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pearl)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "< Back", 
            fontSize = 14.sp, 
            fontWeight = FontWeight.Bold, 
            color = CharcoalMuted,
            modifier = Modifier.clickable { navController.popBackStack() }
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(stream.title, style = Typography.titleLarge, color = Charcoal)
        Text(stream.subtitle, fontSize = 16.sp, color = CharcoalMuted)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Status Badge
        val (bg, fg, label) = when (stream.state) {
            StreamState.FOCUS -> Triple(FocusGreen, Charcoal, "FOCUS")
            StreamState.NEEDS_YOU -> Triple(NeedsYouYellow, Color(0xFF92400E), "NEEDS YOU")
            StreamState.PROCESSING -> Triple(ProcessingLavender, Violet600, "PROCESSING")
            StreamState.READY -> Triple(FreeMint, Color(0xFF047857), "READY")
            StreamState.SNOOZED -> Triple(Color.LightGray, Charcoal, "SNOOZED")
            StreamState.BLOCKED -> Triple(NeedsYouCoral, Color(0xFF9A3412), "BLOCKED")
            StreamState.PAUSED -> Triple(Color(0xFFE2E8F0), Charcoal, "PAUSED")
            StreamState.DONE -> Triple(Color(0xFFCBD5E1), Charcoal, "DONE")
        }
        
        Box(modifier = Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Black, color = fg)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Context Info
        Column(modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text("CURRENT CYCLE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted)
            Spacer(modifier = Modifier.height(12.dp))
            
            if (stream.state == StreamState.PROCESSING) {
                Text("YOU ASKED", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted)
                Text(stream.nextAction ?: "Working...", fontSize = 14.sp, color = Charcoal)
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            Text("WAITING FOR / NEXT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted)
            Text(stream.nextAction ?: "Implement missing logic", fontSize = 14.sp, color = Charcoal)
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // Actions
        when (stream.state) {
            StreamState.FOCUS -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { intents.openHandOff(stream.id) },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Charcoal)
                    ) {
                        Text("HAND OFF")
                    }
                    Button(
                        onClick = { intents.completeStream(stream.id); navController.popBackStack() },
                        modifier = Modifier.weight(1f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.LightGray)
                    ) {
                        Text("DONE", color = Charcoal)
                    }
                }
            }
            StreamState.NEEDS_YOU -> {
                Button(
                    onClick = { intents.openCheck(stream.id) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Charcoal)
                ) {
                    Text("CHECK NOW")
                }
            }
            StreamState.PROCESSING -> {
                Button(
                    onClick = { intents.openCheck(stream.id) },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Charcoal)
                ) {
                    Text("CHECK EARLY")
                }
            }
            StreamState.READY, StreamState.SNOOZED, StreamState.PAUSED, StreamState.BLOCKED -> {
                Button(
                    onClick = { intents.focus(stream.id); navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Charcoal)
                ) {
                    Text("RESUME FOCUS")
                }
            }
            else -> {}
        }
    }

    // Finalized Pass 4 semantics, shared with Now: HAND OFF → check-in chooser (1m/2m/5m/10m/15m,
    // custom, no check); CHECK → "What happened?". Never a silent fixed-duration hand-off.
    val chooser by intents.chooser.collectAsState()
    chooser?.let { NowChooserDialog(it, intents) }


}
