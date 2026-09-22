package com.virlin.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.theme.Charcoal
import java.time.Instant

/**
 * The approved COMPACT Needs You attention card (Phase 01) in its rank colour identity (Phase 02).
 *
 * ONE row, one alignment grid, fixed columns — a card never shifts because a title is longer:
 *
 * ```
 * [ 1 ] [icon]  Navigation · Route structure      01:07:38   CHECK →
 *               Claude · Virlin · Check due
 * ```
 *
 * LEFT   rank badge (circular, tappable — the queue-position selector) + the project's own icon
 * CENTER task title (1 line, ellipsised) over one restrained secondary line (source · reason)
 * RIGHT  HH:MM:SS waiting timer (tabular figures) + the compact CHECK / Resume / Focus now action
 *
 * The card holds NO business logic: rank, timer basis, kind and callbacks are all passed in. Every
 * colour comes from ONE call to [NeedsYouPriority.visualsFor] — badge, icon, border, surface tint,
 * timer and action share the rank's family, so changing the rank changes all of them at once.
 *
 * Deliberately absent: any overflow / three-dot menu, priority labels ("High priority"), and a
 * second colour system — the former urgency palette/glow no longer tints the card (see
 * `docs/DEVELOPMENT_STATUS.md`); `UrgencyLevel` and the timer semantics are unchanged.
 */
@Composable
fun NeedsYouCard(
    stream: WorkStream,
    index: Int,
    kind: AttentionKind? = null,
    /** When this stream started waiting (domain timestamp). Null = treated as just due (00:00:00). */
    waitingSince: Instant? = null,
    /** Shared per-second clock from the section; null renders a static 00:00:00 (previews/tests). */
    now: State<Instant>? = null,
    /** Owning project (identity icon source); null = projectless → the stream's own fallback. */
    project: com.virlin.app.domain.model.Project? = null,
    onFocus: (String) -> Unit = {},
    onCheck: (String) -> Unit = onFocus,
    onDefer: (String) -> Unit = {},
    /** Queue position (1-based); null renders the card without a rank badge (previews/tests). */
    position: Int? = null,
    total: Int = 0,
    onChangePosition: (String) -> Unit = {}
) {
    val v = NeedsYouPriority.visualsFor(position)
    // Colour moves only when the rank does, and then only as a cheap 220ms cross-fade.
    val tween = tween<Color>(durationMillis = 220)
    val surface by animateColorAsState(v.surface, tween, label = "ny_surface")
    val border by animateColorAsState(v.border, tween, label = "ny_border")
    val accent by animateColorAsState(v.accent, tween, label = "ny_accent")
    val container by animateColorAsState(v.container, tween, label = "ny_container")
    val ink by animateColorAsState(NeedsYouPriority.readableInk(v), tween, label = "ny_ink")

    val primary = when (kind) {
        AttentionKind.RETURN_DUE -> "Resume"
        AttentionKind.RESULT_READY -> "Focus now"
        else -> "Check"
    }
    val reason = when (kind) {
        AttentionKind.RETURN_DUE -> "Ready to continue"
        AttentionKind.RESULT_READY -> "Result ready"
        AttentionKind.CHECK_DUE -> "Check due"
        null -> null                                   // no fabricated context line
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("needs_you_card_${stream.id}")
            .background(surface, RoundedCornerShape(14.dp))
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── LEFT: rank badge · contextual icon (fixed columns → every card lines up)
        if (position != null) {
            NeedsYouRankBadge(
                streamId = stream.id, position = position, total = total,
                accent = accent, onAccent = v.onAccent, neutral = v.neutral,
                onClick = { onChangePosition(stream.id) }
            )
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Box(
            modifier = Modifier.size(30.dp).background(container, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // The project's own icon keeps its SEMANTICS; only its container inherits the rank family.
            com.virlin.app.ui.components.ProjectIcon(
                projectId = project?.id ?: stream.id,
                name = project?.title ?: stream.title,
                iconPath = project?.iconPath,
                iconId = project?.iconId,
                size = 22.dp,
                decorative = true
            )
        }
        Spacer(Modifier.width(10.dp))

        // ── CENTER: what needs me, then one restrained context line. Bounded by weight(1f).
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stream.subtitle.ifBlank { stream.title },
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = v.content,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 1.dp)) {
                Text(
                    text = stream.title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = v.subtleContent,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (reason != null) {
                    Text(" · ", fontSize = 11.sp, color = v.subtleContent, maxLines = 1, softWrap = false)
                    Text(
                        text = reason, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = v.subtleContent,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.testTag("needs_you_kind_${stream.id}")
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))

        // ── RIGHT: fixed-width timer, then the action. Both vertically centred, same column on every card.
        WaitingTimerText(streamId = stream.id, waitingSince = waitingSince, now = now, color = ink)
        Spacer(Modifier.width(8.dp))
        if (kind == AttentionKind.RETURN_DUE || kind == AttentionKind.RESULT_READY) {
            Text(
                "+5m", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = v.subtleContent, maxLines = 1, softWrap = false,
                modifier = Modifier.testTag("needs_you_defer_${stream.id}").height(44.dp)
                    .clickable(role = Role.Button) { onDefer(stream.id) }
                    .padding(horizontal = 4.dp, vertical = 14.dp)
            )
        }
        Box(
            modifier = Modifier
                .height(44.dp)
                .testTag("needs_you_primary_${stream.id}")
                .semantics { role = Role.Button; contentDescription = "$primary, ${stream.subtitle}" }
                .clickable { if (kind == AttentionKind.CHECK_DUE || kind == null) onCheck(stream.id) else onFocus(stream.id) },
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .background(container, RoundedCornerShape(10.dp))
                    .border(1.dp, border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(primary.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp, color = ink, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Text("→", fontSize = 10.sp, fontWeight = FontWeight.Black, color = ink)
            }
        }
    }
}

/**
 * The circular rank badge: the number alone communicates queue order — never "#1 of 4",
 * "Priority 1" or "Rank 1" on the card. Filled with the rank accent (neutral ranks read as a
 * quiet outline). 24dp visual inside a 44dp touch target.
 */
@Composable
private fun NeedsYouRankBadge(
    streamId: String,
    position: Int,
    total: Int,
    accent: Color,
    onAccent: Color,
    neutral: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .testTag(needsYouRankTag(streamId))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Attention position $position of $total. Double tap to change." },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(if (neutral) Color.White else accent, CircleShape)
                .border(1.dp, if (neutral) Charcoal.copy(alpha = 0.22f) else accent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                position.toString(),
                fontSize = if (position >= 100) 9.sp else if (position >= 10) 10.5.sp else 12.sp,
                fontWeight = FontWeight.Black,
                color = if (neutral) Charcoal else onAccent,
                maxLines = 1, softWrap = false
            )
        }
    }
}

/**
 * The card's waiting timer in the approved `HH:MM:SS` presentation, tabular figures so a ticking
 * second never moves the CHECK action. Reads the shared ticker HERE (and only here) so a tick
 * recomposes just this text; its semantics carry the human-readable form.
 */
@Composable
private fun WaitingTimerText(
    streamId: String,
    waitingSince: Instant?,
    now: State<Instant>?,
    color: Color
) {
    val elapsed by remember(waitingSince, now) {
        derivedStateOf {
            val since = waitingSince; val n = now
            if (since == null || n == null) 0L else WaitingTime.elapsedSeconds(since, n.value)
        }
    }
    val label = WaitingTime.formatClock(elapsed)
    val spoken = WaitingTime.describe(elapsed)
    Text(
        text = label,
        color = color,
        maxLines = 1,
        softWrap = false,
        // Fixed-width digits: a ticking second never moves the action beside it.
        style = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        modifier = Modifier.testTag("needs_you_timer_$streamId").semantics { contentDescription = spoken }
    )
}
