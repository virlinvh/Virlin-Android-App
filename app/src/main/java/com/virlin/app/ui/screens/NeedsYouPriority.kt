package com.virlin.app.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.virlin.app.ui.theme.Charcoal

/**
 * Needs You PRIORITY VISUALS — the ONE source of truth for a card's colour family (Phase 02).
 *
 * A card never picks colours itself: it asks [NeedsYouPriority.visualsFor] for its rank and uses
 * the returned identity for the badge, the contextual icon, the border, the surface tint, the
 * timer and the CHECK action. Changing a card's rank therefore changes every one of those at once.
 *
 * Rank 1–10 carry the approved accent progression (strongest red → pale yellow); rank 11 and above,
 * and any invalid rank (0, negative, absent), fall back to the neutral identity. Nothing is
 * interpolated past rank 10.
 *
 * Priority ≠ urgency. The rank identity owns the card's colour; the HH:MM:SS timer owns temporal
 * state (see `WaitingTime` / `UrgencyLevel`, unchanged).
 */
data class PriorityVisuals(
    /** The rank identity itself — badge fill, icon symbol, timer text, CHECK text. */
    val accent: Color,
    /** Very pale card surface derived from [accent]. */
    val surface: Color,
    /** Restrained 1dp card border derived from [accent]. */
    val border: Color,
    /** Pale container behind the contextual icon / CHECK pill. */
    val container: Color,
    /** Readable foreground ON [accent] (dark on pale yellows, white on strong reds). */
    val onAccent: Color,
    /** Primary text colour on the card surface. */
    val content: Color,
    /** Secondary text colour on the card surface. */
    val subtleContent: Color,
    /** True for ranks outside 1..10 — the neutral, colourless identity. */
    val neutral: Boolean
)

object NeedsYouPriority {

    /** Approved rank accents, index 0 = rank 1. RED → RED-ORANGE → AMBER → YELLOW → PALE YELLOW. */
    val accents: List<Color> = listOf(
        Color(0xFFD93636), Color(0xFFE64A35), Color(0xFFEF6332),
        Color(0xFFE89B00), Color(0xFFEBAF00), Color(0xFFE8C400),
        Color(0xFFE6D43A), Color(0xFFE8E05A), Color(0xFFF1EA8E), Color(0xFFF7F5BC)
    )

    /** Neutral identity for rank 11+, unranked or invalid rank: white card, quiet ink. */
    private val neutral = PriorityVisuals(
        accent = Color(0xFF6B7280),
        surface = Color.White,
        border = Color(0xFFE6E9ED),
        container = Color(0xFFF3F4F6),
        onAccent = Color.White,
        content = Charcoal,
        subtleContent = Charcoal.copy(alpha = 0.55f),
        neutral = true
    )

    /** How many ranks carry a priority colour; beyond this everything is [neutral]. */
    const val coloredRanks = 10

    private val cache: List<PriorityVisuals> = accents.map(::derive)

    /** Visual identity for a 1-based rank. Null / 0 / negative / > [coloredRanks] → neutral. */
    fun visualsFor(rank: Int?): PriorityVisuals =
        if (rank == null || rank < 1 || rank > coloredRanks) neutral else cache[rank - 1]

    /**
     * Everything except the accent is DERIVED from it, so the family can never mismatch:
     * surface = 8% accent over white, container = 22%, border = 38%; the foreground on the accent
     * is dark or white by the accent's luminance, so pale yellows stay readable.
     */
    private fun derive(accent: Color) = PriorityVisuals(
        accent = accent,
        surface = accent.copy(alpha = 0.08f).compositeOver(Color.White),
        border = accent.copy(alpha = 0.38f).compositeOver(Color.White),
        container = accent.copy(alpha = 0.22f).compositeOver(Color.White),
        onAccent = if (accent.luminance() > 0.45f) Charcoal else Color.White,
        content = Charcoal,
        subtleContent = Charcoal.copy(alpha = 0.62f),
        neutral = false
    )

    /**
     * Timer / CHECK text sits on a pale container, not on the accent, so a pale-yellow accent stays
     * legible: the accent is darkened towards Charcoal until it reads on that container.
     */
    fun readableInk(v: PriorityVisuals): Color =
        if (v.neutral) Charcoal.copy(alpha = 0.75f)
        else if (v.accent.luminance() > 0.45f) blend(v.accent, Charcoal, 0.55f) else blend(v.accent, Charcoal, 0.12f)

    private fun blend(a: Color, b: Color, f: Float) = Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f
    )
}
