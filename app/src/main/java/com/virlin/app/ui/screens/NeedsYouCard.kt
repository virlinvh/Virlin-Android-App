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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
import com.virlin.app.domain.attention.AttentionTiming
import com.virlin.app.model.WorkStream
import com.virlin.app.ui.theme.Charcoal
import java.time.Instant

/**
 * The approved COMPACT Needs You attention card (Phase 01) in its rank colour identity (Phase 02).
 *
 * ONE row, one alignment grid, fixed columns — a card never shifts because a title is longer:
 *
 * ```
 * [icon]  Navigation · Route structure     +58:43  [ #1 | CHECK → ]
 *         Claude · Virlin · Check due
 * ```
 *
 * LEFT   the project's own icon
 * CENTER task title (1 line, ellipsised) over one restrained secondary line (source · reason)
 * RIGHT  the waiting timer (tabular figures, status — never part of the button) and ONE compact
 *        action pill that carries BOTH the queue position and the CHECK / Resume / Focus now
 *        action. There is no separate rank bubble anywhere on the card: the rank is shown once,
 *        inside the pill. The pill is one visual control with TWO interaction targets — `#n`
 *        opens the existing position selector, `CHECK →` runs the existing action — and each is
 *        exposed to TalkBack separately.
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
    /** The item's attention target (`WorkStream.checkAt`). Null falls back to [waitingSince]. */
    dueAt: Instant? = null,
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
            // Clipped to the card's own shape so the left accent below follows the same corners.
            .clip(RoundedCornerShape(14.dp))
            .background(surface, RoundedCornerShape(14.dp))
            // A 6dp accent along the LEFT edge only, in the card's existing colour — drawn over
            // the surface and under the hairline border, so it reads as part of the border itself.
            .drawBehind { drawRect(accent, size = Size(6.dp.toPx(), size.height)) }
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── LEFT: the contextual icon alone — the space the rank bubble used to take now belongs
        // to the title (the rank moved into the action pill on the right).
        Box(
            modifier = Modifier.size(34.dp).background(container, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // The project's own icon keeps its SEMANTICS; only its container inherits the rank family.
            com.virlin.app.ui.components.ProjectIcon(
                projectId = project?.id ?: stream.id,
                name = project?.title ?: stream.title,
                iconPath = project?.iconPath,
                iconId = project?.iconId,
                size = 25.dp,
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
        // `dueAt` is the temporal truth (Phase 05); the card only renders `dueAt − now`.
        AttentionTimerText(streamId = stream.id, dueAt = dueAt ?: waitingSince, now = now, color = ink)
        Spacer(Modifier.width(8.dp))
        if (kind == AttentionKind.RETURN_DUE || kind == AttentionKind.RESULT_READY) {
            Text(
                "+5m", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = v.subtleContent, maxLines = 1, softWrap = false,
                modifier = Modifier.testTag("needs_you_defer_${stream.id}").height(44.dp)
                    .clickable(role = Role.Button) { onDefer(stream.id) }
                    .padding(horizontal = 4.dp, vertical = 14.dp)
            )
        }
        NeedsYouActionPill(
            streamId = stream.id,
            position = position,
            total = total,
            primary = primary,
            actionLabel = "$primary, ${stream.subtitle}",
            accent = accent, onAccent = v.onAccent, container = container, border = border,
            ink = ink, neutral = v.neutral,
            onPosition = { onChangePosition(stream.id) },
            onAction = { if (kind == AttentionKind.CHECK_DUE || kind == null) onCheck(stream.id) else onFocus(stream.id) }
        )
    }
}

/**
 * The combined attention control: ONE pill, two targets.
 *
 * ```
 * [ #1 | CHECK → ]
 * ```
 *
 * `#n` carries the queue position and opens the existing "Move to position" selector; `CHECK →`
 * runs the existing action. They share one surface, one border and one corner radius so the card
 * reads a single control: one capsule in the rank's own colour, the rank block in the full accent
 * with both halves on one fill, parted only by a thin rule. The visible pill stays
 * 34dp high to keep the card compact, while each half is tappable across a 44dp row. Ranks 11+ (and unranked cards) render the quiet neutral treatment; when no
 * position is known the pill is just the action, exactly as before.
 *
 * Rank is never communicated by colour alone — the number is always written out.
 */
@Composable
private fun NeedsYouActionPill(
    streamId: String,
    position: Int?,
    total: Int,
    primary: String,
    actionLabel: String,
    accent: Color,
    onAccent: Color,
    container: Color,
    border: Color,
    ink: Color,
    neutral: Boolean,
    onPosition: () -> Unit,
    onAction: () -> Unit
) {
    // ONE block in the card's own urgency colour: both halves share the SAME fill and a thin
    // vertical rule is all that separates them. The corner radius echoes the card's own
    // 14dp so the control sits inside it as the same family of shape, not a foreign capsule.
    val radius = 11.dp
    val whole = RoundedCornerShape(radius)
    val leftHalf = RoundedCornerShape(topStart = radius, bottomStart = radius)
    val rightHalf = if (position == null) whole else RoundedCornerShape(topEnd = radius, bottomEnd = radius)
    // ONE fill for both halves: the rank side is not a darker block, so the control reads as a
    // single button with a divider rather than two buttons pushed together.
    val actionFill = if (neutral) container else accent
    val actionInk = if (neutral) ink else onAccent
    val hairline = if (neutral) border else Color.White.copy(alpha = 0.55f)
    // The pill is 34dp of paint inside a 44dp row: each half is tappable across the full 44dp, so
    // the control stays compact without shrinking the touch targets.
    Row(modifier = Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (position != null) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .testTag(needsYouRankTag(streamId))
                    .clickable(role = Role.Button, onClick = onPosition)
                    .semantics { contentDescription = "Attention position $position of $total. Double tap to change." },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        // One fixed width, so single- and double-digit ranks keep the same column
                        // on every card and the timers above them stay aligned.
                        .widthIn(min = 32.dp)
                        .clip(leftHalf)
                        .background(actionFill)
                        .then(if (neutral) Modifier.border(1.dp, border, leftHalf) else Modifier)
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "#$position",
                        fontSize = if (position >= 100) 9.sp else if (position >= 10) 10.sp else 11.sp,
                        fontWeight = FontWeight.Black,
                        color = actionInk,
                        maxLines = 1, softWrap = false
                    )
                }
            }
        }
        if (position != null) {
            // The only separation between the halves: a short rule over the shared fill.
            Box(Modifier.height(30.dp).background(actionFill), contentAlignment = Alignment.Center) {
                Box(Modifier.width(1.dp).height(17.dp).background(hairline))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .testTag("needs_you_primary_$streamId")
                .semantics { role = Role.Button; contentDescription = actionLabel }
                .clickable(onClick = onAction),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .clip(rightHalf)
                    .background(actionFill)
                    .then(if (neutral) Modifier.border(1.dp, border, rightHalf) else Modifier)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(primary.uppercase(), fontSize = 9.5.sp, fontWeight = FontWeight.Black, letterSpacing = 0.3.sp, color = actionInk, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
                Text("→", fontSize = 9.5.sp, fontWeight = FontWeight.Black, color = actionInk)
            }
        }
    }
}

/**
 * The card's attention timer (Phase 05): `HH:MM:SS` remaining while WAITING, `00:00:00` at DUE and
 * `+MM:SS` once OVERDUE, gaining the hour segment only past an hour (`+1:02:05`) — derived from
 * `dueAt − now`, never counted. Tabular figures, so a
 * ticking second never moves the CHECK action. Reads the shared ticker HERE (and only here) so a
 * tick recomposes just this text; its semantics carry the human-readable form.
 */
@Composable
private fun AttentionTimerText(
    streamId: String,
    dueAt: Instant?,
    now: State<Instant>?,
    color: Color
) {
    val label by remember(dueAt, now) {
        derivedStateOf { AttentionTiming.format(dueAt, now?.value ?: dueAt ?: Instant.EPOCH, adaptive = true) }
    }
    val spoken by remember(dueAt, now) {
        derivedStateOf { AttentionTiming.describe(dueAt, now?.value ?: dueAt ?: Instant.EPOCH) }
    }
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
