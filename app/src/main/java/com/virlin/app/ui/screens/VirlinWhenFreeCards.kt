package com.virlin.app.ui.screens

// Supplied "When you're free" cards. UI only: it owns no ranking, timer, navigation or task
// state. Adapted where the Now screen already decides something:
//  - the section heading and "Next recommended" stay in NowScreen, which already renders them,
//    so this file draws cards only and there is exactly one heading,
//  - the duration pill is optional, because a recommendation without a recorded estimate must
//    not be given an invented one,
//  - the project icon is optional and is passed in from the caller, which resolves it through
//    ProjectIdentity + ProjectIcon exactly as the Needs You cards and the Projects screen do.

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/** Supply the same project icon composable that Projects/Streams already use. */
data class WhenFreeCardUi(
    val id: String,
    val title: String,
    val detail: String,
    /** Null when the recommendation has no recorded estimate; no placeholder is invented. */
    val durationLabel: String?,
    /** Null when the stream belongs to no project. */
    val projectIcon: (@Composable () -> Unit)?,
)

private data class FreeColors(
    val surface: Color,
    val accent: Color,
    val ink: Color,
)

private val freeColors = listOf(
    FreeColors(Color(0xFFF5F3FF), Color(0xFF8C78CE), Color(0xFF514195)),
    FreeColors(Color(0xFFF1F7FF), Color(0xFF71A7DC), Color(0xFF275C91)),
    FreeColors(Color(0xFFFFF2F7), Color(0xFFD986AA), Color(0xFF934469)),
)
private val forest = Color(0xFF17271D)

/**
 * Drop this inside the existing Now page scroll content. Supply the existing
 * recommendation list in its current order and wire callbacks to existing actions.
 * No navigation, data layer, ranking, or footer is created here.
 */
@Composable
fun VirlinWhenFreeSection(
    items: List<WhenFreeCardUi>,
    onFocus: (String) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    animateClouds: Boolean = true,
) {
    val systemAllowsMotion = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        ValueAnimator.areAnimatorsEnabled()
    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            key(item.id) {
                WhenFreeCard(
                    item = item,
                    colors = freeColors[index % freeColors.size],
                    motion = animateClouds && systemAllowsMotion,
                    variant = index % freeColors.size,
                    onOpen = { onOpen(item.id) },
                    onFocus = { onFocus(item.id) },
                )
            }
            if (index != items.lastIndex) Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun WhenFreeCard(
    item: WhenFreeCardUi,
    colors: FreeColors,
    motion: Boolean,
    variant: Int,
    onOpen: () -> Unit,
    onFocus: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    // defaultMinSize, not a fixed height: a long title or a large font grows the card
    // rather than clipping it.
    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 106.dp).clip(shape)
        .background(Brush.horizontalGradient(listOf(colors.surface, Color.White, colors.surface)))
        .border(1.dp, colors.accent.copy(alpha = .30f), shape)) {
        CloudBackdrop(colors.accent, variant, motion, Modifier.matchParentSize())
        // The card and its Focus button keep independent actions and hit targets.
        Box(Modifier.matchParentSize().testTag(whenFreeCardTag(item.id)).clickable(
            role = Role.Button, onClickLabel = "Open ${item.title}", onClick = onOpen))
        Row(Modifier.fillMaxWidth().align(Alignment.Center)
            .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            item.projectIcon?.let { icon ->
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                    .background(Color.White.copy(alpha = .78f)),
                    contentAlignment = Alignment.Center) {
                    // The real project icon, with its own colours and background.
                    icon()
                }
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF172321), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(item.detail, fontSize = 12.sp, color = Color(0xFF617087),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
                item.durationLabel?.let { label ->
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.clip(RoundedCornerShape(50))
                        .background(colors.accent.copy(alpha = .14f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = colors.ink, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(forest)
                .testTag(whenFreeFocusTag(item.id))
                .clickable(role = Role.Button, onClickLabel = "Focus on ${item.title}",
                    onClick = onFocus)
                .padding(horizontal = 13.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text("FOCUS  →", color = Color.White, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** Two low-contrast cloud clusters, clipped inside the card. Decorative only. */
@Composable
private fun CloudBackdrop(
    accent: Color,
    variant: Int,
    motion: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "When free clouds")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7500 + variant * 1300,
                easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "Cloud drift",
    )
    val offset = if (motion) sin(phase + variant).toFloat() else 0f
    Canvas(modifier.clearAndSetSemantics { }) {
        val r = size.height * .26f
        val horizontal = if (variant == 0) offset * r * .13f else offset * r * .07f
        val vertical = if (variant == 1) offset * r * .13f else offset * r * .05f
        fun cloud(cx: Float, cy: Float, alpha: Float, scale: Float) {
            val radius = r * scale
            val ink = lerp(Color.White, accent, .63f).copy(alpha = alpha)
            drawCircle(ink, radius, Offset(cx - radius * .72f, cy + radius * .2f))
            drawCircle(ink, radius * 1.12f, Offset(cx, cy - radius * .22f))
            drawCircle(ink, radius * .87f, Offset(cx + radius * .78f, cy + radius * .25f))
        }
        cloud(-r * .15f + horizontal, size.height * .99f + vertical,
            alpha = .18f, scale = .80f)
        cloud(size.width + r * .15f - horizontal,
            size.height * .20f - vertical, alpha = .14f, scale = .95f)
        cloud(size.width - r * .18f + horizontal * .5f,
            size.height * 1.08f + vertical, alpha = .17f, scale = 1.2f)
    }
}

fun whenFreeCardTag(id: String) = "when_free_card_$id"
fun whenFreeFocusTag(id: String) = "when_free_focus_$id"
