package com.virlin.app.ui.screens

// Supplied drop-in card. Adapted only where the existing screen needs it:
//  - `headline` so the big line can be the WorkStream while the small line stays the Project,
//  - `primaryLabel` so an EXTERNAL stream still reads HAND OFF instead of COMPLETE,
//  - `motto` made optional (nothing in the domain supplies that sentence),
//  - the app's existing test tags on the context block, invested time, NEXT row and actions.

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val Ink = Color(0xFF21082F)
private val Forest = Color(0xFF542277)
private val Muted = Color(0xFF765989)
private val Live = Color(0xFF00C974)

private val FocusSans = NowApprovedFont

/**
 * Card-only replacement. Caller keeps timer ticking, completion, and exit behavior.
 * `timerContent` may render the application's existing animated flip timer;
 * the fallback renders the same four-tile visual using `elapsedTime`.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun VirlinCurrentFocusCard(
    projectName: String,
    /** The big line. Defaults to [projectName] so the supplied call shape still works. */
    headline: String = projectName,
    taskName: String,
    elapsedTime: String,
    investedTime: String,
    nextAction: String,
    nextEstimate: String,
    onLeave: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    isFocusActive: Boolean = true,
    /** Shown in place of LEAVE while Focus is paused. */
    onResume: (() -> Unit)? = null,
    /** COMPLETE for human work, HAND OFF when the domain says the stream is EXTERNAL. */
    primaryLabel: String = "COMPLETE",
    /** Optional encouragement row; omitted when nothing supplies it. */
    motto: String? = null,
    onContextClick: (() -> Unit)? = null,
    timerContent: (@Composable () -> Unit)? = null,
    hierarchyLabels: List<String> = listOf(headline),
    /** Height the whole card should fit in (px, read during layout); the timer gives way first. */
    maxHeightPx: (() -> Int?)? = null,
    /** COMPLETE was confirmed: the button turns into a green COMPLETED and stops taking taps. */
    completing: Boolean = false,
) {
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val motion = android.animation.ValueAnimator.areAnimatorsEnabled() &&
        lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val cardShape = NowFocusOutline
    val cardSize = remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { cardSize.value = it }
            .shadow(7.dp, cardShape, ambientColor = Color(0xFF39877B), spotColor = Color(0xFF39877B))
            .clip(cardShape),
    ) {
        // Static reference artwork; only the real timer and pressed controls animate.
        NowFocusSurface(modifier = Modifier.matchParentSize())

        // Short screens tighten the spacing (never the content) so the whole card still fits.
        val compact = fitIsCompact(maxHeightPx, CompactBelow)
        val padV = if (compact) 14.dp else CardPadV
        val padH = if (compact) 18.dp else 24.dp
        val gap = if (compact) .45f else 1f
        val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { (padV * 2).roundToPx() }
        FitTimerColumn(
            maxHeightPx = maxHeightPx?.let { budget -> { budget()?.minus(padPx) } },
            modifier = Modifier.padding(horizontal = padH, vertical = padV),
            top = {
            NowFocusPillRow(
                cardSize, padH, padV,
                modifier = Modifier.fillMaxWidth().testTag(FocusPillRowTag),
                lowerCost = if (compact) 8f else 2f,
                belowRoom = if (compact) 12.dp * gap - 2.dp else 0.dp,
                start = {
                    Box(Modifier.testTag(FocusLivePillTag)) {
                        LivePill(if (isFocusActive) "FOCUSING NOW" else "FOCUS PAUSED", breathing = false, compact = compact)
                    }
                },
                end = {
                    // `investedTime` already reads "Task total · 18m" — the caller formats it.
                    MetricPill(
                        label = if (investedTime.isNotBlank()) investedTime
                        else if (isFocusActive) "FOCUS ACTIVE" else "FOCUS PAUSED",
                        active = isFocusActive,
                        clock = investedTime.isNotBlank(),
                        labelModifier = if (investedTime.isNotBlank()) Modifier.testTag(FocusInvestedTag) else Modifier,
                        compact = compact,
                        modifier = Modifier.testTag(FocusMetricPillTag),
                    )
                },
            )

            Spacer(Modifier.height(12.dp * gap))
            val trail = hierarchyLabels.filter { it.isNotBlank() }
            val taskHero = taskName.isNotBlank()
            val projectHero = !taskHero && projectName.isNotBlank()
            // With neither a task nor a project, the deepest hierarchy level is the subject itself.
            val hero = when {
                taskHero -> taskName
                projectHero -> projectName
                else -> trail.lastOrNull().orEmpty()
            }
            val crumbs = if (!taskHero && !projectHero) trail.dropLast(1) else trail
            // No rectangular ripple over the title: a gentle press dip is the only feedback.
            val contextInteraction = remember { MutableInteractionSource() }
            val contextPressed by contextInteraction.collectIsPressedAsState()
            val contextAlpha by animateFloatAsState(if (contextPressed) .6f else 1f,
                tween(if (contextPressed) 80 else 200), label = "focusContextPress")
            val contextScale by animateFloatAsState(
                if (contextPressed && android.animation.ValueAnimator.areAnimatorsEnabled()) .985f else 1f,
                tween(if (contextPressed) 80 else 200), label = "focusContextScale")
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag(FocusContextTag)
                    .then(if (onContextClick != null) Modifier.clickable(
                        interactionSource = contextInteraction, indication = null,
                        onClickLabel = "Open WorkStream") { onContextClick() } else Modifier)
                    .graphicsLayer { alpha = contextAlpha; scaleX = contextScale; scaleY = contextScale }
            ) {
                val projectChip = taskHero && projectName.isNotBlank()
                if (projectChip || crumbs.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 1.dp else 4.dp),
                        modifier = Modifier.fillMaxWidth().testTag("focus_hierarchy"),
                    ) {
                        if (projectChip) ProjectChip(projectName)
                        crumbs.forEachIndexed { index, fullName ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (index > 0 || projectChip) CrumbSeparator()
                                val parent = index == crumbs.lastIndex
                                Text(
                                    compactFocusHierarchyLabel(fullName),
                                    color = if (parent) Forest else Muted,
                                    fontFamily = FocusSans,
                                    fontSize = 12.5.sp, lineHeight = 18.sp,
                                    fontWeight = if (parent) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .padding(vertical = if (compact) 1.dp else 3.dp)
                                        .semantics { contentDescription = fullName },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp * gap))
                }
                if (hero.isNotBlank()) {
                    val type = heroType(hero, compact)
                    Text(
                        hero,
                        color = Ink,
                        fontFamily = FocusSans,
                        fontWeight = FontWeight.Black,
                        fontSize = type.size, lineHeight = type.line, letterSpacing = type.tracking,
                        maxLines = if (taskHero) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = when {
                            taskHero -> Modifier.testTag(FocusTaskTag)
                            projectHero -> Modifier.testTag(FocusProjectTag)
                            else -> Modifier
                        },
                    )
                }
            }

            Canvas(Modifier.align(Alignment.CenterHorizontally).padding(start = 54.dp).size(76.dp, 8.dp)) {
                val highlight = Color(0xFFFFE000)
                drawLine(highlight, Offset(0f, size.height*.6f), Offset(size.width*.8f, 1f), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(highlight, Offset(size.width*.35f, size.height*.95f), Offset(size.width, size.height*.45f), 2.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Spacer(Modifier.height(10.dp * gap))
            },
            timer = { if (timerContent != null) timerContent() else FlipTime(elapsedTime) },
            bottom = {
            Spacer(Modifier.height(10.dp * gap))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(Color(0xFF00CBB3), CircleShape))
                Spacer(Modifier.width(8.dp))
                    Text("CURRENT SESSION", color = Ink, fontFamily = FocusSans,
                    fontSize = 10.5.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(5.dp).background(Color(0xFF00CBB3), CircleShape))
            }

            if (motto != null) {
                Spacer(Modifier.height(14.dp * gap))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = .70f))
                        .border(1.dp, Color.White, RoundedCornerShape(18.dp))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✦", color = Forest, fontSize = 16.sp)
                    Spacer(Modifier.width(9.dp))
                    Text(motto, color = Ink, fontFamily = FocusSans, fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium)
                }
            }

            if (nextAction.isNotBlank()) {
                Spacer(Modifier.height(14.dp * gap))
                NextRow(nextAction, nextEstimate)
            }

            Spacer(Modifier.height(14.dp * gap))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Paused: the same slot continues the held time instead of leaving.
                if (!isFocusActive && onResume != null) FocusAction("RESUME", Icons.Rounded.PlayArrow, onResume,
                    primary = false, modifier = Modifier.weight(1f).testTag(FocusResumeTag), compact = compact)
                else FocusAction("LEAVE", Icons.AutoMirrored.Rounded.Logout, onLeave, primary = false,
                    modifier = Modifier.weight(1f).testTag(FocusLeaveTag), compact = compact)
                val complete = primaryLabel == "COMPLETE"
                FocusAction(
                    primaryLabel,
                    if (complete) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.Send,
                    onComplete, primary = true,
                    modifier = Modifier.weight(1.3f).testTag(if (complete) FocusCompleteTag else FocusHandOffTag),
                    compact = compact,
                    done = complete && completing,
                )
            }
            })
    }
}

private val CardPadV = 22.dp
/** Card budgets shorter than this (small phones, large text) switch to compact spacing. */
private val CompactBelow = 400.dp

private class HeroType(val size: TextUnit, val line: TextUnit, val tracking: TextUnit)

/** Long titles step down in size so a full sentence still reads as one calm block. */
private fun heroType(text: String, compact: Boolean = false): HeroType = when {
    compact && text.length <= 28 -> HeroType(23.sp, 28.sp, (-0.4).sp)
    compact && text.length <= 64 -> HeroType(20.sp, 25.sp, (-0.3).sp)
    compact -> HeroType(18.sp, 23.sp, (-0.15).sp)
    text.length <= 28 -> HeroType(27.sp, 33.sp, (-0.5).sp)
    text.length <= 64 -> HeroType(23.sp, 29.sp, (-0.35).sp)
    text.length <= 120 -> HeroType(20.sp, 26.sp, (-0.2).sp)
    else -> HeroType(18.sp, 24.sp, (-0.1).sp)
}

/**
 * Soft green and warm-yellow light drifting under the card. It lives in its own layer and
 * reads the phase only while drawing, so the timer and text above are never recomposed or
 * re-recorded by it.
 */
@Composable
private fun FocusAurora(animate: Boolean, modifier: Modifier) {
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "focusAurora").animateFloat(
            0f, (2 * PI).toFloat(),
            infiniteRepeatable(tween(20_000, easing = LinearEasing)),
            label = "focusAuroraPhase",
        )
    } else {
        remember { mutableFloatStateOf(0.9f) }
    }
    Box(
        modifier
            .graphicsLayer()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val reach = max(w, h)
                val base = Brush.linearGradient(
                    0.0f to Color(0xFFF8FFF4),
                    0.55f to Color(0xFFE9FCE7),
                    1.0f to Color(0xFFF2FFEF),
                    start = Offset.Zero, end = Offset(w, h),
                )
                val greenR = reach * .62f
                val yellowR = reach * .56f
                val limeR = reach * .44f
                val green = Brush.radialGradient(
                    listOf(Color(0x8AA6EBB4), Color(0x33A6EBB4), Color(0x00A6EBB4)),
                    center = Offset.Zero, radius = greenR)
                val yellow = Brush.radialGradient(
                    listOf(Color(0xBFFFE88C), Color(0x55FFE88C), Color(0x00FFE88C)),
                    center = Offset.Zero, radius = yellowR)
                val lime = Brush.radialGradient(
                    listOf(Color(0x66D6F5A0), Color(0x22D6F5A0), Color(0x00D6F5A0)),
                    center = Offset.Zero, radius = limeR)
                val wave = Path().apply {
                    moveTo(-w * .1f, h * .52f)
                    cubicTo(w * .17f, h * .76f, w * .40f, h * .12f, w * 1.1f, h * .36f)
                    lineTo(w * 1.1f, h)
                    lineTo(-w * .1f, h)
                    close()
                }
                val gloss = Brush.linearGradient(
                    listOf(Color(0x40FFFFFF), Color(0x2898F0A4), Color(0x26FFFFFF)),
                    Offset.Zero, Offset(w, h))
                onDrawBehind {
                    val t = phase.value
                    drawRect(base)
                    blob(green, greenR, w * (.16f + .14f * sin(t)), h * (.70f + .10f * cos(t)))
                    blob(yellow, yellowR, w * (.78f + .16f * cos(t + 1.3f)), h * (.18f + .12f * sin(2 * t + .4f)))
                    blob(lime, limeR, w * (.50f + .22f * sin(t + 2.4f)), h * (.42f + .16f * cos(t + .8f)))
                    translate(left = w * .04f * sin(t + .6f)) { drawPath(wave, gloss) }
                }
            }
    )
}

private fun DrawScope.blob(brush: Brush, radius: Float, x: Float, y: Float) {
    translate(x, y) { drawCircle(brush, radius = radius, center = Offset.Zero) }
}

@Composable
internal fun LivePill(label: String, breathing: Boolean, compact: Boolean = false) {
    val shape = NowPillShape
    val halo: State<Float> = if (breathing) {
        rememberInfiniteTransition(label = "focusLive").animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "focusLiveHalo",
        )
    } else {
        remember { mutableFloatStateOf(-1f) }
    }
    Row(
        Modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFE2FFE8), Color(0xFFBEF9D9))))
            .padding(start = if (compact) 7.dp else 9.dp, end = if (compact) 9.dp else 12.dp,
                top = if (compact) 5.dp else 7.dp, bottom = if (compact) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val c = center
            val p = halo.value
            if (p >= 0f) {
                drawCircle(Live.copy(alpha = .38f * (1f - p)), radius = size.minDimension * (.26f + .26f * p), center = c)
            }
            val r = size.minDimension * .34f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF9BFFB0), Live, Color(0xFF00893F)),
                center = Offset(c.x - r * .35f, c.y - r * .4f), radius = r * 1.6f), radius = r, center = c)
        }
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        Text(label, color = Ink, fontFamily = FocusSans, fontSize = if (compact) 11.sp else 12.sp,
            letterSpacing = if (compact) .1.sp else .3.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

@Composable
private fun MetricPill(
    label: String,
    active: Boolean,
    clock: Boolean,
    labelModifier: Modifier = Modifier,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = NowPillShape
    val tint = if (active) Forest else Muted
    Row(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFF9EFFF), Color(0xFFEADCF8))))
            .border(1.5.dp, Color(0xFFD9C2F7), shape)
            .padding(horizontal = if (compact) 9.dp else 12.dp, vertical = if (compact) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (clock) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = Ink, modifier = Modifier.size(if (compact) 13.dp else 15.dp))
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        }
        Spacer(Modifier.width(if (compact) 4.dp else 6.dp))
        ShrinkToFitText(label, TextStyle(color = Ink, fontFamily = FocusSans, fontWeight = FontWeight.Medium),
            maxSize = if (compact) 11.sp else 12.sp, minSize = 8.5.sp, modifier = labelModifier)
    }
}

/** One line always: steps the size down (never below [minSize]) until [text] fits the width it gets. */
@Composable
private fun ShrinkToFitText(text: String, style: TextStyle, maxSize: TextUnit, minSize: TextUnit, modifier: Modifier = Modifier) {
    BoxWithConstraints {
        val measurer = rememberTextMeasurer()
        val limit = constraints.maxWidth
        val size = remember(text, style, maxSize, minSize, limit) {
            var s = maxSize.value
            while (s > minSize.value && limit != Constraints.Infinity &&
                measurer.measure(text, style.copy(fontSize = s.sp), maxLines = 1, softWrap = false).size.width > limit) s -= .5f
            s.sp
        }
        Text(text, style = style.copy(fontSize = size), maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, modifier = modifier)
    }
}

@Composable
private fun ProjectChip(name: String) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Forest, fontFamily = FocusSans, fontSize = 12.5.sp, lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(FocusProjectTag))
    }
}

@Composable
private fun CrumbSeparator() {
    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Muted.copy(alpha = .55f),
        modifier = Modifier.padding(horizontal = 1.dp).size(14.dp))
}

@Composable
private fun NextRow(action: String, estimate: String) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color.White.copy(alpha = .78f))
            .border(1.dp, Color.White, shape)
            .padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Forest), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("NEXT", color = Muted, fontFamily = FocusSans, fontSize = 9.5.sp,
                letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
            Text(action, color = Ink, fontFamily = FocusSans, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (estimate.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(estimate, color = Muted, fontFamily = FocusSans, fontSize = 11.5.sp, maxLines = 1)
        }
    }
}

@Composable
private fun FlipTime(value: String) {
    val digits = value.filter(Char::isDigit).padStart(4, '0').takeLast(4)
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val tileWidth: Dp = if (maxWidth < 340.dp) 47.dp else 55.dp
        val tileHeight: Dp = if (maxWidth < 340.dp) 66.dp else 76.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlipDigit(digits[0], tileWidth, tileHeight)
            Spacer(Modifier.width(3.dp))
            FlipDigit(digits[1], tileWidth, tileHeight)
            Text(":", Modifier.padding(horizontal = 6.dp), color = Forest,
                fontSize = 40.sp, fontWeight = FontWeight.Bold)
            FlipDigit(digits[2], tileWidth, tileHeight)
            Spacer(Modifier.width(3.dp))
            FlipDigit(digits[3], tileWidth, tileHeight)
        }
    }
}

@Composable
private fun FlipDigit(digit: Char, width: Dp, height: Dp) {
    Box(
        Modifier.size(width, height).clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1E3030), Color(0xFF102221))))
    ) {
        Text(digit.toString(), Modifier.align(Alignment.Center), color = Color.White,
            fontSize = if (width < 50.dp) 43.sp else 50.sp,
            lineHeight = 54.sp, fontWeight = FontWeight.Black)
        Canvas(Modifier.matchParentSize()) {
            drawLine(Color.Black.copy(alpha = .43f),
                Offset(0f, size.height / 2f),
                Offset(size.width, size.height / 2f),
                strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun FocusAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier,
    compact: Boolean = false,
    /** Completed: a green fill sweeps across, the label becomes ✓ COMPLETED and taps stop. */
    done: Boolean = false,
    shape: Shape = if (primary) NowCompleteButtonShape else NowLeaveButtonShape,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = android.animation.ValueAnimator.areAnimatorsEnabled()
    val pressScale by animateFloatAsState(if (pressed && motion) .97f else 1f, tween(120), label = "focusActionPress")
    val fill by animateFloatAsState(if (done) 1f else 0f,
        if (motion) tween(520, easing = FastOutSlowInEasing) else snap(), label = "focusActionDone")
    val checkScale by animateFloatAsState(if (done) 1f else 0f,
        if (motion) spring(dampingRatio = .45f, stiffness = 420f) else snap(), label = "focusActionCheck")
    val pop = remember { Animatable(1f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(done) {
        if (!done) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (motion) {
            pop.animateTo(1.06f, tween(140))
            pop.animateTo(1f, spring(dampingRatio = .4f, stiffness = 380f))
        }
    }
    Row(
        modifier.height(if (compact) 48.dp else 54.dp)
            .graphicsLayer { val s = pressScale * pop.value; scaleX = s; scaleY = s }
            .shadow(5.dp, shape, ambientColor = Color(0xFF0ABFAE), spotColor = Color(0xFF0ABFAE))
            .background(if (primary) lerp(Color(0xFFC7104C), DoneBase, fill) else Color(0xFFA141EF), shape)
            .padding(bottom = 5.dp)
            .clip(shape)
            .background(
                if (primary) Brush.verticalGradient(listOf(Color(0xFFFF819E), Color(0xFFFF3779)))
                else Brush.verticalGradient(listOf(Color.White, Color(0xFFEBDCFB)))
            )
            .drawBehind {
                if (fill > 0f) drawRect(DoneFill, size = Size(size.width * fill, size.height))
            }
            .border(1.5.dp, if (primary) lerp(Color(0xFFFFB0CA), Color(0xFFA8F0C6), fill) else Color(0xFFCD9CFF), shape)
            .clickable(enabled = !done, interactionSource = interaction, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (primary) Color.White else Forest
        if (done) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = tint,
                modifier = Modifier.size(18.dp).graphicsLayer { scaleX = checkScale; scaleY = checkScale })
            Spacer(Modifier.width(6.dp))
        }
        Text(if (done) "COMPLETED" else label, color = tint, fontFamily = FocusSans, fontSize = 15.sp,
            letterSpacing = .4.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

private val DoneBase = Color(0xFF0E7F48)
private val DoneFill = Brush.verticalGradient(listOf(Color(0xFF5FE39A), Color(0xFF14B866)))
