package com.virlin.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * # Virlin design tokens — canonical home for approved values.
 *
 * ## Status: FOUNDATION ONLY. Nothing has been migrated yet.
 *
 * Every value here is transcribed **exactly** from the approved, currently-rendered UI.
 * No call site has been changed, so this file cannot alter Virlin's appearance — it exists
 * so future features can say `VirlinColors.FocusSurface` instead of restating hex values.
 *
 * ### Why values were copied rather than moved
 *
 * The approved colours currently live as package-level `val`s at the top of
 * `ui/screens/NowScreen.kt`. Because Kotlin resolves same-package declarations ahead of
 * star-imports, those — not the ones in `ui/theme/Color.kt` — are what actually render.
 * Several names in `Color.kt` are stale and differ from what ships:
 *
 * | Concept        | Color.kt (stale)         | NowScreen.kt (RENDERS)      |
 * |----------------|--------------------------|------------------------------|
 * | Needs You due  | `NeedsYouYellow` #FEF3C7 | `NeedsYouYellowBg` #FFFDF4  |
 * | Needs You late | `NeedsYouCoral` #FFEDD5  | `NeedsYouCoralBg` #FFF7F2   |
 *
 * The tokens below take the **NowScreen values**, i.e. the approved ones. Migrating the
 * call sites is deliberately deferred: touching the frozen Now surface to swap references
 * risks silently changing it, which the project rules forbid.
 *
 * ### Future migration (needs explicit approval, one surface at a time)
 *
 * 1. Point a single surface at these tokens.
 * 2. Run `gradlew.bat verifyRoborazziDebug` — the golden must still pass unchanged.
 * 3. Only then move to the next surface.
 * 4. Delete the duplicated `val`s in `NowScreen.kt` / stale entries in `Color.kt` last.
 *
 * Do not bulk-migrate. Do not add speculative tokens — only values the approved UI uses.
 */
object VirlinColors {

    // ---- Surfaces ----
    /** App background / pearl. */
    val Background = Color(0xFFF8F7F4)

    /** Current Focus hero card. */
    val FocusSurface = Color(0xFFDDF4C7)

    /** Needs You — due (warm yellow). */
    val NeedsYouDue = Color(0xFFFFFDF4)
    val NeedsYouDueBorder = Color(0xFFFFE29A)

    /** Needs You — overdue (warm coral). Attention, never "error". */
    val NeedsYouOverdue = Color(0xFFFFF7F2)
    val NeedsYouOverdueBorder = Color(0xFFFFD0BB)

    /** Working For You — autonomous background processing. */
    val ProcessingSurface = Color(0xFFF2F1F8)
    val ProcessingBorder = Color(0xFFE2DFED)
    val ProcessingRowBorder = Color(0xFFE8E6F0)

    /** When You're Free — ready work. */
    val ReadySurface = Color(0xFFE8F6EE)
    val ReadyBorder = Color(0xFFCEEBD9)

    // ---- Text ----
    val TextPrimary = Color(0xFF162016)
    val TextPrimaryDark = Color(0xFF111711)
    val TextSecondary = Color(0xFF525B54)
    val TextTertiary = Color(0xFF859088)

    // ---- Accents ----
    val Emerald = Color(0xFF10B981)
    val Violet = Color(0xFF7C3AED)
    val Amber = Color(0xFFF59E0B)

    /** Live/online status dot in the header. */
    val StatusOnline = Color(0xFF34C759)

    // ---- Fullscreen Focus Clock (its own deliberate palette; shares nothing with Now) ----
    val ClockCanvas = Color.Black
    val ClockCardSurface = Color(0xFF212121)
    val ClockCardShadow = Color(0xFF0E0E0E)
    val ClockNumeral = Color(0xFFF0EFEA)
    val ClockColonDot = Color(0xFFBFBFBF)
}

/**
 * Spacing values the approved layouts already use. Transcribed, not invented.
 */
object VirlinSpacing {
    /** Horizontal padding of the Now screen content column. */
    val ScreenHorizontal = 16.dp

    /** Internal padding of the Focus hero card. */
    val CardPadding = 20.dp

    /** Gap between major Now sections. */
    val SectionGap = 24.dp

    /** Gap between a heading and its content. */
    val BlockGap = 14.dp

    /** Tight gap inside a row/cluster. */
    val InlineGap = 6.dp

    /** Floating bottom navigation height. */
    val NavBarHeight = 64.dp
}

/**
 * Corner radii the approved surfaces already use.
 */
object VirlinShapes {
    /** Focus hero card. */
    val FocusCard = RoundedCornerShape(30.dp)

    /** Standard content card (Needs You, Working For You rows). */
    val Card = RoundedCornerShape(16.dp)

    /** Split-flap timer housing on Now. */
    val TimerHousing = RoundedCornerShape(22.dp)

    /** Floating bottom navigation bar. */
    val NavBar = RoundedCornerShape(32.dp)

    /** Badge / status pill. */
    val Pill = RoundedCornerShape(50)
}

/**
 * Motion durations and easings already in use.
 *
 * Motion must communicate state — see the motion rules in the `virlin-motion-interaction`
 * skill. Values here are descriptive of shipped behaviour, not aspirational.
 */
object VirlinMotion {
    /** Immediate touch feedback, e.g. press scale on the Now timer and close controls. */
    const val FastInteractionMs = 120

    /** Press scale target for a large surface (Now flip timer). */
    const val PressScaleLarge = 0.98f

    /** Press scale target for a small control (close button, Orb). */
    const val PressScaleSmall = 0.96f

    /** Screen/state entry, e.g. entering the fullscreen Focus Clock. */
    const val StateTransitionInMs = 220

    /** Screen/state exit. */
    const val StateTransitionOutMs = 180

    /** One physical split-flap card rotation. Shared by Now and the Focus Clock. */
    const val SplitFlapFlipMs = 500

    /** Calm idle breathing cycle (Orb). */
    const val BreathingMs = 3_000

    val Standard: Easing = FastOutSlowInEasing

    /** Decelerating settle used by the split-flap second half. */
    val Settle: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    // NOTE: Orb interaction-state motion (Receiving / Understanding / Acting / Success /
    // Speaking / Error) is specified but NOT yet implemented. Those tokens are deliberately
    // absent — add them in the Living Orb Interaction task, once the real values exist.
}
