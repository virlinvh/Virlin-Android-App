package com.virlin.app.ui.screens

// Supplied drop-in section. Adapted only where the app already owns the thing being drawn:
//  - `iconContent` is fed the real ProjectIcon, and `filterContent` the real sort control,
//  - `onRank` so the numeral keeps opening the Priority tab, as it does today,
//  - `statusLabel` / `checkLabel` so the app's own wording shows (Check due, Result ready,
//    CHECK / RESUME / FOCUS NOW) instead of one hardcoded string,
//  - the timer text comes from `AttentionTiming` (the approved adaptive +MM:SS / +H:MM:SS form),
//  - the app's existing test tags on the card, the numeral and the capsule,
//  - [VirlinCondensedTitle] supplies the compact bold face for card titles,
//  - the icon is placed against the MEASURED glyph, so `1` overlaps exactly as `3` does,
//  - the numeral's line box is trimmed so an 88sp figure is not sliced by its 100dp area.

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import com.virlin.app.domain.attention.AttentionTiming
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity

/**
 * The compact bold face for card titles. Android ships Roboto Condensed as a device family, so this
 * is a real condensed font with no bundled resource; `CondensedTitleFontTest` proves it resolves
 * narrower than the default sans rather than silently falling back.
 */
val VirlinCondensedTitle: FontFamily = FontFamily(
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Bold),
    Font(DeviceFontFamilyName("sans-serif-condensed"), weight = FontWeight.Medium),
)

/** Map existing reminders into this UI model; use real stable IDs and real due timestamps. */
data class NeedsYouTaskUi(
    val id: String,
    val title: String,
    val sourceAndContext: String,    // e.g. "Codex · MBA Research"
    /** Fallback glyph; [iconContent] takes precedence so the app's real ProjectIcon is used. */
    val iconText: String = "",
    val checkDueAtEpochMillis: Long,
    val showCheckLabel: Boolean = false, // Used as the resting state only when animate=false.
    val iconContent: (@Composable () -> Unit)? = null, // pass existing vector icon for exact visual parity
    /** The app's own reason wording: "Check due", "Result ready", "Ready to continue". */
    val statusLabel: String = "Check due",
    /** The app's own action wording: CHECK / RESUME / FOCUS NOW. */
    val checkLabel: String = "CHECK  →",
)

private data class NeedsPalette(val accent: Color, val ink: Color)

// The broad warm path follows the approved four-card visual: coral, salmon,
// peach, apricot, champagne and light yellow. Card surfaces remain nearly white.
private val warmStops = listOf(
    NeedsPalette(Color(0xFFE9434D), Color(0xFFB52532)),
    NeedsPalette(Color(0xFFF2656D), Color(0xFFAE333B)),
    NeedsPalette(Color(0xFFF48C79), Color(0xFFAA4537)),
    NeedsPalette(Color(0xFFF3AA83), Color(0xFF955235)),
    NeedsPalette(Color(0xFFF2BE84), Color(0xFF855823)),
    NeedsPalette(Color(0xFFE3B75D), Color(0xFF735117)),
    NeedsPalette(Color(0xFFD8AA46), Color(0xFF70510D)),
)

private fun needsPalette(rank: Int, count: Int): NeedsPalette {
    if (count <= 1) return warmStops.first()
    val p = rank.toFloat() / (count - 1)
    val scaled = p.coerceIn(0f, 1f) * (warmStops.size - 1)
    val left = scaled.toInt().coerceIn(0, warmStops.lastIndex)
    val right = (left + 1).coerceAtMost(warmStops.lastIndex)
    val t = scaled - left
    return NeedsPalette(
        lerp(warmStops[left].accent, warmStops[right].accent, t),
        lerp(warmStops[left].ink, warmStops[right].ink, t),
    )
}

/**
 * Place within the existing Now screen's vertical scrolling container.
 * [tasks] should arrive in the application's current priority order. No nested scroll.
 * [nowEpochMillis] should be supplied by the existing clock/ticker, once per second.
 */
@Composable
fun VirlinNeedsYouSection(
    tasks: List<NeedsYouTaskUi>,
    nowEpochMillis: Long,
    onCheck: (String) -> Unit,
    onFilterClick: () -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    titleFontFamily: FontFamily = VirlinCondensedTitle,
    /** Tapping the numeral opens the priority controls, as it does today. Null leaves it inert. */
    onRank: ((String) -> Unit)? = null,
    /** The app's real sort/filter control; the glyph below is only a fallback. */
    filterContent: (@Composable () -> Unit)? = null,
) {
    val transition = rememberInfiniteTransition(label = "Needs You shared motion")
    val gradientPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Gentle numeral gradient drift",
    )
    val capsuleCycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "Timer and Check crossfade",
    )

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Needs You", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp,
                color = Color(0xFF182220))
            Spacer(Modifier.width(9.dp))
            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(0xFFFFF2D1)),
                contentAlignment = Alignment.Center) {
                Text(tasks.size.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF9A5A16))
            }
            Spacer(Modifier.weight(1f))
            Text("Review and respond", fontSize = 11.sp, color = Color(0xFF657077),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(9.dp))
            if (filterContent != null) filterContent() else Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Filter reminders", onClick = onFilterClick),
                contentAlignment = Alignment.Center
            ) { Text("☷", fontSize = 19.sp, color = Color(0xFF657077)) }
        }
        Spacer(Modifier.height(12.dp))
        tasks.forEachIndexed { index, task ->
            key(task.id) {
                NeedsYouCard(
                    task = task,
                    rank = index + 1,
                    colors = needsPalette(index, tasks.size),
                    nowEpochMillis = nowEpochMillis,
                    gradientPhase = if (animate) gradientPhase else .5f,
                    capsulePhase = if (animate) (capsuleCycle + index * .17f) % 1f else null,
                    count = tasks.size,
                    titleFontFamily = titleFontFamily,
                    onCheck = { onCheck(task.id) },
                    onRank = onRank?.let { rankAction -> { rankAction(task.id) } },
                )
            }
            if (index != tasks.lastIndex) Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun NeedsYouCard(
    task: NeedsYouTaskUi,
    rank: Int,
    colors: NeedsPalette,
    nowEpochMillis: Long,
    gradientPhase: Float,
    capsulePhase: Float?,
    count: Int,
    titleFontFamily: FontFamily,
    onCheck: () -> Unit,
    onRank: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(19.dp)
    val nearWhite = lerp(Color.White, colors.accent, .025f)
    val warmEdge = lerp(Color.White, colors.accent, .075f)
    val driftPx = with(LocalDensity.current) { (8.dp).toPx() * gradientPhase }
    Row(
        Modifier.fillMaxWidth().height(106.dp)
            .testTag("needs_you_card_${task.id}")
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color.White, nearWhite, warmEdge)))
            // A low-opacity radial light stays clipped INSIDE each card.
            .drawBehind {
                drawRect(Brush.radialGradient(
                    colors = listOf(colors.accent.copy(alpha = .105f),
                        colors.accent.copy(alpha = .025f), Color.Transparent),
                    center = Offset(size.width * (.26f + .42f * gradientPhase), size.height * .52f),
                    radius = size.height * 1.35f,
                ))
            }
            .border(1.dp, colors.accent.copy(alpha = .27f), shape)
            .padding(start = 13.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The icon sits at the lower-right edge of the numeral, not in its own column.
        // How wide the figure actually draws, so the icon sits ON its right edge whatever the
        // digit is: a `1` is far narrower than a `3`, and a fixed end-padding leaves it floating
        // beside the glyph instead of overlapping it.
        var glyphWidthPx by remember(rank) { mutableStateOf(0f) }
        val density = LocalDensity.current
        val glyphScale = if (rank == 1) 1.28f else 1.08f
        val iconOffset = with(density) { (glyphWidthPx * glyphScale).toDp() } - 26.dp
        Box(
            Modifier
                .size(width = 82.dp, height = 100.dp)
                .testTag(needsYouRankTag(task.id))
                .then(
                    if (onRank != null) Modifier
                        .clickable(role = Role.Button, onClickLabel = "Change priority", onClick = onRank)
                        .semantics { contentDescription = "Attention position $rank of $count. Double tap to change priority." }
                    else Modifier
                )
        ) {
            Text(rank.toString(), fontSize = if (rank >= 10) 54.sp else 88.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-2).sp,
                // The reference uses a broad, heavy figure; give the system glyph
                // enough width without shifting the title's reserved column.
                modifier = Modifier.align(Alignment.CenterStart)
                    .graphicsLayer(scaleX = if (rank == 1) 1.28f else 1.08f,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f))
                    .drawWithContent {
                        drawContent()
                        // Add less than one dp of visual stem weight; the gradient
                        // stays INSIDE the glyph, unlike an external glow or outline.
                        drawContext.canvas.save()
                        drawContext.canvas.translate(.7.dp.toPx(), 0f)
                        drawContent()
                        drawContext.canvas.restore()
                    },
                style = TextStyle(
                    // An 88sp figure's default line box is taller than the 100dp it sits in, which
                    // clipped the numeral's top. Trim the line to the glyph.
                    lineHeight = 88.sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                        alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                        trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
                    ),
                    brush = Brush.verticalGradient(
                        colors = listOf(lerp(colors.accent, Color.White, .34f),
                            lerp(colors.accent, Color.White, .47f),
                            lerp(colors.accent, Color.White, .74f)),
                        startY = -14f + driftPx,
                        endY = with(LocalDensity.current) { 100.dp.toPx() } + driftPx,
                    ),
                    shadow = Shadow(colors.accent.copy(alpha = .12f),
                        Offset(0f, 2f + driftPx * .1f), blurRadius = 7f),
                ), maxLines = 1,
                onTextLayout = { glyphWidthPx = it.size.width.toFloat() })
            // Reference geometry: icon CENTER sits near the figure's right edge
            // and at its vertical MIDPOINT (especially noticeable on the 3).
            // The circle overlaps the glyph, while the full rank stays legible.
            Box(Modifier.align(Alignment.CenterStart)
                .offset(x = iconOffset.coerceIn(0.dp, 42.dp))
                .size(40.dp).clip(CircleShape)
                .background(lerp(Color.White, colors.accent, .13f)),
                contentAlignment = Alignment.Center) {
                if (task.iconContent != null) task.iconContent.invoke()
                else Text(task.iconText, fontSize = 15.sp,
                    color = Color(0xFF20312D), maxLines = 1)
            }
        }
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(task.title, color = Color(0xFF192321), fontWeight = FontWeight.Bold,
                fontFamily = titleFontFamily, fontSize = 15.sp,
                letterSpacing = (-.35).sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("${task.sourceAndContext} · ${task.statusLabel}", color = Color(0xFF667486),
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        NeedsActionCapsule(task, nowEpochMillis, colors, capsulePhase, onCheck)
    }
}

@Composable
private fun NeedsActionCapsule(
    task: NeedsYouTaskUi,
    nowEpochMillis: Long,
    colors: NeedsPalette,
    capsulePhase: Float?,
    onCheck: () -> Unit,
) {
    // The approved Virlin format (`+MM:SS`, gaining the hour only past an hour), from the same
    // `AttentionTiming` the rest of the app uses - never a second time implementation.
    val timeText = AttentionTiming.format(
        java.time.Instant.ofEpochMilli(task.checkDueAtEpochMillis),
        java.time.Instant.ofEpochMilli(nowEpochMillis),
        adaptive = true
    )
    val spokenTime = AttentionTiming.describe(
        java.time.Instant.ofEpochMilli(task.checkDueAtEpochMillis),
        java.time.Instant.ofEpochMilli(nowEpochMillis)
    )
    val shape = RoundedCornerShape(15.dp)
    Box(
        Modifier.width(108.dp).height(48.dp)
            .testTag("needs_you_primary_${task.id}")
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(
                lerp(Color.White, colors.accent, .10f),
                lerp(Color.White, colors.accent, .19f))))
            .border(1.dp, colors.accent.copy(alpha = .18f), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = "${task.checkLabel.substringBefore(" ")} ${task.title}. $spokenTime"
            }
            .clickable(role = Role.Button, onClickLabel = "Check ${task.title}", onClick = onCheck),
        contentAlignment = Alignment.Center,
    ) {
        val checkAlpha = if (capsulePhase == null) {
            if (task.showCheckLabel) 1f else 0f
        } else when {
            capsulePhase < .43f -> 0f
            capsulePhase < .51f -> (capsulePhase - .43f) / .08f
            capsulePhase < .91f -> 1f
            else -> 1f - (capsulePhase - .91f) / .09f
        }.coerceIn(0f, 1f)
        Row(Modifier.graphicsLayer(alpha = 1f - checkAlpha),
            verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(15.dp)) {
                    val r = size.minDimension * .40f
                    drawCircle(colors.ink, radius = r, style = Stroke(width = 1.6.dp.toPx()))
                    drawLine(colors.ink, center, Offset(center.x, center.y - r * .57f),
                        strokeWidth = 1.5.dp.toPx())
                    drawLine(colors.ink, center, Offset(center.x + r * .48f, center.y + r * .18f),
                        strokeWidth = 1.5.dp.toPx())
                }
                Spacer(Modifier.width(4.dp))
                Text(timeText, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = colors.ink, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
        Text(task.checkLabel, modifier = Modifier.graphicsLayer(alpha = checkAlpha),
            fontWeight = FontWeight.Bold, fontSize = 12.sp,
            color = colors.ink, maxLines = 1)
    }
}

