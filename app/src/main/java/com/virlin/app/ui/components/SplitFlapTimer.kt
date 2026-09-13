package com.virlin.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
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
// derived from it, so the timer can render much larger (fullscreen Focus Clock) without
// forking the implementation or altering how it looks on the Now screen.
private val BaseDigitWidth = 52.dp
private val BaseDigitHeight = 74.dp

/** Tile face used by the approved Now-screen timer. */
private val DefaultTileColor = Color(0xFF222325)

/** Shared spoken form of the focus time, so every surface announces it identically. */
fun focusTimeContentDescription(timeInSeconds: Int): String {
    val minutes = timeInSeconds / 60
    val seconds = timeInSeconds % 60
    val minuteWord = if (minutes == 1) "minute" else "minutes"
    val secondWord = if (seconds == 1) "second" else "seconds"
    return "Focus invested $minutes $minuteWord $seconds $secondWord"
}

@Composable
fun SplitFlapTimer(
    timeInSeconds: Int,
    modifier: Modifier = Modifier,
    digitWidth: Dp = BaseDigitWidth,
    digitHeight: Dp = BaseDigitHeight,
    contentDescription: String? = null
) {
    val mStr = (timeInSeconds / 60).toString().padStart(2, '0')
    val sStr = (timeInSeconds % 60).toString().padStart(2, '0')

    // Uniform scale factor: 1f reproduces the approved Now-screen rendering exactly.
    val s = digitHeight / BaseDigitHeight

    val description = contentDescription ?: focusTimeContentDescription(timeInSeconds)

    // Housing
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            // Expose the whole timer as ONE accessible element instead of four loose digits.
            .clearAndSetSemantics { this.contentDescription = description }
            .background(Color(0xFF121A12).copy(alpha = 0.12f), RoundedCornerShape(22.dp * s))
            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(22.dp * s))
            .padding(horizontal = 12.dp * s, vertical = 10.dp * s)
    ) {
        SplitFlapDigit(mStr[0], digitWidth, digitHeight, s)
        Spacer(modifier = Modifier.width(1.5.dp * s))
        SplitFlapDigit(mStr[1], digitWidth, digitHeight, s)

        // Subtle pulsing colon perfectly aligned
        val infiniteTransition = rememberInfiniteTransition(label = "colon")
        val colonAlpha by infiniteTransition.animateFloat(
            initialValue = 0.95f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = EaseInOut), // half of 1.8s
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
                    .shadow(1.dp, CircleShape) // Approximate inset shadow
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

        SplitFlapDigit(sStr[0], digitWidth, digitHeight, s)
        Spacer(modifier = Modifier.width(1.5.dp * s))
        SplitFlapDigit(sStr[1], digitWidth, digitHeight, s)
    }
}

@Composable
fun SplitFlapDigit(
    digit: Char,
    digitWidth: Dp = BaseDigitWidth,
    digitHeight: Dp = BaseDigitHeight,
    scale: Float = digitHeight / BaseDigitHeight,
    // Styling hooks. Defaults reproduce the approved Now-screen tile exactly.
    // The fullscreen Focus Clock overrides these to fuse two digits into one housing;
    // the flip engine below is untouched by them.
    tileColor: Color = DefaultTileColor,
    cornerRadius: Dp = 10.dp * scale,
    fontScale: Float = 1f
) {
    var currentValue by remember { mutableStateOf(digit) }
    var nextValue by remember { mutableStateOf(digit) }

    val progressAnim = remember { Animatable(0f) }

    val fontSize = (54f * scale * fontScale).sp
    val innerShadowHeight = 24.dp * scale
    val innerShadowOffset = 12.dp * scale
    val textOffset = (-2).dp * scale

    // Only triggers when the specific digit changes
    LaunchedEffect(digit) {
        if (digit != currentValue) {
            nextValue = digit
            // 500ms mechanical flip with slight overshoot bounce
            progressAnim.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = 500
                    0f at 0 with FastOutLinearInEasing
                    0.5f at 240 with LinearOutSlowInEasing
                    1.05f at 420
                    1f at 500
                }
            )
            currentValue = nextValue
            progressAnim.snapTo(0f)
        }
    }

    Box(
        modifier = Modifier
            .width(digitWidth)
            .height(digitHeight)
    ) {
        // LAYER 1: Static Next Top (Visible behind the falling top flap)
        FlapHalf(
            char = nextValue,
            isTop = true,
            darkenAlphaProvider = { 0f },
            tileColor = tileColor,
            cornerRadius = cornerRadius,
            fontSize = fontSize,
            innerShadowHeight = innerShadowHeight,
            innerShadowOffset = innerShadowOffset,
            textOffset = textOffset,
            modifier = Modifier.zIndex(0f)
        )

        // LAYER 2: Static Current Bottom (Visible behind the unfolding bottom flap)
        FlapHalf(
            char = currentValue,
            isTop = false,
            darkenAlphaProvider = { 0f },
            tileColor = tileColor,
            cornerRadius = cornerRadius,
            fontSize = fontSize,
            innerShadowHeight = innerShadowHeight,
            innerShadowOffset = innerShadowOffset,
            textOffset = textOffset,
            modifier = Modifier.zIndex(0f)
        )

        // LAYER 3: Animated Old Top Flap (falls forward and down)
        FlapHalf(
            char = currentValue,
            isTop = true,
            darkenAlphaProvider = {
                val p = progressAnim.value
                if (p <= 0.5f) p * 2f * 0.5f else 0f
            },
            tileColor = tileColor,
            cornerRadius = cornerRadius,
            fontSize = fontSize,
            innerShadowHeight = innerShadowHeight,
            innerShadowOffset = innerShadowOffset,
            textOffset = textOffset,
            modifier = Modifier
                .zIndex(1f)
                .graphicsLayer {
                    val p = progressAnim.value
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    rotationX = if (p <= 0.5f) lerp(0f, -90f, p * 2f) else -90f
                    // Camera scales with the tile so the perspective reads identically at any size
                    cameraDistance = 12f * density * scale
                }
        )

        // LAYER 4: Animated New Bottom Flap (unfolds from horizontal)
        FlapHalf(
            char = nextValue,
            isTop = false,
            darkenAlphaProvider = {
                val p = progressAnim.value
                if (p > 0.5f) (1f - (p - 0.5f) * 2f).coerceAtLeast(-0.5f) * 0.5f else 0f
            },
            tileColor = tileColor,
            cornerRadius = cornerRadius,
            fontSize = fontSize,
            innerShadowHeight = innerShadowHeight,
            innerShadowOffset = innerShadowOffset,
            textOffset = textOffset,
            modifier = Modifier
                .zIndex(1f)
                .graphicsLayer {
                    val p = progressAnim.value
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                    rotationX = if (p > 0.5f) lerp(90f, 0f, (p - 0.5f) * 2f) else 90f
                    cameraDistance = 12f * density * scale
                }
        )

        // LAYER 5: Center Hinge / Seam (Covers the gap)
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

private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction
}

@Composable
fun FlapHalf(
    char: Char,
    isTop: Boolean,
    darkenAlphaProvider: () -> Float,
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
                // Dynamically clip exactly half of the full-sized bounds
                clip = true
                shape = object : Shape {
                    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
                        Outline.Rectangle(
                            if (isTop) Rect(0f, 0f, size.width, size.height / 2f)
                            else Rect(0f, size.height / 2f, size.width, size.height)
                        )
                }
            }
            .drawWithContent {
                drawContent()
                // Retrieve alpha dynamically to avoid triggering recompositions
                val darkenAlpha = darkenAlphaProvider()
                if (darkenAlpha > 0f) {
                    drawRect(Color.Black, alpha = darkenAlpha.coerceIn(0f, 1f))
                } else if (darkenAlpha < 0f) {
                    drawRect(Color.White, alpha = (-darkenAlpha).coerceIn(0f, 1f))
                }
            }
    ) {
        // Full Tile Background with rounded corners everywhere (clip handles the sharp hinge edge)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tileColor, RoundedCornerShape(cornerRadius))
        )

        // Inner shadow on the bottom half simulates physical inset/depth directly under the center hinge
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

        // 100% full, unbroken digit rendering.
        // Perfectly centered in the tile and completely immune to clipping distortions.
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
