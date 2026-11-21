package com.virlin.app.ui.navigation

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.virlin.app.ui.screens.NowApprovedFont
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GlideMillis = 240

/** One shared dock navigates to the existing pages; it never owns destination content. */
@Composable
internal fun ApprovedNowDock(route: String, count: Int, onNavigate: (String) -> Unit, modifier: Modifier = Modifier) {
    fun indexOf(r: String?) = RootDestination.entries.indexOfFirst { it.route == r }.coerceAtLeast(0)
    val reduced = !ValueAnimator.areAnimatorsEnabled()
    val position = remember { Animatable(indexOf(route).toFloat()) }
    val stretch = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // The tapped tab, while the bubble travels to it. Navigation starts only once the bubble
    // has arrived, so the destination's first (heavy) frame never stalls the bubble mid-flight.
    var pending by remember { mutableStateOf<String?>(null) }
    var travel by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    suspend fun glideTo(target: Int) {
        if (reduced) { position.snapTo(target.toFloat()); stretch.snapTo(0f); return }
        if (position.value == target.toFloat()) return
        coroutineScope {
            launch {
                stretch.animateTo(1f, tween(GlideMillis / 2, easing = FastOutSlowInEasing))
                stretch.animateTo(0f, tween(GlideMillis / 2, easing = FastOutSlowInEasing))
            }
            position.animateTo(target.toFloat(), tween(GlideMillis, easing = FastOutSlowInEasing))
        }
    }
    // Back presses and other route changes move the bubble too; a tap already in flight yields.
    LaunchedEffect(route) {
        if (pending != null && pending != route) travel?.cancel()
        pending = null
        glideTo(indexOf(route))
    }
    val onTap: (String) -> Unit = { target ->
        if (target == route && pending == null) onNavigate(target)
        else if (target != (pending ?: route)) {
            pending = target
            travel?.cancel()
            travel = scope.launch {
                glideTo(indexOf(target))
                onNavigate(target)
            }
        }
    }
    val shownRoute = pending ?: route
    val index by remember { derivedStateOf { position.value } }
    Box(modifier.fillMaxWidth().testTag(BottomNavTag).drawWithCache {
        val w = size.width; val h = size.height
        val rose = Brush.radialGradient(listOf(Color(0xFFFF9DB4), Color(0x99FFC3CE), Color.Transparent), Offset(0f,h), w*.7f)
        val green = Brush.radialGradient(listOf(Color(0xFFAFF0D7), Color(0x70CBF7E5), Color.Transparent), Offset(w*.5f,h), w*.6f)
        val violet = Brush.radialGradient(listOf(Color(0xFFBC94EC), Color(0x80DEC9F8), Color.Transparent), Offset(w,h), w*.65f)
        val feather = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = .2f), Color.Transparent), endY = h*.9f)
        // The page above dissolves into the dock instead of meeting a hard white edge. Drawn
        // over the content's last few dp (the bar sits above the content); it never takes touches.
        val fade = 28.dp.toPx()
        val fadeIn = Brush.verticalGradient(0f to Color.White.copy(alpha = 0f), .4f to Color.White.copy(alpha = .3f),
            .75f to Color.White.copy(alpha = .78f), 1f to Color.White, startY = -fade, endY = 0f)
        onDrawBehind {
            drawRect(fadeIn, topLeft = Offset(0f, -fade), size = Size(w, fade))
            drawRect(Color.White); drawRect(green); drawRect(rose); drawRect(violet); drawRect(feather)
        }
    }.windowInsetsPadding(NavigationBarDefaults.windowInsets).padding(horizontal = 12.dp, vertical = 6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(82.dp)) {
            val slot = maxWidth / 4
            val density = LocalDensity.current
            Canvas(Modifier.matchParentSize()) {
                val w = size.width; val h = size.height
                fun x(v: Float) = w*v/400f
                fun y(v: Float) = h*v/90f
                val p = Path().apply {
                    moveTo(x(3f),y(42f))
                    cubicTo(x(6f),y(17f),x(22f),y(4f),x(47f),y(4f))
                    cubicTo(x(72f),y(3f),x(82f),y(16f),x(111f),y(14f))
                    cubicTo(x(138f),y(12f),x(151f),y(4f),x(175f),y(11f))
                    cubicTo(x(204f),y(20f),x(225f),y(16f),x(248f),y(11f))
                    cubicTo(x(272f),y(5f),x(288f),y(17f),x(312f),y(13f))
                    cubicTo(x(338f),y(10f),x(356f),y(4f),x(372f),y(12f))
                    cubicTo(x(392f),y(21f),x(399f),y(40f),x(395f),y(55f))
                    cubicTo(x(393f),y(75f),x(375f),y(84f),x(355f),y(84f))
                    cubicTo(x(328f),y(86f),x(307f),y(77f),x(282f),y(80f))
                    cubicTo(x(254f),y(84f),x(240f),y(88f),x(217f),y(82f))
                    cubicTo(x(193f),y(74f),x(177f),y(75f),x(152f),y(81f))
                    cubicTo(x(126f),y(88f),x(110f),y(85f),x(89f),y(80f))
                    cubicTo(x(68f),y(74f),x(52f),y(86f),x(32f),y(81f))
                    cubicTo(x(12f),y(77f),x(-1f),y(62f),x(3f),y(42f)); close()
                }
                drawPath(p, Brush.verticalGradient(listOf(Color(0xFFFCF8FF), Color(0xFFF4EAFA), Color(0xFFE8DCF2))))
                clipRect(bottom = h*.5f) { drawPath(p, Color.White, style = Stroke(1.dp.toPx())) }
            }
            val blob = remember { GenericShape { s, _ ->
                moveTo(s.width*.06f,s.height*.49f)
                cubicTo(s.width*.1f,-s.height*.04f,s.width*.58f,-s.height*.07f,s.width*.9f,s.height*.35f)
                cubicTo(s.width*1.18f,s.height*.82f,s.width*.75f,s.height*1.06f,s.width*.32f,s.height*.95f)
                cubicTo(s.width*.08f,s.height*.9f,-s.width*.03f,s.height*.72f,s.width*.06f,s.height*.49f); close()
            } }
            Box(Modifier.offset { IntOffset(with(density) { (slot*(index+.08f)).toPx() }.roundToInt(), 1) }
                .width(slot*.84f).height(68.dp).graphicsLayer {
                    scaleX = 1f + .13f*stretch.value; scaleY = 1f - .12f*stretch.value
                    shape = blob; shadowElevation = 4.dp.toPx()
                }.background(Brush.linearGradient(listOf(Color(0xFFFF8DA8), Color(0xFFFA6083), Color(0xFFEC3B67))), blob))
            val base = when {
                slot >= 92.dp -> 14f
                slot >= 80.dp -> 12.5f
                else -> 11.5f
            }
            // "Momentum" (the longest label) must stay whole at large font scales.
            val labelSize = minOf(base, slot.value * .9f / (8 * .62f) / density.fontScale).sp
            // The bubble narrows toward its foot where the label sits; a label wider than that
            // band (Momentum) condenses and lifts a little as the bubble arrives. Others stay put.
            val measurer = rememberTextMeasurer()
            val bubbleBandPx = with(density) { (slot * .84f * .72f).toPx() }
            val liftPx = with(density) { 2.5.dp.toPx() }
            Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically) {
                RootDestination.entries.forEachIndexed { slotIndex, destination ->
                    val active = shownRoute == destination.route
                    // How much of the bubble sits under this tab; read only while drawing, so the
                    // white/purple hand-over follows the bubble without recomposing per frame.
                    val onBubble = { (1f - abs(position.value - slotIndex) * 1.4f).coerceIn(0f, 1f) }
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val pressScale by animateFloatAsState(if (pressed && !reduced) .92f else 1f,
                        tween(if (pressed) 90 else 180, easing = FastOutSlowInEasing), label = "dockPress")
                    // No ripple: the travelling bubble and this press scale are the only feedback.
                    Column(Modifier.weight(1f).fillMaxHeight().testTag(bottomNavItemTag(destination))
                        .semantics { contentDescription = if (destination == RootDestination.INBOX && count > 0) "${destination.label}, $count in inbox" else destination.label }
                        .selectable(active, interactionSource = interaction, indication = null, role = Role.Tab,
                            onClick = { onTap(destination.route) })
                        .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                        .padding(top = 10.dp, bottom = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box {
                            Box(Modifier.graphicsLayer { alpha = 1f - onBubble() }) { DockIcon(destination, false, Color(0xFF6B2997)) }
                            Box(Modifier.graphicsLayer { alpha = onBubble() }) { DockIcon(destination, true, Color.White) }
                            if (destination == RootDestination.INBOX && count > 0) Box(Modifier.align(Alignment.TopEnd)
                                .offset(x = 16.dp, y = (-9).dp).testTag(InboxBadgeTag)
                                .defaultMinSize(minWidth = 21.dp, minHeight = 21.dp)
                                .background(Color.White, androidx.compose.foundation.shape.CircleShape)
                                .padding(1.5.dp)
                                .background(Brush.verticalGradient(listOf(Color(0xFF37EDBD), Color(0xFF00B78B))), androidx.compose.foundation.shape.CircleShape)
                                .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                Text(if (count > 99) "99+" else "$count", color = Color.White, fontFamily = NowApprovedFont, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        val fit = remember(destination.label, labelSize, bubbleBandPx, measurer) {
                            val wide = measurer.measure(destination.label, TextStyle(fontFamily = NowApprovedFont,
                                fontSize = labelSize, fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false).size.width
                            if (wide > 0) minOf(1f, bubbleBandPx / wide) else 1f
                        }
                        Box(Modifier.graphicsLayer {
                            if (fit < 1f) {
                                val b = onBubble()
                                scaleX = 1f - (1f - fit) * b
                                scaleY = 1f - (1f - fit) * .45f * b
                                translationY = -liftPx * b
                            }
                        }, contentAlignment = Alignment.Center) {
                            Text(destination.label, Modifier.graphicsLayer { alpha = 1f - onBubble() }, color = Color(0xFF4A1A78),
                                fontFamily = NowApprovedFont, fontSize = labelSize, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            Text(destination.label, Modifier.clearAndSetSemantics {}.graphicsLayer { alpha = onBubble() }, color = Color.White,
                                fontFamily = NowApprovedFont, fontSize = labelSize, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/** Momentum and Apps use the reference's drawn icons; Now and Projects keep their vectors. */
@Composable
private fun DockIcon(destination: RootDestination, active: Boolean, tint: Color) {
    when (destination) {
        RootDestination.PULSE -> Canvas(Modifier.size(26.dp)) {
            val w = size.width
            fun p(x: Float, y: Float) = Offset(w * x, w * y)
            val stick = if (active) SolidColor(Color.White)
                else Brush.linearGradient(listOf(Color(0xFF9B5CF6), Color(0xFF5B21B6)), p(.1f, .9f), p(.55f, .45f))
            val ball = if (active) SolidColor(Color.White)
                else Brush.radialGradient(listOf(Color(0xFF8DFFE0), Color(0xFF14C79E), Color(0xFF0A8F74)), p(.6f, .3f), w * .35f)
            drawLine(stick, p(.16f, .84f), p(.52f, .48f), w * .17f, StrokeCap.Round)
            drawLine(if (active) SolidColor(Color.White) else SolidColor(Color(0xFF14B894)), p(.55f, .45f), p(.82f, .22f), w * .07f)
            drawLine(if (active) SolidColor(Color.White) else SolidColor(Color(0xFF14B894)), p(.55f, .45f), p(.84f, .64f), w * .07f)
            drawCircle(ball, w * .14f, p(.55f, .45f))
            drawCircle(ball, w * .13f, p(.83f, .2f))
            drawCircle(ball, w * .12f, p(.85f, .66f))
        }
        RootDestination.INBOX -> Canvas(Modifier.size(25.dp)) {
            val cell = size.width * .27f
            val gap = (size.width - cell * 3) / 2
            val fill = if (active) SolidColor(Color.White)
                else Brush.verticalGradient(listOf(Color(0xFFB27BFF), Color(0xFF7C3AED), Color(0xFF6022C8)))
            for (r in 0 until 3) for (c in 0 until 3) drawRoundRect(fill,
                Offset(c * (cell + gap), r * (cell + gap)), Size(cell, cell), CornerRadius(cell * .3f))
        }
        else -> Icon(if (active) destination.selectedIcon else destination.icon, null, tint = tint,
            modifier = Modifier.size(if (destination == RootDestination.STREAMS) 23.dp else 26.dp))
    }
}
