package com.virlin.app.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private val Ink: Color @Composable get() = NotesInk
private val Green: Color @Composable get() = NotesAccent
private val Border: Color @Composable get() = NotesBorder
private val Muted: Color @Composable get() = NotesMuted

internal const val NOTE_MIN_TEXT_SIZE = 8
internal const val NOTE_MAX_TEXT_SIZE = 96

/**
 * Inline text-size control: ▲ above the number, ▼ below it. Tap an arrow to step by one, drag
 * up/down (or turn a mouse wheel) over the control to step continuously, or tap the number and
 * type a size. [onEditStart] fires before the typing field takes focus so the caller can keep
 * the editor's selection; [onSet] receives the typed value, already clamped.
 */
@Composable
internal fun NoteSizeStepper(
    value: Int,
    enabled: Boolean,
    onStep: (Int) -> Unit,
    onEditStart: () -> Unit,
    onSet: (Int) -> Unit,
    modifier: Modifier = Modifier,
    framed: Boolean = true,
    tag: String = "notes_size",
) {
    val haptics = LocalHapticFeedback.current
    val stepPx = with(LocalDensity.current) { 14.dp.toPx() }
    val step by rememberUpdatedState { delta: Int ->
        val next = (value + delta).coerceIn(NOTE_MIN_TEXT_SIZE, NOTE_MAX_TEXT_SIZE)
        if (next != value) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onStep(next - value)
        }
    }
    var editing by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val tint = if (enabled) Ink else Muted

    Box(
        modifier.width(48.dp).height(44.dp).clip(shape).background(Color.White)
            .border(1.dp, when {
                editing -> Green.copy(alpha = .6f)
                framed -> Border
                else -> Color.Transparent
            }, shape)
            .semantics {
                contentDescription = "Text size $value"
                if (enabled) customActions = listOf(
                    CustomAccessibilityAction("Increase text size") { step(1); true },
                    CustomAccessibilityAction("Decrease text size") { step(-1); true },
                )
            }
            .testTag(tag)
            .then(if (!enabled || editing) Modifier else Modifier
                .pointerInput(Unit) {
                    var travelled = 0f
                    detectVerticalDragGestures(onDragStart = { travelled = 0f }) { change, dy ->
                        change.consume()
                        travelled += dy
                        while (abs(travelled) >= stepPx) {
                            val up = travelled < 0
                            step(if (up) 1 else -1)
                            travelled += if (up) stepPx else -stepPx
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) {
                                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                if (dy != 0f) step(if (dy < 0f) 1 else -1)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }),
        contentAlignment = Alignment.Center,
    ) {
        if (editing) SizeField(value, onDone = { typed ->
            editing = false
            typed?.let { onSet(it.coerceIn(NOTE_MIN_TEXT_SIZE, NOTE_MAX_TEXT_SIZE)) }
        }, tag = "${tag}_field")
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(14.dp)
                .clickable(enabled = enabled && value < NOTE_MAX_TEXT_SIZE, role = Role.Button) { step(1) }
                .testTag("${tag}_up"), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null, tint = tint,
                    modifier = Modifier.size(14.dp))
            }
            Text("$value",
                Modifier.fillMaxWidth().height(16.dp)
                    .clickable(enabled = enabled, role = Role.Button) { onEditStart(); editing = true }
                    .testTag("${tag}_value"),
                color = tint, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 1)
            Box(Modifier.fillMaxWidth().height(14.dp)
                .clickable(enabled = enabled && value > NOTE_MIN_TEXT_SIZE, role = Role.Button) { step(-1) }
                .testTag("${tag}_down"), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = tint,
                    modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Numeric entry; commits on Done or when focus leaves, null when the text is not a number. */
@Composable
private fun SizeField(initial: Int, onDone: (Int?) -> Unit, tag: String) {
    var text by remember { mutableStateOf(TextFieldValue("$initial", TextRange(0, "$initial".length))) }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var hadFocus by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val finish = { if (!finished) { finished = true; onDone(text.text.toIntOrNull()) } }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    BasicTextField(
        value = text,
        onValueChange = { next -> if (next.text.length <= 2 && next.text.all(Char::isDigit)) text = next },
        singleLine = true,
        textStyle = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(Green),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { finish(); focusManager.clearFocus() }),
        modifier = Modifier.width(40.dp).focusRequester(requester)
            .onFocusChanged { if (it.isFocused) hadFocus = true else if (hadFocus) finish() }
            .semantics { contentDescription = "Type text size" }
            .testTag(tag),
    )
}
