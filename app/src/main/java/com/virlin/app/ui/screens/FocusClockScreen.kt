package com.virlin.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.ui.components.focusTimeContentDescription
import kotlinx.coroutines.delay
import java.util.Date

const val FocusClockRoute = "focus_clock"

// Fullscreen palette. Deliberately unrelated to the Now-screen tokens: this surface is a
// black canvas holding a physical split-flap desk clock, and nothing else.
private val ClockCanvas = Color.Black
private val CardSurface = Color(0xFF212121)
private val CardShadow = Color(0xFF0E0E0E)
private val CardEdge = Color.White.copy(alpha = 0.05f)
private val CardSeam = Color(0xFF111113)
private val Numeral = Color(0xFFF0EFEA)
private val ColonDot = Color(0xFFBFBFBF)

// Geometry of the fullscreen presentation, expressed relative to one digit column.
private const val DigitAspect = 1.68f        // card height / one digit column width
private const val ColonWidthFactor = 0.40f   // colon column width, as a fraction of a digit column
private const val GroupWidthFactor = 4.40f   // total group width = this * digit column width
private const val WidthShare = 0.68f         // share of usable landscape width the group takes
private val ChromeReserve = 120.dp           // vertical room kept for the two subtle captions

// Numerals are sized from the card height, matching the proportions of the Now tile.
private val FontReferenceHeight = 74.dp
private const val FontBaseSp = 54f
private const val FontScale = 1.15f
private const val TrackingFactor = 0.10f

private const val FlipDurationMs = 500

private val TopHalfShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(Rect(0f, 0f, size.width, size.height / 2f))
}

private val BottomHalfShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(Rect(0f, size.height / 2f, size.width, size.height))
}

/**
 * Distraction-free landscape presentation of the SAME running FocusSession shown on Now.
 *
 * This screen owns NO focus-timing state: it observes [MockData.streams] — the single source
 * of truth that [com.virlin.app.mock.MockTimerEngine] ticks — exactly as the Now screen does.
 * Never introduce a local focus counter, ticker or timestamp here. (The wall clock below is a
 * separate, once-per-minute display of device time and is not a focus timer.)
 *
 * Visually: a black canvas with two large GROUPED flip cards, `[MM] : [SS]`. Each card is one
 * physical split-flap surface — when its two-character value changes, the whole card flips.
 * Digits inside a card never animate independently. This is fullscreen-only; the Now screen
 * keeps its per-digit `SplitFlapDigit` animation, which this file does not touch.
 */
@Composable
fun FocusClockScreen(navController: NavController) {
    val streams by MockData.streams.collectAsState()
    val focusStream = streams.find { it.state == StreamState.FOCUS }
    val focusInvestedSec = focusStream?.focusInvestedSec ?: 0

    val context = LocalContext.current
    val view = LocalView.current

    // Landscape + immersive + black system bars for the lifetime of this screen only;
    // everything is restored on exit.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }

        @Suppress("DEPRECATION")
        val previousStatusBarColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        val previousNavigationBarColor = window?.navigationBarColor
        val previousLightStatusBars = insetsController?.isAppearanceLightStatusBars
        val previousLightNavBars = insetsController?.isAppearanceLightNavigationBars

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        @Suppress("DEPRECATION")
        window?.statusBarColor = android.graphics.Color.BLACK
        @Suppress("DEPRECATION")
        window?.navigationBarColor = android.graphics.Color.BLACK
        insetsController?.apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            insetsController?.apply {
                show(WindowInsetsCompat.Type.systemBars())
                previousLightStatusBars?.let { isAppearanceLightStatusBars = it }
                previousLightNavBars?.let { isAppearanceLightNavigationBars = it }
            }
            @Suppress("DEPRECATION")
            previousStatusBarColor?.let { window.statusBarColor = it }
            @Suppress("DEPRECATION")
            previousNavigationBarColor?.let { window.navigationBarColor = it }
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val exit: () -> Unit = { navController.popBackStack() }

    // Android Back / back gesture exits exactly like the close control.
    BackHandler(onBack = exit)

    val minutes = (focusInvestedSec / 60).toString().padStart(2, '0')
    val seconds = (focusInvestedSec % 60).toString().padStart(2, '0')
    val wallClock = rememberWallClockTime()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ClockCanvas)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center
        ) {
            // Largest clock that still leaves room for the captions and clear black space.
            val digitColumn = minOf(
                (maxWidth * WidthShare) / GroupWidthFactor,
                ((maxHeight - ChromeReserve) * 0.95f) / DigitAspect
            )
            val cardHeight = digitColumn * DigitAspect
            val cardWidth = digitColumn * 2f
            val cornerRadius = (cardHeight * 0.11f).coerceIn(20.dp, 28.dp)

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 2. Current device time — extremely subtle, never competes with the timer.
                Text(
                    text = wallClock,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(0.35f)
                )

                Spacer(modifier = Modifier.height(26.dp))

                // 1. The focus timer — dominant.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // One meaningful accessible element for the whole clock.
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = focusTimeContentDescription(focusInvestedSec)
                    }
                ) {
                    GroupedFlipCard(minutes, cardWidth, cardHeight, cornerRadius)
                    ColonDots(
                        columnWidth = digitColumn * ColonWidthFactor,
                        dotSize = digitColumn * 0.115f,
                        gap = cardHeight * 0.18f
                    )
                    GroupedFlipCard(seconds, cardWidth, cardHeight, cornerRadius)
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 3. Focus Invested caption — extremely subtle.
                Text(
                    text = "FOCUS INVESTED",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(0.40f)
                )
            }
        }

        // 4. Close control — lowest visual prominence.
        CloseFocusClockButton(
            onClick = exit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
        )
    }
}

/**
 * One large two-character split-flap card (MM or SS).
 *
 * The card takes the COMPLETE two-character [value] and animates it as a single physical
 * surface driven by ONE progress value. The full string is rendered once per half and then
 * clipped into a top and a bottom section, so both halves share one baseline and one
 * horizontal centre — no per-digit positioning, no tearing.
 *
 * Sequence: old upper half rotates down about the centre hinge (p 0 -> 0.5), revealing the
 * new value behind it, then the new lower half unfolds into place (p 0.5 -> 1).
 */
@Composable
private fun GroupedFlipCard(
    value: String,
    cardWidth: Dp,
    cardHeight: Dp,
    cornerRadius: Dp
) {
    var currentValue by remember { mutableStateOf(value) }
    var nextValue by remember { mutableStateOf(value) }
    val progress = remember { Animatable(0f) }

    // Fires only when this card's whole two-character value changes, so the minutes card
    // stays still while the seconds card flips.
    LaunchedEffect(value) {
        if (value != currentValue) {
            nextValue = value
            progress.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = FlipDurationMs
                    0f at 0 with FastOutLinearInEasing
                    0.5f at FlipDurationMs / 2 with LinearOutSlowInEasing
                    1f at FlipDurationMs
                }
            )
            currentValue = nextValue
            progress.snapTo(0f)
        }
    }

    val scale = cardHeight / FontReferenceHeight
    val fontSizeSp = FontBaseSp * scale * FontScale
    val tracking = fontSizeSp * TrackingFactor

    Box(modifier = Modifier.size(cardWidth, cardHeight)) {
        // Restrained depth: a darker body sitting just behind and below the face.
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = 7.dp)
                .clip(RoundedCornerShape(cornerRadius))
                .background(CardShadow)
        )

        // LAYER 1: static top of the NEW value, revealed as the old top falls away.
        CardHalf(nextValue, true, { 0f }, cornerRadius, fontSizeSp, tracking, scale,
            Modifier.zIndex(0f))

        // LAYER 2: static bottom of the OLD value, covered as the new bottom unfolds.
        CardHalf(currentValue, false, { 0f }, cornerRadius, fontSizeSp, tracking, scale,
            Modifier.zIndex(0f))

        // LAYER 3: the OLD upper half rotating down as one surface.
        CardHalf(
            currentValue, true,
            {
                val p = progress.value
                if (p <= 0.5f) p * 2f * 0.5f else 0f
            },
            cornerRadius, fontSizeSp, tracking, scale,
            Modifier
                .zIndex(1f)
                .graphicsLayer {
                    val p = progress.value
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    rotationX = if (p <= 0.5f) lerp(0f, -90f, p * 2f) else -90f
                    cameraDistance = 12f * density * scale
                }
        )

        // LAYER 4: the NEW lower half unfolding into position as one surface.
        CardHalf(
            nextValue, false,
            {
                val p = progress.value
                if (p > 0.5f) (1f - (p - 0.5f) * 2f).coerceAtLeast(-0.5f) * 0.5f else 0f
            },
            cornerRadius, fontSizeSp, tracking, scale,
            Modifier
                .zIndex(1f)
                .graphicsLayer {
                    val p = progress.value
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    rotationX = if (p > 0.5f) lerp(90f, 0f, (p - 0.5f) * 2f) else 90f
                    cameraDistance = 12f * density * scale
                }
        )

        // LAYER 5: the centre hinge, running continuously across the whole card.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp * scale)
                .background(CardSeam)
                .align(Alignment.Center)
                .zIndex(2f)
        )

        // Hairline flap thickness catching the light. No glow.
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(1.dp, CardEdge, RoundedCornerShape(cornerRadius))
                .zIndex(3f)
        )
    }
}

/**
 * Half of a card face. Renders the COMPLETE two-character [text] at full card size and then
 * clips to the top or bottom rectangle, so every layer shares an identical glyph layout.
 */
@Composable
private fun CardHalf(
    text: String,
    isTop: Boolean,
    darkenAlphaProvider: () -> Float,
    cornerRadius: Dp,
    fontSizeSp: Float,
    trackingSp: Float,
    scale: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                clip = true
                shape = if (isTop) TopHalfShape else BottomHalfShape
            }
            .drawWithContent {
                drawContent()
                // Read alpha at draw time so the flip never triggers recomposition.
                val darkenAlpha = darkenAlphaProvider()
                if (darkenAlpha > 0f) {
                    drawRect(Color.Black, alpha = darkenAlpha.coerceIn(0f, 1f))
                } else if (darkenAlpha < 0f) {
                    drawRect(Color.White, alpha = (-darkenAlpha).coerceIn(0f, 1f))
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CardSurface, RoundedCornerShape(cornerRadius))
        )

        // Inset depth directly under the centre hinge.
        if (!isTop) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp * scale)
                    .align(Alignment.Center)
                    .offset(y = 12.dp * scale)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)
                        )
                    )
            )
        }

        // The complete two-character value, rendered once, centred. Trailing letter spacing
        // is compensated so the ink stays optically centred in the card.
        Text(
            text = text,
            color = Numeral,
            fontSize = fontSizeSp.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = trackingSp.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (trackingSp / 2f).dp, y = (-2).dp * scale)
        )
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

/**
 * Device wall-clock time, honouring the user's 12/24-hour preference.
 * Recomputed once per minute on the minute boundary — not a high-frequency timer.
 */
@Composable
private fun rememberWallClockTime(): String {
    val context = LocalContext.current
    var formatted by remember {
        mutableStateOf(android.text.format.DateFormat.getTimeFormat(context).format(Date()))
    }
    LaunchedEffect(Unit) {
        while (true) {
            formatted = android.text.format.DateFormat.getTimeFormat(context).format(Date())
            val now = System.currentTimeMillis()
            delay(60_000L - (now % 60_000L))
        }
    }
    return formatted
}

@Composable
private fun ColonDots(columnWidth: Dp, dotSize: Dp, gap: Dp) {
    Column(
        modifier = Modifier.width(columnWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(modifier = Modifier.size(dotSize).background(ColonDot, CircleShape))
        Spacer(modifier = Modifier.height(gap))
        Box(modifier = Modifier.size(dotSize).background(ColonDot, CircleShape))
    }
}

/**
 * Deliberately low-salience: a 32dp outline inside a 48dp touch target, resting at low
 * opacity and brightening only while pressed.
 */
@Composable
private fun CloseFocusClockButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val controlAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 0.32f,
        animationSpec = tween(160),
        label = "closeFocusClockAlpha"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "closeFocusClockPress"
    )

    Box(
        modifier = modifier
            .size(48.dp) // touch target
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { contentDescription = "Close focus clock" },
        contentAlignment = Alignment.Center
    ) {
        // Bare glyph, no enclosing circle.
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(26.dp) // visual size
                .scale(pressScale)
                .alpha(controlAlpha)
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
