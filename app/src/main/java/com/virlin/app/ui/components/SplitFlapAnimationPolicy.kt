package com.virlin.app.ui.components

/**
 * Split-flap animation eligibility from **elapsed seconds**, not from how many glyphs change.
 *
 * - First value / unknown previous → snap (baseline)
 * - [previous] + 1 → flip (normal tick; includes 09→10 and 59→60)
 * - Any other delta → snap (rebase / identity / hydration jump)
 */
fun splitFlapShouldAnimate(previousSeconds: Int?, nextSeconds: Int): Boolean =
    previousSeconds != null && nextSeconds == previousSeconds + 1
