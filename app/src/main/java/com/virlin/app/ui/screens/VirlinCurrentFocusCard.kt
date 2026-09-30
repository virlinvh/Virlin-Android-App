package com.virlin.app.ui.screens

// Supplied drop-in card. Adapted only where the existing screen needs it:
//  - `headline` so the big line can be the WorkStream while the small line stays the Project,
//  - `primaryLabel` so an EXTERNAL stream still reads HAND OFF instead of COMPLETE,
//  - `motto` made optional (nothing in the domain supplies that sentence),
//  - the app's existing test tags on the context block, invested time, NEXT row and actions.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF172722)
private val Forest = Color(0xFF073C2E)
private val Muted = Color(0xFF55635F)
private val Mint = Color(0xFFE4FAE9)

/**
 * Card-only replacement. Caller keeps timer ticking, completion, and exit behavior.
 * `timerContent` may render the application's existing animated flip timer;
 * the fallback renders the same four-tile visual using `elapsedTime`.
 */
@Composable
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
    /** COMPLETE for human work, HAND OFF when the domain says the stream is EXTERNAL. */
    primaryLabel: String = "COMPLETE",
    /** Optional encouragement row; omitted when nothing supplies it. */
    motto: String? = null,
    onContextClick: (() -> Unit)? = null,
    timerContent: (@Composable () -> Unit)? = null,
) {
    val cardShape = RoundedCornerShape(28.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(Color(0xFFF3FFF0))
            .border(1.dp, Color(0xFFD9EED6), cardShape),
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.linearGradient(
                0.0f to Color(0xFFF8FFF4),
                0.55f to Color(0xFFE7FDE6),
                1.0f to Color(0xFFF1FFF0),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ))
            val wave = Path().apply {
                moveTo(0f, size.height * .50f)
                cubicTo(size.width * .19f, size.height * .74f,
                    size.width * .40f, size.height * .12f,
                    size.width, size.height * .35f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(wave, Brush.linearGradient(
                listOf(Color(0x38FFFFFF), Color(0x2C98F0A4), Color(0x22FFFFFF)),
                Offset.Zero, Offset(size.width, size.height),
            ))
        }

        Column(Modifier.padding(horizontal = 20.dp, vertical = 19.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill("FOCUSING NOW", Color(0xFF14A74C), filled = true)
                // The invested time lives IN the pill now — same value, same live source, one row.
                StatusPill(
                    // `investedTime` already reads "18m invested" — the caller formats it.
                    label = if (investedTime.isNotBlank()) investedTime
                    else if (isFocusActive) "FOCUS ACTIVE" else "FOCUS PAUSED",
                    dot = if (isFocusActive) Forest else Muted,
                    filled = false,
                    clock = investedTime.isNotBlank(),
                    labelModifier = if (investedTime.isNotBlank()) Modifier.testTag(FocusInvestedTag) else Modifier,
                )
            }

            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .testTag(FocusContextTag)
                    .then(if (onContextClick != null) Modifier.clickable(onClickLabel = "Open WorkStream") { onContextClick() } else Modifier)
            ) {
                if (projectName.isNotBlank()) {
                    Text(projectName, color = Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(FocusProjectTag))
                }
                Text(headline, color = Ink, fontSize = 30.sp, lineHeight = 35.sp,
                    fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (taskName.isNotBlank()) {
                    Text(taskName, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(FocusTaskTag))
                }
            }

            Spacer(Modifier.height(15.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (timerContent != null) timerContent() else FlipTime(elapsedTime)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Text("•", color = Color(0xFF17895B), fontSize = 17.sp)
                Spacer(Modifier.width(7.dp))
                Text("CURRENT SESSION", color = Forest, fontSize = 11.sp,
                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp))
                Text("•", color = Color(0xFF17895B), fontSize = 17.sp)
            }

            if (motto != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                        .background(Color.White.copy(alpha = .72f))
                        .border(1.dp, Color.White, RoundedCornerShape(17.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✦", color = Forest, fontSize = 17.sp)
                    Spacer(Modifier.width(9.dp))
                    Text(motto, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(12.dp))
            if (nextAction.isNotBlank()) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp))
                    .background(Color.White.copy(alpha = .80f))
                    .border(1.dp, Color.White, RoundedCornerShape(17.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Forest)
                    .padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text("NEXT", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Text(nextAction, Modifier.weight(1f), color = Ink,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(5.dp))
                Text(nextEstimate, color = Muted, fontSize = 11.sp,
                    maxLines = 1)
            }

            Spacer(Modifier.height(13.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FocusAction("LEAVE", onLeave, false, Modifier.weight(1f).testTag(FocusLeaveTag))
                FocusAction(
                    primaryLabel, onComplete, true,
                    Modifier.weight(1f).testTag(if (primaryLabel == "COMPLETE") FocusCompleteTag else FocusHandOffTag)
                )
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    dot: Color,
    filled: Boolean,
    /** Draw a small clock instead of the status dot (used by the invested-time pill). */
    clock: Boolean = false,
    labelModifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape)
            .background(if (filled) Color(0xFFDDF7DA) else Color.White.copy(alpha = .62f))
            .border(if (filled) 0.dp else 1.dp, Color.White, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (clock) {
            Icon(
                Icons.Outlined.Schedule, contentDescription = null, tint = dot,
                modifier = Modifier.size(12.dp)
            )
        } else {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dot))
        }
        Spacer(Modifier.width(6.dp))
        Text(label, color = Forest, fontSize = 10.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, modifier = labelModifier)
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
private fun FocusAction(label: String, onClick: () -> Unit, primary: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.height(48.dp).clip(shape)
            .background(if (primary) Forest else Color.White.copy(alpha = .82f))
            .border(1.dp, if (primary) Forest else Color(0xFF72BC8C), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color.White else Forest,
            fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
