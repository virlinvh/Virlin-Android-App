package com.virlin.app.ui.hierarchy

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

internal val HierarchyTimerAccent = androidx.compose.ui.semantics.SemanticsPropertyKey<Color>("Timer accent")

/** One compact block at the original top-right location; rotation never affects timers. */
@Composable
internal fun RotatingHierarchyTimer(focused: Long, external: Long?, scopeId: String = "default", projectId: String? = null) {
    val rotation = timerRotationSeconds(scopeId, projectId)
    var showExternal by remember { mutableStateOf(false) }
    LaunchedEffect(external != null, rotation, scopeId) {
        showExternal = false
        if (external != null) while (true) {
            delay(rotation * 1_000L)
            showExternal = !showExternal
        }
    }
    AnimatedContent(targetState = showExternal && external != null,
        transitionSpec = {
            (slideInVertically(tween(420)) { -it / 2 } + fadeIn(tween(340))) togetherWith
                (slideOutVertically(tween(420)) { it / 2 } + fadeOut(tween(340)))
        }, label = "Section timing category") { isExternal ->
        Column {
            Text(if (isExternal) "External" else "Focused", fontSize = 10.sp,
                color = Color(0xFF66758B))
            HierarchyTimer(if (isExternal) external else focused, category = if (isExternal) "External" else "Focused")
        }
    }
}

/** Timestamp-driven display only: animations never own or advance the focus clock. */
@Composable
internal fun HierarchyTimer(seconds: Long?, modifier: Modifier = Modifier, category: String = "Elapsed focus time") {
    val time = seconds?.let(::hierarchyElapsed) ?: "—"
    val density = LocalDensity.current
    val timerColor = LocalProjectPalette.current?.let { Color(it.main) } ?: Color(0xFF65B82E)
    Row(modifier.testTag("hierarchy_elapsed").clearAndSetSemantics {
        this[HierarchyTimerAccent] = timerColor
        contentDescription = if (seconds == null) "No active focus" else "$category $time"
    }) {
        time.forEachIndexed { index, character ->
            // Anchor slots from the seconds end when the hours field first appears.
            key(time.length - index) {
                AnimatedContent(
                    targetState = character,
                    modifier = Modifier.width(with(density) { (if (character == ':') 5.sp else 12.sp).toDp() })
                        .clipToBounds()
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Alpha mask is local to the digit slot, keeping the header/cards crisp.
                            drawRect(Brush.verticalGradient(0f to Color.Transparent,
                                0.15f to Color.Black, 0.85f to Color.Black,
                                1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        },
                    transitionSpec = {
                        (slideInVertically(tween(420, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(340))) togetherWith
                            (slideOutVertically(tween(420, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(340)))
                    },
                    label = "Focus timer digit",
                ) { digit ->
                    Text(digit.toString(), color = timerColor, fontSize = 20.sp,
                        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
                        style = TextStyle(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}
