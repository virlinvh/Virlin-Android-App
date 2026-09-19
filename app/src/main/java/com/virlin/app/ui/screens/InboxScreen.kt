package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.agent.capture.AgentCaptureViewModel
import com.virlin.app.ui.agent.capture.CaptureInbox
import com.virlin.app.ui.theme.*

const val InboxScreenTag = "inbox_screen"

/**
 * Root INBOX destination: view and manage previously captured items. It renders the SAME
 * [CaptureInbox] (filters · rows · detail · organize · archive · restore · convert) over the same
 * capture domain as the Agent's CAPTURE area. Quick capture stays in the Agent (Orb → CAPTURE).
 */
@Composable
fun InboxScreen(
    vm: AgentCaptureViewModel,
    onOpenTextNote: ((captureId: String?) -> Unit)? = null,
    onOpenPrompt: ((captureId: String?) -> Unit)? = null,
    onOpenLink: ((captureId: String?) -> Unit)? = null,
    onOpenFile: ((captureId: String?) -> Unit)? = null,
    onOpenVoice: ((captureId: String?) -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Pearl).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(InboxScreenTag)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Inbox", style = Typography.titleLarge, color = Charcoal)
        Text("Captured, not yet organized · save more from the Orb", fontSize = 12.sp, color = CharcoalMuted)
        Spacer(Modifier.height(12.dp))
        CaptureInbox(vm, onOpenTextNote = onOpenTextNote, onOpenPrompt = onOpenPrompt, onOpenLink = onOpenLink, onOpenFile = onOpenFile, onOpenVoice = onOpenVoice)
        Spacer(Modifier.height(88.dp))
    }
}
