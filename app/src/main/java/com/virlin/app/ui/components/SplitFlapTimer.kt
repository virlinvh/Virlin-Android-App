package com.virlin.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

// Reference geometry the split-flap look was designed against. Every other metric is
// derived from it, so the timer can render much larger without forking the look.
private val BaseDigitWidth = 52.dp
private val BaseDigitHeight = 74.dp

/** Tile face used by the approved Now-screen timer. */
private val DefaultTileColor = Color(0xFF222325)

/** Fullscreen Focus Clock card face (black-canvas desk clock). */
private val FullscreenTileColor = Color(0xFF212121)

private const val FlipDurationMs = 450

/**
 * Presentation chrome only — never changes flip mathematics.
 * [Compact] = Now Focus card. [Fullscreen] = landscape Focus Clock.
 */
enum class SplitFlapPresentation {
    Compact,
    Fullscreen
}

/** Shared spoken form of the focus time, so every surface announces it identically. */
fun focusTimeContentDescription(timeInSeconds: Int): String {
    val minutes = timeInSeconds / 60
    val seconds = timeInSeconds % 60
    val minuteWord = if (minutes == 1) "minute" else "minutes"
    val secondWord = if (seconds == 1) "second" else "seconds"
    return "Focus invested $minutes $minuteWord $seconds $secondWord"
}

/**
 * Remembers the last elapsed-second baseline for this composition identity.
 * Kept for tests / callers that need n→n+1 policy outside per-digit flip.
 */
@Composable
fun rememberSplitFlapShouldAnimate(timeInSeconds: Int): Boolean {
    val reducedMotion = rememberReducedMotionForSplitFlap()
    val previousHolder = remember { object { var value: Int? = null } }
    return remember(timeInSeconds, reducedMotion) {
        val should = !reducedMotion && splitFlapShouldAnimate(previousHolder.value, timeInSeconds)
        previousHolder.value = timeInSeconds
        should
    }
}

@Composable
private fun rememberReducedMotionForSplitFlap(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
}

/** True when a single digit glyph advances by one step on a clock face (9→0 counts). */
internal fun isAdjacentDigitStep(from: Char, to: Char): Boolean {
    if (!from.isDigit() || !to.isDigit()) return false
    val f = from - '0'
    val t = to - '0'
    return t == (f + 1) % 10
}

/**
 * Shared MM:SS split-flap. Animation engine is identical for Now and fullscreen;
 * [presentation] / digit size only change chrome and scale.
 */
@Composable
fun SplitFlapTimer(
    timeInSeconds: Int,
    modifier: Modifier = Modifier,
    digitWidth: Dp = BaseDigitWidth,
    digitHeight: Dp = BaseDigitHeight,
    presentation: SplitFlapPresentation = SplitFlapPresentation.Compact,
    contentDescription: String? = null
) {
    val mStr = (timeInSeconds / 60).toString().padStart(2, '0')
    val sStr = (timeInSeconds % 60).toString().padStart(2, '0')
    val s = digitHeight / BaseDigitHeight
    val description = contentDescription ?: focusTimeContentDescription(timeInSeconds)

    val tileColor = when (presentation) {
        SplitFlapPresentation.Compact -> DefaultTileColor
        SplitFlapPresentation.Fullscreen -> FullscreenTileColor
    }
    val cornerRadius = when (presentation) {
        SplitFlapPresentation.Compact -> 10.dp * s
        SplitFlapPresentation.Fullscreen -> (digitHeight * 0.11f).coerceIn(20.dp, 28.dp)
    }
    val fontScale = when (presentation) {
        SplitFlapPresentation.Compact -> 1f
        SplitFlapPresentation.Fullscreen -> 1.15f
    }
    val housing = when (presentation) {
        SplitFlapPresentation.Compact -> Modifier
            .background(Color(0xFF121A12).copy(alpha = 0.12f), RoundedCornerShape(22.dp * s))
            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(22.dp * s))
            .padding(horizontal = 12.dp * s, vertical = 10.dp * s)
        SplitFlapPresentation.Fullscreen -> Modifier // black canvas supplies chrome
    }

    // Fullscreen only: on each adjacent elapsed-second tick, bump one shared epoch so
    // BOTH seconds digits flip together (including same-value 5→5). Compact never bumps.
    // Computed during composition (not SideEffect) so digit values + epoch arrive together.
    // First composition / discontinuities leave the epoch alone → SNAP.
    val tickHolder = remember {
        object {
            var previous: Int? = null
            var epoch: Int = 0
        }
    }
    val secondsEpoch = if (presentation != SplitFlapPresentation.Fullscreen) {
        0
    } else {
        remember(timeInSeconds) {
            if (splitFlapShouldAnimate(tickHolder.previous, timeInSeconds)) {
                tickHolder.epoch += 1
            }
            tickHolder.previous = timeInSeconds
            tickHolder.epoch
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clearAndSetSemantics { this.contentDescription = description }
            .then(housing)
    ) {
        SplitFlapDigit(mStr[0], digitWidth, digitHeight, s, tileColor, cornerRadius, fontScale)
        Spacer(modifier = Modifier.width(1.5.dp * s))
        SplitFlapDigit(mStr[1], digitWidth, digitHeight, s, tileColor, cornerRadius, fontScale)

        when (presentation) {
            SplitFlapPresentation.Compact -> CompactColon(digitHeight, s)
            SplitFlapPresentation.Fullscreen -> FullscreenColon(digitWidth, digitHeight, s)
        }

        SplitFlapDigit(
            sStr[0], digitWidth, digitHeight, s, tileColor, cornerRadius, fontScale,
            forceFlipEpoch = secondsEpoch
        )
        Spacer(modifier = Modifier.width(1.5.dp * s))
        SplitFlapDigit(
            sStr[1], digitWidth, digitHeight, s, tileColor, cornerRadius, fontScale,
            forceFlipEpoch = secondsEpoch
        )
    }
}

@Composable
private fun CompactColon(digitHeight: Dp, s: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "colon")
    val colonAlpha by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "colonAlpha"
    )
    val colonScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "colonScale"
    )

    Column(
        modifier = Modifier
            .height(digitHeight)
            .padding(horizontal = 8.dp * s)
            .graphicsLayer {
                alpha = colonAlpha
                scaleX = colonScale
                scaleY = colonScale
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(10.dp * s)
                .shadow(1.dp, CircleShape)
                .background(Color(0xFF162016), CircleShape)
        )
        Spacer(modifier = Modifier.height(14.dp * s))
        Box(
            modifier = Modifier
                .size(10.dp * s)
                .shadow(1.dp, CircleShape)
                .background(Color(0xFF162016), CircleShape)
        )
    }
}

/** Quiet desk-clock colon for the black fullscreen canvas. */
@Composable
private fun FullscreenColon(digitWidth: Dp, digitHeight: Dp, s: Float) {
    val dot = digitWidth * 0.115f
    val gap = digitHeight * 0.18f
    Column(
        modifier = Modifier
            .width(digitWidth * 0.40f)
            .height(digitHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(dot).background(Color(0xFFBFBFBF), CircleShape))
        Spacer(modifier = Modifier.height(gap))
        Box(modifier = Modifier.size(dot).background(Color(0xFFBFBFBF), CircleShape))
    }
}

/**
 * Per-digit mechanical flip. [fromDigit]/[toDigit] are immutable for the whole transition.
 * All five visual layers stay composed every frame — no transparent / missing background.
 *
 * [forceFlipEpoch]: when this integer advances, flip even if [digit] is unchanged
 * (Fullscreen seconds same-value 5→5) or the glyph step is non-adjacent (5→0 on :59→:00).
 * Geometry / layers / timing are unchanged — this is only a trigger.
 */
@Composable
fun SplitFlapDigit(
    digit: Char,
    digitWidth: Dp = BaseDigitWidth,
    digitHeight: Dp = BaseDigitHeight,
    scale: Float = digitHeight / BaseDigitHeight,
    tileColor: Color = DefaultTileColor,
    cornerRadius: Dp = 10.dp * scale,
    fontScale: Float = 1f,
    /** Unused by the digit engine; kept for call-site binary compatibility. */
    animate: Boolean = true,
    forceFlipEpoch: Int = 0
) {
    val reducedMotion = rememberReducedMotionForSplitFlap()

    // Settled identity on first composition — SNAP, never animate into the baseline.
    var displayedDigit by remember { mutableStateOf(digit) }
    var fromDigit by remember { mutableStateOf(digit) }
    var toDigit by remember { mutableStateOf(digit) }
    var handledForceEpoch by remember { mutableStateOf(forceFlipEpoch) }
    // Idle rests at progress 0 with from==to (old top at 0°, new bottom folded at +90°).
    val progress = remember { Animatable(0f) }

    val fontSize = (54f * scale * fontScale).sp
    val innerShadowHeight = 24.dp * scale
    val innerShadowOffset = 12.dp * scale
    val textOffset = (-2).dp * scale

    LaunchedEffect(digit, forceFlipEpoch) {
        val epochBumped = forceFlipEpoch != handledForceEpoch
        if (digit == displayedDigit && !epochBumped) return@LaunchedEffect

        // Force (Fullscreen seconds tick) OR normal adjacent glyph step. Else SNAP.
        val canFlip = !reducedMotion && animate && (
            epochBumped || isAdjacentDigitStep(displayedDigit, digit)
        )

        if (!canFlip) {
            progress.stop()
            handledForceEpoch = forceFlipEpoch
            fromDigit = digit
            toDigit = digit
            displayedDigit = digit
            progress.snapTo(0f)
            return@LaunchedEffect
        }

        // Lock from/to for the entire animation. Same-value force keeps from==to (5→5).
        handledForceEpoch = forceFlipEpoch
        fromDigit = displayedDigit
        toDigit = digit
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = FlipDurationMs, easing = LinearEasing)
        )
        displayedDigit = digit
        fromDigit = digit
        toDigit = digit
        progress.snapTo(0f)
    }

    val p = progress.value.coerceIn(0f, 1f)
    // Same-value force flips keep from==to; drive layers off Animatable running state.
    val flipping = fromDigit != toDigit || progress.isRunning
    val oldTopRotation = when {
        !flipping -> 0f
        p <= 0.5f -> -90f * (p / 0.5f)
        else -> -90f
    }
    val newBottomRotation = when {
        !flipping -> 90f // folded away at rest — static bottom layer remains opaque
        p < 0.5f -> 90f
        else -> 90f * (1f - ((p - 0.5f) / 0.5f))
    }

    val staticTopChar = if (flipping) toDigit else displayedDigit
    val staticBottomChar = if (flipping) fromDigit else displayedDigit
    val flapTopChar = if (flipping) fromDigit else displayedDigit
    val flapBottomChar = if (flipping) toDigit else displayedDigit

    Box(
        modifier = Modifier
            .width(digitWidth)
            .height(digitHeight)
    ) {
        // LAYER 1 — static NEW top (always painted)
        FlapHalf(
            char = staticTopChar,
            isTop = true,
            tileColor = tileColor,
            cornerRadius = cornerRadius,
            fontSize = fontSize,
            innerShadowHeight = innerShadowHeight,
            innerShadowOffset = innerShadowOffset,
            textOffset = textOffset,
            modifier = Modifier.zIndex(0f)
        )

        // LAYER 2 — static OLD bottom (always painted)
        FlapHalf(
            char = staticBottomChar,
            isTop = false,
            tileColor = tileColor,
            cornerRadius = cornerRadius,
            fontSize = fontSize,
            innerShadowHeight = innerShadowHeight,
            innerShadowOffset = innerShadowOffset,
            textOffset = textOffset,
            modifier = Modifier.zIndex(0f)
        )

        // LAYER 3 — rotating OLD top. Hide when edge-on so the static NEW top
        // is seen (GPU otherwise paints an opaque blank backface at -90°).
        if (oldTopRotation > -80f) {
            FlapHalf(
                char = flapTopChar,
                isTop = true,
                tileColor = tileColor,
                cornerRadius = cornerRadius,
                fontSize = fontSize,
                innerShadowHeight = innerShadowHeight,
                innerShadowOffset = innerShadowOffset,
                textOffset = textOffset,
                modifier = Modifier
                    .zIndex(1f)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        rotationX = oldTopRotation
                        cameraDistance = 28f * density * scale.coerceAtLeast(1f)
                    }
            )
        }

        // LAYER 4 — rotating NEW bottom. Hide while still folded (≥+80°) so the
        // static OLD bottom remains the visible lower face until unfold.
        if (newBottomRotation < 80f) {
            FlapHalf(
                char = flapBottomChar,
                isTop = false,
                tileColor = tileColor,
                cornerRadius = cornerRadius,
                fontSize = fontSize,
                innerShadowHeight = innerShadowHeight,
                innerShadowOffset = innerShadowOffset,
                textOffset = textOffset,
                modifier = Modifier
                    .zIndex(1f)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        rotationX = newBottomRotation
                        cameraDistance = 28f * density * scale.coerceAtLeast(1f)
                    }
            )
        }

        // LAYER 5 — centre hinge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp * scale)
                .background(Color(0xFF111113))
                .align(Alignment.Center)
                .zIndex(2f)
        )
    }
}

/**
 * Full digit painted once, then clipped to top or bottom half so both halves share one
 * baseline and centre. No separate half-centered Text.
 */
@Composable
fun FlapHalf(
    char: Char,
    isTop: Boolean,
    modifier: Modifier = Modifier,
    tileColor: Color = DefaultTileColor,
    cornerRadius: Dp = 10.dp,
    fontSize: TextUnit = 54.sp,
    innerShadowHeight: Dp = 24.dp,
    innerShadowOffset: Dp = 12.dp,
    textOffset: Dp = (-2).dp
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                clip = true
                shape = object : Shape {
                    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                        Outline.Rectangle(
                            if (isTop) Rect(0f, 0f, size.width, size.height / 2f)
                            else Rect(0f, size.height / 2f, size.width, size.height)
                        )
                }
            }
    ) {
        // Round only the outer card corners — hinge edge stays sharp so rotation
        // never opens a rounded gap into the green Focus card behind.
        val faceShape = if (isTop) {
            RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius, bottomStart = 0.dp, bottomEnd = 0.dp)
        } else {
            RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = cornerRadius, bottomEnd = cornerRadius)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tileColor, faceShape)
        )

        if (!isTop) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(innerShadowHeight)
                    .align(Alignment.Center)
                    .offset(y = innerShadowOffset)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)
                        )
                    )
            )
        }

        Text(
            text = char.toString(),
            color = Color(0xFFF0EFEA),
            fontSize = fontSize,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = textOffset)
        )
    }
}
