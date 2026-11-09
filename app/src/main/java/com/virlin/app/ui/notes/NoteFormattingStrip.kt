package com.virlin.app.ui.notes

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FormatAlignJustify
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class NoteFormattingStripState(
    val settings: NotePrintSettings,
    val onChange: (NotePrintSettings) -> Unit,
    /** Pages the note fills; null while counting. */
    val pageCount: Int?,
    /** Turns page formatting off: the note goes back to its normal, page-less view. */
    val onClose: () -> Unit,
)

/**
 * The formatting strip under the rail: auto-fit, paper, layout, margins, page numbers and title in one
 * small row that scrolls sideways. Every choice reshapes the pages in the editor at once, and
 * printing and exporting follow the same pages.
 */
@Composable
internal fun NoteFormattingStrip(state: NoteFormattingStripState, modifier: Modifier = Modifier) {
    val s = state.settings
    Box(modifier.fillMaxWidth().background(Color.White).testTag("notes_formatting_strip")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(NotesWash), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Article, contentDescription = null, tint = NotesAccent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(7.dp))
            Column(Modifier.widthIn(min = 52.dp)) {
                Text("Pages", color = NotesInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val pages = state.pageCount?.let { if (it == 1) "1 page" else "$it pages" } ?: "Counting…"
                AnimatedContent(pages, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
                    label = "formattingPages") { text ->
                    Text(text, color = NotesMuted, fontSize = 11.sp, maxLines = 1,
                        modifier = Modifier.testTag("notes_formatting_pages"))
                }
            }
            Spacer(Modifier.width(4.dp))
            NoteFeatheredRow(Modifier.weight(1f).height(56.dp), peek = true) {
                AutoFitToggle(s.autoFit) { state.onChange(s.copy(autoFit = it)) }
                NoteStripChoice("Paper", NotePaper.offered, s.paper, { it.label }, "notes_format") {
                    state.onChange(s.copy(paper = it))
                }
                NoteStripChoice("Layout", listOf(false, true), s.landscape, { if (it) "Landscape" else "Portrait" },
                    "notes_format") { state.onChange(s.copy(landscape = it)) }
                NoteStripChoice("Margins", NoteMargins.entries, s.margins, { it.label }, "notes_format") {
                    state.onChange(s.copy(margins = it))
                }
                NoteStripChoice("Page numbers", NotePageNumbers.entries, s.pageNumbers, { it.label }, "notes_format") {
                    state.onChange(s.copy(pageNumbers = it))
                }
                if (s.pageNumbers != NotePageNumbers.OFF) {
                    NoteStripChoice("Position", NoteNumberAlign.entries, s.numberAlign, { it.label }, "notes_format") {
                        state.onChange(s.copy(numberAlign = it))
                    }
                }
                NoteStripChoice("Title", listOf(true, false), s.includeTitle, { if (it) "Show" else "Hide" },
                    "notes_format", last = true) { state.onChange(s.copy(includeTitle = it)) }
            }
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = state.onClose)
                    .semantics { contentDescription = "Turn off page formatting" }
                    .testTag("notes_formatting_close"),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = null, tint = NotesMuted, modifier = Modifier.size(20.dp)) }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(NotesBorder))
    }
}

/** Auto-fit on or off: content flows continuously across the pages instead of keeping blocks whole. */
@Composable
private fun AutoFitToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val fill by animateColorAsState(if (on) NotesAccent.copy(alpha = .12f) else Color.White, tween(160),
        label = "autoFit")
    val tint = if (on) NotesAccent else NotesInk
    Row(
        Modifier.height(34.dp).clip(shape).border(1.dp, if (on) NotesAccent.copy(alpha = .5f) else NotesBorder, shape)
            .background(fill)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = "Auto-fit pages" }
            .testTag("notes_format_auto_fit")
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FormatAlignJustify, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("Auto-fit", color = tint, fontSize = 13.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
    Spacer(Modifier.width(14.dp))
}

/**
 * A row that scrolls sideways under soft white edges, like the formatting rail, so there is
 * always a sense of more to either side. [peek] nudges it once on open to show it scrolls.
 */
@Composable
internal fun NoteFeatheredRow(
    modifier: Modifier = Modifier,
    peek: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val peekPx = with(LocalDensity.current) { 44.dp.toPx() }.toInt()
    if (peek && !reducedMotion) LaunchedEffect(Unit) {
        delay(420)
        if (scroll.value == 0 && scroll.maxValue > 0) {
            scroll.animateScrollTo(minOf(peekPx, scroll.maxValue), tween(380))
            scroll.animateScrollTo(0, tween(460))
        }
    }
    Box(modifier) {
        Row(Modifier.fillMaxSize().horizontalScroll(scroll).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
        // Edge paint is visual only; pointer events pass to the scrollable row.
        Canvas(Modifier.fillMaxSize()) {
            val feather = 18.dp.toPx()
            val steps = 12
            if (scroll.value > 0) for (i in 0 until steps) {
                val alpha = (1f - i.toFloat() / steps) * .88f
                drawRect(Color.White.copy(alpha = alpha), Offset(i * feather / steps, 0f),
                    Size(feather / steps + 1f, size.height))
            }
            if (scroll.value < scroll.maxValue) for (i in 0 until steps) {
                val alpha = (1f - i.toFloat() / steps) * .88f
                drawRect(Color.White.copy(alpha = alpha), Offset(size.width - (i + 1) * feather / steps, 0f),
                    Size(feather / steps + 1f, size.height))
            }
        }
    }
}

/** A caption and a segmented choice; the selected segment is tinted with the note's accent. */
@Composable
internal fun <T> NoteStripChoice(
    caption: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    tagPrefix: String,
    last: Boolean = false,
    onPick: (T) -> Unit,
) {
    Text(caption, color = NotesMuted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(end = 6.dp))
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.height(34.dp).clip(shape).border(1.dp, NotesBorder, shape)) {
        options.forEachIndexed { index, option ->
            val on = option == selected
            val fill by animateColorAsState(if (on) NotesAccent.copy(alpha = .12f) else Color.White, tween(160),
                label = "stripChoice")
            if (index > 0) Box(Modifier.width(1.dp).height(34.dp).background(NotesBorder))
            Box(
                Modifier.height(34.dp)
                    .background(fill)
                    .clickable(role = Role.RadioButton) { onPick(option) }
                    .semantics { contentDescription = "$caption ${label(option)}"; this.selected = on; role = Role.RadioButton }
                    .testTag("${tagPrefix}_${caption.lowercase().replace(' ', '_')}_${label(option).lowercase().replace(' ', '_')}")
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), color = if (on) NotesAccent else NotesInk, fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
    if (!last) Spacer(Modifier.width(14.dp))
}
