package com.virlin.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import com.virlin.app.mock.MockData
import com.virlin.app.model.StreamState
import com.virlin.app.ui.components.SplitFlapPresentation
import com.virlin.app.ui.components.SplitFlapTimer
import kotlinx.coroutines.delay
import java.util.Date

const val FocusClockRoute = "focus_clock"

// Fullscreen palette. Deliberately unrelated to the Now-screen tokens: this surface is a
// black canvas holding a physical split-flap desk clock, and nothing else.
private val ClockCanvas = Color.Black

// Geometry — digit column sizing only. Flip animation lives in SplitFlapTimer.
private const val DigitAspect = 1.68f        // card height / one digit column width
private const val GroupWidthFactor = 4.40f   // total group width ≈ this * digit column width
private const val WidthShare = 0.68f         // share of usable landscape width the group takes
private val ChromeReserve = 120.dp           // vertical room kept for the two subtle captions

/**
 * Distraction-free landscape presentation of the SAME running FocusSession shown on Now.
 *
 * Owns NO focus-timing state: observes [MockData.streams] exactly as Now does.
 * Digits use the shared [SplitFlapTimer] / [com.virlin.app.ui.components.SplitFlapDigit]
 * engine (Fullscreen presentation) — not a second flip implementation.
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
    BackHandler(onBack = exit)

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
            val digitColumn = minOf(
                (maxWidth * WidthShare) / GroupWidthFactor,
                ((maxHeight - ChromeReserve) * 0.95f) / DigitAspect
            )
            val digitHeight = digitColumn * DigitAspect
            val digitWidth = digitColumn

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = wallClock,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.alpha(0.35f)
                )

                Spacer(modifier = Modifier.height(26.dp))

                // Same session seconds as Now. Key by stream so flap state remounts → SNAP.
                key(focusStream?.id ?: "none") {
                    SplitFlapTimer(
                        timeInSeconds = focusInvestedSec,
                        digitWidth = digitWidth,
                        digitHeight = digitHeight,
                        presentation = SplitFlapPresentation.Fullscreen
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

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

/**
 * Deliberately low-salience: a 26dp glyph inside a 48dp touch target, resting at low
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
            .size(48.dp)
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
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(26.dp)
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
