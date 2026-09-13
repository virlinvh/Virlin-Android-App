package com.virlin.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.theme.*

@Composable
fun PulseScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pearl)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text("Pulse", style = Typography.titleLarge, color = Charcoal)
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Today", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.background(Charcoal, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp))
            Text("Week", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Text("Month", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CharcoalMuted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetricCard("Useful Focus", "2h 47m", Modifier.weight(1f))
            Spacer(modifier = Modifier.width(16.dp))
            MetricCard("External", "1h 38m", Modifier.weight(1f))
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Parallel Flow Signature Metric
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val infiniteTransition = rememberInfiniteTransition(label = "")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f, targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = ""
                )
                Box(modifier = Modifier.size(8.dp).background(Emerald500.copy(alpha=alpha), CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text("PARALLEL FLOW", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = Emerald500)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("54m", fontSize = 36.sp, fontWeight = FontWeight.Black, color = Charcoal)
            Text("productive work during external processing", fontSize = 12.sp, color = CharcoalMuted)
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Abstract visualization
            Row(modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0xFFE2E8F0))) {
                Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(0.3f).background(Emerald500))
                Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(0.4f).background(Violet600))
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            Text("11 streams advanced", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal)
            Text("7 check-ins handled", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal)
            Text("2 blockers cleared", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Charcoal)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("BEST PARALLEL WINDOW", fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = CharcoalMuted)
        Spacer(modifier = Modifier.height(16.dp))
        
        Column(modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Text("Claude processed: 24m", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Charcoal)
            Text("During that window:", fontSize = 12.sp, color = CharcoalMuted)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• Psychology: 12m", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Charcoal)
            Text("• MBA: 7m", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Charcoal)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Result: 19m useful overlap", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Emerald500)
        }
        
        Spacer(modifier = Modifier.height(88.dp))
    }
}

@Composable
fun MetricCard(title: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = CharcoalMuted)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Black, color = Charcoal)
    }
}
