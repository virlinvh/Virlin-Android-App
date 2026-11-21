package com.virlin.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
enum class NeedsYouSortMode(val label: String, val icon: ImageVector) {
    /** Canonical: effective rank ascending (the default and the authoritative order). */
    PRIORITY("Priority", Icons.Rounded.Flag),
    /** Earliest `dueAt` first — the item that has been waiting longest. */
    LONGEST_WAITING("Longest waiting", Icons.Rounded.HourglassBottom),
    /** Latest `dueAt` first — the item that most recently became due. */
    MOST_RECENT("Most recent", Icons.Rounded.Update),
    /** Items of one project together, projects by name, canonical rank inside each. */
    BY_PROJECT("By project", Icons.Rounded.FolderOpen);

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
     * deterministic for any input and identical on every recomposition. [projectNames] (project id
     * to title) only matters for [NeedsYouSortMode.BY_PROJECT]; items without a known project go last.
     */
    fun display(
        queue: List<NeedsYouOrder.Entry>,
        mode: NeedsYouSortMode,
        projectNames: Map<String, String> = emptyMap(),
    ): List<NeedsYouOrder.Entry> = when (mode) {
        NeedsYouSortMode.PRIORITY -> queue.sortedWith(compareBy({ it.rank }, { it.stream.id }))
        NeedsYouSortMode.LONGEST_WAITING -> queue.sortedWith(
            compareBy<NeedsYouOrder.Entry> { dueAt(it) }.thenBy { it.rank }.thenBy { it.stream.id }
        )
        NeedsYouSortMode.MOST_RECENT -> queue.sortedWith(
            compareByDescending<NeedsYouOrder.Entry> { dueAt(it) }.thenBy { it.rank }.thenBy { it.stream.id }
        )
        NeedsYouSortMode.BY_PROJECT -> queue.sortedWith(
            compareBy<NeedsYouOrder.Entry> { projectNames[it.stream.projectId] == null }
                .thenBy { projectNames[it.stream.projectId]?.lowercase() }
                .thenBy { it.stream.projectId }
                .thenBy { it.rank }.thenBy { it.stream.id }
        )
    }

    /** Timestamps only — never the formatted timer string. Falls back to the waiting basis. */
    private fun dueAt(entry: NeedsYouOrder.Entry): java.time.Instant =
        entry.stream.checkAt ?: NeedsYouOrder.waitingSince(entry.stream)
}

/**
 * The header control: one quiet `tune` glyph beside "Needs You", with a small dot when a
 * non-default view is active. Tapping shows or hides [NeedsYouSortStrip] under the header.
 */
@Composable
fun NeedsYouSortControl(
    mode: NeedsYouSortMode,
    expanded: Boolean,
    enabled: Boolean = true,
    onExpandedChange: (Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") onSelect: (NeedsYouSortMode) -> Unit = {},
    /** Glyph tint for the default view; null keeps the muted charcoal. */
    tint: Color? = null
) {
    val accent = tint ?: Charcoal
    Box(
        modifier = Modifier
            .size(40.dp)
            .testTag(NeedsYouSortControlTag)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button,
                onClickLabel = if (expanded) "Hide sort options" else "Show sort options") { onExpandedChange(!expanded) }
            .semantics {
                contentDescription =
                    if (mode.isDefault) "Sort Needs You" else "Sort Needs You. ${mode.label} selected."
            },
        contentAlignment = Alignment.Center
    ) {
        if (expanded) Box(Modifier.size(32.dp).background(accent.copy(alpha = .10f), CircleShape))
        // Glyph + (when a non-default view is active) a small dot tucked against it.
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                Icons.Rounded.Tune, contentDescription = null,
                tint = if (mode.isDefault) tint ?: CharcoalMuted else Charcoal,
                modifier = Modifier.size(18.dp).padding(top = 3.dp, end = 3.dp)
            )
            if (!mode.isDefault) Box(Modifier.size(5.dp).background(Charcoal, CircleShape))
        }
    }
}

/**
 * The sort options as a strip under the Needs You header, like the app's other tool strips:
 * bordered chips separated by hairline bars, scrolling sideways under feathered edges. Picking
 * an option applies it and folds the strip away (a view preference needs no Save).
 */
@Composable
fun NeedsYouSortStrip(
    visible: Boolean,
    mode: NeedsYouSortMode,
    onSelect: (NeedsYouSortMode) -> Unit,
    onClose: () -> Unit,
    accent: Color = Charcoal,
) {
    val context = LocalContext.current
    val reducedMotion = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val ms = if (reducedMotion) 0 else 220
    AnimatedVisibility(visible,
        enter = expandVertically(tween(ms)) + fadeIn(tween(ms)),
        exit = shrinkVertically(tween(ms)) + fadeOut(tween(ms))) {
        val shape = RoundedCornerShape(22.dp)
        Box(
            Modifier.padding(top = 8.dp).fillMaxWidth().height(52.dp)
                .clip(shape).background(Color.White)
                .border(1.dp, Color(0xFFEFE8FF), shape)
                .testTag(NeedsYouSortMenuTag)
        ) {
            com.virlin.app.ui.notes.NoteFeatheredRow(Modifier.fillMaxWidth().height(52.dp), peek = true) {
                NeedsYouSortMode.entries.forEachIndexed { index, option ->
                    if (index > 0) Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(22.dp)
                        .background(Color(0xFFE6DEF7)))
                    SortChip(option, option == mode, accent) { onSelect(option); onClose() }
                }
            }
        }
    }
}

@Composable
private fun SortChip(option: NeedsYouSortMode, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = .12f) else Color.White)
            .border(1.dp, if (selected) accent.copy(alpha = .38f) else Color(0xFFEDE7F6), shape)
            .testTag(needsYouSortOptionTag(option))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = option.label; this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(option.icon, null, tint = if (selected) accent else CharcoalMuted, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(option.label, fontSize = 13.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else Charcoal)
    }
}
