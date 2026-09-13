package com.virlin.app.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.ui.theme.VirlinColors

const val AgentReceiptTestTag = "agent_receipt"

/**
 * Compact, temporary confirmation. One reusable component for SUCCESS, CAPTURE_SUCCESS and
 * ERROR. Not a chat bubble, not a toast — a small object the user can act on once.
 */
@Composable
fun AgentReceipt(
    receipt: Receipt,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val (bg, border, accent) = when (receipt.kind) {
        Receipt.Kind.SUCCESS -> Triple(VirlinColors.FocusSurface, Color.White.copy(alpha = 0.7f), VirlinColors.TextPrimary)
        Receipt.Kind.CAPTURE_SUCCESS -> Triple(VirlinColors.ReadySurface, VirlinColors.ReadyBorder, VirlinColors.TextPrimary)
        Receipt.Kind.ERROR -> Triple(VirlinColors.NeedsYouOverdue, VirlinColors.NeedsYouOverdueBorder, VirlinColors.TextPrimary)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(14.dp))
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .testTag(AgentReceiptTestTag)
            .semantics { contentDescription = "${receipt.status}: ${receipt.primary}" }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(receipt.status, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = VirlinColors.TextSecondary)
            Spacer(Modifier.height(2.dp))
            Text(receipt.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
            receipt.secondary?.let {
                Text(it, fontSize = 11.sp, color = VirlinColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        receipt.actions.forEach { action ->
            Spacer(Modifier.width(6.dp))
            Text(
                action,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                color = if (action == "Undo") VirlinColors.TextSecondary else VirlinColors.TextPrimary,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onAction(action) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}
