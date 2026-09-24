package com.virlin.app.ui.screens

import androidx.compose.ui.graphics.Color

/**
 * Needs You attention system — Phase 2: ONE derived urgency classification.
 *
 * `UrgencyLevel` is a pure function of the waiting duration (the same `now − waitingSince`
 * that drives the live timer). There is no separate colour state and no colour timer: the
 * palette, the status indicator and the glow strength are all looked up from the level.
 */
enum class UrgencyLevel(val rank: Int) {
    /** 0:00 – 0:59 */ ATTENTION(1),
    /** 1:00 – 2:59 */ WAITING(2),
    /** 3:00 – 4:59 */ ELEVATED(3),
    /** 5:00 – 9:59 */ HIGH(4),
    /** 10:00+      */ CRITICAL(5);

    companion object {
        /** Deterministic thresholds in whole seconds: 60 / 180 / 300 / 600. */
        fun of(elapsedSeconds: Long): UrgencyLevel = when {
            elapsedSeconds < 60 -> ATTENTION
            elapsedSeconds < 180 -> WAITING
            elapsedSeconds < 300 -> ELEVATED
            elapsedSeconds < 600 -> HIGH
            else -> CRITICAL
        }
    }
}

/**
 * Coordinated colours for one urgency level. Light, low-saturation surfaces; the strongest
 * colour lives in the small indicator / glow, never in a solid card fill.
 */
data class UrgencyPalette(
    /** Resting card surface. */
    val cardBg: Color,
    /** Card surface at the peak of the existing slow attention pulse. */
    val cardBgAttention: Color,
    /** Resting border. */
    val border: Color,
    /** Border at the peak of the attention pulse. */
    val borderPeak: Color,
    /** Timer chip background. */
    val chipBg: Color,
    /** Timer chip text. */
    val chipFg: Color,
    /** Timer chip border. */
    val chipBorder: Color,
    /** Left status indicator (beacon core). */
    val indicator: Color,
    /** Outer attention glow colour. */
    val glow: Color,
    /** Peak glow alpha for this level (0 = none). */
    val glowStrength: Float
)

object NeedsYouUrgency {

    // Level 1 — soft warm yellow (the approved "due now" surface, unchanged).
    private val attention = UrgencyPalette(
        cardBg = Color(0xFFFFFDF4), cardBgAttention = Color(0xFFFEF6C8),
        border = Color(0xFFFFE29A), borderPeak = Color(0xFFFACC15),
        chipBg = Color(0xFFFEF3C7), chipFg = Color(0xFF92400E), chipBorder = Color(0xFFFDE68A),
        indicator = Color(0xFFFFC928), glow = Color(0xFFF8D66B), glowStrength = 0.08f
    )
    // Level 2 — gold / amber.
    private val waiting = UrgencyPalette(
        cardBg = Color(0xFFFFFBEE), cardBgAttention = Color(0xFFFDEFC2),
        border = Color(0xFFFBD98A), borderPeak = Color(0xFFF0B429),
        chipBg = Color(0xFFFDEFC2), chipFg = Color(0xFF8A5A00), chipBorder = Color(0xFFF7DC9B),
        indicator = Color(0xFFEFB13A), glow = Color(0xFFF2B84B), glowStrength = 0.13f
    )
    // Level 3 — warm orange (the approved "overdue" surface, unchanged).
    private val elevated = UrgencyPalette(
        cardBg = Color(0xFFFFF7F2), cardBgAttention = Color(0xFFFFE8DA),
        border = Color(0xFFFFD0BB), borderPeak = Color(0xFFFB923C),
        chipBg = Color(0xFFFFEDD5), chipFg = Color(0xFF9A3412), chipBorder = Color(0xFFFED7AA),
        indicator = Color(0xFFFF7A45), glow = Color(0xFFFFA366), glowStrength = 0.18f
    )
    // Level 4 — deep orange / coral.
    private val high = UrgencyPalette(
        cardBg = Color(0xFFFFF4EF), cardBgAttention = Color(0xFFFFE1D4),
        border = Color(0xFFFFBFA6), borderPeak = Color(0xFFFF7A50),
        chipBg = Color(0xFFFFDDD0), chipFg = Color(0xFFA2330F), chipBorder = Color(0xFFFFC4AE),
        indicator = Color(0xFFFF6F4A), glow = Color(0xFFFF8A6B), glowStrength = 0.23f
    )
    // Level 5 — restrained urgent red / coral (never a saturated fill).
    private val critical = UrgencyPalette(
        cardBg = Color(0xFFFFF2F0), cardBgAttention = Color(0xFFFFDCD6),
        border = Color(0xFFF9B4AA), borderPeak = Color(0xFFF26A5E),
        chipBg = Color(0xFFFFD9D3), chipFg = Color(0xFF9B2C22), chipBorder = Color(0xFFF8BDB4),
        indicator = Color(0xFFF0574A), glow = Color(0xFFF26A5E), glowStrength = 0.28f
    )

    fun palette(level: UrgencyLevel): UrgencyPalette = when (level) {
        UrgencyLevel.ATTENTION -> attention
        UrgencyLevel.WAITING -> waiting
        UrgencyLevel.ELEVATED -> elevated
        UrgencyLevel.HIGH -> high
        UrgencyLevel.CRITICAL -> critical
    }

    /** Living-glow breathing cycle: 2.4–3.5 s, slightly quicker as urgency rises. */
    fun glowCycleMillis(level: UrgencyLevel): Int = when (level) {
        UrgencyLevel.ATTENTION -> 3400
        UrgencyLevel.WAITING -> 3200
        UrgencyLevel.ELEVATED -> 3000
        UrgencyLevel.HIGH -> 2800
        UrgencyLevel.CRITICAL -> 2600
    }

    /** Deterministic per-item phase offset so cards do not breathe as one flashing wall. */
    fun glowPhaseOffsetMillis(index: Int): Int = (index * 700) % 2100

    /**
     * Glow alpha for a level at a breathing position `breath` ∈ [0, 1].
     * With reduced motion the glow is a STATIC halo at half strength — still visible, never
     * animated — so hierarchy remains readable without motion.
     */
    fun glowAlpha(level: UrgencyLevel, breath: Float, reducedMotion: Boolean): Float {
        val peak = palette(level).glowStrength
        if (reducedMotion) return peak * 0.5f
        // Breathe between 35 % and 100 % of the level's peak: perceptible, never off, never abrupt.
        return peak * (0.35f + 0.65f * breath.coerceIn(0f, 1f))
    }
}
