package com.virlin.app.ui.screens

/*
 * Needs You rank badge identity helpers.
 *
 * The badge itself is drawn by `NeedsYouCard` (Phase 01 geometry, Phase 02 colours) and opens the
 * Phase 04 priority editor. Only the shared test tag and the spoken ordinal live here.
 */

fun needsYouRankTag(streamId: String) = "needs_you_rank_$streamId"

/** Spoken ordinal for a position ("First" … "Tenth", then "Position 11"). */
fun positionWord(position: Int): String = when (position) {
    1 -> "First"; 2 -> "Second"; 3 -> "Third"; 4 -> "Fourth"; 5 -> "Fifth"
    6 -> "Sixth"; 7 -> "Seventh"; 8 -> "Eighth"; 9 -> "Ninth"; 10 -> "Tenth"
    else -> "Position $position"
}
