package com.virlin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virlin.app.domain.attention.NeedsYouOrder
import com.virlin.app.ui.theme.Charcoal
import com.virlin.app.ui.theme.CharcoalMuted

/*
 * Needs You SORT / VIEW control (Phase 06).
 *
 * CANONICAL ORDER (the Phase 03 queue, and therefore every card's rank and Phase 02 colour) and
 * DISPLAY ORDER (how the user chooses to look at that queue right now) are different things. This
 * file owns only the second one: a pure projection plus a compact header control. Choosing a sort
 * never writes anything — no rank, no `dueAt`, no preference.
 */

/** How Needs You is currently displayed. Typed state — never a UI string. */
enum class NeedsYouSortMode(val label: String) {
    /** Canonical: effective rank ascending (the default and the authoritative order). */
    PRIORITY("Priority"),
    /** Earliest `dueAt` first — the item that has been waiting longest. */
    LONGEST_WAITING("Longest waiting"),
    /** Latest `dueAt` first — the item that most recently became due. */
    MOST_RECENT("Most recent");

    val isDefault: Boolean get() = this == PRIORITY
}

const val NeedsYouSortControlTag = "needs_you_sort_control"
const val NeedsYouSortMenuTag = "needs_you_sort_menu"
fun needsYouSortOptionTag(mode: NeedsYouSortMode) = "needs_you_sort_" + mode.name.lowercase()

object NeedsYouSort {

    /**
     * The display projection: the canonical queue re-ordered for viewing only. Every entry keeps
     * its canonical [NeedsYouOrder.Entry.rank], so a card's number and colour never follow its
     * position on screen.
     *
     * Ties (identical `dueAt`) fall back to canonical rank, then to the stable id, so the order is
     * deterministic for any input and identical on every recomposition.
     */
    fun display(queue: List<NeedsYouOrder.Entry>, mode: NeedsYouSortMode): List<NeedsYouOrder.Entry> = when (mode) {
        NeedsYouSortMode.PRIORITY -> queue.sortedWith(compareBy({ it.rank }, { it.stream.id }))
        NeedsYouSortMode.LONGEST_WAITING -> queue.sortedWith(
            compareBy<NeedsYouOrder.Entry> { dueAt(it) }.thenBy { it.rank }.thenBy { it.stream.id }
        )
        NeedsYouSortMode.MOST_RECENT -> queue.sortedWith(
            compareByDescending<NeedsYouOrder.Entry> { dueAt(it) }.thenBy { it.rank }.thenBy { it.stream.id }
        )
    }

    /** Timestamps only — never the formatted timer string. Falls back to the waiting basis. */
    private fun dueAt(entry: NeedsYouOrder.Entry): java.time.Instant =
        entry.stream.checkAt ?: NeedsYouOrder.waitingSince(entry.stream)
}

/**
 * The header control: one quiet `tune` glyph beside "Needs You", with a small dot when a
 * non-default view is active. Tapping opens a compact anchored menu; picking an option closes it
 * immediately (a view preference needs no Save).
 */
@Composable
fun NeedsYouSortControl(
    mode: NeedsYouSortMode,
    expanded: Boolean,
    enabled: Boolean = true,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (NeedsYouSortMode) -> Unit
) {
    Box {
        Box(
            modifier = Modifier
                .size(40.dp)
                .testTag(NeedsYouSortControlTag)
                .clickable(enabled = enabled, role = Role.Button) { onExpandedChange(true) }
                .semantics {
                    contentDescription =
                        if (mode.isDefault) "Sort Needs You" else "Sort Needs You. ${mode.label} selected."
                },
            contentAlignment = Alignment.Center
        ) {
            // Glyph + (when a non-default view is active) a small dot tucked against it.
            Box(contentAlignment = Alignment.TopEnd) {
                Icon(
                    Icons.Rounded.Tune, contentDescription = null,
                    tint = if (mode.isDefault) CharcoalMuted else Charcoal,
                    modifier = Modifier.size(18.dp).padding(top = 3.dp, end = 3.dp)
                )
                if (!mode.isDefault) Box(Modifier.size(5.dp).background(Charcoal, CircleShape))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.background(Color.White, RoundedCornerShape(16.dp)).testTag(NeedsYouSortMenuTag)
        ) {
            Text(
                "Sort Needs You", fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.7.sp,
                color = CharcoalMuted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
            )
            NeedsYouSortMode.entries.forEach { option ->
                val isSelected = option == mode
                Row(
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag(needsYouSortOptionTag(option))
                        .clickable(role = Role.RadioButton) { onSelect(option); onExpandedChange(false) }
                        .semantics { contentDescription = option.label; selected = isSelected }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(15.dp).background(Color.White, CircleShape)
                            .border(if (isSelected) 4.5.dp else 1.5.dp, if (isSelected) Charcoal else Charcoal.copy(alpha = 0.3f), CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        option.label, fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, color = Charcoal
                    )
                }
            }
        }
    }
}
